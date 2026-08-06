package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.binary.RouteDataObject;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.helpers.CairoDriveLog;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.router.RouteSegmentResult;

import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Notices a deviation by WHICH ROAD the driver is on, rather than by how far they are from the
 * route line.
 *
 * <h3>The wait this attacks</h3>
 *
 * After the early-start work, the largest remaining term in a reroute is not the search and not
 * the confirmation - it is the time the driver spends physically travelling far enough from the
 * route to be noticed. Upstream calls them off route at {@code distOrth > allowableDeviation},
 * which is 50-120 m depending on GPS accuracy. At Cairo speeds that is seconds of pure travel and
 * no routing optimisation can reach it.
 *
 * <p>But distance is a proxy. The real question is whether the driver is on a road that belongs
 * to the route, and this fork already computes exactly that: {@link CairoDriveMapMatcher} runs an
 * HMM over the local road network and publishes the road id it settled on. A parallel Cairo
 * street 30 m away is unambiguously a different road while still being far inside the distance
 * threshold.
 *
 * <h3>Why the matcher's confidence is not used, and what is used instead</h3>
 *
 * {@code Match.confidence} is normalised over the SURVIVORS, so when pruning leaves one candidate
 * it reads 1.0 whether or not the road is right - {@link CairoDriveMapMatching} says so in as
 * many words. It is confidently wrong exactly when it matters.
 *
 * <p>{@code settledDepth == 0} is the honest signal, and it is stronger than "the matcher is
 * sure". It means the current Viterbi column has ONE survivor, every rival having fallen more
 * than the prune margin behind. On a healthy fix that takes roughly 17 m of extra offset; on a
 * degraded fix the sigma widens so far that nothing but a topologically unreachable rival can
 * lose by that much. So it reads: <em>healthy fix with the rivals clearly further away, or every
 * rival unreachable through the road network.</em>
 *
 * <p>The cost of that strength is coverage. This device reports a 55/45 degraded/healthy fix mix,
 * and on a degraded fix at a fork the matcher will never settle - so this is silent on more than
 * half of all fixes, by construction. That is the correct behaviour and it is also the honest
 * ceiling on what the feature can buy.
 *
 * <h3>What it is allowed to do</h3>
 *
 * Two flags, because there are two very different risks.
 *
 * <ul>
 *   <li>{@code CAIRODRIVE_WRONG_ROAD} - evaluate, log, and feed the EARLY START. Zero route risk:
 *       the early start already discards its result unless the ordinary distance test confirms,
 *       so a false firing costs one wasted calculation.</li>
 *   <li>{@code CAIRODRIVE_WRONG_ROAD_ACT} - let it reach the hysteresis, i.e. let it actually
 *       trigger a reroute. OFF until a drive proves it. A false firing here is a reroute on a
 *       correct route, which is precisely the failure the off-route hysteresis was written to
 *       stop and which has already cost this project a drive.</li>
 * </ul>
 *
 * <p>{@code isDeviatedFromRoute} is deliberately NOT touched by this class. It drives the Android
 * Auto off-route card, the widgets and the voice suppression, and it is the evidence
 * {@code CairoDriveEarlyReroute.mayInstall} tests. A matcher error must not be able to blank the
 * manoeuvre card, silence a turn prompt, or satisfy the install gate on its own.
 */
public final class CairoDriveWrongRoad {

	/** NO "CD_" prefix: {@link CairoDriveLog#log} adds it. */
	private static final String TRACE_TAG = "WRONGROAD";

	/**
	 * Below this the driver is too close to the route line to trust a road-id difference.
	 *
	 * <p>The single most valuable rule here. A flyover directly above the route and the opposite
	 * carriageway of a dual carriageway are both DIFFERENT roads with different ids sitting at
	 * almost zero orthogonal distance - and the flyover case is the one the matcher is measurably
	 * worst at. Requiring real separation kills both outright, and 20 m is still well under half
	 * the 50-120 m the distance test needs.
	 */
	private static final double MIN_DEV_M = 20;

	/** Stricter than the display path's gate: its worst case is a misplaced arrow, ours is a reroute. */
	private static final double MAX_OFFSET_M = 12;

	/** The match must have been computed near where the driver is now, not two junctions ago. */
	private static final double MAX_SOURCE_DRIFT_M = 20;

	/**
	 * Consecutive qualifying matches on the SAME off-route road before this fires.
	 *
	 * <p>The matcher picks a wrong road on roughly 0.5-1% of applied matches. One is therefore not
	 * evidence. Two costs 12-24 m of extra travel - one to two seconds - and that is the price of
	 * not rerouting a driver who is on the correct road.
	 */
	private static final int REQUIRED_RUN = 2;

	/**
	 * Silence after a new route is installed.
	 *
	 * <p>{@code RoutingHelper.setRoute} resets the matcher, so its Viterbi chain restarts cold.
	 * Judging a route the matcher has not seen yet is judging noise.
	 */
	private static final long SETTLE_AFTER_ROUTE_MS = 5000;

	private static volatile Set<Long> routeRoadIds;
	private static volatile long routeInstalledAt;
	private static volatile long runRoadId;
	private static volatile int runLength;
	private static volatile long lastMatchTime;

	// Gate counters, reset when the summary is written. They exist so a drive can say WHY the
	// feature was silent, which is the first question a zero firing count raises.
	private static volatile int seen, gateStale, gateSettled, gateDegraded, gateOffset, gateDev,
			gateRun, gateOnRoute, fired;

	private CairoDriveWrongRoad() {
	}

