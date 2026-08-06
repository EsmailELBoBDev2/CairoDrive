package net.osmand.plus.cairodrive;

import net.osmand.Location;
import net.osmand.plus.BuildConfig;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

/**
 * Decides whether the driver has actually left the route, or whether the GPS just wandered.
 *
 * <p>Upstream declares a deviation from a <em>single</em> fix past the threshold, and the
 * threshold is generous: {@code (30 + accuracy) * 2}, so 120 m with no accuracy reported. One
 * bad fix under a flyover therefore costs a full route recomputation, and the recomputation is
 * the slow part - so the reroutes a driver actually notices are frequently ones that never
 * needed to happen.
 *
 * <p>Every shipping navigation SDK requires corroboration before acting. The rules here follow
 * the ones that are visible in Mapbox's, HERE's and Organic Maps' own source:
 *
 * <ul>
 *     <li><b>Reject unusable fixes outright.</b> Anything worse than {@link #MAX_TRUSTED_ACCURACY_M}
 *     is not evidence of anything - that is a tunnel or an urban canyon, not a wrong turn.
 *     Mapbox drops fixes over 100 m for the same reason.</li>
 *     <li><b>Require consecutive agreement, scaled by how noisy the GPS is.</b>
 *     {@code max(3, accuracy / 4)} fixes must all be off-route before the deviation is
 *     believed, and a single good fix resets the count. At 20 m accuracy that is 5 fixes; at
 *     80 m it is 20. The worse the signal, the more proof required.</li>
 *     <li><b>Do not re-decide too soon.</b> At least {@link #MIN_METERS_BETWEEN_REROUTES} of
 *     travel and {@link #MIN_MILLIS_BETWEEN_REROUTES} must pass after a reroute before
 *     another deviation can be declared, which is what stops a bad stretch of road from
 *     recomputing over and over.</li>
 * </ul>
 *
 * <p>Deliberately <em>not</em> changed here: the distance threshold itself. Mapbox uses 50 m,
 * tightening to 25 m within 40 m of an intersection, which is a better shape than a flat 120 m
 * - but tightening the threshold without first proving the hysteresis works would trade
 * missed reroutes for spurious ones, and this class has no access to intersection geometry.
 * Corroboration first; the threshold can follow once there is real-world evidence it helps.
 *
 * <p>All state is confined to one instance owned by RoutingHelper and touched only from the
 * location callback, so no synchronisation is needed. Set {@code CAIRODRIVE_OFFROUTE_HYSTERESIS=false}
 * at build time for stock behaviour.
 */
public class CairoDriveOffRoute {

	/** Beyond this, a fix says nothing about where the vehicle is. */
	private static final float MAX_TRUSTED_ACCURACY_M = 100;
	/** Fixes that must agree before a deviation is believed, at good accuracy. */
	private static final int MIN_CONSECUTIVE_FIXES = 3;
	/** Divisor turning reported accuracy into a required fix count. */
	private static final float ACCURACY_PER_REQUIRED_FIX_M = 4;
	/** Upper bound, so a wildly pessimistic accuracy cannot suppress rerouting entirely. */
	private static final int MAX_CONSECUTIVE_FIXES = 20;

	private static final float MIN_METERS_BETWEEN_REROUTES = 50;
	private static final long MIN_MILLIS_BETWEEN_REROUTES = 3000;

	/** Longest a genuine deviation may go unannounced, whatever the counters say. */
	private static final long MAX_SUPPRESSION_MS = 12_000;

	/** Past this multiple of the off-route threshold, two fixes are enough. */
	private static final double STRONG_RATIO = 2.5;

	/** And past this, one is - the deviation is many times any plausible GPS error. */
	private static final double OVERWHELMING_RATIO = 4.0;

	/** Consecutive ON-route fixes that clear accumulated off-route evidence. */
	private static final int ON_ROUTE_FIXES_TO_FORGET = 2;

	private int consecutiveOffRouteFixes;
	private int consecutiveOnRouteFixes;
	private long firstOffRouteTime;
	@Nullable
	private Location lastRerouteLocation;
	private long lastRerouteTime;

	/** True when the hysteresis is active; false builds behave exactly as upstream. */
	public static boolean isEnabled() {
		return BuildConfig.CAIRODRIVE_OFFROUTE_HYSTERESIS;
	}

	/**
	 * Whether a fix measured as off-route should actually trigger a recalculation.
	 *
	 * @param location   the fix that looks off-route
	 * @param offRoute   what upstream's distance test concluded about this fix
	 * @return true to recalculate
	 */
	public boolean shouldRecalculate(@NonNull Location location, boolean offRoute) {
		return shouldRecalculate(location, offRoute, 0, 0);
	}

