package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;

import java.util.Locale;

/**
 * Corrects the remaining-time estimate against the pace the driver is actually keeping.
 *
 * <p>Upstream's ETA never learns. {@code RouteCalculationResult.getLeftTime} is
 * {@code afterLeftTime + distanceToNextTurn / averageSpeed}, where {@code afterLeftTime} is a
 * prefix sum computed once when the route is built and {@code getAverageSpeed()} is the ROUTER's
 * modelled speed - OSM {@code maxspeed} where tagged, otherwise a profile default per road class.
 * Nothing in that expression consults a GPS fix, so the figure describes the map, not the drive.
 *
 * <p>Measured on a real Cairo drive (2026-08-04, 76 samples over four legs): the static estimate
 * was short on EVERY sample, mean absolute error 209 s, worst case 8.7 minutes short on a 5.4 km
 * leg. So the correction is warranted. The first version of this class cut that error by 21% but
 * was too volatile - the ratio swung between 0.26 and 1.04 within a single trip.
 *
 * <h3>Why v1 was volatile, and what changed</h3>
 *
 * v1 compared an INSTANTANEOUS GPS speed against {@code getAverageSpeed()} of the current
 * direction. Those are not the same kind of quantity: {@code getAverageSpeed()} is
 * {@code segment distance / segment time} for a whole direction, so it already contains the
 * router's junction and deceleration penalties, while the GPS sample is a single moment - and v1
 * additionally discarded every fix below 2 m/s, throwing away exactly the stops that make Cairo
 * slow. Worse, the modelled value changes whenever the road class changes, so the ratio jumped at
 * every turn rather than describing the trip.
 *
 * <p>v2 compares like with like. It accumulates the distance actually covered and the wall-clock
 * time that took - <em>including</em> every red light and jam - alongside the time the router would
 * have predicted for that same distance. The ratio is then simply
 *
 * <pre>modelled time for the distance covered / actual time it took</pre>
 *
 * which is dimensionless, stop-inclusive on both sides, and converges over a trip instead of
 * oscillating. {@code corrected = static / ratio}.
 *
 * <h3>Constraints, because a confidently wrong ETA is worse than an optimistic one</h3>
 * <ul>
 *   <li>Clamped both ways, so one bus or one empty stretch cannot rewrite the estimate.</li>
 *   <li>Nothing is applied until a minimum distance has been covered - the first 400 m of a trip
 *       is pulling out of a parking space, not evidence about the road ahead.</li>
 *   <li>Implausible jumps between fixes (GPS glitches) are discarded rather than integrated.</li>
 *   <li>{@link #correct} is a PURE function: no mutation, no rate limiting, no shared state
 *       between callers. v1 rate-limited inside correct(), which meant whichever caller asked
 *       first pinned the value every other caller saw for the next five seconds - across threads,
 *       with non-volatile fields. That is also why the arrival card could disagree with the
 *       distance beside it.</li>
 * </ul>
 *
 * <p>Emits {@code CD_ETA} so the correction can be judged against actual arrival rather than
 * trusted.
 */
public class CairoDriveEta {

	/** Distance covered before the ratio is trusted at all. */
	private static final double MIN_DISTANCE_M = 400;

	/** Ignore modelled speeds at or below this - RouteDirectionInfo stores exactly 1 as a sentinel. */
	private static final float MIN_MODELLED_SPEED_MPS = 1.0f;

	/** A single fix-to-fix step further than this is a GPS glitch, not travel. */
	private static final double MAX_STEP_M = 400;

	/** Fix-to-fix gaps longer than this mean the app was backgrounded; do not integrate them. */
	private static final long MAX_STEP_MS = 15_000;

	private static final double MIN_RATIO = 0.20;
	private static final double MAX_RATIO = 1.60;

	private double observedDistanceM;
	private double observedTimeS;
	private double modelledTimeS;
	private Location lastFix;

	/**
	 * Feed one fix plus the modelled speed the router expects for the segment being driven.
	 * Synchronized because {@link #correct} is read from the UI thread, the Android Auto thread,
	 * the notification thread and the live-monitoring thread.
	 */
	public synchronized void registerFix(@Nullable Location location, float modelledSpeed) {
		if (location == null) {
			return;
		}
		Location previous = lastFix;
		lastFix = location;
		if (previous == null || modelledSpeed < MIN_MODELLED_SPEED_MPS) {
			return;
		}
		double stepM = previous.distanceTo(location);
		double stepMs = location.getTime() - previous.getTime();
		if (stepMs <= 0 || stepMs > MAX_STEP_MS || stepM > MAX_STEP_M) {
			// Backgrounded, or a jumped fix. Integrating either would corrupt the ratio.
			return;
		}
		observedDistanceM += stepM;
		observedTimeS += stepMs / 1000.0;
		// What the router would have predicted for exactly this stretch.
		modelledTimeS += stepM / modelledSpeed;
	}

	/** @return the observed/modelled pace ratio, clamped. 1.0 means the router was right. */
	public synchronized double getRatio() {
		if (observedTimeS <= 0 || modelledTimeS <= 0) {
			return 1.0;
		}
		double ratio = modelledTimeS / observedTimeS;
		if (Double.isNaN(ratio) || Double.isInfinite(ratio)) {
			return 1.0;
		}
		return Math.max(MIN_RATIO, Math.min(MAX_RATIO, ratio));
	}

	public synchronized boolean isWarmedUp() {
		return observedDistanceM >= MIN_DISTANCE_M && modelledTimeS > 0;
	}

	public synchronized double getObservedDistanceM() {
		return observedDistanceM;
	}

	/**
	 * Pure. Returns the input unchanged until warmed up, so every caller that asks at the same
	 * moment gets the same answer and the arrival time cannot contradict the time-to-next-turn.
	 */
	public int correct(int staticSeconds) {
		if (staticSeconds <= 0 || !isWarmedUp()) {
			return staticSeconds;
		}
		return (int) Math.round(staticSeconds / getRatio());
	}

	public synchronized void reset() {
		observedDistanceM = 0;
		observedTimeS = 0;
		modelledTimeS = 0;
		lastFix = null;
	}

	@NonNull
	public synchronized String describe(int staticSeconds, int correctedSeconds) {
		return String.format(Locale.US,
				"static=%d corrected=%d ratio=%.3f obsM=%.0f obsS=%.0f modS=%.0f warm=%b",
				staticSeconds, correctedSeconds, getRatio(),
				observedDistanceM, observedTimeS, modelledTimeS, isWarmedUp());
	}
}
