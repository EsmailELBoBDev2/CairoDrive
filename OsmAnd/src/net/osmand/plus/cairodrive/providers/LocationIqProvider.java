package net.osmand.plus.cairodrive.providers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
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
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The second geocoder, and only ever the second.
 *
 * <h3>Why a second one at all</h3>
 *
 * Geoapify is better for this app on the one term that matters most - it permits caching and
 * storage outright, where LocationIQ's free tier caps caching at 48 hours - so it leads. But a
 * single free geocoder means one vendor's outage, one key suspension or one exhausted daily
 * allowance takes the whole capability with it, and the thing being lost is "what street am I on"
 * while driving.
 *
 * <p>Checked online, August 2026: 5,000 requests/day and 2 requests/second on the free tier,
 * commercial use permitted with attribution. That is a LARGER daily allowance than Geoapify's
 * 3,000 on a completely separate vendor, which is exactly the shape a failover wants.
 *
 * <h3>The 48-hour cache rule is honoured by not caching here</h3>
 *
 * This class returns results straight to its caller and stores nothing. Geoapify's permissive
 * terms are what the app's prefix cache is allowed to hold; anything that arrives from LocationIQ
 * is used once and dropped. That keeps the awkward term contained to the one provider it applies
 * to, rather than forcing the strictest rule in the stack onto every provider in it.
 *
 * <h3>Two requests per second</h3>
 *
 * The rate limit is the reason autocomplete here is deliberately blunter than Geoapify's: at 2 rps
 * a keystroke stream will trip it, and a 429 from the fallback while the primary is already down
 * leaves nothing. So the debounce is longer, and autocomplete failover is a last resort rather
 * than an equal partner.
 */
public final class LocationIqProvider {

	private static final Log LOG = PlatformUtil.getLog(LocationIqProvider.class);
	/**
	 * NO "CD_" prefix here: {@link CairoDriveLog#log} adds it. Passing "CD_GEOCODE" wrote every
	 * line of this class under CD_CD_GEOCODE, so grepping the documented tag found nothing.
	 */
	private static final String TRACE_TAG = "GEOCODE";

	private static final String REVERSE_API = "https://us1.locationiq.com/v1/reverse";
	private static final String AUTOCOMPLETE_API = "https://us1.locationiq.com/v1/autocomplete";
	private static final String BALANCE_API = "https://us1.locationiq.com/v1/balance";

	private static final int CONNECT_TIMEOUT_MS = 6000;
	private static final int READ_TIMEOUT_MS = 8000;

	/** Against a documented 5,000/day. Same reasoning as Geoapify's: sized for use, not allowance. */
	private static final int REVERSE_DAILY_CAP = 120;
	private static final int AUTOCOMPLETE_DAILY_CAP = 150;

	/**
	 * Longer than Geoapify's 350 ms because the free tier allows 2 requests/second and this is the
	 * provider that is only ever reached when the primary has already failed. A 429 here is the
	 * end of the capability, not a degradation of it.
	 */
	private static final long TYPING_DEBOUNCE_MS = 700;

	private static final int MIN_QUERY_CHARS = 3;

	private static final String PREF_REV_DAY = "cairodrive_locationiq_rev_day";
	private static final String PREF_REV_COUNT = "cairodrive_locationiq_rev_count";
	private static final String PREF_AC_DAY = "cairodrive_locationiq_ac_day";
	private static final String PREF_AC_COUNT = "cairodrive_locationiq_ac_count";

	private static volatile long lastTypingRequestMs;

	private LocationIqProvider() {
	}

	public static boolean hasKey() {
		return !Algorithms.isEmpty(BuildConfig.CAIRODRIVE_LOCATIONIQ_KEY);
	}

	// ------------------------------------------------------------ reverse geocoding

