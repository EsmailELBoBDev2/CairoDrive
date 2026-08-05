package net.osmand.plus.cairodrive.providers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.cairodrive.CairoDriveLogger;

import org.apache.commons.logging.Log;

import java.util.EnumSet;
import java.util.Locale;

/**
 * Low sun straight down the road ahead, computed entirely on this device.
 *
 * <h3>Why this provider is the one that needs no provider</h3>
 *
 * Sun glare is the single best reason anyone would open an Azure Maps account: "sun glare along the
 * route" is a headline feature of its routing tier, and it is the only thing in that product this
 * fork has ever wanted. It is also pure geometry. Where the sun is at a given instant is a function
 * of nothing but the date, the time and the observer's latitude and longitude, all three of which
 * are already sitting in front of us on every GPS fix; where the driver is pointed is the bearing on
 * that same fix. There is no observation to buy.
 *
 * <p>So this implementation is <b>strictly better than the paid one</b>, not merely cheaper. It has
 * no key, spends no quota and makes no network call, which means it keeps working in the Mokattam
 * tunnels, on the Ring Road underpasses and anywhere else the mobile data drops - which, since the
 * warning is worth most on a fast arterial and fast arterials are exactly where coverage is
 * patchiest, is not a marginal difference. A route-planned glare hint from a server also answers a
 * question the driver did not ask: it describes the route as planned, not the road the car is
 * actually pointed down after a wrong turn or a reroute. This recomputes from the live bearing.
 *
 * <p>It is also the only provider here whose accuracy will never regress. No vendor can deprecate an
 * endpoint, change a field name, re-tier a SKU or rate-limit the solstice.
 *
 * <h3>Why it matters in Cairo specifically</h3>
 *
 * Cairo's arterial grid is substantially east-west - Salah Salem, the 6th of October corridor,
 * Al-Haram, the Autostrad - and the city sits at 30 degrees north, so for most of the year the sun
 * rises and sets within about 25 degrees of due east and due west. A driver on those roads at dawn
 * or dusk is looking directly into it, through a windscreen that in this climate is invariably
 * dusty, which scatters the beam across the whole glass instead of leaving it as one bright disc.
 * This is a genuine safety warning, not a convenience.
 *
 * <h3>Why the existing SunriseSunset utility cannot answer this</h3>
 *
 * {@code net.osmand.util.SunriseSunset} is already in the tree and is no help: it returns the times
 * of sunrise and sunset and the booleans around them. Glare needs the sun's <b>position</b> - an
 * azimuth to compare against the bearing and an elevation to decide whether it is low enough to be
 * in the driver's eyes rather than above the visor - and that class exposes neither. Hence the NOAA
 * solar position algorithm implemented below rather than a call into it.
 *
 * <h3>Cost</h3>
 *
 * Free is not the same as zero. The solar position is a few dozen trigonometric evaluations, which
 * is nothing once a minute and is real work at 20 Hz on a Helio G81 whose frame budget is already
 * 46.9 ms with 61% of it in the map-overlay bucket. So the split below is deliberate: the SOLAR
 * position is cached for a minute, while the cheap bearing comparison re-runs on every fix. See
 * {@link #onLocationUpdate}.
 *
 * @see CairoDriveProviders
 */
public final class SunGlareProvider implements CairoDriveProviders.Provider {

	private static final Log LOG = PlatformUtil.getLog(SunGlareProvider.class);
	private static final String TRACE_TAG = "CD_GLARE";

	// ------------------------------------------------------------------ detector thresholds

	/**
	 * Half-width of the glare cone, degrees. The sun counts as "ahead" when the wrap-safe difference
	 * between its azimuth and the driver's bearing is within this.
	 *
	 * <p>25 degrees is a starting figure and is meant to be tuned from CD_GLARE lines, not argued
	 * about. The reasoning for it: a windscreen spans roughly 50 degrees of forward view either side
	 * of centre, but the sun does not have to be in the middle of the glass to be a problem - a low
	 * sun 25 degrees off axis still sits inside the sun visor's blind spot and still washes out the
	 * dust film. Wider than this and the warning starts firing on roads where the sun is safely off
	 * to the side, which is the failure that gets a banner ignored.
	 */
	private static final double GLARE_HALF_ANGLE_DEG = 25.0;

