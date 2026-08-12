package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.binary.RouteDataObject;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.RouteDirectionInfo;
import net.osmand.router.RouteSegmentResult;

import java.util.List;

/**
 * Writes the ROUTE ITSELF to the drive log, once per installed route.
 *
 * <h3>Why this exists</h3>
 *
 * The owner's report of 2026-08-12: "several times it calculated routes on roads I never drove
 * on, and it gave the instruction wrong - I had to take the bridge and it said keep to the side,
 * then after I had already gone the wrong way it said take the bridge." His own words about what
 * he needed: "I hope this is in the logs, the route that existed and how I drove."
 *
 * <p>It was not. The log recorded {@code CD_NAV} - his position, his deviation, his speed, once a
 * second - and {@code CD_WRONGROAD route roads=1 segs=3}, a COUNT. Neither says which roads the
 * route used or what it was about to tell him to do, so "it routed me down a street I never
 * entered" and "the instruction was wrong" were both unanswerable from a log. That is the gap
 * this closes: with the plan written down, his second-by-second positions in {@code CD_NAV} can
 * be laid against the route he was actually given.
 *
 * <h3>What it deliberately does NOT do</h3>
 *
 * It does not log the full geometry. A Cairo route is thousands of points, the log rotates at
 * 8 MB, and a position trace already exists in {@code CD_NAV} once a second. What is missing is
 * the DECISIONS - the turn list - and the road identity behind them, which is a few dozen lines.
 *
 * <h3>Reading it</h3>
 *
 * <pre>
 * CD_ROUTE_PLAN: source=offline dist=8420m time=1180s dirs=23 segs=412 roads=87
 * CD_ROUTE_PLAN: #0 at 0m turn=C street="شارع سيد درويش" roadId=3105447241
 * CD_ROUTE_PLAN: #1 at 340m turn=KR street="" roadId=0
 * </pre>
 *
 * <p>{@code source=online} is the important one and it is not a detail: an online result has no
 * {@link RouteSegmentResult}, so every {@code roadId} reads 0 and every {@code street} is
 * whatever the server chose to send. A turn list full of {@code KR}/{@code KL} - keep right,
 * keep left - with no street names and no road ids is the signature of exactly the complaint
 * above, because "keep right" is what a generic router emits where OSM data would have said
 * "take the ramp". 42 of 216 turns on the 2026-08-11 drives were keep-type and 34 carried no
 * turn type at all.
 */
public final class CairoDriveRoutePlan {

	/** NO "CD_" prefix: {@link net.osmand.plus.helpers.CairoDriveLog#log} adds it. */
	private static final String TRACE_TAG = "ROUTE_PLAN";

	/**
	 * Directions written in full before truncating.
	 *
	 * <p>A Cairo cross-town route runs to a few dozen; this is above that and still bounded, so a
	 * pathological route cannot fill an 8 MB log on its own. Truncation is always ANNOUNCED - a
	 * list that silently stopped would read as a shorter route than the one being driven, which
	 * is the same class of mistake as a count standing in for the thing counted.
	 */
	private static final int MAX_DIRECTIONS = 80;

	private CairoDriveRoutePlan() {
	}

	public static void onRouteInstalled(@Nullable RouteCalculationResult route) {
		if (!CairoDriveLogger.isEnabled() || route == null || !route.isCalculated()) {
			return;
		}
		try {
			write(route);
		} catch (Throwable t) {
			// Diagnostics must never cost a route. This runs on the navigation path, from
			// RoutingHelper.setRoute, with the new route already assigned.
			CairoDriveLogger.getInstance().log("CD_" + TRACE_TAG, "failed", t);
		}
	}

	private static void write(@NonNull RouteCalculationResult route) {
		// getImmutableAllDirections, not getRouteDirections(app): the latter returns the
		// directions from the driver's CURRENT position onwards and needs an application to
		// aggregate them. This wants the whole plan as calculated, which is what a reader
		// comparing it against a drive needs - the part already passed is the part that
		// explains a wrong turn.
		List<RouteDirectionInfo> dirs = route.getImmutableAllDirections();
		List<RouteSegmentResult> segs = route.getOriginalRoute();
		List<Location> locs = route.getImmutableAllLocations();

		int segCount = segs == null ? 0 : segs.size();
		// Distinct OSM ways, not segments: a route runs over one way in several pieces and the
		// segment count reads as far more roads than the driver passes.
		int roadCount = 0;
		if (segs != null) {
			long previous = Long.MIN_VALUE;
			for (RouteSegmentResult s : segs) {
				RouteDataObject o = s == null ? null : s.getObject();
				if (o == null) {
					continue;
				}
				if (o.getId() != previous) {
					roadCount++;
					previous = o.getId();
				}
			}
		}

		// The one field that changes how everything below is read. An online route carries no
		// RouteSegmentResult at all, so its road ids are absent rather than wrong - and a reader
		// who does not know that will conclude the OSM data is broken.
		String source = segCount > 0 ? "offline" : "online";

		CairoDriveLogger logger = CairoDriveLogger.getInstance();
		logger.log("CD_" + TRACE_TAG, "source=" + source
				+ " dist=" + Math.round(route.getWholeDistance()) + "m"
				+ " time=" + route.getRoutingTime() + "s"
				+ " dirs=" + (dirs == null ? 0 : dirs.size())
				+ " segs=" + segCount
				+ " roads=" + roadCount
				+ " points=" + (locs == null ? 0 : locs.size()));

		if (dirs == null || dirs.isEmpty()) {
			logger.log("CD_" + TRACE_TAG, "no directions - nothing will be announced for this"
					+ " route, which is itself the answer if the driver reports silence");
			return;
		}

		int shown = Math.min(dirs.size(), MAX_DIRECTIONS);
		for (int i = 0; i < shown; i++) {
			RouteDirectionInfo d = dirs.get(i);
			if (d == null) {
				continue;
			}
			// The road id behind the turn, which is what makes "it sent me down a street I never
			// entered" checkable against CD_MATCH's road= on the same second.
			long roadId = 0;
			if (segs != null && d.routePointOffset >= 0 && d.routePointOffset < segs.size()) {
				RouteSegmentResult s = segs.get(d.routePointOffset);
				RouteDataObject o = s == null ? null : s.getObject();
				if (o != null) {
					roadId = o.getId();
				}
			}
			String street = d.getStreetName();
			String ref = d.getRef();
			String dest = d.getDestinationName();
			logger.log("CD_" + TRACE_TAG, "#" + i
					+ " at " + d.distance + "m"
					+ " turn=" + (d.getTurnType() == null ? "none" : d.getTurnType().toString())
					+ " street=\"" + (street == null ? "" : street) + "\""
					+ " ref=\"" + (ref == null ? "" : ref) + "\""
					+ " dest=\"" + (dest == null ? "" : dest) + "\""
					+ " roadId=" + roadId);
		}
		if (dirs.size() > shown) {
			logger.log("CD_" + TRACE_TAG, "... " + (dirs.size() - shown) + " more directions not"
					+ " logged (cap " + MAX_DIRECTIONS + "). The route is longer than this list.");
		}
	}
}