	/** Street-level address for a point, or null. BLOCKING. */
	@Nullable
	public static String reverseGeocode(@NonNull OsmandApplication app, @NonNull LatLon at) {
		if (!hasKey()) {
			ApiHealth.recordSkipped(ApiHealth.Api.LOCATIONIQ, ApiHealth.Skip.NO_KEY);
			return null;
		}
		if (!app.getSettings().isInternetConnectionAvailable()) {
			ApiHealth.recordSkipped(ApiHealth.Api.LOCATIONIQ, ApiHealth.Skip.NO_INTERNET);
			return null;
		}
		if (!claim(app, PREF_REV_DAY, PREF_REV_COUNT, REVERSE_DAILY_CAP)) {
			ApiHealth.recordSkipped(ApiHealth.Api.LOCATIONIQ, ApiHealth.Skip.BUDGET_SPENT);
			return null;
		}
		String url = String.format(Locale.US,
				"%s?key=%s&lat=%.6f&lon=%.6f&format=json&addressdetails=1&accept-language=%s",
				REVERSE_API, BuildConfig.CAIRODRIVE_LOCATIONIQ_KEY,
				at.getLatitude(), at.getLongitude(), lang(app));
		String body = get(url, ApiHealth.Api.LOCATIONIQ);
		if (body == null) {
			return null;
		}
		try {
			JSONObject root = new JSONObject(body);
			JSONObject address = root.optJSONObject("address");
			if (address != null) {
				String road = address.optString("road", "");
				String suburb = address.optString("suburb", "");
				if (Algorithms.isEmpty(suburb)) {
					suburb = address.optString("city_district", "");
				}
				if (!Algorithms.isEmpty(road) && !Algorithms.isEmpty(suburb)) {
					return road + ", " + suburb;
				}
				if (!Algorithms.isEmpty(road)) {
					return road;
				}
			}
			String display = root.optString("display_name", "");
			return Algorithms.isEmpty(display) ? null : display;
		} catch (Throwable t) {
			LOG.info("LocationIQ reverse parse failed: " + t.getClass().getSimpleName());
			return null;
		}
	}

	// ------------------------------------------------------------ autocomplete

	/** Suggestions for a partial query, or an empty list. BLOCKING. */
	@NonNull
	public static List<GeoapifyProvider.Suggestion> autocomplete(@NonNull OsmandApplication app,
	                                                             @Nullable String query) {
		List<GeoapifyProvider.Suggestion> out = new ArrayList<>();
		if (query == null || query.trim().length() < MIN_QUERY_CHARS || !hasKey()) {
			return out;
		}
		if (!app.getSettings().isInternetConnectionAvailable()) {
			return out;
		}
		long now = System.currentTimeMillis();
		synchronized (LocationIqProvider.class) {
			if (now - lastTypingRequestMs < TYPING_DEBOUNCE_MS) {
				return out;
			}
			lastTypingRequestMs = now;
		}
		if (!claim(app, PREF_AC_DAY, PREF_AC_COUNT, AUTOCOMPLETE_DAILY_CAP)) {
			ApiHealth.recordSkipped(ApiHealth.Api.LOCATIONIQ, ApiHealth.Skip.BUDGET_SPENT);
			return out;
		}
		String url;
		try {
			url = String.format(Locale.US,
					"%s?key=%s&q=%s&countrycodes=eg&limit=6&accept-language=%s",
					AUTOCOMPLETE_API, BuildConfig.CAIRODRIVE_LOCATIONIQ_KEY,
					URLEncoder.encode(query.trim(), "UTF-8"), lang(app));
		} catch (Throwable t) {
			return out;
		}
		String body = get(url, ApiHealth.Api.LOCATIONIQ);
		if (body == null) {
			return out;
		}
		try {
			// This endpoint returns a bare ARRAY, not an object with a results field - the one
			// shape difference from Geoapify that matters when reading the two side by side.
			JSONArray results = new JSONArray(body);
			for (int i = 0; i < results.length(); i++) {
				JSONObject r = results.getJSONObject(i);
				String label = r.optString("display_name", "");
				String lat = r.optString("lat", "");
				String lon = r.optString("lon", "");
				if (Algorithms.isEmpty(label) || Algorithms.isEmpty(lat) || Algorithms.isEmpty(lon)) {
					continue;
				}
				out.add(new GeoapifyProvider.Suggestion(label,
						new LatLon(Double.parseDouble(lat), Double.parseDouble(lon))));
			}
		} catch (Throwable t) {
			LOG.info("LocationIQ autocomplete parse failed: " + t.getClass().getSimpleName());
		}
		return out;
	}

