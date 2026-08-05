package net.osmand.plus.cairodrive.providers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.GoogleTrafficHelper;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.router.RouteSegmentResult;
import net.osmand.router.RoutingConfiguration;
import net.osmand.util.MapUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The only part of this package that changes what the router does, rather than what the screen says.
 *
 * <h3>Two jobs, and only one of them is dangerous</h3>
 *
 * <ul>
 *   <li>{@link #adjustedSeconds(long)} corrects an ETA from {@link CairoDriveProviders#getFlow()}.
 *       Pure arithmetic on a snapshot: no state, no side effects, nothing to undo. If it is wrong
 *       the driver sees a wrong number.</li>
 *   <li>{@link #onLocationUpdate} turns a reported ROAD CLOSURE on the route ahead into a temporary
 *       nogo, so the offline engine routes around a street it has no way of knowing is shut. If
 *       this is wrong the driver is routed around a road that is open, possibly for ever, and has
 *       no way of discovering why.</li>
 * </ul>
 *
 * Everything below is shaped by the second one.
 *
 * <h3>The failure this class is built to make impossible</h3>
 *
 * OsmAnd's user-facing avoid-roads feature is PERSISTENT. {@code AvoidRoadsHelper.addImpassableRoad}
 * ends in {@code settings.addImpassableRoad(roadInfo)}, which writes into
 * {@code ImpassableRoadsStorage} - a preference that survives a restart, a force-stop and a Play
 * update, and that is reloaded into every routing config on the next launch. A traffic feature that
 * leaked one entry into that store would permanently corrupt the driver's routing with an
 * explanation that exists nowhere: no UI ever mentioned it, no log line survives the drive, and the
 * road simply stops being used.
 *
 * <p>So this class <b>never touches {@code OsmandSettings}, {@code ImpassableRoadsStorage} or
 * {@code AvoidRoadsHelper} at all</b>. It talks only to
 * {@link RoutingConfiguration.Builder#addImpassableRoad(long)} and
 * {@link RoutingConfiguration.Builder#removeImpassableRoad(long)}, which mutate an in-memory
 * {@code Set<Long>} held by the config builder and nothing else. That single decision bounds the
 * worst case: even a total failure of every cleanup path below cannot outlive the process. The
 * cleanup paths are there so the damage does not last the DRIVE either, but they are the second
 * line of defence, not the first.
 *
 * <h3>How the set is kept honest</h3>
 *
 * The applied set is not maintained by add/remove bookkeeping, because bookkeeping is exactly what
 * leaks when an error path is missed. Instead every pass RECONCILES: it recomputes the set of ids
 * that should be nogo right now, from live incidents against the live route, and diffs it against
 * {@link #applied}. Anything in {@code applied} that is not in the freshly computed set is removed,
 * whatever the reason it is no longer wanted - the closure cleared, the incident aged out of
 * {@link CairoDriveProviders#INCIDENTS_TTL_MS}, the route changed, the flag went off, arbitration
 * was lost. There is no per-reason removal path to forget, because there is no per-reason removal
 * path.
 *
 * <p>{@link #foreign} is the other half. An id that was ALREADY impassable when we first looked
 * belongs to the driver - they marked that road themselves - and is recorded so it is never added
 * by us and, more importantly, never removed by us. Without it a closure that happened to coincide
 * with a road the driver had permanently avoided would end with us deleting their setting from the
 * live config on the next reconcile.
 *
 * @see CairoDriveProviders
 */
public final class TrafficAwareRouting {

	private static final String TRACE_TAG = "CD_TRAFFIC_ROUTE";

	// ------------------------------------------------------------------ closure avoidance

	/**
	 * How near an incident coordinate has to fall to a route segment to be treated as being ON that
	 * segment, metres.
	 *
	 * <p>Generous rather than tight, and deliberately so in the direction that costs nothing: an
	 * incident that fails to match is simply not avoided, which is the behaviour of the whole app
	 * today. A false match, on the other hand, makes a road the driver is about to use impassable.
	 * 60 m is wider than any Cairo carriageway including the Ring Road, so a report anchored to the
	 * opposite direction of a dual carriageway still lands, while the next parallel street over -
	 * which in this city is rarely closer than 80 m - does not.
	 */
	private static final double MATCH_RADIUS_M = 60;

	/**
	 * Nothing closer than this ahead is made impassable, metres.
	 *
	 * <p>You cannot route around the road you are standing on. Making the segment under the car - or
	 * the one immediately in front of it - a nogo either produces no route at all or produces one
	 * that begins with a U-turn, and it does so at the exact moment the driver is committed to the
	 * manoeuvre. 400 m at Cairo arterial speeds is roughly twenty seconds of warning, which is the
	 * least that is any use.
	 *
	 * <p>This is a distance test and it is not sufficient on its own - an OSM way runs far longer
	 * than 400 m, so the way under the wheels is excluded by ID as well. See
	 * {@link #desiredNogoIds}.
	 */
	private static final double MIN_AHEAD_M = 400;

	/**
	 * A ceiling on simultaneous nogos.
	 *
	 * <p>Not a performance limit - it is a blast radius. A vocabulary change at the vendor, a bbox
	 * bug or a bad {@code magnitudeOfDelay} reading could classify a whole response as closures, and
	 * the difference between six impassable roads and sixty is the difference between a strange
	 * detour and a route that cannot be computed. Six also exceeds the number of genuine full
	 * closures a 25 km corridor plausibly carries.
	 */
	private static final int MAX_NOGO = 6;

	/**
	 * Floor between two reroutes triggered by THIS class, milliseconds.
	 *
	 * <p>The 2026-08-04 drive produced reroute after reroute after reroute, and a reroute costs 4-8 s
	 * of native search on the POCO C85 - it is the single most expensive thing this app does. A new
	 * nogo is worth paying that for; a new nogo every 150 s as incidents flicker in and out of a
	 * vendor feed is not. Three minutes means the closure has to still be reported on the following
	 * poll before it can cost a second search.
	 */
	private static final long REROUTE_MIN_INTERVAL_MS = 3 * 60 * 1000L;

	/**
	 * How long after our own {@link RoutingHelper#onSettingsChanged(net.osmand.plus.settings.backend.ApplicationMode)}
	 * call a route-cleared callback is attributed to us, milliseconds.
	 *
	 * <h3>The loop this prevents</h3>
	 *
	 * Adding a nogo has to trigger a recalculation, and {@code onSettingsChanged} recalculates by
	 * calling {@code clearCurrentRoute} first. So our own add fires the route-cleared callback
	 * within milliseconds. If that callback dropped the nogo we just added, the recalculation would
	 * run WITHOUT it, produce the identical route straight through the closure, and the next pass
	 * would add it again - a reroute loop that never converges and costs 4-8 s each time round.
	 *
	 * <p>The window is not load-bearing for leak safety, which is why a crude time guard is
	 * acceptable here. If it is too short we clear our own nogo and lose the avoidance, which is
	 * merely the behaviour of a build without this feature. If it is too long we keep a nogo across
	 * a genuine navigation stop, and the next reconcile - which finds no route and therefore no
	 * desired ids - removes it anyway. Neither direction can strand an entry.
	 */
	private static final long SELF_REROUTE_WINDOW_MS = 5 * 1000L;

	// ------------------------------------------------------------------ ETA correction

	/**
	 * Flow samples below this confidence are ignored entirely rather than weighted down.
	 *
	 * <p>{@code confidence} is the field TomTom won the flow slot for: it says how much probe data
	 * backs THAT segment, and in Cairo a Ring Road sample and an unnamed-alley sample differ by
	 * orders of magnitude. Averaging a 0.1-confidence reading in at one tenth weight still moves the
	 * ETA; dropping it leaves the offline engine's own estimate alone, which is the right answer
	 * when nobody has driven the road recently enough to say otherwise.
	 */
	private static final double MIN_FLOW_CONFIDENCE = 0.5;

	/**
	 * The most this class will stretch an ETA by, as a multiplier.
	 *
	 * <p>A sweep that happens to catch every sample at a red light reports a delay ratio near zero,
	 * and {@code base / ratio} is then an arrival time hours away. Clamping at 3x keeps a bad sweep
	 * looking like heavy traffic rather than like a fault, and 3x is already far beyond anything a
	 * genuine Cairo jam sustains across a whole route.
	 */
	private static final double MAX_ETA_STRETCH = 3.0;

	/** Below this the samples are not a corridor description and the ETA is left alone. */
	private static final int MIN_FLOW_SAMPLES = 2;

	/**
	 * Speed ratios Google's two congestion grades correspond to.
	 *
	 * <p>The Routes API returns a BAND - {@code TRAFFIC_JAM} or {@code SLOW} - not a speed, so a
	 * number has to be assigned to use it as flow. These are deliberately conservative: a stretch
	 * called SLOW is treated as still moving at two thirds, not crawling, because over-stating a
	 * delay pushes the ETA out and makes the driver distrust it. Under-stating it costs nothing
	 * they will notice.
	 */
	private static final double JAM_SPEED_RATIO = 0.25;
	private static final double SLOW_SPEED_RATIO = 0.65;

	/**
	 * How much more Google's route-measured stretch counts than TomTom's area-sampled one.
	 *
	 * <p>Google measured the road THIS CAR IS ON; TomTom sampled fixed points that are only
	 * incidentally on the route. For an ETA about this route the route-specific figure is worth
	 * more - but not infinitely more, because TomTom's samples are real speed observations while
	 * Google's are an interpretation of a congestion band. 2:1 lets Google lead without letting a
	 * single mis-graded stretch erase six honest measurements.
	 */
	private static final int GOOGLE_WEIGHT = 2;

	/**
	 * Google's snapshot, or null unless it is on, live and fresh.
	 *
	 * <p>Aged on {@code spansTimeMs} for the reason the layer does: a cheap delay poll carries the
	 * previous spans forward without re-fetching them, so ageing on the snapshot's own timestamp
	 * would let stale congestion keep influencing the ETA indefinitely.
	 */
	@Nullable
	private static GoogleTrafficHelper.TrafficSnapshot freshGoogleSnapshot() {
		GoogleTrafficHelper.TrafficSnapshot snapshot = GoogleTrafficHelper.getSnapshot();
		if (snapshot == null || snapshot.spans.isEmpty()) {
			return null;
		}
		long age = System.currentTimeMillis() - snapshot.spansTimeMs;
		return age >= 0 && age <= GoogleTrafficHelper.SNAPSHOT_TTL_MS ? snapshot : null;
	}

	// ------------------------------------------------------------------ evaluation cadence

	/**
	 * Floor between two reconciliations, milliseconds.
	 *
	 * <p>Matching an incident to the route means walking the route's own geometry - every point of
	 * every segment, with a haversine per point - once per reported closure. On an 8 km Cairo route
	 * that is a few thousand trigonometric evaluations, and {@link #onLocationUpdate} is a GPS
	 * callback that can land on the main thread, whose frame budget is already 46.9 ms with 61% of
	 * it in the map-overlay bucket. Doing that at fix rate would be exactly the kind of work the
	 * fork's own performance notes say not to put on the draw path.
	 *
	 * <p>15 s costs nothing in responsiveness: incidents are polled every 150 s and expire on a 10
	 * minute TTL, so nothing this reads can change faster than the throttle. It is also only reached
	 * when a closure is actually reported - the no-incidents case returns before any of this.
	 */
	private static final long EVALUATION_INTERVAL_MS = 15 * 1000L;

	// ------------------------------------------------------------------ state

	private static final Object LOCK = new Object();

	/**
	 * Road ids THIS class has made impassable, mapped to the wall clock it happened.
	 *
	 * <p>The single record of what there is to undo. Guarded by {@link #LOCK} rather than made
	 * concurrent because every mutation is part of a compare-and-apply that has to be atomic as a
	 * whole; a thread-safe map would make each line safe and the sequence still wrong.
	 */
	private static final Map<Long, Long> applied = new LinkedHashMap<>();

	/**
	 * Road ids that were impassable before we touched anything - the driver's own avoid-roads.
	 *
	 * <p>Never added by us and never removed by us. Grows only, and only to the handful of roads a
	 * driver has personally marked, so it needs no eviction.
	 */
	private static final Set<Long> foreign = new HashSet<>();

	private static volatile long lastRerouteMs;
	private static volatile long selfRerouteAtMs;
	private static volatile long lastEvaluationMs;

	private TrafficAwareRouting() {
	}

	// ------------------------------------------------------------------ ETA correction

	/**
	 * The offline engine's remaining time, corrected by observed flow.
	 *
	 * <p>Pure: it reads a snapshot and returns a number. Safe to call from the widget path.
	 *
	 * <p>Returns {@code baseSeconds} unchanged whenever there is nothing trustworthy to say - the
	 * feature off, no provider serving flow, samples stale, too few of them, or none above
	 * {@link #MIN_FLOW_CONFIDENCE}. Leaving the engine's own estimate alone is the correct failure:
	 * it is the number the app would have shown anyway.
	 *
	 * @param baseSeconds the offline engine's estimate; returned unchanged if not positive
	 */
	public static long adjustedSeconds(long baseSeconds) {
		if (baseSeconds <= 0) {
			return baseSeconds;
		}
		double stretch = stretchFactor();
		return stretch <= 1.0 ? baseSeconds : Math.round(baseSeconds * stretch);
	}

	/**
	 * The multiplier {@link #adjustedSeconds} applies, exposed so the UI can say HOW MUCH traffic
	 * is costing rather than only showing an ETA that has silently moved.
	 *
	 * <p>Split out of {@code adjustedSeconds} rather than duplicated: a banner that disagrees with
	 * the arrival time next to it is worse than no banner, and two copies of this arithmetic would
	 * eventually disagree. The clamp to {@link #MAX_ETA_STRETCH} therefore also bounds the number
	 * on screen, which is the point - an unbounded stretch from one bad flow sample would render
	 * as an absurd delay and destroy trust in the whole feature.
	 *
	 * @return 1.0 when there is nothing trustworthy to say, otherwise a value in
	 * (1.0, {@link #MAX_ETA_STRETCH}]
	 */
	public static double stretchFactor() {
		if (!BuildConfig.CAIRODRIVE_TRAFFIC_ROUTING) {
			return 1.0;
		}
		try {
			List<CairoDriveProviders.FlowSample> flow = CairoDriveProviders.getFlow();
			double ratioSum = 0;
			int counted = 0;
			for (CairoDriveProviders.FlowSample sample : flow) {
				if (sample == null || sample.confidence < MIN_FLOW_CONFIDENCE) {
					continue;
				}
				double ratio = sample.delayRatio();
				if (ratio <= 0 || Double.isNaN(ratio) || Double.isInfinite(ratio)) {
					continue;
				}
				ratioSum += ratio;
				counted++;
			}

			// TomTom's own stretch: point samples, so a mean is the best available estimator.
			// delayRatio() is a SPEED ratio - 1.0 free-flowing, 0.25 crawling - so a leg takes
			// LONGER by dividing, not by multiplying. Inverting this by accident turns a jam into an
			// improved ETA, which is the one arithmetic mistake here that looks plausible on screen.
			double tomtomStretch = counted >= MIN_FLOW_SAMPLES
					? Math.max(1.0, 1.0 / (ratioSum / counted))
					: 0;

			double googleStretch = googleStretch(freshGoogleSnapshot());

			double stretch;
			if (googleStretch > 0 && tomtomStretch > 0) {
				// Both talking. Google is weighted higher because it measured THIS ROUTE, while
				// TomTom sampled fixed points that are only incidentally on it - but not
				// infinitely higher, because TomTom's samples are real observations and Google's
				// bands are an interpretation of a colour.
				stretch = (GOOGLE_WEIGHT * googleStretch + tomtomStretch) / (GOOGLE_WEIGHT + 1);
			} else if (googleStretch > 0) {
				stretch = googleStretch;
			} else if (tomtomStretch > 0) {
				stretch = tomtomStretch;
			} else {
				return 1.0;
			}
			stretch = Math.min(MAX_ETA_STRETCH, stretch);
			return stretch > 1.0 ? stretch : 1.0;
		} catch (Throwable t) {
			// An ETA is not allowed to be the thing that breaks navigation.
			return 1.0;
		}
	}

	/**
	 * Google's spans as a time multiplier, weighted by how much of the route they actually cover.
	 *
	 * <h3>Why this is not an average of speed ratios</h3>
	 *
	 * The first version of this pooled Google's bands in with TomTom's samples and took a mean. A
	 * sensitivity sweep killed it: two jam spans with no TomTom data produced a mean ratio of 0.25
	 * and therefore a 4x stretch - <b>+90 minutes on a 45-minute drive</b> - because a mean treats
	 * two spans as if they were the entire route. They are not. A jam covering 800 m of a 20 km
	 * route costs the time lost over 800 m, and nothing at all over the other 19.2 km.
	 *
	 * <p>So this computes the physical thing instead. Time is distance over speed, summed:
	 * <pre>
	 *   multiplier = freeFraction + jamFraction/JAM_RATIO + slowFraction/SLOW_RATIO
	 * </pre>
	 * A route entirely in a jam gives 1/0.25 = 4.0, which is correct and is what the
	 * {@link #MAX_ETA_STRETCH} clamp is for. A route with a 5% jam gives 1.15 - a 15% longer
	 * journey, which is the right order of magnitude and is what the pooled version could not
	 * express.
	 *
	 * <p>Measured in METRES along the polyline rather than in point counts: Google's polyline
	 * points are not evenly spaced - they densify through curves and junctions, which is exactly
	 * where jams are - so counting points would systematically overstate congested coverage.
	 *
	 * @return a multiplier >= 1.0, or 0 when there is no usable Google data
	 */
	static double googleStretch(@Nullable GoogleTrafficHelper.TrafficSnapshot snapshot) {
		if (snapshot == null || snapshot.points.size() < 2 || snapshot.spans.isEmpty()) {
			return 0;
		}
		List<LatLon> points = snapshot.points;
		int n = points.size();
		double[] cumulative = new double[n];
		for (int i = 1; i < n; i++) {
			cumulative[i] = cumulative[i - 1] + MapUtils.getDistance(points.get(i - 1), points.get(i));
		}
		double total = cumulative[n - 1];
		if (total <= 0) {
			return 0;
		}
		double jamM = 0;
		double slowM = 0;
		for (GoogleTrafficHelper.CongestionSpan span : snapshot.spans) {
			if (span == null) {
				continue;
			}
			int from = Math.max(0, Math.min(span.start, n - 1));
			int to = Math.max(0, Math.min(span.end, n - 1));
			if (to <= from) {
				continue;
			}
			double length = cumulative[to] - cumulative[from];
			if (span.jam) {
				jamM += length;
			} else {
				slowM += length;
			}
		}
		// Clamped rather than trusted: overlapping spans in a malformed response could otherwise
		// make the congested length exceed the route and drive the free fraction negative.
		double congested = Math.min(total, jamM + slowM);
		if (congested <= 0) {
			return 0;
		}
		if (jamM + slowM > total) {
			double scale = total / (jamM + slowM);
			jamM *= scale;
			slowM *= scale;
		}
		double freeFraction = (total - jamM - slowM) / total;
		return freeFraction
				+ (jamM / total) / JAM_SPEED_RATIO
				+ (slowM / total) / SLOW_SPEED_RATIO;
	}

	/**
	 * How many of the seconds already on screen are traffic.
	 *
	 * <p>Takes the ALREADY-ADJUSTED remaining time, because that is the only number the UI has:
	 * {@code RoutingHelper.getLeftTime()} applies {@link #adjustedSeconds} before anyone sees it.
	 * Subtracting a freshly computed base from it would double-count. So invert instead -
	 * {@code adjusted = base * stretch}, hence {@code delay = adjusted * (1 - 1/stretch)} - which
	 * is exact for the same snapshot and stays consistent with the ETA beside it by construction.
	 *
	 * @return 0 when there is no delay worth reporting
	 */
	public static long delayFromAdjustedSeconds(long adjustedSeconds) {
		if (adjustedSeconds <= 0) {
			return 0;
		}
		double stretch = stretchFactor();
		if (stretch <= 1.0) {
			return 0;
		}
		return Math.round(adjustedSeconds * (1.0 - 1.0 / stretch));
	}

	// ------------------------------------------------------------------ closure avoidance

	/**
	 * Reconciles the nogo set against what is currently reported and currently routed.
	 *
	 * <p>Call on a GPS fix while navigating. Cheap when there is nothing to do: with the feature off
	 * or no closures reported it costs a BuildConfig read and an empty-map test, and it does no work
	 * at all on the routing thread.
	 *
	 * <p>Safe to call redundantly. Every invocation recomputes the desired set from scratch, so
	 * calling it twice in a row is a no-op rather than a double application.
	 */
	public static void onLocationUpdate(@Nullable RoutingHelper helper, @Nullable Location loc) {
		if (helper == null || loc == null) {
			return;
		}
		try {
			OsmandApplication app = helper.getApplication();
			if (app == null) {
				return;
			}
			// Reconciling needs a route to match incidents against. While one is being computed there
			// is none, so every id would look unjustified and be dropped - including, during OUR own
			// recalculation, the very nogo that asked for it. The recalculation would then run without
			// it and come straight back through the closure.
			//
			// Deferring is safe in a way that a special case would not be: nothing is applied and
			// nothing is removed, and the next fix after the calculation settles reconciles normally.
			// The window is bounded by REROUTE_MIN_INTERVAL_MS at worst, and reset() ignores it
			// entirely, so no id can be stranded by it.
			if (withinSelfReroute() || helper.isRouteBeingCalculated()) {
				return;
			}

			// Throttled AFTER the deferral tests and BEFORE any route walking, so a fix that arrives
			// too soon costs three field reads. A negative age is a backwards clock jump and is
			// treated as due rather than as a reason to stop evaluating until it catches up.
			long now = System.currentTimeMillis();
			long sinceEvaluation = now - lastEvaluationMs;
			if (lastEvaluationMs > 0 && sinceEvaluation >= 0
					&& sinceEvaluation < EVALUATION_INTERVAL_MS) {
				return;
			}
			lastEvaluationMs = now;

			// The flag is checked here rather than only at the top, because a build with the flag off
			// must still be able to CLEAN UP. There is no path that turns it off mid-process today,
			// but a class whose undo is conditional on the same flag as its do is one refactor away
			// from being unable to undo.
			boolean enabled = BuildConfig.CAIRODRIVE_TRAFFIC_ROUTING
					&& CairoDriveProviders.resolve(
					CairoDriveProviders.Capability.TRAFFIC_INCIDENTS) != null;

			// Note what is NOT done here: an explicit clear when CairoDriveProviders.currentGeneration()
			// moves. A reroute replaces the geometry the ids were matched against, so they all become
			// unjustified - but reconcile() already derives the desired set from the CURRENT route and
			// removes everything outside it, so a generation check would be a second mechanism for a
			// job one mechanism already does. Two mechanisms that must agree are how the disagreement
			// gets in.
			Set<Long> desired = enabled && helper.isFollowingMode()
					? desiredNogoIds(helper, loc)
					: Collections.<Long>emptySet();
			reconcile(app, desired);
		} catch (Throwable t) {
			CairoDriveLogger.getInstance().log(TRACE_TAG, "reconcile failed", t);
			// A throw part-way through leaves the applied set in an unknown relationship to the road,
			// and the only state that is definitely safe is none at all.
			try {
				clearAll(helper.getApplication(), "reconcile threw");
			} catch (Throwable ignored) {
				// Nothing further is available; the ids die with the process regardless.
			}
		}
	}

	/**
	 * The driver ended navigation, or the route was cleared for a reason that is not our own
	 * recalculation.
	 *
	 * <p>Wire this at the same site as {@link CairoDriveProviders#resetRouteState()}. Calls that
	 * land inside {@link #SELF_REROUTE_WINDOW_MS} of a reroute we asked for are ignored - see that
	 * constant for the loop it prevents.
	 */
	public static void onRouteCleared(@Nullable OsmandApplication app) {
		if (withinSelfReroute()) {
			return;
		}
		clearAll(app, "route cleared");
	}

	/**
	 * Whether the route was cleared by a recalculation this class asked for.
	 *
	 * <p>A zero stamp - no reroute has ever been requested - is deliberately NOT inside the window,
	 * so a fresh process cleans up normally instead of treating its first route change as ours.
	 */
	private static boolean withinSelfReroute() {
		long stamp = selfRerouteAtMs;
		if (stamp <= 0) {
			return false;
		}
		long since = System.currentTimeMillis() - stamp;
		return since >= 0 && since < SELF_REROUTE_WINDOW_MS;
	}

	/**
	 * Unconditional teardown: navigation stopped, the app is going away, or something upstream wants
	 * every trace of this feature gone.
	 *
	 * <p>Unlike {@link #onRouteCleared} this honours no self-reroute window and no flag. It is the
	 * call to reach for when in doubt.
	 */
	public static void reset(@Nullable OsmandApplication app) {
		clearAll(app, "reset");
	}

	/**
	 * Road ids currently made impassable by this class. Empty in a stock build. Never null.
	 *
	 * <p>Exists so a caller can see WHICH roads this class is suppressing, not just how many.
	 * That distinction matters when a route looks wrong: "traffic routing removed 3 roads" does
	 * not let anyone check whether it removed the right ones, and these ids are cross-referenceable
	 * against OSM directly.
	 */
	@NonNull
	public static Set<Long> appliedIds() {
		synchronized (LOCK) {
			return new LinkedHashSet<>(applied.keySet());
		}
	}

	/**
	 * The ETA merge, as one greppable field group.
	 *
	 * <p>Every input to the decision, not just the answer. A drive log that says only
	 * {@code stretch=1.31} cannot tell anyone WHY - whether Google saw a jam, whether TomTom
	 * disagreed, whether one of them was absent because its budget was spent or its snapshot had
	 * aged out. Those four situations produce the same single number and need different fixes, and
	 * the whole method here is to decide from the log rather than from a guess.
	 *
	 * <p>{@code src=} is the one to read first: {@code both} / {@code google} / {@code tomtom} /
	 * {@code none} says which providers were actually talking on this drive.
	 */
	@NonNull
	public static String describeStretch() {
		if (!BuildConfig.CAIRODRIVE_TRAFFIC_ROUTING) {
			return "traffic=off";
		}
		try {
			List<CairoDriveProviders.FlowSample> flow = CairoDriveProviders.getFlow();
			int usable = 0;
			double ratioSum = 0;
			for (CairoDriveProviders.FlowSample sample : flow) {
				if (sample != null && sample.confidence >= MIN_FLOW_CONFIDENCE) {
					double r = sample.delayRatio();
					if (r > 0 && !Double.isNaN(r) && !Double.isInfinite(r)) {
						ratioSum += r;
						usable++;
					}
				}
			}
			double tomtom = usable >= MIN_FLOW_SAMPLES ? Math.max(1.0, 1.0 / (ratioSum / usable)) : 0;

			GoogleTrafficHelper.TrafficSnapshot snapshot = freshGoogleSnapshot();
			double google = googleStretch(snapshot);
			int jamSpans = 0;
			int slowSpans = 0;
			if (snapshot != null) {
				for (GoogleTrafficHelper.CongestionSpan span : snapshot.spans) {
					if (span != null) {
						if (span.jam) {
							jamSpans++;
						} else {
							slowSpans++;
						}
					}
				}
			}
			String src = google > 0 && tomtom > 0 ? "both"
					: google > 0 ? "google" : tomtom > 0 ? "tomtom" : "none";
			return String.format(Locale.US,
					"traffic src=%s stretch=%.3f google=%.3f tomtom=%.3f"
							+ " ttSamples=%d/%d gJam=%d gSlow=%d gAgeS=%d",
					src, stretchFactor(), google, tomtom, usable, flow.size(),
					jamSpans, slowSpans,
					snapshot == null ? -1
							: (System.currentTimeMillis() - snapshot.spansTimeMs) / 1000);
		} catch (Throwable t) {
			return "traffic=error";
		}
	}

	/**
	 * The applied set as a CD_ROUTE-greppable string, for a drive log.
	 *
	 * <p>Capped: a pathological day could apply many closures and a log line is not the place for
	 * an unbounded list. The count is always exact even when the ids are truncated, so the line
	 * cannot understate what happened.
	 */
	@NonNull
	public static String describeApplied() {
		Set<Long> ids = appliedIds();
		if (ids.isEmpty()) {
			return "nogo=0";
		}
		StringBuilder builder = new StringBuilder("nogo=").append(ids.size()).append(" ids=");
		int shown = 0;
		for (Long id : ids) {
			if (shown >= MAX_LOGGED_IDS) {
				builder.append(",...");
				break;
			}
			if (shown > 0) {
				builder.append(',');
			}
			builder.append(id);
			shown++;
		}
		return builder.toString();
	}

	private static final int MAX_LOGGED_IDS = 8;

	// ------------------------------------------------------------------ the reconciler

	/**
	 * Which road ids SHOULD be impassable right now, derived from scratch.
	 *
	 * <p>Only full closures, only on the current route, and only far enough ahead to be avoidable.
	 * An incident that fails any of those is not a smaller nogo, it is no nogo: a lane closure slows
	 * a road rather than removing it, and treating it as removal is how a router sends a driver ten
	 * minutes round a lane cone.
	 */
	@NonNull
	private static Set<Long> desiredNogoIds(@NonNull RoutingHelper helper, @NonNull Location loc) {
		List<CairoDriveProviders.TrafficIncident> incidents = CairoDriveProviders.getIncidents();
		if (incidents.isEmpty()) {
			return Collections.emptySet();
		}
		RouteCalculationResult route = helper.getRoute();
		if (route == null || !route.isCalculated()) {
			return Collections.emptySet();
		}
		List<RouteSegmentResult> segments = route.getOriginalRoute();
		if (segments == null || segments.isEmpty()) {
			return Collections.emptySet();
		}

		// The road the car is on RIGHT NOW, excluded outright below. An OSM way can run for
		// kilometres, so an incident comfortably past MIN_AHEAD_M can still sit on the very way the
		// wheels are on - and making that way impassable does not route around the closure, it
		// deletes the road out from under the driver mid-manoeuvre.
		RouteSegmentResult current = route.getCurrentSegmentResult();
		RouteDataObject currentObject = current != null ? current.getObject() : null;
		long currentId = currentObject != null ? currentObject.getId() : 0;

		Set<Long> desired = new LinkedHashSet<>();
		for (CairoDriveProviders.TrafficIncident incident : incidents) {
			if (incident == null || !incident.closure) {
				continue;
			}
			// Straight-line from the car, not distance along the route. It under-estimates whenever
			// the road bends, so it rejects slightly MORE incidents than a route-following measure
			// would - which is the direction that costs an avoidance rather than causing a bad one.
			if (MapUtils.getDistance(loc.getLatitude(), loc.getLongitude(),
					incident.at.getLatitude(), incident.at.getLongitude()) < MIN_AHEAD_M) {
				continue;
			}
			Long id = matchToRoute(incident.at, segments);
			if (id == null || id == 0 || id == currentId) {
				continue;
			}
			// An id the driver marked themselves is already impassable and is not ours to manage.
			// Adding it would make us the apparent owner and the next reconcile would delete it.
			synchronized (LOCK) {
				if (foreign.contains(id)) {
					continue;
				}
			}
			desired.add(id);
			if (desired.size() >= MAX_NOGO) {
				break;
			}
		}
		addGoogleJams(desired, segments, loc, currentId);
		return desired;
	}

	/**
	 * Google's severe jams, added to the same nogo set - with gates a closure does not need.
	 *
	 * <h3>Why a jam cannot be treated like a closure</h3>
	 *
	 * A closure is a fact about the road and it stays true whether or not this car is near it. A
	 * jam is a measurement of the road THIS CAR IS ON, and Google only reports spans along the
	 * current route. So the moment an avoidance succeeds, the jammed road leaves the route, leaves
	 * the snapshot, and the evidence for avoiding it disappears - the nogo lifts, the router
	 * offers the road back, and the car oscillates between two routes at the junction. That is the
	 * failure mode, and it is why "just make jams impassable" is wrong.
	 *
	 * <p>Four gates, on top of the ahead/on-route/not-current tests the closures already pass:
	 * <ul>
	 *   <li><b>Severity</b> - {@code TRAFFIC_JAM} only. SLOW is what the ETA stretch is for.</li>
	 *   <li><b>Length</b> - a jam shorter than {@link #MIN_JAM_POINTS} polyline points is a queue
	 *       at a light, and rerouting round a traffic light is worse than waiting at it.</li>
	 *   <li><b>Persistence</b> - the same road has to be reported jammed in
	 *       {@link #JAM_CONFIRMATIONS} consecutive snapshots. One poll is noise; a jam that is
	 *       still there on the next fetch is a jam.</li>
	 *   <li><b>Hold</b> - once avoided, the id stays in the desired set for {@link #JAM_HOLD_MS}
	 *       even after it drops out of the data. This is the anti-oscillation gate: the evidence
	 *       vanishing because the avoidance WORKED must not immediately undo the avoidance.</li>
	 * </ul>
	 *
	 * <p>Capped by {@link #MAX_NOGO} jointly with closures, and closures are added first so a real
	 * road closure always wins the last slot over a jam.
	 */
	private static void addGoogleJams(@NonNull Set<Long> desired,
	                                  @NonNull List<RouteSegmentResult> segments,
	                                  @NonNull Location loc, long currentId) {
		long now = System.currentTimeMillis();
		GoogleTrafficHelper.TrafficSnapshot snapshot = freshGoogleSnapshot();
		Set<Long> seenThisPoll = new LinkedHashSet<>();

		if (snapshot != null && snapshot.version != lastJamVersion) {
			lastJamVersion = snapshot.version;
			for (GoogleTrafficHelper.CongestionSpan span : snapshot.spans) {
				if (span == null || !span.jam) {
					continue;
				}
				if (span.end - span.start < MIN_JAM_POINTS) {
					continue;
				}
				// Anchor on the MIDDLE of the span. Its start is where the queue currently ends,
				// which moves backwards as the jam grows and can already be behind the car; the
				// middle is the stretch actually worth going around.
				int mid = (span.start + span.end) / 2;
				if (mid < 0 || mid >= snapshot.points.size()) {
					continue;
				}
				LatLon at = snapshot.points.get(mid);
				if (MapUtils.getDistance(loc.getLatitude(), loc.getLongitude(),
						at.getLatitude(), at.getLongitude()) < MIN_AHEAD_M) {
					continue;
				}
				Long id = matchToRoute(at, segments);
				if (id == null || id == 0 || id == currentId) {
					continue;
				}
				synchronized (LOCK) {
					if (foreign.contains(id)) {
						continue;
					}
				}
				seenThisPoll.add(id);
			}
			// Counted per SNAPSHOT VERSION, not per fix. Location updates arrive about once a
			// second and the traffic snapshot refreshes on a much slower cadence, so counting per
			// fix would confirm a one-poll blip within seconds and defeat the gate entirely.
			for (Long id : seenThisPoll) {
				Integer seen = jamSeen.get(id);
				jamSeen.put(id, seen == null ? 1 : seen + 1);
			}
			// A road that has dropped out of the data loses its progress towards confirmation, but
			// NOT its hold if it already earned one - see jamHeldUntil below.
			jamSeen.keySet().retainAll(seenThisPoll);

			// One line per SNAPSHOT, which is the cadence the decision actually changes on - not
			// per fix, which would be ~1/s of near-identical lines. This is the line that answers
			// "why did it avoid that road", and just as often "why did it NOT": a jam sitting at
			// seen=1 forever means the gate is working and the jam is flickering, which is a
			// different conclusion from no jam being seen at all.
			if (CairoDriveLogger.isEnabled()) {
				StringBuilder counts = new StringBuilder();
				for (Map.Entry<Long, Integer> e : jamSeen.entrySet()) {
					if (counts.length() > 0) {
						counts.append(',');
					}
					counts.append(e.getKey()).append(':').append(e.getValue());
				}
				CairoDriveLogger.getInstance().log(TRACE_TAG, "jams"
						+ " ver=" + snapshot.version
						+ " spans=" + snapshot.spans.size()
						+ " candidates=" + seenThisPoll.size()
						+ " needConfirm=" + JAM_CONFIRMATIONS
						+ " seen=[" + counts + "]"
						+ " held=" + jamHeldUntil.size()
						+ " holdMs=" + JAM_HOLD_MS);
			}
		}

		for (Map.Entry<Long, Integer> entry : jamSeen.entrySet()) {
			if (entry.getValue() >= JAM_CONFIRMATIONS) {
				jamHeldUntil.put(entry.getKey(), now + JAM_HOLD_MS);
			}
		}
		for (Iterator<Map.Entry<Long, Long>> it = jamHeldUntil.entrySet().iterator(); it.hasNext(); ) {
			Map.Entry<Long, Long> entry = it.next();
			if (entry.getValue() < now) {
				it.remove();
				continue;
			}
			if (desired.size() >= MAX_NOGO) {
				break;
			}
			desired.add(entry.getKey());
		}
	}

	/** A jam shorter than this is a queue at a light, not a road worth going around. */
	private static final int MIN_JAM_POINTS = 4;
	/** Consecutive SNAPSHOTS, not fixes - see the counting note in {@link #addGoogleJams}. */
	private static final int JAM_CONFIRMATIONS = 2;
	/**
	 * How long an avoided jam stays avoided after it leaves the data.
	 *
	 * <p>The anti-oscillation gate. Five minutes is longer than a reroute takes to commit and
	 * shorter than a jam typically lasts, so the car cannot be offered the road back at the same
	 * junction it just left.
	 */
	private static final long JAM_HOLD_MS = 5 * 60 * 1000L;

	/** Main/routing-thread only, like the rest of the reconciler. */
	private static final Map<Long, Integer> jamSeen = new LinkedHashMap<>();
	private static final Map<Long, Long> jamHeldUntil = new LinkedHashMap<>();
	private static int lastJamVersion = -1;

	/**
	 * The road id of the route segment an incident sits on, or null when it sits on none of them.
	 *
	 * <p>Walks the route's own geometry rather than asking the native geocoder for the road under a
	 * coordinate, for two reasons: the geocoder answer is asynchronous and would have to be
	 * correlated back to a route that may have changed by the time it returns, and a closure that is
	 * NOT on the route needs no nogo at all - avoiding a road the driver was never going to use
	 * changes nothing except the chance of getting it wrong.
	 *
	 * <p>Proximity only. Whether the incident is far enough ahead to be worth avoiding is decided by
	 * the caller against the live fix, because {@code getOriginalRoute()} hands back the route from
	 * its FIRST point rather than from the car - so a distance accumulated while walking it measures
	 * from the trip origin, and would read as "far ahead" for everything after the first few hundred
	 * metres of any drive.
	 */
	@Nullable
	private static Long matchToRoute(@NonNull LatLon at, @NonNull List<RouteSegmentResult> segments) {
		double bestDistance = MATCH_RADIUS_M;
		Long best = null;
		for (RouteSegmentResult segment : segments) {
			if (segment == null) {
				continue;
			}
			RouteDataObject object = segment.getObject();
			if (object == null) {
				continue;
			}
			int start = segment.getStartPointIndex();
			int end = segment.getEndPointIndex();
			// A segment can be traversed in either direction, so end may be below start. Step towards
			// end rather than assuming ascending indices; assuming it silently skips every reversed
			// segment, which on a route with any doubling back is a large fraction of them.
			int step = end >= start ? 1 : -1;
			for (int i = start; step > 0 ? i <= end : i >= end; i += step) {
				if (i < 0 || i >= object.getPointsLength()) {
					break;
				}
				double lat = MapUtils.get31LatitudeY(object.getPoint31YTile(i));
				double lon = MapUtils.get31LongitudeX(object.getPoint31XTile(i));
				double distance = MapUtils.getDistance(at.getLatitude(), at.getLongitude(), lat, lon);
				if (distance < bestDistance) {
					bestDistance = distance;
					best = object.getId();
				}
			}
		}
		return best;
	}

	/**
	 * Applies the difference between what is wanted and what is applied, and nothing else.
	 *
	 * <p>Removals happen before additions. If the two ever contended for the same id - they cannot
	 * today, but a future caller could pass a set built differently - removing first and adding
	 * second leaves the road passable rather than impassable, which is the direction that fails
	 * safe.
	 *
	 * <p>Only an ADDITION triggers a recalculation. A removal means a road reopened: the current
	 * route is still perfectly valid, merely no longer the fastest, and it is not worth 4-8 s of
	 * native search on this hardware to find that out.
	 */
	private static void reconcile(@Nullable OsmandApplication app, @NonNull Set<Long> desired) {
		if (app == null) {
			return;
		}
		List<Long> toRemove = new ArrayList<>();
		List<Long> toAdd = new ArrayList<>();
		synchronized (LOCK) {
			if (applied.isEmpty() && desired.isEmpty()) {
				return;
			}
			for (Long id : applied.keySet()) {
				if (!desired.contains(id)) {
					toRemove.add(id);
				}
			}
			for (Long id : desired) {
				if (!applied.containsKey(id)) {
					toAdd.add(id);
				}
			}
		}
		if (toRemove.isEmpty() && toAdd.isEmpty()) {
			return;
		}
		mutate(app, toRemove, toAdd, !toAdd.isEmpty());
	}

	/** Drops every id this class applied, whatever the reason. The only removal path. */
	private static void clearAll(@Nullable OsmandApplication app, @NonNull String reason) {
		if (app == null) {
			return;
		}
		List<Long> toRemove;
		synchronized (LOCK) {
			if (applied.isEmpty()) {
				return;
			}
			toRemove = new ArrayList<>(applied.keySet());
		}
		CairoDriveLogger.getInstance().log(TRACE_TAG,
				"clearing " + toRemove.size() + " nogo(s) - " + reason);
		// No recalculation. Whatever cleared the route is already recalculating, or navigation has
		// stopped and there is nothing to recalculate.
		mutate(app, toRemove, Collections.<Long>emptyList(), false);
	}

	/**
	 * The one place a {@code RoutingConfiguration.Builder} is mutated, marshalled onto the UI thread.
	 *
	 * <p>{@code Builder.impassableRoadLocations} is a plain {@code HashSet} that the routing thread
	 * copies while building a config, and upstream's own {@code AvoidRoadsHelper} mutates it from
	 * the UI thread. Doing the same keeps this feature's writes serialised against that helper's,
	 * which is the contention that actually exists; the residual race against a config build in
	 * progress is upstream's and is not made worse here.
	 *
	 * <p>{@link #applied} is updated inside the same UI-thread block as the builder mutation, so the
	 * record of what to undo can never disagree with what was done.
	 */
	private static void mutate(@NonNull OsmandApplication app, @NonNull List<Long> toRemove,
	                           @NonNull List<Long> toAdd, boolean recalculate) {
		app.runInUIThread(() -> {
			int removed = 0;
			int added = 0;
			try {
				List<RoutingConfiguration.Builder> builders = app.getAllRoutingConfigs();
				if (builders == null) {
					return;
				}
				synchronized (LOCK) {
					for (Long id : toRemove) {
						if (!applied.containsKey(id)) {
							// Cleared by another pass between the diff and here. Not ours any more.
							continue;
						}
						for (RoutingConfiguration.Builder builder : builders) {
							builder.removeImpassableRoad(id);
						}
						applied.remove(id);
						removed++;
					}
					for (Long id : toAdd) {
						if (applied.containsKey(id) || foreign.contains(id)) {
							continue;
						}
						// Checked HERE and not at diff time: this is the last instant before the write,
						// and it is the only instant at which "was this already impassable" has an
						// answer that cannot have changed underneath us. An id already present belongs
						// to the driver, and recording it in foreign means no later pass ever removes
						// it on our behalf.
						boolean alreadyThere = false;
						for (RoutingConfiguration.Builder builder : builders) {
							if (builder.getImpassableRoadLocations().contains(id)) {
								alreadyThere = true;
								break;
							}
						}
						if (alreadyThere) {
							foreign.add(id);
							continue;
						}
						for (RoutingConfiguration.Builder builder : builders) {
							builder.addImpassableRoad(id);
						}
						applied.put(id, System.currentTimeMillis());
						added++;
					}
				}
			} catch (Throwable t) {
				CairoDriveLogger.getInstance().log(TRACE_TAG, "nogo mutation failed", t);
				return;
			}
			if (removed == 0 && added == 0) {
				return;
			}
			int remaining;
			synchronized (LOCK) {
				remaining = applied.size();
			}
			CairoDriveLogger.getInstance().log(TRACE_TAG, String.format(Locale.US,
					"nogo added=%d removed=%d active=%d", added, removed, remaining));
			if (added > 0 && recalculate) {
				maybeRecalculate(app);
			}
		});
	}

	/**
	 * Asks for a recalculation, at most once per {@link #REROUTE_MIN_INTERVAL_MS}.
	 *
	 * <p>{@link #selfRerouteAtMs} is stamped BEFORE the call, not after. {@code onSettingsChanged}
	 * clears the current route synchronously, so the route-cleared callback can arrive before this
	 * method returns; a stamp written afterwards would land too late to be seen by it and
	 * {@link #onRouteCleared} would drop the nogo that caused the reroute.
	 */
	private static void maybeRecalculate(@NonNull OsmandApplication app) {
		long now = System.currentTimeMillis();
		long since = now - lastRerouteMs;
		if (lastRerouteMs > 0 && since >= 0 && since < REROUTE_MIN_INTERVAL_MS) {
			CairoDriveLogger.getInstance().log(TRACE_TAG,
					"nogo changed but a reroute was asked for " + (since / 1000)
							+ " s ago - deferred, the next pass will ask again");
			return;
		}
		lastRerouteMs = now;
		selfRerouteAtMs = now;
		try {
			RoutingHelper helper = app.getRoutingHelper();
			if (helper != null) {
				CairoDriveLogger.getInstance().log(TRACE_TAG,
						"requesting recalculation around a reported closure");
				// null mode means "whatever profile is navigating", and the call is a no-op unless a
				// route actually exists.
				helper.onSettingsChanged(null);
			}
		} catch (Throwable t) {
			CairoDriveLogger.getInstance().log(TRACE_TAG, "recalculation request failed", t);
		}
	}
}