	/**
	 * Apparent elevation above which the sun is high enough that the visor and the roofline deal
	 * with it. Above about 15 degrees the beam arrives from over the top of the windscreen for a
	 * seated driver, so it stops being an eyes-level problem.
	 */
	private static final double GLARE_MAX_ELEVATION_DEG = 15.0;

	/**
	 * Below this the sun has set (or has not risen) and there is nothing to warn about.
	 *
	 * <p>Zero rather than a small negative number because the elevation this is compared against has
	 * already had atmospheric refraction applied, so 0 means "the disc is on the visible horizon"
	 * rather than "the disc is geometrically on the horizon" - the two differ by about 0.57 degrees
	 * and the refracted one is the one that reaches the driver's eyes.
	 */
	private static final double GLARE_MIN_ELEVATION_DEG = 0.0;

	/**
	 * The amber band: the sun both very low and nearly dead ahead. Split out from the informational
	 * case because "the sun is somewhere in front of you" and "you cannot see the car in front of
	 * you" are different messages and should not share a colour.
	 */
	private static final double SEVERE_ELEVATION_DEG = 10.0;
	private static final double SEVERE_HALF_ANGLE_DEG = 12.0;

	/**
	 * Below this speed the bearing is not trusted, metres per second (about 7 km/h).
	 *
	 * <p>The trap: a GNSS bearing is course-over-ground derived from successive positions, so at a
	 * standstill it is computed from receiver noise and wanders across the entire compass fix to
	 * fix. Sitting at a light on Salah Salem with a stationary bearing sweeping through 360 degrees
	 * would flicker the banner on and off, which is worse than never showing it. Cars stopped in
	 * traffic also do not need a glare warning.
	 */
	private static final double MIN_TRUSTED_SPEED_MS = 2.0;

	// ------------------------------------------------------------------ cadence

	/**
	 * How long a computed solar position is reused, milliseconds.
	 *
	 * <p>Justified rather than picked: the sun moves 360 degrees in 24 hours, so a minute of staleness
	 * displaces it by 0.25 degrees. That is one hundredth of the {@link #GLARE_HALF_ANGLE_DEG}
	 * window, i.e. far inside the noise of the threshold itself. Driving for that minute moves the
	 * observer at most a couple of kilometres, which at these distances changes the solar azimuth by
	 * a small fraction of a degree, so position staleness does not need its own invalidation either.
	 */
	private static final long SOLAR_CACHE_MS = 60 * 1000L;

	/**
	 * How often an UNCHANGED banner is republished, milliseconds.
	 *
	 * <p>Two forces pull against each other here. Republishing refreshes the hazard slot's timestamp,
	 * which is what keeps the banner from ageing out of
	 * {@link CairoDriveProviders#HAZARD_TTL_MS}; but every publish also bumps the snapshot version,
	 * which is the signal a drawing layer uses to decide it must re-project its cached geometry.
	 * Republishing at the evaluation rate would make a layer rebuild every minute to redraw an
	 * identical strip. Five minutes keeps the banner comfortably fresh against a 90 minute TTL while
	 * costing twelve version bumps an hour instead of sixty. Any actual CHANGE publishes immediately
	 * and does not wait for this.
	 */
	private static final long REPUBLISH_INTERVAL_MS = 5 * 60 * 1000L;

	// ------------------------------------------------------------------ banner text

	/**
	 * String resource KEY, not text - the contract stores a key so the banner resolves in whatever
	 * locale is current at draw time, and this app runs in Arabic and English.
	 *
	 * <p>One key for both severities, as {@code OpenWeatherHazardProvider} does with its dust key:
	 * the severity carries the colour, the text carries the cause.
	 */
	static final String TEXT_KEY_SUN_GLARE = "cairo_hazard_sun_glare";

	// ------------------------------------------------------------------ state

	private static final SunGlareProvider INSTANCE = new SunGlareProvider();

	/**
	 * Cached solar position and the wall clock it was computed for. Volatile rather than
	 * synchronized: the writer is the location update path and there is exactly one of it, so a
	 * torn read is impossible in practice, and the worst a race could do is compute the sun twice.
	 * Held as one immutable object so azimuth, elevation and timestamp can never be read from
	 * different computations.
	 */
	private static volatile SolarPosition cachedSun;