	// ------------------------------------------------------------ quota telemetry

	/**
	 * Remaining requests on the key today, or -1 if unknown.
	 *
	 * <p>This endpoint is LocationIQ's one genuinely unique feature in the whole provider survey:
	 * every other provider here leaves the app guessing at its own consumption from a counter it
	 * keeps itself, which is wrong the moment anything else uses the same key. Worth one call at
	 * session start purely so the status screen can state a real number instead of an estimate.
	 *
	 * <p>Deliberately not counted against {@link #REVERSE_DAILY_CAP} - asking how much budget is
	 * left must not itself spend the budget being asked about.
	 */
	public static int remainingToday(@NonNull OsmandApplication app) {
		if (!hasKey() || !app.getSettings().isInternetConnectionAvailable()) {
			return -1;
		}
		String body = get(BALANCE_API + "?key=" + BuildConfig.CAIRODRIVE_LOCATIONIQ_KEY,
				ApiHealth.Api.LOCATIONIQ);
		if (body == null) {
			return -1;
		}
		try {
			JSONObject balance = new JSONObject(body).optJSONObject("balance");
			int day = balance != null ? balance.optInt("day", -1) : -1;
			if (day >= 0) {
				CairoDriveLog.log(TRACE_TAG, "locationiq balance: " + day + " request(s) left today");
			}
			return day;
		} catch (Throwable t) {
			return -1;
		}
	}

	// ------------------------------------------------------------ plumbing

	@NonNull
	private static String lang(@NonNull OsmandApplication app) {
		String l = app.getLanguage();
		return l != null && l.startsWith("ar") ? "ar" : "en";
	}

	private static boolean claim(@NonNull OsmandApplication app, @NonNull String dayPref,
	                             @NonNull String countPref, int cap) {
		try {
			android.content.SharedPreferences prefs = app.getSharedPreferences(
					"cairodrive_providers", android.content.Context.MODE_PRIVATE);
			int today = (int) (System.currentTimeMillis() / (24L * 60 * 60 * 1000));
			synchronized (LocationIqProvider.class) {
				int day = prefs.getInt(dayPref, -1);
				int count = day == today ? prefs.getInt(countPref, 0) : 0;
				if (count >= cap) {
					return false;
				}
				prefs.edit().putInt(dayPref, today).putInt(countPref, count + 1).apply();
				return true;
			}
		} catch (Throwable t) {
			return false;
		}
	}

	/** Same key-in-URL rule as everywhere else here: status code recorded, error body never read. */
	@Nullable
	private static String get(@NonNull String url, @NonNull ApiHealth.Api api) {
		HttpURLConnection c = null;
		try {
			c = NetworkUtils.getHttpURLConnection(url);
			c.setConnectTimeout(CONNECT_TIMEOUT_MS);
			c.setReadTimeout(READ_TIMEOUT_MS);
			c.setRequestProperty("Accept", "application/json");
			int code = c.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) {
				ApiHealth.recordFailure(api, code, null);
				CairoDriveLog.log(TRACE_TAG, "locationiq HTTP " + code);
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
			ApiHealth.recordOk(api);
			return sb.toString();
		} catch (Throwable t) {
			ApiHealth.recordFailure(api, 0, t.getClass().getSimpleName());
			return null;
		} finally {
			if (c != null) {
				c.disconnect();
			}
		}
	}
}
