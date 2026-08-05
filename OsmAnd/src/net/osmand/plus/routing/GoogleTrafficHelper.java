package net.osmand.plus.routing;

import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.osm.io.NetworkUtils;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.Version;
import net.osmand.plus.helpers.CairoDriveLog;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.views.OsmandMap;
import net.osmand.util.Algorithms;
import net.osmand.util.GeoPolylineParserUtil;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Live traffic on the current route from the Google Routes API - the third traffic source next to
 * the TomTom/HERE raster overlays ({@link net.osmand.plus.helpers.TrafficOverlayHelper}). Google has
 * no raster traffic tiles for third-party basemaps, but computeRoutes with TRAFFIC_AWARE +
 * TRAFFIC_ON_POLYLINE returns congestion spans (SLOW / TRAFFIC_JAM) along a requested polyline plus
 * live vs free-flow duration. While navigating, this polls the remaining route (pinned to OsmAnd's
 * own geometry with via-waypoints so Google scores YOUR route, not its preferred one), stores the
 * result for {@link net.osmand.plus.views.layers.GoogleTrafficLayer} to paint over the route line,
 * and toasts the live delay when it is worth knowing. In free driving (moving with no route set),
 * it instead polls a ~5 km corridor along the road ahead - projected from the current bearing and
 * snapped to roads by Google's own router - so congestion colors appear around you just like the
 * TomTom/HERE ambient overlays, only fresher and road-accurate.
 *
 * Offline-safe and key-gated: needs BOTH a google_routes_api_key in the build AND
 * GOOGLE_TRAFFIC_ON_ROUTE, which defaults ON for the CAR profile and OFF for every other one, and
 * is switched from Configure map. Without either, zero network calls. Any failure degrades
 * silently - navigation is never affected.
 *
 * Cost model (post-March-2025 Google pricing, free allowance is per SKU per calendar month):
 * TRAFFIC_ON_POLYLINE bills "Compute Routes Enterprise" (1,000 free, $15/1000 after) while a plain
 * TRAFFIC_AWARE delay check bills "Compute Routes Pro" (5,000 free, $10/1000 after). To squeeze
 * the most out of both free pools without ever paying: polls run every 2 minutes (the freshness
 * Google's own apps feel like), the expensive span-poll interleaved at most every OTHER poll
 * (congestion colors every ~4 min while the budget lasts) and the cheap delay-poll filling the
 * slots between. The daily caps of 32 and 160 are the $0 guarantee: even a 31-day month of
 * maxed-out driving stays at 992 + <=4,960 requests, under both free allowances - a long day
 * just spends its span budget sooner (~2.1 h of colors, ETA polls continue for hours after).
 */
public class GoogleTrafficHelper {

	private static final Log log = PlatformUtil.getLog(GoogleTrafficHelper.class);

	private static final String ROUTES_API = "https://routes.googleapis.com/directions/v2:computeRoutes";
	// Span-poll mask: polyline + congestion spans + live/free-flow durations (Enterprise SKU).
	private static final String FIELD_MASK_SPANS = "routes.duration,routes.staticDuration,"
			+ "routes.polyline.encodedPolyline,routes.travelAdvisory.speedReadingIntervals";
	// Delay-poll mask: durations only, no extraComputations -> stays on the Pro SKU.
	private static final String FIELD_MASK_DELAY = "routes.duration,routes.staticDuration";

	// 2 min matches how fresh Google's own apps feel. Freshness-first within the SAME free
	// pools: the daily caps below are unchanged, so a long day spends the span budget sooner
	// (~2.1 h of colored congestion, then ETA-only polls) instead of stretching stale colors.
	// A typical Cairo commute fits inside that window, so in practice it is fresher AND $0.
	private static final long CHECK_INTERVAL_MS = 2 * 60 * 1000;
	private static final long REROUTE_DEBOUNCE_MS = 60 * 1000;     // after a reroute, refresh sooner but never storm
	// Urgent variant for a route swapped in under the driver on a corridor Google has not scored:
	// re-score fast. A traffic detour does NOT qualify - it was scored moments ago, so it passes
	// false and the extra billed poll is never spent.
	private static final long URGENT_REROUTE_DEBOUNCE_MS = 15 * 1000;
	// Free driving: only span-polls make sense (no route -> no ETA), and they draw from the small
	// Enterprise budget, so poll at half the navigation cadence: 6 min = same span freshness
	// navigation gets, and the 32/day budget covers ~3h of daily cruising.
	private static final long FREE_DRIVE_INTERVAL_MS = 6 * 60 * 1000;
	private static final double FREE_DRIVE_LOOKAHEAD_M = 5000;     // corridor length ahead of the car
	private static final float MIN_FREE_DRIVE_SPEED_MS = 2f;       // parked/walking -> bearing junk, skip
	public static final long SNAPSHOT_TTL_MS = 10 * 60 * 1000;     // stop painting spans nobody refreshed
	private static final long TOAST_REPEAT_MS = 10 * 60 * 1000;
	private static final int TOAST_MIN_DELAY_SEC = 5 * 60;         // only surface delays worth knowing
	private static final int MAX_INTERMEDIATES = 20;               // Routes API caps at 25; keep headroom
	private static final double MIN_REMAINING_M = 1000;            // arrival imminent - not worth a request
	private static final int DELAY_MIN_REMAINING_M = 2500;         // a "+N min on your route" toast is noise this close
	// "Same jam" skip: not moved and data still young -> a poll buys nothing yet. Cadence degrades
	// to at most 6 min while stopped and snaps back the moment the car moves 150 m.
	private static final double STATIONARY_RADIUS_M = 150;
	private static final long STATIONARY_MAX_AGE_MS = 6 * 60 * 1000;
	// Free-drive corridor reuse: skip the re-poll while still inside the last fetched corridor
	// going the same way; the 8-min age cap refreshes 2 min before the 10-min paint TTL.
	private static final long CORRIDOR_MAX_AGE_MS = 8 * 60 * 1000;
	private static final float CORRIDOR_MAX_BEARING_DIFF = 30f;
	// Daily budgets sized so a full 31-day month stays inside each SKU's free monthly allowance
	// (guaranteed $0): spans = TRAFFIC_ON_POLYLINE / Enterprise SKU, 32*31=992 <= 1,000 free;
	// delay = plain TRAFFIC_AWARE / Pro SKU, 160*31=4,960 <= 5,000 free.
	// CAVEATS the $0 math depends on: (a) those free allowances are Google's to change - re-check
	// them in the Cloud billing console now and then, and set a budget alert there as a backstop;
	// (b) the counters are PER DEVICE - if more than one phone ships the same key, shrink these
	// caps accordingly (each device only counts its own requests).
	private static final int SPANS_DAILY_CAP = 32;
	private static final int DELAY_DAILY_CAP = 160;

	private static final int TIER_NONE = 0;
	private static final int TIER_DELAY = 1;  // durations only - cheap Pro SKU
	private static final int TIER_SPANS = 2;  // + congestion polyline - expensive Enterprise SKU
	private static final int CONNECT_TIMEOUT = 15000;
	private static final int READ_TIMEOUT = 20000;

	/** Immutable result of one poll; geo-anchored, so it stays valid across reroutes until refreshed. */
	public static final class TrafficSnapshot {
		public final List<LatLon> points;
		public final List<CongestionSpan> spans;
		public final int delaySeconds;
		public final long timeMs;
		// When the spans were actually fetched: a cheap delay-poll carries the previous span data
		// forward unchanged, so span expiry must run on this stamp, not on timeMs.
		public final long spansTimeMs;
		public final int version;

		TrafficSnapshot(List<LatLon> points, List<CongestionSpan> spans, int delaySeconds,
		                long timeMs, long spansTimeMs, int version) {
			this.points = points;
			this.spans = spans;
			this.delaySeconds = delaySeconds;
			this.timeMs = timeMs;
			this.spansTimeMs = spansTimeMs;
			this.version = version;
		}
	}

	/** A congested run of the snapshot polyline: points[start..end] inclusive. */
	public static final class CongestionSpan {
		public final int start;
		public final int end;
		public final boolean jam; // true = TRAFFIC_JAM (red), false = SLOW (orange)

		CongestionSpan(int start, int end, boolean jam) {
			this.start = start;
			this.end = end;
			this.jam = jam;
		}
	}

	private static volatile TrafficSnapshot snapshot;
	private static volatile long lastCheck;
	private static volatile long lastToast;
	private static volatile boolean inFlight;
	private static volatile String cachedCertSha1;
	private static boolean lastPollWasSpans;
	private static int versionCounter;
	// Where the last poll ran from (nav: same-jam skip; free-drive: corridor reuse). Written under
	// the class lock; racy reads are benign (worst case one extra or one skipped poll).
	private static volatile double lastPollLat;
	private static volatile double lastPollLon;
	private static volatile float corridorBearing;
	// Bumped by reset(): a fetch that was already in flight when navigation stopped compares its
	// captured value and drops the late result instead of resurrecting a dead route's snapshot.
	private static volatile int generation;

	private GoogleTrafficHelper() {
	}

	public static TrafficSnapshot getSnapshot() {
		return snapshot;
	}

	public static void onLocationUpdate(RoutingHelper helper, Location loc) {
		try {
			OsmandApplication app = helper.getApplication();
			if (app == null || loc == null || !app.getSettings().GOOGLE_TRAFFIC_ON_ROUTE.get()) {
				return;
			}
			// Cheapest gate first: this runs on every 1 Hz GPS fix on the arrow's thread, so the
			// throttle must fire before resource lookups / connectivity binder calls (audit).
			// Monotonic clock: a wall-clock step (NTP/carrier/manual) must never stall the poller
			// or grant a second free-tier budget - see rollDailyCountersIfNeeded for the day side.
			// lastCheck == 0 means "never polled": elapsedRealtime is uptime, so right after a
			// device reboot now-0 is small and would otherwise suppress the first poll.
			long now = SystemClock.elapsedRealtime();
			if (inFlight || (lastCheck != 0 && now - lastCheck < CHECK_INTERVAL_MS)) {
				return;
			}
			String key = app.getString(R.string.google_routes_api_key);
			if (Algorithms.isEmpty(key)) {
				return; // no key -> feature off, app stays fully offline
			}
			if (!helper.isFollowingMode() || !app.getSettings().isInternetConnectionAvailable()) {
				return;
			}
			if (!app.isAppInForeground()) {
				return; // nobody sees the paint; first fix after resume polls if the cadence is due
			}
			// Traffic-aware DRIVE only makes sense for car-like navigation.
			ApplicationMode mode = helper.getAppMode();
			if (mode == null || !mode.isDerivedRoutingFrom(ApplicationMode.CAR)) {
				return;
			}
			double lat = loc.getLatitude();
			double lon = loc.getLongitude();
			// O(1) arrival gate BEFORE the budget claim and the route copy - the old per-poll
			// route walk on this (location) thread was audit-flagged.
			int toFinish = helper.getRoute().getRouteDistanceToFinish(0);
			if (toFinish > 0 && toFinish < MIN_REMAINING_M) {
				return;
			}
			// Same-jam skip: barely moved and the data is still young - a poll buys nothing yet.
			// lastCheck is NOT advanced, so it fires the moment we move or the data ages.
			TrafficSnapshot young = snapshot;
			if (young != null && now - young.timeMs < STATIONARY_MAX_AGE_MS
					&& now - young.spansTimeMs < STATIONARY_MAX_AGE_MS
					&& MapUtils.getDistance(lat, lon, lastPollLat, lastPollLon) < STATIONARY_RADIUS_M) {
				return;
			}
			// Copy the remaining route on this thread - the sublist is a view over the live route.
			List<Location> remaining = new ArrayList<>(helper.getRoute().getRouteLocations());
			if (remaining.size() < 2) {
				return;
			}
			boolean allowDelay = toFinish <= 0 || toFinish >= DELAY_MIN_REMAINING_M;
			int tier;
			synchronized (GoogleTrafficHelper.class) {
				// Re-check under the lock: two rapid GPS fixes could both pass the fast path above.
				if (inFlight || (lastCheck != 0 && now - lastCheck < CHECK_INTERVAL_MS)) {
					return;
				}
				lastCheck = now;
				tier = claimRequestTier(app, allowDelay);
				if (tier == TIER_NONE) {
					return; // budgets spent - lastCheck still advanced, so we re-check at poll cadence
				}
				inFlight = true;
				lastPollLat = lat;
				lastPollLon = lon;
			}
			boolean spansPoll = tier == TIER_SPANS;
			int gen = generation;
			Float heading = loc.hasBearing() ? loc.getBearing() : null;
			boolean started = false;
			try {
				Thread t = new Thread(() -> {
					android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
					try {
						fetchTraffic(app, key, lat, lon, heading, remaining, gen, spansPoll);
					} catch (Throwable th) {
						log.info("Google traffic check skipped: " + th.getMessage());
					} finally {
						inFlight = false;
					}
				}, "google-traffic");
				t.setPriority(Thread.MIN_PRIORITY);
				t.start();
				started = true;
			} finally {
				if (!started) {
					inFlight = false; // thread failed to start - never leave the poller dead
				}
			}
		} catch (Throwable t) {
			log.error("Google traffic trigger failed", t);
		}
	}

	/**
	 * Free-driving twin of {@link #onLocationUpdate}: no route, so poll a corridor along the road
	 * ahead instead. Registered as a plain location listener by GoogleTrafficLayer; no-ops while
	 * navigating (the routing hook owns polling then) or when stationary.
	 */
	public static void onFreeDriveLocation(OsmandApplication app, Location loc) {
		try {
			if (app == null || loc == null || !app.getSettings().GOOGLE_TRAFFIC_ON_ROUTE.get()) {
				return;
			}
			// Cheapest gate first - this fires on every location fix (see the nav-path comment).
			long now = SystemClock.elapsedRealtime();
			if (inFlight || (lastCheck != 0 && now - lastCheck < FREE_DRIVE_INTERVAL_MS)) {
				return;
			}
			String key = app.getString(R.string.google_routes_api_key);
			if (Algorithms.isEmpty(key)) {
				return;
			}
			if (app.getRoutingHelper().isFollowingMode()) {
				return; // navigation path owns polling - avoid double-firing
			}
			if (!app.getSettings().isInternetConnectionAvailable()) {
				return;
			}
			if (!app.isAppInForeground()) {
				return; // never stream position to Google while the app is backgrounded
			}
			// Free driving is still car-context only: the active profile must be car-derived.
			ApplicationMode mode = app.getSettings().getApplicationMode();
			if (mode == null || !mode.isDerivedRoutingFrom(ApplicationMode.CAR)) {
				return;
			}
			// If a raster traffic overlay is active, the ambient picture is already painted from
			// the far larger tile budgets - keep the tiny span budget for navigation.
			String overlay = app.getSettings().MAP_OVERLAY.get();
			if (overlay != null && overlay.contains("Traffic (flow)")) {
				return;
			}
			// A road corridor needs a trustworthy direction of travel.
			if (!loc.hasBearing() || !loc.hasSpeed() || loc.getSpeed() < MIN_FREE_DRIVE_SPEED_MS) {
				return;
			}
			double lat = loc.getLatitude();
			double lon = loc.getLongitude();
			// Corridor reuse: still inside the last fetched corridor, same direction, young data -
			// re-polling would fetch the same 5 km again. lastCheck is not advanced, so the poll
			// fires immediately after a turn (bearing change) or past the corridor midpoint.
			TrafficSnapshot young = snapshot;
			if (young != null && now - young.spansTimeMs < CORRIDOR_MAX_AGE_MS
					&& MapUtils.getDistance(lat, lon, lastPollLat, lastPollLon) < FREE_DRIVE_LOOKAHEAD_M / 2
					&& Math.abs(MapUtils.degreesDiff(loc.getBearing(), corridorBearing)) < CORRIDOR_MAX_BEARING_DIFF) {
				return;
			}
			synchronized (GoogleTrafficHelper.class) {
				if (inFlight || (lastCheck != 0 && now - lastCheck < FREE_DRIVE_INTERVAL_MS)) {
					return;
				}
				lastCheck = now;
				if (!claimSpansPoll(app)) {
					return; // span budget spent - lastCheck advanced, re-check at free-drive cadence
				}
				inFlight = true;
				lastPollLat = lat;
				lastPollLon = lon;
				corridorBearing = loc.getBearing();
			}
			int gen = generation;
			float bearing = loc.getBearing();
			LatLon ahead = MapUtils.rhumbDestinationPoint(lat, lon, FREE_DRIVE_LOOKAHEAD_M, bearing);
			boolean started = false;
			try {
				Thread t = new Thread(() -> {
					android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
					try {
						String body = buildFreeDriveBody(lat, lon, bearing, ahead);
						String response = postComputeRoutes(app, key, body, true);
						if (response != null) {
							parseAndStore(app, response, gen, true, false);
						}
					} catch (Throwable th) {
						log.info("Google traffic free-drive check skipped: " + th.getMessage());
					} finally {
						inFlight = false;
					}
				}, "google-traffic");
				t.setPriority(Thread.MIN_PRIORITY);
				t.start();
				started = true;
			} finally {
				if (!started) {
					inFlight = false; // thread failed to start - never leave the poller dead
				}
			}
		} catch (Throwable t) {
			log.error("Google traffic free-drive trigger failed", t);
		}
	}

	private static void rollDailyCountersIfNeeded(OsmandApplication app) {
		int today = (int) (System.currentTimeMillis() / (24 * 60 * 60 * 1000L));
		// Roll FORWARD only: a wall clock stepped backwards across midnight must never grant a
		// second daily budget - the $0 free-tier math has only a few requests of monthly headroom.
		if (today > app.getSettings().GOOGLE_TRAFFIC_REQUEST_DAY.get()) {
			app.getSettings().GOOGLE_TRAFFIC_REQUEST_DAY.set(today);
			app.getSettings().GOOGLE_TRAFFIC_REQUEST_COUNT.set(0);
			app.getSettings().GOOGLE_TRAFFIC_DELAY_REQUEST_COUNT.set(0);
		}
	}

	/** Span-poll-only budget claim for free driving. Call while holding the class lock. */
	private static boolean claimSpansPoll(OsmandApplication app) {
		rollDailyCountersIfNeeded(app);
		int spansUsed = app.getSettings().GOOGLE_TRAFFIC_REQUEST_COUNT.get();
		if (spansUsed >= SPANS_DAILY_CAP) {
			return false;
		}
		app.getSettings().GOOGLE_TRAFFIC_REQUEST_COUNT.set(spansUsed + 1);
		lastPollWasSpans = true;
		return true;
	}

	/**
	 * Billing guard + squeeze: claims the best affordable request tier against persisted per-day
	 * budgets (reset on the next UTC day; survive app restarts). The expensive span-poll
	 * (Enterprise SKU, tiny free pool) is granted at most every other poll so it stretches across
	 * the whole drive at ~6-min freshness; the cheap delay-poll (Pro SKU) fills the slots between
	 * and keeps going for hours after the span budget is gone. Call while holding the class lock.
	 */
	private static int claimRequestTier(OsmandApplication app, boolean allowDelay) {
		rollDailyCountersIfNeeded(app);
		int spansUsed = app.getSettings().GOOGLE_TRAFFIC_REQUEST_COUNT.get();
		int delayUsed = app.getSettings().GOOGLE_TRAFFIC_DELAY_REQUEST_COUNT.get();
		boolean delayPoolLeft = delayUsed < DELAY_DAILY_CAP;
		if (spansUsed < SPANS_DAILY_CAP && (!lastPollWasSpans || !delayPoolLeft)) {
			app.getSettings().GOOGLE_TRAFFIC_REQUEST_COUNT.set(spansUsed + 1);
			lastPollWasSpans = true;
			return TIER_SPANS;
		}
		if (delayPoolLeft) {
			if (!allowDelay) {
				// Final approach: the delay toast would be noise, so idle this slot without
				// claiming - and keep the alternation ticking so span-polls stay at their cadence.
				lastPollWasSpans = false;
				return TIER_NONE;
			}
			app.getSettings().GOOGLE_TRAFFIC_DELAY_REQUEST_COUNT.set(delayUsed + 1);
			lastPollWasSpans = false;
			return TIER_DELAY;
		}
		if (delayUsed == DELAY_DAILY_CAP) {
			app.getSettings().GOOGLE_TRAFFIC_DELAY_REQUEST_COUNT.set(delayUsed + 1); // log the stop once
			log.info("Google traffic: daily free-tier budgets spent (" + SPANS_DAILY_CAP + " span + "
					+ DELAY_DAILY_CAP + " delay polls) - paused until tomorrow");
			CairoDriveLog.log("TRAFFIC", "daily free budget spent - polling paused until tomorrow");
		}
		return TIER_NONE;
	}

	/**
	 * One extra delay-poll slot for scoring a traffic detour candidate (same Pro-SKU budget the
	 * regular delay-polls draw from, so the $0 guarantee is untouched). False = budget spent.
	 */
	static boolean claimDetourDelayPoll(OsmandApplication app) {
		synchronized (GoogleTrafficHelper.class) {
			rollDailyCountersIfNeeded(app);
			int delayUsed = app.getSettings().GOOGLE_TRAFFIC_DELAY_REQUEST_COUNT.get();
			if (delayUsed >= DELAY_DAILY_CAP) {
				return false;
			}
			app.getSettings().GOOGLE_TRAFFIC_DELAY_REQUEST_COUNT.set(delayUsed + 1);
			return true;
		}
	}

	/**
	 * Live-scores an arbitrary candidate route (a traffic detour) with one delay-mask request:
	 * returns Google's live duration in seconds, or -1 on any failure. The caller must have
	 * claimed the budget slot first. Blocking - call from a background thread only.
	 */
	static int scoreLiveSeconds(OsmandApplication app, Location loc, List<Location> routeLocations) {
		try {
			String key = app.getString(R.string.google_routes_api_key);
			if (Algorithms.isEmpty(key) || routeLocations == null || routeLocations.size() < 2) {
				return -1;
			}
			double total = remainingDistance(routeLocations);
			Float heading = loc != null && loc.hasBearing() ? loc.getBearing() : null;
			double lat = loc != null ? loc.getLatitude() : routeLocations.get(0).getLatitude();
			double lon = loc != null ? loc.getLongitude() : routeLocations.get(0).getLongitude();
			String body = buildRequestBody(lat, lon, heading, routeLocations, total, false);
			String response = postComputeRoutes(app, key, body, false);
			if (response == null) {
				return -1;
			}
			JSONArray routes = new JSONObject(response).optJSONArray("routes");
			if (routes == null || routes.length() == 0) {
				return -1;
			}
			return parseDurationSeconds(routes.getJSONObject(0).optString("duration", ""));
		} catch (Throwable t) {
			log.info("Detour scoring skipped: " + t.getMessage());
			return -1;
		}
	}

	/**
	 * Called when a new/recalculated route is installed: keep the geo-anchored spans (still real
	 * congestion) but let the next poll come sooner, so the changed route gets scored quickly
	 * without a request storm during GPS chaos.
	 */
	public static void onNewRoute() {
		onNewRoute(false);
	}

	public static void onNewRoute(boolean urgent) {
		long debounce = urgent ? URGENT_REROUTE_DEBOUNCE_MS : REROUTE_DEBOUNCE_MS;
		long earliestAllowed = SystemClock.elapsedRealtime() - CHECK_INTERVAL_MS + debounce;
		if (lastCheck > earliestAllowed) {
			lastCheck = earliestAllowed;
		}
	}

	/** Navigation stopped/cancelled: drop everything and repaint so stale spans disappear at once. */
	public static void reset(OsmandApplication app) {
		synchronized (GoogleTrafficHelper.class) {
			generation++; // invalidates any fetch still in flight
			snapshot = null;
			lastPollWasSpans = false; // next drive starts with a span-poll, painting spans right away
		}
		lastCheck = 0;
		lastToast = 0;
		if (app != null) {
			// osmandMap is assigned during app init; reset() is reachable from clearCurrentRoute
			// before that lands, and an NPE inside a posted runnable is an uncaught crash.
			app.runInUIThread(() -> refreshMapIfReady(app));
		}
	}

	/** Repaint only once the map exists - osmandMap is null until AppInitializer assigns it. */
	private static void refreshMapIfReady(@NonNull OsmandApplication app) {
		OsmandMap map = app.getOsmandMap();
		if (map != null) {
			map.refreshMap();
		}
	}

	private static void fetchTraffic(OsmandApplication app, String key, double lat, double lon,
	                                 Float heading, List<Location> remaining, int gen,
	                                 boolean spansPoll) throws Exception {
		// The full route walk runs here on the poll thread - the location thread only pays the
		// O(1) getRouteDistanceToFinish gate.
		double total = remainingDistance(remaining);
		if (total < MIN_REMAINING_M) {
			return;
		}
		String body = buildRequestBody(lat, lon, heading, remaining, total, spansPoll);
		String response = postComputeRoutes(app, key, body, spansPoll);
		if (response != null) {
			parseAndStore(app, response, gen, spansPoll, true);
		}
	}

	/** POSTs a computeRoutes body; returns the response JSON, or null on any HTTP error (logged). */
	private static String postComputeRoutes(OsmandApplication app, String key, String body,
	                                        boolean spansPoll) throws Exception {
		HttpURLConnection c = NetworkUtils.getHttpURLConnection(ROUTES_API);
		try {
			c.setConnectTimeout(CONNECT_TIMEOUT);
			c.setReadTimeout(READ_TIMEOUT);
			c.setDoInput(true);
			c.setDoOutput(true);
			c.setUseCaches(false);
			c.setRequestMethod("POST");
			c.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			c.setRequestProperty("Accept", "application/json");
			c.setRequestProperty("X-Goog-Api-Key", key);
			c.setRequestProperty("X-Goog-FieldMask", spansPoll ? FIELD_MASK_SPANS : FIELD_MASK_DELAY);
			// An Android-app-restricted key checks these against the Cloud Console restriction -
			// plain REST can't prove the signature, so we send package + signing-cert SHA-1 ourselves.
			c.setRequestProperty("X-Android-Package", app.getPackageName());
			String cert = getSigningCertSha1(app);
			if (!Algorithms.isEmpty(cert)) {
				c.setRequestProperty("X-Android-Cert", cert);
			}
			c.setRequestProperty("User-Agent", Version.getFullVersion(app));

			byte[] payload = body.getBytes(StandardCharsets.UTF_8);
			c.setFixedLengthStreamingMode(payload.length);
			OutputStream out = c.getOutputStream();
			out.write(payload);
			out.flush();
			out.close();

			int code = c.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) {
				log.info("Google Routes HTTP " + code + ": " + readStream(c.getErrorStream()));
				return null;
			}
			return readStream(c.getInputStream());
		} finally {
			c.disconnect();
		}
	}

	/** Origin -> a point ~5 km ahead along the current bearing; Google snaps both to real roads. */
	private static String buildFreeDriveBody(double lat, double lon, float bearing, LatLon ahead) throws Exception {
		JSONObject request = new JSONObject();
		// The origin heading disambiguates carriageway snapping - without it a few metres of GPS
		// offset on a divided road can score the ONCOMING side's traffic (audit finding).
		request.put("origin", latLngWaypoint(lat, lon, false, normalizeHeading(bearing)));
		request.put("destination", latLngWaypoint(ahead.getLatitude(), ahead.getLongitude(), false, null));
		request.put("travelMode", "DRIVE");
		request.put("routingPreference", "TRAFFIC_AWARE");
		request.put("polylineQuality", "HIGH_QUALITY");
		JSONArray extra = new JSONArray();
		extra.put("TRAFFIC_ON_POLYLINE");
		request.put("extraComputations", extra);
		return request.toString();
	}

	private static double remainingDistance(List<Location> remaining) {
		double total = 0;
		for (int i = 1; i < remaining.size(); i++) {
			Location a = remaining.get(i - 1);
			Location b = remaining.get(i);
			total += MapUtils.getDistance(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
		}
		return total;
	}

	private static String buildRequestBody(double lat, double lon, Float heading, List<Location> remaining,
	                                       double total, boolean spansPoll) throws Exception {
		JSONObject request = new JSONObject();
		request.put("origin", latLngWaypoint(lat, lon, false,
				heading != null ? normalizeHeading(heading) : null));
		Location dest = remaining.get(remaining.size() - 1);
		request.put("destination", latLngWaypoint(dest.getLatitude(), dest.getLongitude(), false, null));
		// Pin Google to OsmAnd's route with pass-through via-points sampled evenly by distance -
		// otherwise it scores its own preferred route and the spans would paint the wrong roads.
		int count = (int) Math.min(MAX_INTERMEDIATES, total / 1000);
		if (count > 0) {
			double step = total / (count + 1);
			JSONArray intermediates = new JSONArray();
			double acc = 0;
			double nextAt = step;
			for (int i = 1; i < remaining.size() - 1 && intermediates.length() < count; i++) {
				Location a = remaining.get(i - 1);
				Location b = remaining.get(i);
				acc += MapUtils.getDistance(a.getLatitude(), a.getLongitude(), b.getLatitude(), b.getLongitude());
				if (acc >= nextAt) {
					intermediates.put(latLngWaypoint(b.getLatitude(), b.getLongitude(), true, null));
					nextAt += step;
				}
			}
			if (intermediates.length() > 0) {
				request.put("intermediates", intermediates);
			}
		}
		request.put("travelMode", "DRIVE");
		request.put("routingPreference", "TRAFFIC_AWARE");
		if (spansPoll) {
			// TRAFFIC_ON_POLYLINE upgrades the request to the Enterprise SKU - only the
			// budget-limited span-polls pay it; delay-polls stay on the cheaper Pro SKU.
			request.put("polylineQuality", "HIGH_QUALITY");
			JSONArray extra = new JSONArray();
			extra.put("TRAFFIC_ON_POLYLINE");
			request.put("extraComputations", extra);
		}
		return request.toString();
	}

	private static JSONObject latLngWaypoint(double lat, double lon, boolean via, Integer heading) throws Exception {
		JSONObject latLng = new JSONObject();
		latLng.put("latitude", lat);
		latLng.put("longitude", lon);
		JSONObject location = new JSONObject();
		location.put("latLng", latLng);
		if (heading != null) {
			location.put("heading", heading); // snaps to the carriageway matching travel direction
		}
		JSONObject waypoint = new JSONObject();
		waypoint.put("location", location);
		if (via) {
			waypoint.put("via", true); // pass-through: no stop, no leg split
		}
		return waypoint;
	}

	private static int normalizeHeading(float bearing) {
		return ((Math.round(bearing) % 360) + 360) % 360;
	}

	private static void parseAndStore(OsmandApplication app, String response, int gen,
	                                  boolean spansPoll, boolean allowToast) throws Exception {
		JSONArray routes = new JSONObject(response).optJSONArray("routes");
		if (routes == null || routes.length() == 0) {
			return;
		}
		JSONObject route = routes.getJSONObject(0);
		int duration = parseDurationSeconds(route.optString("duration", ""));
		int staticDuration = parseDurationSeconds(route.optString("staticDuration", ""));
		int delay = Math.max(0, duration - staticDuration);

		List<LatLon> points = Collections.emptyList();
		JSONObject polyline = route.optJSONObject("polyline");
		if (polyline != null) {
			String encoded = polyline.optString("encodedPolyline", "");
			if (!Algorithms.isEmpty(encoded)) {
				points = GeoPolylineParserUtil.parse(encoded, GeoPolylineParserUtil.PRECISION_5);
			}
		}
		List<CongestionSpan> spans = parseSpans(route.optJSONObject("travelAdvisory"), points.size());

		long now = SystemClock.elapsedRealtime();
		int prevVersion;
		synchronized (GoogleTrafficHelper.class) {
			if (gen != generation) {
				return; // navigation stopped/changed while this request was in flight - result is dead
			}
			TrafficSnapshot prevSnap = snapshot;
			prevVersion = prevSnap != null ? prevSnap.version : -1;
			long spansTime = now;
			int version;
			if (spansPoll) {
				version = ++versionCounter;
			} else {
				// Cheap delay-poll: update the delay but carry the last span-poll's painted spans
				// forward untouched, keeping their original fetch time so they still age out - and
				// their version, so the GL layer doesn't rebuild identical lines every 3 minutes.
				TrafficSnapshot previous = snapshot;
				if (previous != null && now - previous.spansTimeMs <= SNAPSHOT_TTL_MS) {
					points = previous.points;
					spans = previous.spans;
					spansTime = previous.spansTimeMs;
					version = previous.version;
				} else {
					points = Collections.emptyList();
					spans = Collections.emptyList();
					version = ++versionCounter; // spans just dropped - repaint must clear them
				}
			}
			snapshot = new TrafficSnapshot(points, spans, delay, now, spansTime, version);
		}
		log.info("Google traffic (" + (spansPoll ? "spans" : "delay") + " poll): +" + delay
				+ "s delay, " + spans.size() + " congested span(s)");
		CairoDriveLog.log("TRAFFIC", (spansPoll ? "spans" : "delay") + "-poll ok: +" + delay
				+ " s live delay, " + spans.size() + " congested span(s) painted");

		// The delay toast talks about "your route" - only meaningful while navigating one.
		// lastToast == 0 means "never toasted" (uptime clock - see the lastCheck comment).
		if (allowToast && delay >= TOAST_MIN_DELAY_SEC
				&& (lastToast == 0 || now - lastToast > TOAST_REPEAT_MS)) {
			lastToast = now;
			int minutes = Math.round(delay / 60f);
			CairoDriveLog.log("NOTIFY", "traffic delay toast: +" + minutes + " min on your route");
			app.runInUIThread(() -> app.showShortToastMessage(R.string.cairo_traffic_delay, minutes));
		}
		// Only redraw when something visible changed - a delay-poll that carried identical spans
		// forward keeps its version, and a full-layer repaint every 3 min for nothing is wasted
		// CPU/battery on the legacy renderer (audit finding).
		TrafficSnapshot stored = snapshot;
		if (stored != null && stored.version != prevVersion) {
			app.runInUIThread(() -> refreshMapIfReady(app));
		}
		// Traffic-aware routing: a big delay on the navigated route triggers one background
		// detour evaluation (offline route around the jam, live-scored before any switch).
		if (allowToast) {
			TrafficDetourHelper.onTrafficUpdate(app, delay);
		}
	}

	private static List<CongestionSpan> parseSpans(JSONObject travelAdvisory, int pointCount) {
		List<CongestionSpan> spans = new ArrayList<>();
		if (travelAdvisory == null || pointCount < 2) {
			return spans;
		}
		JSONArray intervals = travelAdvisory.optJSONArray("speedReadingIntervals");
		if (intervals == null) {
			return spans;
		}
		for (int i = 0; i < intervals.length(); i++) {
			JSONObject interval = intervals.optJSONObject(i);
			if (interval == null) {
				continue;
			}
			String speed = interval.optString("speed", "");
			boolean jam = "TRAFFIC_JAM".equals(speed);
			if (!jam && !"SLOW".equals(speed)) {
				continue; // NORMAL / unknown -> nothing to paint
			}
			// Proto3 JSON omits zero-valued fields, so a missing start index means 0.
			int start = Math.max(0, interval.optInt("startPolylinePointIndex", 0));
			int end = Math.min(pointCount - 1, interval.optInt("endPolylinePointIndex", -1));
			if (end <= start) {
				continue;
			}
			CongestionSpan last = spans.isEmpty() ? null : spans.get(spans.size() - 1);
			if (last != null && last.jam == jam && start <= last.end) {
				spans.set(spans.size() - 1, new CongestionSpan(last.start, Math.max(last.end, end), jam));
			} else {
				spans.add(new CongestionSpan(start, end, jam));
			}
		}
		return spans;
	}

	/** Google returns proto Durations as JSON strings like "2112s" or "3.5s". */
	private static int parseDurationSeconds(String duration) {
		if (Algorithms.isEmpty(duration)) {
			return 0;
		}
		try {
			String number = duration.endsWith("s") ? duration.substring(0, duration.length() - 1) : duration;
			return (int) Math.round(Double.parseDouble(number));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	/**
	 * SHA-1 of our own signing certificate, uppercase hex without separators - the format the
	 * Cloud Console's Android restriction expects in X-Android-Cert. Computed once per process.
	 */
	@SuppressWarnings("deprecation")
	public static String getSigningCertSha1(OsmandApplication app) {
		String cached = cachedCertSha1;
		if (cached != null) {
			return cached;
		}
		String hex = "";
		try {
			PackageManager pm = app.getPackageManager();
			byte[] cert = null;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				PackageInfo info = pm.getPackageInfo(app.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
				if (info.signingInfo != null) {
					Signature[] signatures = info.signingInfo.getApkContentsSigners();
					if (signatures != null && signatures.length > 0) {
						cert = signatures[0].toByteArray();
					}
				}
			} else {
				PackageInfo info = pm.getPackageInfo(app.getPackageName(), PackageManager.GET_SIGNATURES);
				if (info.signatures != null && info.signatures.length > 0) {
					cert = info.signatures[0].toByteArray();
				}
			}
			if (cert != null) {
				byte[] digest = MessageDigest.getInstance("SHA-1").digest(cert);
				StringBuilder sb = new StringBuilder(digest.length * 2);
				for (byte b : digest) {
					sb.append(String.format(Locale.US, "%02X", b));
				}
				hex = sb.toString();
			}
		} catch (Exception e) {
			log.error("Signing cert SHA-1 failed", e);
		}
		cachedCertSha1 = hex;
		return hex;
	}

	private static String readStream(InputStream stream) throws Exception {
		if (stream == null) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		try (BufferedReader r = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = r.readLine()) != null) {
				sb.append(line);
			}
		}
		return sb.toString();
	}
}
