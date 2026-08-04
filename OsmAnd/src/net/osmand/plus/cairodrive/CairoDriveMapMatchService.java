package net.osmand.plus.cairodrive;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.routing.RoutingEnvironment;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.router.RouteCalculationProgress;
import net.osmand.router.RoutingContext;
import net.osmand.router.VehicleRouter;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * N6 glue: runs {@link CairoDriveMapMatcher} off the main thread, on bounded work per fix, and
 * writes {@code CD_MATCH}.
 *
 * <h3>Off the main thread, and provably so</h3>
 *
 * Everything past {@link #onLocation} happens on one dedicated {@link HandlerThread} at
 * {@link Process#THREAD_PRIORITY_BACKGROUND}. The caller's thread does three things and nothing
 * else: a volatile boolean read, a distance/time comparison against the last accepted fix, and a
 * {@code Handler.post}. That matters on this device specifically - a POCO C85 has two big cores
 * and the frame is already 46.9 ms, of which 25.9 ms is {@code drawOverMap}. There is no headroom
 * on the main thread and none is asked for.
 *
 * <h3>Bounded work per fix</h3>
 *
 * <ul>
 *   <li><b>Rate.</b> At most one fix per {@link CairoDriveMapMatching#MIN_INTERVAL_MS}, and only
 *       when the car has moved {@link CairoDriveMapMatching#MIN_STEP_M} or
 *       {@link CairoDriveMapMatching#MAX_STEP_MS} has passed.</li>
 *   <li><b>Backpressure.</b> Exactly one fix is ever in flight. If the worker is still busy the
 *       next fix is DROPPED, not queued. A queue would convert a slow patch into an ever-growing
 *       backlog of stale positions, which is the worst possible failure for a live display.</li>
 *   <li><b>Search.</b> At most {@link CairoDriveMapMatching#MAX_CANDIDATES} hypotheses, at most
 *       {@link CairoDriveMapMatching#MAX_ROUTE_DIST_NODES} settled nodes per bounded Dijkstra, at
 *       most {@link CairoDriveMapMatching#MAX_GRAPH_POINTS} points in the local graph.</li>
 *   <li><b>Memory.</b> The matcher's {@link RoutingContext} is its own, never the navigation one,
 *       and its tile cache is unloaded once it passes
 *       {@link CairoDriveMapMatching#MAX_LOADED_TILES}.</li>
 *   <li><b>Watchdog.</b> {@link CairoDriveMapMatching#MAX_CONSECUTIVE_SLOW} fixes over
 *       {@link CairoDriveMapMatching#SLOW_MATCH_MS} latch the whole feature off for the rest of
 *       the process, with the reason in the log. The fork has an explicit rule about measuring
 *       before optimising; this is the same rule pointed the other way - a feature that turns out
 *       to be expensive on the one real device removes itself rather than being defended.</li>
 * </ul>
 *
 * <h3>Off by default</h3>
 *
 * {@link CairoDriveMapMatching#isEnabled()} is false unless the build defines
 * {@code CAIRODRIVE_MAP_MATCHING}. Nothing is constructed, no thread is started and no routing
 * environment is built until the first fix arrives with the flag on.
 *
 * <h3>Display only - deliberately not wired to anything yet</h3>
 *
 * This service computes and logs. It changes no behaviour. That is the same order
 * {@link CairoDriveStationary} used, and for the same reason: {@code CD_MATCH} carries
 * {@code disagree=} on every fix, so the next drive answers "how often would this have moved the
 * car onto a different road, and was it right" BEFORE anything depends on the answer. Consuming
 * {@link #getLastMatch()} for the displayed position is a separate change, to be made once the
 * disagreement rate and the confidence distribution have been read off a real log.
 */
public class CairoDriveMapMatchService {

	private static volatile CairoDriveMapMatchService instance;

	private final OsmandApplication app;
	private final AtomicBoolean busy = new AtomicBoolean();

	private HandlerThread thread;
	private Handler handler;

	private CairoDriveMapMatcher matcher;
	private RoutingEnvironment environment;
	private long environmentBuiltAt;

	private volatile CairoDriveMapMatcher.Match lastMatch;
	/** The fix the match was computed FROM - the staleness test needs it, not the arrival time. */
	private volatile long lastMatchFixTime;
	private volatile double lastMatchLat;
	private volatile double lastMatchLon;

	// Sampling gate. Touched only by the caller's thread, guarded by the busy flag for publication.
	private double gateLat;
	private double gateLon;
	private long gateTime;
	private boolean gateValid;

	// Worker-thread-only counters.
	private int consecutiveSlow;
	private long summaryAt;
	private long detailAt;
	private int fixes;
	private int matched;
	private int disagreements;
	private int degradedFixes;
	private int noCandidate;
	private double confSum;
	private double offsetSum;
	private long msSum;
	private long msMax;

	private CairoDriveMapMatchService(@NonNull OsmandApplication app) {
		this.app = app;
	}

	@NonNull
	public static CairoDriveMapMatchService getInstance(@NonNull OsmandApplication app) {
		CairoDriveMapMatchService local = instance;
		if (local == null) {
			synchronized (CairoDriveMapMatchService.class) {
				local = instance;
				if (local == null) {
					local = new CairoDriveMapMatchService(app);
					instance = local;
				}
			}
		}
		return local;
	}

	/** The most recent match, or null. Safe from any thread; may be a fix or two behind. */
	@Nullable
	public CairoDriveMapMatcher.Match getLastMatch() {
		return CairoDriveMapMatching.isEnabled() ? lastMatch : null;
	}

	/**
	 * Corrects a location for DISPLAY, or returns it unchanged.
	 *
	 * <h3>Display only, and that separation is load-bearing</h3>
	 *
	 * The corrected position must never become the input to off-route detection, the ETA or route
	 * recalculation. A matcher that is wrong would then cause REROUTES, and a spurious reroute is
	 * far worse than an arrow a few metres off the true road. This mirrors what
	 * {@code CairoDriveStationary} already does: raw fixes for logic, corrected positions for what
	 * is drawn.
	 *
	 * <h3>Why staleness is the real hazard, not accuracy</h3>
	 *
	 * The matcher runs on a worker thread and DROPS fixes when it is busy, by design - a queue
	 * would turn a slow patch into a growing backlog of stale positions. So the newest match can
	 * easily be two fixes behind, and snapping the current position onto a road chosen for where
	 * the car was three seconds ago would put the arrow somewhere it has already left. Both gates
	 * below are about that, not about whether the match was good.
	 *
	 * @return a corrected copy, or {@code location} itself when no correction is applied.
	 */
	@NonNull
	public Location applyToDisplay(@NonNull Location location) {
		CairoDriveMapMatcher.Match match = getLastMatch();
		if (match == null) {
			return location;
		}
		String reject = null;
		long now = location.getTime() > 0 ? location.getTime() : System.currentTimeMillis();
		long ageMs = now - lastMatchFixTime;
		double movedM = MapUtils.getDistance(lastMatchLat, lastMatchLon,
				location.getLatitude(), location.getLongitude());
		if (ageMs > MAX_MATCH_AGE_MS) {
			reject = "stale ageMs=" + ageMs;
		} else if (movedM > MAX_SOURCE_DRIFT_M) {
			// The car has moved on since the fix this match was computed from. Applying it now
			// would drag the arrow backwards toward a road chosen for a position already left.
			reject = "drifted movedM=" + Math.round(movedM);
		} else if (match.settledDepth < MIN_SETTLED_DEPTH) {
			// settledDepth, not confidence. The posterior can be high while the surviving
			// hypotheses still disagree about where the car has BEEN - and on a flyover that
			// disagreement is precisely the question. Convergence depth is the honest signal.
			reject = "unsettled depth=" + match.settledDepth;
		} else if (match.offsetM > MAX_CORRECTION_M) {
			// A correction this large is not a lane, it is a different road. If the matcher is
			// right the driver will see it on the next fix anyway; if it is wrong this would be a
			// visible jump. Refuse rather than teleport.
			reject = "tooFar offsetM=" + Math.round(match.offsetM);
		}
		if (reject != null) {
			logApplied(false, reject, 0);
			return location;
		}
		Location corrected = new Location(location);
		corrected.setLatitude(match.lat);
		corrected.setLongitude(match.lon);
		logApplied(true, null, match.offsetM);
		return corrected;
	}

	/** A match older than this describes a position the car has left. */
	private static final long MAX_MATCH_AGE_MS = 2_500;
	/** ...and so does one whose source fix is this far behind the current one. */
	private static final double MAX_SOURCE_DRIFT_M = 40.0;
	/** Viterbi convergence depth required before the match is trusted to move the arrow. */
	private static final int MIN_SETTLED_DEPTH = 3;
	/** Beyond this a "correction" is a different road, not a lane. */
	private static final double MAX_CORRECTION_M = 35.0;

	private long lastAppliedLogAt;
	private int appliedCount;
	private int rejectedCount;

	private void logApplied(boolean applied, String reason, double offsetM) {
		if (applied) {
			appliedCount++;
		} else {
			rejectedCount++;
		}
		long now = System.currentTimeMillis();
		if (now - lastAppliedLogAt < 10_000) {
			return;
		}
		lastAppliedLogAt = now;
		CairoDriveLogger.getInstance().log("CD_MATCH", "applied=" + applied
				+ (applied ? " movedM=" + String.format(java.util.Locale.US, "%.1f", offsetM)
				: " reason=" + reason)
				+ " appliedTotal=" + appliedCount + " rejectedTotal=" + rejectedCount);
	}

	/**
	 * Feeds one fix. Cheap and non-blocking; returns immediately on the caller's thread.
	 *
	 * <p>Intended caller: {@code OsmAndLocationProvider.setLocation}, or any single place that
	 * sees every fix. It must be a place that is NOT gated by
	 * {@code CairoDriveStationary.isStationary} - the matcher wants the raw stream.
	 */
	public void onLocation(@Nullable Location location) {
		if (!CairoDriveMapMatching.isEnabled() || location == null) {
			return;
		}
		long now = location.getTime() > 0 ? location.getTime() : System.currentTimeMillis();
		if (gateValid) {
			long dt = now - gateTime;
			if (dt < CairoDriveMapMatching.MIN_INTERVAL_MS && dt > -CairoDriveMapMatching.MIN_INTERVAL_MS) {
				return;
			}
			double moved = MapUtils.getDistance(gateLat, gateLon,
					location.getLatitude(), location.getLongitude());
			if (moved < CairoDriveMapMatching.MIN_STEP_M && dt < CairoDriveMapMatching.MAX_STEP_MS) {
				return;
			}
		}
		if (!busy.compareAndSet(false, true)) {
			// Still working on the previous fix. Drop this one rather than queue it: the gate is
			// left untouched so the next fix is evaluated against the last one actually processed.
			return;
		}
		gateLat = location.getLatitude();
		gateLon = location.getLongitude();
		gateTime = now;
		gateValid = true;

		Fix fix = new Fix(location.getLatitude(), location.getLongitude(),
				location.hasAccuracy() ? location.getAccuracy() : 0f, location.hasAccuracy(),
				location.hasBearing() ? location.getBearing() : 0f, location.hasBearing(),
				location.hasSpeed() ? location.getSpeed() : 0f, location.hasSpeed(),
				now, CairoDriveLogger.getInstance().isGnssDegraded());
		try {
			handler().post(() -> {
				try {
					process(fix);
				} catch (Throwable t) {
					CairoDriveMapMatching.disable("worker-" + t.getClass().getSimpleName());
					CairoDriveLogger.getInstance().log("CD_MATCH", "worker failed", t);
				} finally {
					busy.set(false);
				}
			});
		} catch (Throwable t) {
			busy.set(false);
			CairoDriveMapMatching.disable("post-" + t.getClass().getSimpleName());
		}
	}

	/** Drops all Viterbi state. Call on a route change or after a long pause. */
	public void reset() {
		Handler h = handler;
		if (h != null) {
			h.post(() -> {
				if (matcher != null) {
					matcher.reset();
				}
			});
		}
		gateValid = false;
		lastMatch = null;
		lastMatchFixTime = 0;
	}

	private synchronized Handler handler() {
		if (handler == null) {
			thread = new HandlerThread("CairoDriveMapMatch", Process.THREAD_PRIORITY_BACKGROUND);
			thread.start();
			handler = new Handler(thread.getLooper());
		}
		return handler;
	}

	// ------------------------------------------------------------------ worker thread

	private static final class Fix {
		final double lat;
		final double lon;
		final float accuracy;
		final boolean hasAccuracy;
		final float bearing;
		final boolean hasBearing;
		final float speed;
		final boolean hasSpeed;
		final long time;
		final boolean degraded;

		Fix(double lat, double lon, float accuracy, boolean hasAccuracy, float bearing,
		    boolean hasBearing, float speed, boolean hasSpeed, long time, boolean degraded) {
			this.lat = lat;
			this.lon = lon;
			this.accuracy = accuracy;
			this.hasAccuracy = hasAccuracy;
			this.bearing = bearing;
			this.hasBearing = hasBearing;
			this.speed = speed;
			this.hasSpeed = hasSpeed;
			this.time = time;
			this.degraded = degraded;
		}
	}

	private void process(Fix fix) {
		if (!CairoDriveMapMatching.isEnabled()) {
			return;
		}
		if (!ensureEnvironment(fix)) {
			return;
		}
		long startNs = System.nanoTime();
		CairoDriveMapMatcher.Match match = matcher.update(fix.lat, fix.lon, fix.accuracy,
				fix.hasAccuracy, fix.degraded, fix.bearing, fix.hasBearing, fix.speed, fix.hasSpeed,
				fix.time);
		long elapsedMs = (System.nanoTime() - startNs) / 1_000_000L;

		fixes++;
		if (fix.degraded) {
			degradedFixes++;
		}
		msSum += elapsedMs;
		msMax = Math.max(msMax, elapsedMs);
		if (match == null) {
			noCandidate++;
		} else {
			matched++;
			confSum += match.confidence;
			offsetSum += match.offsetM;
			if (match.disagreesWithNearest) {
				disagreements++;
			}
			lastMatch = match;
			lastMatchFixTime = fix.time;
			lastMatchLat = fix.lat;
			lastMatchLon = fix.lon;
		}

		logMatch(match, elapsedMs);
		maybeLogSummary();
		trimTiles();
		watchdog(elapsedMs);
	}

	private void watchdog(long elapsedMs) {
		if (elapsedMs >= CairoDriveMapMatching.SLOW_MATCH_MS) {
			if (++consecutiveSlow >= CairoDriveMapMatching.MAX_CONSECUTIVE_SLOW) {
				CairoDriveMapMatching.disable("slow " + consecutiveSlow + "x>="
						+ CairoDriveMapMatching.SLOW_MATCH_MS + "ms lastMs=" + elapsedMs);
			}
		} else {
			consecutiveSlow = 0;
		}
	}

	private void trimTiles() {
		RoutingEnvironment env = environment;
		if (env == null) {
			return;
		}
		RoutingContext ctx = env.getCtx();
		if (ctx != null && ctx.getCurrentlyLoadedTiles() > CairoDriveMapMatching.MAX_LOADED_TILES) {
			ctx.unloadUnusedTiles(CairoDriveMapMatching.TILE_MEMORY_LIMIT_BYTES);
		}
	}

	/**
	 * Builds - or rebuilds - the matcher's own routing environment.
	 *
	 * <p>{@code RoutingHelper.getRoutingEnvironment} is the same entry point {@code GpxApproximator}
	 * uses, and it deliberately does NOT hand out the warm navigation context: see
	 * {@code RouteProvider.calculateRoutingEnvironment}, whose javadoc says the warm cache is
	 * single-tenant because other callers run on other threads. So this gets a throwaway
	 * {@link RoutingContext} of its own and never touches the one the router is using.
	 *
	 * <p>Start and end are both the current fix. They are only used to bound the "are the maps
	 * present" check; nothing is routed here.
	 */
	private boolean ensureEnvironment(Fix fix) {
		long now = SystemClock.elapsedRealtime();
		if (environment != null && now - environmentBuiltAt < CairoDriveMapMatching.ENV_MAX_AGE_MS) {
			return true;
		}
		try {
			LatLon here = new LatLon(fix.lat, fix.lon);
			RoutingEnvironment env = app.getRoutingHelper()
					.getRoutingEnvironment(app, ApplicationMode.CAR, here, here);
			if (env == null || env.getCtx() == null) {
				CairoDriveMapMatching.disable("no-routing-environment");
				return false;
			}
			RoutingContext ctx = env.getCtx();
			if (ctx.calculationProgress == null) {
				ctx.calculationProgress = new RouteCalculationProgress();
			}
			RoutingEnvironment previous = environment;
			environment = env;
			environmentBuiltAt = now;
			if (previous != null && previous.getCtx() != null) {
				previous.getCtx().unloadAllData();
			}
			matcher = new CairoDriveMapMatcher(new ObfRoadSource(ctx));
			CairoDriveLogger.getInstance().log("CD_MATCH",
					"environment built mode=CAR ageLimitMs=" + CairoDriveMapMatching.ENV_MAX_AGE_MS);
			return true;
		} catch (Throwable t) {
			CairoDriveMapMatching.disable("environment-" + t.getClass().getSimpleName());
			CairoDriveLogger.getInstance().log("CD_MATCH", "environment failed", t);
			return false;
		}
	}

	// ------------------------------------------------------------------ road supply

	/**
	 * Loads the local road set out of the {@code .obf}, reusing
	 * {@code RoutingContext.loadTileData} - the same primitive
	 * {@code RoutePlannerFrontEnd.findRouteSegment} is built on, including its zoom-17 then
	 * zoom-15 widening when nothing is found.
	 *
	 * <p>Zoom 17 covers roughly +-265 m at Cairo's latitude, comfortably more than the widest
	 * candidate radius, so a small positional cache avoids reloading on most fixes.
	 */
	private static final class ObfRoadSource implements CairoDriveMapMatcher.RoadSource {

		/** Reuse the cached road set while the car is within this of where it was loaded. */
		private static final double CACHE_REUSE_M = 80.0;

		private final RoutingContext ctx;
		private List<RouteDataObject> cached = Collections.emptyList();
		private int cachedX31;
		private int cachedY31;
		private boolean hasCache;

		ObfRoadSource(RoutingContext ctx) {
			this.ctx = ctx;
		}

		@NonNull
		@Override
		public List<RouteDataObject> loadRoadsAround(int x31, int y31, double radiusM) {
			if (hasCache && MapUtils.squareRootDist31(cachedX31, cachedY31, x31, y31) < CACHE_REUSE_M) {
				return cached;
			}
			List<RouteDataObject> raw = new ArrayList<>();
			ctx.loadTileData(x31, y31, 17, raw);
			if (raw.isEmpty()) {
				ctx.loadTileData(x31, y31, 15, raw);
			}
			VehicleRouter router = ctx.getRouter();
			List<RouteDataObject> out = new ArrayList<>(raw.size());
			for (RouteDataObject r : raw) {
				if (r.getPointsLength() > 1 && (router == null || router.acceptLine(r))) {
					out.add(r);
				}
			}
			cached = out;
			cachedX31 = x31;
			cachedY31 = y31;
			hasCache = true;
			return out;
		}
	}

	// ------------------------------------------------------------------ CD_MATCH

	/**
	 * One detail line per {@code DETAIL_INTERVAL_MS}, PLUS every fix where the matcher disagreed
	 * with nearest-road snapping.
	 *
	 * <p>The disagreements are the whole point of the log. If they are rare, N6 is not earning its
	 * cost on this route and should come out; if they are common, the next question is whether the
	 * matcher or the nearest road was right, and {@code conf} and {@code settled} on the same line
	 * are what answer it. Rate-limiting them away would remove the only evidence the feature
	 * produces.
	 */
	private static final long DETAIL_INTERVAL_MS = 3000;

	private void logMatch(@Nullable CairoDriveMapMatcher.Match match, long elapsedMs) {
		long now = SystemClock.elapsedRealtime();
		boolean interesting = match != null && match.disagreesWithNearest;
		if (!interesting && now - detailAt < DETAIL_INTERVAL_MS) {
			return;
		}
		detailAt = now;
		if (match == null) {
			CairoDriveLogger.getInstance().log("CD_MATCH",
					"noCandidate ms=" + elapsedMs + " chainBroken=true");
			return;
		}
		StringBuilder b = new StringBuilder(200);
		b.append("road=").append(match.roadId);
		b.append(" hw=").append(nullSafe(match.highway));
		b.append(" offM=").append(fmt(match.offsetM));
		b.append(" conf=").append(fmt(match.confidence));
		b.append(" cands=").append(match.candidateCount);
		b.append(" settled=").append(match.settledDepth);
		b.append(" nearest=").append(match.nearestRoadId);
		b.append(" nearestM=").append(fmt(match.nearestOffsetM));
		b.append(" disagree=").append(match.disagreesWithNearest);
		b.append(" degraded=").append(match.degraded);
		b.append(" sigmaM=").append(fmt(match.sigmaM));
		b.append(" betaM=").append(fmt(match.betaM));
		b.append(" gcM=").append(fmt(match.greatCircleM));
		b.append(" routeM=").append(Double.isNaN(match.routeM) ? "na" : fmt(match.routeM));
		b.append(" ms=").append(elapsedMs);
		b.append(" name=\"").append(clean(match.roadName)).append('"');
		CairoDriveLogger.getInstance().log("CD_MATCH", b.toString());
	}

	private void maybeLogSummary() {
		long now = SystemClock.elapsedRealtime();
		if (summaryAt == 0) {
			summaryAt = now;
			return;
		}
		if (now - summaryAt < CairoDriveMapMatching.SUMMARY_INTERVAL_MS || fixes == 0) {
			return;
		}
		summaryAt = now;
		CairoDriveLogger.getInstance().log("CD_MATCH", "summary"
				+ " fixes=" + fixes
				+ " matched=" + matched
				+ " noCandidate=" + noCandidate
				+ " degradedFixes=" + degradedFixes
				+ " (" + (100 * degradedFixes / Math.max(1, fixes)) + "%)"
				+ " disagree=" + disagreements + "/" + Math.max(1, matched)
				+ " (" + (100 * disagreements / Math.max(1, matched)) + "%)"
				+ " avgConf=" + fmt(confSum / Math.max(1, matched))
				+ " avgOffM=" + fmt(offsetSum / Math.max(1, matched))
				+ " avgMs=" + fmt((double) msSum / Math.max(1, fixes))
				+ " maxMs=" + msMax);
		fixes = 0;
		matched = 0;
		noCandidate = 0;
		degradedFixes = 0;
		disagreements = 0;
		confSum = 0;
		offsetSum = 0;
		msSum = 0;
		msMax = 0;
	}

	private static String fmt(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return "na";
		}
		return String.format(Locale.US, "%.2f", value);
	}

	private static String nullSafe(@Nullable String value) {
		return value == null || value.isEmpty() ? "-" : value;
	}

	/** Road names are user data and may contain anything; a log line must stay one line. */
	private static String clean(@Nullable String value) {
		if (value == null) {
			return "";
		}
		return value.replace('"', '\'').replace('\n', ' ').replace('\r', ' ');
	}
}
