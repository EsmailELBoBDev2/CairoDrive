package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;

import net.osmand.Location;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.helpers.CairoDriveLog;

/**
 * The last of the reroute latency, taken from the two SAFETY rules that own it - with a guard that
 * takes it back automatically if the drive says it was a mistake.
 *
 * <h3>Why this is a different kind of change from everything before it</h3>
 *
 * A 40k-trial simulation of the whole wait ({@code tools/sim/reroute_sim.py}) closed the routing
 * question outright: at 8 s of offline search the median wait is 14.7 s, and at 1 s it is 14.5 s.
 * The search time does not move the number, because the early start begins it at half the deviation
 * threshold and the answer is already waiting by the time the deviation confirms.
 *
 * <p>So there is nothing left to make faster. There is only something left to make LOOSER. The same
 * simulation prices it exactly:
 *
 * <pre>
 *   today                                  14.6 s
 *   hysteresis removed                     10.2 s
 *   threshold halved again                  9.2 s
 * </pre>
 *
 * <p>An earlier version of this note also quoted "5.2 s, both plus an instant search". That figure
 * is RETRACTED: it came from a model that removed the rules without keeping the floor, and sweeping
 * the actual shipped knobs puts the most aggressive reachable configuration at 7.4 s, 1.4 s better
 * than what ships here and at a 3.2% chance of rerouting a driver who never left the route. See
 * {@link #TOLERANCE_MULT} for the whole sweep.
 *
 * <p>Every second of that comes out of caution, and this fork has already paid once for taking it:
 * loosening these two rules is what produced "reroute after reroute while trying to turn around"
 * and took {@code CAIRODRIVE_OFFROUTE_HYSTERESIS} off by default for months.
 *
 * <h3>What makes it testable in ONE drive rather than a gamble</h3>
 *
 * The owner gets one drive per build. Shipping a loosened rule and hoping is not a test - a bad
 * flip ruins the drive AND produces no usable data, because a flapping route hides everything else
 * in the log.
 *
 * <p>So the aggressive settings carry their own falsification test and disarm themselves.
 * {@link #rerouteHappened} counts reroutes in a rolling window; real wrong turns are minutes apart,
 * flapping is seconds apart. On {@link #FLAP_COUNT} inside {@link #FLAP_WINDOW_MS} the whole
 * package latches back to the conservative values for the rest of the session and says so in the
 * log. The rest of the drive is then a normal drive, still worth analysing.
 *
 * <p>That is the entire point: a wrong answer here costs one logged line and the old behaviour,
 * not a wasted trip.
 *
 * <h3>The floor is not negotiable</h3>
 *
 * {@link #MIN_ALLOWABLE_M} bounds how tight this can ever make the threshold, no matter how the
 * multipliers compose. Below roughly this distance a lane change on a wide Cairo arterial, or
 * ordinary jitter on a degraded fix, IS larger than the threshold - and then the app rebuilds the
 * route because the driver moved within their own lane. That is not a latency trade, it is a broken
 * app, so it is enforced as a floor rather than trusted to the arithmetic.
 */
public final class CairoDriveFastReroute {

	/** NO "CD_" prefix: {@link CairoDriveLog#log} adds it. */
	private static final String TRACE_TAG = "FAST_REROUTE";

	/**
	 * Global tightening of the off-route threshold, on top of the near-manoeuvre halving.
	 *
	 * <p>0.5, chosen by sweeping the knob rather than by argument. The sweep measures two things at
	 * once: the median wait, and how often a driver CORRECTLY in an outer lane of a wide arterial
	 * is already past the threshold - which is the number that decides whether a drive is usable at
	 * all, because a reroute fired on a correct road is worse than any latency.
	 *
	 * <pre>
	 *   mult  floor  fixes    median   p90   false-positive risk
	 *   0.6   30 m   3         8.8    16.3        0.0%
	 *   0.5   30 m   3         8.5    14.3        0.0%     &lt;- here
	 *   0.45  25 m   2         8.3    12.2        0.2%
	 *   0.4   20 m   2         7.9    10.8        0.5%
	 *   0.3   15 m   1         7.4     9.3        3.2%
	 * </pre>
	 *
	 * <p>0.5 is the last row that is FREE: it takes p90 from 16.3 s to 14.3 s with the false-positive
	 * rate still measuring zero. Everything past it buys tenths of a second at a rate of risk that
	 * climbs fast, and the bottom row - which an earlier note in this project wrongly priced at
	 * "5.2 s" - is worth 1.4 s over what ships here and costs a 3.2% chance of rerouting a driver
	 * who never left the route.
	 *
	 * <p>Note the composition: at a junction, where most wrong turns happen, the near-manoeuvre rule
	 * has already halved the threshold, so the driver gets {@code 0.5 * 0.5 = 0.25} of the default
	 * and {@link #MIN_ALLOWABLE_M} is doing the real work. Away from a junction this is the only
	 * tightening that applies, which is the case the near-manoeuvre rule could never reach - a
	 * missed motorway exit noticed late, or a driver who drifts onto a service road mid-block.
	 */
	private static final double TOLERANCE_MULT = 0.5;

