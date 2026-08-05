package net.osmand.plus.cairodrive.providers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.Calendar;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Popular times - "how busy is this place right now" - from BestTime.app.
 *
 * <p>This is the one field the owner asked for that Google's Places API does not sell at any price:
 * popular times exist in the Maps app and in no API surface. BestTime is a third party that derives
 * the same signal, so it is a separate provider, a separate key and a separate bill.
 *
 * <h3>The one architectural fact that decides the cost</h3>
 *
 * <b>Every network call costs credits.</b> BestTime's own pricing page, read 2026-08-05:
 * <pre>
 *   Venue foot traffic - by name  (POST /forecasts)        2 credits
 *   Venue foot traffic - by ID    (GET  query-week)        1 credit
 * </pre>
 *
 * <p>An earlier version of this class asserted that the GET was "cheap, and re-readable
 * indefinitely" and logged {@code credits=0} on that path. That was wrong, and wrong in the
 * expensive direction: it made every pane view of a known venue cost a credit, for ever, while the
 * log said it cost nothing.
 *
 * <p>So the design changes to match the billing. The POST already returns the WHOLE WEEK - 7 days
 * x 24 hours of intensity - and popular times is a static weekly pattern that BestTime themselves
 * say is "normally accurate for at least several weeks". There is nothing to poll. One POST per
 * venue, cache the week, and index into it on-device at zero recurring cost.
 *
 * <h3>Nothing here is on a hot path</h3>
 *
 * {@link #cachedSummary} is a map lookup and never touches the network; it is what the pane calls
 * from {@code getTemplate()}. The network work happens on a background thread, once per venue, and
 * only after the driver has opened that place's pane - never speculatively, never per map
 * interaction, never per keystroke.
 *
 * <h3>Egypt coverage is unproven, and that is handled rather than assumed</h3>
 *
 * PROVIDER_FEATURES.md records that BestTime's Cairo coverage could not be verified - every example
 * in their own material is US-based, and thin-coverage countries are where this class of dataset is
 * usually empty. So a venue that resolves to nothing is remembered as {@link #NO_DATA} and never
 * asked about again, and the pane simply omits the row. The feature degrading to invisible is the
 * correct outcome for a provider that may have nothing to say about this city; the log line says
 * which it was, so one drive answers the question that could not be answered from a desk.
 */
public final class BestTimeProvider {

	private static final Log LOG = PlatformUtil.getLog(BestTimeProvider.class);
	private static final String TAG = "CD_BESTTIME";

	private static final String BASE = "https://besttime.app/api/v1";
	private static final int TIMEOUT_MS = 8000;

	/**
	 * Venues that were asked about and have no forecast - see the class comment on coverage.
	 *
	 * <p>A SEPARATE SET rather than a sentinel string in {@link #CACHE}. The sentinel version used
	 * a NUL byte to make collision with a real value impossible, which worked and made the source
	 * file binary - grep skipped it, and the audit that checks every build flag has a reader
	 * silently reported these three as never read. A set has no magic value to collide with and
	 * no byte that breaks a text tool.
	 */
	private static final Set<String> NO_DATA = new HashSet<>();

	/**
	 * Bounded so a long drive with a curious driver cannot grow it without limit. LRU by insertion
	 * order; 64 venues is far more than one drive visits and costs a few kilobytes.
	 */
	private static final int MAX_CACHE = 64;

	private static final Map<String, String> CACHE =
			new LinkedHashMap<String, String>(16, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
					return size() > MAX_CACHE;
				}
			};

	/** Venues a request is already in flight for, so a rebuilding pane cannot double-spend. */
	private static final Map<String, Boolean> IN_FLIGHT = new LinkedHashMap<>();

	private BestTimeProvider() {
	}

	public static boolean isEnabled() {
		return BuildConfig.CAIRODRIVE_BESTTIME
				&& !Algorithms.isEmpty(BuildConfig.CAIRODRIVE_BESTTIME_PRIVATE_KEY);
	}

	/**
	 * The line to show, or null.
	 *
	 * <p>Pure cache read - no network, no allocation beyond the key. Safe to call from
	 * {@code getTemplate()}, which is a host callback on the car thread.
	 *
	 * <p>Kicks off exactly one background fetch the first time it is asked about a venue it does
	 * not know, then returns null; when that lands the caller's next rebuild finds it.
	 */
	@Nullable
	public static String cachedSummary(@Nullable OsmandApplication app, @Nullable String name,
	                                   @Nullable String address) {
		if (app == null || !isEnabled() || Algorithms.isEmpty(name)) {
			return null;
		}
		String key = cacheKey(name, address);
		String cached;
		synchronized (CACHE) {
			if (NO_DATA.contains(key)) {
				return null;
			}
			cached = CACHE.get(key);
		}
		if (cached != null) {
			return cached;
		}
		synchronized (IN_FLIGHT) {
			if (IN_FLIGHT.containsKey(key)) {
				return null;
			}
			IN_FLIGHT.put(key, Boolean.TRUE);
		}
		String venueName = name;
		String venueAddress = address == null ? "" : address;
		new Thread(() -> {
			String result = null;
			try {
				result = fetch(app, venueName, venueAddress);
			} catch (Throwable t) {
				// A popular-times row is never allowed to be the thing that breaks a pane.
				LOG.error("BestTime fetch failed", t);
				log("error venue=" + venueName.length() + "ch " + t.getClass().getSimpleName());
			} finally {
				synchronized (CACHE) {
					if (result == null) {
						NO_DATA.add(key);
					} else {
						CACHE.put(key, result);
					}
				}
				synchronized (IN_FLIGHT) {
					IN_FLIGHT.remove(key);
				}
			}
		}, "cd-besttime").start();
		return null;
	}

	/**
	 * Length-prefixed so the join is unambiguous without a separator byte.
	 *
	 * <p>{@code "ab" + "c"} and {@code "a" + "bc"} produce the same string under naive
	 * concatenation and would share a cache entry. Prefixing the name's length distinguishes them
	 * using only printable characters - the previous version reached for a control byte, which is
	 * what made this file unreadable to grep.
	 */
	private static String cacheKey(@NonNull String name, @Nullable String address) {
		return name.length() + ":" + name + (address == null ? "" : address);
	}

	/**
	 * Generate once with the private key, then read the current hour.
	 *
	 * <p>The POST returns the whole week, so it is stored whole and every later lookup is a local
	 * array index. There is no recurring network call and therefore no recurring credit - which is
	 * the correction over the previous design, where a cold start re-read by id at 1 credit a
	 * time.
	 */
	@Nullable
	private static String fetch(@NonNull OsmandApplication app, @NonNull String name,
	                            @NonNull String address) throws Exception {
		String key = cacheKey(name, address);

		// A week already stored from an earlier run - possibly days ago, across restarts. Indexing
		// it costs nothing and makes the whole feature free after one POST per venue. This replaces
		// a GET-by-id, which BestTime bills at 1 credit and which the previous version logged as
		// credits=0.
		int[] week = storedWeek(app, key);
		if (week != null) {
			Integer now = intensityFromWeek(week);
			if (now != null) {
				log("cached venue=" + name.length() + "ch intensity=" + now + " credits=0 net=0");
				return app.getString(describe(now));
			}
		}

		String url = BASE + "/forecasts?api_key_private="
				+ enc(BuildConfig.CAIRODRIVE_BESTTIME_PRIVATE_KEY)
				+ "&venue_name=" + enc(name)
				+ "&venue_address=" + enc(address);
		long start = System.currentTimeMillis();
		String body = post(url);
		long ms = System.currentTimeMillis() - start;
		if (body == null) {
			log("miss venue=" + name.length() + "ch ms=" + ms);
			return null;
		}
		JSONObject root = new JSONObject(body);
		// BestTime answers a venue it cannot find with status "error" and a 200, so the HTTP code
		// is not the test - this is.
		String status = root.optString("status", "");
		if (!"OK".equalsIgnoreCase(status)) {
			log("nodata venue=" + name.length() + "ch status=" + status + " ms=" + ms);
			return null;
		}
		rememberWeek(app, key, root);
		Integer intensity = currentIntensity(root);
		if (intensity == null) {
			log("nohour venue=" + name.length() + "ch ms=" + ms);
			return null;
		}
		log("generated venue=" + name.length() + "ch intensity=" + intensity + " ms=" + ms
				+ " credits=2 net=1 weekCached=1");
		return app.getString(describe(intensity));
	}



	/**
	 * The 7x24 intensity grid for the hour the car is in now, from a stored week.
	 *
	 * <p>Same day/hour convention as {@link #currentIntensity}: Monday=0, and the published window
	 * starts at 06:00, so index 0 is 06:00 and hours before it are outside what this data claims to
	 * describe.
	 */
	@Nullable
	static Integer intensityFromWeek(@NonNull int[] week) {
		Calendar now = Calendar.getInstance();
		int dayInt = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7;
		int index = now.get(Calendar.HOUR_OF_DAY) - 6;
		if (index < 0 || index > 23) {
			return null;
		}
		int slot = dayInt * 24 + index;
		if (slot < 0 || slot >= week.length || week[slot] < 0) {
			return null;
		}
		return week[slot];
	}

	/**
	 * Flattens the whole 7x24 week out of a forecast response and persists it under {@code key}.
	 *
	 * <h3>Why the whole week and not just the hour that was asked for</h3>
	 *
	 * BestTime bills the by-name POST at 2 credits and the by-id GET at 1, and there is no free
	 * read of either. But a single POST already returns the complete week - so storing all 168
	 * hours makes every later question about that venue, on any day, cost nothing at all. Keeping
	 * only the current hour would have meant paying again tomorrow for data already in hand.
	 *
	 * <p>Stored as a flat 168-int array indexed {@code dayInt * 24 + (hour - 6)}, matching
	 * {@link #intensityFromWeek}. Hours the response does not cover are stored as -1 and read back
	 * as unknown, so a partial week is kept rather than discarded - the missing hours simply fall
	 * through to a fresh fetch while the present ones stay free.
	 *
	 * <p>Failures are swallowed. This is a cache write: losing it costs 2 credits next time, while
	 * throwing here would fail a lookup that has already succeeded.
	 */
	private static void rememberWeek(@NonNull OsmandApplication app, @NonNull String key,
	                                 @NonNull JSONObject root) {
		try {
			JSONObject analysisHolder = root.optJSONObject("analysis");
			JSONArray analysis = analysisHolder != null
					? analysisHolder.optJSONArray("week_raw") : root.optJSONArray("analysis");
			if (analysis == null) {
				return;
			}
			JSONArray week = new JSONArray();
			for (int i = 0; i < 168; i++) {
				week.put(-1);
			}
			boolean any = false;
			for (int i = 0; i < analysis.length(); i++) {
				JSONObject day = analysis.optJSONObject(i);
				if (day == null) {
					continue;
				}
				JSONObject info = day.optJSONObject("day_info");
				int dayInt = info != null ? info.optInt("day_int", -1) : day.optInt("day_int", -1);
				JSONArray raw = day.optJSONArray("day_raw");
				if (dayInt < 0 || dayInt > 6 || raw == null) {
					continue;
				}
				for (int hour = 0; hour < 24 && hour < raw.length(); hour++) {
					int value = raw.optInt(hour, -1);
					if (value >= 0) {
						week.put(dayInt * 24 + hour, value);
						any = true;
					}
				}
			}
			if (!any) {
				return;
			}

			String raw = app.getSettings().BESTTIME_VENUE_IDS.get();
			JSONObject store = Algorithms.isEmpty(raw) ? new JSONObject() : new JSONObject(raw);
			// Bounded so a long-lived install cannot grow this preference without limit. Oldest
			// first: JSONObject preserves insertion order for keys read back from a string, so
			// dropping from the front evicts the least recently fetched venue.
			while (store.length() >= MAX_VENUE_IDS) {
				java.util.Iterator<String> keys = store.keys();
				if (!keys.hasNext()) {
					break;
				}
				store.remove(keys.next());
			}
			store.put(key, week);
			app.getSettings().BESTTIME_VENUE_IDS.set(store.toString());
		} catch (Exception e) {
			log("weekStoreFailed " + e.getClass().getSimpleName());
		}
	}

	@Nullable
	private static int[] storedWeek(@NonNull OsmandApplication app, @NonNull String key) {
		String raw = app.getSettings().BESTTIME_VENUE_IDS.get();
		if (Algorithms.isEmpty(raw)) {
			return null;
		}
		try {
			JSONArray stored = new JSONObject(raw).optJSONArray(key);
			if (stored == null || stored.length() != 168) {
				return null;
			}
			int[] week = new int[168];
			for (int i = 0; i < 168; i++) {
				week[i] = stored.optInt(i, -1);
			}
			return week;
		} catch (Exception e) {
			return null;
		}
	}


	private static final int MAX_VENUE_IDS = 200;

	/**
	 * The intensity for the hour the car is in now.
	 *
	 * <p>BestTime's {@code analysis} array is one entry per day of week with
	 * {@code day_info.day_int} 0=Monday..6=Sunday, and {@code day_raw} is 24 values starting at
	 * 06:00 rather than at midnight - a detail that is easy to miss and produces a plausible,
	 * wrong answer six hours out of phase. Hours before 06:00 fall outside the published window
	 * and are reported as unknown rather than wrapped, because a venue's overnight busyness is not
	 * something this data claims to describe.
	 */
	@Nullable
	static Integer currentIntensity(@NonNull JSONObject root) {
		JSONObject analysisHolder = root.optJSONObject("analysis");
		JSONArray analysis = analysisHolder != null
				? analysisHolder.optJSONArray("week_raw") : root.optJSONArray("analysis");
		if (analysis == null) {
			return null;
		}
		Calendar now = Calendar.getInstance();
		// Calendar.MONDAY is 2 and SUNDAY is 1; BestTime uses Monday=0.
		int dayInt = (now.get(Calendar.DAY_OF_WEEK) + 5) % 7;
		int hour = now.get(Calendar.HOUR_OF_DAY);
		int index = hour - 6;
		if (index < 0 || index > 23) {
			return null;
		}
		for (int i = 0; i < analysis.length(); i++) {
			JSONObject day = analysis.optJSONObject(i);
			if (day == null) {
				continue;
			}
			JSONObject info = day.optJSONObject("day_info");
			int thisDay = info != null ? info.optInt("day_int", -1) : day.optInt("day_int", -1);
			if (thisDay != dayInt) {
				continue;
			}
			JSONArray raw = day.optJSONArray("day_raw");
			if (raw == null || index >= raw.length()) {
				return null;
			}
			return raw.optInt(index, -1) < 0 ? null : raw.optInt(index);
		}
		return null;
	}

	/**
	 * Intensity to one of four words.
	 *
	 * <p>Four buckets, not a percentage. "68% busy" is a number the driver has to interpret at
	 * 60 km/h and which implies a precision this data does not have; "busy" is the whole content
	 * of the answer. The thresholds are BestTime's own documented bands.
	 */
	static int describe(int intensity) {
		if (intensity <= 20) {
			return R.string.cairodrive_popular_quiet;
		}
		if (intensity <= 50) {
			return R.string.cairodrive_popular_steady;
		}
		if (intensity <= 80) {
			return R.string.cairodrive_popular_busy;
		}
		return R.string.cairodrive_popular_very_busy;
	}

	// ---------------------------------------------------------------- transport

	@Nullable
	private static String post(@NonNull String url) throws Exception {
		HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
		try {
			conn.setRequestMethod("POST");
			conn.setConnectTimeout(TIMEOUT_MS);
			conn.setReadTimeout(TIMEOUT_MS);
			conn.setDoOutput(true);
			conn.setFixedLengthStreamingMode(0);
			conn.connect();
			int code = conn.getResponseCode();
			InputStream in = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
			if (in == null) {
				return null;
			}
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buffer = new byte[4096];
			int read;
			while ((read = in.read(buffer)) > 0) {
				out.write(buffer, 0, read);
			}
			return code >= 400 ? null : out.toString("UTF-8");
		} finally {
			conn.disconnect();
		}
	}

	@Nullable

	private static String enc(String value) throws Exception {
		return URLEncoder.encode(value, "UTF-8");
	}

	/**
	 * Venue NAMES are never logged, only their lengths.
	 *
	 * <p>A drive log already carries a continuous position trace; it does not also need a list of
	 * every business the owner looked up. The length is enough to correlate a line with a pane
	 * without recording what the place was.
	 */
	private static void log(String message) {
		if (CairoDriveLogger.isEnabled()) {
			CairoDriveLogger.getInstance().log(TAG, message);
		}
	}

	/** Test seam: lets the bucket boundaries be exercised without a network or a device. */
	static String debugDescribe(int intensity) {
		return String.format(Locale.US, "%d->%d", intensity, describe(intensity));
	}
}