	/** Last banner this provider put in the shared hazard slot, and when. */
	private static volatile int publishedSeverity = CairoDriveProviders.HazardBanner.SEVERITY_NONE;
	private static volatile long publishedAtMs;

	private static volatile long lastLogMs;
	/**
	 * The VERDICT last written to CD_GLARE, which is not the same thing as {@link #publishedSeverity}
	 * and must not be folded into it.
	 *
	 * <p>{@code publishedSeverity} records what reached the shared slot, and {@link #publish} can
	 * decline to publish at all when a dust banner outranks us. It is also updated BEFORE
	 * {@link #maybeLog} runs, so comparing against it would make the change test read
	 * {@code severity != severity} - always false, silently reducing the log to its once-a-minute
	 * in-band path and losing every transition, which is the one thing the line exists to record.
	 *
	 * <p>-1 rather than SEVERITY_NONE so the first evaluation of a drive logs a baseline instead of
	 * being mistaken for "no change since a verdict we never made".
	 */
	private static volatile int lastLoggedSeverity = -1;

	private SunGlareProvider() {
	}

	/** The single registered instance. Register it with {@link CairoDriveProviders#register}. */
	@NonNull
	public static SunGlareProvider getInstance() {
		return INSTANCE;
	}

	// ------------------------------------------------------------------ Provider contract

	@NonNull
	@Override
	public String name() {
		return CairoDriveProviders.NAME_LOCAL;
	}

	/**
	 * One gate, not the usual two.
	 *
	 * <p>Every other provider in this package ANDs a compiled-in key with a feature flag, because
	 * without a key it must make zero network calls. There is no key here and there is nothing to
	 * make zero of, so the key half of that test would be checking a constant that does not exist.
	 *
	 * <p>The flag half stays. {@code CAIRODRIVE_SUN_GLARE} already exists in
	 * {@code OsmAnd/cairodrive.gradle} and defaults to false, deliberately: CLAUDE.md's one feature
	 * per build rule is about being able to attribute a regression to a change, and it applies to a
	 * free feature exactly as much as to a paid one. A banner that appears on the head unit at dusk
	 * is a visible change to the drive whether or not it cost anything to compute.
	 */
	@Override
	public boolean isAvailable(@NonNull OsmandApplication app) {
		try {
			return BuildConfig.CAIRODRIVE_SUN_GLARE;
		} catch (Throwable t) {
			// The contract treats a thrower as unavailable. Log it so a missing feature is
			// distinguishable from a deliberately disabled one when reading the drive log later.
			LOG.info(TRACE_TAG + " availability check failed - treating as unavailable", t);
			return false;
		}
	}

	@NonNull
	@Override
	public EnumSet<CairoDriveProviders.Capability> capabilities() {
		return EnumSet.of(CairoDriveProviders.Capability.SUN_GLARE);
	}

	// ------------------------------------------------------------------ entry point

