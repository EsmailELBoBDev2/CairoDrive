package net.osmand.plus.cairodrive.providers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.osm.io.NetworkUtils;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.helpers.CairoDriveLog;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * A second, independent opinion on visibility - and nothing else.
 *
 * <h3>What was actually checked, and what it ruled out</h3>
 *
 * The provider audit dropped Tomorrow.io on two claims, both marked unverified. Both were checked
 * online in August 2026 and both are TRUE:
 *
 * <ul>
 *   <li>Free plan: <b>500 requests/day, 25/hour, 3/second</b>. The hourly limit is the binding
 *       one - 25/hour is one call every 2.4 minutes, so multi-waypoint sampling along a route is
 *       impossible on this plan. Azure does that job instead.</li>
 *   <li>The {@code weatherCode} vocabulary runs 1000-1102 clear/cloudy, 2000/2100 fog,
 *       4000-4201 rain, 5000-5101 snow, 6000-6201 freezing, 7000-7102 ice pellets, 8000
 *       thunderstorm. There is <b>no sand, dust or haze member anywhere in it</b>. The audit
 *       called this load-bearing and it was right: Tomorrow.io cannot name dust, so it can never
 *       replace OpenWeather's 7xx group, which carries 731/751/761 as first-class codes.</li>
 * </ul>
 *
 * <h3>So what is it for</h3>
 *
 * Corroboration. {@link OpenWeatherHazardProvider} is built entirely around not raising a FALSE
 * dust warning - its own comment says the failure it fears is a banner nobody believes - and it
 * decides using three signals that today all come from a single vendor. If OpenWeather's reading
 * for a cell is wrong, all three are wrong together and the two-of-three rule provides no
 * protection whatsoever against exactly the failure it exists to prevent.
 *
 * <p>This provider supplies {@code visibility} from a different company, a different model and a
 * different observation network. When it agrees that visibility is on the floor, the warning is
 * far better evidenced than any number of signals from one source. When it disagrees, that
 * disagreement is written to the drive log, which is the only way anyone would ever find out.
 *
 * <p>It is deliberately incapable of raising a warning on its own. It has no dust vocabulary, so
 * letting it do so would mean promoting "it is a bit murky" to "dust", which is the false positive
 * again by another route.
 *
 * <h3>Visibility is reported in kilometres and is not clamped</h3>
 *
 * OpenWeather clamps visibility at exactly 10,000 m, so anything above 10 km reads as 10 km. That
 * clamp does not matter for detecting LOW visibility and is not why this is here - the independence
 * is. But it does mean the two numbers are not directly comparable above 10 km, and the comparison
 * below only ever runs at the low end where both are exact.
 */
public final class TomorrowWeatherProvider {

	private static final Log LOG = PlatformUtil.getLog(TomorrowWeatherProvider.class);
	private static final String TRACE_TAG = "CD_WEATHER2";

	private static final String REALTIME_API = "https://api.tomorrow.io/v4/weather/realtime";

	private static final int CONNECT_TIMEOUT_MS = 8000;
	private static final int READ_TIMEOUT_MS = 12000;

	/**
	 * 25/hour is the real ceiling on the free plan, so this sits at roughly a quarter of it. There
	 * is no value in asking more often - visibility does not change meaningfully in ten minutes,
	 * and the hourly bucket is small enough that a burst would lock out the rest of the hour.
	 */
	private static final long POLL_INTERVAL_MS = 15 * 60 * 1000L;

	/** Against 500/day. A long drive with a 15-minute poll uses well under this. */
	private static final int DAILY_CAP = 40;

	/**
	 * Below this many metres, this provider agrees visibility is impaired. Matches
	 * OpenWeatherHazardProvider's own low-visibility threshold on purpose - two providers using
	 * different thresholds would disagree by construction and the comparison would mean nothing.
	 */
	private static final int VISIBILITY_LOW_M = 5000;

	private static final String PREF_DAY = "cairodrive_tomorrow_day";
	private static final String PREF_COUNT = "cairodrive_tomorrow_count";

	private static volatile long lastPollMs;
	private static volatile int lastVisibilityM = -1;
	private static volatile long lastReadingMs;

	private TomorrowWeatherProvider() {
	}

	public static boolean hasKey() {
		return !Algorithms.isEmpty(BuildConfig.CAIRODRIVE_TOMORROW_KEY);
	}

	/**
	 * The most recent independent visibility reading in metres, or -1 if there is none.
	 *
	 * <p>Non-blocking and network-free: it returns what the last poll found. A corroborating
	 * signal that made the dust decision WAIT on a second network round trip would delay the
	 * warning it is meant to strengthen.
	 */
	public static int lastVisibilityMetres() {
		return lastVisibilityM;
	}

	/** Age of {@link #lastVisibilityMetres()} in ms, or Long.MAX_VALUE if never read. */
	public static long readingAgeMs() {
		return lastReadingMs == 0 ? Long.MAX_VALUE : System.currentTimeMillis() - lastReadingMs;
	}

	/**
	 * Does an independent source agree that visibility is impaired here?
	 *
	 * <p>Three-valued on purpose, and the third value is the point: {@link Corroboration#UNKNOWN}
	 * means nobody asked or nobody answered, which must not be read as disagreement. A caller that
	 * treated silence as "no" would quietly disable the dust warning in every build without this
	 * key - the same class of fault as a provider reading an empty string and returning silently,
	 * which has now happened five times in this codebase.
	 */
	@NonNull
	public static Corroboration corroboratesLowVisibility() {
		if (!hasKey() || lastVisibilityM < 0 || readingAgeMs() > 2 * POLL_INTERVAL_MS) {
			return Corroboration.UNKNOWN;
		}
		return lastVisibilityM <= VISIBILITY_LOW_M ? Corroboration.AGREES : Corroboration.DISAGREES;
	}

