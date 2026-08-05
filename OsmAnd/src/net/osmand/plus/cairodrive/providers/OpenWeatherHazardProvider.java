package net.osmand.plus.cairodrive.providers;

import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.osm.io.NetworkUtils;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.Version;
import net.osmand.plus.api.SettingsAPI;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.util.EnumSet;
import java.util.Locale;

/**
 * Dust and reduced visibility over Cairo, from OpenWeather's free endpoints.
 *
 * <h3>Why OpenWeather serves this slot and nothing else does</h3>
 *
 * Every weather API returns rain, wind and temperature, none of which a driver in Cairo needs a
 * banner for. The one condition that actually changes how this city drives is airborne dust - a
 * khamsin afternoon drops visibility to a few hundred metres on the Ring Road with no warning from
 * anything the app already knows. OpenWeather is the only free provider whose condition vocabulary
 * names it: the 7xx "atmosphere" group carries 731 sand/dust whirls, 751 sand and 761 dust as
 * first-class codes. Tomorrow.io and the other candidates from the provider audit have no
 * equivalent - dust simply is not in their enumeration, so no amount of parsing recovers it.
 *
 * <h3>Two of three signals, and why a single one is not allowed to raise the banner</h3>
 *
 * The failure this design is built around is not a missed dust storm, it is a false one. A banner
 * that cries dust on a clear day is read once, disbelieved the second time and ignored from then
 * on - at which point the real khamsin warning is worth less than no banner at all, because the
 * driver has been trained to skip it. Every one of the three signals available here is individually
 * unreliable:
 *
 * <ul>
 *   <li>the <b>condition code</b> is a single station's observation, and a 761 that lingers in the
 *       feed after the air has cleared is common;</li>
 *   <li>the <b>PM10:PM2.5 ratio</b> is the best free programmatic fingerprint of mineral dust -
 *       wind-blown crustal material is coarse, whereas Cairo's permanent traffic and combustion
 *       haze is fine - but a construction site or an agricultural burn upwind moves it too;</li>
 *   <li><b>visibility</b> drops for fog, smoke and the winter inversion just as readily as for
 *       dust.</li>
 * </ul>
 *
 * Requiring any TWO to agree is what turns three noisy indicators into one trustworthy one. It is
 * a deliberate trade: a genuine event that only trips one signal produces no banner, which is the
 * harmless direction to fail in.
 *
 * <h3>Cost and safety</h3>
 *
 * Two gates, as everywhere in this fork: a key compiled into {@code BuildConfig} AND
 * {@code CAIRODRIVE_WEATHER_HAZARD} on. Without both, {@link #isAvailable} is false, the provider
 * never wins {@link CairoDriveProviders.Capability#WEATHER_HAZARD}, and not one request is made.
 * Polling is every 30 minutes with the last poll time PERSISTED, capped per UTC day, and runs
 * entirely on a background thread. Every network path swallows its own exceptions and returns;
 * nothing here can reach navigation.
 *
 * @see CairoDriveProviders
 */
public final class OpenWeatherHazardProvider implements CairoDriveProviders.Provider {

	private static final Log LOG = PlatformUtil.getLog(OpenWeatherHazardProvider.class);
	private static final String TRACE_TAG = "CD_WEATHER";

	private static final String WEATHER_API = "https://api.openweathermap.org/data/2.5/weather";
	private static final String AIR_API = "https://api.openweathermap.org/data/2.5/air_pollution";

	// ------------------------------------------------------------------ condition codes
	// OpenWeather's 7xx "atmosphere" group. Split into two sets because they mean different
	// things to a driver: the first four are airborne mineral dust, the second three are
	// obscuration by something else. Mixing them would let a foggy Delta morning raise a dust
	// warning, and the whole point of this class is that the banner stays believable.

	/** 731 sand/dust whirls. */
	private static final int CODE_SAND_DUST_WHIRLS = 731;
	/** 751 sand. */
	private static final int CODE_SAND = 751;
	/** 761 dust. */
	private static final int CODE_DUST = 761;
	/** 771 squalls - the leading edge of a khamsin front, which is what raises the dust. */
	private static final int CODE_SQUALLS = 771;

	/** 711 smoke. */
	private static final int CODE_SMOKE = 711;
	/** 721 haze. */
	private static final int CODE_HAZE = 721;
	/** 741 fog. */
	private static final int CODE_FOG = 741;

