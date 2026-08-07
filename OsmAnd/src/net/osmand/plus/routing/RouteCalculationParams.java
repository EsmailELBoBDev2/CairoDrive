package net.osmand.plus.routing;

import net.osmand.Location;
import net.osmand.data.LatLon;
import net.osmand.gpx.GPXFile;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.router.RouteCalculationProgress;
import net.osmand.shared.gpx.GpxFile;

import java.util.List;

public class RouteCalculationParams {

	public Location start;
	public LatLon end;
	public List<LatLon> intermediates;
	public Location currentLocation;

	public OsmandApplication ctx;
	public ApplicationMode mode;
	public GPXRouteParams gpxRoute;
	public RouteCalculationResult previousToRecalculate;

	public boolean onlyStartPointChanged;

	/**
	 * Wall clock at which this calculation was dispatched, or 0. Fork-specific and diagnostic only -
	 * nothing reads it except the CD_REROUTE log line, which uses it to price the span from
	 * "deviation acted on" to "route produced". CD_ROUTE_TIMING cannot: it starts inside
	 * RouteProvider, after this task has already been queued.
	 */
	public long cairoDriveDispatchedAt;
	/**
	 * Whether a CairoDriveEarlyReroute calculation was in flight when this task was dispatched.
	 * <p>
	 * mayInstall reads and CLEARS a global static latch, so without this every task consumed it -
	 * including ones with nothing to do with the deviation. A road-closure or settings
	 * recalculation finishing while an early start was pending would find confirmed==false and
	 * have its own perfectly good route DISCARDED, leaving the driver on the closed road.
	 */
	public boolean cairoDriveEarlyInFlight;
	public boolean fast;
	public boolean leftSide;
	public boolean startTransportStop;
	public boolean targetTransportStop;
	public boolean inPublicTransportMode;
	public boolean extraIntermediates;
	public boolean initialCalculation;
	public GpxFile gpxFile;

	public RouteCalculationProgress calculationProgress;
	public RouteCalculationProgressListener calculationProgressListener;
	public RouteCalculationResultListener alternateResultListener;

	public boolean recheckRouteNearestPoint() {
		return previousToRecalculate != null && onlyStartPointChanged && start != null && gpxRoute != null;
	}

	public interface RouteCalculationResultListener {
		void onRouteCalculated(RouteCalculationResult route);
	}
}
