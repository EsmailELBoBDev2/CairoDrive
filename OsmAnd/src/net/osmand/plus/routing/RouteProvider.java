package net.osmand.plus.routing;


import android.os.Bundle;
import android.util.Base64;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.IndexConstants;
import net.osmand.Location;
import net.osmand.LocationsHolder;
import net.osmand.PlatformUtil;
import net.osmand.ResultMatcher;
import net.osmand.plus.settings.enums.RouteCalculationMethod;
import net.osmand.plus.shared.SharedUtil;
import net.osmand.binary.BinaryMapIndexReader;
import net.osmand.binary.RouteDataObject;
import net.osmand.data.LatLon;
import net.osmand.gpx.GPXFile;
import net.osmand.map.OsmandRegions;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.avoidroads.AvoidRoadsHelper;
import net.osmand.plus.avoidroads.DirectionPointsHelper;
import net.osmand.plus.helpers.TargetPointsHelper;
import net.osmand.plus.helpers.TargetPoint;
import net.osmand.plus.measurementtool.GpxApproximationHelper;
import net.osmand.plus.measurementtool.GpxApproximationParams;
import net.osmand.plus.onlinerouting.OnlineRoutingHelper;
import net.osmand.plus.onlinerouting.engine.OnlineRoutingEngine;
import net.osmand.plus.onlinerouting.engine.OnlineRoutingEngine.OnlineRoutingResponse;
import net.osmand.plus.render.NativeOsmandLibrary;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.plus.cairodrive.CairoDriveRouteRace;
import net.osmand.plus.resources.ResourceManager;
import net.osmand.plus.routing.GPXRouteParams.GPXRouteParamsBuilder;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.settings.backend.preferences.CommonPreference;
import net.osmand.plus.settings.enums.ApproximationType;
import net.osmand.router.*;
import net.osmand.router.GeneralRouter.RoutingParameter;
import net.osmand.router.GeneralRouter.RoutingParameterType;
import net.osmand.router.RoutePlannerFrontEnd.GpxPoint;
import net.osmand.router.RoutePlannerFrontEnd.RouteCalculationMode;
import net.osmand.router.RoutingConfiguration.Builder;
import net.osmand.router.RoutingConfiguration.RoutingMemoryLimits;
import net.osmand.router.RoutingContext;
import net.osmand.router.TurnType;
import net.osmand.shared.gpx.GpxFile;
import net.osmand.gpx.GPXUtilities.Route;
import net.osmand.gpx.GPXUtilities.TrkSegment;
import net.osmand.gpx.GPXUtilities.WptPt;
import net.osmand.util.Algorithms;
import net.osmand.util.CollectionUtils;
import net.osmand.util.MapUtils;

import org.json.JSONException;
import org.xml.sax.SAXException;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.GZIPInputStream;

import javax.xml.parsers.FactoryConfigurationError;
import javax.xml.parsers.ParserConfigurationException;

import btools.routingapp.IBRouterService;


public class RouteProvider {

	private static final org.apache.commons.logging.Log log = PlatformUtil.getLog(RouteProvider.class);
	private static final int MIN_STRAIGHT_DIST = 50000;

	private final GpxRouteHelper gpxRouteHelper = new GpxRouteHelper(this);

	// ------------------------------------------------------------------------------------------------
	// CairoDrive: warm routing environment
	//
	// RoutingHelper builds exactly one RouteProvider and keeps it for the life of the process, so this is
	// app scoped state. Every calculation used to build a brand new RoutePlannerFrontEnd, RoutingConfiguration
	// and RoutingContext, which throws away the Highway Hierarchies network index (loadNetworkPoints,
	// groupByClusters and a QuadTree fill, see HHRoutePlanner.initHCtx) on every single reroute. Keeping one
	// set alive lets HHRoutePlanner take its `if (hctx.initialized) return hctx;` early exit.
	//
	// The whole risk here is staleness: routing on a network index or a router configuration that no longer
	// matches what the user has set or what is on disk is far worse than routing slowly. The rule below is
	// therefore "rebuild unless we can cheaply prove nothing relevant changed" - see buildWarmSignature.
	// ------------------------------------------------------------------------------------------------

	/**
	 * Master switch for the warm routing environment. Left as a mutable static so the behaviour can be
	 * disabled from a debugger or a test without a rebuild; there is no user facing setting for it.
	 */
	// Off until a CD_ROUTE_TIMING log from a real drive says it is both safe and worth having.
	// It reuses a RoutingContext across calculations, which is the highest-consequence change in
	// this fork - a stale one does not crash, it returns a wrong route - and the measured benefit
	// on a device running the C++ HH engine is the setup phase only. Unverified and load-bearing
	// is the wrong combination to ship on by default.
	public static boolean USE_WARM_ROUTING_ENVIRONMENT = false;

	/** Grep handle for the per-calculation timing line. */
	private static final String TIMING_TAG = "CD_ROUTE_TIMING";

	/**
	 * One cached, reusable routing environment. Immutable apart from the reuse counter - everything that
	 * varies per calculation is reset on the contexts and re-applied on the configuration before use.
	 */
	static class WarmRoutingEnvironment {
		final String signature;
		final RoutePlannerFrontEnd router;
		final RoutingConfiguration config;
		final RoutingContext ctx;
		/** May be null when this profile never uses the two phase COMPLEX context. */
		final RoutingContext complexCtx;
		/**
		 * penaltyForReverseDirection as built from the routing profile. HHRoutePlanner.runHHRoute halves it
		 * for intermediate targets and restores it afterwards, but not through a finally block - so snapshot
		 * the built value and restore it on every reuse rather than trusting that it was put back.
		 */
		final double penaltyForReverseDirection;
		/** Value of {@link RouteProvider#mapGeneration} when the reader array behind this entry was read. */
		final long mapGeneration;
		int reuseCount;

		WarmRoutingEnvironment(String signature, RoutePlannerFrontEnd router, RoutingConfiguration config,
		                       RoutingContext ctx, RoutingContext complexCtx, double penaltyForReverseDirection,
		                       long mapGeneration) {
			this.signature = signature;
			this.router = router;
			this.config = config;
			this.ctx = ctx;
			this.complexCtx = complexCtx;
			this.penaltyForReverseDirection = penaltyForReverseDirection;
			this.mapGeneration = mapGeneration;
		}
	}

	/** Result of asking for the cache: what we got, and whether we are allowed to fill it when we are done. */
	private static class WarmCheckout {
		static final WarmCheckout NONE = new WarmCheckout(null, false);

		final WarmRoutingEnvironment environment;
		final boolean mayCache;

		WarmCheckout(WarmRoutingEnvironment environment, boolean mayCache) {
			this.environment = environment;
			this.mayCache = mayCache;
		}
	}

	private final Object warmLock = new Object();
	private WarmRoutingEnvironment warmEnvironment;
	/**
	 * The thread that currently owns the cache slot. The cache is single tenant on purpose: a RoutingContext
	 * is not remotely thread safe, and while reroutes are serialised on RouteRecalculationHelper's single
	 * thread executor, nothing structurally prevents a second calculation from starting elsewhere. A second
	 * caller simply builds its own throwaway environment, exactly like before this cache existed.
	 */
	private Thread warmSessionOwner;
	/**
	 * Bumped from the ResourceManager callbacks whenever the map files change underneath us.
	 * <p>
	 * A counter rather than a flag, and read <em>before</em> the reader array is taken, so that a map change
	 * landing in the middle of a calculation cannot be mistaken for one that landed before it. An entry is
	 * only reused, and only stored, while the generation it was built at is still current.
	 * <p>
	 * This is a second line of defence: buildWarmSignature already fingerprints the reader array, so a map
	 * that was downloaded, deleted or replaced by an OsmAnd Live update is caught even without the listener.
	 * The listener additionally covers a reader object surviving while its content is re-indexed.
	 */
	private volatile long mapGeneration;
	private boolean resourceListenerRegistered;

	/**
	 * The application, captured the first time one is handed to this class.
	 * <p>
	 * RouteProvider had no app reference at all, which is why the cache invalidation below originally
	 * reached for {@code PlatformUtil.getOsmandRegions()}. That was wrong twice over: it does not compile
	 * without an import, and had it compiled it would have invalidated the wrong object. PlatformUtil owns
	 * a separate java-side singleton; the instance routing actually queries is OsmandApplication's, built
	 * in its constructor. Clearing PlatformUtil's would have been a silent no-op - the worst kind, because
	 * the log line would still have said the cache was dropped.
	 * <p>
	 * Volatile and nullable rather than a constructor parameter: this class is created by AppInitializer
	 * before ResourceManager exists, and the invalidation callbacks can fire on any thread.
	 */
	private volatile OsmandApplication resolvedApp;

	/**
	 * Registers the map-change listener once, lazily. It cannot be done in the constructor: AppInitializer
	 * creates RoutingHelper (and therefore this) before it creates ResourceManager.
	 */
	private void ensureResourceListenerRegistered(@NonNull OsmandApplication app) {
		resolvedApp = app;
		synchronized (warmLock) {
			if (resourceListenerRegistered) {
				return;
			}
			resourceListenerRegistered = true;
		}
		app.getResourceManager().addResourceListener(new ResourceManager.ResourceListener() {
			@Override
			public void onMapsIndexed() {
				invalidateWarmEnvironment("maps indexed");
			}

			@Override
			public void onReaderIndexed(BinaryMapIndexReader reader) {
				invalidateWarmEnvironment("reader indexed");
			}

			@Override
			public void onReaderClosed(BinaryMapIndexReader reader) {
				invalidateWarmEnvironment("reader closed");
			}

			@Override
			public void onMapClosed(String fileName) {
				invalidateWarmEnvironment("map closed");
			}
		});
	}

	/**
	 * Drops the cached environment. Safe to call from any thread at any time: it only raises a flag and clears
	 * the reference. A calculation that is mid-flight keeps using the objects it already holds - it was
	 * started against the map set that was current when it started, which is exactly the guarantee OsmAnd
	 * gives today - but the flag stops the environment from being handed to the next one.
	 */
	public void invalidateWarmEnvironment(@NonNull String reason) {
		// Item 5: the region point memo is keyed on geography, and geography does not move - but
		// the FILES it was answered from can. Every signal that drops the warm environment is
		// exactly a signal that the loaded region set changed, so the two are invalidated together.
		// Missing this would mean a route being told a map is absent that has since been installed.
		try {
			OsmandApplication app = resolvedApp;
			if (app != null) {
				OsmandRegions regions = app.getRegions();
				if (regions != null) {
					regions.invalidateRegionPointCache();
				}
				// Same signal, same reason: a cached reroute computed over a map that has since been
				// installed or removed is exactly the stale answer that cache must never serve.
				app.getRoutingHelper().invalidateRerouteCache();
			}
		} catch (Throwable ignored) {
			// Diagnostics and caches must never be able to break a map-change callback.
		}
		synchronized (warmLock) {
			mapGeneration++;
			if (warmEnvironment != null) {
				log.info(TIMING_TAG + " warm environment dropped: " + reason);
				warmEnvironment = null;
			}
		}
	}

	/**
	 * @param generation the value of {@link #mapGeneration} read before the caller took its snapshot of the
	 * routing map readers.
	 */
	@NonNull
	private WarmCheckout checkOutWarmEnvironment(@NonNull RouteCalculationParams params, @NonNull String signature,
	                                             long generation) {
		ensureResourceListenerRegistered(params.ctx);
		synchronized (warmLock) {
			if (warmSessionOwner != null) {
				// Another calculation holds the slot. Do not reuse and do not overwrite - build throwaway.
				return WarmCheckout.NONE;
			}
			warmSessionOwner = Thread.currentThread();
			WarmRoutingEnvironment cached = warmEnvironment;
			if (cached != null && cached.mapGeneration != mapGeneration) {
				log.info(TIMING_TAG + " warm environment dropped: map files changed");
				cached = null;
				warmEnvironment = null;
			}
			if (cached != null && !cached.signature.equals(signature)) {
				log.info(TIMING_TAG + " warm environment dropped: routing signature changed");
				cached = null;
				warmEnvironment = null;
			}
			if (cached != null) {
				cached.reuseCount++;
			}
			// A map change that landed after the caller read the reader array makes this calculation's
			// environment stale by construction: use it for this route, but never cache it.
			return new WarmCheckout(cached, generation == mapGeneration);
		}
	}