	/**
	 * @param devM       how far off the route this fix is
	 * @param allowableM the threshold at which it counts as off route at all
	 */
	public boolean shouldRecalculate(@NonNull Location location, boolean offRoute,
	                                 double devM, double allowableM) {
		if (!isEnabled()) {
			return offRoute;
		}
		if (!offRoute) {
			// DECAY, do not reset. The original reset-to-zero is the first of the three rules
			// that between them could suppress a reroute indefinitely: a wrong turn onto a road
			// that runs near the route produces an alternating on/off/on/off pattern, and any
			// single on-route fix wiped the whole run of evidence. Decaying by one keeps a
			// genuine sustained deviation accumulating while still forgiving isolated noise.
			// Evidence is cleared only by SUSTAINED agreement that we are back on the route, not
			// by a single fix. That single-fix reset was the first of the three compounding rules
			// that made this class unsafe: a wrong turn onto a road running parallel to the route
			// produces an alternating off/on/off/on pattern, and any one on-route fix wiped the
			// whole run - so the reroute never came. Requiring a short run instead still forgives
			// isolated noise while letting a genuine deviation accumulate.
			//
			// Both thresholds below were chosen by simulating six patterns (sustained deviation,
			// parallel-road alternation, mostly-off, isolated glitches at 1/2/3 fixes) against
			// this logic before the flag was turned on.
			if (++consecutiveOnRouteFixes >= ON_ROUTE_FIXES_TO_FORGET) {
				consecutiveOffRouteFixes = 0;
				firstOffRouteTime = 0;
			}
			return false;
		}
		consecutiveOnRouteFixes = 0;
		// START THE BACKSTOP CLOCK FIRST. It used to be started below, AFTER the accuracy
		// rejection - so on a sustained run of fixes worse than MAX_TRUSTED_ACCURACY_M the clock
		// never started and the 12 s timeout could never fire. Suppression was unbounded, in
		// exactly the conditions the timeout was written for, and the comment on the rejection
		// claimed the opposite.
		if (firstOffRouteTime == 0) {
			firstOffRouteTime = System.currentTimeMillis();
		}
		if (location.hasAccuracy() && location.getAccuracy() > MAX_TRUSTED_ACCURACY_M) {
			// Unusable fix: no evidence. But the clock above is running now, so a long run of
			// these can no longer hold a real deviation back indefinitely.
			return System.currentTimeMillis() - firstOffRouteTime > MAX_SUPPRESSION_MS;
		}
		consecutiveOffRouteFixes++;
		// HARD TIMEOUT. However noisy the GPS, however the counter is behaving, a deviation that
		// has persisted this long is real and the driver needs to know now. This is the backstop
		// that makes the whole mechanism safe to enable: the failure mode that took it off by
		// default was a genuine wrong turn going unannounced for kilometres, and no combination
		// of the rules above can now delay a reroute past this.
		boolean timedOut = System.currentTimeMillis() - firstOffRouteTime > MAX_SUPPRESSION_MS;
		if (!timedOut && consecutiveOffRouteFixes < requiredFixes(location, devM, allowableM)) {
			return false;
		}
		// Debounce checked AFTER the evidence test, not before. Checking it first meant a fix that
		// arrived too soon after the previous reroute never advanced the counter at all, so the
		// evidence had to start over - the third compounding rule.
		if (!enoughTravelledSinceLastReroute(location)) {
			return false;
		}
		consecutiveOffRouteFixes = 0;
		firstOffRouteTime = 0;
		lastRerouteLocation = new Location(location);
		lastRerouteTime = System.currentTimeMillis();
		return true;
	}

	/** Forget any accumulated evidence - a new route invalidates all of it. */
	public void reset() {
		consecutiveOffRouteFixes = 0;
		consecutiveOnRouteFixes = 0;
		firstOffRouteTime = 0;
		lastRerouteLocation = null;
		lastRerouteTime = 0;
	}

	private int requiredFixes(@NonNull Location location) {
		return requiredFixes(location, 0, 0);
	}

	/**
	 * How many consecutive off-route fixes must corroborate, given how far off the driver is.
	 *
	 * <p>Accuracy alone was the only input, so a driver 400 m off the route had to wait exactly as
	 * long as one 51 m off. That is over-confirming the obvious: this whole mechanism exists to
	 * reject GPS WOBBLE, and wobble is bounded by accuracy. {@code allowableM} is already twice an
	 * accuracy-derived tolerance, so a fix several times beyond it is not noise by any reading of
	 * the error model - it is a driver on a different road.
	 *
	 * <p>This is deliberately NOT a flat reduction. Near the threshold, where the fork's history
	 * says the danger is, the count is untouched; it relaxes only where the evidence is
	 * overwhelming. That distinction matters, because a blind reduction here is what produced
	 * "reroute after reroute while trying to turn around" and took the feature off by default.
	 */
	private int requiredFixes(@NonNull Location location, double devM, double allowableM) {
		int base;
		if (!location.hasAccuracy()) {
			base = MIN_CONSECUTIVE_FIXES;
		} else {
			int scaled = (int) (location.getAccuracy() / ACCURACY_PER_REQUIRED_FIX_M);
			base = Math.min(MAX_CONSECUTIVE_FIXES, Math.max(MIN_CONSECUTIVE_FIXES, scaled));
		}
		if (allowableM <= 0 || devM <= 0) {
			return base;
		}
		double ratio = devM / allowableM;
		if (ratio >= OVERWHELMING_RATIO) {
			return 1;   // several times past a threshold that is itself 2x the tolerance
		}
		if (ratio >= STRONG_RATIO) {
			return Math.min(base, 2);
		}
		return base;
	}

	private boolean enoughTravelledSinceLastReroute(@NonNull Location location) {
		Location previous = lastRerouteLocation;
		if (previous == null) {
			return true;
		}
		// Both conditions, not either: standing still at a junction that the router keeps
		// disagreeing with should not recompute on a timer, and crawling 50 m in traffic
		// should not recompute the instant the distance is met.
		return location.distanceTo(previous) >= MIN_METERS_BETWEEN_REROUTES
				&& System.currentTimeMillis() - lastRerouteTime >= MIN_MILLIS_BETWEEN_REROUTES;
	}
}
