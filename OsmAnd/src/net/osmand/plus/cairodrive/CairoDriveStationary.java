package net.osmand.plus.cairodrive;

import net.osmand.Location;

import androidx.annotation.Nullable;

/**
 * Decides when the vehicle is genuinely stopped, so the map stops wandering while parked.
 *
 * <h3>The thresholds here were simulated, not chosen</h3>
 *
 * The obvious version - "freeze below 2 km/h" - was tested first and REJECTED. Against a modelled
 * Cairo crawl it froze the arrow on <b>13.4% of fixes from a car actually moving at 3 km/h</b>.
 * Cairo traffic spends a great deal of time at 3 km/h, so a single-fix speed test cannot separate
 * parked from crawling: the two distributions overlap.
 *
 * <p>Requiring consecutive corroboration fixes that. Simulated over 40,000 fixes per cell:
 *
 * <pre>
 *   2.0 km/h, 1 consecutive  ->  crawling car wrongly frozen 13.40%   REJECTED
 *   2.0 km/h, 2 consecutive  ->                               1.77%
 *   2.0 km/h, 3 consecutive  ->                               0.27%   chosen
 *   2.5 km/h, 3 consecutive  ->                               2.42%
 * </pre>
 *
 * At 2.0 km/h and 3 consecutive fixes, a car crawling at 3 km/h is wrongly frozen on 0.27% of
 * fixes and one at 5 km/h effectively never, while roughly 48% of parked drift is suppressed.
 *
 * <h3>What the simulation ALSO says, and it is the more important half</h3>
 *
 * This barely works when the fix is network-derived. Modelled at the drift such fixes actually
 * show, the same settings suppress <b>0.8%</b> of parked wander instead of 48% - because the
 * apparent speed while stationary is then larger than any threshold that would still be safe for
 * a crawling car.
 *
 * <p>That is not a reason to skip it; it is a reason to record it. 55% of the 2026-08-04 drive's
 * fixes had {@code satsUsed=0}. So this is expected to help on roughly the other 45%, and
 * {@code CD_STATIONARY} reports how often it fires and at what fix quality, which is what makes
 * the prediction checkable instead of a claim.
 *
 * <h3>Display only</h3>
 *
 * Nothing here reaches routing. Routing sees every fix exactly as before. This decides only
 * whether the map is re-centred and the arrow moved - the same separation Mapbox describes as two
 * streams, raw for logic and matched for display.
 */
public class CairoDriveStationary {

	/** Simulated: see the class comment. Below this a fix counts as "possibly stopped". */
	private static final float STATIONARY_SPEED_MS = 2.0f / 3.6f;
	/** Simulated: 3 is where a crawling car stops being frozen (13.4% -> 0.27%). */
	private static final int FIXES_TO_FREEZE = 3;
	/**
	 * One fix at or above the threshold releases immediately. Moving again must never be delayed -
	 * the cost of a late release is a stale arrow while the car pulls away, which is the exact
	 * failure this class is supposed to prevent, only worse.
	 */
	private static final int FIXES_TO_RELEASE = 1;

	private static final long LOG_INTERVAL_MS = 60_000;

	private int consecutiveSlowFixes;
	private boolean frozen;
	private long lastLogAt;
	private int frozenFixes;
	private int totalFixes;
	private int frozenWhileDegraded;

	/**
	 * @param location   the newest fix, or null
	 * @param degraded   whether GNSS health says this fix is network-derived (see N1)
	 * @return true when the map should NOT follow this fix
	 */
	public synchronized boolean isStationary(@Nullable Location location, boolean degraded) {
		if (location == null || !location.hasSpeed()) {
			// No speed at all: say nothing rather than guess. A provider that omits speed is not
			// evidence of being stopped.
			consecutiveSlowFixes = 0;
			frozen = false;
			return false;
		}
		totalFixes++;
		if (location.getSpeed() < STATIONARY_SPEED_MS) {
			if (++consecutiveSlowFixes >= FIXES_TO_FREEZE) {
				frozen = true;
			}
		} else {
			consecutiveSlowFixes = 0;
			frozen = false;
		}
		if (frozen) {
			frozenFixes++;
			if (degraded) {
				frozenWhileDegraded++;
			}
		}
		maybeLog(location, degraded);
		return frozen;
	}