	/**
	 * Called on every GPS fix. Everything here is arithmetic - no network, no storage, no thread.
	 *
	 * <h3>The two cadences, and why they are not the same</h3>
	 *
	 * The obvious reading of "compute it at most once a minute and cache" is to cache the ANSWER,
	 * and that would be wrong. Solar position changes at 0.25 degrees a minute; a driver's bearing
	 * changes by 90 degrees in the two seconds it takes to turn off Al-Haram onto a side street.
	 * Caching the verdict would leave the banner up to a minute behind the car - so the warning
	 * would arrive after the driver had already been blinded, and would then persist for a minute
	 * after they turned away from it.
	 *
	 * <p>So the expensive half is cached and the cheap half is not: the SOLAR POSITION is recomputed
	 * once a minute, while the azimuth-versus-bearing comparison - two subtractions and a compare -
	 * re-runs on every fix against the cached sun. That is both fast and correct, which caching the
	 * verdict is not.
	 *
	 * @param app      used only to refresh the map when the banner actually changes
	 * @param location the current fix; needs a bearing, and is ignored while stationary
	 */
	public static void onLocationUpdate(@Nullable OsmandApplication app, @Nullable Location location) {
		if (app == null || location == null) {
			return;
		}
		try {
			// The arbitration gate. Cheapest test first: one volatile read and a string compare, and
			// it rejects every build that does not ship this feature.
			if (!CairoDriveProviders.isServing(CairoDriveProviders.NAME_LOCAL,
					CairoDriveProviders.Capability.SUN_GLARE)) {
				return;
			}
			if (!location.hasBearing()) {
				return;
			}
			// hasSpeed() false means the fix provider declined to report one, not that the car is
			// stopped - some providers only report speed when moving. Only reject on a speed we were
			// actually given; rejecting on its absence would silence the feature on those providers.
			if (location.hasSpeed() && location.getSpeed() < MIN_TRUSTED_SPEED_MS) {
				return;
			}

			// The wall clock rather than location.getTime(): the two agree to well within a second on
			// Android, one second moves the sun 0.004 degrees, and using one time base keeps the cache
			// age and the solar computation from being measured against different clocks.
			long now = System.currentTimeMillis();
			SolarPosition sun = solarPosition(now, location.getLatitude(), location.getLongitude());

			double bearing = normaliseDegrees(location.getBearing());
			double offAxis = angularDifference(sun.azimuthDeg, bearing);
			int severity = severityFor(sun.elevationDeg, offAxis);

			publish(app, severity, now);
			maybeLog(sun, bearing, offAxis, severity, now);
		} catch (Throwable t) {
			// Nothing in this class may ever reach navigation. Glare is advisory; a fault here costs
			// the banner and nothing else.
			LOG.info(TRACE_TAG + " onLocationUpdate failed", t);
		}
	}

	/**
	 * The glare rule itself, in one place so it can be exercised without a device.
	 *
	 * @param elevationDeg apparent (refraction-corrected) solar elevation
	 * @param offAxisDeg   wrap-safe angle between the sun and the direction of travel, 0-180
	 * @return one of the {@code HazardBanner.SEVERITY_*} values
	 */
	static int severityFor(double elevationDeg, double offAxisDeg) {
		// Below the horizon FIRST, and as its own test rather than as one end of a range. The sun
		// being at azimuth 285 an hour after sunset is perfectly true and perfectly harmless; a
		// detector that only checked "elevation < 15" would warn all night, every night, pointing
		// west - and would do it with completely correct arithmetic, which is what makes it a trap
		// worth naming rather than a bug worth finding later.
		if (elevationDeg <= GLARE_MIN_ELEVATION_DEG || elevationDeg > GLARE_MAX_ELEVATION_DEG) {
			return CairoDriveProviders.HazardBanner.SEVERITY_NONE;
		}
		if (offAxisDeg > GLARE_HALF_ANGLE_DEG) {
			return CairoDriveProviders.HazardBanner.SEVERITY_NONE;
		}
		if (elevationDeg <= SEVERE_ELEVATION_DEG && offAxisDeg <= SEVERE_HALF_ANGLE_DEG) {
			return CairoDriveProviders.HazardBanner.SEVERITY_WARN;
		}
		return CairoDriveProviders.HazardBanner.SEVERITY_INFO;
	}

	// ------------------------------------------------------------------ publishing

