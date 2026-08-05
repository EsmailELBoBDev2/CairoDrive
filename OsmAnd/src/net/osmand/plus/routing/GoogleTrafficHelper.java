package net.osmand.plus.routing;

import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.osm.io.NetworkUtils;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.plus.Version;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.util.Algorithms;
import net.osmand.util.GeoPolylineParserUtil;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Live traffic congestion on the ACTIVE ROUTE, from Google's Routes API.
 *
 * <h3>Why this is not a tile layer</h3>
 *
 * Every ordinary traffic overlay is raster tiles - request {@code .../{z}/{x}/{y}.png}, draw them.
 * Google has the best traffic data in Cairo by a distance and will not give you traffic tiles to
 * put on someone else's basemap. So this stops thinking "map layer" and thinks "the route I am
 * driving" instead: {@code computeRoutes} will, for a given path, return how long it takes now
 * versus free-flow, and a list of spans marked SLOW or TRAFFIC_JAM. That is not a tile, but it is
 * exactly what a driver wants - "this stretch ahead is red, and you are +6 min". OsmAnd still draws
 * the map; Google only annotates the line already being followed.
 *
 * <h3>The billing squeeze, which is most of the design pressure</h3>
 *
 * {@code computeRoutes} bills by what the {@code X-Goog-FieldMask} asks for:
 * <ul>
 *   <li>congestion spans ({@code TRAFFIC_ON_POLYLINE} + {@code speedReadingIntervals}) bills the
 *       Enterprise SKU - about 1,000 free calls a month;</li>
 *   <li>durations only bills the Pro SKU - about 5,000 free a month.</li>
 * </ul>
 *
 * The colours do not need refreshing as often as the delay number does, and cost five times more,
 * so the two run on INDEPENDENT {@link net.osmand.plus.cairodrive.providers.BudgetPacer} ladders
 * rather than an interleave - see {@link #claimRequestTier} for why the interleave was wrong. Each
 * ladder is solved backwards from a floor interval it may never degrade past ({@link
 * #SPANS_LADDER} 65 min, {@link #DELAY_LADDER} 16 min) so a 24-hour drive ends on data that is
 * still worth having rather than merely still arriving. Daily caps of {@value #SPANS_DAILY_CAP} and
 * {@value #DELAY_DAILY_CAP} make a maxed 31-day month arithmetically free:
 * 32x31 = 992 <= 1000 and 160x31 = 4960 <= 5000. The caps are persisted settings, so killing the
 * app does not reset the budget.
 *
 * <h3>Safety</h3>
 *
 * Four independent guarantees stack. Two hard gates (a compiled-in key AND an off-by-default
 * toggle) mean the common case costs literally nothing. The persisted caps keep both SKUs inside
 * their free allowance. Every network path swallows its own exceptions and returns, so nothing
 * here can touch navigation. And {@link #generation} plus the snapshot TTL mean stale or dead data
 * can never paint.
 *
 * <p>The standing caveat is legal rather than technical: showing Google's data over an OSM basemap
 * is ToS-grey. That is why it ships OFF and is a deliberate private-build opt-in.
 */
public class GoogleTrafficHelper {

	private static final Log LOG = PlatformUtil.getLog(GoogleTrafficHelper.class);
	private static final String TRACE_TAG = "CD_GTRAFFIC";

	private static final String ROUTES_API =
			"https://routes.googleapis.com/directions/v2:computeRoutes";

	/** Enterprise SKU: asks for the polyline and the congestion intervals. */
	private static final String FIELD_MASK_SPANS =
			"routes.duration,routes.staticDuration,routes.polyline.encodedPolyline,"
					+ "routes.travelAdvisory.speedReadingIntervals";
	/** Pro SKU: durations only. Must NOT mention the polyline or travelAdvisory. */
	private static final String FIELD_MASK_DELAY = "routes.duration,routes.staticDuration";

	private static final long CHECK_INTERVAL_MS = 60 * 1000L;

	/**
	 * Spans ladder - the Enterprise SKU, 1000/month, 32/day, <b>65-minute floor</b>, 25.7 hours.
	 *
	 * <p>Rung one is 5 minutes, the fast end of Google's own 5-10 minute traffic-layer refresh:
	 * fresh as the data allows and no faster, because past that these are the most expensive
	 * requests in the app buying identical bytes.
	 *
	 * <h3>This is the one stream whose floor is set by arithmetic, not by judgement</h3>
	 *
	 * 32 requests across 1440 minutes is 45 minutes flat with no burst whatsoever. Wanting any fast
	 * opening at all - 30 minutes of 5-minute polls and 80 more at 20 - spends 10 of the 32 and
	 * leaves 22 to cover the remaining 22 hours, which is 65 minutes and there is no arrangement of
	 * this budget that does better. The previous ladder reached hour 24 at 120-minute polls; 65 is
	 * the honest floor, not a good one.
	 *
	 * <p>Two things make that acceptable rather than merely unavoidable. Spans carry DETAIL - which
	 * stretch is red - while the delay number carries freshness on the Pro SKU at a 16-minute floor
	 * for a fifth of the price, so the number stays current even when the colours are an hour old.
	 * And {@link #spansPaintTtlMs()} tracks this ladder, so the overlay shows the hour-old colours
	 * instead of blanking; a fixed TTL against this floor would have hidden them 85% of the time.
	 */
	private static final net.osmand.plus.cairodrive.providers.BudgetPacer.Tier[] SPANS_LADDER = {
			new net.osmand.plus.cairodrive.providers.BudgetPacer.Tier(0.1875, 1, 300),
			new net.osmand.plus.cairodrive.providers.BudgetPacer.Tier(0.1250, 1, 1200),
			new net.osmand.plus.cairodrive.providers.BudgetPacer.Tier(0.6875, 1, 3900),
	};

	/**
	 * Delay ladder - the Pro SKU, 5000/month, 161/day, <b>16-minute floor</b>, 25.2 hours.
	 *
	 * <p>Rung one is 1 minute, matching the 1-5 minute cadence Google's delay figure moves on.
	 * This is the cheap tier, so it carries the freshness while spans carry the detail - and that
	 * division is why the floors are allowed to differ by a factor of four. 84 of the 161 are
	 * reserved to hold 16 minutes from hour three to hour 24; the other 77 buy the opening.
	 */
	private static final net.osmand.plus.cairodrive.providers.BudgetPacer.Tier[] DELAY_LADDER = {
			new net.osmand.plus.cairodrive.providers.BudgetPacer.Tier(0.186, 1, 60),
			new net.osmand.plus.cairodrive.providers.BudgetPacer.Tier(0.155, 1, 120),
			new net.osmand.plus.cairodrive.providers.BudgetPacer.Tier(0.137, 1, 240),
			new net.osmand.plus.cairodrive.providers.BudgetPacer.Tier(0.522, 1, 960),
	};

	private static volatile long lastDelayAtMs;

	private static volatile long lastSpansAtMs;
	private static final long REROUTE_DEBOUNCE_MS = 60 * 1000L;
	/**
	 * How long spans may still steer a DECISION. Public so the router applies the same rule.
	 *
	 * <p>Deliberately NOT the same as {@link #spansPaintTtlMs()}, because the two consumers have
	 * opposite failure modes. Painting an hour-old colour is a cosmetic inaccuracy. Rerouting
	 * around an hour-old jam that has since cleared sends the car the long way round for nothing -
	 * the same argument that keeps {@code CairoDriveProviders.INCIDENTS_TTL_MS} short. So this
	 * stays at ten minutes regardless of how slowly the ladder is polling: when spans are older
	 * than this the router simply stops using them and falls back to TomTom flow, which still has
	 * its own 10-minute floor.
	 */
	public static final long SNAPSHOT_TTL_MS = 10 * 60 * 1000L;

	/**
	 * How long spans stay PAINTABLE - the ladder's current interval plus half, floored at
	 * {@link #SNAPSHOT_TTL_MS}.
	 *
	 * <p>A fixed ten minutes against a ladder whose floor is 65 would have blanked the overlay for
	 * 85% of a long drive: the budget would be spent exactly as designed and the user would see
	 * nothing. Tracking the tier means the colours persist until the poll that replaces them is
	 * actually due, which is the correct definition of stale for a display. The 1.5x allows one
	 * missed poll - a tunnel, a dropped request - without a blink.
	 *
	 * <p>It follows the tier rather than a constant so that retuning {@link #SPANS_LADDER} cannot
	 * leave this silently wrong, which is the failure this method exists to prevent.
	 *
	 * <p>Reads a volatile that {@link #claimRequestTier} already computed rather than going to
	 * settings. This is called from the draw path: a preference read per frame is exactly the kind
	 * of cost that turns up later as {@code over} in CD_FRAME.
	 */
	public static long spansPaintTtlMs() {
		long interval = lastSpansIntervalMs;
		return Math.max(SNAPSHOT_TTL_MS, interval + interval / 2);
	}

	/**
	 * The spans interval currently in force, republished by the accountant for
	 * {@link #spansPaintTtlMs()}.
	 *
	 * <p>Starts at the ladder's FLOOR rather than its first rung. The value is only ever used to
	 * decide how long to keep showing something, so before the first poll of a day the safe
	 * direction is the longest interval, not the shortest - starting at 5 minutes would hide
	 * carried-over spans in the window before the accountant has run.
	 */
	private static volatile long lastSpansIntervalMs =
			net.osmand.plus.cairodrive.providers.BudgetPacer.floorIntervalMs(SPANS_LADDER);
	private static final long TOAST_REPEAT_MS = 10 * 60 * 1000L;
	private static final int TOAST_MIN_DELAY_SEC = 300;
	/** computeRoutes caps intermediates at 25; 20 leaves headroom. */
	private static final int MAX_INTERMEDIATES = 20;
	private static final int MIN_REMAINING_M = 1000;
	private static final int SPANS_DAILY_CAP = 32;
	private static final int DELAY_DAILY_CAP = 161;

	private static final int TIER_NONE = 0;
	private static final int TIER_DELAY = 1;
	private static final int TIER_SPANS = 2;

	private static final int CONNECT_TIMEOUT_MS = 8000;
	private static final int READ_TIMEOUT_MS = 12000;

	private GoogleTrafficHelper() {
	}

	// ------------------------------------------------------------------ model

	/** One SLOW or JAM stretch, as inclusive indices into {@link TrafficSnapshot#points}. */
	public static class CongestionSpan {
		public final int start;
		public final int end;
		/** true = TRAFFIC_JAM (red), false = SLOW (orange). */
		public final boolean jam;

		CongestionSpan(int start, int end, boolean jam) {
			this.start = start;
			this.end = end;
			this.jam = jam;
		}
	}

	/**
	 * Immutable, and GEO-ANCHORED rather than index-anchored.
	 *
	 * <p>It stores real {@link LatLon}s, not offsets into the current route, so it stays meaningful
	 * across a reroute until the next poll replaces it. Indices into a route that has since been
	 * recalculated would paint somewhere arbitrary.
	 */
	public static class TrafficSnapshot {
		public final List<LatLon> points;
		public final List<CongestionSpan> spans;
		public final int delaySeconds;
		public final long timeMs;
		/**
		 * When the SPANS were actually fetched, which is not the same as when this snapshot was
		 * built. A cheap delay poll carries the previous spans forward unchanged, so expiry has to
		 * run on this and not on {@link #timeMs} - otherwise a delay poll would silently "refresh"
		 * colours it never re-fetched and stale paint would linger indefinitely.
		 */
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

	// ------------------------------------------------------------------ state
	// Touched from the GPS thread, the fetch thread and the draw thread.

	private static volatile TrafficSnapshot snapshot;
	private static volatile long lastCheck;
	private static volatile long lastToast;
	private static volatile boolean inFlight;
	private static volatile int generation;
	private static volatile boolean budgetExhaustedLogged;
	private static int versionCounter;
	private static volatile String signingCert;

	@Nullable
	public static TrafficSnapshot getSnapshot() {
		return snapshot;
	}

	// ------------------------------------------------------------------ entry point

	/**
	 * Called on every GPS fix. A gauntlet of cheap bail-outs first, so the common case - feature
	 * off - costs almost nothing.
	 */
	public static void onLocationUpdate(@Nullable RoutingHelper helper, @Nullable Location loc) {
		if (helper == null || loc == null) {
			return;
		}
		OsmandApplication app = helper.getApplication();
		if (app == null) {
			return;
		}
		try {
			OsmandSettings settings = app.getSettings();
			if (!settings.GOOGLE_TRAFFIC_ON_ROUTE.get() || Algorithms.isEmpty(apiKey(app))) {
				return;
			}
			if (!helper.isFollowingMode() || !settings.isInternetConnectionAvailable()) {
				return;
			}
			ApplicationMode mode = settings.getApplicationMode();
			if (mode == null || !mode.isDerivedRoutingFrom(ApplicationMode.CAR)) {
				return;
			}
			long now = System.currentTimeMillis();
			if (inFlight || now - lastCheck < CHECK_INTERVAL_MS) {
				return;
			}
			// Copied on THIS thread: getRouteLocations() is a live sublist view over the route and
			// mutates as the car moves. Handing the live list to a background thread would let the
			// geometry change underneath the request.
			RouteCalculationResult route = helper.getRoute();
			if (route == null) {
				return;
			}
			List<Location> remaining = new ArrayList<>(route.getRouteLocations());
			if (remaining.size() < 2) {
				return;
			}

			int tier;
			synchronized (GoogleTrafficHelper.class) {
				// Re-checked under the lock: two fixes milliseconds apart can both pass the test
				// above, and each would claim a billed request.
				if (inFlight || now - lastCheck < CHECK_INTERVAL_MS) {
					return;
				}
				lastCheck = now;
				tier = claimRequestTier(app);
				if (tier == TIER_NONE) {
					return;
				}
				inFlight = true;
			}

			int gen = generation;
			double lat = loc.getLatitude();
			double lon = loc.getLongitude();
			boolean spansPoll = tier == TIER_SPANS;
			Thread worker = new Thread(() -> {
				try {
					Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
					fetchTraffic(app, lat, lon, remaining, gen, spansPoll);
				} catch (Throwable t) {
					LOG.info(TRACE_TAG + " fetch failed", t);
				} finally {
					// In a finally so a thrown exception can never wedge the feature off forever.
					inFlight = false;
				}
			}, "google-traffic");
			worker.setPriority(Thread.MIN_PRIORITY);
			worker.start();
		} catch (Throwable t) {
			LOG.info(TRACE_TAG + " onLocationUpdate failed", t);
			inFlight = false;
		}
	}

	/**
	 * The budget accountant. MUST be called while holding the class lock.
	 *
	 * <p>Counters live in settings rather than memory precisely so the budget survives a restart -
	 * you cannot reset the bill by killing the app.
	 */
	private static int claimRequestTier(@NonNull OsmandApplication app) {
		OsmandSettings settings = app.getSettings();
		int today = (int) (System.currentTimeMillis() / 86_400_000L);
		if (settings.GOOGLE_TRAFFIC_REQUEST_DAY.get() != today) {
			settings.GOOGLE_TRAFFIC_REQUEST_DAY.set(today);
			settings.GOOGLE_TRAFFIC_REQUEST_COUNT.set(0);
			settings.GOOGLE_TRAFFIC_DELAY_REQUEST_COUNT.set(0);
			budgetExhaustedLogged = false;
		}
		int spansUsed = settings.GOOGLE_TRAFFIC_REQUEST_COUNT.get();
		int delayUsed = settings.GOOGLE_TRAFFIC_DELAY_REQUEST_COUNT.get();

		boolean delayPoolGone = delayUsed >= DELAY_DAILY_CAP;
		// Spans get their OWN interval rather than an every-other-poll interleave.
		//
		// The interleave tied the two tiers together: making delay polls faster automatically made
		// span polls faster, and spans are the Enterprise SKU with a 1000/month allowance. At a
		// 60 s base check the interleave would have spent the whole daily span budget in 32
		// minutes of driving, and spent it re-fetching a layer that only refreshes every 5-10
		// minutes - paying the most expensive SKU in the app for identical bytes.
		//
		// Decoupled: delay runs at the base interval because its data moves on a 1-5 minute
		// cadence, spans run at their own because theirs moves on a 5-10 minute one.
		//
		// Both intervals now come from a BudgetPacer ladder rather than a constant, so each tier
		// stretches as its own pool is consumed and the day's budget lasts a 24-hour drive instead
		// of running out partway through one. A 45-minute drive never leaves the first two rungs of
		// either ladder, so the common case is unchanged.
		long now = System.currentTimeMillis();
		long spansInterval = net.osmand.plus.cairodrive.providers.BudgetPacer
				.tierFor(spansUsed, SPANS_DAILY_CAP, SPANS_LADDER).intervalMs;
		// Republished for spansPaintTtlMs(), which must widen as this widens or the overlay blanks
		// between polls late in a drive.
		lastSpansIntervalMs = spansInterval;
		boolean spansDue = now - lastSpansAtMs >= spansInterval;
		if (spansUsed < SPANS_DAILY_CAP && (spansDue || delayPoolGone)) {
			lastSpansAtMs = System.currentTimeMillis();
			settings.GOOGLE_TRAFFIC_REQUEST_COUNT.set(spansUsed + 1);
			return TIER_SPANS;
		}
		long delayInterval = net.osmand.plus.cairodrive.providers.BudgetPacer
				.tierFor(delayUsed, DELAY_DAILY_CAP, DELAY_LADDER).intervalMs;
		if (!delayPoolGone && now - lastDelayAtMs >= delayInterval) {
			lastDelayAtMs = now;
			settings.GOOGLE_TRAFFIC_DELAY_REQUEST_COUNT.set(delayUsed + 1);
			if (CairoDriveLogger.isEnabled()) {
				CairoDriveLogger.getInstance().log(TRACE_TAG, "pace delay "
						+ net.osmand.plus.cairodrive.providers.BudgetPacer
						.describe(delayUsed, DELAY_DAILY_CAP, DELAY_LADDER)
						+ " spans " + net.osmand.plus.cairodrive.providers.BudgetPacer
						.describe(spansUsed, SPANS_DAILY_CAP, SPANS_LADDER));
			}
			return TIER_DELAY;
		}
		// Only EXHAUSTION gets the loud line. Reaching here now has two quite different causes:
		// the day's budget is gone, or a ladder interval simply has not elapsed - and since the
		// pacer stretches those intervals to hours late in a long drive, "not due yet" is the
		// common case. Logging it as "budget spent" would report the feature dead every minute
		// while it was working exactly as designed.
		boolean exhausted = spansUsed >= SPANS_DAILY_CAP && delayPoolGone;
		if (exhausted && !budgetExhaustedLogged) {
			budgetExhaustedLogged = true;
			LOG.info(TRACE_TAG + " daily budget spent (spans=" + spansUsed
					+ "/" + SPANS_DAILY_CAP + " delay=" + delayUsed + "/" + DELAY_DAILY_CAP
					+ ") - no further polls until the UTC day rolls");
			if (CairoDriveLogger.isEnabled()) {
				CairoDriveLogger.getInstance().log(TRACE_TAG, "budgetSpent"
						+ " spans=" + spansUsed + "/" + SPANS_DAILY_CAP
						+ " delay=" + delayUsed + "/" + DELAY_DAILY_CAP);
			}
		}
		// lastCheck was already advanced, so this retries on the next CHECK_INTERVAL_MS tick.
		return TIER_NONE;
	}

	/**
	 * A freshly recalculated route should be scored soon, but not instantly - the GPS chaos around
	 * a reroute would otherwise fire a request per fix.
	 */
	public static void onNewRoute() {
		long target = System.currentTimeMillis() - CHECK_INTERVAL_MS + REROUTE_DEBOUNCE_MS;
		if (lastCheck > target) {
			lastCheck = target;
		}
	}

	/** Navigation stopped. Bumping the generation orphans any fetch still in flight. */
	public static void reset(@Nullable OsmandApplication app) {
		synchronized (GoogleTrafficHelper.class) {
			generation++;
			snapshot = null;
		}
		lastCheck = 0;
		lastToast = 0;
		refreshMap(app);
	}

	// ------------------------------------------------------------------ network

	private static void fetchTraffic(@NonNull OsmandApplication app, double lat, double lon,
	                                 @NonNull List<Location> remaining, int gen, boolean spansPoll) {
		String body = buildRequestBody(lat, lon, remaining, spansPoll);
		if (body == null) {
			return;
		}
		HttpURLConnection connection = null;
		try {
			connection = NetworkUtils.getHttpURLConnection(ROUTES_API);
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			connection.setRequestProperty("Accept", "application/json");
			connection.setRequestProperty("X-Goog-Api-Key", apiKey(app));
			connection.setRequestProperty("X-Goog-FieldMask",
					spansPoll ? FIELD_MASK_SPANS : FIELD_MASK_DELAY);
			connection.setRequestProperty("User-Agent", Version.getFullVersion(app));
			// An Android-restricted key is normally proven by the Maps SDK. A plain REST call has
			// to present the same two headers itself or the request is rejected with
			// API_KEY_ANDROID_APP_BLOCKED however the key is configured.
			String cert = getSigningCertSha1(app);
			if (cert != null) {
				connection.setRequestProperty("X-Android-Package", app.getPackageName());
				connection.setRequestProperty("X-Android-Cert", cert);
			}
			byte[] payload = body.getBytes(StandardCharsets.UTF_8);
			connection.setFixedLengthStreamingMode(payload.length);
			try (OutputStream out = connection.getOutputStream()) {
				out.write(payload);
			}
			int code = connection.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) {
				LOG.info(TRACE_TAG + " HTTP " + code + " " + read(connection.getErrorStream()));
				return;
			}
			parseAndStore(app, read(connection.getInputStream()), gen, spansPoll);
		} catch (Throwable t) {
			LOG.info(TRACE_TAG + " request failed", t);
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	/**
	 * Pins Google to OsmAnd's route.
	 *
	 * <p>Sending only origin and destination would make Google compute its OWN preferred route and
	 * return spans for roads the driver is not on - the colours would paint the wrong streets,
	 * convincingly. Sampling pass-through waypoints along the real geometry forces it to score this
	 * path. {@code via: true} means no stop and no leg split.
	 */
	@Nullable
	private static String buildRequestBody(double lat, double lon, @NonNull List<Location> remaining,
	                                       boolean spansPoll) {
		try {
			double total = 0;
			for (int i = 1; i < remaining.size(); i++) {
				total += MapUtils.getDistance(
						remaining.get(i - 1).getLatitude(), remaining.get(i - 1).getLongitude(),
						remaining.get(i).getLatitude(), remaining.get(i).getLongitude());
			}
			// Arrival is imminent; not worth a billed request.
			if (total < MIN_REMAINING_M) {
				return null;
			}
			Location last = remaining.get(remaining.size() - 1);

			JSONObject root = new JSONObject();
			root.put("origin", waypoint(lat, lon, false));
			root.put("destination", waypoint(last.getLatitude(), last.getLongitude(), false));

			int wanted = (int) Math.min(MAX_INTERMEDIATES, total / 1000);
			if (wanted > 0) {
				JSONArray intermediates = new JSONArray();
				double step = total / (wanted + 1);
				double walked = 0;
				double nextAt = step;
				for (int i = 1; i < remaining.size() && intermediates.length() < wanted; i++) {
					Location a = remaining.get(i - 1);
					Location b = remaining.get(i);
					walked += MapUtils.getDistance(a.getLatitude(), a.getLongitude(),
							b.getLatitude(), b.getLongitude());
					if (walked >= nextAt) {
						intermediates.put(waypoint(b.getLatitude(), b.getLongitude(), true));
						nextAt += step;
					}
				}
				if (intermediates.length() > 0) {
					root.put("intermediates", intermediates);
				}
			}
			root.put("travelMode", "DRIVE");
			root.put("routingPreference", "TRAFFIC_AWARE");
			if (spansPoll) {
				// These two are exactly what tips the call into the Enterprise SKU. A delay poll
				// must never include them.
				root.put("polylineQuality", "HIGH_QUALITY");
				root.put("extraComputations", new JSONArray().put("TRAFFIC_ON_POLYLINE"));
			}
			return root.toString();
		} catch (Throwable t) {
			LOG.info(TRACE_TAG + " could not build request", t);
			return null;
		}
	}

	private static JSONObject waypoint(double lat, double lon, boolean via) throws Exception {
		JSONObject latLng = new JSONObject();
		latLng.put("latitude", lat);
		latLng.put("longitude", lon);
		JSONObject location = new JSONObject();
		location.put("latLng", latLng);
		JSONObject waypoint = new JSONObject();
		waypoint.put("location", location);
		if (via) {
			waypoint.put("via", true);
		}
		return waypoint;
	}

	// ------------------------------------------------------------------ parsing

	private static void parseAndStore(@NonNull OsmandApplication app, @NonNull String response,
	                                  int gen, boolean spansPoll) {
		try {
			JSONArray routes = new JSONObject(response).optJSONArray("routes");
			if (routes == null || routes.length() == 0) {
				return;
			}
			JSONObject route = routes.getJSONObject(0);
			int duration = seconds(route.optString("duration", null));
			int staticDuration = seconds(route.optString("staticDuration", null));
			int delay = Math.max(0, duration - staticDuration);

			List<LatLon> points = Collections.emptyList();
			List<CongestionSpan> spans = Collections.emptyList();
			if (spansPoll) {
				JSONObject polyline = route.optJSONObject("polyline");
				String encoded = polyline != null ? polyline.optString("encodedPolyline", null) : null;
				if (!Algorithms.isEmpty(encoded)) {
					points = GeoPolylineParserUtil.parse(encoded, GeoPolylineParserUtil.PRECISION_5);
				}
				if (points == null) {
					points = Collections.emptyList();
				}
				spans = parseSpans(route.optJSONObject("travelAdvisory"), points.size());
			}

			long now = System.currentTimeMillis();
			synchronized (GoogleTrafficHelper.class) {
				// The route this was requested for is gone; storing would resurrect dead traffic.
				if (gen != generation) {
					return;
				}
				long spansTime = now;
				if (!spansPoll) {
					TrafficSnapshot prev = snapshot;
					// The PAINT ttl, not the decision one. A delay poll rebuilds the snapshot, so a
					// 10-minute rule here would have thrown the spans away on nearly every delay
					// poll once the spans ladder reached its 65-minute floor - deleting data the
					// Enterprise SKU had just been billed for. The original spansTimeMs is carried
					// with them, so the router still applies its own 10-minute gate downstream and
					// nothing stale reaches a reroute decision.
					if (prev != null && now - prev.spansTimeMs <= spansPaintTtlMs()) {
						points = prev.points;
						spans = prev.spans;
						spansTime = prev.spansTimeMs;
					} else {
						points = Collections.emptyList();
						spans = Collections.emptyList();
						spansTime = 0;
					}
				}
				snapshot = new TrafficSnapshot(points, spans, delay, now, spansTime, ++versionCounter);
			}
			LOG.info(TRACE_TAG + " Google traffic (" + (spansPoll ? "spans" : "delay")
					+ " poll): +" + delay + "s delay, " + spans.size() + " congested span(s)");

			if (delay >= TOAST_MIN_DELAY_SEC && now - lastToast > TOAST_REPEAT_MS) {
				lastToast = now;
				int minutes = Math.round(delay / 60f);
				app.runInUIThread(() -> app.showToastMessage(
						app.getString(R.string.cairo_traffic_delay, minutes)));
			}
			refreshMap(app);
		} catch (Throwable t) {
			LOG.info(TRACE_TAG + " parse failed", t);
		}
	}

	/** Durations arrive as proto strings such as {@code "2112s"}. */
	private static int seconds(@Nullable String value) {
		if (Algorithms.isEmpty(value)) {
			return 0;
		}
		String trimmed = value.endsWith("s") ? value.substring(0, value.length() - 1) : value;
		try {
			return (int) Math.round(Double.parseDouble(trimmed));
		} catch (NumberFormatException e) {
			return 0;
		}
	}

	@NonNull
	private static List<CongestionSpan> parseSpans(@Nullable JSONObject travelAdvisory, int pointCount) {
		List<CongestionSpan> result = new ArrayList<>();
		if (travelAdvisory == null || pointCount < 2) {
			return result;
		}
		JSONArray intervals = travelAdvisory.optJSONArray("speedReadingIntervals");
		if (intervals == null) {
			return result;
		}
		for (int i = 0; i < intervals.length(); i++) {
			JSONObject interval = intervals.optJSONObject(i);
			if (interval == null) {
				continue;
			}
			String speed = interval.optString("speed", "");
			boolean jam = "TRAFFIC_JAM".equals(speed);
			if (!jam && !"SLOW".equals(speed)) {
				continue; // NORMAL, or a value this build does not know about
			}
			// proto3 omits zero-valued fields, so an absent start index means 0 rather than
			// "missing" - defaulting it to -1 would silently drop every span that begins at the
			// route's origin, which is the one the driver is about to enter.
			int start = Math.max(0, interval.optInt("startPolylinePointIndex", 0));
			int end = Math.min(pointCount - 1, interval.optInt("endPolylinePointIndex", -1));
			if (end <= start) {
				continue;
			}
			CongestionSpan previous = result.isEmpty() ? null : result.get(result.size() - 1);
			if (previous != null && previous.jam == jam && start <= previous.end + 1) {
				result.set(result.size() - 1,
						new CongestionSpan(previous.start, Math.max(previous.end, end), jam));
			} else {
				result.add(new CongestionSpan(start, end, jam));
			}
		}
		return result;
	}

	// ------------------------------------------------------------------ plumbing

	/**
	 * The Routes key, which DEFAULTS to the Places key in cairodrive.gradle.
	 *
	 * <p>One Google Cloud key with both APIs enabled serves both, and that is what this project
	 * actually has - so a separate secret, a separate string resource and a CI substitution step
	 * were three moving parts buying nothing. They are gone. {@code CAIRODRIVE_ROUTES_KEY} still
	 * overrides at build time if the two ever need splitting, which is the only way to revoke
	 * traffic without also killing search.
	 */
	@NonNull
	private static String apiKey(@NonNull OsmandApplication app) {
		String key = BuildConfig.CAIRODRIVE_ROUTES_KEY;
		return key == null ? "" : key;
	}

	/**
	 * SHA-1 of this app's own signing certificate, uppercase hex with no separators - the exact
	 * format the Cloud Console Android restriction compares against. Computed once.
	 */
	@Nullable
	private static String getSigningCertSha1(@NonNull OsmandApplication app) {
		String cached = signingCert;
		if (cached != null) {
			return cached.isEmpty() ? null : cached;
		}
		String computed = "";
		try {
			PackageManager manager = app.getPackageManager();
			Signature[] signatures;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				SigningInfo info = manager.getPackageInfo(app.getPackageName(),
						PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
				signatures = info.hasMultipleSigners()
						? info.getApkContentsSigners() : info.getSigningCertificateHistory();
			} else {
				signatures = manager.getPackageInfo(app.getPackageName(),
						PackageManager.GET_SIGNATURES).signatures;
			}
			if (signatures != null && signatures.length > 0) {
				byte[] digest = MessageDigest.getInstance("SHA1").digest(signatures[0].toByteArray());
				StringBuilder hex = new StringBuilder(digest.length * 2);
				for (byte b : digest) {
					hex.append(String.format("%02X", b));
				}
				computed = hex.toString();
			}
		} catch (Throwable t) {
			LOG.info(TRACE_TAG + " signing certificate unavailable", t);
		}
		signingCert = computed;
		return computed.isEmpty() ? null : computed;
	}

	private static void refreshMap(@Nullable OsmandApplication app) {
		if (app == null) {
			return;
		}
		try {
			app.runInUIThread(() -> {
				if (app.getOsmandMap() != null) {
					app.getOsmandMap().refreshMap();
				}
			});
		} catch (Throwable ignored) {
		}
	}

	@NonNull
	private static String read(@Nullable InputStream stream) {
		if (stream == null) {
			return "";
		}
		try (InputStream in = stream) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[4096];
			int n;
			while ((n = in.read(chunk)) > 0) {
				buffer.write(chunk, 0, n);
			}
			return buffer.toString("UTF-8");
		} catch (Throwable t) {
			return "";
		}
	}
}