	/**
	 * Ends the cache session opened by {@link #checkOutWarmEnvironment}. Must be reached on every path out of
	 * a calculation, including exceptions, or the slot stays locked and the cache silently stops working.
	 *
	 * @param keep false whenever the calculation did not finish cleanly. A context that was abandoned part way
	 * through - cancelled, out of memory, or a RuntimeException swallowed by calcOfflineRouteImpl - is not
	 * worth the risk of reusing, and rebuilding costs one cold calculation.
	 */
	private void finishWarmSession(@Nullable RoutingEnvironment env, boolean keep) {
		WarmRoutingEnvironment entry = env != null ? env.getWarmEnvironment() : null;
		synchronized (warmLock) {
			if (warmSessionOwner != Thread.currentThread()) {
				return;
			}
			warmSessionOwner = null;
			if (entry != null && keep && entry.mapGeneration == mapGeneration) {
				warmEnvironment = entry;
			} else {
				warmEnvironment = null;
			}
		}
	}

	/**
	 * Fingerprint of everything the cached environment was built from. Any difference forces a rebuild.
	 * <p>
	 * Covered here:
	 * <ul>
	 *     <li><b>application / routing profile</b> - mode key, derived profile and resolved routing profile;</li>
	 *     <li><b>routing parameters and preferences</b> - the full parameter map that GeneralRouter is built
	 *         with, plus the calculation method, approximation type, safe mode and missing-map flags;</li>
	 *     <li><b>the routing configuration itself</b> - identity of the RoutingConfiguration.Builder and of the
	 *         template GeneralRouter, so reloading routing.xml or switching to a custom routing file rebuilds;</li>
	 *     <li><b>the set of loaded map files</b> - identity and order of the BinaryMapIndexReader array, which
	 *         changes whenever a map is downloaded, deleted, re-indexed or updated by OsmAnd Live;</li>
	 *     <li><b>avoided roads and direction points</b> - the impassable road ids baked into the router, and the
	 *         selected avoid-roads files with their size and mtime, since their contents are parsed into the
	 *         configuration's direction point tree;</li>
	 *     <li><b>the native library</b> - identity, so loading it after a cold start rebuilds.</li>
	 * </ul>
	 * Deliberately not covered, because they are re-applied on every reuse instead: memory limits, initial
	 * bearing, conditional-routing timestamp, minor-turns flag, left-hand driving and the transport-stop flags.
	 */
	@NonNull
	private String buildWarmSignature(@NonNull RouteCalculationParams params, @NonNull OsmandSettings settings,
	                                  @NonNull Builder configBuilder, @NonNull GeneralRouter generalRouter,
	                                  @NonNull Map<String, String> routingParams,
	                                  @NonNull BinaryMapIndexReader[] files, @Nullable NativeOsmandLibrary lib,
	                                  @NonNull RouteCalculationMethod method,
	                                  @NonNull ApproximationType approximationType) {
		StringBuilder sb = new StringBuilder(256);
		sb.append("mode=").append(params.mode.getStringKey())
				.append('|').append(params.mode.getDerivedProfile())
				.append('|').append(getRoutingProfileName(params.mode));
		sb.append(";builder=").append(System.identityHashCode(configBuilder));
		sb.append(";router=").append(System.identityHashCode(generalRouter));
		sb.append(";params=").append(routingParams);
		sb.append(";method=").append(method).append(',').append(approximationType);
		sb.append(";safe=").append(settings.SAFE_MODE.get());
		sb.append(";missing=").append(OsmandSettings.IGNORE_MISSING_MAPS).append(',').append(OsmandSettings.STOP_ON_MISSING_MAPS);
		sb.append(";lib=").append(System.identityHashCode(lib));
		// The impassable road ids are baked into the built GeneralRouter, so list them rather than hashing
		// them - the set is a handful of entries and a hash collision here would mean silently routing over a
		// road the user asked to avoid.
		List<Long> impassable = new ArrayList<>(configBuilder.getImpassableRoadLocations());
		Collections.sort(impassable);
		sb.append(";impassable=").append(impassable);
		sb.append(";avoidFiles=").append(avoidRoadsFilesSignature(params));
		sb.append(";maps=").append(files.length);
		for (BinaryMapIndexReader reader : files) {
			sb.append(',').append(System.identityHashCode(reader));
		}
		return sb.toString();
	}

	/**
	 * The avoid-roads (direction point) files selected for this mode, with size and last-modified time.
	 * <p>
	 * DirectionPointsHelper.getDirectionPoints re-parses these JSON files on every calculation and the result
	 * is baked into the RoutingConfiguration, so a reused configuration must be able to notice that a file was
	 * added, removed or edited. Almost always the selection is empty and this costs nothing.
	 */
	@NonNull
	private String avoidRoadsFilesSignature(@NonNull RouteCalculationParams params) {
		List<String> selected = params.ctx.getAvoidSpecificRoads().getPointsHelper().getSelectedFilesForMode(params.mode);
		if (Algorithms.isEmpty(selected)) {
			return "none";
		}
		StringBuilder sb = new StringBuilder();
		File dir = params.ctx.getAppPath(IndexConstants.ROUTING_PROFILES_DIR);
		List<String> sorted = new ArrayList<>(selected);
		Collections.sort(sorted);
		for (String name : sorted) {
			File f = new File(dir, name);
			sb.append(name).append(':').append(f.length()).append(':').append(f.lastModified()).append(';');
		}
		return sb.toString();
	}

	public static Location createLocation(@NonNull WptPt pt) {
		Location loc = new Location("OsmandRouteProvider");
		loc.setLatitude(pt.lat);
		loc.setLongitude(pt.lon);
		loc.setSpeed((float) pt.speed);
		if (!Double.isNaN(pt.ele)) {
			loc.setAltitude(pt.ele);
		}
		loc.setTime(pt.time);
		if (!Double.isNaN(pt.hdop)) {
			loc.setAccuracy((float) pt.hdop);
		}
		return loc;
	}

	public static Location createLocation(net.osmand.shared.gpx.primitives.WptPt pt){
		Location loc = new Location("OsmandRouteProvider");
		loc.setLatitude(pt.getLatitude());
		loc.setLongitude(pt.getLongitude());
		loc.setSpeed((float) pt.getSpeed());
		if(!Double.isNaN(pt.getEle())) {
			loc.setAltitude(pt.getEle());
		}
		loc.setTime(pt.getTime());
		if(!Double.isNaN(pt.getHdop())) {
			loc.setAccuracy((float) pt.getHdop());
		}
		return loc;
	}

	public static List<Location> locationsFromWpts(List<WptPt> wpts) {
		List<Location> locations = new ArrayList<>(wpts.size());
		for (WptPt pt : wpts) {
			locations.add(createLocation(pt));
		}
		return locations;
	}

	public static List<Location> locationsFromSharedWpts(List<net.osmand.shared.gpx.primitives.WptPt> wpts) {
		List<Location> locations = new ArrayList<>(wpts.size());
		for (net.osmand.shared.gpx.primitives.WptPt pt : wpts) {
			locations.add(createLocation(pt));
		}
		return locations;
	}