	/**
	 * Puts the verdict into the shared hazard slot - carefully, because that slot is shared.
	 *
	 * <h3>The collision this exists to avoid</h3>
	 *
	 * {@link CairoDriveProviders.Capability#SUN_GLARE} and
	 * {@link CairoDriveProviders.Capability#WEATHER_HAZARD} are two capabilities but there is only
	 * ONE {@code publishHazard} slot, and this provider re-evaluates roughly once a minute while
	 * {@code OpenWeatherHazardProvider} polls once every thirty. A naive publisher here would
	 * therefore erase a live dust warning within a minute of it being raised and keep it erased for
	 * the next half hour. Dust is the more dangerous of the two and is the one that would be lost.
	 *
	 * <p>So this yields. A foreign banner - one whose text key is not ours - of equal or greater
	 * severity is left alone, and a clear verdict only ever clears OUR banner, never someone else's.
	 * That is also the physically correct ordering: heavy airborne dust scatters and attenuates the
	 * direct solar beam, so a Cairo sky dusty enough for OpenWeather to raise a warning is a sky
	 * where the low sun is a diffuse orange smear rather than a glare source. Deferring to dust is
	 * not merely a tie-break, it is the better answer.
	 */
	private static void publish(@NonNull OsmandApplication app, int severity, long now) {
		CairoDriveProviders.HazardBanner current = CairoDriveProviders.getHazard();
		boolean slotIsOurs = current != null && TEXT_KEY_SUN_GLARE.equals(current.textKey);

		if (severity == CairoDriveProviders.HazardBanner.SEVERITY_NONE) {
			if (slotIsOurs) {
				CairoDriveProviders.publishHazard(null);
				publishedSeverity = CairoDriveProviders.HazardBanner.SEVERITY_NONE;
				publishedAtMs = 0;
				refreshMap(app);
			} else if (publishedSeverity != CairoDriveProviders.HazardBanner.SEVERITY_NONE) {
				// Our banner is gone from the slot but we still thought it was ours - someone else
				// took the slot, or it expired. Forget it rather than clearing a banner we do not own.
				publishedSeverity = CairoDriveProviders.HazardBanner.SEVERITY_NONE;
				publishedAtMs = 0;
			}
			return;
		}

		if (current != null && !slotIsOurs && current.severity >= severity) {
			// Someone else's warning, at least as serious as ours. Leave it. Not tracked as a publish
			// either, so the moment their banner clears we take the slot on the very next fix.
			return;
		}

		boolean changed = !slotIsOurs || severity != publishedSeverity;
		boolean stale = now - publishedAtMs >= REPUBLISH_INTERVAL_MS || publishedAtMs <= 0;
		if (!changed && !stale) {
			return;
		}
		CairoDriveProviders.publishHazard(
				new CairoDriveProviders.HazardBanner(TEXT_KEY_SUN_GLARE, severity));
		publishedSeverity = severity;
		publishedAtMs = now;
		if (changed) {
			// Only on a real change. A refresh is pointless on a keep-alive republish - the map is
			// already redrawing continuously while the car is moving.
			refreshMap(app);
		}
	}

	/**
	 * Navigation stopped. Drops the glare banner if it is still ours.
	 *
	 * <p>Needed because the evaluation path is what normally clears this banner and it only runs on
	 * a fix above {@link #MIN_TRUSTED_SPEED_MS}. Park the car at dusk with a warning showing and
	 * nothing re-evaluates: the banner then sits in the shared slot for the full
	 * {@link CairoDriveProviders#HAZARD_TTL_MS}, which is 90 minutes and is sized for weather, not
	 * for a glare episode that is over the moment the driver stops.
	 *
	 * <p>{@link CairoDriveProviders#resetRouteState()} deliberately does NOT clear the hazard slot -
	 * dust over Cairo is no less true because the destination changed - so this has to be its own
	 * call, wired at the same site.
	 */
	public static void reset(@Nullable OsmandApplication app) {
		publishedSeverity = CairoDriveProviders.HazardBanner.SEVERITY_NONE;
		publishedAtMs = 0;
		lastLoggedSeverity = -1;
		if (app == null || !isGlareBanner(CairoDriveProviders.getHazard())) {
			// Not ours to clear. Another provider's warning outlives our navigation session.
			return;
		}
		CairoDriveProviders.publishHazard(null);
		refreshMap(app);
	}

	/**
	 * Whether a hazard banner in the shared slot came from this provider.
	 *
	 * <p>Public because a renderer may reasonably want to treat glare differently from dust - an icon,
	 * or a shorter freshness rule than the contract's 90 minute hazard TTL, which is sized for
	 * weather and is far longer than a dusk glare episode lasts.
	 */
	public static boolean isGlareBanner(@Nullable CairoDriveProviders.HazardBanner banner) {
		return banner != null && TEXT_KEY_SUN_GLARE.equals(banner.textKey);
	}

	// ------------------------------------------------------------------ solar position

	/** An immutable solar position, so azimuth and elevation can never be read from different runs. */
	static final class SolarPosition {
		/** Degrees clockwise from true north, 0-360. */
		final double azimuthDeg;
		/** Degrees above the visible horizon, refraction already applied. Negative below it. */
		final double elevationDeg;
		final long computedAtMs;

		SolarPosition(double azimuthDeg, double elevationDeg, long computedAtMs) {
			this.azimuthDeg = azimuthDeg;
			this.elevationDeg = elevationDeg;
			this.computedAtMs = computedAtMs;
		}
	}

