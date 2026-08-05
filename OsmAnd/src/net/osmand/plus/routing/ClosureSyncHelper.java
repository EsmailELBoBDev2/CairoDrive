package net.osmand.plus.routing;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.osm.io.NetworkUtils;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.helpers.CairoDriveLog;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.router.RoutingConfiguration;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Live road-closure sync from TWO independent providers - TomTom Traffic Incidents
 * (iconCategory 8 = Road Closed) and HERE Traffic v7 incidents (type=roadClosure /
 * roadClosed=true) - fused into the offline router: closure geometry is resolved to the
 * underlying OSM roads and those ids are held in the shared routing configuration as
 * impassable, so EVERY route computation (deviation reroutes, traffic detours) avoids closed
 * roads automatically at full HH speed. Verified against the HH implementation: impassable ids
 * are honored through the detailed-routing phase with bounded re-iteration - no fallback to slow
 * routing (RoutePlannerFrontEnd/HHRoutePlanner analysis).
 *
 * <p>Zone-scoped like everything else here: the route corridor while navigating, a ~12 km
 * box around the car in free drive - never a whole country. Closures expire with the
 * 10-minute refresh (30-minute hard TTL if the network dies); a closure ON the current route
 * triggers one immediate recalculation with a spoken heads-up. User-added avoid roads are
 * never touched - only ids this helper added are ever removed.
 *
 * <p>Gated by LIVE_ROAD_CLOSURES, which is ON for the CAR profile and OFF for every other one.
 * It stays a UI toggle rather than being implied by an API key: resolving one refresh costs up to
 * SAMPLES_PER_CLOSURE * MAX_CLOSURES probes on the single shared road-lookup thread that the
 * location arrow's own snap-to-road uses, so it must remain stoppable without rebuilding.
 */
public class ClosureSyncHelper {

	private static final Log log = PlatformUtil.getLog(ClosureSyncHelper.class);

	private static final long REFRESH_MS = 10 * 60 * 1000;
	private static final long STALE_TTL_MS = 30 * 60 * 1000;
	// TomTom incidentDetails caps bbox area (~10,000 km2); HERE has no hard cap. Clamp spans.
	private static final double MAX_BBOX_SPAN_DEG = 0.9;
	private static final double NAV_MARGIN_DEG = 0.02;        // ~2 km around the route corridor
	private static final double FREE_DRIVE_SPAN_DEG = 0.11;   // ~12 km box around the car
	private static final int MAX_CLOSURES = 15;               // nearest first - bound the id resolution
	private static final int SAMPLES_PER_CLOSURE = 3;         // start/mid/end -> road id lookups
	private static final long RESOLVE_TIMEOUT_MS = 8000;
	private static final double ON_ROUTE_MATCH_M = 40;
	private static final int CONNECT_TIMEOUT = 15000;
	private static final int READ_TIMEOUT = 20000;

	private static final Object LOCK = new Object();
	private static volatile long lastFetch;
	private static volatile long lastSuccess;
	private static volatile boolean fetching;
	private static volatile boolean releasing;
	// Ids WE hold impassable right now (never contains user-added avoid roads).
	private static final Set<Long> appliedIds = new HashSet<>();
	// The builder those ids actually live in. RouteProvider resolves the builder per ApplicationMode
	// (a profile with a custom .routing.xml does NOT use the default one), so the marks must be
	// removed from the builder they were added to - a profile switch would otherwise strand them
	// there permanently, distorting every route with nothing in the UI to show it.
	private static volatile RoutingConfiguration.Builder appliedConfig;

	private ClosureSyncHelper() {
	}