	// ------------------------------------------------------------------ detector thresholds

	/**
	 * PM10:PM2.5 above this counts as the coarse-particle signal.
	 *
	 * <p><b>This is a starting guess, not a measured value.</b> The reasoning behind 3.0: Cairo's
	 * background aerosol is a mix of traffic and combustion, which is fine-mode and sits closer to
	 * 2:1, while wind-blown crustal dust is overwhelmingly coarse and pushes the ratio well past
	 * 4:1. 3.0 is placed between those two, and no drive has yet been logged through an actual dust
	 * event to say whether it lands in the right place.
	 *
	 * <p>TUNE IT FROM DRIVE LOGS, not from reasoning. Every poll writes the raw PM values and the
	 * computed ratio to CD_WEATHER precisely so that after a khamsin the correct threshold can be
	 * read off the log rather than argued about. Until then, treat a banner that fired on this
	 * signal as provisional.
	 */
	private static final double DUST_PM10_PM25_RATIO = 3.0;

	/**
	 * PM10 floor, ug/m3, below which the ratio is not consulted at all.
	 *
	 * <p>A ratio computed from two small numbers is arithmetic, not evidence: 6 over 1.5 is 4.0 and
	 * means clean air, yet it clears the ratio threshold comfortably. Requiring an absolute PM10
	 * load as well keeps the signal to air that is genuinely dirty. Also a starting guess - the WHO
	 * 24-hour guideline is 45 and Cairo routinely exceeds it on an ordinary day, so this is set
	 * above the everyday background rather than at any health threshold.
	 */
	private static final double DUST_PM10_FLOOR_UGM3 = 75.0;

	/**
	 * OpenWeather clamps reported visibility at exactly this value, so 10000 means "10 km or
	 * better" and carries no information. Anything AT the clamp must never be read as a measurement
	 * - it is the single easiest way to turn a clear day into a hazard warning.
	 */
	private static final int VISIBILITY_CLAMP_M = 10000;

	/**
	 * At or below this, visibility counts as the third signal. Well under the clamp on purpose:
	 * 8 km is a hazy Cairo morning and would fire almost daily, which would effectively reduce the
	 * two-of-three rule to one-of-two.
	 */
	private static final int VISIBILITY_LOW_M = 5000;

	/**
	 * Visibility this bad is a driving hazard whatever caused it, so a banner raised on it is
	 * amber rather than informational.
	 */
	private static final int VISIBILITY_SEVERE_M = 2000;

	// ------------------------------------------------------------------ banner text keys

	/**
	 * String resource KEYS, not text. The contract stores a key so the banner is resolved in
	 * whatever locale is current at draw time - this app is Arabic and English, and a sentence
	 * formatted when the network call returned would be pinned to whichever locale happened to be
	 * active then.
	 */
	static final String TEXT_KEY_DUST = "cairo_hazard_dust";
	static final String TEXT_KEY_LOW_VISIBILITY = "cairo_hazard_low_visibility";

	// ------------------------------------------------------------------ polling and budget

	/** Dust is a weather-front timescale phenomenon; polling faster would buy nothing. */
	private static final long POLL_INTERVAL_MS = 10 * 60 * 1000L;

	/**
	 * Polls per UTC day, each spending at most two requests (weather + air quality).
	 *
	 * <p>48 x 2 = 96 against OpenWeather's free 1,000 calls a day, so a fully saturated day sits
	 * around a tenth of the allowance. The cap is not really defending against the 30-minute
	 * interval, which cannot exceed 48 on its own - it defends against the restart case. Android
	 * Auto reconnecting, or the app being killed and relaunched on a drive, resets any in-memory
	 * throttle to zero, and "poll once at trip start" then means once per launch. The persisted
	 * last-poll time below is the first line of defence and this is the backstop behind it.
	 */
	private static final int POLL_DAILY_CAP = 150;

	private static final int CONNECT_TIMEOUT_MS = 8000;
	private static final int READ_TIMEOUT_MS = 12000;

	/**
	 * Budget counters, kept in the global preference file directly rather than as fields on
	 * {@code OsmandSettings}.
	 *
	 * <p>Self-contained on purpose: the whole feature is then one file plus a build flag, and there
	 * is no way to ship the provider while forgetting the two preferences that bound its bill.
	 * {@code SharedPreferences} is thread-safe, which the preference registry is not, and these are
	 * written from a background thread.
	 */
	private static final String PREF_POLL_DAY = "cairodrive_openweather_poll_day";
	private static final String PREF_POLL_COUNT = "cairodrive_openweather_poll_count";
	private static final String PREF_LAST_POLL_MS = "cairodrive_openweather_last_poll_ms";