	public RouteCalculationResult calculateRouteImpl(@NonNull RouteCalculationParams params) {
		long time = System.currentTimeMillis();
		if (params.start != null && params.end != null) {
			params.calculationProgress.routeCalculationStartTime = time;
			if (log.isInfoEnabled()) {
				log.info("Start finding route from " + params.start + " to " + params.end + " using " +
						params.mode.getRouteService().getName()); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			try {
				RouteCalculationResult res;
				boolean calcGPXRoute = shouldCalculateGpxRoute(params);
				if (calcGPXRoute && !params.gpxRoute.calculateOsmAndRoute) {
					res = gpxRouteHelper.calculateGpxRoute(params);
				} else if (params.mode.getRouteService() == RouteService.OSMAND) {
					// CairoDrive: optionally race an online calculation alongside the local one
					// and take whichever answers first. Returns null when the race is switched
					// off or no online engine is configured, and then this is exactly the
					// upstream line it replaced.
					res = raceOnlineWithOffline(params, calcGPXRoute);
					if (res == null) {
						res = findVectorMapsRoute(params, calcGPXRoute);
					}
					if (params.calculationProgress.missingMapsCalculationResult != null) {
						res.setMissingMapsCalculationResult(params.calculationProgress.missingMapsCalculationResult);
					}
				} else if (params.mode.getRouteService() == RouteService.BROUTER) {
					res = findBROUTERRoute(params);
				} else if (params.mode.getRouteService() == RouteService.ONLINE) {
					boolean useFallbackRouting = false;
					try {
						res = findOnlineRoute(params);
					} catch (IOException | JSONException e) {
						res = new RouteCalculationResult(null);
						params.initialCalculation = false;
						useFallbackRouting = true;
					}
					if (useFallbackRouting || !res.isCalculated()) {
						OnlineRoutingHelper helper = params.ctx.getOnlineRoutingHelper();
						String engineKey = params.mode.getRoutingProfile();
						OnlineRoutingEngine engine = helper.getEngineByKey(engineKey);
						if (engine != null && engine.useRoutingFallback()) {
							res = findVectorMapsRoute(params, calcGPXRoute);
						}
					}
				} else if (params.mode.getRouteService() == RouteService.STRAIGHT ||
						params.mode.getRouteService() == RouteService.DIRECT_TO) {
					res = findStraightRoute(params);
				} else {
					res = new RouteCalculationResult("Selected route service is not available");
				}
				if (log.isInfoEnabled()) {
					log.info("Finding route contained " + res.getImmutableAllLocations().size() + " points for " + (System.currentTimeMillis() - time) + " ms"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				}
				return res;
			} catch (IOException | ParserConfigurationException | SAXException e) {
				log.error("Failed to find route ", e);
			}
		}
		return new RouteCalculationResult(null);
	}

	private boolean shouldCalculateGpxRoute(@NonNull RouteCalculationParams params) {
		if (params.gpxRoute != null) {
			GpxApproximationParams approximationParams = params.gpxRoute.approximationParams;
			if (approximationParams != null && !params.gpxRoute.gpxFile.isAttachedToRoads()) {
				GpxFile gpxFile = GpxApproximationHelper
						.approximateGpxSync(params.ctx, params.gpxRoute.gpxFile, approximationParams, null);
				if (gpxFile.getError() == null && gpxFile.isAttachedToRoads()) {
					params.gpxRoute = new GPXRouteParamsBuilder(gpxFile, params.gpxRoute).build(params.ctx, params.end);
				}
			}
			return params.gpxRoute != null && (!params.gpxRoute.points.isEmpty()
					|| (params.gpxRoute.reverse && !params.gpxRoute.routePoints.isEmpty()));
		}
		return false;
	}

	public RouteCalculationResult recalculatePartOfflineRoute(RouteCalculationResult res, RouteCalculationParams params) {
		RouteCalculationResult rcr = params.previousToRecalculate;
		List<Location> locs = new ArrayList<Location>(rcr.getRouteLocations());
		try {
			int[] startI = {0};
			int[] endI = {locs.size()};
			locs = findStartAndEndLocationsFromRoute(locs, params.start, params.end, startI, endI);
			List<RouteDirectionInfo> directions = calcDirections(params, startI[0], endI[0], rcr.getRouteDirections(params.ctx));
			gpxRouteHelper.insertInitialSegment(params, locs, directions, true);
			res = new RouteCalculationResult(locs, directions, params, null, true);
		} catch (RuntimeException e) {
			log.error(e.getMessage(), e);
		}
		return res;
	}


	protected List<RouteDirectionInfo> calcDirections(RouteCalculationParams params, int startIndex, int endIndex,
                                                      List<RouteDirectionInfo> inputDirections) {
		List<RouteDirectionInfo> directions = new ArrayList<RouteDirectionInfo>();
		if (inputDirections != null) {
			for (RouteDirectionInfo info : inputDirections) {
				if (info.routePointOffset >= startIndex && info.routePointOffset < endIndex) {
					RouteDirectionInfo ch = new RouteDirectionInfo(info.getAverageSpeed(), info.getTurnType());
					ch.routePointOffset = info.routePointOffset - startIndex;
					if(info.routeEndPointOffset != 0) {
						ch.routeEndPointOffset = info.routeEndPointOffset - startIndex;
					}
					ch.setDescriptionRoute(info.getDescriptionRoutePart(params.ctx));
					ch.setRouteDataObject(info.getRouteDataObject());
					// Issue #2894
					if (info.getRef() != null && !"null".equals(info.getRef())) {
						ch.setRef(info.getRef());
					}
					if (info.getStreetName() != null && !"null".equals(info.getStreetName())) {
						ch.setStreetName(info.getStreetName());
					}
					if (info.getDestinationName() != null && !"null".equals(info.getDestinationName())) {
						ch.setDestinationName(info.getDestinationName());
					}

					directions.add(ch);
				}
			}
		}
		return directions;
	}

	protected ArrayList<Location> findStartAndEndLocationsFromRoute(List<Location> route, Location startLoc, LatLon endLoc, int[] startI, int[] endI) {
		float minDist = Integer.MAX_VALUE;
		int start = 0;
		int end = route.size();
		if (startLoc != null) {
			for (int i = 0; i < route.size(); i++) {
				float d = route.get(i).distanceTo(startLoc);
				if (d < minDist) {
					start = i;
					minDist = d;
				}
			}
//		} else {
//			startLoc = route.get(0); // no more used
		}
		Location l = new Location("temp"); //$NON-NLS-1$
		l.setLatitude(endLoc.getLatitude());
		l.setLongitude(endLoc.getLongitude());
		minDist = Integer.MAX_VALUE;
		// get in reverse order taking into account ways with cycle
		for (int i = route.size() - 1; i >= start; i--) {
			float d = route.get(i).distanceTo(l);
			if (d < minDist) {
				end = i + 1;
				// slightly modify to allow last point to be added
				minDist = d - 40;
			}
		}
		ArrayList<Location> sublist = new ArrayList<Location>(route.subList(start, end));
		if(startI != null) {
			startI[0] = start;
		}
		if(endI != null) {
			endI[0] = end;
		}
		return sublist;
	}

	public RoutingEnvironment getRoutingEnvironment(OsmandApplication ctx, ApplicationMode mode, LatLon start, LatLon end) throws IOException {
		RouteCalculationParams params = new RouteCalculationParams();
		params.ctx = ctx;
		params.mode = mode;
		params.start = new Location("", start.getLatitude(), start.getLongitude());
		params.end = end;
		return calculateRoutingEnvironment(params, false, true);
	}

	public List<GpxPoint> generateGpxPoints(RoutingEnvironment env, GpxRouteApproximation gctx, LocationsHolder locationsHolder) {
		return env.getRouter().generateGpxPoints(gctx, locationsHolder);
	}

	public GpxRouteApproximation calculateGpxPointsApproximation(RoutingEnvironment env, GpxRouteApproximation gctx, List<GpxPoint> points, ResultMatcher<GpxRouteApproximation> resultMatcher, boolean useExternalTimestamps) throws IOException, InterruptedException {
		return env.getRouter().searchGpxRoute(gctx, points, resultMatcher, useExternalTimestamps);
	}

	protected RoutingEnvironment calculateRoutingEnvironment(RouteCalculationParams params, boolean calcGPXRoute, boolean skipComplex) throws IOException {
		return calculateRoutingEnvironment(params, calcGPXRoute, skipComplex, false);
	}

	/**
	 * @param allowWarmEnvironment when true this call may be served from - and may populate - the warm routing
	 * environment cache. Only the offline navigation path ({@link #findVectorMapsRoute}) passes true; every
	 * other caller (GPX approximation, route-preview helpers) keeps building throwaway objects, because those
	 * run on other threads and with other lifetimes and the cache is deliberately single-tenant.
	 */
	protected RoutingEnvironment calculateRoutingEnvironment(RouteCalculationParams params, boolean calcGPXRoute,
	                                                         boolean skipComplex, boolean allowWarmEnvironment) throws IOException {
		// Read before the reader array so that a map change racing with this calculation is always seen as
		// "after" it, never as "before".
		long mapGenerationAtStart = mapGeneration;
		BinaryMapIndexReader[] files = params.ctx.getResourceManager().getRoutingMapFiles();

		OsmandSettings settings = params.ctx.getSettings();

		RoutePlannerFrontEnd.CALCULATE_MISSING_MAPS = !OsmandSettings.IGNORE_MISSING_MAPS;
		RoutePlannerFrontEnd.CONTINUE_ON_MISSING_MAPS = !OsmandSettings.STOP_ON_MISSING_MAPS;

		RouteCalculationMethod method = settings.ROUTE_CALCULATION_METHOD.getModeValue(params.mode);
		ApproximationType approximationType = settings.APPROXIMATION_TYPE.getModeValue(params.mode);

		RoutingConfiguration.Builder config = params.ctx.getRoutingConfigForMode(params.mode);
		GeneralRouter generalRouter = params.ctx.getRouter(config, params.mode);
		if (generalRouter == null) {
			return null;
		}
		Map<String, String> routingParams = collectRoutingParameters(params, settings, generalRouter);

		// BUILD context
		NativeOsmandLibrary lib = settings.SAFE_MODE.get() ? null : NativeOsmandLibrary.getLoadedLibrary();

		// A GPX-guided route carries a PrecalculatedRouteDirection derived from the track, and a public
		// transport calculation drives the context differently again; neither is a plain navigation reroute,
		// so neither is allowed to read from or write to the warm cache.
		boolean warmAllowed = allowWarmEnvironment && USE_WARM_ROUTING_ENVIRONMENT
				&& !calcGPXRoute && !skipComplex && !params.inPublicTransportMode;
		String signature = warmAllowed
				? buildWarmSignature(params, settings, config, generalRouter, routingParams, files, lib, method, approximationType)
				: null;
		WarmCheckout checkout = signature != null
				? checkOutWarmEnvironment(params, signature, mapGenerationAtStart) : WarmCheckout.NONE;
		WarmRoutingEnvironment warm = checkout.environment;

		RoutePlannerFrontEnd router;
		RoutingConfiguration cf;
		if (warm != null) {
			router = warm.router;
			cf = warm.config;
			// The HH network cache and the built GeneralRouter come along untouched - the signature above is
			// what guarantees they still describe the current profile, parameters and map files. Only the
			// genuinely per-calculation fields are refreshed.
			cf.penaltyForReverseDirection = warm.penaltyForReverseDirection;
			config.applyMemoryLimits(cf, currentMemoryLimits(settings, false));
			applyPerCalculationSettings(cf, params, settings);
		} else {
			router = new RoutePlannerFrontEnd();
			if (method.isFastRoutingPossible(params.mode)) {
				// Ask the HH planner to hold on to its loaded network between calls. It only ever acts on
				// this when the same RoutingContext comes back, which is exactly what the warm cache provides
				// and what a throwaway front end never will - so passing warmAllowed here is a hint, not a
				// correctness decision.
				router.setDefaultHHRoutingConfig(warmAllowed);
			} else {
				router.setHHRoutingConfig(null);
			}
			cf = initOsmAndRoutingConfig(config, params, settings, generalRouter, routingParams);
			if (cf == null) {
				return null;
			}
		}
		router.setHHRouteCpp(!settings.SAFE_MODE.get());
		router.setUseOnlyHHRouting(method.isFastRoutingOnly(params.mode));
		router.setUseNativeApproximation(approximationType.isNativeApproximation());
		router.setUseGeometryBasedApproximation(approximationType.isGeoApproximation());

		PrecalculatedRouteDirection precalculated = null;
		if (calcGPXRoute) {
			ArrayList<Location> sublist = findStartAndEndLocationsFromRoute(params.gpxRoute.points,
					params.start, params.end, null, null);
			LatLon[] latLon = new LatLon[sublist.size()];
			for (int k = 0; k < latLon.length; k++) {
				latLon[k] = new LatLon(sublist.get(k).getLatitude(), sublist.get(k).getLongitude());
			}
			precalculated = PrecalculatedRouteDirection.build(latLon, generalRouter.getMaxSpeed());
			precalculated.setFollowNext(true);
			//cf.planRoadDirection = 1;
		}
		// check loaded files
		int leftX = MapUtils.get31TileNumberX(params.start.getLongitude());
		int rightX = leftX;
		int bottomY = MapUtils.get31TileNumberY(params.start.getLatitude());
		int topY = bottomY;
		if (params.intermediates != null) {
			for (LatLon l : params.intermediates) {
				leftX = Math.min(MapUtils.get31TileNumberX(l.getLongitude()), leftX);
				rightX = Math.max(MapUtils.get31TileNumberX(l.getLongitude()), rightX);
				bottomY = Math.max(MapUtils.get31TileNumberY(l.getLatitude()), bottomY);
				topY = Math.min(MapUtils.get31TileNumberY(l.getLatitude()), topY);
			}
		}
		LatLon l = params.end;
		leftX = Math.min(MapUtils.get31TileNumberX(l.getLongitude()), leftX);
		rightX = Math.max(MapUtils.get31TileNumberX(l.getLongitude()), rightX);
		bottomY = Math.max(MapUtils.get31TileNumberY(l.getLatitude()), bottomY);
		topY = Math.min(MapUtils.get31TileNumberY(l.getLatitude()), topY);

		params.ctx.getResourceManager().getRenderer().checkInitialized(15, lib, leftX, rightX, bottomY, topY);

		RoutingContext ctx;
		if (warm != null) {
			ctx = warm.ctx;
			ctx.resetForNewCalculation();
			if (warm.complexCtx != null) {
				warm.complexCtx.resetForNewCalculation();
			}
		} else {
			ctx = router.buildRoutingContext(cf, lib, files, RouteCalculationMode.NORMAL);
		}
		// A reroute happens mid-drive, so the private-access dialog this probe feeds can neither
		// be read nor answered. Skipping it drops a JNI round trip that resolves both endpoints
		// inside the C++ engine. A FIRST calculation still asks, so route planning is unchanged.
		ctx.skipPrivateAccessCheck = params.previousToRecalculate != null;
		ctx.leftSideNavigation = params.leftSide;
		ctx.calculationProgress = params.calculationProgress;
		ctx.publicTransport = params.inPublicTransportMode;
		ctx.startTransportStop = params.startTransportStop;
		ctx.targetTransportStop = params.targetTransportStop;
		if (params.previousToRecalculate != null && params.onlyStartPointChanged) {
			// UPSTREAM INDEX-SPACE BUG, fixed here.
			//
			// getCurrentRoute() is a LOCATION index. getOriginalRoute() with no argument is
			// getOriginalRoute(0) - the fully deduplicated SEGMENT list, whose size is the segment
			// count. Slicing one with the other applies a location index to a segment-indexed list,
			// so the "remaining route" handed to the router either starts far too late or, when the
			// guard `currentRoute < originalRoute.size()` fails, is never set at all.
			//
			// The overload that takes a location index and deduplicates forward from it already
			// exists and is exactly what was wanted.
			//
			// Inert today - the HH C++ branch discards previouslyCalculatedRoute anyway - which is
			// why it has never been noticed. It stops being inert the moment anything changes at
			// RoutePlannerFrontEnd:460, and a wrong tail there is a wrong ROUTE, not a slow one.
			int currentRoute = params.previousToRecalculate.getCurrentRoute();
			List<RouteSegmentResult> remaining = params.previousToRecalculate.getOriginalRoute(currentRoute);
			if (remaining != null && !remaining.isEmpty()) {
				ctx.previouslyCalculatedRoute = remaining;
			}
		}
		boolean complexPossible = !skipComplex && params.mode.isDerivedRoutingFrom(ApplicationMode.CAR)
				// Setting using RoutingType A_STAR_CLASSIC/A_STAR_2_PHASE is deprecated
				&& precalculated == null;
		boolean complex = complexPossible && router.getRecalculationEnd(ctx) == null;

		// Whether the COMPLEX context is needed flips during a drive: it is used unless getRecalculationEnd
		// found a reusable tail of the previous route, which in practice means the initial calculation and the
		// last ~20km (config.recalculateDistance) use COMPLEX while the middle of a long drive uses NORMAL.
		// A cache entry holding only one of the two would rebuild at every flip, so when we are going to cache
		// we build both up front and let each calculation pick. The HH network cache still follows whichever
		// context is actually searched, so a flip costs one cold calculation - not one per reroute.
		RoutingContext cachedComplexCtx;
		if (warm != null) {
			cachedComplexCtx = warm.complexCtx;
		} else if (complexPossible && (complex || checkout.mayCache && warmAllowed)) {
			cachedComplexCtx = router.buildRoutingContext(cf, lib, files, RouteCalculationMode.COMPLEX);
		} else {
			cachedComplexCtx = null;
		}
		RoutingContext complexCtx = complex ? cachedComplexCtx : null;
		if (complexCtx != null) {
			complexCtx.calculationProgress = params.calculationProgress;
			// Must be set here too. complexCtx is the context actually handed to searchRoute on
			// this path, so setting the flag only on `ctx` above would skip nothing at all.
			complexCtx.skipPrivateAccessCheck = ctx.skipPrivateAccessCheck;
			complexCtx.leftSideNavigation = params.leftSide;
			complexCtx.previouslyCalculatedRoute = ctx.previouslyCalculatedRoute;
		}

		RoutingEnvironment env = new RoutingEnvironment(router, ctx, complexCtx, precalculated);
		if (warm != null) {
			env.setWarmEnvironment(warm);
		} else if (warmAllowed && checkout.mayCache) {
			env.setWarmEnvironment(new WarmRoutingEnvironment(signature, router, cf, ctx, cachedComplexCtx,
					cf.penaltyForReverseDirection, mapGenerationAtStart));
		}
		return env;
	}

	protected RouteCalculationResult findVectorMapsRoute(RouteCalculationParams params, boolean calcGPXRoute) throws IOException {
		long startNanos = System.nanoTime();
		RoutingEnvironment env = null;
		RouteCalculationResult result = null;
		try {
			env = calculateRoutingEnvironment(params, calcGPXRoute, false, true);
			if (env == null) {
				return applicationModeNotSupported(params);
			}
			long setupNanos = System.nanoTime() - startNanos;
			LatLon st = new LatLon(params.start.getLatitude(), params.start.getLongitude());
			LatLon en = new LatLon(params.end.getLatitude(), params.end.getLongitude());
			List<LatLon> inters = new ArrayList<>();
			if (params.intermediates != null) {
				inters = new ArrayList<>(params.intermediates);
			}
			result = calcOfflineRouteImpl(params, env.getRouter(), env.getCtx(), env.getComplexCtx(), st, en,
					inters, env.getPrecalculated(), env, setupNanos);
			return result;
		} finally {
			// Only a clean, completed calculation leaves the environment in a state worth reusing. Anything
			// else - cancelled by a newer reroute, out of memory, a RuntimeException that calcOfflineRouteImpl
			// turned into an error result - drops the cache and costs one cold calculation next time.
			boolean keep = result != null && result.isCalculated()
					&& (params.calculationProgress == null || !params.calculationProgress.isCancelled);
			finishWarmSession(env, keep);
		}
	}

	/**
	 * Collects the routing parameter values that {@code GeneralRouter} is built with for this mode.
	 * <p>
	 * Split out of {@link #initOsmAndRoutingConfig} because the warm routing environment has to compare
	 * exactly these values to decide whether a cached configuration is still valid. Deriving the cache key
	 * from a second, independently written copy of this loop would be the classic way to end up routing on
	 * stale preferences, so both callers read the same map.
	 */
	@NonNull
	private Map<String, String> collectRoutingParameters(@NonNull RouteCalculationParams params,
	                                                     @NonNull OsmandSettings settings,
	                                                     @NonNull GeneralRouter generalRouter) {
		Map<String, String> paramsR = new LinkedHashMap<String, String>();
		for (Map.Entry<String, RoutingParameter> e : RoutingHelperUtils.getParametersForDerivedProfile(params.mode, generalRouter).entrySet()) {
			String key = e.getKey();
			RoutingParameter pr = e.getValue();
			String vl;
			if (key.equals(GeneralRouter.USE_SHORTEST_WAY)) {
				boolean bool = !settings.FAST_ROUTE_MODE.getModeValue(params.mode);
				vl = bool ? "true" : null;
			} else if (pr.getType() == RoutingParameterType.BOOLEAN) {
				CommonPreference<Boolean> pref = settings.getCustomRoutingBooleanProperty(key, pr.getDefaultBoolean());
				Boolean bool = pref.getModeValue(params.mode);
				vl = bool ? "true" : null;
			} else {
				vl = settings.getCustomRoutingProperty(key, pr.getDefaultString()).getModeValue(params.mode);
			}
			if (vl != null && vl.length() > 0) {
				paramsR.put(key, vl);
			}
		}
		Float defaultSpeed = params.mode.getDefaultSpeed();
		if (defaultSpeed > 0) {
			paramsR.put(GeneralRouter.DEFAULT_SPEED, String.valueOf(defaultSpeed));
		}
		Float minSpeed = params.mode.getMinSpeed();
		if (minSpeed > 0) {
			paramsR.put(GeneralRouter.MIN_SPEED, String.valueOf(minSpeed));
		}
		Float maxSpeed = params.mode.getMaxSpeed();
		if (maxSpeed > 0) {
			paramsR.put(GeneralRouter.MAX_SPEED, String.valueOf(maxSpeed));
		}
		return paramsR;
	}

	@NonNull
	private RoutingMemoryLimits currentMemoryLimits(@NonNull OsmandSettings settings, boolean verbose) {
		float mb = (1 << 20);
		Runtime rt = Runtime.getRuntime();
		// make visible
		int memoryLimitMb = (int) (0.95 * ((rt.maxMemory() - rt.totalMemory()) + rt.freeMemory()) / mb);
		int nativeMemoryLimitMb = settings.MEMORY_ALLOCATED_FOR_ROUTING.get();
		if (verbose) {
			log.warn("Use " + memoryLimitMb + " MB Free " + rt.freeMemory() / mb + " of " + rt.totalMemory() / mb + " max " + rt.maxMemory() / mb);
			log.warn("Use " + nativeMemoryLimitMb + " MB of native memory ");
		}
		return new RoutingMemoryLimits(memoryLimitMb, nativeMemoryLimitMb);
	}

	@NonNull
	private static String getRoutingProfileName(@NonNull ApplicationMode mode) {
		String derivedProfile = mode.getDerivedProfile();
		return "default".equals(derivedProfile) ? mode.getRoutingProfile() : derivedProfile;
	}

	private RoutingConfiguration initOsmAndRoutingConfig(Builder builder, RouteCalculationParams params, OsmandSettings settings,
	                                                     GeneralRouter generalRouter, Map<String, String> paramsR) {
		OsmandApplication app = settings.getContext();
		DirectionPointsHelper helper = app.getAvoidSpecificRoads().getPointsHelper();
		builder.setDirectionPoints(helper.getDirectionPoints(params.mode));

		RoutingMemoryLimits memoryLimits = currentMemoryLimits(settings, true);
		String routingProfile = getRoutingProfileName(params.mode);
		Double direction = params.start.hasBearing() ? params.start.getBearing() / 180d * Math.PI : null;

		RoutingConfiguration configuration = builder.build(routingProfile, direction, memoryLimits, paramsR);
		applyPerCalculationSettings(configuration, params, settings);

		return configuration;
	}

	/**
	 * The handful of configuration fields that legitimately differ between two consecutive calculations made
	 * with otherwise identical settings. They are re-applied both when the configuration is built and when a
	 * cached one is reused, so a warm configuration never carries the previous calculation's heading or
	 * conditional-routing timestamp into the next one.
	 */
	private void applyPerCalculationSettings(@NonNull RoutingConfiguration configuration,
	                                         @NonNull RouteCalculationParams params,
	                                         @NonNull OsmandSettings settings) {
		configuration.initialDirection = params.start.hasBearing() ? params.start.getBearing() / 180d * Math.PI : null;
		configuration.routeCalculationTime = settings.ENABLE_TIME_CONDITIONAL_ROUTING.getModeValue(params.mode)
				? System.currentTimeMillis() : 0;
		configuration.showMinorTurns = settings.SHOW_MINOR_TURNS.getModeValue(params.mode);
	}

	private RouteCalculationResult calcOfflineRouteImpl(RouteCalculationParams params,
	                                                    RoutePlannerFrontEnd router, RoutingContext ctx, RoutingContext complexCtx, LatLon st, LatLon en,
	                                                    List<LatLon> inters, PrecalculatedRouteDirection precalculated,
	                                                    RoutingEnvironment env, long setupNanos) throws IOException {
		// Sampled before the search: after it the HH config always holds a context, so asking afterwards
		// cannot tell a warm start from a cold one.
		boolean warmHHContext = router.isHHCalculationContextCached();
		int reuseCount = env != null && env.getWarmEnvironment() != null ? env.getWarmEnvironment().reuseCount : 0;
		long searchStartNanos = System.nanoTime();
		try {
			RouteResultPreparation.RouteCalcResult result = null;
			try {
				if (complexCtx != null) {
					try {
						result = router.searchRoute(complexCtx, st, en, inters, precalculated);
						// discard ctx and replace with calculated
						ctx = complexCtx;
					} catch (RuntimeException e) {
						params.ctx.runInUIThread(() -> {
							log.error("Runtime error: " + e.getMessage(), e);
							params.ctx.showToastMessage(R.string.complex_route_calculation_failed, e.getMessage());
						});
					}
				}
				if (result == null) {
					result = router.searchRoute(ctx, st, en, inters);
				}
			} finally {
				logRouteCalculationTiming(params, router, ctx, result, warmHHContext, reuseCount,
						setupNanos, System.nanoTime() - searchStartNanos);
			}

			if (result == null || result.getList().isEmpty()) {
				if(ctx.calculationProgress.segmentNotFound == 0) {
					return new RouteCalculationResult(params.ctx.getString(R.string.starting_point_too_far));
				} else if(ctx.calculationProgress.segmentNotFound == inters.size() + 1) {
					return new RouteCalculationResult(params.ctx.getString(R.string.ending_point_too_far));
				} else if(ctx.calculationProgress.segmentNotFound > 0) {
					return new RouteCalculationResult(params.ctx.getString(R.string.intermediate_point_too_far, "'" + ctx.calculationProgress.segmentNotFound + "'"));
				} else if (ctx.calculationProgress.directSegmentQueueSize == 0) {
					return new RouteCalculationResult("Route can not be found from start point (" + ctx.calculationProgress.distanceFromBegin / 1000f + " km)");
				} else if (ctx.calculationProgress.reverseSegmentQueueSize == 0) {
					return new RouteCalculationResult("Route can not be found from end point (" + ctx.calculationProgress.distanceFromEnd / 1000f + " km)");
				} else if (ctx.calculationProgress.isCancelled) {
					return interrupted();
				} else if(result != null && !Algorithms.isEmpty(result.getError())) {
					return new RouteCalculationResult(result.getError());
				}
				// something really strange better to see that message on the scren
				return emptyResult();
			} else {
				return new RouteCalculationResult(result.getList(), params, ctx,
						params.gpxRoute == null ? null : params.gpxRoute.wpt, true);
			}
		} catch (RuntimeException e) {
			log.error("Runtime error: " + e.getMessage(), e);
			return new RouteCalculationResult(e.getMessage() );
		} catch (InterruptedException e) {
			log.error("Interrupted: " + e.getMessage(), e);
			return interrupted();
		} catch (OutOfMemoryError e) {
//			ActivityManager activityManager = (ActivityManager)app.getSystemService(Context.ACTIVITY_SERVICE);
//			ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
//			activityManager.getMemoryInfo(memoryInfo);
//			int avl = (int) (memoryInfo.availMem / (1 << 20));
			int max = (int) (Runtime.getRuntime().maxMemory() / (1 << 20));
			int avl = (int) (Runtime.getRuntime().freeMemory() / (1 << 20));
			String s = " (" + avl + " MB available of " + max  + ") ";
			return new RouteCalculationResult("Not enough process memory "+ s);
		}
	}

	/**
	 * One greppable line per offline route calculation, written through the ordinary commons-logging Log.
	 * Written twice on purpose: once to logcat, and once directly into CairoDriveLogger's rotating
	 * files. The logger does pump this process' logcat into those files, but that route cannot be
	 * relied on - some vendor ROMs drop third-party tags out of the buffer before anything can read
	 * them, and a timing line nobody can retrieve after a drive is worth nothing. The direct write
	 * is a no-op when file logging is off.
	 * <p>
	 * The point of the split is to say <em>where</em> a slow reroute went:
	 * <ul>
	 *     <li>{@code setup} - building the routing environment: routing configuration, GeneralRouter clone,
	 *         avoid-roads parsing, RoutingContext construction. This is the part the warm cache removes, so
	 *         {@code warm=1} lines should show it collapse;</li>
	 *     <li>{@code search} - wall time inside RoutePlannerFrontEnd.searchRoute, i.e. the actual routing;</li>
	 *     <li>{@code find}/{@code load}/{@code headers}/{@code calc} - the engine's own breakdown from
	 *         RouteCalculationProgress (initial segment search, tile data, tile headers, total);</li>
	 *     <li>{@code hh} - the Highway Hierarchies phase timings, present only on the Java HH path.</li>
	 * </ul>
	 * If {@code setup} is small and {@code search} dominates on a warm line, the remaining latency is in the
	 * engine and not in this cache.
	 */
	/**
	 * Straight-line metres from the start of this calculation to its destination. Not the road
	 * distance - that is not known until the search finishes, and the point here is to have the
	 * INPUT size to plot the search time against.
	 */
	private static long straightLineDistance(@NonNull RouteCalculationParams params) {
		if (params.start == null || params.end == null) {
			return -1;
		}
		return Math.round(MapUtils.getDistance(params.start.getLatitude(), params.start.getLongitude(),
				params.end.getLatitude(), params.end.getLongitude()));
	}

	/**
	 * Whether upstream's route-repair path found a reusable tail, as {@code none} or the number of
	 * segments it would have reused. Expected to be {@code none} on every line of every log this
	 * device produces - which is exactly why it is worth printing once rather than assuming
	 * forever. If it is ever anything else, the static reading behind the plan above is wrong.
	 */
	private static String recalculationEndDescription(@NonNull RoutePlannerFrontEnd router,
	                                                  @NonNull RoutingContext ctx) {
		try {
			return router.getRecalculationEnd(ctx) != null ? "found" : "none";
		} catch (Throwable t) {
			// Diagnostics must never be able to fail a route calculation.
			return "err";
		}
	}

	/**
	 * Item 4. Splices a short repair route onto the untouched tail of the previous route.
	 *
	 * <p>Called with the raw segment list of a search that ran only as far as a rejoin point ON the
	 * previous route, plus that route and the location index of the rejoin. Produces a complete
	 * RouteCalculationResult to the REAL destination, or null - in which case the caller must run a
	 * full search, which is the unchanged behaviour.
	 *
	 * <h3>The three things that make this safe rather than merely plausible</h3>
	 *
	 * <b>1. The tail is deep-copied.</b> getOriginalRoute hands back the same RouteSegmentResult
	 * objects the LIVE route still holds. prepareResult below mutates them - setTurnType,
	 * distance, segment time. Without the copy, a repair that is later rejected would have
	 * rewritten the turn types of the route the driver is currently following, and it would only
	 * happen on the rejection path, the one least likely to be exercised.
	 *
	 * <b>2. prepareResult is re-run over the WHOLE spliced list.</b> This is the flagship risk and
	 * the answer to it. The tail's turn types were computed against a predecessor segment that the
	 * splice just replaced, so "turn right" can silently become wrong. prepareResult recomputes
	 * every turn from geometry, so after it the turns are correct BY CONSTRUCTION rather than by
	 * comparison. If ctx.requestNativePrepareResult were true it would short-circuit and this would
	 * be unsafe - so that is checked explicitly rather than assumed.
	 *
	 * <b>3. The joint is verified geometrically.</b> validateAllPointsConnected only prints and
	 * returns - it never throws and never fixes - so a disconnected splice would otherwise produce
	 * a route with a teleport in it and no error anywhere.
	 */
	/**
	 * Item 4, LIVE. Calculates a repair route to a rejoin point on the previous route and splices
	 * the untouched tail onto it, producing a complete route to the real destination.
	 *
	 * @return the spliced route, or null - in which case the caller runs a full search exactly as
	 * before. Every failure path returns null; none of them throws, and none of them mutates the
	 * route the driver is currently following.
	 */
	@Nullable
	public RouteCalculationResult calculateRepairRoute(@NonNull RouteCalculationParams params,
	                                                   @NonNull RouteCalculationResult previous,
	                                                   @NonNull LatLon rejoin,
	                                                   int rejoinLocationIndex) {
		RoutingEnvironment env = null;
		try {
			// A clone aimed at the rejoin point. previousToRecalculate is deliberately null: this
			// search must not itself try to reuse anything, or the reasoning becomes circular.
			RouteCalculationParams repairParams = new RouteCalculationParams();
			repairParams.start = params.start;
			repairParams.end = rejoin;
			repairParams.intermediates = null;
			repairParams.gpxRoute = null;
			repairParams.onlyStartPointChanged = false;
			repairParams.previousToRecalculate = null;
			repairParams.leftSide = params.leftSide;
			repairParams.fast = params.fast;
			repairParams.mode = params.mode;
			repairParams.ctx = params.ctx;
			repairParams.calculationProgress = new RouteCalculationProgress();

			env = calculateRoutingEnvironment(repairParams, false, true);
			if (env == null) {
				return null;
			}
			RoutingContext ctx = env.getComplexCtx() != null ? env.getComplexCtx() : env.getCtx();
			RouteResultPreparation.RouteCalcResult raw = env.getRouter().searchRoute(ctx,
					new LatLon(params.start.getLatitude(), params.start.getLongitude()),
					rejoin, null, env.getPrecalculated());
			if (raw == null || !raw.isCorrect() || raw.getList().isEmpty()) {
				return null;
			}
			// Spliced with the ORIGINAL params, so the result ends at the real destination.
			return spliceRepair(params, ctx, raw.getList(), previous, rejoinLocationIndex);
		} catch (Throwable t) {
			CairoDriveLogger.getInstance().log("CD_REROUTE",
					"repair search FAILED " + t.getClass().getSimpleName() + ": " + t.getMessage());
			return null;
		} finally {
			if (env != null) {
				try {
					finishWarmSession(env, false);
				} catch (Throwable ignored) {
				}
			}
		}
	}

	@Nullable
	private RouteCalculationResult spliceRepair(@NonNull RouteCalculationParams params,
	                                            @NonNull RoutingContext ctx,
	                                            @NonNull List<RouteSegmentResult> repairSegments,
	                                            @NonNull RouteCalculationResult previous,
	                                            int rejoinLocationIndex) {
		try {
			if (repairSegments.isEmpty() || ctx.requestNativePrepareResult) {
				return null;
			}
			List<RouteSegmentResult> tail = previous.getOriginalRoute(rejoinLocationIndex);
			if (tail == null || tail.isEmpty()) {
				return null;
			}
			List<RouteSegmentResult> spliced = new ArrayList<>(repairSegments.size() + tail.size());
			spliced.addAll(repairSegments);
			RouteSegmentResult last = repairSegments.get(repairSegments.size() - 1);
			for (RouteSegmentResult rr : tail) {
				// Deep copy - see (1) above.
				RouteSegmentResult copy = new RouteSegmentResult(rr.getObject(),
						rr.getStartPointIndex(), rr.getEndPointIndex());
				if (spliced.size() == repairSegments.size()
						&& sameSegment(last, copy)) {
					continue;   // dedupe the joint
				}
				spliced.add(copy);
			}
			// (3) the joint must be geometrically continuous.
			RouteSegmentResult a = repairSegments.get(repairSegments.size() - 1);
			RouteSegmentResult b = spliced.get(repairSegments.size());
			double gap = MapUtils.getDistance(
					a.getPoint(a.getEndPointIndex()).getLatitude(), a.getPoint(a.getEndPointIndex()).getLongitude(),
					b.getPoint(b.getStartPointIndex()).getLatitude(), b.getPoint(b.getStartPointIndex()).getLongitude());
			if (gap > 1.0) {
				CairoDriveLogger.getInstance().log("CD_REROUTE",
						"splice REJECTED jointGapM=" + Math.round(gap));
				return null;
			}
			// (2) recompute every turn from geometry over the whole spliced list.
			new RouteResultPreparation().prepareResult(ctx, spliced);
			// Constructed with the ORIGINAL params, so introduceLastPoint appends a leg to the REAL
			// destination rather than stopping the route at the rejoin point.
			return new RouteCalculationResult(spliced, params, ctx, null, true);
		} catch (Throwable t) {
			CairoDriveLogger.getInstance().log("CD_REROUTE",
					"splice FAILED " + t.getClass().getSimpleName() + ": " + t.getMessage());
			return null;
		}
	}

	private static boolean sameSegment(@NonNull RouteSegmentResult x, @NonNull RouteSegmentResult y) {
		return x.getObject() != null && y.getObject() != null
				&& x.getObject().getId() == y.getObject().getId()
				&& x.getStartPointIndex() == y.getStartPointIndex()
				&& x.getEndPointIndex() == y.getEndPointIndex();
	}

	/** Non-static so it reads the app's OsmandRegions - the one routing queries. See resolvedApp. */
	private String regionCacheStats() {
		try {
			OsmandApplication app = resolvedApp;
			if (app == null) {
				return "n/a";
			}
			OsmandRegions regions = app.getRegions();
			return regions != null ? regions.getRegionCacheStats() : "n/a";
		} catch (Throwable t) {
			return "err";
		}
	}

	private void logRouteCalculationTiming(@NonNull RouteCalculationParams params,
	                                       @NonNull RoutePlannerFrontEnd router,
	                                       @NonNull RoutingContext ctx,
	                                       @Nullable RouteResultPreparation.RouteCalcResult result,
	                                       boolean warmHHContext, int reuseCount,
	                                       long setupNanos, long searchNanos) {
		try {
			RouteCalculationProgress progress = params.calculationProgress;
			String engine;
			if (result instanceof HHRouteDataStructure.HHNetworkRouteRes) {
				engine = "hh-java";
			} else if (router.isHHRoutingConfigured()) {
				engine = ctx.nativeLib != null ? "hh-cpp" : "hh-java-failed";
			} else {
				engine = ctx.nativeLib != null ? "astar-cpp" : "astar-java";
			}
			StringBuilder sb = new StringBuilder(220);
			sb.append(TIMING_TAG)
					.append(" mode=").append(params.mode.getStringKey())
					.append(" engine=").append(engine)
					.append(" reroute=").append(params.previousToRecalculate != null ? 1 : 0)
					// Whether the private-access probe was skipped for this calculation. Without it, a
					// `pre=` that did not move would be ambiguous between "skipping it saved nothing"
					// and "it was never skipped" - and the flag is set on two different contexts, so
					// the second one silently not being set is a real way for it to be a no-op.
					.append(" skipPriv=").append(ctx.skipPrivateAccessCheck ? 1 : 0)
					// Which arm of the A/B this calculation was. Reported from what the config actually
					// asked for, not from the flag, so an uneven split shows up as data rather than
					// silently skewing the comparison.
					.append(" alt=").append(RoutePlannerFrontEnd.wasAlternativesUsed() ? 1 : 0)
					// Item 5. hits/total for the region point memo. If pre= drops and this shows hits,
					// the two agree; if pre= drops and this shows none, the saving came from somewhere
					// else and the attribution would have been wrong.
					.append(" regionCache=").append(regionCacheStats())
					.append(" warm=").append(warmHHContext ? 1 : 0)
					.append(" reuse=").append(reuseCount)
					.append(" setup=").append(ms(setupNanos))
					.append(" search=").append(ms(searchNanos))
					// THE FALSIFICATION PROBE. Free, and it decides a multi-day question.
					//
					// A code map plus upstream issue #19737 both say the same thing: every
					// deviation here runs a FULL search to the destination. OsmAnd's route-repair
					// mechanism (getRecalculationEnd) is bypassed by the HH C++ branch, which
					// passes a hardcoded null, and would not fire anyway below its 20 km
					// threshold. HERE ships the repair technique as returnToRoute() and documents
					// it as existing to avoid a costly recalculation; TomTom ships it as
					// continuous replanning with a 1 km cutoff that doubles on repeated deviation.
					// So the obvious move is to reroute to a point ~500 m ahead ON the old route
					// and splice the tail back on.
					//
					// That whole plan rests on ONE unmeasured assumption: that a short route is
					// proportionally cheaper on this device. It might not be - HH's cost is
					// dominated by loading and searching the network around each endpoint, and if
					// that fixed cost dominates, a 500 m route costs nearly what an 8 km one does
					// and the entire idea collapses.
					//
					// These two numbers test it for nothing. Cairo reroutes happen at naturally
					// varying distances-to-destination, so one ordinary drive plots `search`
					// against `straightM` by itself. A flat line kills the plan before a week is
					// spent on it; a sloped one justifies building it. Six hypotheses have already
					// been spent guessing at this router - this one gets measured first.
					.append(" straightM=").append(straightLineDistance(params))
					.append(" recalcEnd=").append(recalculationEndDescription(router, ctx))
					// OsmAnd's own verdict on whether the fast Highway-Hierarchy path actually
					// worked - which nothing here was recording. engine=hh-cpp only says which
					// planner was ASKED; this says what happened. SUCCESS means the precomputed
					// shortcuts in the .obf were used. Anything FAILED_* means the route was
					// produced the slow way, and the enum names the reason:
					//   FAILED_UNSUPPORTED_PARAMETERS - a routing parameter is set to a value the
					//     HH index was not built for. HHRoutePlanner.matchGroupRoutingParams counts
					//     a parameter as unsupported only when it DIFFERS FROM ITS DECLARED
					//     DEFAULT, so a profile left alone is fine and a customised one may not be.
					//   FAILED_NO_HH_ROUTING_DATA - the map carries no HH index at all.
					//   FAILED_WITH_MIXED_MAPS / MISSING_MAPS / NEED_MORE_LAND_MAPS - map coverage.
					// Without this, a degraded route and a fast one look identical in the log -
					// which is how five hypotheses came to be tested against the wrong number.
					.append(" fast=").append(fastRoutingStatus(progress));
			if (progress != null) {
				// The pre-search block: missing-maps check, private-access probe, region lookup for
				// all route points. On the HH C++ path `find` is structurally 0 - that path never
				// resolves start segments in Java - and `routingTime` is only ~15-20% of `search`,
				// so most of a 4-8 s reroute has had no bucket at all. This is the half of it that
				// can be timed without touching the native library.
				sb.append(" pre=").append(ms(progress.timeToPrepare));
				sb.append(" find=").append(ms(progress.timeToFindInitialSegments))
						.append(" load=").append(ms(progress.timeToLoad))
						.append(" headers=").append(ms(progress.timeToLoadHeaders))
						.append(" calc=").append(ms(progress.timeToCalculate))
						.append(" tiles=").append(progress.loadedTiles)
						.append('/').append(progress.distinctLoadedTiles)
						.append(" visited=").append(progress.visitedSegments)
						.append(" cancelled=").append(progress.isCancelled ? 1 : 0);
			}
			sb.append(" routingTime=").append(String.format(Locale.US, "%.0f", ctx.routingTime));
			sb.append(" ok=").append(result != null && result.isCorrect() ? 1 : 0);
			if (result instanceof HHRouteDataStructure.HHNetworkRouteRes) {
				HHRouteDataStructure.RoutingStats stats = ((HHRouteDataStructure.HHNetworkRouteRes) result).stats;
				if (stats != null) {
					sb.append(" hh[").append(stats.toLogString()).append(']');
				}
			}
			String line = sb.toString();
			log.info(line);
			// Also straight into the on-device files. Some vendor ROMs - MIUI among them - drop
			// third-party tags out of the logcat buffer entirely, so `adb logcat` on such a
			// phone shows the framework classes injected into this process and nothing this app
			// wrote. The whole point of this line is to be readable after a drive, so it must
			// not depend on logcat surviving.
			CairoDriveLogger.getInstance().log(TIMING_TAG, line.substring(TIMING_TAG.length() + 1));
		} catch (RuntimeException e) {
			// Diagnostics must never be able to break a navigation calculation.
			log.error(TIMING_TAG + " failed to log timing", e);
		}
		logNarrowStreetCoverage(result);
	}

	/** Grep handle for the narrow-street data coverage line. */
	private static final String NARROW_TAG = "CD_NARROW";

	/**
	 * Egyptian street-name words that mean "alley" outright, and the ones that mean "proper road".
	 *
	 * <p>Measured, not acted on - see {@link #logNarrowStreetCoverage}. An Overpass count of
	 * central Cairo showed the tags the routing rules depend on cover at most 2.5% of the network
	 * (width 14 ways out of 71922, surface 1.4%), while 74% of it is a bare highway=residential.
	 * The one field that IS populated on those ways is the name, and Egyptian naming encodes width
	 * by convention: an عطفة or a حارة is an alley by definition of what it is called, the same way
	 * service=alley is. Whether that is worth building on depends on how much of the network
	 * actually carries these words, which is what this counts.
	 */
	private static final String[] NARROW_NAME_WORDS = {"عطفة", "حارة", "زقاق", "درب", "ممر"};
	private static final String[] WIDE_NAME_WORDS = {"شارع", "طريق", "كوبري", "محور", "ميدان", "كورنيش"};

	private static boolean startsWithAny(@NonNull String name, @NonNull String[] words) {
		for (String word : words) {
			if (name.startsWith(word)) {
				return true;
			}
		}
		return false;
	}

	/**
	 * Reports how much of the road the router just chose actually carries the tags the
	 * "deprioritise narrow streets" option depends on.
	 *
	 * <p>Why this exists rather than a cleverer routing rule. That option can only act on tags
	 * that are positively present - a width, a surface, a service classification. Inferring
	 * narrowness from ABSENT data is not something that can be done accurately: in an under-mapped
	 * city the absence of a width tag is the normal state of a perfectly good four-lane road, so
	 * any rule of the form "untagged residential means narrow" penalises most of Cairo and makes
	 * routing worse. There is no honest proxy, so the question "does this option do anything on
	 * the roads I actually drive" has to be answered with a measurement instead of a guess.
	 *
	 * <p>This is that measurement, and it is taken on the real route rather than on a sample of
	 * the city, which makes it the right population: one drive produces a line saying exactly what
	 * fraction of the roads used were taggeable at all, and which rule tier could have fired.
	 * If coverage turns out to be near zero the answer is to improve OSM, or to drop the feature -
	 * not to invent a proxy for it.
	 *
	 * <p>Counted per distinct way, not per segment, so a long road split into forty pieces does
	 * not swamp the numbers.
	 */
	private void logNarrowStreetCoverage(@Nullable RouteResultPreparation.RouteCalcResult result) {
		if (!CairoDriveLogger.isEnabled() || result == null || !result.isCorrect()) {
			return;
		}
		try {
			List<RouteSegmentResult> segments = result.getList();
			if (segments == null || segments.isEmpty()) {
				return;
			}
			Set<Long> seen = new HashSet<>();
			int ways = 0, width = 0, maxwidth = 0, lanes = 0, surface = 0, smoothness = 0;
			int tracktype = 0, service = 0, actionable = 0;
			int named = 0, narrowName = 0, wideName = 0;
			for (RouteSegmentResult segment : segments) {
				RouteDataObject obj = segment == null ? null : segment.getObject();
				if (obj == null || !seen.add(obj.getId())) {
					continue;
				}
				ways++;
				String name = obj.getName();
				if (name != null && !name.isEmpty()) {
					named++;
					if (startsWithAny(name, NARROW_NAME_WORDS)) {
						narrowName++;
					} else if (startsWithAny(name, WIDE_NAME_WORDS)) {
						wideName++;
					}
				}
				boolean any = false;
				if (obj.getValue("width") != null) { width++; any = true; }
				if (obj.getValue("maxwidth") != null) { maxwidth++; any = true; }
				if (obj.getValue("lanes") != null) { lanes++; any = true; }
				if (obj.getValue("surface") != null) { surface++; any = true; }
				if (obj.getValue("smoothness") != null) { smoothness++; any = true; }
				if (obj.getValue("tracktype") != null) { tracktype++; any = true; }
				if (obj.getValue("service") != null) { service++; any = true; }
				if (any) {
					actionable++;
				}
				// The exact population this measurement was built to find: named as an alley,
				// tagged as nothing. Recorded in memory only - nothing is uploaded until the
				// driver asks, and the feature is off by default. See CairoDriveOsmFeedback.
				if (!any && name != null && startsWithAny(name, NARROW_NAME_WORDS)) {
					net.osmand.plus.cairodrive.CairoDriveOsmFeedback.observeNarrowCandidate(
							resolvedApp, segmentLocation(segment), name);
				}
			}
			if (ways == 0) {
				return;
			}
			CairoDriveLogger.getInstance().log(NARROW_TAG, "ways=" + ways
					+ " actionable=" + actionable + " (" + (actionable * 100 / ways) + "%)"
					+ " width=" + width + " maxwidth=" + maxwidth + " lanes=" + lanes
					+ " surface=" + surface + " smoothness=" + smoothness
					+ " tracktype=" + tracktype + " service=" + service
					+ " named=" + named + " nameAlley=" + narrowName + " nameStreet=" + wideName);
		} catch (RuntimeException e) {
			// Diagnostics must never be able to break a navigation calculation.
			log.error(NARROW_TAG + " failed to measure tag coverage", e);
		}
	}

	/**
	 * A point on the segment, for an OSM note.
	 *
	 * <p>The START point rather than a midpoint: a note wants to land on the way, and the start of
	 * a segment is a vertex that is definitely on it, where an interpolated midpoint of a curved
	 * segment can sit off the road entirely - which for a note reading "is this street narrow?" is
	 * the difference between a useful survey lead and a mapper wondering which street is meant.
	 */
	@Nullable
	private static LatLon segmentLocation(@Nullable RouteSegmentResult segment) {
		if (segment == null) {
			return null;
		}
		try {
			net.osmand.router.RouteSegmentResult s = segment;
			int index = s.getStartPointIndex();
			RouteDataObject obj = s.getObject();
			if (obj == null || index < 0 || index >= obj.getPointsLength()) {
				return null;
			}
			return new LatLon(
					MapUtils.get31LatitudeY(obj.getPoint31YTile(index)),
					MapUtils.get31LongitudeX(obj.getPoint31XTile(index)));
		} catch (RuntimeException e) {
			return null;
		}
	}

	/**
	 * OsmAnd's fast-routing verdict as a short token, or {@code n/a} when the progress object
	 * does not carry one. Defensive: this reads an enum that crosses the JNI boundary.
	 */
	@NonNull
	private static String fastRoutingStatus(@Nullable RouteCalculationProgress progress) {
		if (progress == null) {
			return "n/a";
		}
		try {
			FastRoutingState.Status status = progress.getFastRoutingStatus();
			return status == null ? "n/a" : status.name();
		} catch (RuntimeException e) {
			return "n/a";
		}
	}

	private static String ms(long nanos) {
		return String.format(Locale.US, "%.0f", nanos / 1.0e6);
	}

	private RouteCalculationResult applicationModeNotSupported(RouteCalculationParams params) {
		return new RouteCalculationResult("Application mode '"+ params.mode.toHumanString()+ "' is not supported.");
	}

	private RouteCalculationResult interrupted() {
		return new RouteCalculationResult("Route calculation was interrupted");
	}

	private RouteCalculationResult emptyResult() {
		return new RouteCalculationResult("Empty result");
	}

	@NonNull
	public static List<RouteSegmentResult> parseOsmAndGPXRoute(List<Location> points, GpxFile gpxFile,
	                                                           List<Location> segmentEndpoints,
	                                                           int selectedSegment) {
		return parseOsmAndGPXRoute(points, gpxFile, segmentEndpoints, selectedSegment, false);
	}

	@NonNull
	public static List<RouteSegmentResult> parseOsmAndGPXRoute(List<Location> points, GpxFile gpxFile,
	                                                           List<Location> segmentEndpoints,
	                                                           int selectedSegment, boolean leftSide) {
		GPXFile javaGpx = SharedUtil.jGpxFile(gpxFile);
		List<TrkSegment> segments = javaGpx.getNonEmptyTrkSegments(false);
		if (selectedSegment != -1 && segments.size() > selectedSegment) {
			TrkSegment segment = segments.get(selectedSegment);
			points.addAll(locationsFromWpts(segment.points));
			RouteImporter routeImporter = new RouteImporter(segment, javaGpx.getRoutePoints(selectedSegment));
			return routeImporter.importRoute();
		} else {
			collectPointsFromSegments(segments, points, segmentEndpoints);
			RouteImporter routeImporter = new RouteImporter(javaGpx, leftSide);
			return routeImporter.importRoute();
		}
	}

	protected static void collectSegmentPointsFromGpx(GpxFile gpxFile, List<Location> points,
													  List<Location> segmentEndpoints, int selectedSegment) {
		List<net.osmand.shared.gpx.primitives.TrkSegment> segments = gpxFile.getNonEmptyTrkSegments(false);
		if (selectedSegment != -1 && segments.size() > selectedSegment) {
			net.osmand.shared.gpx.primitives.TrkSegment segment = segments.get(selectedSegment);
			points.addAll(locationsFromSharedWpts(segment.getPoints()));
		} else {
			collectPointsFromSharedSegments(segments, points, segmentEndpoints);
		}
	}

	protected static void collectSegmentPointsFromGpx(GPXFile gpxFile, List<Location> points,
													  List<Location> segmentEndpoints, int selectedSegment) {
		List<TrkSegment> segments = gpxFile.getNonEmptyTrkSegments(false);
		if (selectedSegment != -1 && segments.size() > selectedSegment) {
			TrkSegment segment = segments.get(selectedSegment);
			points.addAll(locationsFromWpts(segment.points));
		} else {
			collectPointsFromSegments(segments, points, segmentEndpoints);
		}
	}

	protected static void collectPointsFromSegments(List<TrkSegment> segments, List<Location> points, List<Location> segmentEndpoints) {
		Location lastPoint = null;
		for (int i = 0; i < segments.size(); i++) {
			TrkSegment segment = segments.get(i);
			points.addAll(locationsFromWpts(segment.points));
			if (i <= segments.size() - 1 && lastPoint != null) {
				segmentEndpoints.add(lastPoint);
				segmentEndpoints.add(points.get((points.size() - segment.points.size())));
			}
			lastPoint = points.get(points.size() - 1);
		}
	}

	protected static void collectPointsFromSharedSegments(List<net.osmand.shared.gpx.primitives.TrkSegment> segments, List<Location> points, List<Location> segmentEndpoints) {
		Location lastPoint = null;
		for (int i = 0; i < segments.size(); i++) {
			net.osmand.shared.gpx.primitives.TrkSegment segment = segments.get(i);
			points.addAll(locationsFromSharedWpts(segment.getPoints()));
			if (i <= segments.size() - 1 && lastPoint != null) {
				segmentEndpoints.add(lastPoint);
				segmentEndpoints.add(points.get((points.size() - segment.getPoints().size())));
			}
			lastPoint = points.get(points.size() - 1);
		}
	}

	protected static List<RouteDirectionInfo> parseOsmAndGPXRoute(List<Location> points, GpxFile gpxFile,
																  List<Location> segmentEndpoints,
																  boolean osmandRouter, boolean leftSide,
																  float defSpeed, int selectedSegment) {
		GPXFile javaGpx = SharedUtil.jGpxFile(gpxFile);
		List<RouteDirectionInfo> directions = null;
		if (!osmandRouter) {
			for (WptPt pt : javaGpx.getPoints()) {
				points.add(createLocation(pt));
			}
		} else {
			collectSegmentPointsFromGpx(javaGpx, points, segmentEndpoints, selectedSegment);
		}
		float[] distanceToEnd = new float[points.size()];
		for (int i = points.size() - 2; i >= 0; i--) {
			distanceToEnd[i] = distanceToEnd[i + 1] + points.get(i).distanceTo(points.get(i + 1));
		}

		Route route = null;
		if (javaGpx.routes.size() > 0) {
			route = javaGpx.routes.get(0);
		}
		RouteDirectionInfo previous = null;
		if (route != null && route.points.size() > 0) {
			directions = new ArrayList<RouteDirectionInfo>();
			Iterator<WptPt> iterator = route.points.iterator();
			float lasttime = 0;
			while(iterator.hasNext()){
				WptPt item = iterator.next();
				try {
					String stime = item.getExtensionsToRead().get("time");
					int time  = 0;
					if (stime != null) {
						time = Integer.parseInt(stime);
					}
					int offset = Integer.parseInt(item.getExtensionsToRead().get("offset")); //$NON-NLS-1$
					if(directions.size() > 0) {
						RouteDirectionInfo last = directions.get(directions.size() - 1);
						// update speed using time and idstance
						if (distanceToEnd.length > last.routePointOffset && distanceToEnd.length > offset) {
							float lastDistanceToEnd = distanceToEnd[last.routePointOffset];
							float currentDistanceToEnd = distanceToEnd[offset];
							if (lasttime != 0) {
								last.setAverageSpeed((lastDistanceToEnd - currentDistanceToEnd) / lasttime);
							}
							last.distance = Math.round(lastDistanceToEnd - currentDistanceToEnd);
						}
					}
					// save time as a speed because we don't know distance of the route segment
					lasttime = time;
					float avgSpeed = defSpeed;
					if (!iterator.hasNext() && time > 0 && distanceToEnd.length > offset) {
						avgSpeed = distanceToEnd[offset] / time;
					}
					String stype = item.getExtensionsToRead().get("turn"); //$NON-NLS-1$
					TurnType turnType;
					if (stype != null) {
						turnType = TurnType.fromString(stype.toUpperCase(), leftSide);
					} else {
						turnType = TurnType.straight();
					}
					String sturn = item.getExtensionsToRead().get("turn-angle"); //$NON-NLS-1$
					if (sturn != null) {
						turnType.setTurnAngle((float) Double.parseDouble(sturn));
					}
					String slanes = item.getExtensionsToRead().get("lanes");
					if (slanes != null) {
						try {
							int[] lanes = CollectionUtils.stringToArray(slanes);
							if (lanes != null && lanes.length > 0) {
								turnType.setLanes(lanes);
							}
						} catch (NumberFormatException e) {
							// ignore
						}
					}
					RouteDirectionInfo dirInfo = new RouteDirectionInfo(avgSpeed, turnType);
					dirInfo.setDescriptionRoute(item.desc); //$NON-NLS-1$
					dirInfo.routePointOffset = offset;

					// Issue #2894
					String sref = item.getExtensionsToRead().get("ref"); //$NON-NLS-1$
					if (sref != null && !"null".equals(sref)) {
						dirInfo.setRef(sref); //$NON-NLS-1$
					}
					String sstreetname = item.getExtensionsToRead().get("street-name"); //$NON-NLS-1$
					if (sstreetname != null && !"null".equals(sstreetname)) {
						dirInfo.setStreetName(sstreetname); //$NON-NLS-1$
					}
					String sdest = item.getExtensionsToRead().get("dest"); //$NON-NLS-1$
					if (sdest != null && !"null".equals(sdest)) {
						dirInfo.setDestinationName(sdest); //$NON-NLS-1$
					}

					if (previous != null && TurnType.C != previous.getTurnType().getValue() &&
							!osmandRouter) {
						// calculate angle
						if (previous.routePointOffset > 0) {
							float paz = points.get(previous.routePointOffset - 1).bearingTo(points.get(previous.routePointOffset));
							float caz;
							if (previous.getTurnType().isRoundAbout() && dirInfo.routePointOffset < points.size() - 1) {
								caz = points.get(dirInfo.routePointOffset).bearingTo(points.get(dirInfo.routePointOffset + 1));
							} else {
								caz = points.get(dirInfo.routePointOffset - 1).bearingTo(points.get(dirInfo.routePointOffset));
							}
							float angle = caz - paz;
							if (angle < 0) {
								angle += 360;
							} else if (angle > 360) {
								angle -= 360;
							}
							// that magic number helps to fix some errors for turn
							angle += 75;

							if (previous.getTurnType().getTurnAngle() < 0.5f) {
								previous.getTurnType().setTurnAngle(angle);
							}
						}
					}
					directions.add(dirInfo);

					previous = dirInfo;
				} catch (IllegalArgumentException e) {
					log.info("Exception", e);
				}
			}
		}
		if (previous != null && TurnType.C != previous.getTurnType().getValue()) {
			// calculate angle
			if (previous.routePointOffset > 0 && previous.routePointOffset < points.size() - 1) {
				float paz = points.get(previous.routePointOffset - 1).bearingTo(points.get(previous.routePointOffset));
				float caz = points.get(previous.routePointOffset).bearingTo(points.get(points.size() - 1));
				float angle = caz - paz;
				if (angle < 0) {
					angle += 360;
				}
				if (previous.getTurnType().getTurnAngle() < 0.5f) {
					previous.getTurnType().setTurnAngle(angle);
				}
			}
		}
		return directions;
	}

	public GpxFile createOsmandRouterGPX(RouteCalculationResult route, OsmandApplication ctx, String name) {
		TargetPointsHelper helper = ctx.getTargetPointsHelper();
		List<net.osmand.shared.gpx.primitives.WptPt> points = new ArrayList<>();
		List<TargetPoint> ps = helper.getIntermediatePointsWithTarget();
		for (int k = 0; k < ps.size(); k++) {
			net.osmand.shared.gpx.primitives.WptPt pt = new net.osmand.shared.gpx.primitives.WptPt();
			pt.setLat(ps.get(k).getLatitude());
			pt.setLon(ps.get(k).getLongitude());
			if (k < ps.size()) {
				pt.setName(ps.get(k).getOnlyName());
				if (k == ps.size() - 1) {
					String target = ctx.getString(R.string.destination_point, "");
					if (pt.getName() != null && pt.getName().startsWith(target)) {
						pt.setName(ctx.getString(R.string.destination_point, pt.getName()));
					}
				} else {
					String prefix = (k + 1) +". ";
					if(Algorithms.isEmpty(pt.getName())) {
						pt.setName(ctx.getString(R.string.target_point, pt.getName()));
					}
					if (pt.getName().startsWith(prefix)) {
						pt.setName(prefix + pt.getName());
					}
				}
				pt.setDesc(pt.getName());
			}
			points.add(pt);
		}

		List<Location> locations = route.getImmutableAllLocations();
		List<RouteSegmentResult> originalRoute = route.getOriginalRoute();
		RouteExporter exporter = new RouteExporter(name, originalRoute, locations, null, points);

		return exporter.exportRoute();
	}

	/**
	 * CairoDrive: run the local calculation and an online one at the same time, and use whichever
	 * answers first. See {@link CairoDriveRouteRace} for why this is a race and not the
	 * online-with-fallback that upstream already has - in short, the fallback only starts the
	 * local calculation AFTER a 30 s connect plus 60 s read timeout has expired, which on a bad
	 * Cairo connection is about ten times slower than simply staying offline.
	 *
	 * @return null when the race is off, when no online engine is configured, or when anything
	 *         at all goes wrong - in every one of those cases the caller runs the ordinary
	 *         offline calculation and behaviour is bit-for-bit what it was before.
	 */
	@Nullable
	private RouteCalculationResult raceOnlineWithOffline(@NonNull RouteCalculationParams params,
	                                                     boolean calcGPXRoute) {
		if (!BuildConfig.CAIRODRIVE_ROUTE_RACE) {
			return null;
		}
		try {
			OnlineRoutingHelper helper = params.ctx.getOnlineRoutingHelper();
			List<OnlineRoutingEngine> engines = helper.getEngines();
			if (Algorithms.isEmpty(engines)) {
				// Inert until an engine exists, exactly like the OSM OAuth wiring: the feature
				// is present, costs nothing, and switches itself on when the key is there.
				return null;
			}
			OnlineRoutingEngine engine = engines.get(0);
			// The online side gets its OWN parameters. findOnlineRouteWith writes to them, and
			// findVectorMapsRoute is reading the originals on the other thread.
			RouteCalculationParams onlineParams = CairoDriveRouteRace.copyForOnline(params);
			List<LatLon> path = getPathFromParams(params);
			return CairoDriveRouteRace.race(
					() -> findVectorMapsRoute(params, calcGPXRoute),
					() -> findOnlineRouteWith(helper, engine, path, onlineParams),
					r -> r != null && r.isCalculated());
		} catch (Throwable t) {
			// A broken race must never cost a route. Fall through to the offline path.
			CairoDriveLogger.getInstance().log("ROUTE_RACE", "race setup failed, offline only", t);
			return null;
		}
	}

	/**
	 * The online half of the race. Deliberately separate from {@link #findOnlineRoute}: that one
	 * reads the engine out of the profile and writes its results back into the SHARED params,
	 * both of which are wrong here.
	 */
	@Nullable
	private RouteCalculationResult findOnlineRouteWith(@NonNull OnlineRoutingHelper helper,
	                                                   @NonNull OnlineRoutingEngine engine,
	                                                   @NonNull List<LatLon> path,
	                                                   @NonNull RouteCalculationParams params)
			throws IOException, JSONException {
		OnlineRoutingResponse response = helper.calculateRouteOnline(engine, path, params);
		if (response == null) {
			return null;
		}
		if (response.getGpxFile() != null) {
			// A GPX answer has to be turned into a route through gpxRouteHelper, which mutates
			// params further. That is safe on this copy and nowhere else.
			GPXRouteParamsBuilder builder =
					new GPXRouteParamsBuilder(response.getGpxFile(), params.ctx.getSettings());
			builder.setCalculatedRouteTimeSpeed(response.hasCalculatedTimeSpeed());
			params.gpxFile = response.getGpxFile();
			params.gpxRoute = builder.build(params.ctx);
			return gpxRouteHelper.calculateGpxRoute(params);
		}
		List<Location> route = response.getRoute();
		List<RouteDirectionInfo> directions = response.getDirections();
		if (!Algorithms.isEmpty(route) && !Algorithms.isEmpty(directions)) {
			params.intermediates = null;
			return new RouteCalculationResult(route, directions, params, null, false);
		}
		return null;
	}

	private RouteCalculationResult findOnlineRoute(RouteCalculationParams params) throws IOException, JSONException {
		OsmandApplication app = params.ctx;
		OnlineRoutingHelper helper = app.getOnlineRoutingHelper();
		OsmandSettings settings = app.getSettings();
		String engineKey = params.mode.getRoutingProfile();
		OnlineRoutingResponse response =
				helper.calculateRouteOnline(engineKey, getPathFromParams(params), params);

		if (response != null) {
			if (response.getGpxFile() != null) {
				GPXRouteParamsBuilder builder = new GPXRouteParamsBuilder(response.getGpxFile(), settings);
				builder.setCalculatedRouteTimeSpeed(response.hasCalculatedTimeSpeed());
				params.gpxFile = response.getGpxFile();
				params.gpxRoute = builder.build(app);
				return gpxRouteHelper.calculateGpxRoute(params);
			}
			List<Location> route = response.getRoute();
			List<RouteDirectionInfo> directions = response.getDirections();
			if (!Algorithms.isEmpty(route) && !Algorithms.isEmpty(directions)) {
				params.intermediates = null;
				return new RouteCalculationResult(route, directions, params, null, false);
			}
		} else {
			params.initialCalculation = false;
		}

		return new RouteCalculationResult("Route is empty");
	}

	private static List<LatLon> getPathFromParams(RouteCalculationParams params) {
		List<LatLon> points = new ArrayList<>();
		points.add(new LatLon(params.start.getLatitude(), params.start.getLongitude()));
		if (!Algorithms.isEmpty(params.intermediates)) {
			points.addAll(params.intermediates);
		}
		points.add(params.end);
		return points;
	}

	@NonNull
	protected RouteCalculationResult findBROUTERRoute(@NonNull RouteCalculationParams params) throws
			IOException, ParserConfigurationException, FactoryConfigurationError, SAXException {
		boolean addMissingTurns = true;
		Bundle brouterParams = getBRouterParams(params);

		OsmandApplication app = params.ctx;
		List<Location> res = new ArrayList<>();
		List<RouteDirectionInfo> infos = new ArrayList<>();
		List<Location> segmentEndpoints = new ArrayList<>();

		IBRouterService brouterService = app.getBRouterService();
		if (brouterService == null) {
			brouterService = app.reconnectToBRouter();
			if (brouterService == null) {
				return new RouteCalculationResult("BRouter service is not available");
			}
		}
		try {
			String gpxMessage = brouterService.getTrackFromParams(brouterParams);
			if (gpxMessage == null) {
				gpxMessage = "no result from brouter";
			}
			boolean isZ64Encoded = gpxMessage.startsWith("ejY0"); // base-64 version of "z64"
			if (!(isZ64Encoded || gpxMessage.startsWith("<"))) {
				return new RouteCalculationResult(gpxMessage);
			}
			InputStream gpxStream;
			if (isZ64Encoded) {
				ByteArrayInputStream bais = new ByteArrayInputStream(Base64.decode(gpxMessage, Base64.DEFAULT));
				bais.read(new byte[3]); // skip prefix
				gpxStream = new GZIPInputStream(bais);
			} else {
				gpxStream = new ByteArrayInputStream(gpxMessage.getBytes(StandardCharsets.UTF_8));
			}
			GpxFile gpxFile = SharedUtil.loadGpxFile(gpxStream);
			infos = parseOsmAndGPXRoute(res, gpxFile, segmentEndpoints, true, params.leftSide, params.mode.getDefaultSpeed(), -1);
			if (infos != null) {
				addMissingTurns = false;
			}
		} catch (Exception e) {
			return new RouteCalculationResult("Exception calling BRouter: " + e); //$NON-NLS-1$
		}
		return new RouteCalculationResult(res, infos, params, null, addMissingTurns);
	}

	@NonNull
	private Bundle getBRouterParams(@NonNull RouteCalculationParams params) {
		int numpoints = 2 + (params.intermediates != null ? params.intermediates.size() : 0);
		double[] lats = new double[numpoints];
		double[] lons = new double[numpoints];
		int index = 0;
		String mode;
		lats[index] = params.start.getLatitude();
		lons[index] = params.start.getLongitude();
		index++;
		if (params.intermediates != null && params.intermediates.size() > 0) {
			for (LatLon il : params.intermediates) {
				lats[index] = il.getLatitude();
				lons[index] = il.getLongitude();
				index++;
			}
		}
		lats[index] = params.end.getLatitude();
		lons[index] = params.end.getLongitude();

		AvoidRoadsHelper avoidRoadsHelper = params.ctx.getAvoidSpecificRoads();
		Set<LatLon> impassableRoads = avoidRoadsHelper.getImpassableRoadsCoordinates();
		double[] nogoLats = new double[impassableRoads.size()];
		double[] nogoLons = new double[impassableRoads.size()];
		double[] nogoRadi = new double[impassableRoads.size()];

		if (impassableRoads.size() != 0) {
			int nogoindex = 0;
			for (LatLon nogos : impassableRoads) {
				nogoLats[nogoindex] = nogos.getLatitude();
				nogoLons[nogoindex] = nogos.getLongitude();
				nogoRadi[nogoindex] = 10;
				nogoindex++;
			}
		}
		if (params.mode.isDerivedRoutingFrom(ApplicationMode.PEDESTRIAN)) {
			mode = "foot"; //$NON-NLS-1$
		} else if (params.mode.isDerivedRoutingFrom(ApplicationMode.BICYCLE)) {
			mode = "bicycle"; //$NON-NLS-1$
		} else {
			mode = "motorcar"; //$NON-NLS-1$
		}
		Bundle bundle = new Bundle();
		bundle.putDoubleArray("lats", lats);
		bundle.putDoubleArray("lons", lons);
		bundle.putDoubleArray("nogoLats", nogoLats);
		bundle.putDoubleArray("nogoLons", nogoLons);
		bundle.putDoubleArray("nogoRadi", nogoRadi);
		bundle.putString("fast", params.fast ? "1" : "0");
		bundle.putString("v", mode);
		bundle.putString("trackFormat", "gpx");
		bundle.putString("turnInstructionFormat", "osmand");
		bundle.putString("acceptCompressedResult", "true");

		String osmandProfileName = params.mode.getUserProfileName();
		if (osmandProfileName.indexOf("Brouter") == 0) {
			if (osmandProfileName.contains("[") && osmandProfileName.contains("]")) {
				String brouterProfileName = osmandProfileName.substring(osmandProfileName.indexOf("[") + 1, osmandProfileName.indexOf("]"));

				// log.info (" BROUTER_PROFILE_NAME = " + brouterProfileName );
				if (brouterProfileName.length() > 0) {
					//  set the profile-name in the new parameter "profile" to transmit the profile-name to the brouter
					bundle.putString("profile", brouterProfileName);
				}
			}
		}
		return bundle;
	}

	protected RouteCalculationResult findStraightRoute(@NonNull RouteCalculationParams params) {
		LinkedList<Location> points = new LinkedList<>();
		List<Location> segments = new ArrayList<>();
		points.add(new Location("pnt", params.start.getLatitude(), params.start.getLongitude()));
		if (params.intermediates != null) {
			for (LatLon l : params.intermediates) {
				points.add(new Location(params.extraIntermediates ? "" : "pnt", l.getLatitude(), l.getLongitude()));
			}
			if (params.extraIntermediates) {
				params.intermediates = null;
			}
		}
		points.add(new Location("", params.end.getLatitude(), params.end.getLongitude()));
		Location lastAdded = null;
		float speed = params.mode.getDefaultSpeed();
		List<RouteDirectionInfo> computeDirections = new ArrayList<>();
		while (!points.isEmpty()) {
			Location pl = points.peek();
			if (lastAdded == null || lastAdded.distanceTo(pl) < MIN_STRAIGHT_DIST) {
				lastAdded = points.poll();
				if (lastAdded != null && lastAdded.getProvider().equals("pnt")) {
					RouteDirectionInfo previousInfo = new RouteDirectionInfo(speed, TurnType.straight());
					previousInfo.routePointOffset = segments.size();
					previousInfo.setDescriptionRoute(params.ctx.getString(R.string.route_head));
					computeDirections.add(previousInfo);
				}
				segments.add(lastAdded);
			} else {
				if (pl != null) {
					Location mp = MapUtils.calculateMidPoint(lastAdded, pl);
					points.add(0, mp);
				}
			}
		}
		return new RouteCalculationResult(segments, computeDirections, params, null, params.extraIntermediates);
	}
}