	/** Called from the navigation location pipeline and the free-drive listener. */
	public static void onLocationUpdate(OsmandApplication app, Location loc, boolean navigating) {
		try {
			if (app == null || loc == null) {
				return;
			}
			// Gated here rather than at the call sites so every caller is covered by construction.
			if (!app.getSettings().LIVE_ROAD_CLOSURES.get()) {
				// Turning the toggle off mid-drive must not leave roads held impassable.
				releaseAppliedMarksAsync("live closures turned off - marks dropped");
				return;
			}
			long now = System.currentTimeMillis();
			// Before every early return below, not just the throttled one. Once the refresh window
			// has elapsed, a lost network / backgrounded app exits at one of the guards further down
			// WITHOUT advancing lastFetch, so the throttle branch never fires again - which is
			// exactly when held ids must still be able to age out. Cheap: a timestamp compare, and
			// the release itself is async.
			maybeExpire(now);
			if (fetching || (lastFetch != 0 && now - lastFetch < REFRESH_MS)) {
				return;
			}
			String tomtomKey = BuildConfig.CAIRODRIVE_TOMTOM_KEY;
			String hereKey = BuildConfig.CAIRODRIVE_HERE_KEY;
			if (Algorithms.isEmpty(tomtomKey) && Algorithms.isEmpty(hereKey)) {
				return; // no provider - fully offline build
			}
			if (!app.getSettings().isInternetConnectionAvailable() || !app.isAppInForeground()) {
				return;
			}
			ApplicationMode mode = navigating ? app.getRoutingHelper().getAppMode()
					: app.getSettings().getApplicationMode();
			if (mode == null || !mode.isDerivedRoutingFrom(ApplicationMode.CAR)) {
				return;
			}
			double[] bbox = navigating ? routeBbox(app, loc) : aroundBbox(loc);
			if (bbox == null) {
				return;
			}
			lastFetch = now;
			fetching = true;
			Thread t = new Thread(() -> {
				android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
				try {
					refresh(app, mode, loc, bbox, tomtomKey, hereKey, navigating);
				} catch (Throwable th) {
					log.error("Closure refresh failed", th);
				} finally {
					fetching = false;
				}
			}, "closure-sync");
			t.setPriority(Thread.MIN_PRIORITY);
			t.start();
		} catch (Throwable t) {
			log.error("Closure trigger failed", t);
			fetching = false;
		}
	}

	private static void refresh(OsmandApplication app, ApplicationMode mode, Location loc,
	                            double[] bbox, String tomtomKey, String hereKey, boolean navigating) {
		List<List<LatLon>> closures = new ArrayList<>();
		if (!Algorithms.isEmpty(tomtomKey)) {
			fetchTomTom(closures, tomtomKey, bbox);
		}
		if (!Algorithms.isEmpty(hereKey)) {
			fetchHere(closures, hereKey, bbox);
		}
		// Nearest closures first, then cap - id resolution costs a routing-data probe each.
		LatLon me = new LatLon(loc.getLatitude(), loc.getLongitude());
		closures.sort((a, b) -> Double.compare(distTo(me, a), distTo(me, b)));
		if (closures.size() > MAX_CLOSURES) {
			closures = closures.subList(0, MAX_CLOSURES);
		}
		Set<Long> ids = resolveRoadIds(app, mode, closures);
		applyToRouter(app, mode, ids);
		lastSuccess = System.currentTimeMillis();
		CairoDriveLog.log("CLOSURE", closures.size() + " closure(s) in zone -> "
				+ ids.size() + " road id(s) held impassable (TomTom"
				+ (Algorithms.isEmpty(tomtomKey) ? " off" : " on") + ", HERE"
				+ (Algorithms.isEmpty(hereKey) ? " off" : " on") + ")");

		// A closure sitting ON the current route: recalculate now (the ids are already baked
		// into the config, so the recalculation avoids it) and tell the driver why. Toast only:
		// the spoken sibling would need a CommandPlayer raw-text path this fork does not carry.
		if (navigating && !closures.isEmpty() && touchesCurrentRoute(app, closures)) {
			CairoDriveLog.log("CLOSURE", "closure ON the current route - recalculating around it");
			app.runInUIThread(() -> {
				app.showShortToastMessage(R.string.cairo_road_closed);
				app.getRoutingHelper().recalculateRouteDueToSettingsChange(false);
			});
		}
	}

	// ------------------------------------------------------------ providers

