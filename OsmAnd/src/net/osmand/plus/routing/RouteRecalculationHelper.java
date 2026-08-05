package net.osmand.plus.routing;

import static net.osmand.plus.notifications.OsmandNotification.NotificationType.NAVIGATION;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
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
		if ((!isRouteBeingCalculated() && System.currentTimeMillis() - lastTimeEvaluatedRoute > evalWaitInterval)
				|| paramsChanged || !onlyStartPointChanged) {
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
			if (recalculateCountInInterval < RECALCULATE_THRESHOLD_COUNT_CAUSING_FULL_RECALCULATE
					|| (gpxRoute != null && isDeviatedFromRoute())) {
				params.previousToRecalculate = previousRoute;
			} else {
				recalculateCountInInterval = 0;
			}
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
			if (res.isCalculated() || res.hasMissingMaps()) {
				if (params.alternateResultListener != null) {
					params.alternateResultListener.onRouteCalculated(res);
				} else {
					routingThreadHelper.setNewRoute(prev, res, params.start);
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
