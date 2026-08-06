package net.osmand.plus.routing;

import static net.osmand.plus.notifications.OsmandNotification.NotificationType.NAVIGATION;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.cairodrive.CairoDriveEarlyReroute;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.plus.R;
import net.osmand.plus.helpers.CairoDriveLog;
import net.osmand.plus.onlinerouting.engine.OnlineRoutingEngine;
import net.osmand.plus.routing.GPXRouteParams.GPXRouteParamsBuilder;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.router.FastRoutingState;
import net.osmand.router.MissingMapsCalculationResult;
import net.osmand.router.RouteCalculationProgress;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

class RouteRecalculationHelper {
	private static final Log LOG = PlatformUtil.getLog(RouteRecalculationHelper.class);

	private static final int RECALCULATE_THRESHOLD_COUNT_CAUSING_FULL_RECALCULATE = 3;
	private static final int RECALCULATE_THRESHOLD_CAUSING_FULL_RECALCULATE_INTERVAL = 2 * 60 * 1000;
	private static final long SUGGEST_MAPS_ONLINE_SEARCH_WAITING_TIME = 60000;
	/** See the two call sites. Upstream's ceiling is 120000 and it is far too long to be silent. */
	static final int MAX_EVAL_WAIT_MS = 15000;

	private final OsmandApplication app;
	private final RoutingHelper routingHelper;

	private final ExecutorService executor = new RouteRecalculationExecutor();
	private final Map<Future<?>, RouteRecalculationTask> tasksMap = new LinkedHashMap<>();
	private RouteRecalculationTask lastTask;

	private long lastTimeEvaluatedRoute;
	private String lastRouteCalcError;
	private String lastRouteCalcErrorShort;
	private long recalculateCountInInterval;
	private int evalWaitInterval;

	private Set<RouteCalculationProgressListener> calculationProgressListeners = new HashSet<>();

	RouteRecalculationHelper(@NonNull RoutingHelper routingHelper) {
		this.routingHelper = routingHelper;
		this.app = routingHelper.getApplication();
	}

	String getLastRouteCalcError() {
		return lastRouteCalcError;
	}

	String getLastRouteCalcErrorShort() {
		return lastRouteCalcErrorShort;
	}

	public void addCalculationProgressListener(@NonNull RouteCalculationProgressListener listener) {
		Set<RouteCalculationProgressListener> listeners = new HashSet<>(this.calculationProgressListeners);
		listeners.add(listener);
		this.calculationProgressListeners = listeners;
	}

	public void removeCalculationProgressListener(@NonNull RouteCalculationProgressListener listener) {
		Set<RouteCalculationProgressListener> listeners = new HashSet<>(this.calculationProgressListeners);
		listeners.remove(listener);
		this.calculationProgressListeners = listeners;
	}

	/**
	 * Is a calculation waiting BESIDES the one on this thread?
	 *
	 * <p>Exists because {@link #isRouteBeingCalculated()} cannot answer that question from inside
	 * a running task. The probe and the speculation are both called from within
	 * {@code RouteRecalculationTask.run()}, and a task's own Future does not become
	 * {@code isDone()} until {@code run()} returns - so the plain check saw the CALLER and always
	 * said "busy". Both features returned at their first line on every invocation and neither had
	 * ever executed once. The repair probe is the measurement this whole feature was gated on, so
	 * "no repairProbe lines in the log" was read as "no deviations" for weeks; it meant the probe
	 * was structurally unreachable.
	 *
	 * <p>Counting more than one incomplete Future answers the intended question - is somebody
	 * else waiting - and is correct whether or not the caller is itself a task.
	 */
	boolean isAnotherRouteQueued() {
		synchronized (routingHelper) {
			int pending = 0;
			for (Future<?> future : tasksMap.keySet()) {
				if (!future.isDone() && ++pending > 1) {
					return true;
				}
			}
		}
		return false;
	}

	boolean isRouteBeingCalculated() {
		synchronized (routingHelper) {
			for (Future<?> future : tasksMap.keySet()) {
				if (!future.isDone()) {
					return true;
				}
			}
		}
		return false;
	}

	void resetEvalWaitInterval() {
		evalWaitInterval = 0;
	}

	void stopCalculationIfParamsNotChanged() {
		synchronized (routingHelper) {
			//boolean hasPendingTasks = tasksMap.isEmpty();
			for (Entry<Future<?>, RouteRecalculationTask> taskFuture : tasksMap.entrySet()) {
				RouteRecalculationTask task = taskFuture.getValue();
				if (!task.isParamsChanged()) {
					taskFuture.getKey().cancel(false);
					task.stopCalculation();
				}
			}
			// Avoid offRoute/onRoute loop, #16571
			//if (hasPendingTasks) {
			//	if (isFollowingMode()) {
			//		getVoiceRouter().announceBackOnRoute();
			//	}
			//}
		}
	}

	void stopCalculation() {
		synchronized (routingHelper) {
			for (Entry<Future<?>, RouteRecalculationTask> taskFuture : tasksMap.entrySet()) {
				taskFuture.getValue().stopCalculation();
				taskFuture.getKey().cancel(false);
			}
		}
	}

	private OsmandSettings getSettings() {
		return routingHelper.getSettings();
	}

	private ApplicationMode getAppMode() {
		return routingHelper.getAppMode();
	}

	private boolean isFollowingMode() {
		return routingHelper.isFollowingMode();
	}

	private VoiceRouter getVoiceRouter() {
		return routingHelper.getVoiceRouter();
	}

	private Location getLastFixedLocation() {
		return routingHelper.getLastFixedLocation();
	}

	private boolean isDeviatedFromRoute() {
		return routingHelper.isDeviatedFromRoute();
	}

	private Location getLastProjection() {
		return routingHelper.getLastProjection();
	}

	private void setNewRoute(RouteCalculationResult prevRoute, RouteCalculationResult res, Location start) {
		setNewRoute(prevRoute, res, start, false);
	}