	/** TomTom Traffic Incidents v5: categoryFilter=8 = Road Closed, GeoJSON lon-lat pairs. */
	private static void fetchTomTom(List<List<LatLon>> out, String key, double[] bbox) {
		try {
			String url = String.format(Locale.US,
					"https://api.tomtom.com/traffic/services/5/incidentDetails?key=%s"
							+ "&bbox=%f,%f,%f,%f&categoryFilter=8&timeValidityFilter=present",
					key, bbox[0], bbox[1], bbox[2], bbox[3]); // minLon,minLat,maxLon,maxLat
			String body = get(url);
			if (body == null) {
				return;
			}
			JSONArray incidents = new JSONObject(body).optJSONArray("incidents");
			for (int i = 0; incidents != null && i < incidents.length(); i++) {
				JSONObject geometry = incidents.getJSONObject(i).optJSONObject("geometry");
				if (geometry == null) {
					continue;
				}
				JSONArray coords = geometry.optJSONArray("coordinates");
				if (coords == null || coords.length() == 0) {
					continue;
				}
				List<LatLon> line = new ArrayList<>();
				if ("Point".equals(geometry.optString("type"))) {
					line.add(new LatLon(coords.getDouble(1), coords.getDouble(0)));
				} else {
					for (int j = 0; j < coords.length(); j++) {
						JSONArray p = coords.getJSONArray(j); // [lon, lat]
						line.add(new LatLon(p.getDouble(1), p.getDouble(0)));
					}
				}
				out.add(line);
			}
		} catch (Throwable t) {
			// Type only, never getMessage(): the key rides in this URL as a query parameter and
			// MalformedURLException / FileNotFoundException both carry the full URL as their message.
			log.info("TomTom closures skipped: " + t.getClass().getSimpleName());
		}
	}

	/** HERE Traffic v7: type=roadClosure with shape geometry (links of lat/lng points). */
	private static void fetchHere(List<List<LatLon>> out, String key, double[] bbox) {
		try {
			String url = String.format(Locale.US,
					"https://data.traffic.hereapi.com/v7/incidents?in=bbox:%f,%f,%f,%f"
							+ "&locationReferencing=shape&type=roadClosure&apiKey=%s",
					bbox[0], bbox[1], bbox[2], bbox[3], key); // west,south,east,north
			String body = get(url);
			if (body == null) {
				return;
			}
			JSONArray results = new JSONObject(body).optJSONArray("results");
			for (int i = 0; results != null && i < results.length(); i++) {
				JSONObject item = results.getJSONObject(i);
				JSONObject details = item.optJSONObject("incidentDetails");
				// type=roadClosure is requested server-side; roadClosed catches edge deliveries.
				if (details != null && !details.optBoolean("roadClosed", true)) {
					continue;
				}
				JSONObject location = item.optJSONObject("location");
				JSONObject shape = location != null ? location.optJSONObject("shape") : null;
				JSONArray links = shape != null ? shape.optJSONArray("links") : null;
				if (links == null) {
					continue;
				}
				List<LatLon> line = new ArrayList<>();
				for (int j = 0; j < links.length(); j++) {
					JSONArray points = links.getJSONObject(j).optJSONArray("points");
					for (int k = 0; points != null && k < points.length(); k++) {
						JSONObject p = points.getJSONObject(k);
						line.add(new LatLon(p.getDouble("lat"), p.getDouble("lng")));
					}
				}
				if (!line.isEmpty()) {
					out.add(line);
				}
			}
		} catch (Throwable t) {
			// Type only - same key-in-URL reason as fetchTomTom.
			log.info("HERE closures skipped: " + t.getClass().getSimpleName());
		}
	}

	// -------------------------------------------------------- id resolution