	// ------------------------------------------------------------------ state

	private static final OpenWeatherHazardProvider INSTANCE = new OpenWeatherHazardProvider();

	/** Guards against two fixes milliseconds apart each starting a fetch. */
	private static volatile boolean inFlight;
	/**
	 * In-memory throttle only, so the per-fix gate never touches storage - the caller is the
	 * location update path. The authoritative check is the persisted timestamp, read on the worker.
	 */
	private static volatile long lastAttemptMs;
	private static volatile boolean budgetExhaustedLogged;

	/** Last banner actually published, so the map is only refreshed when something changed. */
	private static volatile String publishedTextKey = "";
	private static volatile int publishedSeverity = CairoDriveProviders.HazardBanner.SEVERITY_NONE;

	private OpenWeatherHazardProvider() {
	}

	/** The single registered instance. Register it with {@link CairoDriveProviders#register}. */
	@NonNull
	public static OpenWeatherHazardProvider getInstance() {
		return INSTANCE;
	}

	// ------------------------------------------------------------------ Provider contract

	@NonNull
	@Override
	public String name() {
		return CairoDriveProviders.NAME_OPENWEATHER;
	}

	/**
	 * Both gates ANDed. A build with no key makes zero network calls whatever the flag says,
	 * because an unavailable provider never wins the capability and is therefore never polled.
	 */
	@Override
	public boolean isAvailable(@NonNull OsmandApplication app) {
		try {
			return BuildConfig.CAIRODRIVE_WEATHER_HAZARD
					&& !Algorithms.isEmpty(BuildConfig.CAIRODRIVE_OPENWEATHER_KEY)
					&& app.getSettings().WEATHER_HAZARD_ON.get();
		} catch (Throwable t) {
			// The contract says a thrower is treated as unavailable; say so rather than letting it
			// look like a deliberately disabled feature in the log.
			LOG.info(TRACE_TAG + " availability check failed - treating as unavailable", t);
			return false;
		}
	}

	@NonNull
	@Override
	public EnumSet<CairoDriveProviders.Capability> capabilities() {
		return EnumSet.of(CairoDriveProviders.Capability.WEATHER_HAZARD);
	}

	// ------------------------------------------------------------------ entry point

	/**
	 * Called on every GPS fix. Cheap bail-outs first, in the order that rejects most drives
	 * soonest, so the ordinary case - feature off - costs a couple of comparisons per fix.
	 *
	 * <p>Nothing here touches storage or the network. The persisted budget, the persisted poll time
	 * and both requests all happen on the worker thread, because this runs on the location update
	 * path and the frame budget on the head unit is already 46.9 ms.
	 *
	 * @param location the current fix; only its coordinates are used, rounded before they are sent
	 */
	public static void onLocationUpdate(@Nullable OsmandApplication app, @Nullable Location location) {
		if (app == null || location == null) {
			return;
		}
		try {
			// The arbitration gate, not merely a courtesy check: a provider that lost WEATHER_HAZARD
			// must make no requests at all. Being ignored on the way out would still drain the
			// losing vendor's quota for the whole drive.
			if (!CairoDriveProviders.isServing(CairoDriveProviders.NAME_OPENWEATHER,
					CairoDriveProviders.Capability.WEATHER_HAZARD)) {
				return;
			}
			long now = System.currentTimeMillis();
			if (inFlight || !due(lastAttemptMs, now)) {
				return;
			}
			OsmandSettings settings = app.getSettings();
			if (settings == null || !settings.isInternetConnectionAvailable()) {
				return;
			}
			synchronized (OpenWeatherHazardProvider.class) {
				// Re-tested under the lock: two fixes can both clear the check above.
				if (inFlight || !due(lastAttemptMs, now)) {
					return;
				}
				// Advanced before the fetch rather than after it, so a run of failures retries every
				// 30 minutes instead of on every single fix.
				lastAttemptMs = now;
				inFlight = true;
			}

			// Two decimal places is about 1.1 km, which is finer than any weather or air-quality
			// grid this will be sampled against, and it means the driver's exact position is never
			// handed to a third party. Locale.US because the app runs in Arabic: the default locale
			// would format these with Arabic-Indic digits and OpenWeather would reject the URL.
			String lat = String.format(Locale.US, "%.2f", location.getLatitude());
			String lon = String.format(Locale.US, "%.2f", location.getLongitude());

			Thread worker = new Thread(() -> {
				try {
					Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
					poll(app, lat, lon);
				} catch (Throwable t) {
					LOG.info(TRACE_TAG + " poll failed", t);
				} finally {
					// In a finally: a throw here must not wedge the feature off for the process
					// lifetime.
					inFlight = false;
				}
			}, "cairodrive-weather");
			worker.setPriority(Thread.MIN_PRIORITY);
			worker.start();
		} catch (Throwable t) {
			LOG.info(TRACE_TAG + " onLocationUpdate failed", t);
			inFlight = false;
		}
	}