	// servedInstantly marks a swap the driver never asked for (a traffic detour). Such a swap is
	// announced once by its caller as what it saves; letting the generic route-recalculated prompt
	// fire as well would give one event two voice lines.
	private void setNewRoute(RouteCalculationResult prevRoute, RouteCalculationResult res, Location start,
	                         boolean servedInstantly) {
		routingHelper.setRoute(res);
		// Re-arm the traffic poller against the new geometry. Non-urgent in both cases: a detour
		// was live-scored moments ago, so a shorter rewind would only buy one extra billed poll.
		GoogleTrafficHelper.onNewRoute();
		boolean newRoute = !prevRoute.isCalculated();
		if (isFollowingMode() && !servedInstantly) {
			Location lastFixedLocation = getLastFixedLocation();
			if (lastFixedLocation != null) {
				start = lastFixedLocation;
			}
			// try remove false route-recalculated prompts by checking direction to second route node
			boolean wrongMovementDirection = false;
			List<Location> routeNodes = res.getImmutableAllLocations();
			if (routeNodes != null && !routeNodes.isEmpty()) {
				int newCurrentRoute = RoutingHelperUtils.lookAheadFindMinOrthogonalDistance(start, routeNodes, res.currentRoute, 15);
				if (newCurrentRoute + 1 < routeNodes.size()) {
					// This check is valid for Online/GPX services (offline routing is aware of route direction)
					Location prev = res.getRouteLocationByDistance(-15);
					wrongMovementDirection = RoutingHelperUtils.checkWrongMovementDirection(start, prev, routeNodes.get(newCurrentRoute + 1));
					// set/reset evalWaitInterval only if new route is in forward direction
					if (wrongMovementDirection) {
						evalWaitInterval = 3000;
					} else {
						evalWaitInterval = Math.max(3000, evalWaitInterval * 3 / 2);
						// Capped at 15 s, not 120 s. Upstream's ceiling means a driver can be
						// refused a recalculation for TWO MINUTES with nothing retrying and no
						// indication - which from the seat is indistinguishable from the app
						// having given up. The growth is still there, it just stops somewhere a
						// human would tolerate. 15 s is longer than the off-route hysteresis
						// window (12 s), so it cannot mask a deviation that has already been
						// confirmed.
						evalWaitInterval = Math.min(evalWaitInterval, MAX_EVAL_WAIT_MS);
					}

				}
			}
			// trigger voice prompt only if new route is in forward direction
			// If route is in wrong direction after one more setLocation it will be recalculated
			if (shouldAnnounceNewRoute(res) && (!wrongMovementDirection || newRoute)) {
				getVoiceRouter().newRouteIsCalculated(newRoute);
			}
		}
		app.getWaypointHelper().setNewRoute(res);
		routingHelper.newRouteCalculated(newRoute, res);
		// Score the fresh route sooner than the usual 3-minute tick, but with a debounce so the
		// GPS churn around a reroute cannot fire a billed request per fix.
		GoogleTrafficHelper.onNewRoute();
		net.osmand.plus.cairodrive.providers.TomTomTrafficProvider.onNewRoute();
		if (res.initialCalculation) {
			app.runInUIThread(() -> routingHelper.recalculateRouteDueToSettingsChange(false));
		}
	}

	/** Base distance ahead on the old route - HERE and TomTom both work at this scale. */
	private static final int REPAIR_PROBE_REJOIN_M = 600;
	/**
	 * Item 4 escape hatch, TomTom's shape: the rejoin point moves FURTHER away on each consecutive
	 * repair that failed its sanity tests, so a driver who keeps leaving the route is not dragged
	 * back toward a point that keeps receding. 600 -> 1200 -> 2400, then stop trying.
	 */
	private static final int REPAIR_MAX_CONSECUTIVE = 3;
	/** A repair longer than this is never worth it, whatever the ratio says. */
	private static final int REPAIR_ABSOLUTE_CAP_M = 2500;
	/** Repair road distance may not exceed this multiple of the along-route distance saved. */
	private static final double REPAIR_DETOUR_RATIO = 3.0;
	/** Floor for the ratio test, so a tiny base cannot make the ratio meaningless. */
	private static final int REPAIR_MIN_BASE_M = 300;

	private int consecutiveRepairs;

	private int repairCutoffM() {
		return REPAIR_PROBE_REJOIN_M << Math.min(consecutiveRepairs, REPAIR_MAX_CONSECUTIVE - 1);
	}
	/** At most one probe per this interval, so a reroute storm cannot turn into a CPU storm. */
	private static final long REPAIR_PROBE_MIN_INTERVAL_MS = 90_000;

	private long lastRepairProbeAt;

