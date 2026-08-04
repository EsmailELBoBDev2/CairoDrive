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
	 * N4 instrumentation. Whether to drop the location callback rate from "as fast as the provider
	 * will give it" to ~1 Hz is a real question, and the honest answer is that nobody knows the
	 * current rate: requestLocationUpdates is called with minTime=0, but GPS hardware is typically
	 * 1 Hz anyway, so the fused provider may already be delivering exactly what a 1000 ms request
	 * would ask for. Guessing here would trade battery for nothing, or nothing for jitter.
	 *
	 * <p>So this counts. One CD_FIXRATE line a minute says how many fixes actually arrived. If it
	 * reads ~60 the change is pointless; if it reads 300+ then nine in ten callbacks are
	 * interpolation and there is something to win.
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
			CairoDriveLogger.getInstance().log("CD_FIXRATE",
					"fixes=" + fixesThisWindow + " inMs=" + elapsed
							+ " hz=" + String.format(java.util.Locale.US, "%.2f",
							fixesThisWindow * 1000.0 / Math.max(1, elapsed)));
			rateWindowStart = now;
			fixesThisWindow = 0;
		}
	}

	public synchronized void reset() {
		consecutiveSlowFixes = 0;
		frozen = false;
	}
}
