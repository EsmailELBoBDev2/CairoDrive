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

	private int consecutiveOffRouteFixes;
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
		if (!isEnabled()) {
			return offRoute;
		}
		if (!offRoute) {
			// Back on the route: forget the run of bad fixes entirely. A deviation has to be
			// sustained, not merely accumulated over a drive.
			consecutiveOffRouteFixes = 0;
			return false;
		}
		if (location.hasAccuracy() && location.getAccuracy() > MAX_TRUSTED_ACCURACY_M) {
			// Not evidence. Leave the counter alone rather than resetting it, so a burst of
			// unusable fixes neither triggers nor forgives a genuine deviation.
			return false;
		}
		if (!enoughTravelledSinceLastReroute(location)) {
			return false;
		}
		consecutiveOffRouteFixes++;
		if (consecutiveOffRouteFixes < requiredFixes(location)) {
			return false;
		}
		consecutiveOffRouteFixes = 0;
		lastRerouteLocation = new Location(location);
		lastRerouteTime = System.currentTimeMillis();
		return true;
	}

	/** Forget any accumulated evidence - a new route invalidates all of it. */
	public void reset() {
		consecutiveOffRouteFixes = 0;
		lastRerouteLocation = null;
		lastRerouteTime = 0;
	}

	private int requiredFixes(@NonNull Location location) {
		if (!location.hasAccuracy()) {
			return MIN_CONSECUTIVE_FIXES;
		}
		int scaled = (int) (location.getAccuracy() / ACCURACY_PER_REQUIRED_FIX_M);
		return Math.min(MAX_CONSECUTIVE_FIXES, Math.max(MIN_CONSECUTIVE_FIXES, scaled));
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