	/**
	 * Times what a ROUTE REPAIR would have cost, and throws the answer away.
	 *
	 * <h3>The question this exists to settle</h3>
	 *
	 * Every deviation on this device runs a full search to the final destination. OsmAnd's own
	 * repair mechanism is bypassed by the HH C++ branch and gated behind a 20 km threshold besides,
	 * and upstream issue #19737 says the same. The fix the commercial SDKs ship - HERE's
	 * {@code returnToRoute()}, TomTom's continuous replanning - is to route only as far as a point
	 * a few hundred metres ahead ON the existing route, and splice the untouched tail back on.
	 *
	 * <p>All of that rests on one assumption nobody has measured: <b>that a short route is
	 * proportionally cheaper on this hardware.</b> It might not be. HH's cost is dominated by
	 * loading and searching the network around each endpoint, and if that fixed cost dominates then
	 * a 600 m repair costs nearly what an 8 km search does and the whole technique is worthless
	 * here. Six hypotheses have already been spent guessing at this router and all six were wrong.
	 *
	 * <h3>Why a shadow run rather than shipping the repair</h3>
	 *
	 * Correlating {@code search} against {@code straightM} across a drive would only ever be
	 * indirect evidence. This measures the actual thing: a real repair search, on the real device,
	 * from the real position the driver actually deviated at.
	 *
	 * <p>And it cannot produce a wrong route, because the result is discarded. A route that is
	 * wrong is far worse than a route that is slow, and that is the whole reason the repair is not
	 * simply switched on to find out.
	 *
	 * <h3>Why it cannot cost the drive anything</h3>
	 *
	 * <ul>
	 *   <li>It runs only AFTER {@code setNewRoute} - the driver already has their route and the
	 *       head unit is already showing it.</li>
	 *   <li>Only on a reroute ({@code previousToRecalculate != null}), never on a first
	 *       calculation.</li>
	 *   <li>At most once every 90 s, so the reroute storm this project is trying to fix cannot turn
	 *       into a CPU storm.</li>
	 *   <li>Not at all if another calculation has since been queued - a real route always wins.</li>
	 *   <li>Its own {@code RouteCalculationProgress}, so it cannot disturb the live one.</li>
	 *   <li>Wrapped in Throwable, so a probe can never take down a navigation session.</li>
	 * </ul>
	 */
	void runRepairProbe(@NonNull RouteProvider provider, @NonNull RouteCalculationParams params) {
		try {
			RouteCalculationResult previous = params.previousToRecalculate;
			if (previous == null || !previous.isCalculated() || params.start == null) {
				return;
			}
			long now = System.currentTimeMillis();
			if (now - lastRepairProbeAt < REPAIR_PROBE_MIN_INTERVAL_MS) {
				return;
			}
			if (isAnotherRouteQueued()) {
				// A real calculation has been queued behind this one. It gets the CPU.
				return;
			}
			// Along the road, not across it. getRouteLocationByDistance measures a STRAIGHT LINE
			// from the current position, so on a flyover ramp or a route that doubles back it
			// picks a point far further along than asked for - and on a loop it never meets the
			// threshold at all and returns null, so the probe would silently never run. Both would
			// corrupt the one measurement this whole exercise depends on.
			Object[] ahead = previous.getLocationAheadAlongRoute(repairCutoffM());
			if (ahead == null) {
				// Less than 600 m of route left. Nothing to rejoin to, and nothing to learn.
				return;
			}
			Location rejoin = (Location) ahead[0];
			int alongRouteM = (Integer) ahead[1];
			lastRepairProbeAt = now;

			RouteCalculationParams probe = new RouteCalculationParams();
			probe.start = params.start;
			probe.end = new LatLon(rejoin.getLatitude(), rejoin.getLongitude());
			probe.intermediates = null;
			probe.gpxRoute = null;
			probe.onlyStartPointChanged = false;
			probe.previousToRecalculate = null;
			probe.leftSide = params.leftSide;
			probe.fast = params.fast;
			probe.mode = params.mode;
			probe.ctx = params.ctx;
			probe.calculationProgress = new RouteCalculationProgress();

			long startedAt = System.currentTimeMillis();
			RouteCalculationResult probeResult = provider.calculateRouteImpl(probe);
			long elapsedMs = System.currentTimeMillis() - startedAt;

			// straightM is the like-for-like comparison: the real calculation logs its own in
			// CD_ROUTE_TIMING, so the two lines together give cost against distance for the SAME
			// deviation, seconds apart, on the same roads - which no amount of cross-drive
			// correlation can match for cleanliness.
			// SANITY TESTS. A repair that loops 3 km to rejoin 600 m ahead is worse than a full
			// search, and a ratio test alone cannot tell a necessary detour from an absurd one -
			// the absolute cap is what does the real work.
			String reject = null;
			int repairDistM = probeResult.isCalculated() ? probeResult.getWholeDistance() : -1;
			if (!probeResult.isCalculated()) {
				reject = "notCalculated";
			} else if (repairDistM > REPAIR_ABSOLUTE_CAP_M) {
				reject = "tooLong";
			} else if (repairDistM > REPAIR_DETOUR_RATIO * Math.max(alongRouteM, REPAIR_MIN_BASE_M)) {
				reject = "detourRatio";
			}
			if (reject != null) {
				consecutiveRepairs++;
			} else {
				consecutiveRepairs = 0;
			}

			long straightM = Math.round(MapUtils.getDistance(
					params.start.getLatitude(), params.start.getLongitude(),
					probe.end.getLatitude(), probe.end.getLongitude()));
			CairoDriveLogger.getInstance().log("CD_REROUTE", "repairProbe"
					+ " repairMs=" + elapsedMs
					+ " straightM=" + straightM
					// Both are logged because they answer different questions. straightM is what
					// the router was actually given and is the like-for-like comparison against
					// CD_ROUTE_TIMING's own straightM. alongRouteM is how far ahead on the old
					// route the rejoin point sits, i.e. how much route a real repair would have
					// reused. They diverge exactly where the road curves, and the gap between them
					// is itself worth seeing.
					+ " alongRouteM=" + alongRouteM
					+ " askedM=" + REPAIR_PROBE_REJOIN_M
					+ " ok=" + probeResult.isCalculated()
					+ " repairDistM=" + repairDistM
					+ " cutoffM=" + repairCutoffM()
					+ " consecutiveRepairs=" + consecutiveRepairs
					+ " reject=" + (reject == null ? "none" : reject)
					+ " - result DISCARDED, navigation unaffected");

			// THE TEST THAT MATTERS, and the only one that can catch item 4's real failure before
			// it ever reaches a driver.
			//
			// A bad splice does not produce a broken-looking route. It produces a route that is
			// geometrically continuous, passes every distance check, draws perfectly on the map -
			// and speaks the WRONG TURN at a real junction, because the reused tail's turn types
			// were computed against a predecessor segment that the splice replaced.
			//
			// Both routes exist here at the same moment, for the same deviation, so comparing the
			// first turns after the rejoin point costs nothing. If the repair's turns match the
			// full search's, the splice would have been safe. If they diverge, that is the bug -
			// found in a log instead of at a junction in Cairo.
			if (reject == null) {
				logTurnDiff(probeResult, routingHelper.getRoute());
			}
		} catch (Throwable t) {
			CairoDriveLogger.getInstance().log("CD_REROUTE", "repairProbe failed "
					+ t.getClass().getSimpleName() + ": " + t.getMessage());
		}
	}

	/**
	 * Compares the first few turn instructions of the shadow repair against the route that actually
	 * shipped. Pure diagnostics - neither route is modified.
	 */
	private void logTurnDiff(@NonNull RouteCalculationResult repair, @Nullable RouteCalculationResult live) {
		try {
			if (live == null || !live.isCalculated()) {
				return;
			}
			StringBuilder sb = new StringBuilder(120);
			int compared = 0;
			int mismatches = 0;
			List<RouteDirectionInfo> a = repair.getImmutableAllDirections();
			List<RouteDirectionInfo> b = live.getImmutableAllDirections();
			for (int i = 0; i < 3 && i < a.size() && i < b.size(); i++) {
				String ta = String.valueOf(a.get(i).getTurnType());
				String tb = String.valueOf(b.get(i).getTurnType());
				compared++;
				if (!ta.equals(tb)) {
					mismatches++;
					sb.append(" [").append(i).append("] repair=").append(ta).append(" live=").append(tb);
				}
			}
			CairoDriveLogger.getInstance().log("CD_REROUTE", "repairTurnDiff"
					+ " compared=" + compared + " mismatches=" + mismatches
					+ (mismatches > 0 ? sb.toString() : " - turns agree, a splice here would have been safe"));
		} catch (Throwable t) {
			CairoDriveLogger.getInstance().log("CD_REROUTE",
					"repairTurnDiff failed " + t.getClass().getSimpleName());
		}
	}

	/**
	 * ITEM 4 LIVE. Attempts a short repair to a rejoin point on the previous route instead of a
	 * full search to the destination.
	 *
	 * <p>Every gate below returns null, and null means the caller runs the unchanged full search.
	 * There is no path here that produces a route without passing all of them.
	 *
	 * @return a complete spliced route to the real destination, or null.
	 */
	// ITEM 6. Speculative precompute.
	//
	// Affordable only because item 4 exists. A full 8 km search per junction is ~13% duty cycle on
	// one of this phone's two big cores, continuously, on a device already at 46.9 ms/frame - which
	// is why this was blocked. A 600 m repair is a different order of cost, so precomputing one
	// while the CPU is otherwise idle is a trade worth making.
	//
	// This is where an offline app can genuinely beat Google rather than catch up: Google pays per
	// routing query, so speculation is a line item and Mapbox's own alternatives feature runs on a
	// 5-minute interval for exactly that reason. Here it costs CPU and nothing else.
	//
	// Serialised on the SAME executor as real calculations, never a second thread:
	// BinaryMapIndexReader holds one RandomAccessFile with a mutable stream position, so two
	// concurrent Java-side searches corrupt each other. Bailing when a real calculation is queued
	// is what keeps a speculative search from ever delaying a real one.
	private static final long SPECULATE_MIN_INTERVAL_MS = 45_000;
	private static final int SPECULATE_VALID_MS = 120_000;
	private static final int SPECULATE_MATCH_M = 120;

