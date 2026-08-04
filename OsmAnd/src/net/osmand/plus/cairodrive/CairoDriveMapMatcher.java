package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.binary.RouteDataObject;
import net.osmand.data.QuadPointDouble;
import net.osmand.util.MapUtils;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * N6 - an OFFLINE hidden-Markov-model map matcher (Newson &amp; Krumm, 2009) over the road data
 * already in the {@code .obf}.
 *
 * <h3>Why offline rather than the online Roads API this was originally scoped as</h3>
 *
 * <ol>
 *   <li><b>The data is already here.</b> The matcher needs the geometry and the connectivity of
 *       every road within ~100 m of the car. The {@code .obf} routing index has exactly that, on
 *       disk, and the routing engine is already loading those same tiles for the route the driver
 *       is following. A remote call would be asking a server for something already in memory.</li>
 *   <li><b>Metered Egyptian mobile data.</b> Every network path in this fork goes through
 *       {@link CairoDriveDataSaver}. A per-fix HTTP request at ~1 Hz for a one-hour drive is
 *       thousands of round trips. Google's Roads API bills per request; the fork's own notes on
 *       the Places integration are explicit that a new endpoint is a new SKU and a new bill.</li>
 *   <li><b>It would be slower than the thing it is fixing.</b> A round trip on Cairo mobile data
 *       is 100-400 ms. The matched position is wanted for the current fix, not the one four fixes
 *       ago. The offline matcher's whole budget is a few milliseconds.</li>
 *   <li><b>It fails exactly where it is needed.</b> The flyover problem is worst in tunnels and
 *       under elevated structures - which is also where the mobile signal is worst, and where
 *       {@code satsUsed} is already 0.</li>
 *   <li><b>Privacy.</b> Sending the raw trace to a third party is a Play Data-safety disclosure of
 *       precise location. The fork accepted that for Places search because the user typed a query
 *       and asked for it. Nobody asks for map matching.</li>
 * </ol>
 *
 * Batch matching against a Roads API remains defensible for a post-hoc track cleanup. It is the
 * wrong shape for a live navigation display, which is what N6 is for.
 *
 * <h3>What already existed, and what is reused</h3>
 *
 * OsmAnd ships a GPX approximation path and it was evaluated first:
 * <ul>
 *   <li>{@code RoutePlannerFrontEnd.searchGpxRoute} ->
 *       {@code GpxRouteApproximation.searchGpxRouteByRouting}, which runs a full A*
 *       ({@code searchRouteInternalPrepare}) between consecutive GPX points;</li>
 *   <li>{@code GpxMultiSegmentsApproximation}, a best-first search over route segments scored by
 *       {@code maxDistToGpx / sqrt(gpxLen + 1)}.</li>
 * </ul>
 * Neither is reusable here. Both are BATCH - they take the whole track up front, and
 * {@code GpxApproximator} runs them on a single-thread executor over seconds. Neither is an HMM:
 * there is no emission probability, so neither has any way to express "this fix is a 2.2 m lie
 * from a Wi-Fi position", which is the entire problem on this device. And the routing variant
 * costs one A* per point pair, which is not a per-fix budget on a POCO C85.
 *
 * <p>What IS reused is the layer below them - the primitive both of those are built on:
 * {@code RoutingContext.loadTileData(x31, y31, 17, list)} for the local road set, and the
 * projection idea from {@code RoutePlannerFrontEnd.calcPreciseRouteSegmentPoint}
 * ({@code RoutePlannerFrontEnd.java:263}), reimplemented here without its per-candidate
 * {@code new RouteDataObject(r)} copy. The {@code RoutingContext} itself comes from
 * {@code RoutingHelper.getRoutingEnvironment} - the sanctioned way to get a throwaway context off
 * the navigation thread, which is what {@code GpxApproximator} uses too.
 *
 * <h3>The model</h3>
 *
 * States are candidate road segments near a fix. For fix {@code t} and candidate {@code c}:
 *
 * <pre>
 *   log P(emission) = -0.5 * (d(z_t, c) / sigma_t)^2        [- heading penalty]
 *   log P(transition from p) = -|gc(z_t-1, z_t) - route(p, c)| / beta_t
 * </pre>
 *
 * {@code route()} is the true road-network distance, from a bounded Dijkstra over the locally
 * loaded roads - not a straight line, which is what makes it able to say "you cannot get from the
 * street to the flyover here, there is no ramp". Viterbi runs forward over a pruned column of at
 * most {@link CairoDriveMapMatching#MAX_CANDIDATES} states.
 *
 * <h3>The satsUsed=0 correction, which is the whole design</h3>
 *
 * 55% of this device's fixes report 2.1-2.5 m accuracy with zero satellites used. Both
 * {@code sigma_t} and {@code beta_t} are inflated when {@link CairoDriveLogger#isGnssDegraded()}
 * says so - see {@link CairoDriveMapMatching#DEGRADED_SIGMA_MULT} and
 * {@link CairoDriveMapMatching#BETA_SIGMA_FACTOR} for the measured curves behind both.
 *
 * <p>Simulated over 400 traces of a flyover 6 m laterally from the street beneath it, with the
 * measured 55/45 degraded/healthy fix mix (n6_matchsim.py). Correct-road rate:
 *
 * <pre>
 *   trace     matcher            all fixes   under flyover   degraded fixes
 *   flyover   nearest-road           66.1%          61.6%          57.0%
 *   flyover   HMM, no correction     65.4%          61.9%          57.7%
 *   flyover   HMM (N6)               92.7%          97.3%          91.1%
 *   street    nearest-road           49.1%          36.4%          33.3%
 *   street    HMM, no correction     46.4%          32.2%          41.1%
 *   street    HMM (N6)               92.3%          94.4%          91.6%
 * </pre>
 *
 * The middle row is the point. <b>An HMM that trusts the reported accuracy is no better than
 * nearest-road snapping</b> - on the street trace it is worse. The gain comes from refusing to
 * believe the accuracy float, not from the Viterbi.
 *
 * <h3>Threading</h3>
 *
 * This class is not thread-safe and does no I/O. {@link CairoDriveMapMatchService} owns exactly
 * one instance and touches it only from its own worker thread.
 */
public class CairoDriveMapMatcher {

	/** Supplies the road data around a point. Implemented over {@code RoutingContext.loadTileData}. */
	public interface RoadSource {
		/** @return roads near the 31-coordinate, already filtered to ones the vehicle can use. */
		@NonNull
		List<RouteDataObject> loadRoadsAround(int x31, int y31, double radiusM);
	}

	/** What the matcher decided for one fix. Immutable; safe to publish to another thread. */
	public static final class Match {
		public final long roadId;
		public final String roadName;
		public final String highway;
		public final double lat;
		public final double lon;
		/** Distance from the raw fix to the matched point, metres. */
		public final double offsetM;
		/** Posterior over the surviving hypotheses, 0..1. */
		public final double confidence;
		public final int candidateCount;
		public final long nearestRoadId;
		public final double nearestOffsetM;
		public final boolean disagreesWithNearest;
		public final double sigmaM;
		public final double betaM;
		public final boolean degraded;
		public final double greatCircleM;
		public final double routeM;
		/** Steps back at which every surviving hypothesis agrees, or -1 if none within the window. */
		public final int settledDepth;

		/**
		 * 31-coordinates of the RAW fix this match was computed from, and its timestamp.
		 *
		 * <p>These exist for {@link CairoDriveMatchedPosition}, not for the log. The service is
		 * asynchronous and DROPS fixes while the worker is busy, so by the time a consumer reads
		 * {@link CairoDriveMapMatchService#getLastMatch()} the car has already moved. Without the
		 * source fix there is no way to ask how far, and a match is only meaningful near the
		 * position that produced it.
		 */
		public final int fixX31;
		public final int fixY31;
		public final long fixTime;

		/**
		 * 31-coordinates of the two ends of the matched road segment.
		 *
		 * <p>Also for {@link CairoDriveMatchedPosition}. Carrying the SEGMENT rather than only the
		 * matched point is what lets a later fix be re-projected onto the same road instead of
		 * being teleported back to where the car was when the match was computed. That difference
		 * is the whole of the along-track staleness error - see the class javadoc there.
		 */
		public final int segAX31;
		public final int segAY31;
		public final int segBX31;
		public final int segBY31;

		Match(long roadId, String roadName, String highway, double lat, double lon, double offsetM,
		      double confidence, int candidateCount, long nearestRoadId, double nearestOffsetM,
		      double sigmaM, double betaM, boolean degraded, double greatCircleM, double routeM,
		      int settledDepth, int fixX31, int fixY31, long fixTime,
		      int segAX31, int segAY31, int segBX31, int segBY31) {
			this.roadId = roadId;
			this.roadName = roadName;
			this.highway = highway;
			this.lat = lat;
			this.lon = lon;
			this.offsetM = offsetM;
			this.confidence = confidence;
			this.candidateCount = candidateCount;
			this.nearestRoadId = nearestRoadId;
			this.nearestOffsetM = nearestOffsetM;
			this.disagreesWithNearest = nearestRoadId != 0 && nearestRoadId != roadId;
			this.sigmaM = sigmaM;
			this.betaM = betaM;
			this.degraded = degraded;
			this.greatCircleM = greatCircleM;
			this.routeM = routeM;
			this.settledDepth = settledDepth;
			this.fixX31 = fixX31;
			this.fixY31 = fixY31;
			this.fixTime = fixTime;
			this.segAX31 = segAX31;
			this.segAY31 = segAY31;
			this.segBX31 = segBX31;
			this.segBY31 = segBY31;
		}
	}

	private static final class Candidate {
		final RouteDataObject road;
		final int segIdx;
		final int x31;
		final int y31;
		final double offsetM;
		double logProb;
		int back = -1;
		/** Road-network distance on the winning incoming transition. Reported in CD_MATCH. */
		double routeDistM = Double.NaN;

		Candidate(RouteDataObject road, int segIdx, int x31, int y31, double offsetM) {
			this.road = road;
			this.segIdx = segIdx;
			this.x31 = x31;
			this.y31 = y31;
			this.offsetM = offsetM;
		}
	}

	/** One Viterbi column, reduced to what the convergence walk needs. Holds no road references. */
	private static final class HistoryColumn {
		final long[] roadIds;
		final int[] backs;

		HistoryColumn(long[] roadIds, int[] backs) {
			this.roadIds = roadIds;
			this.backs = backs;
		}
	}

	private final RoadSource roadSource;

	private List<Candidate> column = new ArrayList<>();
	private final ArrayDeque<HistoryColumn> history = new ArrayDeque<>();

	private List<RouteDataObject> previousRoads = Collections.emptyList();
	private int previousX31;
	private int previousY31;
	private long previousTime;
	private boolean hasPrevious;

	public CairoDriveMapMatcher(@NonNull RoadSource roadSource) {
		this.roadSource = roadSource;
	}

	/** Drops all state. Called on a long gap, a new route, or a re-enable. */
	public void reset() {
		column = new ArrayList<>();
		history.clear();
		previousRoads = Collections.emptyList();
		hasPrevious = false;
	}

	/**
	 * Advances the model by one fix.
	 *
	 * @param degraded {@link CairoDriveLogger#isGnssDegraded()} for this fix - the caller reads it,
	 *                 because it is a property of the GNSS status stream and not of the Location.
	 * @return the match, or null when no road is within the candidate radius.
	 */
	@Nullable
	public Match update(double lat, double lon, float accuracyM, boolean hasAccuracy, boolean degraded,
	                    float bearingDeg, boolean hasBearing, float speedMs, boolean hasSpeed,
	                    long timeMs) {
		double sigma = sigmaFor(accuracyM, hasAccuracy, degraded);
		double beta = CairoDriveMapMatching.BETA_BASE_M + CairoDriveMapMatching.BETA_SIGMA_FACTOR * sigma;
		double radius = Math.min(CairoDriveMapMatching.CANDIDATE_RADIUS_MAX_M,
				Math.max(CairoDriveMapMatching.CANDIDATE_RADIUS_MIN_M,
						CairoDriveMapMatching.CANDIDATE_RADIUS_SIGMAS * sigma));

		int x31 = MapUtils.get31TileNumberX(lon);
		int y31 = MapUtils.get31TileNumberY(lat);

		List<RouteDataObject> roads = roadSource.loadRoadsAround(x31, y31, radius);
		List<Candidate> candidates = buildCandidates(roads, x31, y31, radius);
		if (candidates.isEmpty()) {
			// No road in range at all. Break the chain rather than carry a stale column across a
			// gap whose length we cannot bound.
			column = new ArrayList<>();
			history.clear();
			hasPrevious = false;
			return null;
		}

		Candidate nearest = candidates.get(0);
		for (Candidate c : candidates) {
			if (c.offsetM < nearest.offsetM) {
				nearest = c;
			}
		}

		for (Candidate c : candidates) {
			double z = c.offsetM / sigma;
			c.logProb = -0.5 * z * z;
			if (hasBearing && hasSpeed && speedMs > CairoDriveMapMatching.HEADING_MIN_SPEED_MS
					&& c.road.getOneway() != 0 && disagreesWithHeading(c, bearingDeg)) {
				c.logProb -= CairoDriveMapMatching.HEADING_PENALTY_NATS;
			}
			c.back = -1;
		}

		double greatCircle = 0;
		double chosenRouteDist = Double.NaN;
		boolean linked = hasPrevious && !column.isEmpty()
				&& (timeMs - previousTime) <= CairoDriveMapMatching.BROKEN_CHAIN_GAP_MS
				&& (timeMs - previousTime) >= 0;
		if (linked) {
			greatCircle = MapUtils.squareRootDist31(previousX31, previousY31, x31, y31);
			double dmax = greatCircle + Math.max(CairoDriveMapMatching.ROUTE_DIST_SLACK_M, 2 * sigma);
			LocalGraph graph = buildGraph(previousRoads, roads, column, candidates,
					(previousX31 >> 1) + (x31 >> 1), (previousY31 >> 1) + (y31 >> 1));
			double[][] routeDist = new double[column.size()][];
			for (int i = 0; i < column.size(); i++) {
				routeDist[i] = routeDistances(graph, column.get(i), candidates, dmax);
			}
			for (int j = 0; j < candidates.size(); j++) {
				Candidate c = candidates.get(j);
				double bestLog = Double.NEGATIVE_INFINITY;
				int bestBack = -1;
				double bestRoute = Double.NaN;
				for (int i = 0; i < column.size(); i++) {
					double rd = routeDist[i][j];
					double discrepancy = Double.isNaN(rd)
							? CairoDriveMapMatching.UNREACHABLE_PENALTY_M
							: Math.abs(greatCircle - rd);
					double lp = column.get(i).logProb - discrepancy / beta;
					if (lp > bestLog) {
						bestLog = lp;
						bestBack = i;
						bestRoute = rd;
					}
				}
				if (bestBack >= 0) {
					c.logProb += bestLog;
					c.back = bestBack;
					c.routeDistM = bestRoute;
				}
			}
		}

		double max = Double.NEGATIVE_INFINITY;
		for (Candidate c : candidates) {
			max = Math.max(max, c.logProb);
		}
		List<Candidate> survivors = new ArrayList<>(candidates.size());
		for (Candidate c : candidates) {
			if (c.logProb >= max - CairoDriveMapMatching.PRUNE_LOG_MARGIN) {
				c.logProb -= max;
				survivors.add(c);
			}
		}

		Candidate best = survivors.get(0);
		double norm = 0;
		for (Candidate c : survivors) {
			norm += Math.exp(c.logProb);
			if (c.logProb > best.logProb) {
				best = c;
			}
		}
		double confidence = norm > 0 ? Math.exp(best.logProb) / norm : 1.0;
		chosenRouteDist = best.routeDistM;

		pushHistory(survivors);
		int settled = convergenceDepth();

		column = survivors;
		previousRoads = roads;
		previousX31 = x31;
		previousY31 = y31;
		previousTime = timeMs;
		hasPrevious = true;

		return new Match(best.road.getId(), best.road.getName(), best.road.getHighway(),
				MapUtils.get31LatitudeY(best.y31), MapUtils.get31LongitudeX(best.x31),
				best.offsetM, confidence, survivors.size(),
				nearest.road.getId(), nearest.offsetM,
				sigma, beta, degraded, greatCircle, chosenRouteDist, settled,
				x31, y31, timeMs,
				best.road.getPoint31XTile(best.segIdx), best.road.getPoint31YTile(best.segIdx),
				best.road.getPoint31XTile(best.segIdx + 1), best.road.getPoint31YTile(best.segIdx + 1));
	}

	// ------------------------------------------------------------------ emission

	private static double sigmaFor(float accuracyM, boolean hasAccuracy, boolean degraded) {
		double sigma = hasAccuracy && accuracyM > 0
				? Math.max(accuracyM, CairoDriveMapMatching.SIGMA_FLOOR_M)
				: CairoDriveMapMatching.SIGMA_FLOOR_M;
		if (degraded) {
			// The reported accuracy is a Wi-Fi/cell position wearing a GNSS number. See
			// CairoDriveMapMatching.DEGRADED_SIGMA_MULT for the measured curve behind the 8.
			sigma *= CairoDriveMapMatching.DEGRADED_SIGMA_MULT;
		}
		return Math.min(sigma, CairoDriveMapMatching.SIGMA_CEILING_M);
	}

	private static boolean disagreesWithHeading(Candidate c, float bearingDeg) {
		RouteDataObject r = c.road;
		int ax = r.getPoint31XTile(c.segIdx);
		int ay = r.getPoint31YTile(c.segIdx);
		int bx = r.getPoint31XTile(c.segIdx + 1);
		int by = r.getPoint31YTile(c.segIdx + 1);
		// 31-coordinates are Mercator, which is conformal, so an angle taken in tile units is the
		// true compass bearing over a segment this short. y31 increases southward, hence -dy.
		double segBearing = Math.toDegrees(Math.atan2((double) bx - ax, (double) ay - by));
		if (r.getOneway() < 0) {
			segBearing += 180;
		}
		double diff = Math.abs(MapUtils.degreesDiff(bearingDeg, segBearing));
		return diff > 90;
	}

	private List<Candidate> buildCandidates(List<RouteDataObject> roads, int x31, int y31, double radius) {
		List<Candidate> out = new ArrayList<>();
		for (RouteDataObject r : roads) {
			int len = r.getPointsLength();
			if (len < 2) {
				continue;
			}
			double bestDist = Double.MAX_VALUE;
			int bestSeg = -1;
			int bestX = 0;
			int bestY = 0;
			for (int i = 1; i < len; i++) {
				QuadPointDouble pr = MapUtils.getProjectionPoint31(x31, y31,
						r.getPoint31XTile(i - 1), r.getPoint31YTile(i - 1),
						r.getPoint31XTile(i), r.getPoint31YTile(i));
				int px = (int) pr.x;
				int py = (int) pr.y;
				double d = MapUtils.squareRootDist31(px, py, x31, y31);
				if (d < bestDist) {
					bestDist = d;
					bestSeg = i - 1;
					bestX = px;
					bestY = py;
				}
			}
			if (bestSeg >= 0 && bestDist <= radius) {
				out.add(new Candidate(r, bestSeg, bestX, bestY, bestDist));
			}
		}
		Collections.sort(out, (a, b) -> Double.compare(a.offsetM, b.offsetM));
		while (out.size() > CairoDriveMapMatching.MAX_CANDIDATES) {
			out.remove(out.size() - 1);
		}
		return out;
	}

	// ------------------------------------------------------------------ Viterbi history

	private void pushHistory(List<Candidate> survivors) {
		long[] ids = new long[survivors.size()];
		int[] backs = new int[survivors.size()];
		for (int i = 0; i < survivors.size(); i++) {
			ids[i] = survivors.get(i).road.getId();
			backs[i] = survivors.get(i).back;
		}
		history.addLast(new HistoryColumn(ids, backs));
		while (history.size() > CairoDriveMapMatching.HISTORY_WINDOW) {
			history.removeFirst();
		}
	}

	/**
	 * How many steps back every surviving hypothesis agrees on the same ancestor.
	 *
	 * <p>This is the honest confidence signal for a Viterbi matcher and it is what the posterior
	 * alone cannot say. A depth of 2 means the last two fixes are still genuinely ambiguous but
	 * everything before them is decided - which is exactly the state the matcher is in halfway
	 * along a flyover, and exactly what a drive log needs to show.
	 *
	 * @return depth in fixes, or -1 if the hypotheses have not merged within the window.
	 */
	private int convergenceDepth() {
		if (history.isEmpty()) {
			return -1;
		}
		HistoryColumn[] cols = history.toArray(new HistoryColumn[0]);
		int last = cols.length - 1;
		if (cols[last].backs.length <= 1) {
			return 0;
		}
		boolean[] active = new boolean[cols[last].backs.length];
		for (int i = 0; i < active.length; i++) {
			active[i] = true;
		}
		for (int depth = 1; depth <= last; depth++) {
			HistoryColumn cur = cols[last - depth + 1];
			int prevSize = cols[last - depth].backs.length;
			boolean[] next = new boolean[prevSize];
			int count = 0;
			for (int i = 0; i < cur.backs.length; i++) {
				if (!active[i]) {
					continue;
				}
				int b = cur.backs[i];
				if (b < 0 || b >= prevSize) {
					return -1;
				}
				if (!next[b]) {
					next[b] = true;
					count++;
				}
			}
			if (count == 0) {
				return -1;
			}
			if (count == 1) {
				return depth;
			}
			active = next;
		}
		return -1;
	}

	// ------------------------------------------------------------------ local road graph

	/**
	 * A point-level graph over the roads loaded around the previous and current fix.
	 *
	 * <p>Connectivity is by identical 31-coordinate, which is exactly how OsmAnd's own routing
	 * graph joins roads ({@code RoutingContext.loadRouteSegment} hashes on x31/y31). That is what
	 * makes "there is no ramp here" expressible: the flyover polyline and the street polyline
	 * beneath it share no point, so no path exists between them except through the ramp.
	 */
	private static final class LocalGraph {
		final List<RouteDataObject> roads = new ArrayList<>();
		final Map<Long, Integer> roadIndexById = new HashMap<>();
		final Map<Long, int[]> refsByCoord = new HashMap<>();
		int points;

		boolean add(RouteDataObject road) {
			long id = road.getId();
			if (roadIndexById.containsKey(id)) {
				return true;
			}
			int len = road.getPointsLength();
			if (len < 2 || len > 60000 || points + len > CairoDriveMapMatching.MAX_GRAPH_POINTS) {
				return false;
			}
			int roadIdx = roads.size();
			roads.add(road);
			roadIndexById.put(id, roadIdx);
			points += len;
			for (int i = 0; i < len; i++) {
				long key = coordKey(road.getPoint31XTile(i), road.getPoint31YTile(i));
				int ref = (roadIdx << 16) | i;
				int[] existing = refsByCoord.get(key);
				if (existing == null) {
					refsByCoord.put(key, new int[] {ref});
				} else {
					int[] grown = new int[existing.length + 1];
					System.arraycopy(existing, 0, grown, 0, existing.length);
					grown[existing.length] = ref;
					refsByCoord.put(key, grown);
				}
			}
			return true;
		}

		int indexOf(RouteDataObject road) {
			Integer idx = roadIndexById.get(road.getId());
			return idx == null ? -1 : idx;
		}
	}

	private static long coordKey(int x31, int y31) {
		return (((long) x31) << 32) | (y31 & 0xffffffffL);
	}

	private LocalGraph buildGraph(List<RouteDataObject> prevRoads, List<RouteDataObject> curRoads,
	                              List<Candidate> prevColumn, List<Candidate> curCandidates,
	                              int midX31, int midY31) {
		LocalGraph graph = new LocalGraph();
		// Candidate roads first and unconditionally - a candidate that is not in the graph can
		// never be reached, which would silently convert it into an unreachable transition.
		for (Candidate c : prevColumn) {
			graph.add(c.road);
		}
		for (Candidate c : curCandidates) {
			graph.add(c.road);
		}
		addNearby(graph, prevRoads, midX31, midY31);
		addNearby(graph, curRoads, midX31, midY31);
		return graph;
	}

	private void addNearby(LocalGraph graph, List<RouteDataObject> roads, int midX31, int midY31) {
		for (RouteDataObject r : roads) {
			if (graph.points >= CairoDriveMapMatching.MAX_GRAPH_POINTS) {
				return;
			}
			int len = r.getPointsLength();
			if (len < 2) {
				continue;
			}
			boolean near = false;
			// Sampled rather than exhaustive: a road is admitted if ANY of its points is within
			// the radius, and OSM geometry is dense enough that sampling every fourth point
			// cannot miss a road that actually passes through the window.
			for (int i = 0; i < len; i += 4) {
				if (MapUtils.squareRootDist31(r.getPoint31XTile(i), r.getPoint31YTile(i), midX31, midY31)
						<= CairoDriveMapMatching.GRAPH_RADIUS_M) {
					near = true;
					break;
				}
			}
			if (near) {
				graph.add(r);
			}
		}
	}

	// ------------------------------------------------------------------ bounded route distance

	private static final class Goal {
		final int candIdx;
		final double extraM;

		Goal(int candIdx, double extraM) {
			this.candIdx = candIdx;
			this.extraM = extraM;
		}
	}

	private static final class QNode implements Comparable<QNode> {
		final int ref;
		final double dist;

		QNode(int ref, double dist) {
			this.ref = ref;
			this.dist = dist;
		}

		@Override
		public int compareTo(QNode o) {
			return Double.compare(dist, o.dist);
		}
	}

	/**
	 * Road-network distance from one previous state to every current candidate, in one bounded
	 * Dijkstra rather than one per pair.
	 *
	 * @return metres per candidate, {@code NaN} where no path exists within {@code dmax}.
	 */
	private double[] routeDistances(LocalGraph graph, Candidate from, List<Candidate> to, double dmax) {
		double[] out = new double[to.size()];
		Map<Integer, List<Goal>> goals = new HashMap<>();
		int pending = 0;
		for (int j = 0; j < to.size(); j++) {
			Candidate c = to.get(j);
			if (c.road.getId() == from.road.getId()) {
				// Same road: the along-polyline distance is exact and needs no search. This is the
				// common case at 12-30 m steps, which is why the Dijkstra rarely runs at all.
				out[j] = alongRoad(from, c);
				continue;
			}
			out[j] = Double.NaN;
			int roadIdx = graph.indexOf(c.road);
			if (roadIdx < 0) {
				continue;
			}
			pending++;
			addGoal(goals, (roadIdx << 16) | c.segIdx, j,
					MapUtils.squareRootDist31(c.road.getPoint31XTile(c.segIdx),
							c.road.getPoint31YTile(c.segIdx), c.x31, c.y31));
			addGoal(goals, (roadIdx << 16) | (c.segIdx + 1), j,
					MapUtils.squareRootDist31(c.road.getPoint31XTile(c.segIdx + 1),
							c.road.getPoint31YTile(c.segIdx + 1), c.x31, c.y31));
		}
		if (pending == 0) {
			return out;
		}
		int fromIdx = graph.indexOf(from.road);
		if (fromIdx < 0) {
			return out;
		}

		PriorityQueue<QNode> queue = new PriorityQueue<>();
		Map<Integer, Double> settled = new HashMap<>();
		queue.add(new QNode((fromIdx << 16) | from.segIdx,
				MapUtils.squareRootDist31(from.road.getPoint31XTile(from.segIdx),
						from.road.getPoint31YTile(from.segIdx), from.x31, from.y31)));
		queue.add(new QNode((fromIdx << 16) | (from.segIdx + 1),
				MapUtils.squareRootDist31(from.road.getPoint31XTile(from.segIdx + 1),
						from.road.getPoint31YTile(from.segIdx + 1), from.x31, from.y31)));

		int expanded = 0;
		while (!queue.isEmpty() && expanded < CairoDriveMapMatching.MAX_ROUTE_DIST_NODES) {
			QNode node = queue.poll();
			Double known = settled.get(node.ref);
			if (known != null && known <= node.dist) {
				continue;
			}
			if (node.dist > dmax) {
				break;
			}
			settled.put(node.ref, node.dist);
			expanded++;

			List<Goal> here = goals.get(node.ref);
			if (here != null) {
				for (Goal g : here) {
					double total = node.dist + g.extraM;
					if (Double.isNaN(out[g.candIdx]) || total < out[g.candIdx]) {
						out[g.candIdx] = total;
					}
				}
			}

			int roadIdx = node.ref >>> 16;
			int pointIdx = node.ref & 0xffff;
			RouteDataObject road = graph.roads.get(roadIdx);
			int x = road.getPoint31XTile(pointIdx);
			int y = road.getPoint31YTile(pointIdx);
			// Along the road, both directions. Direction of travel is handled softly in the
			// emission term instead: a hard one-way veto here would let a single mis-ordered fix
			// destroy the correct hypothesis, and at 12 m steps mis-ordering is routine.
			if (pointIdx > 0) {
				relax(queue, settled, road, roadIdx, pointIdx - 1, x, y, node.dist, dmax);
			}
			if (pointIdx + 1 < road.getPointsLength()) {
				relax(queue, settled, road, roadIdx, pointIdx + 1, x, y, node.dist, dmax);
			}
			// Across roads sharing this exact 31-coordinate - the junctions.
			int[] shared = graph.refsByCoord.get(coordKey(x, y));
			if (shared != null) {
				for (int ref : shared) {
					if (ref == node.ref) {
						continue;
					}
					Double s = settled.get(ref);
					if (s == null || s > node.dist) {
						queue.add(new QNode(ref, node.dist));
					}
				}
			}
		}
		return out;
	}

	private void relax(PriorityQueue<QNode> queue, Map<Integer, Double> settled, RouteDataObject road,
	                   int roadIdx, int nextPoint, int x, int y, double dist, double dmax) {
		double step = MapUtils.squareRootDist31(x, y,
				road.getPoint31XTile(nextPoint), road.getPoint31YTile(nextPoint));
		double nd = dist + step;
		if (nd > dmax) {
			return;
		}
		int ref = (roadIdx << 16) | nextPoint;
		Double s = settled.get(ref);
		if (s == null || s > nd) {
			queue.add(new QNode(ref, nd));
		}
	}

	private static void addGoal(Map<Integer, List<Goal>> goals, int ref, int candIdx, double extra) {
		List<Goal> list = goals.get(ref);
		if (list == null) {
			list = new ArrayList<>(2);
			goals.put(ref, list);
		}
		list.add(new Goal(candIdx, extra));
	}

	/** Exact along-polyline distance between two projections onto the same road. */
	private static double alongRoad(Candidate a, Candidate b) {
		Candidate lo = a;
		Candidate hi = b;
		if (lo.segIdx > hi.segIdx) {
			lo = b;
			hi = a;
		}
		if (lo.segIdx == hi.segIdx) {
			return MapUtils.squareRootDist31(lo.x31, lo.y31, hi.x31, hi.y31);
		}
		RouteDataObject r = lo.road;
		double d = MapUtils.squareRootDist31(lo.x31, lo.y31,
				r.getPoint31XTile(lo.segIdx + 1), r.getPoint31YTile(lo.segIdx + 1));
		for (int i = lo.segIdx + 1; i < hi.segIdx; i++) {
			d += MapUtils.squareRootDist31(r.getPoint31XTile(i), r.getPoint31YTile(i),
					r.getPoint31XTile(i + 1), r.getPoint31YTile(i + 1));
		}
		d += MapUtils.squareRootDist31(r.getPoint31XTile(hi.segIdx), r.getPoint31YTile(hi.segIdx),
				hi.x31, hi.y31);
		return d;
	}
}
