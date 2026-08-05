package net.osmand.plus.routing;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.LocationsHolder;
import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.data.LatLon;
import net.osmand.data.ValueHolder;
import net.osmand.plus.NavigationService;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.auto.NavigationSession;
import net.osmand.plus.cairodrive.CairoDriveEta;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.plus.cairodrive.CairoDriveOffRoute;
import net.osmand.plus.helpers.TargetPointsHelper;
import net.osmand.plus.helpers.TargetPoint;
import net.osmand.plus.notifications.OsmandNotification.NotificationType;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.routing.GPXRouteParams.GPXRouteParamsBuilder;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmAndAppCustomization.OsmAndAppCustomizationListener;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.settings.enums.RouteCalculationMethod;
import net.osmand.plus.simulation.SimulationProvider;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.router.FastRoutingState;
import net.osmand.router.GpxRouteApproximation;
import net.osmand.router.MissingMapsCalculationResult;
import net.osmand.router.RouteExporter;
import net.osmand.router.RoutePlannerFrontEnd.GpxPoint;
import net.osmand.router.RouteSegmentResult;
import net.osmand.shared.gpx.GpxFile;
import net.osmand.shared.settings.enums.MetricsConstants;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;

public class RoutingHelper {

	private static final org.apache.commons.logging.Log log = PlatformUtil.getLog(RoutingHelper.class);

	// POS_TOLERANCE
	// 1) calculate current closest segment of the route during navigation
	// 2) identify u-turn, projected distance
	// 3) calculate max allowed deviation before route recalculation * multiplier
	private static final float POS_TOLERANCE = 60; // 60m or 30m + accuracy
	private static final float POS_TOLERANCE_DEVIATION_MULTIPLIER = 2;
	private static final int MAX_POSSIBLE_SPEED = 340; // ~ 1 Mach
	private static final boolean ENABLE_LOG_POS_PROCESSED = false;
	private static final int STOP_NAVIGATION_ON_AA_DISCONNECT_DISTANCE_THRESHOLD = 100;
	private static final long ETA_LOG_INTERVAL_MS = 30_000;
	private static final int PAUSE_NAVIGATION_ON_AA_DISCONNECT_SPEED_THRESHOLD = 1;

	private List<WeakReference<IRouteInformationListener>> listeners = new LinkedList<>();
	private List<WeakReference<IRoutingDataUpdateListener>> updateListeners = new LinkedList<>();
	private List<WeakReference<IRouteSettingsListener>> settingsListeners = new LinkedList<>();

	private final OsmandApplication app;
	private OsmandSettings settings;
	private final RouteProvider provider;
	private final VoiceRouter voiceRouter;
	private final RouteRecalculationHelper routeRecalculationHelper;
	/** Requires corroboration before a deviation triggers a recalculation. */
	private final CairoDriveOffRoute offRouteHysteresis = new CairoDriveOffRoute();
	private final TransportRoutingHelper transportRoutingHelper;

	// volatile together with route below: both are read off the recalculation executor thread by
	// RouteRecalculationHelper.installTrafficDetour, which re-checks that the route a detour was
	// computed against is still the one being driven. Without the happens-before edge that check
	// can pass on a stale value and install a detour over navigation already cancelled here.
	private volatile boolean isFollowingMode;
	private boolean isRoutePlanningMode;
	private boolean isPauseNavigation;
	private boolean isPausedOnAADisconnect;

	private GPXRouteParamsBuilder currentGPXRoute;

	private volatile RouteCalculationResult route = new RouteCalculationResult("");

	private LatLon finalLocation;
	private List<LatLon> intermediatePoints;
	private Location lastProjection;
	private Location lastFixedLocation;
	private final CairoDriveEta etaCalibrator = new CairoDriveEta();
	private long lastEtaLogTime;
	private Location lastGoodRouteLocation;
	private boolean routeWasFinished;
	private ApplicationMode mode;
	private boolean deviceHasBearing;

	private boolean isDeviatedFromRoute;
	private long deviateFromRouteDetected;
	//private long wrongMovementDetected = 0;
	private boolean voiceRouterStopped;

	public boolean isDeviatedFromRoute() {
		return isDeviatedFromRoute;
	}

	public boolean isRouteWasFinished() {
		return routeWasFinished;
	}

	public RoutingHelper(OsmandApplication context) {
		this.app = context;
		settings = context.getSettings();
		voiceRouter = new VoiceRouter(this);
		provider = new RouteProvider();
		routeRecalculationHelper = new RouteRecalculationHelper(this);
		transportRoutingHelper = context.getTransportRoutingHelper();
		transportRoutingHelper.setRoutingHelper(this);
		setAppMode(settings.APPLICATION_MODE.get());

		OsmAndAppCustomizationListener customizationListener = () -> settings = app.getSettings();
		app.getAppCustomization().addListener(customizationListener);
	}

	/** Drops the reroute result cache - see RouteRecalculationHelper. Called on any map change. */
	public void invalidateRerouteCache() {
		routeRecalculationHelper.invalidateRerouteCache();
	}

	RouteProvider getProvider() {
		return provider;
	}

	// Package-private on purpose: TrafficDetourHelper installs a computed detour through the same
	// serialized recalculation executor real recalculations use, and it lives in this package.
	RouteRecalculationHelper getRouteRecalculationHelper() {
		return routeRecalculationHelper;
	}

	public void resetRouteWasFinished() {
		routeWasFinished = false;
	}

	void setRoute(RouteCalculationResult route) {
		this.route = route;
		// Evidence of deviating from the previous route says nothing about this one.
		offRouteHysteresis.reset();
		// N6: the same argument. A Viterbi history is about a journey that no longer exists, and
		// while BROKEN_CHAIN_GAP_MS would recover on its own, it would do so only after 20 s of
		// driving on stale hypotheses. No-op when map matching is off.
		try {
			net.osmand.plus.cairodrive.CairoDriveMapMatchService.getInstance(app).reset();
		} catch (Throwable ignored) {
		}
		// The speed ratio is deliberately NOT reset here. A reroute mid-trip is the same driver on
		// the same roads a minute later, and throwing the ratio away would put the estimate back to
		// the modelled speed exactly when the driver is most likely to be looking at it. It is only
		// reset when navigation stops - see clearCurrentRoute.
	}

	long getDeviateFromRouteDetected() {
		return deviateFromRouteDetected;
	}

	void setDeviateFromRouteDetected(long deviateFromRouteDetected) {
		this.deviateFromRouteDetected = deviateFromRouteDetected;
	}

	public TransportRoutingHelper getTransportRoutingHelper() {
		return transportRoutingHelper;
	}

	public boolean isFollowingMode() {
		return isFollowingMode;
	}

	public OsmandApplication getApplication() {
		return app;
	}

	public String getLastRouteCalcError() {
		return routeRecalculationHelper.getLastRouteCalcError();
	}

	public String getLastRouteCalcErrorShort() {
		return routeRecalculationHelper.getLastRouteCalcErrorShort();
	}

	public void resumeNavigation() {
		setRoutePlanningMode(false);
		setFollowingMode(true);
		setCurrentLocation(app.getLocationProvider().getLastKnownLocation(), false);
	}