	/**
	 * Never let the composed threshold go below this, in metres.
	 *
	 * <p>See the class note. This is the line between "notices a wrong turn sooner" and "reroutes
	 * because the driver changed lane", and it is a floor rather than a target.
	 */
	private static final double MIN_ALLOWABLE_M = 30;

	/**
	 * Cap on the consecutive off-route fixes the hysteresis may demand.
	 *
	 * <p>The unmodified rule scales 3-20 by GPS accuracy, and on this device's degraded fixes -
	 * 55% of them - that lands near the top of the range: 20 fixes is 20 seconds of driving past
	 * the point where the app already believed the driver was off route.
	 *
	 * <p>Three is not arbitrary. It is {@code MIN_CONSECUTIVE_FIXES}, i.e. the corroboration the
	 * rule demands on a PERFECT fix. This does not invent a weaker rule; it says a degraded fix
	 * should not be trusted less than three times over, because at 20 the accuracy scaling stopped
	 * protecting anything and started just being slow.
	 *
	 * <p>MEASURED CAVEAT: requiredFixes scales as {@code accuracy / 4}, so base reaches 20 only at
	 * 80 m of reported accuracy and reaches 4 only at 16 m. This drive's fixes report 2.1-2.5 m, so
	 * base is already MIN_CONSECUTIVE_FIXES = 3 and {@code min(3, 3)} changes nothing. On this
	 * device the cap is inert and the distance tightening is doing all the work - do not read a
	 * good drive as evidence that capping the hysteresis was safe, because it was never exercised.
	 */
	private static final int MAX_REQUIRED_FIXES = 3;

	/** Reroutes inside {@link #FLAP_WINDOW_MS} that mean this is flapping, not driving. */
	private static final int FLAP_COUNT = 4;
	private static final long FLAP_WINDOW_MS = 90_000;

	private static final long[] recentReroutes = new long[FLAP_COUNT];
	private static volatile int rerouteIndex;
	private static volatile boolean disarmed;
	private static volatile int applied;

	private CairoDriveFastReroute() {
	}

	/** True while the aggressive settings are in force. False once the guard has taken them back. */
	public static boolean isActive() {
		return BuildConfig.CAIRODRIVE_FAST_REROUTE && !disarmed;
	}

	/**
	 * The off-route threshold this fix should be judged against.
	 *
	 * @param allowableM what upstream (plus the near-manoeuvre rule) already decided
	 * @return the same value when inactive - so the flag off is byte-for-byte the old behaviour
	 */
	public static double tolerance(double allowableM) {
		if (!isActive() || allowableM <= 0) {
			return allowableM;
		}
		double tightened = allowableM * TOLERANCE_MULT;
		// The floor applies to the COMPOSED value, which is why it lives here and not beside the
		// multiplier: near a manoeuvre this is the second tightening, and 0.5 * 0.5 of a 50 m
		// threshold is 12.5 m - inside the jitter of a degraded fix.
		if (tightened < MIN_ALLOWABLE_M) {
			tightened = Math.min(allowableM, MIN_ALLOWABLE_M);
		}
		// Counted only when the value actually MOVED. Incrementing unconditionally made this a
		// count of guided fixes - thousands on any drive - printed under the name "tightenedFixes",
		// which is the same kind of mislabel as the routingTime/routeCostSec unit error that cost
		// this project a whole investigation. When the floor clamps the result back to allowableM,
		// nothing was tightened and nothing is counted.
		if (tightened != allowableM) {
			applied++;
		}
		return tightened;
	}

