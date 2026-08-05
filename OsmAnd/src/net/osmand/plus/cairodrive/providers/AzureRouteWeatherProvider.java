package net.osmand.plus.cairodrive.providers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.osm.io.NetworkUtils;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.helpers.CairoDriveLog;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * Weather at the points the car will actually reach, and WHEN it will reach them.
 *
 * <h3>The one thing here that nothing else can do</h3>
 *
 * Every other weather source in this app answers "what is it like where you are, now".
 * OpenWeather is polled at the current location; that is the right shape for the dust banner and
 * the wrong shape for a drive. Azure's Weather Along Route takes up to <b>60 waypoints, each with
 * an ETA in minutes and a heading</b>, and answers for each one at the time of arrival. On a
 * Cairo-to-Alexandria desert road run that is the difference between "clear here" and "a
 * dust front will be across the road in forty minutes".
 *
 * <p>Verified online, August 2026: {@code GET atlas.microsoft.com/weather/route/json},
 * api-version 1.1, query is {@code lat,lon,eta,heading} tuples separated by colons, 60 waypoints
 * maximum, routes must complete within two hours of now, JSON only, data refreshed every five
 * minutes. The response carries a per-waypoint {@code hazards.hazardIndex} (0-4), precipitation,
 * lightning counts, and a {@code sunGlare} object with {@code glareIndex}.
 *
 * <h3>Why the billing changed the answer</h3>
 *
 * The provider audit dropped Azure Maps partly on cost, sizing it against a 5,000/month
 * allowance. That figure was wrong: Gen2 carries a shared free tier of <b>250,000 transactions per
 * month</b>. At one request per route this is not a constraint in any realistic use, and the
 * feature is genuinely unavailable elsewhere - so the drop no longer follows from its own premise.
 *
 * <p>What has NOT changed: Gen1 retires <b>15 September 2026</b> with automatic migration to Gen2
 * at roughly 5-9x the per-transaction rate, and Azure bills through a subscription rather than a
 * card-free freemium. Both are reasons the daily cap below is small and hard rather than generous.
 *
 * <h3>Why the sun glare here does not replace the local one</h3>
 *
 * {@link SunGlareProvider} computes glare on-device from solar geometry, costs nothing, and works
 * offline. It stays the source. Azure's {@code glareIndex} is used only as a CROSS-CHECK written
 * to the drive log: it is computed by someone else from the same inputs, so a persistent
 * disagreement means the local model has a bug worth finding. Paying an API for a number you can
 * already compute would be indefensible; using it to audit that computation, a few times a drive,
 * is not.
 */
public final class AzureRouteWeatherProvider {

	private static final Log LOG = PlatformUtil.getLog(AzureRouteWeatherProvider.class);
	private static final String TRACE_TAG = "CD_ROUTEWX";

	private static final String ROUTE_WEATHER_API = "https://atlas.microsoft.com/weather/route/json";
	/**
	 * The SAME subscription key, a different endpoint, and the same 250,000/month pool.
	 *
	 * <p>Checked online August 2026: global coverage, sourced from official government
	 * meteorological agencies and regional providers via AccuWeather, empty {@code results[]} when
	 * there is nothing. For Cairo that means a khamsin or sandstorm warning issued by the Egyptian
	 * Meteorological Authority - an OFFICIAL declaration, which is a categorically different kind
	 * of evidence from this app inferring dust from particulate ratios and a visibility reading.
	 *
	 * <p>Not fetching it while already holding the key would have been leaving the best dust
	 * signal in the entire stack on the table.
	 */
	private static final String SEVERE_ALERTS_API =
			"https://atlas.microsoft.com/weather/severe/alerts/json";
	private static final String API_VERSION = "1.1";

	private static final int CONNECT_TIMEOUT_MS = 8000;
	private static final int READ_TIMEOUT_MS = 12000;

	/** Hard API limit, not a choice. */
	private static final int MAX_WAYPOINTS = 60;