	/**
	 * True when a poll is allowed again.
	 *
	 * <p>A negative age means the clock moved backwards - NTP correcting a phone that booted with a
	 * bad RTC - and is treated as due. The alternative, waiting for the stored future timestamp to
	 * arrive, would silently disable the feature for however far the clock jumped.
	 */
	private static boolean due(long lastMs, long now) {
		if (lastMs <= 0) {
			return true;
		}
		long age = now - lastMs;
		return age < 0 || age >= POLL_INTERVAL_MS;
	}

	// ------------------------------------------------------------------ the poll

	/** Runs on the worker thread: budget, both requests, evaluation, publish, log. */
	private static void poll(@NonNull OsmandApplication app, @NonNull String lat, @NonNull String lon) {
		if (!claimPoll(app)) {
			return;
		}
		Reading reading = new Reading();
		if (!fetchWeather(app, lat, lon, reading)) {
			// Without the condition code and visibility only one signal can ever fire, so there is
			// nothing to decide. Returning leaves any existing banner to expire on the contract's
			// TTL rather than blanking a live warning because one request timed out in a tunnel.
			return;
		}
		// A failure here is survivable: the remaining two signals can still agree. Deliberately not
		// aborting on it, and deliberately not counting a missing signal as a firing one.
		fetchAirQuality(app, lat, lon, reading);
		// Read, never fetched: this is whatever the independent provider last saw on its own
		// schedule. Making the dust decision wait on a second network round trip would delay the
		// warning it is there to make more trustworthy.
		reading.secondOpinion = TomorrowWeatherProvider.dustCorroboration();

		CairoDriveProviders.HazardBanner banner = evaluate(reading);
		CairoDriveLogger.getInstance().log(TRACE_TAG, describe(reading, banner));
		publish(app, banner);
	}

	/**
	 * Puts the verdict into the shared hazard slot without trampling a banner this provider does not
	 * own.
	 *
	 * <h3>The collision this exists to avoid</h3>
	 *
	 * WEATHER_HAZARD and SUN_GLARE are two capabilities, but the contract has ONE
	 * {@code publishHazard} slot and {@code SunGlareProvider} writes to it too. The cadences are
	 * wildly different - glare re-evaluates on every fix, this provider polls twice an hour - so an
	 * unconditional {@code publishHazard(evaluate(...))} here does real damage in one direction:
	 * a clear-air poll returns null and would ERASE a live glare warning that a driver is looking
	 * into at that moment, on a signal that says nothing whatever about the sun.
	 *
	 * <p>So a null verdict only ever clears a banner raised by THIS provider. A non-null one does
	 * take the slot, and that asymmetry is deliberate rather than an oversight: dust is the more
	 * dangerous of the two, and it is also physically the correct precedence, because air thick
	 * enough to raise a dust warning scatters the direct solar beam into a diffuse smear that is no
	 * longer a glare source. {@code SunGlareProvider.publish} yields from its side for the same
	 * reason, so neither provider can starve the other.
	 */
	private static void publish(@NonNull OsmandApplication app,
	                            @Nullable CairoDriveProviders.HazardBanner banner) {
		if (banner == null) {
			CairoDriveProviders.HazardBanner current = CairoDriveProviders.getHazard();
			if (current != null && !isOurs(current)) {
				// Someone else's warning. Forget ours rather than clearing theirs - and do not refresh
				// the map, because nothing on screen changed.
				publishedTextKey = "";
				publishedSeverity = CairoDriveProviders.HazardBanner.SEVERITY_NONE;
				return;
			}
		}
		CairoDriveProviders.publishHazard(banner);

		String key = banner != null ? banner.textKey : "";
		int severity = banner != null
				? banner.severity : CairoDriveProviders.HazardBanner.SEVERITY_NONE;
		if (!key.equals(publishedTextKey) || severity != publishedSeverity) {
			publishedTextKey = key;
			publishedSeverity = severity;
			// Only on a change. The map redraws itself constantly while driving anyway; forcing a
			// refresh twice an hour to re-draw an identical banner is work for nothing.
			refreshMap(app);
		}
	}

