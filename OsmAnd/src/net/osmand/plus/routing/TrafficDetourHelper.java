package net.osmand.plus.routing;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.helpers.CairoDriveLog;
import net.osmand.plus.routing.GoogleTrafficHelper.CongestionSpan;
import net.osmand.plus.routing.GoogleTrafficHelper.TrafficSnapshot;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.router.RouteCalculationProgress;
import net.osmand.router.RouteSegmentResult;
import net.osmand.router.RoutingConfiguration;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Traffic-aware routing on top of the offline OSM router, Waze-style: when Google reports a
 * meaningful live delay on the current route, the congested spans are mapped to their underlying
 * OSM road segments, those segments are marked temporarily impassable, and the offline router
 * computes a detour that physically avoids the jam. The detour is then live-scored by Google with
 * ONE cheap delay-poll (its own jams count against it - a detour into different traffic must not
 * win), and it replaces the current route only when it saves real time. TomTom/HERE ambient
 * overlays inform the DRIVER's eyes; this helper is how the ROUTER gets traffic eyes.
 *
 * <p>Fully best-effort: no spans, no budget, no faster alternative - nothing changes and the
 * driver keeps the route they can see. The impassable marks live only for the one computation
 * (added and removed around it), so user-chosen avoid-roads are never touched.
 */
class TrafficDetourHelper {

	private static final Log log = PlatformUtil.getLog(TrafficDetourHelper.class);

	// Only bother for delays worth detouring around, on routes with room to detour.
	private static final int MIN_DELAY_S = 240;
	private static final int MIN_REMAINING_M = 3000;
	// A detour must beat the current route by this much AFTER Google scored it - switching
	// routes mid-drive has a human cost, so marginal wins stay put.
	private static final int MIN_SAVING_S = 120;
	private static final long EVAL_THROTTLE_MS = 8 * 60 * 1000;
	// Span sample point -> route segment matching radius (Google's polyline is our own route
	// pinned via waypoints, so the geometries track within GPS noise).
	private static final double JAM_MATCH_RADIUS_M = 40;

	private static volatile long lastEvalTime;
	private static volatile boolean computing;

	private TrafficDetourHelper() {
	}

	/**
	 * True while a detour computation holds the shared map readers. Currently read only by this
	 * helper's own single-flight guard; it is also the re-attachment point if a speculative
	 * reroute helper is ported later, which is why it stays package-visible.
	 */
	static boolean isComputing() {
		return computing;
	}

	/**
	 * Called by {@link GoogleTrafficHelper} after every navigation poll with the fresh live
	 * delay. Kicks one background evaluation when the delay crosses the threshold.
	 */
	static void onTrafficUpdate(OsmandApplication app, int delaySeconds) {
		try {
			if (delaySeconds < MIN_DELAY_S || computing) {
				return;
			}
			long now = System.currentTimeMillis();
			if (now - lastEvalTime < EVAL_THROTTLE_MS) {
				return;
			}
			RoutingHelper helper = app.getRoutingHelper();
			if (!helper.isFollowingMode() || helper.isRouteBeingCalculated()
					|| helper.isDeviatedFromRoute()) {
				return;
			}
			ApplicationMode mode = helper.getAppMode();
			if (mode == null || mode.getRouteService() != RouteService.OSMAND
					|| helper.getCurrentGPXRoute() != null) {
				return; // detours only make sense for our own offline engine
			}
			RouteCalculationResult route = helper.getRoute();
			Location loc = helper.getLastFixedLocation();
			LatLon finalLocation = helper.getFinalLocation();
			if (route == null || !route.isCalculated() || loc == null || finalLocation == null
					|| !app.getTargetPointsHelper().getIntermediatePoints().isEmpty()) {
				return;
			}
			if (route.getRouteDistanceToFinish(0) < MIN_REMAINING_M) {
				return; // arriving - ride it out
			}
			TrafficSnapshot snapshot = GoogleTrafficHelper.getSnapshot();
			if (snapshot == null || snapshot.spans.isEmpty() || snapshot.points.isEmpty()) {
				return; // need actual jam geometry, not just a delay number
			}
			lastEvalTime = now;
			computing = true;
			Thread t = new Thread(() -> {
				android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
				try {
					evaluate(app, helper, mode, route, loc, finalLocation, snapshot, delaySeconds);
				} catch (Throwable th) {
					log.error("Traffic detour evaluation failed", th);
				} finally {
					computing = false;
				}
			}, "traffic-detour");
			t.setPriority(Thread.MIN_PRIORITY);
			t.start();
		} catch (Throwable t) {
			log.error("Traffic detour trigger failed", t);
			computing = false;
		}
	}

