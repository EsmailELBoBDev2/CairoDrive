package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;

import java.util.Locale;

/**
 * Corrects the remaining-time estimate against the speed the driver is actually managing.
 *
 * <p>Upstream's ETA never learns. {@code RouteCalculationResult.getLeftTime} is
 * {@code afterLeftTime + distanceToNextTurn / averageSpeed}, where {@code afterLeftTime} is a
 * prefix sum computed once when the route is built and {@code getAverageSpeed()} is the ROUTER's
 * modelled speed - taken from the OSM {@code maxspeed} tag where one exists, and otherwise from the
 * routing profile's default for that road class. Nothing in that expression consults a GPS fix, so
 * the figure is a property of the map, not of the drive.
 *
 * <p>That is survivable in a country where {@code maxspeed} is widely tagged and traffic broadly
 * matches it. In Cairo it is not: most roads carry no {@code maxspeed} at all, so the router falls
 * back to defaults that assume free-flowing traffic, and the error compounds with distance. The
 * reported symptom is exactly what the arithmetic predicts - short estimates that get shorter the
 * longer the trip.
 *
 * <p>Worth being precise about what this is NOT. It is not traffic data. Waze produces good
 * estimates in Egypt without live traffic because its baseline speeds come from historical
 * per-segment averages built out of real driver traces; that is a dataset, and this fork has no
 * route to one. What it does have is one driver, driving the same city every day, whose own
 * observed speed is a perfectly good estimator of how far off the modelled speed is. So this
 * tracks the ratio
 *
 * <pre>observed speed / modelled speed for the segment being driven</pre>
 *
 * smooths it, and scales the remaining static estimate by it.
 *
 * <p>Deliberate constraints, because a confidently wrong ETA is worse than an optimistic one:
 * <ul>
 *   <li>The ratio is clamped. A driver stuck behind a bus for a minute must not turn a 20 minute
 *       trip into two hours.</li>
 *   <li>Nothing is applied until enough moving samples have accumulated, so the first minute of a
 *       drive shows upstream's number rather than a ratio derived from pulling out of a parking
 *       space.</li>
 *   <li>Stationary and near-stationary fixes are ignored entirely. Traffic lights are not evidence
 *       about road speed, and including them would make every Cairo estimate diverge.</li>
 *   <li>The output is rate-limited so the arrival time does not visibly jitter between fixes.</li>
 * </ul>
 *
 * <p>Emits {@code CD_ETA} so a drive log can show whether this helped: it carries the raw estimate,
 * the corrected one, the live ratio and the sample count, and can be compared against the actual
 * arrival time at the end of the trip.
 */
public class CairoDriveEta {

	/** Ignore fixes below this speed - stopped at a light says nothing about the road. */
	private static final float MIN_SPEED_MPS = 2.0f;

	/** Modelled speeds below this are not credible enough to divide by. */
	private static final float MIN_MODELLED_SPEED_MPS = 1.0f;

	/**
	 * Ratio bounds. 0.25 means "you are managing a quarter of the modelled speed" - about as bad as
	 * Cairo gets before the trip is not really moving; 1.5 allows for genuinely under-tagged roads
	 * where the driver comfortably exceeds a conservative default.
	 */
	private static final float MIN_RATIO = 0.25f;
	private static final float MAX_RATIO = 1.5f;

	/** Smoothing factor. Low, because the useful signal is the trip average, not the last minute. */
	private static final float ALPHA = 0.05f;

	/** Samples before the correction is trusted at all. At ~1 Hz this is roughly half a minute. */
	private static final int MIN_SAMPLES = 30;

	/** Do not restate the arrival time more often than this. */
	private static final long OUTPUT_INTERVAL_MS = 5000;

	private float ratio = 1.0f;
	private int samples;
	private long lastLoggedTime;
	private int lastReportedSeconds = -1;

	/**
	 * Feed one fix and the modelled speed the router expects for the segment being driven.
	 *
	 * @param location      the current fix; ignored when null, stationary, or without a speed
	 * @param modelledSpeed the router's {@code getAverageSpeed()} for the current direction, m/s
	 */
	public void registerFix(@Nullable Location location, float modelledSpeed) {
		if (location == null || !location.hasSpeed() || modelledSpeed < MIN_MODELLED_SPEED_MPS) {
			return;
		}
		float observed = location.getSpeed();
		if (observed < MIN_SPEED_MPS) {
			return;
		}
		float sample = clamp(observed / modelledSpeed);
		if (samples == 0) {
			ratio = sample;
		} else {
			ratio = ratio + ALPHA * (sample - ratio);
		}
		ratio = clamp(ratio);
		samples++;
	}

	/**
	 * @param staticSeconds upstream's uncorrected estimate
	 * @return the corrected estimate, or the input unchanged while still warming up
	 */
	public int correct(int staticSeconds) {
		if (samples < MIN_SAMPLES || staticSeconds <= 0) {
			lastReportedSeconds = staticSeconds;
			return staticSeconds;
		}
		int corrected = Math.round(staticSeconds / ratio);
		// Rate limit the visible number so it does not flicker between fixes. The underlying ratio
		// keeps updating regardless; this only smooths what the driver reads.
		long now = System.currentTimeMillis();
		if (lastReportedSeconds >= 0 && now - lastLoggedTime < OUTPUT_INTERVAL_MS) {
			// Let it move if it has drifted far enough to matter anyway.
			if (Math.abs(corrected - lastReportedSeconds) < 30) {
				return lastReportedSeconds;
			}
		}
		lastLoggedTime = now;
		lastReportedSeconds = corrected;
		return corrected;
	}

	public boolean isWarmedUp() {
		return samples >= MIN_SAMPLES;
	}

	public float getRatio() {
		return ratio;
	}

	public int getSamples() {
		return samples;
	}

	public void reset() {
		ratio = 1.0f;
		samples = 0;
		lastLoggedTime = 0;
		lastReportedSeconds = -1;
	}

	@NonNull
	public String describe(int staticSeconds, int correctedSeconds) {
		return String.format(Locale.US,
				"static=%d corrected=%d ratio=%.3f samples=%d warm=%b",
				staticSeconds, correctedSeconds, ratio, samples, isWarmedUp());
	}

	private static float clamp(float value) {
		if (Float.isNaN(value) || Float.isInfinite(value)) {
			return 1.0f;
		}
		return Math.max(MIN_RATIO, Math.min(MAX_RATIO, value));
	}
}