	public void onCarNavigationStart() {
		if (isPausedOnAADisconnect && isPauseNavigation()) {
			isPausedOnAADisconnect = false;
			resumeNavigation();
		}
	}

	public void onCarNavigationSessionChanged() {
		if (app.getCarNavigationSession() == null) {
			if (isFollowingMode()) {
				if (getLeftDistance() < STOP_NAVIGATION_ON_AA_DISCONNECT_DISTANCE_THRESHOLD) {
					app.stopNavigation();
				} else {
					Location currentLocation = app.getLocationProvider().getLastKnownLocation();
					if (currentLocation != null && currentLocation.getSpeed() < PAUSE_NAVIGATION_ON_AA_DISCONNECT_SPEED_THRESHOLD) {
						isPausedOnAADisconnect = true;
						pauseNavigation();
					}
				}
			}
		}
	}

	public void pauseNavigation() {
		setRoutePlanningMode(true);
		setFollowingMode(false);
		setPauseNavigation(true);
	}

	public void setPauseNavigation(boolean pause) {
		app.logRoutingEvent("setPauseNavigation pause " + pause);
		this.isPauseNavigation = pause;
		if (pause) {
			if (app.getNavigationService() != null) {
				app.getNavigationService().stopIfNeeded(app, NavigationService.USED_BY_NAVIGATION);
			} else {
				app.getNotificationHelper().updateTopNotification();
				app.getNotificationHelper().refreshNotifications();
			}
		} else {
			app.startNavigationService(NavigationService.USED_BY_NAVIGATION);
		}
	}

	public boolean isPauseNavigation() {
		return isPauseNavigation;
	}

	public void setFollowingMode(boolean follow) {
		app.logRoutingEvent("setFollowingMode follow " + follow);
		isPausedOnAADisconnect = false;
		isFollowingMode = follow;
		isPauseNavigation = false;
		if (!follow) {
			if (app.getNavigationService() != null) {
				app.getNavigationService().stopIfNeeded(app, NavigationService.USED_BY_NAVIGATION);
			} else {
				app.getNotificationHelper().updateTopNotification();
				app.getNotificationHelper().refreshNotifications();
			}
		} else {
			app.startNavigationService(NavigationService.USED_BY_NAVIGATION);
		}
	}

	public boolean isRoutePlanningMode() {
		return isRoutePlanningMode;
	}

	public void setRoutePlanningMode(boolean isRoutePlanningMode) {
		this.isRoutePlanningMode = isRoutePlanningMode;
	}

	public synchronized void setFinalAndCurrentLocation(LatLon finalLocation, List<LatLon> intermediatePoints, Location currentLocation) {
		app.logRoutingEvent("setFinalAndCurrentLocation finalLocation " + finalLocation + " intermediatePoints " + intermediatePoints + " currentLocation " + currentLocation);
		RoutingHelperUtils.updateDrivingRegionIfNeeded(app, currentLocation, false);
		RouteCalculationResult previousRoute = route;
		clearCurrentRoute(finalLocation, intermediatePoints);
		// to update route
		setCurrentLocation(currentLocation, false, previousRoute, true);
	}

	public synchronized void clearCurrentRoute(LatLon newFinalLocation, List<LatLon> newIntermediatePoints) {
		app.logRoutingEvent("clearCurrentRoute newFinalLocation " + newFinalLocation + " newIntermediatePoints " + newIntermediatePoints);
		// Bumps the generation so any fetch still in flight discards its result instead of
		// resurrecting traffic for a route that no longer exists.
		GoogleTrafficHelper.reset(app);
		// Orphans any fetch still in flight, and drops the state that really is route-anchored:
		// FLOW, which is per-segment speed along the route that just went away.
		//
		// Two slots deliberately SURVIVE, for the same reason. Dust is a property of the sky, and a
		// closure is a property of the city - neither becomes untrue because the destination
		// changed. Incidents used to be wiped here, which blanked the closure chip on every route
		// change and forced a re-fetch that can be forty minutes away on a spent ladder.
		net.osmand.plus.cairodrive.providers.CairoDriveProviders.resetRouteState();
		net.osmand.plus.cairodrive.providers.TrafficAwareRouting.onRouteCleared(app);
		net.osmand.plus.cairodrive.providers.SunGlareProvider.reset(app);
		// TomTomTrafficProvider is DELIBERATELY absent from this list. Its published data is
		// already dropped by resetRouteState() above; the only thing it could additionally clear is
		// its poll cadence, and clearing that would make the next poll immediately due. Ending and
		// restarting navigation would then buy an off-ladder request every time - the same waste
		// TomTomTrafficProvider.seedCadence() exists to stop across process restarts. The cadence
		// is budget state, not route state, so it survives on purpose.
		routeWasFinished = false; // Prevent stale "arrived" state from leaking into the next navigation session
		route = new RouteCalculationResult("");
		// Congestion spans are geo-anchored, not route-anchored, so nothing else drops them when
		// navigation ends - without this they keep painting over whatever comes next.
		GoogleTrafficHelper.reset(app);
		isDeviatedFromRoute = false;
		// New trip, new conditions. The observed/modelled speed ratio from the last drive is not
		// evidence about this one - a morning commute and a 2am airport run are different roads at
		// different speeds even when the map data is identical.
		etaCalibrator.reset();
		lastEtaLogTime = 0;
		routeRecalculationHelper.resetEvalWaitInterval();
		app.getWaypointHelper().setNewRoute(route);
		app.runInUIThread(() -> {
			Iterator<WeakReference<IRouteInformationListener>> it = listeners.iterator();
			while (it.hasNext()) {
				WeakReference<IRouteInformationListener> ref = it.next();
				IRouteInformationListener l = ref.get();
				if (l == null) {
					it.remove();
				} else {
					l.routeWasCancelled();
				}
			}
		});
		this.finalLocation = newFinalLocation;
		this.lastGoodRouteLocation = null;
		this.intermediatePoints = newIntermediatePoints;
		routeRecalculationHelper.stopCalculation();
		if (newFinalLocation == null) {
			settings.FOLLOW_THE_ROUTE.set(false);
			settings.FOLLOW_THE_GPX_ROUTE.set(null);
			// clear last fixed location
			this.lastProjection = null;
			setFollowingMode(false);
		}
		transportRoutingHelper.clearCurrentRoute(newFinalLocation);
	}

	private synchronized void finishCurrentRoute() {
		app.logRoutingEvent("finishCurrentRoute");
		routeWasFinished = true;
		app.runInUIThread(() -> {
			Iterator<WeakReference<IRouteInformationListener>> it = listeners.iterator();
			while (it.hasNext()) {
				WeakReference<IRouteInformationListener> ref = it.next();
				IRouteInformationListener l = ref.get();
				if (l == null) {
					it.remove();
				} else {
					l.routeWasFinished();
				}
			}
		});
	}

