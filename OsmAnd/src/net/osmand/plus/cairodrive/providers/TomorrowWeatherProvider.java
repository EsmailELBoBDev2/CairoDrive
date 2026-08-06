package net.osmand.plus.cairodrive.providers;

import android.os.SystemClock;

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
import java.net.SocketTimeoutException;
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
	/**
	 * NO "CD_" prefix here: {@link CairoDriveLog#log} adds it. Passing "CD_WEATHER2" wrote every
	 * line of this class under CD_CD_WEATHER2, so grepping the documented tag found nothing.
	 */
	private static final String TRACE_TAG = "WEATHER2";

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

	/**
	 * Same threshold and same ratio as OpenWeatherHazardProvider's PM signal, deliberately.
	 *
	 * <p>Two providers judging "is this coarse mineral dust" against different numbers would
	 * disagree by construction, and the disagreement would say nothing about the air.
	 */
	private static final double DUST_PM10_PM25_RATIO = 3.0;
	private static final double DUST_PM10_FLOOR_UGM3 = 75.0;

	private static volatile long lastPollMs;
	private static volatile int lastVisibilityM = -1;
	private static volatile double lastPm10 = -1;
	private static volatile double lastPm25 = -1;
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

	/** The independent coarse-particle ratio, or -1 when air-quality fields were not served. */
	public static double pmRatio() {
		return lastPm10 >= 0 && lastPm25 > 0 ? lastPm10 / lastPm25 : -1;
	}

	/**
	 * Does the independent source's particulate reading also look like mineral dust?
	 *
	 * <p>Second half of the free second opinion. Same one-directional rule as visibility: it can
	 * only ever soften the warning, never raise it, and UNKNOWN - which is what a plan that does
	 * not serve air-quality layers produces - changes nothing.
	 */
	@NonNull
	public static Corroboration corroboratesDustParticles() {
		if (!hasKey() || lastPm10 < 0 || lastPm25 <= 0 || readingAgeMs() > 2 * POLL_INTERVAL_MS) {
			return Corroboration.UNKNOWN;
		}
		boolean dusty = lastPm10 >= DUST_PM10_FLOOR_UGM3 && pmRatio() >= DUST_PM10_PM25_RATIO;
		return dusty ? Corroboration.AGREES : Corroboration.DISAGREES;
	}

	/**
	 * The two independent checks combined into the single answer the dust decision consumes.
	 *
	 * <p>Deliberately generous towards raising the warning: <b>any</b> agreement blocks the
	 * downgrade, and DISAGREES is only returned when the independent source actually looked at
	 * something and saw nothing dusty in any of it. So the softer outcome requires positive
	 * evidence of calm air rather than merely a lack of evidence for dust.
	 *
	 * <p>The asymmetry is the same one the whole hazard design rests on. A dust warning that is
	 * one shade too loud costs a little credibility; one that was quietly softened on a thin
	 * signal, on a day when visibility really was collapsing, costs more than that.
	 */
	@NonNull
	public static Corroboration dustCorroboration() {
		Corroboration vis = corroboratesLowVisibility();
		Corroboration pm = corroboratesDustParticles();
		if (vis == Corroboration.AGREES || pm == Corroboration.AGREES) {
			return Corroboration.AGREES;
		}
		if (vis == Corroboration.UNKNOWN && pm == Corroboration.UNKNOWN) {
			return Corroboration.UNKNOWN;
		}
		return Corroboration.DISAGREES;
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
		// Stamped on the ATTEMPT, before any early return in poll() below.
		//
		// It used to be set only after claim() succeeded inside the worker, so no internet or a
		// spent budget left it at 0 - this guard never engaged again and a new Thread was built on
		// EVERY GPS fix, roughly once a second, for the rest of the drive. inFlight capped how many
		// ran at once, not how many were created.
		lastPollMs = now;
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
		if (!ProviderBudget.claim(app, ApiHealth.Api.TOMORROW, PREF_DAY, PREF_COUNT, DAILY_CAP)) {
			ApiHealth.recordSkipped(ApiHealth.Api.TOMORROW, ApiHealth.Skip.BUDGET_SPENT);
			return;
		}

		// The particulate fields ride the SAME request for the SAME one unit of quota. That is the
		// whole reason they are here: OpenWeather's dust decision rests on three signals, and two
		// of them - the condition code and the PM10:PM2.5 ratio - can now be checked against a
		// different vendor for no extra cost at all. Asking for one field and ignoring a free
		// second opinion on another would be leaving the request half spent.
		//
		// Air-quality layers may not be served on every plan. That is handled by reading them as
		// optional below rather than by not asking: a field that does not come back simply leaves
		// that corroboration UNKNOWN, which is already a first-class state here.
		String url = String.format(Locale.US,
				"%s?location=%.5f,%.5f&fields=visibility,windGust,particulateMatter10,"
						+ "particulateMatter25&units=metric&apikey=%s",
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
			// Optional, and absence is not a failure - see the note on the request above.
			lastPm10 = values.optDouble("particulateMatter10", -1);
			lastPm25 = values.optDouble("particulateMatter25", -1);
			lastReadingMs = System.currentTimeMillis();
			double gust = values.optDouble("windGust", -1);
			CairoDriveLog.log(TRACE_TAG, "tomorrow.io visibility=" + lastVisibilityM + " m"
					+ (gust >= 0 ? String.format(Locale.US, " gust=%.1f m/s", gust) : "")
					+ (lastPm10 >= 0 ? String.format(Locale.US, " pm10=%.1f", lastPm10) : "")
					+ (lastPm25 >= 0 ? String.format(Locale.US, " pm2_5=%.1f", lastPm25) : "")
					+ (pmRatio() >= 0 ? String.format(Locale.US, " ratio=%.2f", pmRatio()) : "")
					+ " (independent second opinion)");
		} catch (Throwable t) {
			LOG.info("Tomorrow.io parse failed: " + t.getClass().getSimpleName());
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
		long started = SystemClock.elapsedRealtime();
		try {
			c = NetworkUtils.getHttpURLConnection(url);
			c.setConnectTimeout(CONNECT_TIMEOUT_MS);
			c.setReadTimeout(READ_TIMEOUT_MS);
			c.setRequestProperty("Accept", "application/json");
			int code = c.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) {
				long ms = SystemClock.elapsedRealtime() - started;
				ApiHealth.recordFailure(ApiHealth.Api.TOMORROW, code, null, ms);
				CairoDriveLog.log(TRACE_TAG, "tomorrow.io HTTP " + code + " ms=" + ms);
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
			long ms = SystemClock.elapsedRealtime() - started;
			ApiHealth.recordOk(ApiHealth.Api.TOMORROW, ms);
			CairoDriveLog.log(TRACE_TAG, "tomorrow.io HTTP 200 ms=" + ms + " bytes=" + sb.length());
			return sb.toString();
		} catch (Throwable t) {
			// A request that never got a response wrote nothing at all before this: the failure
			// went into ApiHealth and the method returned null, so a poll lost in a tunnel and a
			// poll that was never made produced the same log. The elapsed time is what tells a
			// refused connection from a read that ran the full timeout.
			long ms = SystemClock.elapsedRealtime() - started;
			String kind = t instanceof SocketTimeoutException ? "TIMEOUT"
					: t.getClass().getSimpleName();
			ApiHealth.recordFailure(ApiHealth.Api.TOMORROW, 0, kind, ms);
			CairoDriveLog.log(TRACE_TAG, "tomorrow.io NO RESPONSE " + kind + " ms=" + ms
					+ " connectTimeoutMs=" + CONNECT_TIMEOUT_MS
					+ " readTimeoutMs=" + READ_TIMEOUT_MS);
			return null;
		} finally {
			if (c != null) {
				c.disconnect();
			}
		}
	}
}
