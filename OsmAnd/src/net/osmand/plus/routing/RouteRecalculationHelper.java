package net.osmand.plus.routing;

import static net.osmand.plus.notifications.OsmandNotification.NotificationType.NAVIGATION;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
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
						evalWaitInterval = Math.min(evalWaitInterval, 120000);
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

	/** Distance ahead on the old route to aim the probe at - HERE and TomTom both work at this scale. */
	private static final int REPAIR_PROBE_REJOIN_M = 600;
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
			Location rejoin = previous.getRouteLocationByDistance(REPAIR_PROBE_REJOIN_M);
			if (rejoin == null) {
				// Less than 600 m of route left. Nothing to rejoin to, and nothing to learn.
				return;
			}
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
			long straightM = Math.round(MapUtils.getDistance(
					params.start.getLatitude(), params.start.getLongitude(),
					probe.end.getLatitude(), probe.end.getLongitude()));
			CairoDriveLogger.getInstance().log("CD_REROUTE", "repairProbe"
					+ " repairMs=" + elapsedMs
					+ " straightM=" + straightM
					+ " rejoinAheadM=" + REPAIR_PROBE_REJOIN_M
					+ " ok=" + probeResult.isCalculated()
					+ " - result DISCARDED, navigation unaffected");
		} catch (Throwable t) {
			CairoDriveLogger.getInstance().log("CD_REROUTE", "repairProbe failed "
					+ t.getClass().getSimpleName() + ": " + t.getMessage());
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
			RouteCalculationResult res = provider.calculateRouteImpl(params);
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
					routingThreadHelper.runRepairProbe(provider, params);
				}
			} else {
				evalWaitInterval = Math.max(3000, routingThreadHelper.evalWaitInterval * 3 / 2); // for Issue #3899
				evalWaitInterval = Math.min(evalWaitInterval, 120000);
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