	void newRouteCalculated(boolean newRoute, RouteCalculationResult res) {
		app.logRoutingEvent("newRouteCalculated newRoute " + newRoute + " res " + res);
		app.runInUIThread(() -> {
			ValueHolder<Boolean> showToast = new ValueHolder<>();
			showToast.value = true;
			Iterator<WeakReference<IRouteInformationListener>> it = listeners.iterator();
			while (it.hasNext()) {
				WeakReference<IRouteInformationListener> ref = it.next();
				IRouteInformationListener l = ref.get();
				if (l == null) {
					it.remove();
				} else {
					l.newRouteIsCalculated(newRoute, showToast);
				}
			}
			if (showToast.value && newRoute && PluginsHelper.isDevelopment() && settings.DEBUG_RENDERING_INFO.get()) {
				String msg = app.getString(R.string.new_route_calculated_dist_dbg,
						OsmAndFormatter.getFormattedDistance(res.getWholeDistance(), app),
						((int) res.getRoutingTime()) + " sec",
						res.getCalculateTime(), res.getVisitedSegments(), res.getLoadedTiles());
				app.showToastMessage(msg);
			}
		});
	}

	public GPXRouteParamsBuilder getCurrentGPXRoute() {
		return currentGPXRoute;
	}

	public boolean isCurrentGPXRouteV2() {
		return currentGPXRoute != null && RouteExporter.OSMAND_ROUTER_V2.equals(currentGPXRoute.getFile().getAuthor());
	}

	@Nullable
	public GpxFile getCurrentGPX() {
		return currentGPXRoute != null ? currentGPXRoute.getFile() : null;
	}

	public void setGpxParams(GPXRouteParamsBuilder params) {
		app.logRoutingEvent("setGpxParams params " + params);
		currentGPXRoute = params;
	}

	public List<Location> getCurrentCalculatedRoute() {
		return route.getImmutableAllLocations();
	}

	public void setAppMode(@NonNull ApplicationMode mode) {
		this.mode = mode;
		voiceRouter.updateAppMode();
	}

	@NonNull
	public ApplicationMode getAppMode() {
		return mode;
	}

	public LatLon getFinalLocation() {
		return finalLocation;
	}

	public List<LatLon> getIntermediatePoints() {
		return intermediatePoints;
	}

	public boolean isOnRoute() {
		return isRouteCalculated() && !isDeviatedFromRoute();
	}

	public boolean isRouteCalculated() {
		return route.isCalculated();
	}

	@NonNull
	public VoiceRouter getVoiceRouter() {
		return voiceRouter;
	}

	@Nullable
	public Location getLastProjection() {
		return lastProjection;
	}

	public Location getLastFixedLocation() {
		return lastFixedLocation;
	}

	public void addRouteDataListener(@NonNull IRoutingDataUpdateListener listener) {
		updateListeners = Algorithms.updateWeakReferencesList(updateListeners, listener, true);
	}

	public void removeRouteDataListener(@NonNull IRoutingDataUpdateListener listener) {
		updateListeners = Algorithms.updateWeakReferencesList(updateListeners, listener, false);
	}

	public void addRouteSettingsListener(@NonNull IRouteSettingsListener listener) {
		settingsListeners = Algorithms.updateWeakReferencesList(settingsListeners, listener, true);
	}

	public void removeRouteSettingsListener(@NonNull IRouteSettingsListener listener) {
		settingsListeners = Algorithms.updateWeakReferencesList(settingsListeners, listener, false);
	}

	public void addListener(@NonNull IRouteInformationListener l) {
		listeners = Algorithms.updateWeakReferencesList(listeners, l, true);
		transportRoutingHelper.addListener(l);
	}

	public void removeListener(@NonNull IRouteInformationListener lt) {
		listeners = Algorithms.updateWeakReferencesList(listeners, lt, false);
	}


	public Location setCurrentLocation(Location currentLocation, boolean returnUpdatedLocation) {
		return setCurrentLocation(currentLocation, returnUpdatedLocation, route, false);
	}

	public double getRouteDeviation() {
		if (route == null ||
				route.getImmutableAllDirections().size() < 2 ||
				route.currentRoute == 0) {
			return 0;
		}
		List<Location> routeNodes = route.getImmutableAllLocations();
		return RoutingHelperUtils.getOrthogonalDistance(lastFixedLocation, routeNodes.get(route.currentRoute - 1), routeNodes.get(route.currentRoute));
	}