	/**
	 * Waypoints actually sent. Far below the limit because each one is a place a hazard could be
	 * reported and 10 across a Cairo drive is already one every few minutes - past that the answer
	 * repeats itself and the response gets big enough to parse on a thread that has better things
	 * to do.
	 */
	private static final int WAYPOINTS = 10;

	/**
	 * The API only answers for routes completing within two hours. A longer route is not an error
	 * - it is sampled across the first two hours instead, because that is the window it can
	 * actually speak about and a truncated answer beats a rejected request.
	 */
	private static final int HORIZON_MINUTES = 115;

	/**
	 * Small on purpose. One request covers a whole route, so a drive needs 1-3; this leaves room
	 * for a reroute or two and nothing more. Azure bills through a subscription, so an unbounded
	 * loop here has a bill at the end of it rather than a 429.
	 */
	private static final int DAILY_CAP = 12;

	/** A route is re-checked no more often than this. Azure refreshes the data every 5 minutes. */
	private static final long MIN_INTERVAL_MS = 10 * 60 * 1000L;

	/** hazardIndex is documented 0-4. At or above this it is worth a line in the log. */
	private static final int HAZARD_NOTABLE = 2;

	private static final String PREF_DAY = "cairodrive_azure_wx_day";
	private static final String PREF_COUNT = "cairodrive_azure_wx_count";

	private static volatile long lastPollMs;

	private AzureRouteWeatherProvider() {
	}

	public static boolean hasKey() {
		return !Algorithms.isEmpty(BuildConfig.CAIRODRIVE_AZURE_MAPS_KEY);
	}

