package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;

/**
 * N6 - the on/off switch and every tuning constant for the offline HMM map matcher.
 *
 * <h3>Why this class holds the switch instead of {@code cairodrive.gradle}</h3>
 *
 * Every other fork feature reads a {@code BuildConfig} field written by
 * {@code OsmAnd/cairodrive.gradle}. This one cannot, yet: the gradle file is being edited by
 * other work in parallel and a third hand in it produces exactly the kind of merge damage that
 * the fork's own notes warn about. So the flag is resolved REFLECTIVELY from
 * {@code net.osmand.plus.BuildConfig.CAIRODRIVE_MAP_MATCHING}, once, at class load:
 *
 * <ul>
 *   <li>the field is absent today, so the reflective read fails and the feature is <b>OFF</b>;</li>
 *   <li>the moment somebody adds
 *       {@code buildConfigField "boolean", "CAIRODRIVE_MAP_MATCHING", mapMatching}
 *       driven by {@code System.getenv("CAIRODRIVE_MAP_MATCHING")}, this class starts reading it
 *       with no source change here at all.</li>
 * </ul>
 *
 * The reflective lookup runs exactly once in a static initialiser, never on a fix.
 *
 * <p><b>Requested environment variable name: {@code CAIRODRIVE_MAP_MATCHING}, default
 * {@code false}.</b>
 *
 * <h3>Offline, not online</h3>
 *
 * N6 was originally scoped as "online map matching" - a remote Roads API. That was rejected. See
 * {@link CairoDriveMapMatcher} for the reasoning; the short version is that this app is
 * offline-first, drives on metered Egyptian mobile data behind {@link CairoDriveDataSaver}, and
 * already has the whole Cairo road network on disk in the {@code .obf}. A network round trip per
 * fix would be slower, cost money, leak the whole trace to a third party, and stop working in the
 * tunnels where matching is hardest.
 *
 * <h3>Fail-safe</h3>
 *
 * {@link #disable} latches the feature off for the rest of the process and records why. It is
 * called by the watchdog in {@link CairoDriveMapMatchService} when matching is too slow on this
 * device, and when the routing environment cannot be built. A feature that is off is exactly as
 * fast as not having built it; a feature that is quietly eating a big core on a two-big-core
 * phone already at 46.9 ms/frame is not.
 */
public final class CairoDriveMapMatching {

	/** The env var / BuildConfig field this feature wants. Absent today, so the default is off. */
	public static final String BUILD_FLAG_NAME = "CAIRODRIVE_MAP_MATCHING";

	private static final boolean BUILD_DEFAULT = resolveBuildFlag();

	private static volatile boolean enabled = BUILD_DEFAULT;
	private static volatile String disabledReason;

	private CairoDriveMapMatching() {
	}

	public static boolean isEnabled() {
		return enabled;
	}

	/** True when the build asked for matching but the runtime latched it off. */
	public static boolean isDisabledAtRuntime() {
		return BUILD_DEFAULT && !enabled;
	}

	public static String getDisabledReason() {
		return disabledReason;
	}

	/**
	 * Latches the feature off for the remainder of the process. Idempotent; the FIRST reason wins,
	 * because it is the one that explains the failure - later ones are consequences.
	 */
	static synchronized void disable(@NonNull String reason) {
		if (!enabled) {
			return;
		}
		enabled = false;
		disabledReason = reason;
		CairoDriveLogger.getInstance().log("CD_MATCH", "disabled reason=" + reason);
	}

	private static boolean resolveBuildFlag() {
		try {
			Class<?> buildConfig = Class.forName("net.osmand.plus.BuildConfig");
			Object value = buildConfig.getField(BUILD_FLAG_NAME).get(null);
			return value instanceof Boolean && (Boolean) value;
		} catch (Throwable ignored) {
			// The field does not exist yet. Off is the correct answer and the intended default.
			return false;
		}
	}

	// ------------------------------------------------------------------ emission model

	/**
	 * Smallest position sigma the matcher will believe, in metres.
	 *
	 * <p>Newson &amp; Krumm measured 4.07 m for their GPS logger and that is the number the
	 * literature uses. Android's {@code Location.getAccuracy()} is a 68% horizontal radius, i.e.
	 * roughly one sigma, so on a genuine GNSS fix the reported value can be used directly - but
	 * never below this floor. A matcher that believes a 1 m accuracy will refuse to move off the
	 * road it first picked.
	 */
	public static final double SIGMA_FLOOR_M = 4.0;

	/**
	 * Largest position sigma, in metres. Above this the emission term is meaningless anyway and
	 * the only effect of a larger value is a wider candidate radius and more work.
	 */
	public static final double SIGMA_CEILING_M = 60.0;