	private Location setCurrentLocation(Location currentLocation, boolean returnUpdatedLocation,
	                                    RouteCalculationResult previousRoute, boolean targetPointsChanged) {
		Location locationProjection = currentLocation;
		if (isPublicTransportMode() && currentLocation != null && finalLocation != null &&
				(targetPointsChanged || transportRoutingHelper.getStartLocation() == null)) {
			lastFixedLocation = currentLocation;
			lastProjection = locationProjection;
			transportRoutingHelper.setApplicationMode(mode);
			transportRoutingHelper.setFinalAndCurrentLocation(finalLocation,
					new LatLon(currentLocation.getLatitude(), currentLocation.getLongitude()));
		}
		// The providers that do not need a DESTINATION run here, above the early return below.
		//
		// They used to sit further down with the route-anchored ones, which meant none of them ever
		// ran while free driving: finalLocation is null without a destination, so this method
		// returned before reaching them. Dust and sun glare are properties of the sky and the hour,
		// not of a route that may not exist, and a closure or a flooded underpass blocks a road
		// whether or not the driver told the app where they were going. All three were silently
		// navigation-only.
		//
		// Ordered cheapest-first, as before: glare is pure arithmetic, weather is minutes apart,
		// TomTom is the only one here that can spend a billed request. Each is a self-gating entry
		// point that returns on its first line when its flag is off, so a default build still pays
		// one boolean read per fix for the lot.
		if (currentLocation != null) {
			// Google traffic WITHOUT a destination - the corridor ahead instead of a route. Sits
			// here for the same reason the three below do: setCurrentLocation returns above on
			// finalLocation == null, so anything left further down never runs while free driving.
			// It self-gates on the toggle, the key, foreground, car mode, speed and the ladder.
			GoogleTrafficHelper.onFreeDriveLocation(app, currentLocation);
			net.osmand.plus.cairodrive.providers.SunGlareProvider.onLocationUpdate(app, currentLocation);
			net.osmand.plus.cairodrive.providers.OpenWeatherHazardProvider.onLocationUpdate(app, currentLocation);
			net.osmand.plus.cairodrive.providers.TomTomTrafficProvider.onLocationUpdate(this, currentLocation);
		}
		if (finalLocation == null || currentLocation == null || isPublicTransportMode()) {
			isDeviatedFromRoute = false;
			return locationProjection;
		}
		float posTolerance = getPosTolerance(currentLocation.hasAccuracy() ? currentLocation.getAccuracy() : 0);
		boolean calculateRoute = false;
		synchronized (this) {
			isDeviatedFromRoute = false;
			double distOrth = 0;

			// 0. Route empty or needs to be extended? Then re-calculate route.
			if (route.isEmpty()) {
				calculateRoute = !route.hasMissingMaps() || isLocationJumping(currentLocation, targetPointsChanged);
			} else {
				// 1. Update current route position status according to latest received location
				boolean finished = updateCurrentRouteStatus(currentLocation, posTolerance);
				if (finished) {
					return null;
				}
				List<Location> routeNodes = route.getImmutableAllLocations();
				int currentRoute = route.currentRoute;
				double allowableDeviation = route.getRouteRecalcDistance();
				if (allowableDeviation <= 0) {
					allowableDeviation = getDefaultAllowedDeviation(settings, route.getAppMode(), posTolerance);
				}

				// 2. Analyze if we need to recalculate route
				// >100m off current route (sideways) or parameter (for Straight line)
				if (allowableDeviation > 0) {
					if (currentRoute == 0) {
						distOrth = currentLocation.distanceTo(routeNodes.get(currentRoute)); // deviation at the start
					} else {
						distOrth = RoutingHelperUtils.getOrthogonalDistance(currentLocation, routeNodes.get(currentRoute - 1), routeNodes.get(currentRoute));
					}
					// "Am I off the route" and "is it worth recalculating" are two different
					// questions, and only the second one is hysteresis-gated.
					//
					// isDeviatedFromRoute drives the display: the Android Auto OFF-ROUTE
					// manoeuvre, the next-turn and lane widgets, the notification. Setting it
					// inside the recalculation branch made it a ONE-FIX PULSE whenever the
					// hysteresis is enabled - shouldRecalculate stamps a cooldown when it fires,
					// so the following fixes are refused and the flag, cleared at the top of
					// this method, stays false for the whole recalculation window. The driver
					// would lose the off-route indication precisely while off route.
					//
					// Not reachable in the shipping build, because the hysteresis is off by
					// default and shouldRecalculate then returns its argument unchanged - which
					// is exactly upstream's behaviour. Fixed anyway so the flag is safe to turn
					// on, rather than leaving a trap for whoever does.
					boolean offRoute = distOrth > allowableDeviation;
					isDeviatedFromRoute = offRoute;
					if (offRouteHysteresis.shouldRecalculate(currentLocation, offRoute)) {
						log.info("Recalculate route, because correlation  : " + distOrth); //$NON-NLS-1$
						calculateRoute = !settings.DISABLE_OFFROUTE_RECALC.get();
					}
				}
				// 3. Identify wrong movement direction
				Location next = route.getNextRouteLocation();
				Location prev = route.getRouteLocationByDistance(-15);//-15 meters
				boolean isStraight =
						route.getRouteService() == RouteService.DIRECT_TO || route.getRouteService() == RouteService.STRAIGHT;
				boolean wrongMovementDirection = RoutingHelperUtils.checkWrongMovementDirection(currentLocation, prev, next);
				if ((allowableDeviation > 0 && wrongMovementDirection && !isStraight
						&& (currentLocation.distanceTo(routeNodes.get(currentRoute)) > allowableDeviation)) && !settings.DISABLE_WRONG_DIRECTION_RECALC.get()) {
					log.info("Recalculate route, because wrong movement direction: " + currentLocation.distanceTo(routeNodes.get(currentRoute))); //$NON-NLS-1$
					isDeviatedFromRoute = true;
					calculateRoute = true;
				}
				// 4. Identify if UTurn is needed
				if (RoutingHelperUtils.identifyUTurnIsNeeded(this, currentLocation, posTolerance)) {
					isDeviatedFromRoute = true;
				}
				// 4.5. Disable recalculation in tunnels (tunnel locations are simulated)
				if (calculateRoute && SimulationProvider.isTunnelLocationSimulated(currentLocation)) {
					log.info("Ignore route recalculation in tunnel: " + currentLocation); //$NON-NLS-1$
					isDeviatedFromRoute = false;
					calculateRoute = false;
				}
				// 5. Update Voice router
				// Do not update in route planning mode
				boolean inRecalc = (calculateRoute || isRouteBeingCalculated());
				if (isFollowingMode) {
					// Same reasoning as the projection gate below: keep guiding while the driver is
					// still on the route and only the app is busy. A settings change or a target
					// change should not silence the next turn instruction for 4-8 s. A genuine
					// deviation still stops prompts, on the branch below.
					boolean silenceForDeviation = inRecalc && isDeviatedFromRoute;
					if (!silenceForDeviation && !wrongMovementDirection) {
						voiceRouter.updateStatus(currentLocation, false);
						voiceRouterStopped = false;
					} else if (isDeviatedFromRoute && !voiceRouterStopped) {
						voiceRouter.interruptRouteCommands();
						voiceRouterStopped = true; // Prevents excessive execution of stop() code
					}
					voiceRouter.announceOffRoute(distOrth);
				}

				// 5.5. Live traffic, navigation side. Both helpers own their preference gate and
				// their throttle, so a disabled or throttled feature costs a cached read here; the
				// free-drive side is driven by GoogleTrafficLayer's own location listener instead.
				// Network and routing work is handed to background threads by both helpers.
				// Known cost, accepted deliberately: on the polls that actually claim budget
				// (at most one per 2 min while navigating, plus three on the first poll after a UTC
				// day roll) GoogleTrafficHelper persists its counters through IntPreference, whose
				// setValue ends in a synchronous commit() - a blocking disk write on this thread
				// while this monitor is held. That is the price of a budget cap that survives process
				// death, which is what keeps Google billing at zero; an in-memory counter flushed
				// later could be lost on a kill and grant a second daily budget. Nothing else here
				// may block, and no new blocking work belongs in this block.
				if (isFollowingMode) {
					ClosureSyncHelper.onLocationUpdate(app, currentLocation, true);
					GoogleTrafficHelper.onLocationUpdate(this, currentLocation);
				}

				// calculate projection of current location
				// N2. Suppressed only when the recalculation is happening BECAUSE the driver
				// deviated - not for every recalculation.
				//
				// inRecalc is also true for a target change, a settings change and a
				// wrong-direction trigger. In all of those the driver is still ON the route, so
				// dropping the projection throws the arrow off the polyline onto raw GPS for the
				// whole 4-8 s search, for no reason. Combined with the blanked manoeuvre card and
				// the silent voice over the same window, that is what made a reroute read as the
				// app having lost the car.
				//
				// When the driver HAS genuinely left the route, projecting onto it would be a lie,
				// so that case still suppresses exactly as before.
				boolean suppressProjection = inRecalc && isDeviatedFromRoute;
				if (currentRoute > 0 && !suppressProjection) {
					Location previousRouteLocation = routeNodes.get(currentRoute - 1);
					Location currentRouteLocation = routeNodes.get(currentRoute);
					locationProjection = RoutingHelperUtils.getProject(currentLocation, previousRouteLocation,
							currentRouteLocation);
					if (settings.SNAP_TO_ROAD.get() && currentRoute + 1 < routeNodes.size()) {
						boolean previewNextTurn = settings.PREVIEW_NEXT_TURN.get();
						Location nextRouteLocation = routeNodes.get(currentRoute + 1);
						RoutingHelperUtils.approximateBearingIfNeeded(this,
								locationProjection, currentLocation, previousRouteLocation,
								currentRouteLocation, nextRouteLocation, previewNextTurn);
					}
				}
				// One navigation-state line per fix while guiding, straight to the on-device file:
				// the raw GPS the FIX line already carries, plus what only exists here - how far
				// off the route this fix measured (devM), the threshold it was tested against, the
				// app's verdict, whether a reroute fired, and where the snapped arrow ended up
				// versus the real position. This is what lets a drive's deviations be reconstructed
				// after the fact rather than guessed at.
				logNavState(currentLocation, locationProjection, distOrth, allowableDeviation,
						wrongMovementDirection, calculateRoute);
			}
			lastFixedLocation = currentLocation;
			lastProjection = locationProjection;
			// Live traffic on the route. Self-gating and self-throttling: with the feature off or
			// no key compiled in this returns on the first line and costs nothing per fix.
			GoogleTrafficHelper.onLocationUpdate(this, currentLocation);
			// The provider stack. Each of these is the same shape as the line above: a self-gating,
			// self-throttling entry point that returns immediately when its flag is off or its key
			// is absent, so a default build pays one boolean read per fix for the lot.
			//
			// Ordered cheapest-first on purpose. Sun glare is pure arithmetic and no network at
			// all; the weather poller is minutes apart; TomTom is the only one that can spend a
			// billed request, and TrafficAwareRouting runs last because it CONSUMES what the
			// others published rather than fetching anything itself.
			// Glare, weather and TomTom already ran above the destination check - they work
			// without one. TrafficAwareRouting stays here because it CONSUMES route state: it
			// applies flow to the ETA and turns closures into nogo points, neither of which means
			// anything without a route.
			net.osmand.plus.cairodrive.providers.TrafficAwareRouting.onLocationUpdate(this, currentLocation);
			if (!route.isEmpty()) {
				lastGoodRouteLocation = currentLocation;
				// Feed the ETA calibrator the modelled speed the router expects for the segment
				// being driven, alongside what the driver is actually doing. See CairoDriveEta.
				RouteDirectionInfo currentDirection = route.getCurrentDirection();
				if (currentDirection != null) {
					etaCalibrator.registerFix(currentLocation, currentDirection.getAverageSpeed());
				}
			}
		}

		if (calculateRoute) {
			routeRecalculationHelper.recalculateRouteInBackground(currentLocation, finalLocation, intermediatePoints, currentGPXRoute,
					previousRoute.isCalculated() ? previousRoute : null, false, !targetPointsChanged);
		} else {
			routeRecalculationHelper.stopCalculationIfParamsNotChanged();
		}

		double projectDist = mode != null && mode.hasFastSpeed() ? posTolerance : posTolerance / 2;
		if (returnUpdatedLocation && locationProjection != null && currentLocation.distanceTo(locationProjection) < projectDist) {
			return locationProjection;
		} else {
			return currentLocation;
		}
	}