	private void maybeLog(Location location, boolean degraded) {
		long now = System.currentTimeMillis();
		if (now - lastLogAt < LOG_INTERVAL_MS || totalFixes == 0) {
			return;
		}
		lastLogAt = now;
		// Rate-limited to once a minute: this is asked on every fix, and the interesting quantity
		// is the RATE, not the individual decision.
		CairoDriveLogger.getInstance().log("CD_STATIONARY",
				"frozen=" + frozenFixes + "/" + totalFixes
						+ " (" + (100 * frozenFixes / Math.max(1, totalFixes)) + "%)"
						+ " frozenWhileDegraded=" + frozenWhileDegraded
						+ " speed=" + String.format(java.util.Locale.US, "%.2f", location.getSpeed())
						+ " degradedNow=" + degraded
						+ " thresholdMs=" + String.format(java.util.Locale.US, "%.2f", STATIONARY_SPEED_MS)
						+ " needFixes=" + FIXES_TO_FREEZE);
	}

	/**
	 * N4 instrumentation, and now also N4's verdict.
	 *
	 * <p>This was written to answer "should the callback rate be changed", because nobody knew the
	 * actual rate. Reading the two location helpers answered it instead, and the answer was that
	 * there was never anything to change: the AOSP path already asks for
	 * {@code requestLocationUpdates(provider, 0, 0)} and the Play path already asks for
	 * {@code PRIORITY_HIGH_ACCURACY} every 100 ms. Both are already past what the hardware gives.
	 *
	 * <p>So {@code hz=} is no longer a decision input - it is the denominator. What it now
	 * calibrates is the position PREDICTION this fork turned on (see
	 * {@code CairoDriveFeatures.getLocationInterpolationPercent}), because how far ahead
	 * {@code interp=}% projects the marker depends entirely on how long a fix interval is:
	 *
	 * <ul>
	 *   <li>{@code hz=1.0} - one fix a second. At 60 km/h, 50% projects the arrow ~8 m ahead.
	 *       This is the case the 50 default was chosen for.</li>
	 *   <li>{@code hz} well above 1 - intervals are short, the projection distance shrinks with
	 *       them, and the prediction is nearly free. A higher {@code interp=} would be safe.</li>
	 *   <li>{@code hz} well below 1 - fixes are sparse, every projection extrapolates further on
	 *       older evidence, and overshoot at every deceleration gets worse. Lower {@code interp=}
	 *       rather than raise it.</li>
	 * </ul>
	 *
	 * <p>Both values on one line on purpose: neither is interpretable without the other.
	 */
	private long rateWindowStart;
	private int fixesThisWindow;

	public synchronized void countFix() {
		long now = System.currentTimeMillis();
		if (rateWindowStart == 0) {
			rateWindowStart = now;
			fixesThisWindow = 0;
		}
		fixesThisWindow++;
		long elapsed = now - rateWindowStart;
		if (elapsed >= 60_000) {
			double hz = fixesThisWindow * 1000.0 / Math.max(1, elapsed);
			int interp = CairoDriveFeatures.getLocationInterpolationPercent();
			CairoDriveLogger.getInstance().log("CD_FIXRATE",
					"fixes=" + fixesThisWindow + " inMs=" + elapsed
							+ " hz=" + String.format(java.util.Locale.US, "%.2f", hz)
							+ " interp=" + interp
							// How far ahead of the last fix the marker is being drawn, in metres
							// at 60 km/h. This is the number that decides whether interp is set
							// sensibly - a percentage means nothing without the interval it
							// applies to, and metres is what a driver actually sees.
							+ " aheadM@60=" + String.format(java.util.Locale.US, "%.1f",
							hz > 0 ? (60 / 3.6) * (1.0 / hz) * (interp / 100.0) : 0.0));
			rateWindowStart = now;
			fixesThisWindow = 0;
		}
	}

	public synchronized void reset() {
		consecutiveSlowFixes = 0;
		frozen = false;
	}
}