	/**
	 * <b>The single most important constant in N6.</b> Multiplier applied to the position sigma
	 * when the fix is network-derived - {@code satsUsed &lt; 4}, per
	 * {@link CairoDriveLogger#isGnssDegraded()}.
	 *
	 * <p>On the 2026-08-04 drive <b>55% of fixes had {@code satsUsed=0} while reporting 2.1-2.5 m
	 * accuracy</b>. Those are the fused provider answering from Wi-Fi and cell and stamping a
	 * confident number on it. Feeding that float into an HMM emission term as if it were one sigma
	 * is not a small error - it makes the emission term dominate everything, which is precisely
	 * the failure mode of nearest-road snapping that N6 exists to remove.
	 *
	 * <p>With {@link #SIGMA_FLOOR_M} applied first, a degraded fix therefore gets
	 * {@code max(2.2, 4.0) * 8 = 32 m}. At 32 m the log-likelihood gap between a candidate 3 m
	 * away and one 12 m away is 0.07 nats - nothing. That is deliberate: on a degraded fix the
	 * geometry gets almost no vote and the transition term (road topology and history) decides.
	 *
	 * <p>Simulated (n6_matchsim.py, 400 traces, flyover-over-street with the measured 55/45 fix
	 * mix). Correct-road rate against the multiplier, floor disabled so the multiplier alone acts:
	 *
	 * <pre>
	 *   mult   sigma      flyover trace   street trace
	 *    1.0     4 m          64.5%          45.9%     &lt;- no correction: HMM no better than nearest
	 *    2.0     8 m          66.2%          51.5%
	 *    4.0    16 m          79.9%          70.0%
	 *    6.0    24 m          90.3%          81.9%
	 *    8.0    32 m          92.6%          87.8%     &lt;- chosen, knee of the curve
	 *   12.0    48 m          92.9%          85.3%
	 *   20.0    80 m          92.5%          86.7%
	 *   40.0   160 m          91.7%          89.3%
	 * </pre>
	 *
	 * 8.0 is the knee. Everything above it is flat, and a larger value only widens the candidate
	 * radius and buys work.
	 */
	public static final double DEGRADED_SIGMA_MULT = 8.0;

	/** Candidate search radius, in sigmas. 3 sigma covers 98.9% of a 2D Gaussian. */
	public static final double CANDIDATE_RADIUS_SIGMAS = 3.0;
	/** Never search less than this. A tight sigma must not exclude the right road outright. */
	public static final double CANDIDATE_RADIUS_MIN_M = 25.0;
	/** Never search more than this - it bounds both the road load and the local graph. */
	public static final double CANDIDATE_RADIUS_MAX_M = 120.0;

	/** Maximum surviving hypotheses per fix. Cost is O(states^2) in transitions, so this is a budget. */
	public static final int MAX_CANDIDATES = 6;

	/**
	 * Log-likelihood margin for pruning, in nats. 9 nats is about 8000:1 - a hypothesis that far
	 * behind will not come back, and keeping it costs a full transition row every fix.
	 */
	public static final double PRUNE_LOG_MARGIN = 9.0;

	/**
	 * Penalty, in nats, for a one-way road whose direction of travel disagrees with the fix
	 * bearing by more than 90 degrees. Applied only above {@link #HEADING_MIN_SPEED_MS}, where the
	 * bearing means something.
	 *
	 * <p>Soft, not a veto. It separates the two carriageways of a dual carriageway, which is worth
	 * having. It does NOT separate a flyover from the street beneath it - those are parallel and
	 * share a bearing - so it is not the mechanism N6 relies on, and 1 nat (2.7:1) is enough to
	 * break a tie without being able to override a strong geometric or topological signal.
	 */
	public static final double HEADING_PENALTY_NATS = 1.0;
	public static final float HEADING_MIN_SPEED_MS = 3.0f;

	// ---------------------------------------------------------------- transition model

	/**
	 * Base of the exponential transition parameter beta, in metres.
	 *
	 * <p>Newson &amp; Krumm's transition probability is {@code (1/beta) * exp(-d_t / beta)} where
	 * {@code d_t = |great-circle distance between fixes - road-network distance between candidate
	 * projections|}. beta is the expected size of that discrepancy.
	 */
	public static final double BETA_BASE_M = 1.0;