	/**
	 * Whether a banner in the shared slot came from this provider, decided on the text key rather
	 * than on remembered state. State can be stale - the slot may have expired on its TTL or been
	 * taken by another provider since the last poll - whereas the key is what is actually there.
	 */
	static boolean isOurs(@Nullable CairoDriveProviders.HazardBanner banner) {
		return banner != null
				&& (TEXT_KEY_DUST.equals(banner.textKey)
				|| TEXT_KEY_LOW_VISIBILITY.equals(banner.textKey));
	}

	/**
	 * The budget accountant, and the authoritative poll-interval check.
	 *
	 * <p>Both counters live in the global preference file, so killing the app does not reset the
	 * budget and relaunching does not re-poll. Runs on the worker thread because it commits to
	 * storage.
	 *
	 * @return true when this poll may spend its requests
	 */
	private static boolean claimPoll(@NonNull OsmandApplication app) {
		try {
			OsmandSettings settings = app.getSettings();
			SettingsAPI api = settings.getSettingsAPI();
			Object prefs = settings.getPreferences(true);
			long now = System.currentTimeMillis();

			long lastPoll = api.getLong(prefs, PREF_LAST_POLL_MS, 0);
			if (!due(lastPoll, now)) {
				// The in-memory throttle was reset by a restart but the real one was not. This is
				// the common case on a drive where Android Auto reconnects, and it must cost
				// nothing beyond one preference read.
				return false;
			}

			// Same UTC day key as GoogleTrafficHelper, for the same reason: it needs no calendar,
			// no timezone and no date parsing, and it rolls at a fixed instant worldwide.
			int today = (int) (now / 86_400_000L);
			int day = api.getInt(prefs, PREF_POLL_DAY, 0);
			int used = api.getInt(prefs, PREF_POLL_COUNT, 0);
			if (day != today) {
				day = today;
				used = 0;
				budgetExhaustedLogged = false;
			}
			if (used >= POLL_DAILY_CAP) {
				if (!budgetExhaustedLogged) {
					budgetExhaustedLogged = true;
					CairoDriveLogger.getInstance().log(TRACE_TAG, "daily budget spent (" + used
							+ "/" + POLL_DAILY_CAP + " polls) - no further requests until the UTC day rolls");
				}
				return false;
			}
			api.edit(prefs)
					.putInt(PREF_POLL_DAY, day)
					.putInt(PREF_POLL_COUNT, used + 1)
					.putLong(PREF_LAST_POLL_MS, now)
					.commit();
			return true;
		} catch (Throwable t) {
			// Fail CLOSED. An accountant that cannot count must not authorise spending - the
			// failure mode of guessing "probably fine" here is an uncapped request rate against a
			// third-party quota.
			LOG.info(TRACE_TAG + " budget check failed - skipping poll", t);
			return false;
		}
	}

	// ------------------------------------------------------------------ network

