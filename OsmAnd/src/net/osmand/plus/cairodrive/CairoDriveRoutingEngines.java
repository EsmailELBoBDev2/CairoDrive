package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.cairodrive.providers.ApiHealth;
import net.osmand.plus.cairodrive.providers.ProviderBudget;
import net.osmand.plus.helpers.CairoDriveLog;
import net.osmand.plus.onlinerouting.EngineParameter;
import net.osmand.plus.onlinerouting.OnlineRoutingHelper;
import net.osmand.plus.onlinerouting.engine.GeoapifyEngine;
import net.osmand.plus.onlinerouting.engine.GraphhopperEngine;
import net.osmand.plus.onlinerouting.engine.OnlineRoutingEngine;
import net.osmand.plus.onlinerouting.engine.OrsEngine;
import net.osmand.util.Algorithms;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the online routing engines from the build keys, so the owner never sets one up by hand.
 *
 * <h3>Why the engines are created in code</h3>
 *
 * Upstream expects a human to open Settings, pick a provider, paste an API key and choose a
 * vehicle profile. That is a reasonable thing to ask of someone at a desk and an unreasonable
 * thing to ask of someone in a car in Cairo, which is the only place this fork is ever used. The
 * keys already arrive through {@code BuildConfig} like every other provider in this app, so the
 * engine can simply be assembled from them at startup.
 *
 * <h3>One request per reroute, not three</h3>
 *
 * These providers are BACKUPS for one another, not a fan-out. {@link #pick} returns a single
 * engine, and the race in {@link CairoDriveRouteRace} runs that one against the LOCAL
 * calculation - which is the comparison that matters, since the local one is running anyway.
 * Querying all three at once would treble the mobile data and battery cost of every reroute in
 * order to beat whichever was fastest by a few milliseconds.
 *
 * <h3>Order, and why it is not simply "biggest number first"</h3>
 *
 * Read from the provider dashboards on 2026-08-06, against roughly 12 reroutes per drive:
 *
 * <ul>
 *   <li><b>ORS</b> - Directions V2, 2000/day, 40/min. The most genuinely free headroom, so it
 *       leads.</li>
 *   <li><b>GraphHopper</b> - 500 credits/day, and a hard limit of 5 waypoints per request. The
 *       waypoint limit is a real structural constraint rather than a budget one, so a route with
 *       more points than that skips this provider instead of sending a request that would be
 *       rejected.</li>
 *   <li><b>Geoapify</b> - last, and capped tightest, despite having the largest allowance of the
 *       three on paper at 3000 credits/day. That 3000 is SHARED with Places and geocoding, and
 *       the dashboard showed Places alone consuming 3,881 credits in a month, so a request made
 *       here is taken away from search rather than drawn from spare headroom.</li>
 * </ul>
 *
 * <p>Geoapify needed {@link net.osmand.plus.onlinerouting.engine.GeoapifyEngine} written for it
 * first: its Routing API returns a GeoJSON shape no existing engine can read, close enough to
 * ORS to invite reuse and different in three ways that would each have produced a silently wrong
 * route. See that class for the specifics.
 */
public final class CairoDriveRoutingEngines {

	/** NO "CD_" prefix: {@link CairoDriveLog#log} adds it. */
	private static final String TRACE_TAG = "ROUTE_ENGINE";

	/** Names are stable: they are how an already-created engine is recognised on later starts. */
	private static final String ORS_NAME = "CairoDrive ORS";
	private static final String GRAPHHOPPER_NAME = "CairoDrive GraphHopper";
	private static final String GEOAPIFY_NAME = "CairoDrive Geoapify";

	/**
	 * Daily caps, far below each provider's allowance.
	 *
	 * <p>These do not exist to ration a scarce resource - 12 reroutes a drive against 2000 is not
	 * scarce. They exist because a LOOP is the realistic failure here: a reroute storm on a bad
	 * GPS fix could spend a day's allowance in minutes and the first anyone would know is a drive
	 * with no online routing at all. The cap turns that into a logged stand-down.
	 */
	private static final int ORS_DAILY_CAP = 200;
	private static final int GRAPHHOPPER_DAILY_CAP = 100;
	/**
	 * Lowest cap of the three despite Geoapify's 3000 being the largest allowance, because
	 * that 3000 is SHARED with Places and geocoding - the dashboard showed Places alone using
	 * 3,881 credits in a month. Routing here competes with search, so it is last in line and
	 * capped tightest.
	 */
	private static final int GEOAPIFY_DAILY_CAP = 50;

	/** GraphHopper's free plan: "Max Locations: 5". Not a budget, a hard API limit. */
	private static final int GRAPHHOPPER_MAX_POINTS = 5;

	private static final String PREF_ORS_DAY = "cairodrive_ors_day";
	private static final String PREF_ORS_COUNT = "cairodrive_ors_count";
	private static final String PREF_GH_DAY = "cairodrive_graphhopper_day";
	private static final String PREF_GH_COUNT = "cairodrive_graphhopper_count";
	private static final String PREF_GEO_DAY = "cairodrive_geoapify_route_day";
	private static final String PREF_GEO_COUNT = "cairodrive_geoapify_route_count";

	private static volatile boolean ensured;

	private CairoDriveRoutingEngines() {
	}

	private static boolean hasOrsKey() {
		return !Algorithms.isEmpty(BuildConfig.CAIRODRIVE_ORS_KEY);
	}

	private static boolean hasGraphhopperKey() {
		return !Algorithms.isEmpty(BuildConfig.CAIRODRIVE_GRAPHHOPPER_KEY);
	}

	private static boolean hasGeoapifyKey() {
		return !Algorithms.isEmpty(BuildConfig.CAIRODRIVE_GEOAPIFY_KEY);
	}

	/**
	 * Create any engine whose key is present and which does not exist yet. Safe to call more than
	 * once; it is a no-op after the first successful pass.
	 *
	 * <p>An engine is matched by NAME rather than by key, because the generated key differs every
	 * time one is created - matching on it would add a duplicate engine on every cold start until
	 * the settings file was full of them.
	 */
	public static void ensureConfigured(@NonNull OsmandApplication app) {
		if (ensured) {
			return;
		}
		try {
			OnlineRoutingHelper helper = app.getOnlineRoutingHelper();
			if (hasOrsKey() && findByName(helper, ORS_NAME) == null) {
				Map<String, String> params = new HashMap<>();
				params.put(EngineParameter.API_KEY.name(), BuildConfig.CAIRODRIVE_ORS_KEY);
				params.put(EngineParameter.VEHICLE_KEY.name(), "driving-car");
				params.put(EngineParameter.CUSTOM_NAME.name(), ORS_NAME);
				helper.saveEngine(new OrsEngine(params));
				CairoDriveLog.log(TRACE_TAG, "created " + ORS_NAME);
			}
			if (hasGraphhopperKey() && findByName(helper, GRAPHHOPPER_NAME) == null) {
				Map<String, String> params = new HashMap<>();
				params.put(EngineParameter.API_KEY.name(), BuildConfig.CAIRODRIVE_GRAPHHOPPER_KEY);
				params.put(EngineParameter.VEHICLE_KEY.name(), "car");
				params.put(EngineParameter.CUSTOM_NAME.name(), GRAPHHOPPER_NAME);
				helper.saveEngine(new GraphhopperEngine(params));
				CairoDriveLog.log(TRACE_TAG, "created " + GRAPHHOPPER_NAME);
			}
			if (hasGeoapifyKey() && findByName(helper, GEOAPIFY_NAME) == null) {
				Map<String, String> params = new HashMap<>();
				params.put(EngineParameter.API_KEY.name(), BuildConfig.CAIRODRIVE_GEOAPIFY_KEY);
				params.put(EngineParameter.VEHICLE_KEY.name(), "drive");
				params.put(EngineParameter.CUSTOM_NAME.name(), GEOAPIFY_NAME);
				helper.saveEngine(new GeoapifyEngine(params));
				CairoDriveLog.log(TRACE_TAG, "created " + GEOAPIFY_NAME);
			}
			if (!hasOrsKey() && !hasGraphhopperKey() && !hasGeoapifyKey()) {
				CairoDriveLog.log(TRACE_TAG, "no online routing key in this build - race inert");
			}
			// Set only on a clean pass. Setting it up-front would be a latch on FAILURE: called
			// from startup, before the routing helper is ready, one throw would mark the work
			// done for the life of the process and no reroute would ever get an engine.
			ensured = true;
		} catch (Throwable t) {
			// Never fatal, and deliberately retryable - pick() calls this again on the next
			// reroute, by which time the helper exists. Without an engine the race simply does
			// not run and the app routes locally, as it did before any of this existed.
			CairoDriveLog.log(TRACE_TAG, "could not configure engines, will retry: " + t);
		}
	}

	/**
	 * The one engine to race on this reroute, or null to route locally only.
	 *
	 * @param pointCount waypoints in the request, used to respect GraphHopper's 5-point limit.
	 */
	@Nullable
	public static OnlineRoutingEngine pick(@NonNull OsmandApplication app, int pointCount) {
		try {
			ensureConfigured(app);
			OnlineRoutingHelper helper = app.getOnlineRoutingHelper();

			OnlineRoutingEngine ors = findByName(helper, ORS_NAME);
			if (ors != null && ProviderBudget.claim(app, ApiHealth.Api.ORS,
					PREF_ORS_DAY, PREF_ORS_COUNT, ORS_DAILY_CAP)) {
				return ors;
			}

			OnlineRoutingEngine gh = findByName(helper, GRAPHHOPPER_NAME);
			if (gh != null) {
				if (pointCount > GRAPHHOPPER_MAX_POINTS) {
					// Skipped rather than attempted: the free plan rejects more than 5 locations,
					// so sending it would spend a request to receive an error. Note this only
					// rules GraphHopper out - the next provider has no such limit, so selection
					// continues rather than giving up on the whole race.
					ApiHealth.recordSkipped(ApiHealth.Api.GRAPHHOPPER, ApiHealth.Skip.NOT_APPLICABLE);
					CairoDriveLog.log(TRACE_TAG, "graphhopper skipped, " + pointCount
							+ " points exceeds its " + GRAPHHOPPER_MAX_POINTS + " limit");
				} else if (ProviderBudget.claim(app, ApiHealth.Api.GRAPHHOPPER,
						PREF_GH_DAY, PREF_GH_COUNT, GRAPHHOPPER_DAILY_CAP)) {
					return gh;
				} else {
					ApiHealth.recordSkipped(ApiHealth.Api.GRAPHHOPPER, ApiHealth.Skip.BUDGET_SPENT);
				}
			}

			OnlineRoutingEngine geo = findByName(helper, GEOAPIFY_NAME);
			if (geo != null && ProviderBudget.claim(app, ApiHealth.Api.GEOAPIFY_ROUTING,
					PREF_GEO_DAY, PREF_GEO_COUNT, GEOAPIFY_DAILY_CAP)) {
				return geo;
			}
			if (geo != null) {
				ApiHealth.recordSkipped(ApiHealth.Api.GEOAPIFY_ROUTING, ApiHealth.Skip.BUDGET_SPENT);
			}
			if (ors != null) {
				ApiHealth.recordSkipped(ApiHealth.Api.ORS, ApiHealth.Skip.BUDGET_SPENT);
			}
			return null;
		} catch (Throwable t) {
			CairoDriveLog.log(TRACE_TAG, "engine selection failed: " + t);
			return null;
		}
	}

	@Nullable
	private static OnlineRoutingEngine findByName(@NonNull OnlineRoutingHelper helper,
	                                              @NonNull String name) {
		List<OnlineRoutingEngine> engines = helper.getEngines();
		if (engines == null) {
			return null;
		}
		for (OnlineRoutingEngine e : engines) {
			if (e != null && name.equals(e.get(EngineParameter.CUSTOM_NAME))) {
				return e;
			}
		}
		return null;
	}
}
