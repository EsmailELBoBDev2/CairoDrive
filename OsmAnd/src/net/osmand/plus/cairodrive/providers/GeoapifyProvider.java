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
 * Addresses and as-you-type suggestions, from Geoapify - the two things this app asks for most
 * often and pays Google the most for.
 *
 * <h3>Why this provider exists at all</h3>
 *
 * The provider audit put Geoapify at "do not register yet", and the reason it gave was Places
 * search, where Google's Cairo corpus is genuinely irreplaceable. That verdict still stands and
 * nothing here touches place SEARCH. This class takes the two jobs Google is worst value for:
 *
 * <ul>
 *   <li><b>Autocomplete.</b> Google bills this per keystroke SESSION and it is the single most
 *       expensive line in the deferred-features table. It is also the one the previous mass-add
 *       attempt went wrong on, because a per-keystroke cost is invisible until you type.</li>
 *   <li><b>Reverse geocoding.</b> "What street am I on", "what is this pin". The offline .obf
 *       answers it for named roads and gives nothing for the Cairo alleys CD_NARROW measured at
 *       ~16.6% name coverage, and the audit rules out Google Geocoding as another SKU.</li>
 * </ul>
 *
 * <h3>Why Geoapify rather than the other free geocoder</h3>
 *
 * Checked online, August 2026: 3,000 credits/day, no credit card, and - the part that decides it -
 * explicit written permission to cache and store results. LocationIQ's free tier caps caching at
 * 48 hours, which collides with the prefix cache CD_SEARCH exists to protect. So Geoapify leads
 * and {@link LocationIqProvider} stands behind it, on a different vendor, for the case where
 * Geoapify is down or the day's credits are gone.
 *
 * <h3>Cost shape</h3>
 *
 * Reverse geocoding is 1 credit. Autocomplete is 1 credit PER REQUEST, not per session, which is
 * the opposite of Google's model and means the debounce below is the whole cost control: without
 * it, "مدينة نصر" is nine requests instead of one or two. 3,000/day is generous but it is a DAY,
 * not a month, so the ladder still applies.
 */
public final class GeoapifyProvider {

	private static final Log LOG = PlatformUtil.getLog(GeoapifyProvider.class);
	private static final String TRACE_TAG = "CD_GEOCODE";

	private static final String REVERSE_API = "https://api.geoapify.com/v1/geocode/reverse";
	private static final String AUTOCOMPLETE_API = "https://api.geoapify.com/v1/geocode/autocomplete";
	/** Same key, third endpoint. 800+ OSM categories; billed per 20 places returned. */
	private static final String PLACES_API = "https://api.geoapify.com/v2/places";

	private static final int CONNECT_TIMEOUT_MS = 6000;
	private static final int READ_TIMEOUT_MS = 8000;

	/**
	 * Against a documented 3,000/day. Sized for what this app actually does - a handful of reverse
	 * lookups per drive and a search session or two - not for the allowance, so that a bug that
	 * starts looping cannot spend the day's budget before anyone sees a drive log.
	 */
	private static final int REVERSE_DAILY_CAP = 120;
	private static final int AUTOCOMPLETE_DAILY_CAP = 300;
	/** Small: this is a deliberate driver action, not something polled. */
	private static final int NEARBY_DAILY_CAP = 60;

	/** One credit's worth. More than anyone reads from a car, and the next 20 cost another. */
	private static final int NEARBY_LIMIT = 20;

	/**
	 * Beyond this a "nearby" result is not nearby. Also bounds the work the provider does for a
	 * query someone made by mistake.
	 */
	private static final int MAX_NEARBY_RADIUS_M = 15000;

	/**
	 * Typing debounce. Geoapify bills per REQUEST, so this number is the cost model: at 350 ms a
	 * normal typist produces one request per word rather than one per letter. Deliberately longer
	 * than a UI would feel snappy at, because the previous attempt at typing-time features was
	 * judged "buggy as hell" while typing and the standing rule is to judge this one the same way.
	 */
	private static final long TYPING_DEBOUNCE_MS = 350;

	/** Below this, a query is a prefix nobody can geocode usefully and it is not sent. */
	private static final int MIN_QUERY_CHARS = 3;

	/** Cairo. Biases results without restricting them - a wrong city ranks below a right street. */
	private static final double BIAS_LAT = 30.05;
	private static final double BIAS_LON = 31.24;