	/**
	 * Writes one CD_NAV line per guided fix to the diagnostic file. Runs at the fix rate (~1Hz),
	 * so a single {@code String.format} per call is not a hot-path cost, and the whole thing is
	 * skipped when file logging is compiled out. The file writer is non-blocking.
	 *
	 * @param projection where the snapped arrow was placed - equal to {@code fix} when the arrow
	 *                   was not projected onto the route this tick, so {@code gapM} then reads 0.
	 */
	private void logNavState(@NonNull Location fix, Location projection, double devM,
	                         double allowM, boolean wrongDirection, boolean recalc) {
		if (!CairoDriveLogger.isEnabled()) {
			return;
		}
		double gapM = projection != null ? fix.distanceTo(projection) : 0;
		float speedKmh = fix.hasSpeed() ? fix.getSpeed() * 3.6f : 0;
		CairoDriveLogger.getInstance().log("CD_NAV", String.format(java.util.Locale.US,
				"rawLat=%.5f rawLon=%.5f armLat=%.5f armLon=%.5f devM=%.1f allowM=%.1f off=%b"
						+ " recalc=%b wrongDir=%b armGapM=%.1f spdKmh=%.1f",
				fix.getLatitude(), fix.getLongitude(),
				projection != null ? projection.getLatitude() : fix.getLatitude(),
				projection != null ? projection.getLongitude() : fix.getLongitude(),
				devM, allowM, isDeviatedFromRoute, recalc, wrongDirection, gapM, speedKmh));
	}

	private boolean isLocationJumping(@NonNull Location currentLocation, boolean targetPointsChanged) {
		if (route.hasMissingMaps() && lastGoodRouteLocation != null && !targetPointsChanged) {
			double time = currentLocation.getTime() - lastGoodRouteLocation.getTime();
			double dist = currentLocation.distanceTo(lastGoodRouteLocation);
			if (time > 0) {
				double speed = dist / (time / 1000.0);
				return speed > MAX_POSSIBLE_SPEED;
			}
		}
		return false;
	}

	public double getMaxAllowedProjectDist(@NonNull Location location) {
		float posTolerance = getPosTolerance(location.hasAccuracy() ? location.getAccuracy() : 0);
		return mode != null && mode.hasFastSpeed() ? posTolerance : posTolerance / 2;
	}