	private long lastSpeculationAt;
	@Nullable
	private RouteCalculationResult speculativeRoute;
	private long speculativeAt;
	@Nullable
	private LatLon speculativeFrom;

	/**
	 * Called after a route is set. Precomputes the repair the driver would need if they miss the
	 * next turn, so that deviation costs a lookup instead of a search.
	 */
	void speculate(@NonNull RouteProvider provider, @NonNull RouteCalculationParams params,
	               @NonNull RouteCalculationResult route) {
		if (!BuildConfig.CAIRODRIVE_SPECULATE || !BuildConfig.CAIRODRIVE_ROUTE_REPAIR) {
			return;
		}
		try {
			long now = System.currentTimeMillis();
			// DELIBERATELY still isRouteBeingCalculated(), not isAnotherRouteQueued().
			//
			// Called from inside RouteRecalculationTask.run(), that predicate is always true, so
			// this returns every time and speculation has never once executed. The probe above
			// had the same fault and was fixed; this one is left dead ON PURPOSE, because
			// unblocking it without fixing its geometry would turn a dead feature into a wrong
			// route.
			//
			// What it computes today: `from` is 400 m ahead ON THE CURRENT ROUTE and `rejoin` is
			// 1000 m ahead ON THE CURRENT ROUTE, so the result is approximately the existing
			// route with its first 400 m removed. It does not model a missed turn at all - a
			// missed turn puts the driver on a DIFFERENT road. takeSpeculation then accepts it
			// whenever the driver is within SPECULATE_MATCH_M = 120 m of that on-route point,
			// and on a dense Cairo grid a parallel street is well inside 120 m. So a "hit" would
			// hand a deviated driver their OLD route, starting from a point they are not on, and
			// it is consulted BEFORE every guard in tryRepairRoute.
			//
			// Fix the geometry first - speculate at the actual junction and its plausible wrong
			// exits, invalidated once the fork is passed - then swap this predicate.
			if (now - lastSpeculationAt < SPECULATE_MIN_INTERVAL_MS || isRouteBeingCalculated()) {
				return;
			}
			// The point a driver reaches by MISSING the next turn: keep going past it. Approximated
			// as a point further along the current route than the turn, which is where a missed
			// turn most often leaves you on a Cairo grid.
			Object[] ahead = route.getLocationAheadAlongRoute(SPECULATE_AHEAD_M);
			if (ahead == null) {
				return;
			}
			Location from = (Location) ahead[0];
			lastSpeculationAt = now;
			long startedAt = System.currentTimeMillis();
			Object[] rejoinAhead = route.getLocationAheadAlongRoute(SPECULATE_AHEAD_M + REPAIR_PROBE_REJOIN_M);
			if (rejoinAhead == null) {
				return;
			}
			Location rejoinLoc = (Location) rejoinAhead[0];
			int rejoinIndex = (Integer) rejoinAhead[2];

			RouteCalculationParams specParams = new RouteCalculationParams();
			specParams.start = from;
			specParams.end = params.end;
			specParams.mode = params.mode;
			specParams.ctx = params.ctx;
			specParams.leftSide = params.leftSide;
			specParams.fast = params.fast;
			specParams.calculationProgress = new RouteCalculationProgress();

			RouteCalculationResult precomputed = provider.calculateRepairRoute(specParams, route,
					new LatLon(rejoinLoc.getLatitude(), rejoinLoc.getLongitude()), rejoinIndex);
			long elapsedMs = System.currentTimeMillis() - startedAt;
			if (precomputed != null && precomputed.isCalculated()) {
				speculativeRoute = precomputed;
				speculativeAt = now;
				speculativeFrom = new LatLon(from.getLatitude(), from.getLongitude());
			}
			CairoDriveLogger.getInstance().log("CD_SPECULATE", "precomputed"
					+ " ok=" + (precomputed != null && precomputed.isCalculated())
					+ " ms=" + elapsedMs
					+ " aheadM=" + SPECULATE_AHEAD_M);
		} catch (Throwable t) {
			CairoDriveLogger.getInstance().log("CD_SPECULATE",
					"failed " + t.getClass().getSimpleName());
		}
	}

	/** Distance past the next turn a missed turn typically leaves the driver. */
	private static final int SPECULATE_AHEAD_M = 400;

	/**
	 * Returns a precomputed route if one was made for roughly where the driver now is. Consumed
	 * once - a stale speculative route is worse than none, and reusing it twice is how it becomes
	 * stale.
	 */
	@Nullable
	private RouteCalculationResult takeSpeculation(@NonNull Location start) {
		RouteCalculationResult cached = speculativeRoute;
		LatLon from = speculativeFrom;
		if (cached == null || from == null) {
			return null;
		}
		speculativeRoute = null;
		speculativeFrom = null;
		if (System.currentTimeMillis() - speculativeAt > SPECULATE_VALID_MS) {
			CairoDriveLogger.getInstance().log("CD_SPECULATE", "discarded stale");
			return null;
		}
		double d = MapUtils.getDistance(from.getLatitude(), from.getLongitude(),
				start.getLatitude(), start.getLongitude());
		if (d > SPECULATE_MATCH_M) {
			CairoDriveLogger.getInstance().log("CD_SPECULATE",
					"discarded missM=" + Math.round(d));
			return null;
		}
		CairoDriveLogger.getInstance().log("CD_SPECULATE",
				"HIT missM=" + Math.round(d) + " - reroute served with no search at all");
		return cached;
	}

	// REROUTE RESULT CACHE.
	//
	// The 2026-08-04 drive produced reroute after reroute while turning around, and upstream makes
	// it worse on purpose: after three recalculations in two minutes it DISCARDS the previous
	// route to break a reuse loop, so the fourth is a full cold search. A driver oscillating at a
	// junction is asking a question that was answered seconds ago.
	//
	// Free to a system with no marginal query cost - which is the same asymmetry item 6 exploits,
	// and the reason Google would not do this: it bills per query and wants fresh traffic.
	//
	// Correctness is entirely about not serving a STALE route, so it borrows the discipline the
	// warm-environment cache already uses: keyed on start cell + destination + profile, dropped
	// whenever the loaded map set changes, and expired by time. It is NOT keyed on the previous
	// route, because the point is to answer a repeat of the same question.
	private static final int CACHE_MAX = 4;
	private static final long CACHE_TTL_MS = 90_000;
	/** ~150 m. Two starts inside one cell get the same answer, which is the whole idea. */
	private static final int CACHE_CELL_SHIFT = 12;