	/** The cache in front of {@link #computeSolarPosition}. See {@link #SOLAR_CACHE_MS}. */
	@NonNull
	private static SolarPosition solarPosition(long nowMs, double latitude, double longitude) {
		SolarPosition cached = cachedSun;
		if (cached != null) {
			long age = nowMs - cached.computedAtMs;
			// A negative age means the clock jumped backwards - NTP correcting a phone that booted
			// with a bad RTC. Treat it as expired: the alternative is trusting a position computed
			// for a time that has not happened yet.
			if (age >= 0 && age < SOLAR_CACHE_MS) {
				return cached;
			}
		}
		SolarPosition computed = computeSolarPosition(nowMs, latitude, longitude);
		cachedSun = computed;
		return computed;
	}

	/**
	 * Where the sun is, by the NOAA solar position algorithm.
	 *
	 * <p>Implemented here rather than called, because nothing in the tree computes it: the OsmAnd
	 * {@code SunriseSunset} utility returns event TIMES, not a position, and Android has no solar
	 * API at all. It is the same low-precision series NOAA publishes as its solar calculator
	 * spreadsheet - good to roughly a hundredth of a degree for any date this app will see, which is
	 * three orders of magnitude finer than the 25 degree window it feeds.
	 *
	 * <p>Verified against known Cairo geometry before being committed: at solar noon on 2026-08-05
	 * it gives elevation 76.86 against the theoretical maximum of 76.96 for latitude 30.04 at that
	 * declination, and it puts sunset at 19:43 local against an almanac 19:41.
	 *
	 * <p>Everything is done in UTC. The Unix epoch begins at midnight UTC, so minutes-past-midnight
	 * falls straight out of the millisecond value with no calendar, no time zone and no DST - which
	 * matters more than it looks, because Egypt reinstated summer time in 2023 and a device time
	 * zone is exactly the kind of input that is wrong on a head unit.
	 *
	 * @param nowMs     Unix epoch milliseconds, UTC
	 * @param latitude  degrees north
	 * @param longitude degrees east
	 */
	@NonNull
	static SolarPosition computeSolarPosition(long nowMs, double latitude, double longitude) {
		// Julian day. 2440587.5 is the Julian day number of the Unix epoch.
		double julianDay = nowMs / 86400000.0 + 2440587.5;
		// Julian centuries since J2000.0, the argument every series below is expanded in.
		double t = (julianDay - 2451545.0) / 36525.0;

		double meanLongDeg = mod360(280.46646 + t * (36000.76983 + t * 0.0003032));
		double meanAnomalyDeg = 357.52911 + t * (35999.05029 - 0.0001537 * t);
		double eccentricity = 0.016708634 - t * (0.000042037 + 0.0000001267 * t);

		double meanAnomalyRad = Math.toRadians(meanAnomalyDeg);
		// Equation of the centre: the correction from the fictitious mean sun to the real one,
		// which is what makes the earth's orbit elliptical rather than circular in this model.
		double centreDeg = Math.sin(meanAnomalyRad) * (1.914602 - t * (0.004817 + 0.000014 * t))
				+ Math.sin(2 * meanAnomalyRad) * (0.019993 - 0.000101 * t)
				+ Math.sin(3 * meanAnomalyRad) * 0.000289;
		double trueLongDeg = meanLongDeg + centreDeg;

		// Nutation and aberration, both driven by the moon's ascending node.
		double omegaRad = Math.toRadians(125.04 - 1934.136 * t);
		double apparentLongDeg = trueLongDeg - 0.00569 - 0.00478 * Math.sin(omegaRad);

		// Obliquity of the ecliptic - the earth's axial tilt - expressed as degrees, arcminutes and
		// arcseconds folded together, which is the form NOAA publishes the series in.
		double meanObliquityDeg = 23.0
				+ (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0;
		double obliquityDeg = meanObliquityDeg + 0.00256 * Math.cos(omegaRad);
		double obliquityRad = Math.toRadians(obliquityDeg);

		double declinationRad = Math.asin(
				Math.sin(obliquityRad) * Math.sin(Math.toRadians(apparentLongDeg)));

		// Equation of time, minutes: the gap between clock time and sundial time. Without it the
		// computed azimuth would be out by up to about 4 degrees in early November.
		double y = Math.tan(obliquityRad / 2.0);
		y *= y;
		double meanLongRad = Math.toRadians(meanLongDeg);
		double eqOfTimeMin = 4.0 * Math.toDegrees(
				y * Math.sin(2 * meanLongRad)
						- 2 * eccentricity * Math.sin(meanAnomalyRad)
						+ 4 * eccentricity * y * Math.sin(meanAnomalyRad) * Math.cos(2 * meanLongRad)
						- 0.5 * y * y * Math.sin(4 * meanLongRad)
						- 1.25 * eccentricity * eccentricity * Math.sin(2 * meanAnomalyRad));

		// Minutes past midnight UTC, straight out of the epoch value. floorMod rather than % so a
		// pre-1970 timestamp - which a head unit with a dead RTC can genuinely produce - does not
		// come back negative and put the sun on the wrong side of the sky.
		double utcMinutes = Math.floorMod(nowMs, 86400000L) / 60000.0;
		// 4 minutes of solar time per degree of longitude, east positive.
		double trueSolarTimeMin = mod(utcMinutes + eqOfTimeMin + 4.0 * longitude, 1440.0);

		// Hour angle: 0 at local solar noon, negative before it, positive after. 1440 minutes map
		// onto 360 degrees, so 4 minutes per degree again.
		double hourAngleDeg = trueSolarTimeMin / 4.0 - 180.0;

		double latitudeRad = Math.toRadians(latitude);
		double cosZenith = Math.sin(latitudeRad) * Math.sin(declinationRad)
				+ Math.cos(latitudeRad) * Math.cos(declinationRad)
				* Math.cos(Math.toRadians(hourAngleDeg));
		// Clamped because rounding can push this a hair outside [-1, 1] near the poles, and acos of
		// 1.0000000001 is NaN - which would silently propagate through every comparison as false.
		cosZenith = clamp(cosZenith, -1.0, 1.0);
		double zenithDeg = Math.toDegrees(Math.acos(cosZenith));
		double elevationDeg = 90.0 - zenithDeg;

		elevationDeg += refractionDeg(elevationDeg);

		double azimuthDeg = azimuthDeg(latitudeRad, zenithDeg, declinationRad, hourAngleDeg);
		return new SolarPosition(azimuthDeg, elevationDeg, nowMs);
	}

	/**
	 * Solar azimuth, degrees clockwise from true north.
	 *
	 * <p>Split out because the sign handling is the fiddly part: {@code acos} only returns 0-180, so
	 * it cannot distinguish morning from afternoon on its own and the hour angle has to supply the
	 * missing half. Getting that branch wrong mirrors the sun about the north-south line, which
	 * produces a detector that fires at dawn for a westbound driver - plausible-looking output that
	 * is exactly backwards.
	 */
	private static double azimuthDeg(double latitudeRad, double zenithDeg, double declinationRad,
	                                 double hourAngleDeg) {
		double zenithRad = Math.toRadians(zenithDeg);
		double denominator = Math.cos(latitudeRad) * Math.sin(zenithRad);
		if (Math.abs(denominator) < 1e-9) {
			// The sun directly overhead, or an observer at a pole. Azimuth is undefined rather than
			// zero; returning 0 is harmless only because an overhead sun is far outside the elevation
			// band this class acts on.
			return 0.0;
		}
		double cosAzimuth = clamp(
				(Math.sin(latitudeRad) * Math.cos(zenithRad) - Math.sin(declinationRad)) / denominator,
				-1.0, 1.0);
		double angleDeg = Math.toDegrees(Math.acos(cosAzimuth));
		return hourAngleDeg > 0 ? mod360(angleDeg + 180.0) : mod360(540.0 - angleDeg);
	}

	/**
	 * Atmospheric refraction, degrees to ADD to the geometric elevation.
	 *
	 * <p>Not a rounding detail at these angles. The atmosphere bends the beam by about 0.57 degrees
	 * at the horizon - more than the sun's own diameter - so the sun is still visibly above the
	 * horizon, and still in the driver's eyes, when geometry says it has already set. Since this
	 * detector's whole operating band is the lowest 15 degrees of the sky, the correction is largest
	 * exactly where it is being used. NOAA's piecewise fit, in arcseconds, converted on return.
	 */
	private static double refractionDeg(double elevationDeg) {
		if (elevationDeg > 85.0) {
			return 0.0;
		}
		double tan = Math.tan(Math.toRadians(elevationDeg));
		double arcSeconds;
		if (elevationDeg > 5.0) {
			arcSeconds = 58.1 / tan - 0.07 / (tan * tan * tan)
					+ 0.000086 / (tan * tan * tan * tan * tan);
		} else if (elevationDeg > -0.575) {
			// Near the horizon the tangent form blows up, so NOAA switches to a polynomial fit.
			arcSeconds = 1735.0 + elevationDeg * (-518.2 + elevationDeg
					* (103.4 + elevationDeg * (-12.79 + elevationDeg * 0.711)));
		} else {
			arcSeconds = -20.772 / tan;
		}
		return arcSeconds / 3600.0;
	}

	// ------------------------------------------------------------------ angles

	/**
	 * Smallest angle between two compass bearings, 0-180 degrees.
	 *
	 * <p><b>The trap this method exists for.</b> The obvious {@code Math.abs(a - b)} is wrong twice a
	 * day, and wrong in the direction that matters. A driver heading 355 degrees with the sun at 5
	 * degrees is 10 degrees off axis and squarely in the glare; naive subtraction calls it 350 and
	 * reports no hazard. It fails identically at dusk, when a westbound bearing and a solar azimuth
	 * can straddle 360 as the sun swings north of west in high summer. Both failures are silent -
	 * the banner simply never appears - which is why this is a named method with a comment rather
	 * than an inline expression somewhere.
	 */
	static double angularDifference(double aDeg, double bDeg) {
		double difference = mod(Math.abs(aDeg - bDeg), 360.0);
		return difference > 180.0 ? 360.0 - difference : difference;
	}

	/** Compass bearings from a fix are normally 0-360 already; this makes that an invariant. */
	private static double normaliseDegrees(double degrees) {
		return mod360(degrees);
	}

	private static double mod360(double degrees) {
		return mod(degrees, 360.0);
	}

	/** Always non-negative, unlike {@code %}, which keeps the sign of the dividend. */
	private static double mod(double value, double modulus) {
		double result = value % modulus;
		return result < 0 ? result + modulus : result;
	}

	private static double clamp(double value, double min, double max) {
		return value < min ? min : (value > max ? max : value);
	}

	// ------------------------------------------------------------------ logging

	/**
	 * Writes CD_GLARE.
	 *
	 * <p>Logged on every state change, and otherwise once per solar recompute for as long as the sun
	 * is inside the elevation band - which is the ONLY window worth recording. Logging all day would
	 * bury the interesting minutes in a thousand lines saying the sun is overhead; logging only when
	 * the banner fires would record no near misses, and near misses are precisely what
	 * {@link #GLARE_HALF_ANGLE_DEG} has to be tuned against. Bounding it to dawn and dusk gives about
	 * an hour of once-a-minute lines a day.
	 *
	 * <p>Every input to the verdict appears, so a banner - or a missing one - can be replayed from
	 * the log alone without the drive.
	 */
	private static void maybeLog(@NonNull SolarPosition sun, double bearingDeg, double offAxisDeg,
	                             int severity, long nowMs) {
		boolean changed = severity != lastLoggedSeverity;
		boolean inBand = sun.elevationDeg > GLARE_MIN_ELEVATION_DEG
				&& sun.elevationDeg <= GLARE_MAX_ELEVATION_DEG;
		long sinceLog = nowMs - lastLogMs;
		if (!changed && !(inBand && (lastLogMs <= 0 || sinceLog < 0 || sinceLog >= SOLAR_CACHE_MS))) {
			return;
		}
		lastLogMs = nowMs;
		lastLoggedSeverity = severity;
		// Locale.US on purpose: the app runs in Arabic, and the default locale would render these
		// with Arabic-Indic digits, which no log parser here reads.
		CairoDriveLogger.getInstance().log(TRACE_TAG, String.format(Locale.US,
				"az=%.1f el=%.2f bearing=%.1f offAxis=%.1f severity=%d banner=%s",
				sun.azimuthDeg, sun.elevationDeg, bearingDeg, offAxisDeg, severity,
				severity == CairoDriveProviders.HazardBanner.SEVERITY_NONE
						? "none" : TEXT_KEY_SUN_GLARE));
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
}