	private boolean updateCurrentRouteStatus(Location currentLocation, double posTolerance) {
		List<Location> routeNodes = route.getImmutableAllLocations();
		int currentRoute = route.currentRoute;
		// 1. Try to proceed to next point using orthogonal distance (finding minimum orthogonal dist)
		currentRoute = calculateCurrentRoute(currentLocation, posTolerance, routeNodes, currentRoute, true);

		// 2. check if intermediate found
		if (route.getIntermediatePointsToPass() > 0
				&& route.getDistanceToNextIntermediate(lastFixedLocation) < voiceRouter.getArrivalDistance() && !isRoutePlanningMode) {
			app.showToastMessage(R.string.arrived_at_intermediate_point);
			route.passIntermediatePoint();
			TargetPointsHelper targets = app.getTargetPointsHelper();
			String name = "";
			if (intermediatePoints != null && !intermediatePoints.isEmpty()) {
				LatLon rm = intermediatePoints.remove(0);
				List<TargetPoint> ll = targets.getIntermediatePointsNavigation();
				int ind = -1;
				for (int i = 0; i < ll.size(); i++) {
					if (ll.get(i).getLatLon() != null && MapUtils.getDistance(ll.get(i).getLatLon(), rm) < 5) {
						name = ll.get(i).getOnlyName();
						ind = i;
						break;
					}
				}
				if (ind >= 0) {
					targets.removeWayPoint(false, ind);
				}
			}
			if (isFollowingMode) {
				voiceRouter.arrivedIntermediatePoint(name);
			}
			// double check
			while (intermediatePoints != null && route.getIntermediatePointsToPass() < intermediatePoints.size()) {
				intermediatePoints.remove(0);
			}
		}

		// 3. check if destination found
		Location lastPoint = routeNodes.get(routeNodes.size() - 1);
		if (currentRoute > routeNodes.size() - 3
				&& currentLocation.distanceTo(lastPoint) < voiceRouter.getArrivalDistance()
				&& !isRoutePlanningMode) {
			//showMessage(app.getString(R.string.arrived_at_destination));
			TargetPointsHelper targets = app.getTargetPointsHelper();
			TargetPoint tp = targets.getPointToNavigate();
			String description = tp == null ? "" : tp.getOnlyName();
			if (isFollowingMode) {
				voiceRouter.arrivedDestinationPoint(description);
			}
			boolean onDestinationReached = true;
			if (onDestinationReached) {
				clearCurrentRoute(null, null);
				setRoutePlanningMode(false);
				app.runInUIThread(() -> {
					settings.LAST_ROUTING_APPLICATION_MODE = settings.APPLICATION_MODE.get();
					//settings.setApplicationMode(settings.DEFAULT_APPLICATION_MODE.get());
				});
				finishCurrentRoute();
				// targets.clearPointToNavigate(false);
				return true;
			}
		}

		// 4. update angle point
		if (route.getRouteVisibleAngle() > 0) {
			// proceed to the next point with min acceptable bearing
			double ANGLE_TO_DECLINE = route.getRouteVisibleAngle();
			int nextPoint = route.currentRoute;
			for (; nextPoint < routeNodes.size() - 1; nextPoint++) {
				float bearingTo = currentLocation.bearingTo(routeNodes.get(nextPoint));
				float bearingTo2 = routeNodes.get(nextPoint).bearingTo(routeNodes.get(nextPoint + 1));
				if (Math.abs(MapUtils.degreesDiff(bearingTo2, bearingTo)) <= ANGLE_TO_DECLINE) {
					break;
				}
			}

			if (nextPoint > 0) {
				Location next = routeNodes.get(nextPoint);
				Location prev = routeNodes.get(nextPoint - 1);
				float bearing = prev.bearingTo(next);
				double bearingTo = Math.abs(MapUtils.degreesDiff(bearing, currentLocation.bearingTo(next)));
				double bearingPrev = Math.abs(MapUtils.degreesDiff(bearing, currentLocation.bearingTo(prev)));
				while (true) {
					Location mp = MapUtils.calculateMidPoint(prev, next);
					if (mp.distanceTo(next) <= 100) {
						break;
					}
					double bearingMid = Math.abs(MapUtils.degreesDiff(bearing, currentLocation.bearingTo(mp)));
					if (bearingPrev < ANGLE_TO_DECLINE) {
						next = mp;
						bearingTo = bearingMid;
					} else if (bearingTo < ANGLE_TO_DECLINE) {
						prev = mp;
						bearingPrev = bearingMid;
					} else {
						break;
					}
				}
				route.updateNextVisiblePoint(nextPoint, next);
			}
		}

		// 5. Update car navigation
		NavigationSession carNavigationSession = app.getCarNavigationSession();
		if (carNavigationSession != null) {
			app.runInUIThread(() -> carNavigationSession.updateCarNavigation(currentLocation));
		}
		return false;
	}

	public int calculateCurrentRoute(@NonNull Location currentLocation, double posTolerance,
	                                 @NonNull List<Location> routeNodes, int currentRoute,
	                                 boolean updateAndNotify) {
		while (currentRoute + 1 < routeNodes.size()) {
			double dist = currentLocation.distanceTo(routeNodes.get(currentRoute));
			if (currentRoute > 0) {
				dist = RoutingHelperUtils.getOrthogonalDistance(currentLocation, routeNodes.get(currentRoute - 1),
						routeNodes.get(currentRoute));
			}
			boolean processed = false;
			// if we are still too far try to proceed many points
			// if not then look ahead only 3 in order to catch sharp turns
			boolean longDistance = dist >= 250;
			int newCurrentRoute = RoutingHelperUtils.lookAheadFindMinOrthogonalDistance(currentLocation, routeNodes, currentRoute, longDistance ? 15 : 8);
			double newDist = RoutingHelperUtils.getOrthogonalDistance(currentLocation, routeNodes.get(newCurrentRoute),
					routeNodes.get(newCurrentRoute + 1));
			if (longDistance) {
				if (newDist < dist) {
					if (ENABLE_LOG_POS_PROCESSED) {
						log.debug("Processed by distance : (new) " + newDist + " (old) " + dist); //$NON-NLS-1$//$NON-NLS-2$
					}
					processed = true;
				}
			} else if (newDist < dist || newDist < posTolerance / 8) {
				// newDist < posTolerance / 8 - 4-8 m (avoid distance 0 till next turn)
				if (dist > posTolerance) {
					processed = true;
					if (ENABLE_LOG_POS_PROCESSED) {
						log.debug("Processed by distance : " + newDist + " " + dist); //$NON-NLS-1$//$NON-NLS-2$
					}
				} else {
					if (currentLocation.hasBearing() && !deviceHasBearing) {
						deviceHasBearing = true;
					}
					// lastFixedLocation.bearingTo -  gives artefacts during u-turn, so we avoid for devices with bearing
					if ((currentRoute > 0 || newCurrentRoute > 0) &&
							(currentLocation.hasBearing() || (!deviceHasBearing && lastFixedLocation != null))) {
						float bearingToRoute = currentLocation.bearingTo(routeNodes.get(currentRoute));
						float bearingRouteNext = routeNodes.get(newCurrentRoute).bearingTo(routeNodes.get(newCurrentRoute + 1));
						float bearingMotion = currentLocation.hasBearing() ? currentLocation.getBearing() : lastFixedLocation
								.bearingTo(currentLocation);
						double diff = Math.abs(MapUtils.degreesDiff(bearingMotion, bearingToRoute));
						double diffToNext = Math.abs(MapUtils.degreesDiff(bearingMotion, bearingRouteNext));
						if (diff > diffToNext) {
							if (ENABLE_LOG_POS_PROCESSED) {
								log.debug("Processed point bearing deltas : " + diff + " " + diffToNext);
							}
							processed = true;
						}
					}
				}
			}
			if (processed) {
				// that node already passed
				currentRoute = newCurrentRoute + 1;
				if (updateAndNotify) {
					route.updateCurrentRoute(newCurrentRoute + 1);
					app.getNotificationHelper().refreshNotification(NotificationType.NAVIGATION);
					fireRoutingDataUpdateEvent();
				}
			} else {
				break;
			}
		}
		return currentRoute;
	}