	/**
	 * Current conditions: the condition code and the reported visibility, i.e. two of the three
	 * signals.
	 *
	 * @return true when the response was parsed and {@code reading} carries a condition code
	 */
	private static boolean fetchWeather(@NonNull OsmandApplication app, @NonNull String lat,
	                                    @NonNull String lon, @NonNull Reading reading) {
		// lang=ar only affects the human-readable description, which is logged and never shown -
		// the banner text comes from string resources so it follows the app locale at draw time.
		String url = WEATHER_API + "?lat=" + lat + "&lon=" + lon
				+ "&units=metric&lang=ar&appid=" + BuildConfig.CAIRODRIVE_OPENWEATHER_KEY;
		String body = request(app, url, "weather");
		if (body == null) {
			return false;
		}
		try {
			JSONObject root = new JSONObject(body);
			JSONArray weather = root.optJSONArray("weather");
			if (weather != null) {
				for (int i = 0; i < weather.length(); i++) {
					JSONObject entry = weather.optJSONObject(i);
					if (entry == null) {
						continue;
					}
					int code = entry.optInt("id", 0);
					if (i == 0) {
						reading.conditionCode = code;
						reading.description = entry.optString("description", "");
					}
					// Every entry is scanned, not just the first. OpenWeather reports the dominant
					// condition first, so a sky that is mostly clear with dust blowing through can
					// carry 800 at index 0 and 761 behind it - reading only [0] would miss exactly
					// the onset this provider exists to catch.
					if (isDustCode(code)) {
						reading.dustCode = true;
					}
					if (isObscurationCode(code)) {
						reading.obscurationCode = true;
					}
				}
			}
			// Absent on some responses. -1 keeps "not reported" distinct from "reported as 0",
			// which would otherwise read as zero visibility and fire the signal at full strength.
			reading.visibilityM = root.optInt("visibility", -1);
			reading.haveWeather = true;
			return true;
		} catch (Throwable t) {
			LOG.info(TRACE_TAG + " could not parse weather response", t);
			return false;
		}
	}

	/** Air quality: the PM10:PM2.5 ratio, i.e. the third signal. Optional - failure is survivable. */
	private static void fetchAirQuality(@NonNull OsmandApplication app, @NonNull String lat,
	                                    @NonNull String lon, @NonNull Reading reading) {
		String url = AIR_API + "?lat=" + lat + "&lon=" + lon
				+ "&appid=" + BuildConfig.CAIRODRIVE_OPENWEATHER_KEY;
		String body = request(app, url, "air_pollution");
		if (body == null) {
			return;
		}
		try {
			JSONArray list = new JSONObject(body).optJSONArray("list");
			JSONObject first = list != null && list.length() > 0 ? list.optJSONObject(0) : null;
			if (first == null) {
				return;
			}
			JSONObject components = first.optJSONObject("components");
			if (components == null) {
				return;
			}
			double pm25 = components.optDouble("pm2_5", -1);
			double pm10 = components.optDouble("pm10", -1);
			if (pm25 < 0 || pm10 < 0) {
				return;
			}
			reading.pm25 = pm25;
			reading.pm10 = pm10;
			JSONObject main = first.optJSONObject("main");
			reading.aqi = main != null ? main.optInt("aqi", 0) : 0;
			reading.haveAir = true;
		} catch (Throwable t) {
			LOG.info(TRACE_TAG + " could not parse air quality response", t);
		}
	}