	public enum Corroboration {
		/** A second vendor sees impaired visibility too. */
		AGREES,
		/** A second vendor sees clear air. Not a veto - it is logged, not acted on. */
		DISAGREES,
		/** No key, no reading, or the reading is stale. Never treat this as DISAGREES. */
		UNKNOWN
	}

	/**
	 * Location-callback entry point. Cheap, non-blocking, safe to call on every GPS fix.
	 *
	 * <p>Returns on its first line in a build without the key, which is what keeps this affordable
	 * on the fix path: the callback runs several times a second and everything expensive happens
	 * on the worker below, only when a poll is genuinely due.
	 */
	public static void onLocationUpdate(@Nullable OsmandApplication app,
	                                    @Nullable net.osmand.Location location) {
		if (app == null || location == null || !hasKey()) {
			return;
		}
		long now = System.currentTimeMillis();
		if (lastPollMs != 0 && now - lastPollMs < POLL_INTERVAL_MS) {
			return;
		}
		if (inFlight) {
			return;
		}
		double lat = location.getLatitude();
		double lon = location.getLongitude();
		synchronized (TomorrowWeatherProvider.class) {
			if (inFlight) {
				return;
			}
			inFlight = true;
		}
		Thread t = new Thread(() -> {
			android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
			try {
				poll(app, lat, lon);
			} catch (Throwable th) {
				LOG.info("Tomorrow.io poll failed: " + th.getClass().getSimpleName());
			} finally {
				inFlight = false;
			}
		}, "cairo-tomorrow");
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	private static volatile boolean inFlight;

	/**
	 * Refresh the independent reading. BLOCKING - call from the same worker that polls OpenWeather.
	 *
	 * <p>Silent no-op when there is no key, no internet, the poll is not due, or the day's small
	 * budget is gone. All four are ordinary states, not errors.
	 */
	public static void poll(@NonNull OsmandApplication app, double lat, double lon) {
		if (!hasKey()) {
			ApiHealth.recordSkipped(ApiHealth.Api.TOMORROW, ApiHealth.Skip.NO_KEY);
			return;
		}
		if (!app.getSettings().isInternetConnectionAvailable()) {
			ApiHealth.recordSkipped(ApiHealth.Api.TOMORROW, ApiHealth.Skip.NO_INTERNET);
			return;
		}
		long now = System.currentTimeMillis();
		if (lastPollMs != 0 && now - lastPollMs < POLL_INTERVAL_MS) {
			return;
		}
		if (!claim(app)) {
			ApiHealth.recordSkipped(ApiHealth.Api.TOMORROW, ApiHealth.Skip.BUDGET_SPENT);
			return;
		}
		lastPollMs = now;

		// fields is pinned to the two core layers actually used. Asking for everything would work
		// on this plan but makes the response, and any future plan change, larger than the job.
		String url = String.format(Locale.US,
				"%s?location=%.5f,%.5f&fields=visibility,windGust&units=metric&apikey=%s",
				REALTIME_API, lat, lon, BuildConfig.CAIRODRIVE_TOMORROW_KEY);
		String body = get(url);
		if (body == null) {
			return;
		}
		try {
			JSONObject values = new JSONObject(body).optJSONObject("data");
			values = values != null ? values.optJSONObject("values") : null;
			if (values == null || !values.has("visibility")) {
				CairoDriveLog.log(TRACE_TAG, "tomorrow.io: no visibility in response");
				return;
			}
			// Documented in KILOMETRES here, unlike OpenWeather's metres. Getting this wrong by a
			// factor of 1000 would make every reading look like a dust storm.
			double km = values.getDouble("visibility");
			lastVisibilityM = (int) Math.round(km * 1000);
			lastReadingMs = System.currentTimeMillis();
			double gust = values.optDouble("windGust", -1);
			CairoDriveLog.log(TRACE_TAG, "tomorrow.io visibility=" + lastVisibilityM + " m"
					+ (gust >= 0 ? String.format(Locale.US, " gust=%.1f m/s", gust) : "")
					+ " (independent second opinion)");
		} catch (Throwable t) {
			LOG.info("Tomorrow.io parse failed: " + t.getClass().getSimpleName());
		}
	}

	private static boolean claim(@NonNull OsmandApplication app) {
		try {
			android.content.SharedPreferences prefs = app.getSharedPreferences(
					"cairodrive_providers", android.content.Context.MODE_PRIVATE);
			int today = (int) (System.currentTimeMillis() / (24L * 60 * 60 * 1000));
			synchronized (TomorrowWeatherProvider.class) {
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
	 * Key-in-URL again, so the error body is never read.
	 *
	 * <p>429 is the expected failure here and it means the 25/hour bucket is empty, not that
	 * anything is broken. The status screen's "rate limited, should recover on its own" wording is
	 * exactly right for it.
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
				ApiHealth.recordFailure(ApiHealth.Api.TOMORROW, code, null);
				CairoDriveLog.log(TRACE_TAG, "tomorrow.io HTTP " + code);
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
			ApiHealth.recordOk(ApiHealth.Api.TOMORROW);
			return sb.toString();
		} catch (Throwable t) {
			ApiHealth.recordFailure(ApiHealth.Api.TOMORROW, 0, t.getClass().getSimpleName());
			return null;
		} finally {
			if (c != null) {
				c.disconnect();
			}
		}
	}
}
