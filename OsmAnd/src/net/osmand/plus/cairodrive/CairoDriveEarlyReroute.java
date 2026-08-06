package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.plus.helpers.CairoDriveLog;

/**
 * Starts the reroute while the deviation is still being CONFIRMED, and installs it only if the
 * confirmation arrives.
 *
 * <h3>Where the seconds actually are</h3>
 *
 * An 8-second offline calculation is not the whole wait. Before it even starts, the app spends
 * 3-12 seconds deciding whether the driver has really left the route - {@code CairoDriveOffRoute}
 * requires several consecutive off-route fixes, scaled by GPS accuracy, because one bad fix under
 * a flyover must not trigger a reroute. That caution is correct and was tuned after a drive that
 * produced "reroute after reroute while trying to turn around".
 *
 * <p>But during that window the app already knows the driver is off the route. It is simply not
 * sure yet. And it does nothing with the time.
 *
 * <h3>Why this is not speculation</h3>
 *
 * Nothing here is predicted. The driver's wrong position is OBSERVED - they are at it. There is
 * no junction model, no guess about which exit was taken, no precomputed alternative that might
 * turn out to be for the wrong road. It is the same calculation the app was going to run anyway,
 * started at the first evidence instead of the last.
 *
 * <p>That is what makes it safe enough to leave on. The failure mode of a wrong guess is a wrong
 * route; the failure mode of THIS is a calculation whose answer is thrown away.
 *
 * <h3>The rule that keeps the route from flapping</h3>
 *
 * Starting early must not mean deciding early. The hysteresis exists to stop a GPS wobble from
 * moving the driver onto a new route, and an early START would defeat it if the result were
 * installed unconditionally.
 *
 * <p>So the calculation runs early and the DECISION stays where it was: a result computed for an
 * unconfirmed deviation is installed only if, by the time it is ready, the deviation has been
 * confirmed or the driver is still off route. If they drifted back, the answer is discarded and
 * the only cost is CPU nobody was using. The route is never moved on weaker evidence than before
 * this existed.
 *
 * <h3>Why no cancellation is needed</h3>
 *
 * The obvious worry is that an early calculation occupies the single routing thread when the real
 * reroute wants it. It cannot, because they are the same question: the early calculation was
 * started FOR this deviation, so when the deviation confirms there is nothing separate left to
 * run. {@link #confirm} marks the in-flight work as wanted rather than dispatching beside it.
 */
public final class CairoDriveEarlyReroute {

	/** NO "CD_" prefix: {@link CairoDriveLog#log} adds it. */
	private static final String TRACE_TAG = "EARLY_REROUTE";

	/**
	 * Least time between two early starts.
	 *
	 * <p>The whole cost of this feature is calculations begun for deviations that never confirm,
	 * so this is the only thing bounding it. Deliberately longer than a calculation takes: a
	 * second early start while the first is still running would queue behind it on the
	 * single-threaded executor and delay the answer it was meant to bring forward.
	 */
	private static final long MIN_INTERVAL_MS = 20_000;

	/** Beyond this an early result is answering a question the driver has moved on from. */
	private static final long MAX_AGE_MS = 30_000;

	/**
	 * How far the driver may have travelled between the early start and the install.
	 *
	 * <p>Not a tolerance for error - a bound on staleness. A route computed from a point the
	 * driver is now 150 m past starts behind them, and upstream would immediately measure that as
	 * a deviation from the new route and reroute again.
	 */
	private static final float MAX_DRIFT_M = 150f;

	private static volatile boolean inFlight;
	private static volatile boolean confirmed;
	private static volatile long startedAt;
	private static volatile Location startedFrom;
	private static volatile long lastStartAt;

	private CairoDriveEarlyReroute() {
	}

	/**
	 * May a calculation be started for a deviation that has not been confirmed yet?
	 *
	 * <p>Called on a fix that is off route while the hysteresis is still gathering evidence.
	 */
	public static boolean shouldStart(long now) {
		if (inFlight) {
			return false;
		}
		return now - lastStartAt >= MIN_INTERVAL_MS;
	}

	/** Record that an early calculation has been dispatched for {@code from}. */
	public static void started(@NonNull Location from, long now) {
		inFlight = true;
		confirmed = false;
		startedAt = now;
		lastStartAt = now;
		startedFrom = from;
		CairoDriveLog.log(TRACE_TAG, "started - deviation forming, not yet confirmed");
	}

	/**
	 * The deviation has now been confirmed by the ordinary hysteresis.
	 *
	 * @return true if an early calculation is already in flight for it, in which case the caller
	 *         must NOT dispatch another - the running one is the answer, and starting a second
	 *         would queue behind it and arrive later than doing nothing.
	 */
	public static boolean confirm() {
		if (!inFlight) {
			return false;
		}
		confirmed = true;
		CairoDriveLog.log(TRACE_TAG, "confirmed while in flight - no second dispatch");
		return true;
	}

	/**
	 * May the finished result be installed?
	 *
	 * <p>This is the hysteresis, applied at the end instead of the beginning. Everything the
	 * caution was protecting is still protected: a deviation that evaporated installs nothing.
	 *
	 * @param stillOffRoute whether the driver is off route on the fix that completed the work
	 * @param now           current time
	 * @param at            where the driver is now, for the staleness bound
	 */
	public static boolean mayInstall(boolean stillOffRoute, long now, @Nullable Location at) {
		boolean wasInFlight = inFlight;
		Location from = startedFrom;
		long age = now - startedAt;
		inFlight = false;
		startedFrom = null;
		if (!wasInFlight) {
			return true;   // not ours: an ordinary reroute, unchanged behaviour
		}
		if (!confirmed && !stillOffRoute) {
			CairoDriveLog.log(TRACE_TAG, "DISCARDED ageMs=" + age
					+ " - deviation did not confirm, driver back on route");
			return false;
		}
		if (age > MAX_AGE_MS) {
			CairoDriveLog.log(TRACE_TAG, "DISCARDED ageMs=" + age + " - too old to trust");
			return false;
		}
		if (at != null && from != null) {
			float drift = at.distanceTo(from);
			if (drift > MAX_DRIFT_M) {
				CairoDriveLog.log(TRACE_TAG, "DISCARDED driftM=" + Math.round(drift)
						+ " - computed from a point the driver has left behind");
				return false;
			}
			CairoDriveLog.log(TRACE_TAG, "USED ageMs=" + age + " driftM=" + Math.round(drift)
					+ " confirmed=" + confirmed + " - reroute began before it was asked for");
			return true;
		}
		CairoDriveLog.log(TRACE_TAG, "USED ageMs=" + age + " confirmed=" + confirmed);
		return true;
	}

	/** Route replaced, navigation stopped, or anything else that makes the question obsolete. */
	public static void reset() {
		if (inFlight) {
			CairoDriveLog.log(TRACE_TAG, "reset - route or session changed under an early start");
		}
		inFlight = false;
		confirmed = false;
		startedFrom = null;
	}

	public static boolean isInFlight() {
		return inFlight;
	}
}