	/**
	 * One GET, fully self-contained: it swallows everything it can throw and returns null.
	 *
	 * @param label endpoint name for the log - the URL itself is NEVER logged, and neither is the
	 *              error body, because the query string carries the API key and a log file is
	 *              pulled off the phone and pasted into a transcript
	 * @return the response body, or null on any failure
	 */
	@Nullable
	private static String request(@NonNull OsmandApplication app, @NonNull String url,
	                              @NonNull String label) {
		HttpURLConnection connection = null;
		try {
			connection = NetworkUtils.getHttpURLConnection(url);
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setRequestProperty("Accept", "application/json");
			// Identifies this build to the vendor, so a misbehaving version can be recognised from
			// their side rather than only from a drive log on this phone.
			connection.setRequestProperty("User-Agent", Version.getFullVersion(app));
			int code = connection.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) {
				// 401 means the key is wrong or not yet activated - OpenWeather takes a couple of
				// hours to enable a new one - and 429 means the free allowance is gone. Both are
				// worth seeing in a drive log, which is why the status is logged even though the
				// body is not.
				ApiHealth.recordFailure(ApiHealth.Api.OPENWEATHER, code, null);
				LOG.info(TRACE_TAG + " " + label + " HTTP " + code);
				return null;
			}
			ApiHealth.recordOk(ApiHealth.Api.OPENWEATHER);
			return read(connection.getInputStream());
		} catch (Throwable t) {
			LOG.info(TRACE_TAG + " " + label + " request failed", t);
			return null;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	// ------------------------------------------------------------------ the detector

	/** One poll's worth of raw observations, before any of it is interpreted. */
	static final class Reading {
		boolean haveWeather;
		/** The dominant condition code, for the log. The signals below are set from ALL entries. */
		int conditionCode;
		boolean dustCode;
		boolean obscurationCode;
		/** Metres, or -1 when not reported. {@link #VISIBILITY_CLAMP_M} means "10 km or better". */
		int visibilityM = -1;
		@NonNull
		String description = "";

		boolean haveAir;
		double pm25;
		double pm10;
		/** OpenWeather's 1-5 index. Logged for context; not one of the three signals. */
		int aqi;

		/**
		 * What a DIFFERENT vendor says about visibility here. Never one of the three signals.
		 *
		 * <p>All three signals above come from one company, one model and one observation network,
		 * so if that source is wrong about this cell they are wrong together and the two-of-three
		 * rule protects against nothing. {@link TomorrowWeatherProvider} supplies an independent
		 * reading, and it is used in exactly one direction - see {@link #evaluate}.
		 */
		@NonNull
		TomorrowWeatherProvider.Corroboration secondOpinion =
				TomorrowWeatherProvider.Corroboration.UNKNOWN;

		/** The coarse-particle fingerprint, or -1 when air quality was not obtained. */
		double pmRatio() {
			return haveAir && pm25 > 0 ? pm10 / pm25 : -1;
		}

		boolean dustCodeSignal() {
			return dustCode;
		}

		/** Coarse particles: a high ratio AND enough absolute load for that ratio to mean anything. */
		boolean pmSignal() {
			return haveAir && pm10 >= DUST_PM10_FLOOR_UGM3 && pm25 > 0
					&& pm10 / pm25 >= DUST_PM10_PM25_RATIO;
		}

		/**
		 * Visibility at or near its floor. The clamp is excluded explicitly - treating the 10000 that
		 * OpenWeather returns for every clear day as a measurement would make this signal fire
		 * constantly and quietly collapse the two-of-three rule into one-of-two.
		 */
		boolean visibilitySignal() {
			return visibilityM >= 0 && visibilityM < VISIBILITY_CLAMP_M && visibilityM <= VISIBILITY_LOW_M;
		}

		/**
		 * How many of the three agree. Counted in one place so the CD_WEATHER line cannot drift out
		 * of step with {@link #evaluate} - a log that disagrees with the decision it is meant to
		 * explain is worse than no log, and this is the number the thresholds get tuned against.
		 */
		int signalCount() {
			return (dustCodeSignal() ? 1 : 0) + (pmSignal() ? 1 : 0) + (visibilitySignal() ? 1 : 0);
		}
	}

	/**
	 * Turns one reading into a banner, or into nothing.
	 *
	 * <p>The two-of-three rule lives here and nowhere else. Package-private so it can be exercised
	 * against recorded readings without a network or a device.
	 *
	 * @return the banner to publish, or null when no hazard is established
	 */
	@Nullable
	static CairoDriveProviders.HazardBanner evaluate(@NonNull Reading reading) {
		if (!reading.haveWeather) {
			return null;
		}
		int fired = reading.signalCount();
		if (fired >= 2) {
			// Amber only when the driver can actually see the problem, or when all three agree.
			// Dust aloft with the road still clear is worth knowing and not worth an amber strip -
			// see the class comment on why over-warning is the expensive mistake here.
			int severity = reading.visibilitySignal() || fired == 3
					? CairoDriveProviders.HazardBanner.SEVERITY_WARN
					: CairoDriveProviders.HazardBanner.SEVERITY_INFO;
			// The independent reading works in ONE direction: it can take the amber strip down to
			// an info line, never the reverse, and never remove the banner altogether.
			//
			// Downgrade-only because the two errors are not symmetrical. If the second vendor is
			// wrong and the air really is thick, the driver still gets a warning, just a quieter
			// one. If it were allowed to UPGRADE, a vendor that reads low on a hazy-but-safe
			// afternoon would raise the amber strip this class exists to keep believable - and it
			// has no dust vocabulary at all, so it would be doing so on a signal that cannot tell
			// dust from smog.
			//
			// UNKNOWN changes nothing whatsoever. A build with no Tomorrow.io key must behave
			// exactly as it did before this provider existed, and reading silence as disagreement
			// is the same fault that has now shipped five times here: a provider sees an empty
			// string and quietly changes what the app does.
			if (severity == CairoDriveProviders.HazardBanner.SEVERITY_WARN
					&& reading.secondOpinion == TomorrowWeatherProvider.Corroboration.DISAGREES) {
				severity = CairoDriveProviders.HazardBanner.SEVERITY_INFO;
			}
			return new CairoDriveProviders.HazardBanner(TEXT_KEY_DUST, severity);
		}
		// Not dust, but fog, smoke or haze thick enough to matter. Still two independent signals -
		// a code from the obscuration group AND a visibility reading that agrees with it - so this
		// is the same rule applied to a different cause, not an exception to it.
		if (reading.obscurationCode && reading.visibilitySignal()) {
			int severity = reading.visibilityM <= VISIBILITY_SEVERE_M
					? CairoDriveProviders.HazardBanner.SEVERITY_WARN
					: CairoDriveProviders.HazardBanner.SEVERITY_INFO;
			return new CairoDriveProviders.HazardBanner(TEXT_KEY_LOW_VISIBILITY, severity);
		}
		return null;
	}

	private static boolean isDustCode(int code) {
		return code == CODE_SAND_DUST_WHIRLS || code == CODE_SAND
				|| code == CODE_DUST || code == CODE_SQUALLS;
	}

	private static boolean isObscurationCode(int code) {
		return code == CODE_SMOKE || code == CODE_HAZE || code == CODE_FOG;
	}

	// ------------------------------------------------------------------ logging

	/**
	 * One CD_WEATHER line per poll, whether or not a banner was raised.
	 *
	 * <p>The no-banner polls are the valuable ones: they are the only record of what an ORDINARY
	 * Cairo day measures, and the thresholds above cannot be tuned without knowing that baseline.
	 * Every input to the decision appears, so a banner can be replayed from the log alone.
	 *
	 * <p>The Arabic description goes last. Mixing RTL text into the middle of a line reorders the
	 * tokens after it when the log is read, which would scramble the numbers this line exists for.
	 */
	@NonNull
	private static String describe(@NonNull Reading reading,
	                               @Nullable CairoDriveProviders.HazardBanner banner) {
		StringBuilder line = new StringBuilder();
		line.append("code=").append(reading.conditionCode);
		line.append(" vis=").append(reading.visibilityM >= 0 ? String.valueOf(reading.visibilityM) : "n/a");
		if (reading.haveAir) {
			line.append(String.format(Locale.US, " pm2_5=%.1f pm10=%.1f aqi=%d",
					reading.pm25, reading.pm10, reading.aqi));
			// A zero PM2.5 makes the ratio undefined rather than infinite. Printing "n/a" keeps that
			// distinguishable in the log from a genuinely computed value, which matters because this
			// number is what the threshold above will eventually be tuned against.
			double ratio = reading.pmRatio();
			line.append(ratio >= 0 ? String.format(Locale.US, " ratio=%.2f", ratio) : " ratio=n/a");
		} else {
			line.append(" pm2_5=n/a pm10=n/a aqi=n/a ratio=n/a");
		}
		line.append(" signals=");
		line.append(reading.dustCodeSignal() ? "code" : "-");
		line.append('+').append(reading.pmSignal() ? "pm" : "-");
		line.append('+').append(reading.visibilitySignal() ? "vis" : "-");
		line.append('(').append(reading.signalCount()).append("/3)");
		// The second vendor, always printed - including UNKNOWN. A drive log where this reads
		// DISAGREES on a day the banner fired is the only way anyone would learn that one of the
		// two sources is wrong about Cairo, and printing it only when it happened to matter would
		// hide the baseline needed to tell which.
		line.append(" 2nd=").append(reading.secondOpinion.name().toLowerCase(Locale.US));
		int independentVis = TomorrowWeatherProvider.lastVisibilityMetres();
		if (independentVis >= 0) {
			line.append('(').append(independentVis).append("m)");
		}
		if (reading.obscurationCode) {
			line.append(" obscuration=yes");
		}
		if (banner != null) {
			line.append(" banner=").append(banner.textKey).append(" severity=").append(banner.severity);
		} else {
			line.append(" banner=none");
		}
		if (!Algorithms.isEmpty(reading.description)) {
			line.append(" desc=").append(reading.description);
		}
		return line.toString();
	}

	// ------------------------------------------------------------------ plumbing

	private static void refreshMap(@NonNull OsmandApplication app) {
		try {
			app.runInUIThread(() -> {
				if (app.getOsmandMap() != null) {
					app.getOsmandMap().refreshMap();
				}
			});
		} catch (Throwable ignored) {
			// A banner that appears one redraw later is not worth a crash on a teardown path.
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