	/**
	 * The consecutive-fix count the hysteresis should demand.
	 *
	 * @param required what {@code CairoDriveOffRoute.requiredFixes} computed from GPS accuracy
	 * @return the same value when inactive, never more than it, and never below the caller's own
	 *         strong-evidence reductions - this only ever lowers a HIGH count, it cannot raise one
	 */
	public static int requiredFixes(int required) {
		if (!isActive()) {
			return required;
		}
		return Math.min(required, MAX_REQUIRED_FIXES);
	}

	/**
	 * A reroute was actually dispatched. This is the falsification test.
	 *
	 * <p>Called on the dispatch, not on the deviation: a deviation that the hysteresis refused cost
	 * nothing and is not evidence of anything. What is being counted is the app deciding, four
	 * times inside a minute and a half, that the route it just built is already wrong - which no
	 * amount of genuine wrong turns produces, and which is exactly what over-tightening looks like.
	 */
	public static void rerouteHappened(long now) {
		if (!isActive()) {
			return;
		}
		try {
			synchronized (recentReroutes) {
				recentReroutes[rerouteIndex % FLAP_COUNT] = now;
				rerouteIndex++;
				if (rerouteIndex < FLAP_COUNT) {
					return;
				}
				long oldest = Long.MAX_VALUE;
				for (long t : recentReroutes) {
					if (t > 0 && t < oldest) {
						oldest = t;
					}
				}
				if (now - oldest <= FLAP_WINDOW_MS) {
					disarmed = true;
					CairoDriveLog.log(TRACE_TAG, "DISARMED after " + FLAP_COUNT
							+ " reroutes in " + ((now - oldest) / 1000) + "s"
							+ " - the tightened threshold is producing reroutes faster than a"
							+ " driver produces wrong turns. Conservative rules restored for the"
							+ " rest of this session; the rest of this drive is still valid data.");
				}
			}
		} catch (Throwable t) {
			// A guard must never be able to break the thing it guards.
			disarmed = true;
		}
	}

	/**
	 * Navigation stopped. NOT called when the route is merely replaced.
	 *
	 * <p>That distinction is the whole detector. Flapping IS repeated route replacement, so
	 * clearing the window on a new route would reset the counter on exactly the event it exists to
	 * count, and it could never reach {@link #FLAP_COUNT} however badly the app misbehaved.
	 *
	 * <p>{@code disarmed} deliberately survives this. Once a drive has demonstrated that the
	 * tightened threshold flaps, restarting navigation is not evidence that it stopped.
	 */
	public static void navigationStopped() {
		synchronized (recentReroutes) {
			java.util.Arrays.fill(recentReroutes, 0L);
			rerouteIndex = 0;
		}
	}

	/**
	 * One line saying whether this was in force and whether it survived.
	 *
	 * <p>Without it a drive with no reroutes and a drive that disarmed in the first minute look
	 * identical in the log, and they mean opposite things.
	 */
	public static void logSummary() {
		try {
			CairoDriveLog.log(TRACE_TAG, "summary flag=" + BuildConfig.CAIRODRIVE_FAST_REROUTE
					+ " disarmed=" + disarmed
					+ " tightenedFixes=" + applied
					+ " toleranceMult=" + TOLERANCE_MULT
					+ " floorM=" + (int) MIN_ALLOWABLE_M
					+ " maxFixes=" + MAX_REQUIRED_FIXES);
		} catch (Throwable ignored) {
		}
	}

	/** For the log line that reports what a fix was actually judged against. */
	public static void logDecision(@NonNull Location at, double devM, double beforeM, double afterM) {
		try {
			CairoDriveLog.log(TRACE_TAG, "tightened devM=" + Math.round(devM)
					+ " was=" + Math.round(beforeM) + " now=" + Math.round(afterM)
					+ " offRouteNow=" + (devM > afterM)
					+ " wouldHaveBeen=" + (devM > beforeM)
					+ " acc=" + (at.hasAccuracy() ? Math.round(at.getAccuracy()) : -1));
		} catch (Throwable ignored) {
		}
	}
}