	private static final String PREF_REV_DAY = "cairodrive_geoapify_rev_day";
	private static final String PREF_REV_COUNT = "cairodrive_geoapify_rev_count";
	private static final String PREF_AC_DAY = "cairodrive_geoapify_ac_day";
	private static final String PREF_AC_COUNT = "cairodrive_geoapify_ac_count";
	private static final String PREF_NEARBY_DAY = "cairodrive_geoapify_nearby_day";
	private static final String PREF_NEARBY_COUNT = "cairodrive_geoapify_nearby_count";

	private static volatile long lastTypingRequestMs;

	private GeoapifyProvider() {
	}

	/** Key present. The flag is checked by the caller, because both users of this differ. */
	public static boolean hasKey() {
		return !Algorithms.isEmpty(BuildConfig.CAIRODRIVE_GEOAPIFY_KEY);
	}

	// ------------------------------------------------------------ reverse geocoding

	/**
	 * Street-level address for a point, or null.
	 *
	 * <p>BLOCKING - call it off the main thread. It is not made async here on purpose: both
	 * callers already run on a worker, and an internal thread would make the daily cap racy for
	 * no benefit.
	 */
	@Nullable
	public static String reverseGeocode(@NonNull OsmandApplication app, @NonNull LatLon at) {
		if (!hasKey()) {
			ApiHealth.recordSkipped(ApiHealth.Api.GEOAPIFY, ApiHealth.Skip.NO_KEY);
			return null;
		}
		if (!app.getSettings().isInternetConnectionAvailable()) {
			ApiHealth.recordSkipped(ApiHealth.Api.GEOAPIFY, ApiHealth.Skip.NO_INTERNET);
			return null;
		}
		if (!claim(app, PREF_REV_DAY, PREF_REV_COUNT, REVERSE_DAILY_CAP)) {
			ApiHealth.recordSkipped(ApiHealth.Api.GEOAPIFY, ApiHealth.Skip.BUDGET_SPENT);
			CairoDriveLog.log(TRACE_TAG, "geoapify reverse skipped - daily cap " + REVERSE_DAILY_CAP);
			return null;
		}
		String url = String.format(Locale.US,
				"%s?lat=%.6f&lon=%.6f&format=json&lang=%s&apiKey=%s",
				REVERSE_API, at.getLatitude(), at.getLongitude(), lang(app),
				BuildConfig.CAIRODRIVE_GEOAPIFY_KEY);
		long started = System.currentTimeMillis();
		String body = get(url, ApiHealth.Api.GEOAPIFY);
		if (body == null) {
			return null;
		}
		try {
			// format=json gives a flat `results` array rather than GeoJSON `features`, which is
			// why format is pinned above - the two shapes differ and silently parsing the wrong
			// one returns null forever.
			JSONArray results = new JSONObject(body).optJSONArray("results");
			if (results == null || results.length() == 0) {
				CairoDriveLog.log(TRACE_TAG, "geoapify reverse: no result");
				return null;
			}
			String formatted = bestAddress(results.getJSONObject(0));
			CairoDriveLog.log(TRACE_TAG, "geoapify reverse ok in "
					+ (System.currentTimeMillis() - started) + " ms");
			return formatted;
		} catch (Throwable t) {
			LOG.info("Geoapify reverse parse failed: " + t.getClass().getSimpleName());
			return null;
		}
	}

	/**
	 * The most specific thing worth reading aloud or putting in a context menu.
	 *
	 * <p>`formatted` alone is often "Cairo, Egypt" for an alley, which is useless to someone
	 * standing in it. Street plus suburb is what a person would say.
	 */
	@Nullable
	private static String bestAddress(@NonNull JSONObject r) {
		String street = r.optString("street", "");
		String suburb = r.optString("suburb", "");
		if (Algorithms.isEmpty(suburb)) {
			suburb = r.optString("district", "");
		}
		if (!Algorithms.isEmpty(street) && !Algorithms.isEmpty(suburb)) {
			return street + ", " + suburb;
		}
		if (!Algorithms.isEmpty(street)) {
			return street;
		}
		String formatted = r.optString("formatted", "");
		return Algorithms.isEmpty(formatted) ? null : formatted;
	}

	// ------------------------------------------------------------ autocomplete