	private static void evaluate(OsmandApplication app, RoutingHelper helper, ApplicationMode mode,
	                             RouteCalculationResult route, Location loc, LatLon finalLocation,
	                             TrafficSnapshot snapshot, int delaySeconds) {
		// 1. Jam spans -> underlying OSM road ids on the current route.
		Set<Long> jamRoadIds = collectJammedRoadIds(route, snapshot);
		if (jamRoadIds.isEmpty()) {
			CairoDriveLog.log("DETOUR", "delay +" + delaySeconds + " s but no jam spans matched our roads - skip");
			return;
		}
		if (helper.isRouteBeingCalculated()) {
			return; // a real recalculation already holds the shared map readers
		}

		// 2. Offline route that physically avoids the jammed segments. The marks are added and
		// removed around this ONE computation.
		// Scope of the monitor below, stated honestly: RouteProvider does NOT synchronize on the
		// builder, and this runs on its own thread rather than the recalculation executor, so a real
		// recalculation CAN overlap and inherit these temporary ids - isRouteBeingCalculated() above
		// is a sample, not a barrier. The monitor only serialises this helper against
		// ClosureSyncHelper, which mutates the same builder. Overlap is accepted: the ids are the
		// jam the driver is already in, so a concurrent reroute avoiding them is not a wrong answer,
		// and Builder.addImpassableRoad/removeImpassableRoad are copy-on-write so a concurrent
		// build() can never see a torn set.
		// The builder MUST be the one the computation will actually read - RouteProvider resolves
		// it per mode, so a profile with a custom .routing.xml uses a builder that is NOT the
		// default one. Marking the default builder there would exclude nothing and still spend a
		// billed poll scoring the unchanged route.
		RouteCalculationResult detour;
		RoutingConfiguration.Builder config = app.getRoutingConfigForMode(mode);
		Set<Long> added = new HashSet<>();
		synchronized (config) {
			try {
				for (long id : jamRoadIds) {
					if (!config.getImpassableRoadLocations().contains(id)) {
						config.addImpassableRoad(id);
						added.add(id);
					}
				}
				detour = computeDetour(app, helper, mode, loc, finalLocation);
			} finally {
				for (long id : added) {
					config.removeImpassableRoad(id);
				}
			}
		}
		if (detour == null || !detour.isCalculated()) {
			CairoDriveLog.log("DETOUR", "no drivable route around the jam (" + jamRoadIds.size() + " segment(s)) - staying");
			return;
		}

		// 3. Google live-scores the detour - its own congestion counts against it. No budget
		// slot left means no honest comparison, so no switch.
		if (!GoogleTrafficHelper.claimDetourDelayPoll(app)) {
			CairoDriveLog.log("DETOUR", "daily delay budget spent - cannot score the detour, staying");
			return;
		}
		int detourLiveS = GoogleTrafficHelper.scoreLiveSeconds(app, loc, detour.getImmutableAllLocations());
		if (detourLiveS <= 0) {
			return; // scoring failed - never switch blind
		}
		int currentLiveS = helper.getLeftTime() + delaySeconds;
		int savingS = currentLiveS - detourLiveS;
		CairoDriveLog.log("DETOUR", "current ~" + (currentLiveS / 60) + " min live vs detour ~"
				+ (detourLiveS / 60) + " min - " + (savingS >= MIN_SAVING_S ? "SWITCHING" : "not worth it"));
		if (savingS < MIN_SAVING_S) {
			return;
		}

		// 4. Install through the recalculation executor (the same serialized path real
		// recalculations use), with the saved minutes announced once.
		helper.getRouteRecalculationHelper().installTrafficDetour(route, detour, loc,
				Math.max(1, Math.round(savingS / 60f)));
	}

	/** Distinct OSM road ids under the congested spans, matched by proximity to our route. */
	private static Set<Long> collectJammedRoadIds(RouteCalculationResult route, TrafficSnapshot snapshot) {
		Set<Long> ids = new HashSet<>();
		List<RouteSegmentResult> segments = route.getOriginalRoute();
		if (segments == null) {
			return ids;
		}
		for (CongestionSpan span : snapshot.spans) {
			int step = Math.max(1, (span.end - span.start) / 4); // <=5 sample points per span
			for (int i = span.start; i <= span.end && i < snapshot.points.size(); i += step) {
				LatLon p = snapshot.points.get(i);
				for (RouteSegmentResult seg : segments) {
					if (seg.getObject() == null) {
						continue;
					}
					LatLon a = seg.getPoint(seg.getStartPointIndex());
					LatLon b = seg.getPoint(seg.getEndPointIndex());
					if (a == null || b == null) {
						continue;
					}
					double dist = Math.min(MapUtils.getDistance(p, a), MapUtils.getDistance(p, b));
					if (dist < JAM_MATCH_RADIUS_M
							|| MapUtils.getOrthogonalDistance(p.getLatitude(), p.getLongitude(),
							a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude()) < JAM_MATCH_RADIUS_M) {
						ids.add(seg.getObject().getId());
					}
				}
			}
		}
		return ids;
	}

	private static RouteCalculationResult computeDetour(OsmandApplication app, RoutingHelper helper,
	                                                    ApplicationMode mode, Location loc,
	                                                    LatLon finalLocation) {
		RouteCalculationParams params = new RouteCalculationParams();
		Location start = new Location(loc);
		params.start = start;
		params.end = finalLocation;
		params.leftSide = app.getSettings().DRIVING_REGION.get().leftHandDriving;
		params.fast = app.getSettings().FAST_ROUTE_MODE.getModeValue(mode);
		// Same mode the caller resolved the impassable-road builder from - RouteProvider derives
		// the builder from params.mode, so these must not be allowed to drift apart.
		params.mode = mode;
		params.ctx = app;
		params.calculationProgress = new RouteCalculationProgress();
		try {
			return helper.getProvider().calculateRouteImpl(params);
		} catch (Throwable t) {
			log.error("Detour route computation failed", t);
			return null;
		}
	}
}