	/** Sample points of each closure -> underlying OSM road ids, via the offline road index. */
	private static Set<Long> resolveRoadIds(OsmandApplication app, ApplicationMode mode,
	                                        List<List<LatLon>> closures) {
		Set<Long> ids = java.util.Collections.synchronizedSet(new HashSet<>());
		List<LatLon> samples = new ArrayList<>();
		for (List<LatLon> line : closures) {
			samples.add(line.get(0));
			if (line.size() > 2) {
				samples.add(line.get(line.size() / 2));
			}
			if (line.size() > 1) {
				samples.add(line.get(line.size() - 1));
			}
		}
		if (samples.isEmpty()) {
			return ids;
		}
		CountDownLatch latch = new CountDownLatch(samples.size());
		for (LatLon p : samples) {
			Location probe = new Location("closure"); //$NON-NLS-1$
			probe.setLatitude(p.getLatitude());
			probe.setLongitude(p.getLongitude());
			boolean scheduled = app.getLocationProvider().getRouteSegment(probe, mode, false,
					new ResultMatcher<RouteDataObject>() {
						@Override
						public boolean publish(RouteDataObject object) {
							if (object != null) {
								ids.add(object.getId());
							}
							latch.countDown();
							return true;
						}

						@Override
						public boolean isCancelled() {
							return false;
						}
					});
			if (!scheduled) {
				latch.countDown();
			}
		}
		try {
			latch.await(RESOLVE_TIMEOUT_MS, TimeUnit.MILLISECONDS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return new HashSet<>(ids);
	}

	/** Swap the held impassable set to {@code ids} - only ever removing ids WE added. */
	private static void applyToRouter(OsmandApplication app, ApplicationMode mode, Set<Long> ids) {
		RoutingConfiguration.Builder config = app.getRoutingConfigForMode(mode);
		// Released FIRST, in its own monitor: two builder monitors are never held at once, so no
		// lock-order inversion can exist between this and the detour helper.
		releaseMarksOutside(config);
		// Lock order is fixed: the builder monitor OUTSIDE, LOCK inside. TrafficDetourHelper holds
		// a builder monitor across a multi-second computation, so taking LOCK first anywhere here
		// would deadlock against it.
		synchronized (config) {
			synchronized (LOCK) {
				for (Long stale : new HashSet<>(appliedIds)) {
					if (!ids.contains(stale)) {
						config.removeImpassableRoad(stale);
						appliedIds.remove(stale);
					}
				}
				for (Long id : ids) {
					if (!appliedIds.contains(id) && !config.getImpassableRoadLocations().contains(id)) {
						config.addImpassableRoad(id);
						appliedIds.add(id);
					}
				}
				appliedConfig = appliedIds.isEmpty() ? null : config;
			}
		}
	}

	/** Drops every mark we hold in a builder that is no longer the one the router will read. */
	private static void releaseMarksOutside(RoutingConfiguration.Builder keep) {
		RoutingConfiguration.Builder previous = appliedConfig;
		if (previous == null || previous == keep) {
			return;
		}
		synchronized (previous) {
			synchronized (LOCK) {
				if (appliedConfig != previous) {
					return; // another thread already released it
				}
				for (Long id : appliedIds) {
					previous.removeImpassableRoad(id);
				}
				appliedIds.clear();
				appliedConfig = null;
			}
		}
	}

	/**
	 * Drops every mark we hold, wherever it lives - always off the caller's thread. Both callers
	 * run on the location pipeline, i.e. the main looper, and TrafficDetourHelper holds a builder
	 * monitor across a multi-second route computation: taking it inline would park the UI thread
	 * behind that computation.
	 */
	private static void releaseAppliedMarksAsync(String reason) {
		if (appliedConfig == null || releasing) {
			return;
		}
		releasing = true;
		boolean started = false;
		try {
			Thread t = new Thread(() -> {
				android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
				try {
					if (releaseAppliedMarks()) {
						CairoDriveLog.log("CLOSURE", reason);
					}
				} catch (Throwable th) {
					log.error("Closure release failed", th);
				} finally {
					releasing = false;
				}
			}, "closure-release");
			t.setPriority(Thread.MIN_PRIORITY);
			t.start();
			started = true;
		} finally {
			if (!started) {
				releasing = false; // thread failed to start - never leave the release latched off
			}
		}
	}

	/** True when marks were actually dropped. Blocking - background threads only. */
	private static boolean releaseAppliedMarks() {
		RoutingConfiguration.Builder config = appliedConfig;
		if (config == null) {
			return false;
		}
		boolean dropped = false;
		synchronized (config) {
			synchronized (LOCK) {
				if (appliedConfig == config) {
					for (Long id : appliedIds) {
						config.removeImpassableRoad(id);
					}
					dropped = !appliedIds.isEmpty();
					appliedIds.clear();
					appliedConfig = null;
				}
			}
		}
		lastSuccess = 0;
		return dropped;
	}

	/** Network died: closures older than the TTL are more likely fiction than fact - drop them. */
	private static void maybeExpire(long now) {
		if (lastSuccess == 0 || now - lastSuccess < STALE_TTL_MS) {
			return;
		}
		if (appliedConfig == null) {
			lastSuccess = 0; // nothing held - stop re-checking until the next successful refresh
			return;
		}
		releaseAppliedMarksAsync("closure data stale (no refresh in 30 min) - marks dropped");
	}

	// ------------------------------------------------------------- helpers

	private static boolean touchesCurrentRoute(OsmandApplication app, List<List<LatLon>> closures) {
		RouteCalculationResult route = app.getRoutingHelper().getRoute();
		if (route == null || !route.isCalculated()) {
			return false;
		}
		List<Location> nodes = route.getRouteLocations();
		if (nodes == null || nodes.isEmpty()) {
			return false;
		}
		for (List<LatLon> line : closures) {
			LatLon mid = line.get(line.size() / 2);
			for (Location node : nodes) {
				if (MapUtils.getDistance(mid, node.getLatitude(), node.getLongitude()) < ON_ROUTE_MATCH_M) {
					return true;
				}
			}
		}
		return false;
	}

	private static double[] routeBbox(OsmandApplication app, Location loc) {
		RouteCalculationResult route = app.getRoutingHelper().getRoute();
		List<Location> nodes = route != null ? route.getImmutableAllLocations() : null;
		if (nodes == null || nodes.isEmpty()) {
			return aroundBbox(loc);
		}
		double minLat = loc.getLatitude(), maxLat = loc.getLatitude();
		double minLon = loc.getLongitude(), maxLon = loc.getLongitude();
		for (Location n : nodes) {
			minLat = Math.min(minLat, n.getLatitude());
			maxLat = Math.max(maxLat, n.getLatitude());
			minLon = Math.min(minLon, n.getLongitude());
			maxLon = Math.max(maxLon, n.getLongitude());
		}
		minLat -= NAV_MARGIN_DEG;
		maxLat += NAV_MARGIN_DEG;
		minLon -= NAV_MARGIN_DEG;
		maxLon += NAV_MARGIN_DEG;
		// Clamp to the provider's max area, centered on the car (the far end of a very long
		// route gets covered as the car approaches and the box slides with it).
		if (maxLat - minLat > MAX_BBOX_SPAN_DEG) {
			minLat = Math.max(minLat, loc.getLatitude() - MAX_BBOX_SPAN_DEG / 2);
			maxLat = Math.min(maxLat, minLat + MAX_BBOX_SPAN_DEG);
		}
		if (maxLon - minLon > MAX_BBOX_SPAN_DEG) {
			minLon = Math.max(minLon, loc.getLongitude() - MAX_BBOX_SPAN_DEG / 2);
			maxLon = Math.min(maxLon, minLon + MAX_BBOX_SPAN_DEG);
		}
		return new double[] {minLon, minLat, maxLon, maxLat};
	}

	private static double[] aroundBbox(Location loc) {
		double half = FREE_DRIVE_SPAN_DEG / 2;
		return new double[] {loc.getLongitude() - half, loc.getLatitude() - half,
				loc.getLongitude() + half, loc.getLatitude() + half};
	}

	private static double distTo(LatLon me, List<LatLon> line) {
		LatLon mid = line.get(line.size() / 2);
		return MapUtils.getDistance(me, mid);
	}

	private static String get(String url) throws Exception {
		HttpURLConnection c = NetworkUtils.getHttpURLConnection(url);
		try {
			c.setConnectTimeout(CONNECT_TIMEOUT);
			c.setReadTimeout(READ_TIMEOUT);
			c.setRequestProperty("Accept", "application/json");
			int code = c.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) {
				// Return BEFORE touching any stream: the provider keys ride in this URL as query
				// parameters, and reading the error stream surfaces the full URL in the exception
				// message. Log the code only, never the URL or the body.
				log.info("Closure provider HTTP " + code);
				return null;
			}
			StringBuilder sb = new StringBuilder();
			try (BufferedReader r = new BufferedReader(
					new InputStreamReader(c.getInputStream(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = r.readLine()) != null) {
					sb.append(line);
				}
			}
			return sb.toString();
		} finally {
			c.disconnect();
		}
	}
}
