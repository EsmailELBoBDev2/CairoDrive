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
	/**
	 * Set only by {@code RouteRecalculationTask.stopCalculation()} - i.e. only when a NEWER request
	 * has replaced this one and its result is genuinely stale.
	 *
	 * <p>It exists because {@code calculationProgress.isCancelled} had come to mean two different
	 * things and the task could not tell them apart. That flag is also how the online/offline race
	 * stops the offline leg it no longer needs, and the task's own post-calculation check read it
	 * as "superseded" and threw the WINNING online route away. The 2026-08-08 drive is what that
	 * costs when the race is not allowed to cancel: 45 abandoned native searches alive at once,
	 * frames at 711-1370 ms, and two SIGSEGVs on the routing thread.
	 *
	 * <p>So cancellation and supersession are now separate signals. {@code isCancelled} means
	 * "stop working", which the native search polls; this means "your answer is no longer wanted".
	 * <b>Anything that wants a result discarded must set THIS</b> - setting {@code isCancelled}
	 * alone will stop the search and keep whatever the race already won.
	 *
	 * <p>volatile: written on the UI thread by stopCalculation, read on the calculation thread.
	 */
	public volatile boolean cairoDriveSuperseded;
	/**
	 * Build this environment WITHOUT the native library, so tile data lands on the Java side.
	 *
	 * <p>Only the HMM map matcher sets it, and only because it reads roads through
	 * {@code RoutingContext.loadTileData}. With the native library attached,
	 * {@code loadSubregionTile} takes the {@code nativeLib.loadRouteRegion} branch and
	 * {@code setLoadedNative} leaves {@code routes} null whenever the native call hands back a
	 * handle rather than materialised objects - at which point {@code loadAllObjects} adds
	 * nothing and the caller sees an empty road set. The 2026-08-08 drive shows exactly that:
	 * {@code CD_MATCH ... raw=0 accepted=0} on 700+ fixes and {@code matched=0} for two entire
	 * drives, in the middle of Cairo.
	 *
	 * <p>Nothing else should set this. The router itself WANTS the native path - that is the
	 * hh-cpp engine and the reason a reroute is seconds rather than tens of seconds.
	 */
	public boolean cairoDriveJavaTilesOnly;
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
