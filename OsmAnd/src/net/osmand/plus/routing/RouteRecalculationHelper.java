package net.osmand.plus.routing;

import static net.osmand.plus.notifications.OsmandNotification.NotificationType.NAVIGATION;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.plus.R;
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
		routingHelper.setRoute(res);
		boolean newRoute = !prevRoute.isCalculated();
		if (isFollowingMode()) {
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
			if (isRouteBeingCalculated()) {
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
	@Nullable
	RouteCalculationResult tryRepairRoute(@NonNull RouteProvider provider,
	                                      @NonNull RouteCalculationParams params) {
		if (!BuildConfig.CAIRODRIVE_ROUTE_REPAIR) {
			return null;
		}
		try {
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
			if (repaired == null || !repaired.isCalculated()) {
				reject = "notCalculated";
			} else {
				int repairDistM = repaired.getWholeDistance();
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
						+ " cutoffM=" + repairCutoffM()
						+ " consecutiveRepairs=" + consecutiveRepairs + " - falling back to full search");
				return null;
			}
			consecutiveRepairs = 0;
			CairoDriveLogger.getInstance().log("CD_REROUTE", "repair USED"
					+ " ms=" + elapsedMs
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
				} else {
					routingThreadHelper.setNewRoute(prev, res, params.start);
					// AFTER the driver already has their route, never before.
					// Shadow probe only when the live repair did NOT run. Once the repair is real,
					// timing a second one would just burn a search to re-measure what `repair USED`
					// already reports - and would do it on the same worker the next reroute needs.
					if (!repaired) {
						routingThreadHelper.runRepairProbe(provider, params);
					}
				}
			} else {
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