	/**
	 * Location-callback entry point, navigation only.
	 *
	 * <p>Free driving is deliberately excluded, and this is the one provider here where that is
	 * right rather than lazy: the whole value is answering for a point at the TIME the car reaches
	 * it, and without a destination there is no such time. Sampling a box around the car would
	 * spend a request to learn what OpenWeather already says for free.
	 */
	public static void onLocationUpdate(@Nullable net.osmand.plus.routing.RoutingHelper helper,
	                                    @Nullable net.osmand.Location location) {
		if (helper == null || location == null || !hasKey()) {
			return;
		}
		OsmandApplication app = helper.getApplication();
		if (app == null || inFlight) {
			return;
		}
		long now = System.currentTimeMillis();
		boolean routeDue = lastPollMs == 0 || now - lastPollMs >= MIN_INTERVAL_MS;
		boolean alertsDue = lastAlertPollMs == 0 || now - lastAlertPollMs >= ALERT_INTERVAL_MS;
		if (!routeDue && !alertsDue) {
			return;
		}
		// Alerts do not need a route; route weather does. Working that out HERE rather than
		// inside the worker keeps the thread from being started for a job that has nothing to do.
		List<Location> ahead = null;
		int remainingMinutes = 0;
		if (routeDue) {
			net.osmand.plus.routing.RouteCalculationResult route = helper.getRoute();
			if (route != null) {
				List<Location> locations = new java.util.ArrayList<>(route.getRouteLocations());
				if (locations.size() >= 2) {
					ahead = locations;
					remainingMinutes = Math.max(0, helper.getLeftTime() / 60);
				}
			}
		}
		if (ahead == null && !alertsDue) {
			return;
		}
		double lat = location.getLatitude();
		double lon = location.getLongitude();
		List<Location> routeAhead = ahead;
		int minutes = remainingMinutes;
		boolean wantAlerts = alertsDue;
		synchronized (AzureRouteWeatherProvider.class) {
			if (inFlight) {
				return;
			}
			inFlight = true;
		}
		Thread t = new Thread(() -> {
			android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
			try {
				if (routeAhead != null) {
					checkRoute(app, routeAhead, minutes);
				}
				if (wantAlerts) {
					// Free driving included, unlike the route call above - a government sandstorm
					// warning is about the sky over the car, not about a destination.
					severeAlerts(app, lat, lon);
				}
			} catch (Throwable th) {
				LOG.info("Azure weather failed: " + th.getClass().getSimpleName());
			} finally {
				inFlight = false;
			}
		}, "cairo-azure-wx");
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	private static volatile boolean inFlight;

	/**
	 * Sample the route ahead and log what the weather will be on arrival at each sample.
	 *
	 * <p>Returns the worst hazard index found, or -1 when nothing was fetched. BLOCKING - call it
	 * from a worker. Nothing on screen: this writes to the drive log only, on purpose. The dust
	 * banner is OpenWeather's and stays OpenWeather's; adding a second thing that can raise a
	 * warning is how a banner stops being believed.
	 */
	public static int checkRoute(@NonNull OsmandApplication app,
	                             @Nullable List<Location> routeAhead,
	                             int remainingMinutes) {
		if (!hasKey()) {
			ApiHealth.recordSkipped(ApiHealth.Api.AZURE_MAPS, ApiHealth.Skip.NO_KEY);
			return -1;
		}
		if (routeAhead == null || routeAhead.size() < 2) {
			ApiHealth.recordSkipped(ApiHealth.Api.AZURE_MAPS, ApiHealth.Skip.NOT_APPLICABLE);
			return -1;
		}
		if (!app.getSettings().isInternetConnectionAvailable()) {
			ApiHealth.recordSkipped(ApiHealth.Api.AZURE_MAPS, ApiHealth.Skip.NO_INTERNET);
			return -1;
		}
		long now = System.currentTimeMillis();
		if (lastPollMs != 0 && now - lastPollMs < MIN_INTERVAL_MS) {
			return -1;
		}
		if (!claim(app)) {
			ApiHealth.recordSkipped(ApiHealth.Api.AZURE_MAPS, ApiHealth.Skip.BUDGET_SPENT);
			CairoDriveLog.log(TRACE_TAG, "azure route weather skipped - daily cap " + DAILY_CAP);
			return -1;
		}
		lastPollMs = now;

		String query = buildQuery(routeAhead, remainingMinutes);
		if (query == null) {
			return -1;
		}
		String url = ROUTE_WEATHER_API + "?api-version=" + API_VERSION
				+ "&query=" + query
				+ "&subscription-key=" + BuildConfig.CAIRODRIVE_AZURE_MAPS_KEY;
		String body = get(url);
		if (body == null) {
			return -1;
		}
		return parse(body);
	}

	/**
	 * {@code lat,lon,eta,heading} tuples joined by colons.
	 *
	 * <p>The ETA is what makes this endpoint worth calling, so it is computed from the remaining
	 * time the routing engine already knows rather than assumed: waypoint i sits at fraction i/n
	 * of the route, so it is reached at roughly i/n of the remaining minutes. Uniform in TIME
	 * would be better than uniform in INDEX, but the polyline is denser where the road turns -
	 * i.e. in town, where the car is slower - so index-uniform already leans the right way.
	 */
	@Nullable
	private static String buildQuery(@NonNull List<Location> route, int remainingMinutes) {
		int n = Math.min(WAYPOINTS, Math.min(MAX_WAYPOINTS, route.size()));
		if (n < 2) {
			return null;
		}
		int horizon = remainingMinutes > 0 ? Math.min(remainingMinutes, HORIZON_MINUTES)
				: HORIZON_MINUTES;
		StringBuilder sb = new StringBuilder();
		for (int i = 0; i < n; i++) {
			int idx = (int) ((long) i * (route.size() - 1) / (n - 1));
			Location p = route.get(idx);
			if (p == null) {
				continue;
			}
			int etaMinutes = (int) ((long) i * horizon / (n - 1));
			int heading = headingAt(route, idx);
			if (sb.length() > 0) {
				sb.append(':');
			}
			sb.append(String.format(Locale.US, "%.5f,%.5f,%d,%d",
					p.getLatitude(), p.getLongitude(), etaMinutes, heading));
		}
		return sb.length() == 0 ? null : sb.toString();
	}

	/**
	 * Bearing from this point to the next, in degrees.
	 *
	 * <p>Heading is not optional decoration - it is what the sun-glare part of the response is
	 * computed FROM. Sending a constant would return a glare index for a car that is not pointing
	 * where this one is pointing, which is worse than not asking.
	 */
	private static int headingAt(@NonNull List<Location> route, int idx) {
		int next = Math.min(idx + 1, route.size() - 1);
		int from = next == idx ? Math.max(0, idx - 1) : idx;
		Location a = route.get(from);
		Location b = route.get(next);
		if (a == null || b == null || from == next) {
			return 0;
		}
		double lat1 = Math.toRadians(a.getLatitude());
		double lat2 = Math.toRadians(b.getLatitude());
		double dLon = Math.toRadians(b.getLongitude() - a.getLongitude());
		double y = Math.sin(dLon) * Math.cos(lat2);
		double x = Math.cos(lat1) * Math.sin(lat2)
				- Math.sin(lat1) * Math.cos(lat2) * Math.cos(dLon);
		double bearing = Math.toDegrees(Math.atan2(y, x));
		return (int) Math.round((bearing % 360 + 360) % 360);
	}

	/** Worst hazard index across the waypoints, and one log line describing the route's weather. */
	private static int parse(@NonNull String body) {
		try {
			JSONArray waypoints = new JSONObject(body).optJSONArray("waypoints");
			if (waypoints == null) {
				CairoDriveLog.log(TRACE_TAG, "azure route weather: no waypoints in response");
				return -1;
			}
			int worst = 0;
			int worstAt = -1;
			int glareWaypoints = 0;
			StringBuilder detail = new StringBuilder();
			for (int i = 0; i < waypoints.length(); i++) {
				JSONObject w = waypoints.getJSONObject(i);
				JSONObject hazards = w.optJSONObject("hazards");
				int index = hazards != null ? hazards.optInt("hazardIndex", 0) : 0;
				if (index > worst) {
					worst = index;
					worstAt = i;
				}
				JSONObject sunGlare = w.optJSONObject("sunGlare");
				if (sunGlare != null && sunGlare.optInt("glareIndex", 0) > 0) {
					glareWaypoints++;
				}
				if (index >= HAZARD_NOTABLE && detail.length() < 120) {
					if (detail.length() > 0) {
						detail.append(", ");
					}
					detail.append("wp").append(i).append(":idx").append(index);
				}
			}
			CairoDriveLog.log(TRACE_TAG, "azure route weather: " + waypoints.length()
					+ " waypoint(s), worst hazardIndex=" + worst
					+ (worstAt >= 0 ? " at wp" + worstAt : "")
					+ ", glare on " + glareWaypoints + " wp"
					+ (detail.length() > 0 ? " [" + detail + "]" : ""));
			return worst;
		} catch (Throwable t) {
			LOG.info("Azure route weather parse failed: " + t.getClass().getSimpleName());
			return -1;
		}
	}

	// ------------------------------------------------------------ severe weather alerts

	/**
	 * Official severe-weather alerts for a point, as a short human-readable line, or null.
	 *
	 * <p>BLOCKING. Shares the daily cap with the route call on purpose - they are the same key,
	 * the same account and the same bill, and a cap that only counted one of two endpoints would
	 * bound nothing.
	 *
	 * <p>Free driving IS served here, unlike the route weather. A government sandstorm warning is
	 * about the sky over the car, not about a destination the driver may not have entered.
	 */
	@Nullable
	public static String severeAlerts(@NonNull OsmandApplication app, double lat, double lon) {
		if (!hasKey() || !app.getSettings().isInternetConnectionAvailable()) {
			return null;
		}
		long now = System.currentTimeMillis();
		if (lastAlertPollMs != 0 && now - lastAlertPollMs < ALERT_INTERVAL_MS) {
			return null;
		}
		if (!claim(app)) {
			ApiHealth.recordSkipped(ApiHealth.Api.AZURE_MAPS, ApiHealth.Skip.BUDGET_SPENT);
			return null;
		}
		lastAlertPollMs = now;
		String url = String.format(Locale.US, "%s?api-version=%s&query=%.5f,%.5f&subscription-key=%s",
				SEVERE_ALERTS_API, API_VERSION, lat, lon, BuildConfig.CAIRODRIVE_AZURE_MAPS_KEY);
		String body = get(url);
		if (body == null) {
			return null;
		}
		try {
			JSONArray results = new JSONObject(body).optJSONArray("results");
			if (results == null || results.length() == 0) {
				// The overwhelmingly common case, and it is logged. A silent nothing is
				// indistinguishable from a provider that never ran, which is the failure this
				// codebase keeps repeating.
				CairoDriveLog.log(TRACE_TAG, "azure severe alerts: none active here");
				lastAlertSummary = null;
				return null;
			}
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < results.length() && sb.length() < 200; i++) {
				JSONObject alert = results.getJSONObject(i);
				String description = alert.optString("description", "");
				if (Algorithms.isEmpty(description)) {
					JSONObject d = alert.optJSONObject("description");
					description = d != null ? d.optString("english", "") : "";
				}
				String category = alert.optString("category", "");
				if (Algorithms.isEmpty(description) && Algorithms.isEmpty(category)) {
					continue;
				}
				if (sb.length() > 0) {
					sb.append("; ");
				}
				sb.append(Algorithms.isEmpty(description) ? category : description);
			}
			lastAlertSummary = sb.length() == 0 ? null : sb.toString();
			CairoDriveLog.log(TRACE_TAG, "azure severe alerts: " + results.length()
					+ " active - " + (lastAlertSummary != null ? lastAlertSummary : "unnamed"));
			return lastAlertSummary;
		} catch (Throwable t) {
			LOG.info("Azure severe alerts parse failed: " + t.getClass().getSimpleName());
			return null;
		}
	}