	/**
	 * Suggestions for a partial query. Empty list rather than null when there is nothing to say,
	 * so a caller never has to distinguish "no suggestions" from "provider unavailable" - both
	 * mean the same thing on screen.
	 *
	 * <p>BLOCKING. The debounce is enforced HERE rather than in the UI because there is more than
	 * one caller and a per-caller debounce is a per-caller bug.
	 */
	@NonNull
	public static List<Suggestion> autocomplete(@NonNull OsmandApplication app, @Nullable String query) {
		List<Suggestion> out = new ArrayList<>();
		if (query == null || query.trim().length() < MIN_QUERY_CHARS || !hasKey()) {
			return out;
		}
		if (!app.getSettings().isInternetConnectionAvailable()) {
			ApiHealth.recordSkipped(ApiHealth.Api.GEOAPIFY, ApiHealth.Skip.NO_INTERNET);
			return out;
		}
		long now = System.currentTimeMillis();
		synchronized (GeoapifyProvider.class) {
			if (now - lastTypingRequestMs < TYPING_DEBOUNCE_MS) {
				return out;
			}
			lastTypingRequestMs = now;
		}
		if (!claim(app, PREF_AC_DAY, PREF_AC_COUNT, AUTOCOMPLETE_DAILY_CAP)) {
			ApiHealth.recordSkipped(ApiHealth.Api.GEOAPIFY, ApiHealth.Skip.BUDGET_SPENT);
			return out;
		}
		String url;
		try {
			url = String.format(Locale.US,
					"%s?text=%s&bias=proximity:%.4f,%.4f&filter=countrycode:eg&format=json&lang=%s&limit=6&apiKey=%s",
					AUTOCOMPLETE_API, URLEncoder.encode(query.trim(), "UTF-8"),
					BIAS_LON, BIAS_LAT, lang(app), BuildConfig.CAIRODRIVE_GEOAPIFY_KEY);
		} catch (Throwable t) {
			return out;
		}
		String body = get(url, ApiHealth.Api.GEOAPIFY);
		if (body == null) {
			return out;
		}
		try {
			JSONArray results = new JSONObject(body).optJSONArray("results");
			for (int i = 0; results != null && i < results.length(); i++) {
				JSONObject r = results.getJSONObject(i);
				String label = r.optString("formatted", "");
				if (Algorithms.isEmpty(label) || !r.has("lat") || !r.has("lon")) {
					continue;
				}
				out.add(new Suggestion(label, new LatLon(r.getDouble("lat"), r.getDouble("lon"))));
			}
			CairoDriveLog.log(TRACE_TAG, "geoapify autocomplete '" + redact(query) + "' -> "
					+ out.size() + " result(s)");
		} catch (Throwable t) {
			LOG.info("Geoapify autocomplete parse failed: " + t.getClass().getSimpleName());
		}
		return out;
	}

	// ------------------------------------------------------------ nearby places

	/**
	 * Places of a category within a radius - "petrol near me", "pharmacy near me".
	 *
	 * <p>The SAME key that already serves addresses, on a different endpoint. Google's Nearby
	 * Search sits at a deliberate quota of <b>zero</b> in the console, so this capability has no
	 * other source in the app at all; the offline .obf has POIs but is missing exactly the
	 * informal, unbranded businesses that make up much of Cairo.
	 *
	 * <p>Billing shape worth knowing: Geoapify charges per 20 PLACES returned, not per request, so
	 * the limit below is the cost. 20 is one credit and is already more than anyone reads from a
	 * car.
	 *
	 * <p>BLOCKING. Empty list when unavailable, never null - the caller cannot act differently on
	 * "no petrol stations" and "no provider" anyway.
	 */
	@NonNull
	public static List<Suggestion> nearby(@NonNull OsmandApplication app, @NonNull LatLon at,
	                                      @NonNull String categories, int radiusMetres) {
		List<Suggestion> out = new ArrayList<>();
		if (!hasKey()) {
			ApiHealth.recordSkipped(ApiHealth.Api.GEOAPIFY, ApiHealth.Skip.NO_KEY);
			return out;
		}
		if (!app.getSettings().isInternetConnectionAvailable()) {
			ApiHealth.recordSkipped(ApiHealth.Api.GEOAPIFY, ApiHealth.Skip.NO_INTERNET);
			return out;
		}
		if (!claim(app, PREF_NEARBY_DAY, PREF_NEARBY_COUNT, NEARBY_DAILY_CAP)) {
			ApiHealth.recordSkipped(ApiHealth.Api.GEOAPIFY, ApiHealth.Skip.BUDGET_SPENT);
			CairoDriveLog.log(TRACE_TAG, "geoapify nearby skipped - daily cap " + NEARBY_DAILY_CAP);
			return out;
		}
		int radius = Math.max(200, Math.min(radiusMetres, MAX_NEARBY_RADIUS_M));
		String url = String.format(Locale.US,
				"%s?categories=%s&filter=circle:%.6f,%.6f,%d&bias=proximity:%.6f,%.6f"
						+ "&limit=%d&lang=%s&apiKey=%s",
				PLACES_API, categories, at.getLongitude(), at.getLatitude(), radius,
				at.getLongitude(), at.getLatitude(), NEARBY_LIMIT, lang(app),
				BuildConfig.CAIRODRIVE_GEOAPIFY_KEY);
		String body = get(url, ApiHealth.Api.GEOAPIFY);
		if (body == null) {
			return out;
		}
		try {
			// This endpoint answers in GeoJSON - `features`, each with `properties` and a
			// `geometry` - NOT the flat `results` the geocoding endpoints return with format=json.
			// Same provider, same key, different shape.
			JSONArray features = new JSONObject(body).optJSONArray("features");
			for (int i = 0; features != null && i < features.length(); i++) {
				JSONObject props = features.getJSONObject(i).optJSONObject("properties");
				if (props == null || !props.has("lat") || !props.has("lon")) {
					continue;
				}
				String label = props.optString("name", "");
				if (Algorithms.isEmpty(label)) {
					label = props.optString("formatted", "");
				}
				if (Algorithms.isEmpty(label)) {
					continue;
				}
				out.add(new Suggestion(label,
						new LatLon(props.getDouble("lat"), props.getDouble("lon"))));
			}
			CairoDriveLog.log(TRACE_TAG, "geoapify nearby " + categories + " r=" + radius
					+ "m -> " + out.size() + " place(s)");
		} catch (Throwable t) {
			LOG.info("Geoapify places parse failed: " + t.getClass().getSimpleName());
		}
		return out;
	}