	/**
	 * A new route is live. Rebuild the id set once, here, rather than per fix.
	 *
	 * <p>Any segment without a usable id makes the whole route untestable - a missing id would
	 * read as "not on the route" and produce a false deviation on a road the driver is correctly
	 * following. Refusing the whole route is the safe direction; the distance test is unaffected.
	 */
	public static void onRouteChanged(@Nullable RouteCalculationResult route) {
		runRoadId = 0;
		runLength = 0;
		routeInstalledAt = System.currentTimeMillis();
		try {
			if (route == null || !route.isCalculated()) {
				routeRoadIds = null;
				return;
			}
			List<RouteSegmentResult> segments = route.getImmutableAllSegments();
			if (segments == null || segments.isEmpty()) {
				routeRoadIds = null;
				CairoDriveLog.log(TRACE_TAG, "route has no segments - inert for this route");
				return;
			}
			Set<Long> ids = new HashSet<>();
			RouteSegmentResult prev = null;
			int bad = 0;
			for (RouteSegmentResult seg : segments) {
				if (seg == prev) {
					continue;   // the same segment repeats once per emitted location
				}
				prev = seg;
				RouteDataObject o = seg.getObject();
				if (o == null || o.getId() <= 0) {
					bad++;
					continue;
				}
				ids.add(o.getId());
			}
			if (bad > 0 || ids.isEmpty()) {
				routeRoadIds = null;
				CairoDriveLog.log(TRACE_TAG, "route badIds=" + bad + " roads=" + ids.size()
						+ " - inert for this route, a missing id would read as off-route");
				return;
			}
			routeRoadIds = Collections.unmodifiableSet(ids);
			CairoDriveLog.log(TRACE_TAG, "route roads=" + ids.size() + " segs=" + segments.size());
		} catch (Throwable t) {
			routeRoadIds = null;
		}
	}

	/**
	 * Is the driver on a road that is not part of the route?
	 *
	 * @param devM       orthogonal distance to the route line, as upstream measures it
	 * @param allowableM the threshold upstream would use to call this off route
	 * @return true only on strong, corroborated evidence. False whenever the matcher is off,
	 *         stale, unsure or degraded - never a guess.
	 */
	public static boolean evaluate(@NonNull OsmandApplication app, @Nullable Location fix,
	                               double devM, double allowableM, long now) {
		if (!BuildConfig.CAIRODRIVE_WRONG_ROAD) {
			return false;
		}
		try {
			Set<Long> ids = routeRoadIds;
			if (ids == null || fix == null) {
				return false;
			}
			if (now - routeInstalledAt < SETTLE_AFTER_ROUTE_MS) {
				return false;
			}
			CairoDriveMapMatcher.Match m = CairoDriveMapMatchService.getInstance(app).getLastMatch();
			if (m == null) {
				return false;   // flag off, watchdog latched, or nothing matched yet
			}
			if (m.fixTime == lastMatchTime) {
				return false;   // already counted; the service drops fixes while busy
			}
			lastMatchTime = m.fixTime;
			seen++;

			long age = now - m.fixTime;
			if (age < 0 || age > CairoDriveMapMatching.MAX_MATCH_AGE_MS) {
				gateStale++;
				return false;
			}
			if (m.settledDepth != CairoDriveMapMatching.MAX_SETTLED_DEPTH) {
				gateSettled++;
				return false;
			}
			if (m.degraded) {
				gateDegraded++;
				return false;
			}
			if (m.offsetM > MAX_OFFSET_M) {
				gateOffset++;
				return false;
			}
			// The rule that kills the flyover and the opposite carriageway: both are genuinely
			// different roads sitting almost on top of the route.
			if (devM < MIN_DEV_M) {
				gateDev++;
				return false;
			}
			if (ids.contains(m.roadId)) {
				gateOnRoute++;
				runRoadId = 0;
				runLength = 0;
				return false;
			}
			if (runRoadId == m.roadId) {
				runLength++;
			} else {
				runRoadId = m.roadId;
				runLength = 1;
			}
			if (runLength < REQUIRED_RUN) {
				gateRun++;
				return false;
			}
			fired++;
			CairoDriveLog.log(TRACE_TAG, "FIRED devM=" + Math.round(devM)
					+ " allowM=" + Math.round(allowableM)
					+ " roadId=" + m.roadId + " road=" + m.roadName
					+ " offM=" + Math.round(m.offsetM)
					+ " runLen=" + runLength
					+ " wouldBeOffRouteYet=" + (devM > allowableM)
					+ " act=" + BuildConfig.CAIRODRIVE_WRONG_ROAD_ACT);
			return true;
		} catch (Throwable t) {
			return false;
		}
	}

	/**
	 * May this evidence trigger a reroute, as opposed to merely starting one early?
	 *
	 * <p>Separate from {@link #evaluate} so the observation can ship, and be judged from a drive,
	 * without being able to move a route.
	 */
	public static boolean mayAct() {
		return BuildConfig.CAIRODRIVE_WRONG_ROAD_ACT;
	}

	/** Why the feature was silent, which is the first question a zero firing count raises. */
	public static void logSummary() {
		try {
			if (seen == 0) {
				return;
			}
			CairoDriveLog.log(TRACE_TAG, "summary matches=" + seen + " fired=" + fired
					+ " gated=[stale:" + gateStale + " unsettled:" + gateSettled
					+ " degraded:" + gateDegraded + " offset:" + gateOffset
					+ " tooClose:" + gateDev + " run:" + gateRun + " onRoute:" + gateOnRoute + "]");
			seen = fired = gateStale = gateSettled = gateDegraded = 0;
			gateOffset = gateDev = gateRun = gateOnRoute = 0;
		} catch (Throwable t) {
			// ignored
		}
	}
}