	/**
	 * N8. The deviation tolerance, now keyed on whether the fix is worth believing.
	 *
	 * <h3>Why this stopped needing a drive to decide</h3>
	 *
	 * N8 was "tighten 120 m towards Mapbox's 50 m", and it was correctly held: tightening blind
	 * trades missed reroutes for spurious ones, and there was no way to tell which a given fix
	 * deserved. That is no longer true. N1 already computes exactly the missing signal, and the
	 * 2026-08-04 drive says how much it matters - 55% of fixes reported 2.1-2.5 m accuracy with
	 * {@code satsUsed=0}, a Wi-Fi/cell position wearing a satellite fix's error bar.
	 *
	 * <p>So the honest reading of the old formula is that it was never too loose OR too tight - it
	 * was uniform over two populations that deserve opposite treatment. On a real satellite fix
	 * 120 m is far more slack than the error justifies, and a genuine wrong turn goes unnoticed for
	 * a block. On a degraded fix the same 120 m is barely enough, and tightening it there is how
	 * you manufacture the reroute storm this fork spent a drive fixing.
	 *
	 * <p>Healthy fixes therefore tighten toward what the industry uses; degraded fixes keep the
	 * old behaviour exactly. Nothing here loosens anything.
	 *
	 * @param accuracy the reported accuracy, which on a degraded fix is not to be trusted
	 */
	public static float getPosTolerance(float accuracy) {
		boolean degraded;
		try {
			degraded = CairoDriveLogger.getInstance().isGnssDegraded();
		} catch (Throwable t) {
			// Diagnostics must never change routing behaviour by failing. Unknown means "assume
			// the worse case", which is the old formula.
			degraded = true;
		}
		if (accuracy > 0) {
			float legacy = POS_TOLERANCE / 2 + accuracy;
			if (!degraded) {
				// A satellite fix's accuracy figure is meaningful, so lean on it rather than on a
				// fixed 30 m of slack on top.
				//
				// Capped at the legacy value, and that cap is not decoration: a simulation of this
				// found that above ~20 m of reported accuracy, 2.5x OVERTAKES the old formula and
				// this would have LOOSENED the tolerance - the exact opposite of what N8 is for,
				// on the fixes where a wrong turn is easiest to miss. A healthy fix reporting 40 m
				// is unusual but reachable (four satellites, poor geometry), and "unusual" is not
				// a reason to ship a regression. This only ever tightens.
				return Math.min(legacy,
						Math.max(MIN_POS_TOLERANCE_GOOD_FIX, POS_TOLERANCE_GOOD_FIX_FACTOR * accuracy));
			}
			return legacy;
		}
		return POS_TOLERANCE;
	}

	/**
	 * Floor for a healthy fix. Below this, lane changes and GPS jitter on a wide road start
	 * reading as deviations - and a spurious reroute is worse than a late one.
	 */
	private static final float MIN_POS_TOLERANCE_GOOD_FIX = 25;
	/** 2.5 sigma of a 68%-radius accuracy figure is ~99%: generous, without being uniform. */
	private static final float POS_TOLERANCE_GOOD_FIX_FACTOR = 2.5f;

	private static float getDefaultAllowedDeviation(OsmandSettings settings, ApplicationMode mode, float posTolerance) {
		if (mode.getRouteService() == RouteService.DIRECT_TO) {
			return -1.0f;
		} else if (mode.getRouteService() == RouteService.STRAIGHT) {
			MetricsConstants mc = settings.METRIC_SYSTEM.getModeValue(mode);
			if (mc == MetricsConstants.KILOMETERS_AND_METERS || mc == MetricsConstants.MILES_AND_METERS) {
				return 500.f;
			} else {
				// 1/4 mile
				return 482.f;
			}
		}
		return posTolerance * POS_TOLERANCE_DEVIATION_MULTIPLIER;
	}

	public static float getDefaultAllowedDeviation(OsmandSettings settings, ApplicationMode mode) {
		return getDefaultAllowedDeviation(settings, mode, getPosTolerance(0));
	}

	private void fireRoutingDataUpdateEvent() {
		if (!updateListeners.isEmpty()) {
			ArrayList<WeakReference<IRoutingDataUpdateListener>> tmp = new ArrayList<>(updateListeners);
			for (WeakReference<IRoutingDataUpdateListener> ref : tmp) {
				IRoutingDataUpdateListener l = ref.get();
				if (l != null) {
					l.onRoutingDataUpdate();
				}
			}
		}
	}

	private void fireRouteSettingsChangedEvent(@Nullable ApplicationMode mode) {
		if (!settingsListeners.isEmpty()) {
			ArrayList<WeakReference<IRouteSettingsListener>> tmp = new ArrayList<>(settingsListeners);
			for (WeakReference<IRouteSettingsListener> ref : tmp) {
				IRouteSettingsListener l = ref.get();
				if (l != null) {
					l.onRouteSettingsChanged(mode);
				}
			}
		}
	}

	public int getLeftDistance() {
		return route.getDistanceToFinish(lastFixedLocation);
	}

	public int getLeftDistanceNextIntermediate() {
		return getLeftDistanceToIntermediate(0);
	}

	public int getLeftDistanceToIntermediate(int intermediateIndexOffset) {
		return route.getDistanceToNextIntermediate(lastFixedLocation, intermediateIndexOffset);
	}

	public int getLeftTime() {
		int staticSeconds = route.getLeftTime(lastFixedLocation);
		int corrected = etaCalibrator.correct(staticSeconds);
		// Live traffic on top of the learned correction, in that order and not the other way.
		//
		// The calibrator learns THIS DRIVER on THESE roads over many drives - it is a long-run
		// personal bias. Traffic is a right-now condition on the road ahead. Applying traffic to
		// the already-personalised number is the composition that makes sense: "the time this
		// driver normally takes, given the road is currently this congested". Feeding traffic in
		// first would have the calibrator slowly learn away the very congestion the traffic feed
		// is reporting, and the two corrections would fight.
		//
		// Returns its input unchanged when no flow is fresh, no provider is serving, or every
		// sample failed the confidence gate - so this is a no-op on a default build.
		corrected = (int) net.osmand.plus.cairodrive.providers.TrafficAwareRouting
				.adjustedSeconds(corrected);
		// Logged unconditionally, not only when the value changed. Before warm-up the two are equal
		// by construction, so a drive where the calibrator never warmed up produced ZERO CD_ETA
		// lines - indistinguishable in the log from the feature not being compiled in.
		if (CairoDriveLogger.isEnabled()) {
			long now = System.currentTimeMillis();
			if (now - lastEtaLogTime > ETA_LOG_INTERVAL_MS) {
				lastEtaLogTime = now;
				// The nogo ids ride along on this line rather than getting their own. They change
				// only when a closure is applied or lifted, so a dedicated periodic line would be
				// almost entirely repetition - and this is the line that already says what the ETA
				// came out as, which is the number a suppressed road changes.
				CairoDriveLogger.getInstance().log("CD_ETA",
						etaCalibrator.describe(staticSeconds, corrected)
								+ " leftM=" + getLeftDistance()
								+ " " + net.osmand.plus.cairodrive.providers.TrafficAwareRouting
								.describeApplied()
								+ " " + net.osmand.plus.cairodrive.providers.TrafficAwareRouting
								.describeStretch());
			}
		}
		return corrected;
	}

	@NonNull
	public CairoDriveEta getEtaCalibrator() {
		return etaCalibrator;
	}

	public int getLeftTimeNextTurn() {
		// Corrected too. If the arrival card is calibrated and the time-to-next-turn beside it is
		// not, the two disagree on screen - and RouteCalculationResult.getLeftTimeToNextIntermediate
		// subtracts a raw prefix sum from a corrected total, which can land on zero. That is the
		// "0 min - 493 m" the head unit showed on the 2026-08-04 drive.
		return etaCalibrator.correct(route.getLeftTimeToNextTurn(lastFixedLocation));
	}

	public int getLeftTimeNextIntermediate() {
		return getLeftTimeNextIntermediate(0);
	}

	public int getLeftTimeNextIntermediate(int intermediateIndexOffset) {
		return etaCalibrator.correct(
				route.getLeftTimeToNextIntermediate(lastFixedLocation, intermediateIndexOffset));
	}

	public OsmandSettings getSettings() {
		return settings;
	}