	/**
	 * Alerts change on the scale of a weather bulletin, not a drive. Longer than the route poll
	 * because re-asking every ten minutes would spend the shared cap on an answer that is the same
	 * string it was ten minutes ago.
	 */
	private static final long ALERT_INTERVAL_MS = 30 * 60 * 1000L;

	private static volatile long lastAlertPollMs;
	@Nullable
	private static volatile String lastAlertSummary;

	private static boolean claim(@NonNull OsmandApplication app) {
		try {
			android.content.SharedPreferences prefs = app.getSharedPreferences(
					"cairodrive_providers", android.content.Context.MODE_PRIVATE);
			int today = (int) (System.currentTimeMillis() / (24L * 60 * 60 * 1000));
			synchronized (AzureRouteWeatherProvider.class) {
				int day = prefs.getInt(PREF_DAY, -1);
				int count = day == today ? prefs.getInt(PREF_COUNT, 0) : 0;
				if (count >= DAILY_CAP) {
					return false;
				}
				prefs.edit().putInt(PREF_DAY, today).putInt(PREF_COUNT, count + 1).apply();
				return true;
			}
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * Same key-in-URL discipline as the other providers: the subscription key is a query
	 * parameter, so the error stream is never read and only the status code is recorded.
	 *
	 * <p>One Azure-specific note worth keeping: 401 and 403 here mean the subscription key is
	 * wrong or the account is disabled, NOT that the free allowance ran out - Azure does not
	 * refuse over-allowance requests, it bills them. So a working key that goes quiet is a billing
	 * question, not a quota one, and the daily cap above is the only thing bounding it.
	 */
	@Nullable
	private static String get(@NonNull String url) {
		HttpURLConnection c = null;
		try {
			c = NetworkUtils.getHttpURLConnection(url);
			c.setConnectTimeout(CONNECT_TIMEOUT_MS);
			c.setReadTimeout(READ_TIMEOUT_MS);
			c.setRequestProperty("Accept", "application/json");
			int code = c.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) {
				ApiHealth.recordFailure(ApiHealth.Api.AZURE_MAPS, code, null);
				CairoDriveLog.log(TRACE_TAG, "azure HTTP " + code);
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
			ApiHealth.recordOk(ApiHealth.Api.AZURE_MAPS);
			return sb.toString();
		} catch (Throwable t) {
			ApiHealth.recordFailure(ApiHealth.Api.AZURE_MAPS, 0, t.getClass().getSimpleName());
			return null;
		} finally {
			if (c != null) {
				c.disconnect();
			}
		}
	}
}