	private final LinkedHashMap<String, Object[]> rerouteCache =
			new LinkedHashMap<String, Object[]>(8, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, Object[]> eldest) {
					return size() > CACHE_MAX;
				}
			};

	@Nullable
	private String cacheKey(@NonNull RouteCalculationParams params) {
		if (params.start == null || params.end == null || params.mode == null
				|| params.gpxRoute != null
				|| (params.intermediates != null && !params.intermediates.isEmpty())) {
			return null;
		}
		int sx = MapUtils.get31TileNumberX(params.start.getLongitude()) >>> CACHE_CELL_SHIFT;
		int sy = MapUtils.get31TileNumberY(params.start.getLatitude()) >>> CACHE_CELL_SHIFT;
		return sx + ":" + sy + ":" + params.end.getLatitude() + ":" + params.end.getLongitude()
				+ ":" + params.mode.getStringKey();
	}

	@Nullable
	private synchronized RouteCalculationResult takeCachedRoute(@NonNull RouteCalculationParams params) {
		if (!BuildConfig.CAIRODRIVE_REROUTE_CACHE) {
			return null;
		}
		String key = cacheKey(params);
		if (key == null) {
			return null;
		}
		Object[] entry = rerouteCache.get(key);
		if (entry == null) {
			return null;
		}
		long at = (Long) entry[1];
		if (System.currentTimeMillis() - at > CACHE_TTL_MS) {
			rerouteCache.remove(key);
			return null;
		}
		CairoDriveLogger.getInstance().log("CD_REROUTE",
				"cache HIT ageMs=" + (System.currentTimeMillis() - at) + " - no search at all");
		return (RouteCalculationResult) entry[0];
	}

	private synchronized void putCachedRoute(@NonNull RouteCalculationParams params,
	                                         @NonNull RouteCalculationResult res) {
		if (!BuildConfig.CAIRODRIVE_REROUTE_CACHE || !res.isCalculated()) {
			return;
		}
		String key = cacheKey(params);
		if (key != null) {
			rerouteCache.put(key, new Object[] {res, System.currentTimeMillis()});
		}
	}

	/** Dropped whenever the loaded map set changes - a cached route over a map that has since been
	 *  installed or removed is exactly the stale answer this must never serve. */
	synchronized void invalidateRerouteCache() {
		rerouteCache.clear();
	}

	@Nullable
	RouteCalculationResult tryRepairRoute(@NonNull RouteProvider provider,
	                                      @NonNull RouteCalculationParams params) {
		if (!BuildConfig.CAIRODRIVE_ROUTE_REPAIR) {
			return null;
		}
		try {
			// Cache first: an oscillating driver is re-asking a question answered seconds ago.
			RouteCalculationResult cached = takeCachedRoute(params);
			if (cached != null) {
				return cached;
			}
			// Then item 6: if a route was already precomputed for roughly here, this reroute
			// costs a lookup instead of a search.
			if (params.start != null) {
				RouteCalculationResult speculated = takeSpeculation(params.start);
				if (speculated != null) {
					return speculated;
				}
			}
			RouteCalculationResult previous = params.previousToRecalculate;
			// Only a deviation reroute. Never a first calculation, never with intermediates (their
			// indices are rebuilt from the location list and a splice invalidates that), never a
			// GPX route (it has its own recalculation path).
			if (previous == null || !previous.isCalculated() || params.start == null
					|| params.gpxRoute != null
					|| (params.intermediates != null && !params.intermediates.isEmpty())) {
				return null;
			}
			// Upstream discards previousToRecalculate after three recalculations in two minutes,
			// deliberately, to break a reuse loop. Repairing then would reuse exactly the route it
			// just declared untrustworthy.
			if (recalculateCountInInterval >= RECALCULATE_THRESHOLD_COUNT_CAUSING_FULL_RECALCULATE) {
				return null;
			}
			// Escape hatch: after this many consecutive repairs the driver is not coming back, and
			// dragging them toward a receding rejoin point is the failure TomTom's doubling cutoff
			// exists to bound.
			if (consecutiveRepairs >= REPAIR_MAX_CONSECUTIVE) {
				CairoDriveLogger.getInstance().log("CD_REROUTE",
						"repair SKIPPED consecutiveRepairs=" + consecutiveRepairs + " - full search");
				return null;
			}
			Object[] ahead = previous.getLocationAheadAlongRoute(repairCutoffM());
			if (ahead == null) {
				return null;   // less than a cutoff of route left; a short search is cheap anyway
			}
			Location rejoinLoc = (Location) ahead[0];
			int alongRouteM = (Integer) ahead[1];
			int rejoinIndex = ahead.length > 2 ? (Integer) ahead[2] : -1;
			if (rejoinIndex < 0) {
				return null;
			}
			long startedAt = System.currentTimeMillis();
			RouteCalculationResult repaired = provider.calculateRepairRoute(params, previous,
					new LatLon(rejoinLoc.getLatitude(), rejoinLoc.getLongitude()), rejoinIndex);
			long elapsedMs = System.currentTimeMillis() - startedAt;

			String reject = null;
			int repairDistM = -1;
			if (repaired == null || !repaired.isCalculated()) {
				reject = "notCalculated";
			} else {
				// Score the NEW LEG, not the whole journey.
				//
				// This used to read repaired.getWholeDistance(), which is listDistance[0] - the
				// distance of the entire spliced route to the REAL destination, tail included.
				// The thresholds are sized for a leg of a few hundred metres, so on any route
				// with more than ~2.4 km left the comparison was 7000-odd metres against 3100 and
				// the repair was rejected every single time. Worse than useless: each rejection
				// costs a full wasted repair search BEFORE the ordinary search, and three of them
				// trip consecutiveRepairs and disable the path for the rest of the process. The
				// feature was making reroutes slower than having no repair at all.
				//
				// previous.getDistanceFromPoint(rejoinIndex) is listDistance[rejoinIndex] on the
				// OLD route - exactly the tail that was reused - so subtracting it leaves the
				// distance actually driven to get back on route, which is what both thresholds
				// were written to bound.
				int reusedTailM = previous.getDistanceFromPoint(rejoinIndex);
				repairDistM = Math.max(0, repaired.getWholeDistance() - reusedTailM);
				if (repairDistM > REPAIR_ABSOLUTE_CAP_M + alongRouteM) {
					reject = "tooLong";
				} else if (repairDistM > REPAIR_DETOUR_RATIO * Math.max(alongRouteM, REPAIR_MIN_BASE_M)
						+ alongRouteM) {
					reject = "detourRatio";
				}
			}
			if (reject != null) {
				consecutiveRepairs++;
				CairoDriveLogger.getInstance().log("CD_REROUTE", "repair REJECTED"
						+ " reason=" + reject + " ms=" + elapsedMs
						+ " repairLegM=" + repairDistM
						+ " cutoffM=" + repairCutoffM()
						+ " consecutiveRepairs=" + consecutiveRepairs + " - falling back to full search");
				return null;
			}
			consecutiveRepairs = 0;
			CairoDriveLogger.getInstance().log("CD_REROUTE", "repair USED"
					+ " ms=" + elapsedMs
					+ " repairLegM=" + repairDistM
					+ " alongRouteM=" + alongRouteM
					+ " cutoffM=" + repairCutoffM()
					+ " wholeDistM=" + repaired.getWholeDistance());
			return repaired;
		} catch (Throwable t) {
			CairoDriveLogger.getInstance().log("CD_REROUTE",
					"repair threw " + t.getClass().getSimpleName() + " - full search");
			return null;
		}
	}

	private boolean shouldAnnounceNewRoute(RouteCalculationResult res) {
		if (res.getAppMode().getRouteService() == RouteService.ONLINE) {
			OnlineRoutingEngine engine = app.getOnlineRoutingHelper().getEngineByKey(res.getAppMode().getRoutingProfile());
			if (engine != null && engine.isOnlineEngineWithApproximation()) {
				return res.initialCalculation; // announce at 1st phase (before approximation)
			}
		}
		return !res.initialCalculation; // announce at final
	}

	/**
	 * Installs a detour computed by {@link TrafficDetourHelper} through the same single-thread
	 * executor the real recalculations use, so it can never overlap one. The route the detour was
	 * computed against is re-checked here because that computation took seconds off-thread: a
	 * navigation stopped or replaced meanwhile must not be resurrected. Installed silently, with
	 * one announcement of what the detour saves.
	 *
	 * Not registered in tasksMap - it is not a recalculation, and isRouteBeingCalculated() must
	 * keep reporting on real recalculations only.
	 *
	 * The re-check and the swap are one atomic step under the RoutingHelper monitor: clearCurrentRoute
	 * is synchronized on it, so without that hold it could land entirely between the two and the
	 * detour would resurrect navigation the driver had just stopped. Lock order is
	 * RoutingHelper -> GoogleTrafficHelper.class everywhere; this thread holds neither the routing
	 * config monitor nor the class monitor here, so the order is preserved.
	 */
	void installTrafficDetour(RouteCalculationResult expected, RouteCalculationResult detour,
	                          Location start, int savedMinutes) {
		executor.submit(() -> {
			try {
				synchronized (routingHelper) {
					if (routingHelper.getRoute() != expected || !isFollowingMode()) {
						return;
					}
					// The detour starts at the position the computation used; the driver has moved on
					// since, so advance to the nearest node ahead before it becomes the live route.
					List<Location> nodes = detour.getImmutableAllLocations();
					if (nodes != null && !nodes.isEmpty()) {
						int ahead = RoutingHelperUtils.lookAheadFindMinOrthogonalDistance(start, nodes, 0, 15);
						if (ahead > 0 && ahead + 1 < nodes.size()) {
							detour.updateCurrentRoute(ahead + 1);
						}
					}
					setNewRoute(expected, detour, start, true);
				}
				CairoDriveLog.log("DETOUR", "detour installed - saving ~" + savedMinutes + " min");
				// Visual only: the spoken sibling would need a CommandPlayer raw-text path that this
				// fork does not carry - see the port notes. The toast is the whole notification.
				app.runInUIThread(() -> app.showShortToastMessage(R.string.cairo_traffic_detour,
						String.valueOf(savedMinutes)));
			} catch (Throwable t) {
				LOG.error("Traffic detour install failed", t);
			}
		});
	}

	void startRouteCalculationThread(RouteCalculationParams params, boolean paramsChanged, boolean updateProgress) {
		synchronized (routingHelper) {
			getSettings().LAST_ROUTE_APPLICATION_MODE.set(getAppMode());
			RouteRecalculationTask newTask = new RouteRecalculationTask(this,
					params, paramsChanged, updateProgress);
			lastTask = newTask;
			onRouteCalculationStart(params);
			if (updateProgress) {
				updateProgressWithDelay(params);
			}
			Future<?> future = executor.submit(newTask);
			tasksMap.put(future, newTask);
		}
	}

	public void recalculateRouteInBackground(Location start, LatLon end, List<LatLon> intermediates,
	                                         GPXRouteParamsBuilder gpxRoute, RouteCalculationResult previousRoute,
	                                         boolean paramsChanged, boolean onlyStartPointChanged) {
		if (start == null || end == null) {
			return;
		}
		try {
			if (PlatformUtil.getOsmandRegions() == null || !app.getAppInitializer().isRoutingConfigInitialized()) {
				app.showToastMessage(R.string.waiting_for_route_calculation);
				LOG.warn("recalculateRouteInBackground is waiting for initialization");
				return; // will be retried automatically
			}
		} catch (IOException e) {
			LOG.warn("getOsmandRegions", e);
		}
		// do not evaluate very often
		boolean busy = isRouteBeingCalculated();
		long sinceLastMs = System.currentTimeMillis() - lastTimeEvaluatedRoute;
		boolean allowed = (!busy && sinceLastMs > evalWaitInterval) || paramsChanged || !onlyStartPointChanged;
		if (!allowed) {
			// The request is DROPPED here, silently, and nothing upstream retries it - the next
			// GPS fix has to raise the deviation all over again. Two things can cause it and they
			// mean opposite things:
			//   busy=1        a calculation is already running. Expected, harmless.
			//   waiting       evalWaitInterval has not elapsed. That interval starts at 0, is set
			//                 to 3000 on a normal route, and is multiplied by 1.5 up to a cap of
			//                 120000 every time a route comes back pointing the wrong way
			//                 (RouteRecalculationHelper:176-179, :429-430). Two minutes of
			//                 refusing to recalculate reads, from the driver's seat, as the app
			//                 having given up - and until now it left no trace anywhere.
			// Logged because CD_ROUTE_TIMING only measures the search itself, so a reroute that
			// never became a search was invisible in every log this project has.
			CairoDriveLogger.getInstance().log("CD_REROUTE", "dropped"
					+ " busy=" + (busy ? 1 : 0)
					+ " sinceLastMs=" + sinceLastMs
					+ " evalWaitMs=" + evalWaitInterval
					+ " waitLeftMs=" + Math.max(0, evalWaitInterval - sinceLastMs));
			return;
		}
		{
			if (System.currentTimeMillis() - lastTimeEvaluatedRoute < RECALCULATE_THRESHOLD_CAUSING_FULL_RECALCULATE_INTERVAL) {
				recalculateCountInInterval++;
			}
			ApplicationMode mode = getAppMode();
			RouteCalculationParams params = new RouteCalculationParams();
			params.start = start;
			params.end = end;
			params.intermediates = intermediates;
			if (gpxRoute != null) {
				params.gpxRoute = gpxRoute.build(app, end);
			} else {
				params.gpxRoute = null;
			}
			params.onlyStartPointChanged = onlyStartPointChanged;
			boolean keptPrevious;
			if (recalculateCountInInterval < RECALCULATE_THRESHOLD_COUNT_CAUSING_FULL_RECALCULATE
					|| (gpxRoute != null && isDeviatedFromRoute())) {
				params.previousToRecalculate = previousRoute;
				keptPrevious = previousRoute != null;
			} else {
				// Three recalculations inside two minutes and upstream throws the previous route
				// away on purpose, to break a loop where a bad reuse keeps reproducing itself. The
				// 2026-08-04 drive hit exactly that pattern - reroute after reroute while turning
				// around - so this branch was almost certainly taken, and nothing recorded it.
				recalculateCountInInterval = 0;
				keptPrevious = false;
			}
			// Dispatch stamped so the SPAN NOBODY HAS EVER MEASURED can be measured: from the
			// deviation being acted on to the new route reaching the screen. CD_ROUTE_TIMING covers
			// `setup` and `search` inside RouteProvider only - it starts after the thread has been
			// queued and ends before the result is handed to any listener. The driver experiences
			// the whole span, and the parts outside the search have never been priced.
			params.cairoDriveDispatchedAt = System.currentTimeMillis();
			CairoDriveLogger.getInstance().log("CD_REROUTE", "dispatched"
					+ " sinceLastMs=" + sinceLastMs
					+ " evalWaitMs=" + evalWaitInterval
					+ " countInInterval=" + recalculateCountInInterval
					+ " keptPrevious=" + keptPrevious
					+ " onlyStartPointChanged=" + onlyStartPointChanged
					+ " paramsChanged=" + paramsChanged);
			params.leftSide = getSettings().DRIVING_REGION.get().leftHandDriving;
			params.fast = getSettings().FAST_ROUTE_MODE.getModeValue(mode);
			params.mode = mode;
			params.ctx = app;
			boolean updateProgress = false;
			if (params.mode.getRouteService() == RouteService.OSMAND) {
				params.calculationProgress = new RouteCalculationProgress();
				updateProgress = true;
			}
			if (getLastProjection() != null) {
				params.currentLocation = getLastFixedLocation();
			}
			if (params.mode.getRouteService() == RouteService.ONLINE) {
				OnlineRoutingEngine engine = app.getOnlineRoutingHelper().getEngineByKey(params.mode.getRoutingProfile());
				if (engine != null) {
					engine.updateRouteParameters(params, paramsChanged ? previousRoute : null);
				}
			}
			startRouteCalculationThread(params, paramsChanged, updateProgress);
		}
	}

	void updateProgressWithDelay(RouteCalculationParams params) {
		app.runInUIThread(() -> {
			updateProgressInUIThread(params);
		}, 300);
	}

	private void updateProgressInUIThread(RouteCalculationParams params) {
		Collection<RouteCalculationProgressListener> listeners = params.calculationProgressListener != null
				? Collections.singletonList(params.calculationProgressListener)
				: calculationProgressListeners;
		boolean isRouteBeingCalculated = !Algorithms.isEmpty(listeners);
		for (RouteCalculationProgressListener listener : listeners) {
			isRouteBeingCalculated &= onRouteCalculationUpdate(listener, params);
		}
		if (isRouteBeingCalculated) {
			updateProgressWithDelay(params);
		}
	}

	private void onRouteCalculationStart(@NonNull RouteCalculationParams params) {
		if (params.calculationProgressListener != null) {
			params.calculationProgressListener.onCalculationStart();
		} else {
			for (RouteCalculationProgressListener listener : calculationProgressListeners) {
				listener.onCalculationStart();
			}
		}
	}

	private boolean onRouteCalculationUpdate(@NonNull RouteCalculationProgressListener progressRoute,
	                                         @NonNull RouteCalculationParams params) {
		RouteCalculationProgress calculationProgress = params.calculationProgress;
		if (isRouteBeingCalculated()) {
			if (lastTask != null && lastTask.params == params) {
				progressRoute.onUpdateCalculationProgress((int) calculationProgress.getLinearProgress());
				if (calculationProgress.requestPrivateAccessRouting) {
					progressRoute.onRequestPrivateAccessRouting();
				}
				return true;
			}
		} else {
			if (calculationProgress.requestPrivateAccessRouting) {
				progressRoute.onRequestPrivateAccessRouting();
			}
			progressRoute.onCalculationFinish();
		}
		return false;
	}

	private void onRouteCalculationFinish(@NonNull RouteCalculationParams params) {
		if (params.calculationProgressListener != null) {
			params.calculationProgressListener.onCalculationFinish();
		} else {
			for (RouteCalculationProgressListener listener : calculationProgressListeners) {
				listener.onCalculationFinish();
			}
		}
	}

	protected boolean isCurrentSlowRoutingActive() {
		return lastTask != null
				&& lastTask.params.calculationProgress != null
				&& lastTask.params.calculationProgress.isSlowRoutingActive();
	}

	protected boolean hasCurrentMissingMaps() {
		return lastTask != null
				&& lastTask.params.calculationProgress != null
				&& lastTask.params.calculationProgress.hasMixedOrMissingMaps();
	}

	@Nullable
	protected FastRoutingState.Status getCurrentFastRoutingComplication() {
		return lastTask != null && lastTask.params.calculationProgress != null
				? lastTask.params.calculationProgress.getFastRoutingStatus() : null;
	}

	@Nullable
	protected MissingMapsCalculationResult getCurrentMissingMapsCalculationResult() {
		return lastTask != null && lastTask.params.calculationProgress != null ?
				lastTask.params.calculationProgress.missingMapsCalculationResult : null;
	}

	private class RouteRecalculationTask implements Runnable {

		private final RouteRecalculationHelper routingThreadHelper;
		private final RoutingHelper routingHelper;
		private final RouteCalculationParams params;
		private final boolean paramsChanged;
		private final boolean updateProgress;

		String routeCalcError;
		String routeCalcErrorShort;
		int evalWaitInterval;

		public RouteRecalculationTask(@NonNull RouteRecalculationHelper routingThreadHelper,
									  @NonNull RouteCalculationParams params, boolean paramsChanged,
									  boolean updateProgress) {
			this.routingThreadHelper = routingThreadHelper;
			this.routingHelper = routingThreadHelper.routingHelper;
			this.params = params;
			this.paramsChanged = paramsChanged;
			this.updateProgress = updateProgress;
			if (params.calculationProgress == null) {
				params.calculationProgress = new RouteCalculationProgress();
			}
		}

		public boolean isParamsChanged() {
			return paramsChanged;
		}

		public void stopCalculation() {
			params.calculationProgress.isCancelled = true;
		}

		private OsmandSettings getSettings() {
			return routingHelper.getSettings();
		}

		@Override
		public void run() {
			if (!updateProgress) {
				updateProgressWithDelay(params);
			}
			RouteProvider provider = routingHelper.getProvider();
			OsmandSettings settings = getSettings();
			// ITEM 4, LIVE. Try the short repair first. Returns null on ANY failure or any failed
			// sanity test, and the full search below then runs exactly as it always has - so the
			// worst case is one wasted short search, never a wrong route.
			RouteCalculationResult res = routingThreadHelper.tryRepairRoute(provider, params);
			boolean repaired = res != null;
			if (!repaired) {
				res = provider.calculateRouteImpl(params);
			}
			if (params.calculationProgress.isCancelled) {
				// Release the latch on EVERY exit. inFlight is cleared only by mayInstall, and
				// this return sits above it - so a cancelled early calculation would leave the
				// flag raised forever, confirm() would keep answering true, and every later
				// deviation would be suppressed with nothing running. The driver would simply
				// stop being rerouted, silently, for the rest of the session.
				CairoDriveEarlyReroute.reset();
				return;
			}
			boolean onlineSourceWithoutInternet = !res.isCalculated() &&
					params.mode.getRouteService().isOnline() && !settings.isInternetConnectionAvailable();
			if (onlineSourceWithoutInternet && settings.GPX_ROUTE_CALC_OSMAND_PARTS.get()) {
				if (params.previousToRecalculate != null && params.previousToRecalculate.isCalculated()) {
					res = provider.recalculatePartOfflineRoute(res, params);
				}
			}
			RouteCalculationResult prev = routingHelper.getRoute();
			OsmandApplication app = routingHelper.getApplication();
			// Closes the span opened at dispatch. `queue+search` here minus `setup+search` in
			// CD_ROUTE_TIMING is the time spent NOT searching - waiting for the single-threaded
			// executor, and running at a deliberately lowered thread priority. If that difference
			// turns out to be large, the fix is scheduling, not the router, and the router is where
			// six hypotheses have already been spent.
			long dispatchedAt = params.cairoDriveDispatchedAt;
			if (dispatchedAt > 0) {
				CairoDriveLogger.getInstance().log("CD_REROUTE", "finished"
						+ " repaired=" + repaired
						+ " totalMs=" + (System.currentTimeMillis() - dispatchedAt)
						+ " calculated=" + res.isCalculated()
						+ " missingMaps=" + res.hasMissingMaps()
						+ " cancelled=" + params.calculationProgress.isCancelled);
			}
			if (res.isCalculated() || res.hasMissingMaps()) {
				if (params.alternateResultListener != null) {
					params.alternateResultListener.onRouteCalculated(res);
				} else if (!CairoDriveEarlyReroute.mayInstall(
						routingHelper.isDeviatedFromRoute(), System.currentTimeMillis(),
						routingHelper.getLastFixedLocation())) {
					// An early start whose deviation never confirmed. The hysteresis has not been
					// weakened by starting sooner - it is applied here instead, on exactly the
					// evidence it would have had. The calculation is thrown away, which is the
					// entire cost of being early, and the route is untouched.
					routingThreadHelper.putCachedRoute(params, res);
				} else {
					routingThreadHelper.setNewRoute(prev, res, params.start);
					routingThreadHelper.putCachedRoute(params, res);
					// AFTER the driver already has their route, never before.
					routingThreadHelper.speculate(provider, params, res);
					// Shadow probe only when the live repair did NOT run. Once the repair is real,
					// timing a second one would just burn a search to re-measure what `repair USED`
					// already reports - and would do it on the same worker the next reroute needs.
					if (!repaired) {
						routingThreadHelper.runRepairProbe(provider, params);
					}
				}
			} else {
				// Same reasoning as the cancellation path: a calculation that produced nothing
				// must not leave the early-start latch raised, or the next deviation is swallowed.
				CairoDriveEarlyReroute.reset();
				evalWaitInterval = Math.max(3000, routingThreadHelper.evalWaitInterval * 3 / 2); // for Issue #3899
				// Same cap as above. This is the failure path - the route could not be calculated
				// at all - and it is the one that escalates fastest.
				evalWaitInterval = Math.min(evalWaitInterval, MAX_EVAL_WAIT_MS);
				if (onlineSourceWithoutInternet) {
					routeCalcError = app.getString(R.string.error_calculating_route)
							+ ":\n" + app.getString(R.string.internet_connection_required_for_online_route);
					routeCalcErrorShort = app.getString(R.string.error_calculating_route);
					app.showToastMessage(routeCalcError);
				} else {
					if (res.getErrorMessage() != null) {
						routeCalcError = app.getString(R.string.error_calculating_route) + ":\n" + res.getErrorMessage();
						routeCalcErrorShort = app.getString(R.string.error_calculating_route);
					} else {
						routeCalcError = app.getString(R.string.empty_route_calculated);
						routeCalcErrorShort = app.getString(R.string.empty_route_calculated);
					}
					app.getSettings().IGNORE_MISSING_MAPS = false; // reset on routing error
					app.showToastMessage(routeCalcError);
				}
			}
			if (!updateProgress) {
				app.runInUIThread(() -> routingThreadHelper.onRouteCalculationFinish(params));
			}
			app.getNotificationHelper().refreshNotification(NAVIGATION);
		}
	}

	private class RouteRecalculationExecutor extends ThreadPoolExecutor {

		public RouteRecalculationExecutor() {
			super(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
		}

		/**
		 * A route calculation is CPU bound for seconds at a time on a single thread. Left at the default
		 * priority it is scheduled as an equal of the UI thread, so on a mid-range phone a reroute and the map
		 * redraw fight for the same big core: the map stutters while rerouting, and the reroute itself is
		 * slowed by having to share.
		 * <p>
		 * THREAD_PRIORITY_DEFAULT is what a bare Java thread gets; Android's own guidance is that background
		 * work should sit at THREAD_PRIORITY_BACKGROUND. That is too low here - a backgrounded thread is
		 * confined to the "bg" cpuset on most devices and a reroute is latency critical, it is the thing the
		 * driver is waiting for. THREAD_PRIORITY_DEFAULT + THREAD_PRIORITY_LESS_FAVORABLE is one nice step
		 * below the UI: still on the foreground cpuset and still scheduled promptly, but it yields to the UI
		 * thread instead of competing with it.
		 * <p>
		 * Set from beforeExecute rather than a ThreadFactory so it also applies if the pool ever replaces its
		 * worker after an uncaught exception.
		 */
		@Override
		protected void beforeExecute(Thread t, Runnable r) {
			super.beforeExecute(t, r);
			try {
				android.os.Process.setThreadPriority(
						android.os.Process.THREAD_PRIORITY_DEFAULT + android.os.Process.THREAD_PRIORITY_LESS_FAVORABLE);
			} catch (RuntimeException e) {
				// setThreadPriority can throw SecurityException/IllegalArgumentException; never fatal here.
				LOG.warn("Could not lower route calculation thread priority", e);
			}
		}

		protected void afterExecute(Runnable r, Throwable t) {
			super.afterExecute(r, t);
			RouteRecalculationTask task = null;
			synchronized (routingHelper) {
				if (r instanceof Future<?>) {
					task = tasksMap.remove(r);
				}
			}
			if (t == null && task != null) {
				evalWaitInterval = task.evalWaitInterval;
				lastRouteCalcError = task.routeCalcError;
				lastRouteCalcErrorShort = task.routeCalcErrorShort;
			}
			lastTimeEvaluatedRoute = System.currentTimeMillis();
		}
	}
}