	public String getGeneralRouteInformation() {
		int dist = getLeftDistance();
		int hours = getLeftTime() / (60 * 60);
		int minutes = (getLeftTime() / 60) % 60;
		return app.getString(R.string.route_general_information, OsmAndFormatter.getFormattedDistance(dist, app),
				hours, minutes);
	}

	public Location getLocationFromRouteDirection(RouteDirectionInfo i) {
		return route.getLocationFromRouteDirection(i);
	}

	public synchronized NextDirectionInfo getNextRouteDirectionInfo(NextDirectionInfo info, boolean toSpeak) {
		NextDirectionInfo i = route.getNextRouteDirectionInfo(info, lastProjection, toSpeak);
		if (i != null) {
			i.imminent = voiceRouter.calculateImminent(i.distanceTo, lastProjection);
		}
		return i;
	}

	public synchronized float getCurrentMaxSpeed() {
		return route.getCurrentMaxSpeed(getAppMode().getRouteTypeProfile());
	}

	@NonNull
	public synchronized CurrentStreetName getCurrentName(NextDirectionInfo n, boolean showNextTurn) {
		return new CurrentStreetName(this, n, showNextTurn);
	}

	public RouteSegmentResult getCurrentSegmentResult() {
		return route.getCurrentSegmentResult();
	}

	public RouteSegmentResult getNextStreetSegmentResult() {
		return route.getNextStreetSegmentResult();
	}

	public List<RouteSegmentResult> getUpcomingTunnel(float distToStart) {
		return route.getUpcomingTunnel(distToStart);
	}

	public synchronized NextDirectionInfo getNextRouteDirectionInfoAfter(NextDirectionInfo previous, NextDirectionInfo to, boolean toSpeak) {
		NextDirectionInfo i = route.getNextRouteDirectionInfoAfter(previous, to, toSpeak);
		if (i != null) {
			i.imminent = voiceRouter.calculateImminent(i.distanceTo, null);
		}
		return i;
	}

	public List<RouteDirectionInfo> getRouteDirections() {
		return new ArrayList<>(route.getRouteDirections(app));
	}

	public void onSettingsChanged() {
		onSettingsChanged(false);
	}

	public void onSettingsChanged(boolean forceRouteRecalculation) {
		onSettingsChanged(mode, forceRouteRecalculation);
	}

	public void onSettingsChanged(@Nullable ApplicationMode mode) {
		onSettingsChanged(mode, false);
	}

	public void onSettingsChanged(@Nullable ApplicationMode mode, boolean forceRouteRecalculation) {
		if (forceRouteRecalculation ||
				((mode == null || mode.equals(this.mode)) && (isRouteCalculated() || isRouteBeingCalculated()))) {
			recalculateRouteDueToSettingsChange(true);
		}
		fireRouteSettingsChangedEvent(mode);
	}

	public void recalculateRouteDueToSettingsChange(boolean clearCurrentRoute) {
		if (clearCurrentRoute) {
			clearCurrentRoute(finalLocation, intermediatePoints);
		}
		if (isPublicTransportMode()) {
			Location start = lastFixedLocation;
			LatLon finish = finalLocation;
			transportRoutingHelper.setApplicationMode(mode);
			if (start != null && finish != null) {
				transportRoutingHelper.setFinalAndCurrentLocation(finish,
						new LatLon(start.getLatitude(), start.getLongitude()));
			} else {
				transportRoutingHelper.recalculateRouteDueToSettingsChange();
			}
		} else {
			routeRecalculationHelper.recalculateRouteInBackground(lastFixedLocation, finalLocation,
					intermediatePoints, currentGPXRoute, route, true, false);
		}
	}

	public void startRouteCalculationThread(RouteCalculationParams params) {
		routeRecalculationHelper.startRouteCalculationThread(params, true, true);
	}

	public static void applyApplicationSettings(RouteCalculationParams params, OsmandSettings settings, ApplicationMode mode) {
		params.leftSide = settings.DRIVING_REGION.get().leftHandDriving;
		params.fast = settings.FAST_ROUTE_MODE.getModeValue(mode);
	}

	public void addCalculationProgressListener(@NonNull RouteCalculationProgressListener listener) {
		routeRecalculationHelper.addCalculationProgressListener(listener);
	}

	public void removeCalculationProgressListener(@NonNull RouteCalculationProgressListener listener) {
		routeRecalculationHelper.removeCalculationProgressListener(listener);
	}

	public boolean isPublicTransportMode() {
		return mode.isDerivedRoutingFrom(ApplicationMode.PUBLIC_TRANSPORT);
	}

	public boolean isBoatMode() {
		return mode.isDerivedRoutingFrom(ApplicationMode.BOAT);
	}

	public boolean isOsmandRouting() {
		return mode.getRouteService() == RouteService.OSMAND;
	}

	public boolean isRouteBeingCalculated() {
		return routeRecalculationHelper.isRouteBeingCalculated();
	}

	@NonNull
	public RouteCalculationResult getRoute() {
		return route;
	}

	public GpxFile generateGPXFileWithRoute(String name) {
		return generateGPXFileWithRoute(route, name);
	}

	public GpxFile generateGPXFileWithRoute(RouteCalculationResult route, String name) {
		return provider.createOsmandRouterGPX(route, app, name);
	}

	public RoutingEnvironment getRoutingEnvironment(OsmandApplication ctx, ApplicationMode mode, LatLon start, LatLon end) throws IOException {
		return provider.getRoutingEnvironment(ctx, mode, start, end);
	}

	public List<GpxPoint> generateGpxPoints(RoutingEnvironment env, GpxRouteApproximation gctx, LocationsHolder locationsHolder) {
		return provider.generateGpxPoints(env, gctx, locationsHolder);
	}

	public GpxRouteApproximation calculateGpxApproximation(RoutingEnvironment env, GpxRouteApproximation gctx, List<GpxPoint> points, ResultMatcher<GpxRouteApproximation> resultMatcher, boolean useExternalTimestamps) throws IOException, InterruptedException {
		return provider.calculateGpxPointsApproximation(env, gctx, points, resultMatcher, useExternalTimestamps);
	}

	public void notifyIfRouteIsCalculated() {
		if (route.isCalculated()) {
			voiceRouter.newRouteIsCalculated(true);
		}
	}

	public boolean shouldDrawFastRoutingProgressBar() {
		RouteCalculationMethod method = settings.ROUTE_CALCULATION_METHOD.getModeValue(mode);
		return method.isFastRoutingPossible(mode) && !routeRecalculationHelper.isCurrentSlowRoutingActive();
	}

	public boolean hasCurrentMissingMaps() {
		return routeRecalculationHelper.hasCurrentMissingMaps();
	}

	@Nullable
	public FastRoutingState.Status getCurrentFastRoutingComplication() {
		return routeRecalculationHelper.getCurrentFastRoutingComplication();
	}

	@Nullable
	public MissingMapsCalculationResult getCurrentMissingMapsCalculationResult() {
		return routeRecalculationHelper.getCurrentMissingMapsCalculationResult();
	}

	public void stopCalculationImmediately() {
		routeRecalculationHelper.stopCalculation();
	}
}