	/**
	 * beta scales with the position sigma: {@code beta = BETA_BASE_M + BETA_SIGMA_FACTOR * sigma}.
	 *
	 * <p>This is physics, not a fudge. The route-versus-straight-line discrepancy is GENERATED by
	 * the position error - if the fix is 30 m off, the projected points move along and across the
	 * road by tens of metres and the two distances disagree by tens of metres. So the expected
	 * discrepancy is of the same order as the position error, which is a factor of 1.0.
	 *
	 * <p>It is also the second place the {@code satsUsed=0} correction enters, and empirically it
	 * matters as much as the sigma inflation does. Simulated correct-road rate:
	 *
	 * <pre>
	 *   factor   flyover trace   street trace
	 *    0.00        88.2%          46.8%   (beta constant - transition term far too strict)
	 *    0.25        92.4%          69.4%
	 *    0.50        92.6%          87.8%
	 *    1.00        92.9%          92.4%   &lt;- chosen: the knee, and the physically motivated value
	 *    2.00        92.9%          94.1%
	 *    3.00        92.8%          94.5%
	 *    5.00        92.4%          93.8%
	 * </pre>
	 *
	 * Above 1.0 the curve is nearly flat and the transition term gets progressively weaker, which
	 * would matter in a case the simulation does not cover (a genuine parallel service road that
	 * IS connected). 1.0 takes almost all the gain and keeps topology meaningful.
	 */
	public static final double BETA_SIGMA_FACTOR = 1.0;

	/**
	 * Discrepancy, in metres, charged when no road-network path exists between two candidates
	 * within the search bound.
	 *
	 * <p>Finite rather than infinite on purpose. The local graph is built from whatever the
	 * {@code .obf} yields around the fix, and Cairo data is incomplete - the fork has already
	 * measured that only ~2.5% of narrow streets carry the tags that would describe them. A hard
	 * veto would let one missing link kill the correct hypothesis permanently. 500 m over a
	 * beta of ~33 m is a penalty of roughly 15 nats, so an unreachable transition loses to any
	 * reachable one, but a run of them can still be overturned by later evidence.
	 */
	public static final double UNREACHABLE_PENALTY_M = 500.0;

	/**
	 * Slack, in metres, added to the great-circle step when bounding the road-distance search.
	 * A real road path can be longer than the straight line (that is the entire signal), but not
	 * unboundedly so at these step sizes.
	 */
	public static final double ROUTE_DIST_SLACK_M = 60.0;

	/** Hard cap on settled nodes in one bounded Dijkstra. Cost ceiling, not a tuning parameter. */
	public static final int MAX_ROUTE_DIST_NODES = 400;
	/** Hard cap on points admitted to the local graph for one fix. Cost ceiling. */
	public static final int MAX_GRAPH_POINTS = 3000;
	/** Roads further than this from the step midpoint are not admitted to the local graph. */
	public static final double GRAPH_RADIUS_M = 200.0;

	// ------------------------------------------------------------------ sampling gate

	/**
	 * Never process two fixes closer together than this. At 1 Hz - which is what the GNSS
	 * hardware produces and what CD_FIXRATE is measuring - this admits every fix at most once.
	 */
	public static final long MIN_INTERVAL_MS = 1000;

	/**
	 * Minimum movement between processed fixes, in metres.
	 *
	 * <p>Two reasons, and the second is the important one. It bounds work in stop-and-go traffic,
	 * where Cairo spends much of its time. And it improves the matcher: the transition term is a
	 * comparison between a road distance and a straight-line distance, and when the car has moved
	 * 1 m while the fix is 30 m noisy, that comparison is pure noise. 12 m is roughly three times
	 * a healthy sigma.
	 */
	public static final double MIN_STEP_M = 12.0;

	/** ... but never wait longer than this, so a stationary car still gets a periodic match. */
	public static final long MAX_STEP_MS = 5000;

	/**
	 * A gap longer than this breaks the Viterbi chain and the matcher restarts cold. After 20 s
	 * with no fix the car may be anywhere - a tunnel exit, a reboot, a resumed app - and carrying
	 * the old hypotheses forward would be worse than starting again.
	 */
	public static final long BROKEN_CHAIN_GAP_MS = 20000;

	/** Columns of Viterbi backpointers retained, for the convergence depth reported in CD_MATCH. */
	public static final int HISTORY_WINDOW = 16;

	// ------------------------------------------------------------------ watchdog

	/** A single fix taking longer than this on the worker thread counts as slow. */
	public static final long SLOW_MATCH_MS = 150;
	/** This many consecutive slow fixes latches the feature off. */
	public static final int MAX_CONSECUTIVE_SLOW = 5;
	/** Loaded routing tiles above this trigger an unload on the matcher's own context. */
	public static final int MAX_LOADED_TILES = 60;
	/** Memory budget handed to {@code RoutingContext.unloadUnusedTiles}. */
	public static final long TILE_MEMORY_LIMIT_BYTES = 8L * 1024 * 1024;
	/** Rebuild the routing environment this often, so a mid-drive map download is picked up. */
	public static final long ENV_MAX_AGE_MS = 30L * 60 * 1000;
	/** Interval between CD_MATCH summary lines. */
	public static final long SUMMARY_INTERVAL_MS = 60000;
}