	/** One suggestion. Deliberately not an OsmAnd SearchResult - the caller decides what it is. */
	public static final class Suggestion {
		public final String label;
		public final LatLon location;

		Suggestion(String label, LatLon location) {
			this.label = label;
			this.location = location;
		}
	}

	// ------------------------------------------------------------ plumbing

	/**
	 * Length only, never the text.
	 *
	 * <p>What a driver types into a search box is their destination. It goes to the provider
	 * because that is what a search is, but it does not go into a log file that gets pulled off
	 * the phone and pasted into a chat.
	 */
	@NonNull
	private static String redact(@NonNull String query) {
		return "<" + query.trim().length() + " chars>";
	}

	/** Arabic when the app is Arabic, English otherwise. Geoapify returns native names for both. */
	@NonNull
	private static String lang(@NonNull OsmandApplication app) {
		String l = app.getLanguage();
		return l != null && l.startsWith("ar") ? "ar" : "en";
	}

	/**
	 * Day-rolling counter, same shape as every other cap in this package.
	 *
	 * <p>Keyed on the local calendar day so it resets overnight rather than 24h after first use.
	 */
	private static boolean claim(@NonNull OsmandApplication app, @NonNull String dayPref,
	                             @NonNull String countPref, int cap) {
		try {
			android.content.SharedPreferences prefs = app.getSharedPreferences(
					"cairodrive_providers", android.content.Context.MODE_PRIVATE);
			int today = (int) (System.currentTimeMillis() / (24L * 60 * 60 * 1000));
			synchronized (GeoapifyProvider.class) {
				int day = prefs.getInt(dayPref, -1);
				int count = day == today ? prefs.getInt(countPref, 0) : 0;
				if (count >= cap) {
					return false;
				}
				prefs.edit().putInt(dayPref, today).putInt(countPref, count + 1).apply();
				return true;
			}
		} catch (Throwable t) {
			// A failed counter must not become a free pass: if the budget cannot be accounted for,
			// no request is made.
			return false;
		}
	}

	/**
	 * GET returning the body, or null on any failure.
	 *
	 * <p>The error body is never read, and that is not laziness: the API key rides in this URL as
	 * a query parameter, and reading the error stream is what puts the whole URL - key included -
	 * into an exception message that then reaches a log. The status code is recorded and the
	 * status screen shows its generic wording.
	 */
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
				CairoDriveLog.log(TRACE_TAG, api.name() + " HTTP " + code);
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
			// Type only - MalformedURLException and FileNotFoundException both carry the full URL
			// as their message, and the key is in it.
			ApiHealth.recordFailure(api, 0, t.getClass().getSimpleName());
			return null;
		} finally {
			if (c != null) {
				c.disconnect();
			}
		}
	}
}
