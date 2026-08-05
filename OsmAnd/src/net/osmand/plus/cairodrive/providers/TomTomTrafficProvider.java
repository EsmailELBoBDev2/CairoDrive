package net.osmand.plus.cairodrive.providers;

import android.os.Process;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.data.LatLon;
import net.osmand.osm.io.NetworkUtils;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.api.SettingsAPI;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.plus.routing.RouteCalculationResult;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;

/**
 * TomTom traffic - closures and incidents on the corridor ahead, plus per-segment flow.
 *
 * <h3>Why TomTom serves both traffic slots</h3>
 *
 * It is the only major traffic vendor with no "you must display our basemap" clause, so its data
 * can legally sit on top of OsmAnd's OSM rendering - which is the whole reason
 * {@link CairoDriveProviders#PRIORITY_TOMTOM} outranks {@link CairoDriveProviders#PRIORITY_GOOGLE}
 * even though Google's Cairo coverage is better. See CairoDriveProviders for that argument; this
 * file only implements it.
 *
 * <h3>The two endpoints cost wildly different amounts, and that drives the whole design</h3>
 *
 * <ul>
 *   <li><b>Incidents</b> ({@code incidentDetails}, v5) takes a bounding box, so ONE request covers
 *       the entire corridor ahead however long the route is. Cheap per unit of information, and
 *       polled on the shorter cadence because a closure is the only thing here that can change
 *       BEHAVIOUR - it can become a nogo point and send the router the long way round.</li>
 *   <li><b>Flow</b> ({@code flowSegmentData}, v4) takes a single {@code point} and answers for the
 *       one road segment under it. There is no batch form. A route is therefore N requests, and N
 *       is chosen by us: {@value #FLOW_SAMPLE_POINTS} points on a {@value #FLOW_INTERVAL_MS} ms
 *       cadence rather than a dense sweep, because flow only corrects an ETA. Sampling the whole
 *       route densely would spend the entire day's allowance inside one drive to move a number
 *       the driver glances at.</li>
 * </ul>
 *
 * <h3>Confidence is the reason flow is worth having at all</h3>
 *
 * {@code flowSegmentData} returns a per-segment {@code confidence} saying how much probe data backs
 * THAT road. No other provider in this system exposes it, and in Cairo it is the difference between
 * a signal and a guess: the Ring Road carries orders of magnitude more probes than an unnamed
 * alley, so a single city-wide trust level is meaningless. It is carried into
 * {@link CairoDriveProviders.FlowSample#confidence} untouched so downstream gates per sample, and
 * its distribution is logged on every poll so a drive can answer "is flow usable on the roads he
 * actually drives" rather than "is flow usable in Cairo".
 *
 * <h3>Budget</h3>
 *
 * The non-tile transaction allowance is treated as scarce AND unverified - nobody here has
 * confirmed what this account is entitled to, so the code must not be the thing that finds out.
 * Two independent daily caps ({@value #INCIDENT_DAILY_CAP} incident requests,
 * {@value #FLOW_DAILY_CAP} flow points) are counted into persisted settings keyed by UTC day,
 * exactly as {@code GoogleTrafficHelper.claimRequestTier} does, so force-stopping the app cannot
 * reset the budget. Separate counters rather than one pool because the two endpoints fail
 * differently: exhausting flow must not silently stop reporting closures.
 *
 * <h3>Safety</h3>
 *
 * Two hard gates (a key compiled into BuildConfig AND {@code CAIRODRIVE_TOMTOM_TRAFFIC}) mean the
 * default build makes zero requests. Arbitration is re-checked per endpoint before every poll, so
 * a provider that lost a capability spends nothing on it. Everything blocking runs on a
 * MIN_PRIORITY worker; the caller - a GPS callback that may be on the main thread - only does
 * arithmetic and a list copy. Every network path swallows its own exceptions and returns.
 *
 * @see CairoDriveProviders
 */
public final class TomTomTrafficProvider implements CairoDriveProviders.Provider {

	private static final String TRACE_TAG = "CD_TRAFFIC";

	private static final String BASE = "https://api.tomtom.com";
	private static final String INCIDENTS_PATH = "/traffic/services/5/incidentDetails";
	private static final String FLOW_PATH = "/traffic/services/4/flowSegmentData/relative0/10/json";

	/**
	 * Asks for the smallest response that still supports a map pin, a closure decision and a
	 * readable line of text. Every extra field is bytes over a metered cellular link in a moving
	 * car, and {@code geometry} in particular can be a long polyline per incident.
	 */
	private static final String INCIDENT_FIELDS =
			"{incidents{type,geometry{type,coordinates},properties{iconCategory,"
					+ "magnitudeOfDelay,delay,roadNumbers,events{description,code}}}}";

	/**
	 * Arabic first because the car is driven in Cairo and the descriptions are read at a glance.
	 * TomTom rejects an unsupported language tag with HTTP 400 rather than falling back, so
	 * {@link #languageFallback} latches to this list's second entry on the first such rejection.
	 */
	private static final String LANGUAGE_PRIMARY = "ar-EG";
	private static final String LANGUAGE_FALLBACK = "en-GB";

	// ------------------------------------------------------------------ incident vocabulary
	//
	// TomTom's v5 iconCategory numbering. Written out rather than used as bare integers because
	// the brief this was built from quoted a DIFFERENT numbering (8=road closed was given as
	// 6=lane closed, flooding as 10), and a silent disagreement between two tables is exactly the
	// kind of thing that produces a plausible-looking wrong answer months later. The table below
	// is TomTom's published one; the mitigation for it being wrong is two-fold:
	//   1. the request filter is a SUPERSET covering both readings, so nothing is filtered away
	//      server-side on the strength of a number nobody has verified on the wire;
	//   2. the closure decision does not rest on the icon alone - see closureFrom().
	// Verify against a real response before anything acts on a single category id.

	private static final int ICON_JAM = 6;
	private static final int ICON_LANE_CLOSED = 7;
	private static final int ICON_ROAD_CLOSED = 8;
	private static final int ICON_ROAD_WORKS = 9;
	private static final int ICON_FLOODING = 11;

	/**
	 * What the server is asked to return. Deliberately wider than the categories acted on: a
	 * category that arrives and is ignored costs a few bytes, whereas a category filtered out
	 * server-side cannot be recovered without spending another transaction.
	 *
	 * <p>Flooding is in here for a Cairo-specific reason rather than for completeness - a winter
	 * downpour closes underpasses across the city and it is the one hazard an offline router has
	 * no way of knowing about.
	 */
	private static final String CATEGORY_FILTER = ICON_JAM + "," + ICON_LANE_CLOSED + ","
			+ ICON_ROAD_CLOSED + "," + ICON_ROAD_WORKS + "," + ICON_FLOODING;

	/**
	 * {@code magnitudeOfDelay} 4 is TomTom's "undefined", which is what it reports for a road
	 * closure and other indefinite delays. It is a second, independent witness to a closure that
	 * does not depend on the icon numbering above being right, which is why closureFrom() ORs the
	 * two rather than trusting either alone.
	 */
	private static final int MAGNITUDE_UNDEFINED = 4;

	// ------------------------------------------------------------------ cadence and budget

	/**
	 * 2.5 minutes. The corridor bbox covers everything ahead in one request, so the cadence is set
	 * by how fast a closure needs to become actionable rather than by cost. Faster than the 10
	 * minute {@link CairoDriveProviders#INCIDENTS_TTL_MS} so three or four polls can be lost in an
	 * underpass before the data expires.
	 */
	private static final long INCIDENT_INTERVAL_MS = 90 * 1000L;
	/** Matches TomTom's own 1-minute data refresh; each tick costs {@value #FLOW_SAMPLE_POINTS} requests. */
	private static final long FLOW_INTERVAL_MS = 60 * 1000L;

	/**
	 * A recalculated route should be scored soon but not instantly. Without this the GPS churn
	 * around a reroute fires a poll per fix - the same trap {@code GoogleTrafficHelper.onNewRoute}
	 * exists to avoid - and here it would cost a whole flow sweep each time.
	 */
	private static final long REROUTE_DEBOUNCE_MS = 45 * 1000L;

	/**
	 * How far ahead the corridor extends, for BOTH endpoints.
	 *
	 * <p>Bounded rather than "the whole route" because the incident query is a rectangle, and a
	 * rectangle around a 60 km route across Cairo contains most of Cairo - the response would be
	 * dominated by incidents on roads the driver will never touch, and the flow samples would be
	 * spaced so far apart that none of them describes the next twenty minutes. 25 km at Cairo
	 * arterial speeds is roughly half an hour ahead, which is further than any of this data stays
	 * fresh anyway.
	 */
	private static final double CORRIDOR_M = 25000;
	/** Below this, arrival is imminent and no transaction is worth spending. */
	private static final double MIN_REMAINING_M = 1500;

	/** 6 - the middle of the 5-8 the corridor can carry without the spacing becoming meaningless. */
	private static final int FLOW_SAMPLE_POINTS = 10;
	/**
	 * A sweep of one point is not a sample of a corridor, it is a single reading presented as one.
	 * If the budget cannot cover at least this many, the poll is skipped whole and the previous
	 * flow is left to expire on its own TTL.
	 */
	private static final int FLOW_MIN_POINTS = 3;

	/**
	 * 80 a day, and that number is floor(2500/31).
	 *
	 * <p><b>TomTom's free tier is 2,500 incident requests per MONTH, not per day.</b> An earlier
	 * pass had it as daily - a 31x error, taken from a research summary because the pricing page
	 * itself was unreachable - and briefly shipped a cap of 450/day, which is 13,950 a month
	 * against a 2,500 allowance. The owner pasted the actual page and it says "Free 2.5K monthly".
	 *
	 * <p>So every daily cap here is now floor(monthlyFree / 31): 31 is the longest month, so a cap
	 * derived from it cannot overrun a shorter one. 80 x 31 = 2,480 of 2,500 - 99.2%, and the
	 * remaining 20 is the rounding, not a reserve.
	 *
	 * <p>At the 90 s cadence that is 120 minutes of driving before it runs dry, against ~45 minutes
	 * of typical use, so a normal day spends 36% of the month's allowance.
	 */
	private static final int INCIDENT_DAILY_CAP = 80;
	/**
	 * 645 flow POINTS a day, not polls - floor(20000/31), the same monthly-derived rule as the
	 * incident cap above. 645 x 31 = 19,995 of TomTom's 20,000 per month: 100.0%, to the point
	 * where the only thing left is the rounding.
	 *
	 * <p>Counted in points because points are what the vendor bills, and
	 * {@value #FLOW_SAMPLE_POINTS} of them go out per sweep.
	 */
	private static final int FLOW_DAILY_CAP = 645;

	/**
	 * Fraction of the daily budget after which the sweep thins instead of stopping.
	 *
	 * <h3>The failure this replaces</h3>
	 *
	 * A hard cap fails at the worst possible moment. Spending the budget at full rate and then
	 * going silent for the rest of the UTC day means traffic disappears two and a half hours into
	 * a drive - and a drive that long is exactly the one where it is worth having. Raising the
	 * number does not fix that shape; it moves the cliff twenty minutes later.
	 *
	 * <p>So past this fraction the sweep degrades: fewer points per pass and a longer interval,
	 * which trades spatial and temporal resolution for reach. Half resolution on hour four is
	 * worth more than full resolution for two hours and nothing after.
	 */
	private static final double BUDGET_THIN_FRACTION = 0.70;

	/** Points per sweep once past {@link #BUDGET_THIN_FRACTION}. */
	private static final int FLOW_SAMPLE_POINTS_THIN = 5;

	/** Multiplier on the poll interval once past {@link #BUDGET_THIN_FRACTION}. */
	private static final int THIN_INTERVAL_FACTOR = 2;

	/**
	 * How many points this sweep should ask for, given what is left.
	 *
	 * <p>Reads the budget rather than a timer, so it degrades on ACTUAL consumption - a day with
	 * two long drives thins at the same point as one continuous drive of the same total length.
	 */
	/** Flow points already spent today. Cheap: one preference read, no network, no lock. */
	private static int usedFlowPoints(@NonNull OsmandApplication app) {
		try {
			return used(app, PREF_FLOW_COUNT);
		} catch (Throwable t) {
			// Unknown budget is treated as SPENT, so an accounting failure degrades rather than
			// overspends - the same principle as claimFlowRequests refusing on a failed claim.
			return FLOW_DAILY_CAP;
		}
	}

	private static int samplePointsForBudget(int flowUsed) {
		return flowUsed >= FLOW_DAILY_CAP * BUDGET_THIN_FRACTION
				? FLOW_SAMPLE_POINTS_THIN
				: FLOW_SAMPLE_POINTS;
	}

	/** The poll floor for this sweep, stretched once the budget is mostly spent. */
	private static long flowIntervalForBudget(int flowUsed) {
		return flowUsed >= FLOW_DAILY_CAP * BUDGET_THIN_FRACTION
				? FLOW_INTERVAL_MS * THIN_INTERVAL_FACTOR
				: FLOW_INTERVAL_MS;
	}

	private static final int CONNECT_TIMEOUT_MS = 8000;
	private static final int READ_TIMEOUT_MS = 12000;
	/** Enough for a whole city centre's worth; a longer list is a bbox mistake, not useful data. */
	private static final int MAX_INCIDENTS = 60;
	/** Keeps one pathological description from dominating a log line and a banner. */
	private static final int MAX_DESCRIPTION_CHARS = 120;

	/**
	 * ~1.1 km. A route that runs due north gives a zero-width bounding box, which TomTom rejects
	 * as a degenerate rectangle; padding both axes to a floor also catches the case where the
	 * corridor is short because arrival is near.
	 */
	private static final double BBOX_MIN_SPAN_DEG = 0.01;
	/** ~500 m of slack so an incident just off the traced line is still inside the rectangle. */
	private static final double BBOX_MARGIN_DEG = 0.005;

	/**
	 * Floor applied to {@code currentSpeed} when the segment is reported closed or stopped.
	 *
	 * <p>{@link CairoDriveProviders.FlowSample#delayRatio()} is documented to be used as
	 * {@code freeFlowSeconds / delayRatio()}. A literal zero there is not "very slow", it is
	 * Infinity - a NaN ETA or a divide that propagates into the arrival time. 0.5 m/s still reads
	 * as "effectively blocked" to anything that gates on the ratio, without handing a downstream
	 * caller a value that cannot be divided by.
	 */
	private static final double MIN_CURRENT_SPEED_MPS = 0.5;

	// ------------------------------------------------------------------ persisted budget keys
	//
	// Read and written through SettingsAPI against the GLOBAL preference object directly, rather
	// than through OsmandSettings.registerIntPreference.
	//
	// registerIntPreference looks the tempting option - it is one line and it hands back an
	// already-registered preference on a second call - but it also does
	// `registeredPreferences.put(id, p)` on a plain LinkedHashMap that OsmandSettings shares across
	// the whole app. These counters are claimed on the polling worker, so registering from here is
	// a structural mutation of that map from a background thread while the UI thread is registering
	// its own preferences and while backup/settings-export code is ITERATING it. The failure is a
	// ConcurrentModificationException or a lost entry on a settings screen, arriving nowhere near
	// this file and with nothing in the drive log to connect it back.
	//
	// SettingsAPI writes to exactly the same store an IntPreference.makeGlobal() would - see
	// IntPreference.setValue - so the values are byte-identical if these are ever promoted to real
	// OsmandSettings fields. The only thing given up is the registry entry, which this feature has
	// no use for: nothing displays these and nothing backs them up.

	private static final String PREF_DAY = "tomtom_traffic_request_day";
	private static final String PREF_INCIDENT_COUNT = "tomtom_traffic_incident_request_count";
	private static final String PREF_FLOW_COUNT = "tomtom_traffic_flow_request_count";
	private static final String PREF_LAST_INCIDENT_MS = "tomtom_traffic_last_incident_ms";
	private static final String PREF_LAST_FLOW_MS = "tomtom_traffic_last_flow_ms";

	// ------------------------------------------------------------------ state
	// Touched from the GPS thread (which may be the main thread) and from the worker.

	private static final TomTomTrafficProvider INSTANCE = new TomTomTrafficProvider();

	private static volatile long lastIncidentPoll;
	private static volatile long lastFlowPoll;
	private static volatile boolean inFlight;
	/** Latched on the first HTTP 400, so at most one incident poll is ever lost to the language. */
	private static volatile boolean languageFallback;
	/**
	 * Latched on HTTP 403. A rejected key does not become accepted by being retried, and the
	 * failure is silent from the driver's seat - so polling stops for the life of the process
	 * rather than burning a request every 2.5 minutes to re-learn the same thing.
	 */
	private static volatile boolean keyRejected;
	private static volatile boolean budgetExhaustedLogged;
	private static volatile boolean notServingLogged;
	/** Whether the cadence has been seeded from storage yet. See {@link #seedCadence}. */
	private static volatile boolean cadenceSeeded;

	private TomTomTrafficProvider() {
	}

	/** The instance to hand {@link CairoDriveProviders#register}. */
	@NonNull
	public static TomTomTrafficProvider getInstance() {
		return INSTANCE;
	}

	// ------------------------------------------------------------------ Provider contract

	@NonNull
	@Override
	public String name() {
		return CairoDriveProviders.NAME_TOMTOM;
	}

	/**
	 * Both gates ANDed, and nothing else. No settings read, no network, no allocation - this runs
	 * during arbitration at startup, before most of the app exists.
	 */
	@Override
	public boolean isAvailable(@NonNull OsmandApplication app) {
		// Build flag AND key AND the live preference. The preference is last because it is the
		// only one that can change while driving.
		return BuildConfig.CAIRODRIVE_TOMTOM_TRAFFIC
				&& !Algorithms.isEmpty(BuildConfig.CAIRODRIVE_TOMTOM_KEY)
				&& app.getSettings().TOMTOM_TRAFFIC_ON.get();
	}

	@NonNull
	@Override
	public EnumSet<CairoDriveProviders.Capability> capabilities() {
		return EnumSet.of(CairoDriveProviders.Capability.TRAFFIC_FLOW,
				CairoDriveProviders.Capability.TRAFFIC_INCIDENTS);
	}

	// ------------------------------------------------------------------ entry points

	/**
	 * Called on every GPS fix while navigating. A gauntlet of cheap bail-outs first, so the common
	 * case - feature off, or nothing due - costs a handful of field reads.
	 *
	 * <p>Everything past the bail-outs is arithmetic on a copied list; the settings write and the
	 * two HTTP calls happen on the worker. That differs from {@code GoogleTrafficHelper}, which
	 * claims its budget on the caller's thread, and it differs on purpose: this callback can land
	 * on the main thread, and a SharedPreferences commit there is a disk write on the draw path.
	 */
	public static void onLocationUpdate(@Nullable RoutingHelper helper, @Nullable Location loc) {
		if (helper == null || loc == null || keyRejected) {
			return;
		}
		OsmandApplication app = helper.getApplication();
		if (app == null) {
			return;
		}
		try {
			if (!INSTANCE.isAvailable(app)) {
				return;
			}
			// Re-checked per endpoint rather than once for the provider: winning INCIDENTS does not
			// grant FLOW. A capability this provider lost must cost nothing at all - being ignored
			// on the way out would still spend the transaction.
			boolean serveIncidents = CairoDriveProviders.isServing(
					CairoDriveProviders.NAME_TOMTOM, CairoDriveProviders.Capability.TRAFFIC_INCIDENTS);
			boolean serveFlow = CairoDriveProviders.isServing(
					CairoDriveProviders.NAME_TOMTOM, CairoDriveProviders.Capability.TRAFFIC_FLOW);
			if (!serveIncidents && !serveFlow) {
				if (!notServingLogged) {
					notServingLogged = true;
					// Worth exactly one line. A build where the key is compiled in and TomTom still
					// never polls is otherwise indistinguishable from a network fault in a log.
					CairoDriveLogger.getInstance().log(TRACE_TAG,
							"available but serving neither capability - no polls will be made ("
									+ CairoDriveProviders.describeResolution() + ")");
				}
				return;
			}

			OsmandSettings settings = app.getSettings();
			if (!helper.isFollowingMode()) {
				return;
			}
			ApplicationMode mode = settings.getApplicationMode();
			if (mode == null || !mode.isDerivedRoutingFrom(ApplicationMode.CAR)) {
				return;
			}

			seedCadence(app);

			long now = System.currentTimeMillis();
			if (inFlight) {
				return;
			}
			boolean incidentsDue = serveIncidents && now - lastIncidentPoll >= INCIDENT_INTERVAL_MS;
			// Budget-aware cadence: past BUDGET_THIN_FRACTION this interval doubles, so the feature
			// stretches across a long day instead of stopping dead partway through it.
			long flowInterval = flowIntervalForBudget(usedFlowPoints(app));
			boolean flowDue = serveFlow && now - lastFlowPoll >= flowInterval;
			if (!incidentsDue && !flowDue) {
				return;
			}

			// Connectivity is tested AFTER the cadence check, not with the other cheap gates above.
			// isInternetConnectionAvailable() looks free and is not: it re-queries ConnectivityManager
			// whenever its own 15 s cache is cold, which is a binder round trip on whatever thread the
			// fix arrived on - possibly the main one, whose frame budget is already 46.9 ms. Placing it
			// here means the 149 seconds out of every 150 when nothing is due cost no binder call at
			// all, and the check still runs before a single byte is spent.
			if (!settings.isInternetConnectionAvailable()) {
				return;
			}

			// Copied on THIS thread. getRouteLocations() is a live sublist view over the route that
			// shrinks as the car moves, so handing it to a worker would let the geometry change
			// underneath a request that is already describing it.
			RouteCalculationResult route = helper.getRoute();
			if (route == null) {
				return;
			}
			List<Location> remaining = new ArrayList<>(route.getRouteLocations());
			if (remaining.size() < 2) {
				return;
			}

			synchronized (TomTomTrafficProvider.class) {
				// Re-tested under the lock: two fixes milliseconds apart both pass the checks above,
				// and each would start a worker and claim a separate slice of the day's budget.
				if (inFlight) {
					return;
				}
				incidentsDue = serveIncidents && now - lastIncidentPoll >= INCIDENT_INTERVAL_MS;
				flowDue = serveFlow && now - lastFlowPoll >= flowIntervalForBudget(usedFlowPoints(app));
				if (!incidentsDue && !flowDue) {
					return;
				}
				// Advanced before the work rather than after it, so a failing endpoint retries on
				// the next cadence tick instead of on the next fix.
				if (incidentsDue) {
					lastIncidentPoll = now;
				}
				if (flowDue) {
					lastFlowPoll = now;
				}
				inFlight = true;
			}

			int generation = CairoDriveProviders.currentGeneration();
			double lat = loc.getLatitude();
			double lon = loc.getLongitude();
			boolean pollIncidents = incidentsDue;
			boolean pollFlow = flowDue;
			Thread worker = new Thread(() -> {
				try {
					Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND);
					poll(app, lat, lon, remaining, generation, pollIncidents, pollFlow);
				} catch (Throwable t) {
					CairoDriveLogger.getInstance().log(TRACE_TAG, "poll failed", t);
				} finally {
					// In a finally so a thrown exception cannot wedge the feature off for the rest
					// of the drive.
					inFlight = false;
				}
			}, "tomtom-traffic");
			worker.setPriority(Thread.MIN_PRIORITY);
			worker.start();
		} catch (Throwable t) {
			CairoDriveLogger.getInstance().log(TRACE_TAG, "onLocationUpdate failed", t);
			inFlight = false;
		}
	}

	/**
	 * A freshly recalculated route should be scored soon, but not on the next fix - see
	 * {@link #REROUTE_DEBOUNCE_MS}. Only pulls the deadlines forward, never pushes them back.
	 */
	public static void onNewRoute() {
		long now = System.currentTimeMillis();
		long incidentTarget = now - INCIDENT_INTERVAL_MS + REROUTE_DEBOUNCE_MS;
		if (lastIncidentPoll > incidentTarget) {
			lastIncidentPoll = incidentTarget;
		}
		long flowTarget = now - FLOW_INTERVAL_MS + REROUTE_DEBOUNCE_MS;
		if (lastFlowPoll > flowTarget) {
			lastFlowPoll = flowTarget;
		}
	}

	/**
	 * Navigation stopped. Clears only this provider's cadence state - the published data is owned
	 * by {@link CairoDriveProviders#resetRouteState()}, which the caller is expected to invoke at
	 * the same site, and which is also what orphans any fetch still in flight.
	 *
	 * <p>{@link #keyRejected} deliberately survives: a key the server refused an hour ago is still
	 * refused, and re-arming it here would put the retry loop back.
	 */
	public static void reset() {
		lastIncidentPoll = 0;
		lastFlowPoll = 0;
	}

	// ------------------------------------------------------------------ polling

	private static void poll(@NonNull OsmandApplication app, double lat, double lon,
	                         @NonNull List<Location> remaining, int generation,
	                         boolean pollIncidents, boolean pollFlow) {
		// The corridor is traced once and shared. Both endpoints describe the same stretch of road
		// ahead, and tracing it twice could give them different answers if the walk changed.
		List<Location> corridor = corridorAhead(remaining);
		if (corridor.size() < 2) {
			return;
		}
		if (pollIncidents) {
			fetchIncidents(app, corridor, generation);
		}
		if (pollFlow) {
			fetchFlow(app, lat, lon, corridor, generation);
		}
	}

	/**
	 * The first {@link #CORRIDOR_M} metres of what is left, or all of it when that is shorter.
	 * Returns an empty list when arrival is close enough that no transaction is justified.
	 */
	@NonNull
	private static List<Location> corridorAhead(@NonNull List<Location> remaining) {
		List<Location> corridor = new ArrayList<>();
		double walked = 0;
		Location previous = null;
		for (Location location : remaining) {
			if (location == null) {
				continue;
			}
			if (previous != null) {
				walked += MapUtils.getDistance(previous.getLatitude(), previous.getLongitude(),
						location.getLatitude(), location.getLongitude());
			}
			corridor.add(location);
			previous = location;
			if (walked >= CORRIDOR_M) {
				break;
			}
		}
		return walked >= MIN_REMAINING_M ? corridor : new ArrayList<Location>();
	}

	// ------------------------------------------------------------------ incidents

	private static void fetchIncidents(@NonNull OsmandApplication app,
	                                   @NonNull List<Location> corridor, int generation) {
		double minLat = Double.MAX_VALUE;
		double maxLat = -Double.MAX_VALUE;
		double minLon = Double.MAX_VALUE;
		double maxLon = -Double.MAX_VALUE;
		for (Location location : corridor) {
			minLat = Math.min(minLat, location.getLatitude());
			maxLat = Math.max(maxLat, location.getLatitude());
			minLon = Math.min(minLon, location.getLongitude());
			maxLon = Math.max(maxLon, location.getLongitude());
		}
		minLat -= BBOX_MARGIN_DEG;
		maxLat += BBOX_MARGIN_DEG;
		minLon -= BBOX_MARGIN_DEG;
		maxLon += BBOX_MARGIN_DEG;
		// A route running due north or due east collapses one axis to nothing, and TomTom rejects a
		// degenerate rectangle outright - a failure that would look like an outage rather than a
		// geometry bug.
		if (maxLat - minLat < BBOX_MIN_SPAN_DEG) {
			double centre = (maxLat + minLat) / 2;
			minLat = centre - BBOX_MIN_SPAN_DEG / 2;
			maxLat = centre + BBOX_MIN_SPAN_DEG / 2;
		}
		if (maxLon - minLon < BBOX_MIN_SPAN_DEG) {
			double centre = (maxLon + minLon) / 2;
			minLon = centre - BBOX_MIN_SPAN_DEG / 2;
			maxLon = centre + BBOX_MIN_SPAN_DEG / 2;
		}

		if (!claimIncidentRequest(app)) {
			return;
		}
		String language = languageFallback ? LANGUAGE_FALLBACK : LANGUAGE_PRIMARY;
		// bbox order is minLon,minLat,maxLon,maxLat - longitude first, which is the opposite of
		// every other coordinate pair in this file.
		String url = BASE + INCIDENTS_PATH
				+ "?key=" + encode(BuildConfig.CAIRODRIVE_TOMTOM_KEY)
				+ "&bbox=" + fixed(minLon, 5) + "," + fixed(minLat, 5)
				+ "," + fixed(maxLon, 5) + "," + fixed(maxLat, 5)
				+ "&fields=" + encode(INCIDENT_FIELDS)
				+ "&language=" + encode(language)
				+ "&categoryFilter=" + encode(CATEGORY_FILTER)
				+ "&timeValidityFilter=present";

		long started = System.currentTimeMillis();
		Response response = request(url);
		long elapsed = System.currentTimeMillis() - started;

		String bbox = fixed(minLon, 4) + "," + fixed(minLat, 4)
				+ "," + fixed(maxLon, 4) + "," + fixed(maxLat, 4);
		if (response.code != HttpURLConnection.HTTP_OK) {
			// Note what is NOT logged: the URL. It carries the key in a query parameter, and the
			// redaction in CairoDriveLogger only filters the logcat pump, not direct log() calls.
			CairoDriveLogger.getInstance().log(TRACE_TAG, "incidents http=" + response.code
					+ " ms=" + elapsed + " bbox=" + bbox + " lang=" + language
					+ " budget=" + used(app, PREF_INCIDENT_COUNT) + "/" + INCIDENT_DAILY_CAP
					+ (Algorithms.isEmpty(response.body) ? "" : " body=" + trim(response.body, 200)));
			handleHttpFailure(response.code, language);
			return;
		}

		List<CairoDriveProviders.TrafficIncident> incidents = new ArrayList<>();
		int closures = 0;
		int flooding = 0;
		int laneClosed = 0;
		try {
			JSONArray features = new JSONObject(response.body).optJSONArray("incidents");
			int count = features != null ? Math.min(features.length(), MAX_INCIDENTS) : 0;
			for (int i = 0; i < count; i++) {
				JSONObject feature = features.optJSONObject(i);
				if (feature == null) {
					continue;
				}
				LatLon at = firstCoordinate(feature.optJSONObject("geometry"));
				if (at == null) {
					// Without a position it cannot be placed on the map or matched to the route, and
					// an incident of unknown location is worse than none - it would have to be shown
					// as applying everywhere.
					continue;
				}
				JSONObject properties = feature.optJSONObject("properties");
				int category = properties != null ? properties.optInt("iconCategory", 0) : 0;
				int magnitude = properties != null ? properties.optInt("magnitudeOfDelay", 0) : 0;
				int delay = properties != null ? Math.max(0, properties.optInt("delay", 0)) : 0;
				boolean closure = closureFrom(category, magnitude);
				if (closure) {
					closures++;
				}
				if (category == ICON_FLOODING) {
					flooding++;
				}
				if (category == ICON_LANE_CLOSED) {
					laneClosed++;
				}
				incidents.add(new CairoDriveProviders.TrafficIncident(at, category, closure, delay,
						describe(properties)));
			}
		} catch (Throwable t) {
			CairoDriveLogger.getInstance().log(TRACE_TAG, "incident parse failed", t);
			return;
		}

		CairoDriveProviders.publishIncidents(generation, incidents);
		CairoDriveLogger.getInstance().log(TRACE_TAG, "incidents http=200 ms=" + elapsed
				+ " bbox=" + bbox + " lang=" + language
				+ " n=" + incidents.size() + " closures=" + closures
				+ " laneClosed=" + laneClosed + " flooding=" + flooding
				+ " budget=" + used(app, PREF_INCIDENT_COUNT) + "/" + INCIDENT_DAILY_CAP);
	}

	/**
	 * Whether an incident actually BLOCKS the road, which is the only question that may justify a
	 * reroute.
	 *
	 * <p>Two independent witnesses, ORed, because neither is trustworthy alone here. The icon
	 * category is the natural signal but its numbering is the one thing in this file nobody has
	 * confirmed against a live response (see the vocabulary block above). {@code magnitudeOfDelay}
	 * 4 - "undefined", TomTom's marker for indefinite delays including closures - is derived from a
	 * different field and would survive the icon table being wrong.
	 *
	 * <p>A lane closure is deliberately NOT a closure. It slows a road, it does not remove it, and
	 * treating it as removal is how a router sends a driver ten minutes round a lane cone.
	 */
	private static boolean closureFrom(int category, int magnitude) {
		return category == ICON_ROAD_CLOSED || magnitude == MAGNITUDE_UNDEFINED;
	}

	/**
	 * A short human line: the road number where TomTom gives one, then the first event description.
	 * Already localised by the server via the {@code language} parameter, which is why the text is
	 * carried rather than re-derived from the category.
	 */
	@NonNull
	private static String describe(@Nullable JSONObject properties) {
		if (properties == null) {
			return "";
		}
		StringBuilder text = new StringBuilder();
		JSONArray roads = properties.optJSONArray("roadNumbers");
		if (roads != null && roads.length() > 0) {
			String road = roads.optString(0, "");
			if (!Algorithms.isEmpty(road)) {
				text.append(road).append(": ");
			}
		}
		JSONArray events = properties.optJSONArray("events");
		if (events != null) {
			for (int i = 0; i < events.length(); i++) {
				JSONObject event = events.optJSONObject(i);
				String description = event != null ? event.optString("description", "") : "";
				if (!Algorithms.isEmpty(description)) {
					text.append(description);
					break;
				}
			}
		}
		return trim(text.toString(), MAX_DESCRIPTION_CHARS);
	}

	/**
	 * The first coordinate of an incident's geometry, whatever shape it arrived in.
	 *
	 * <p>GeoJSON nests differently per type - Point is {@code [lon,lat]}, LineString is
	 * {@code [[lon,lat],...]}, MultiLineString one level deeper again - and TomTom picks the type
	 * per incident. Descending until two numbers appear handles all three plus anything a future
	 * API version adds, instead of switching on a {@code type} string this build has to know in
	 * advance.
	 *
	 * <p>The FIRST coordinate rather than a centroid: on a LineString it is where the incident
	 * starts, which is the end the driver reaches first.
	 */
	@Nullable
	private static LatLon firstCoordinate(@Nullable JSONObject geometry) {
		if (geometry == null) {
			return null;
		}
		return firstCoordinate(geometry.optJSONArray("coordinates"), 0);
	}

	@Nullable
	private static LatLon firstCoordinate(@Nullable JSONArray coordinates, int depth) {
		// Bounded so a malformed or self-referential response cannot recurse without end. GeoJSON
		// never nests deeper than MultiPolygon, which is four.
		if (coordinates == null || coordinates.length() == 0 || depth > 5) {
			return null;
		}
		JSONArray nested = coordinates.optJSONArray(0);
		if (nested != null) {
			return firstCoordinate(nested, depth + 1);
		}
		if (coordinates.length() < 2) {
			return null;
		}
		// GeoJSON is longitude first. Reading it as lat/lon puts every Cairo incident in the sea
		// off Somalia, which is at least an obvious failure rather than a subtle one.
		double lon = coordinates.optDouble(0, Double.NaN);
		double lat = coordinates.optDouble(1, Double.NaN);
		if (Double.isNaN(lat) || Double.isNaN(lon)
				|| Math.abs(lat) > 90 || Math.abs(lon) > 180) {
			return null;
		}
		return new LatLon(lat, lon);
	}

	// ------------------------------------------------------------------ flow

	private static void fetchFlow(@NonNull OsmandApplication app, double lat, double lon,
	                              @NonNull List<Location> corridor, int generation) {
		List<LatLon> points = flowSamplePoints(lat, lon, corridor);
		if (points.size() < FLOW_MIN_POINTS) {
			return;
		}
		// Thin the sweep before claiming, not after: claiming the full set and discarding the tail
		// would spend budget on points that are never requested.
		int wanted = Math.min(points.size(), samplePointsForBudget(usedFlowPoints(app)));
		if (wanted < points.size()) {
			points = points.subList(0, wanted);
		}
		int granted = claimFlowRequests(app, points.size());
		if (granted < FLOW_MIN_POINTS) {
			return;
		}
		if (granted < points.size()) {
			// Trim from the far end. The near samples describe the next few minutes, which is the
			// part of the ETA the driver is about to live through.
			points = points.subList(0, granted);
		}

		List<CairoDriveProviders.FlowSample> samples = new ArrayList<>();
		StringBuilder confidences = new StringBuilder();
		StringBuilder ratios = new StringBuilder();
		int low = 0;
		int medium = 0;
		int high = 0;
		int failures = 0;
		int lastCode = 0;
		long started = System.currentTimeMillis();

		for (LatLon point : points) {
			if (keyRejected) {
				// Latched mid-sweep by an earlier point. Every remaining request would be refused
				// identically, so stop rather than spend five more.
				break;
			}
			String url = BASE + FLOW_PATH
					+ "?key=" + encode(BuildConfig.CAIRODRIVE_TOMTOM_KEY)
					+ "&point=" + fixed(point.getLatitude(), 5) + "," + fixed(point.getLongitude(), 5)
					+ "&unit=KMPH";
			Response response = request(url);
			lastCode = response.code;
			if (response.code != HttpURLConnection.HTTP_OK) {
				failures++;
				handleHttpFailure(response.code, null);
				continue;
			}
			CairoDriveProviders.FlowSample sample = parseFlow(point, response.body);
			if (sample == null) {
				failures++;
				continue;
			}
			samples.add(sample);
			if (sample.confidence < 0.3) {
				low++;
			} else if (sample.confidence < 0.7) {
				medium++;
			} else {
				high++;
			}
			if (confidences.length() > 0) {
				confidences.append(',');
				ratios.append(',');
			}
			confidences.append(fixed(sample.confidence, 2));
			ratios.append(fixed(sample.delayRatio(), 2));
		}
		long elapsed = System.currentTimeMillis() - started;

		// Published even when some points failed: a partial sweep still corrects the ETA for the
		// segments it covers, and publishing nothing would let the previous sweep sit until its TTL
		// while fresher data was in hand.
		if (!samples.isEmpty()) {
			CairoDriveProviders.publishFlow(generation, samples);
		}
		CairoDriveLogger.getInstance().log(TRACE_TAG, "flow http="
				+ (failures == 0 ? 200 : lastCode) + " ms=" + elapsed
				+ " pts=" + samples.size() + "/" + points.size() + " failed=" + failures
				+ " budget=" + used(app, PREF_FLOW_COUNT) + "/" + FLOW_DAILY_CAP
				// The distribution is the point of this endpoint: a sweep that is all low-confidence
				// means the route is on roads TomTom has no probes for, and the ETA correction
				// should be ignored rather than averaged in. Buckets AND the raw values, because
				// six numbers cost nothing and the buckets alone hide a bimodal sweep.
				+ " conf lo=" + low + " mid=" + medium + " hi=" + high
				+ " conf=[" + confidences + "] ratio=[" + ratios + "]");
	}

	/**
	 * Evenly spaced points along the corridor, starting at the car itself.
	 *
	 * <p>The first sample is the live fix rather than the first route node, because the node may be
	 * some way behind the car after a long straight - and the segment under the car is the one
	 * whose speed the driver can immediately verify, which makes it the sample worth trusting the
	 * logs about.
	 */
	@NonNull
	private static List<LatLon> flowSamplePoints(double lat, double lon,
	                                             @NonNull List<Location> corridor) {
		List<LatLon> points = new ArrayList<>(FLOW_SAMPLE_POINTS);
		points.add(new LatLon(lat, lon));

		double total = 0;
		for (int i = 1; i < corridor.size(); i++) {
			Location a = corridor.get(i - 1);
			Location b = corridor.get(i);
			total += MapUtils.getDistance(a.getLatitude(), a.getLongitude(),
					b.getLatitude(), b.getLongitude());
		}
		if (total < MIN_REMAINING_M) {
			return points;
		}
		double step = total / FLOW_SAMPLE_POINTS;
		double nextAt = step;
		double walked = 0;
		for (int i = 1; i < corridor.size() && points.size() < FLOW_SAMPLE_POINTS; i++) {
			Location a = corridor.get(i - 1);
			Location b = corridor.get(i);
			walked += MapUtils.getDistance(a.getLatitude(), a.getLongitude(),
					b.getLatitude(), b.getLongitude());
			if (walked >= nextAt) {
				points.add(new LatLon(b.getLatitude(), b.getLongitude()));
				nextAt += step;
			}
		}
		return points;
	}

	@Nullable
	private static CairoDriveProviders.FlowSample parseFlow(@NonNull LatLon at, @NonNull String body) {
		try {
			JSONObject data = new JSONObject(body).optJSONObject("flowSegmentData");
			if (data == null) {
				return null;
			}
			double freeFlowKmh = data.optDouble("freeFlowSpeed", Double.NaN);
			double currentKmh = data.optDouble("currentSpeed", Double.NaN);
			if (Double.isNaN(freeFlowKmh) || freeFlowKmh <= 0 || Double.isNaN(currentKmh)) {
				// Without a free-flow figure delayRatio() would report 1.0 - "no delay" - from data
				// that says nothing at all. Dropping the sample keeps that lie out of the ETA.
				return null;
			}
			boolean closed = data.optBoolean("roadClosure", false);
			double currentMps = Math.max(0, currentKmh) / 3.6;
			double freeFlowMps = freeFlowKmh / 3.6;
			if (closed || currentMps < MIN_CURRENT_SPEED_MPS) {
				currentMps = MIN_CURRENT_SPEED_MPS;
			}
			// An absent confidence is treated as zero, NOT as trusted. Downstream is expected to
			// gate on this field, so failing closed means a segment TomTom said nothing about
			// leaves the offline engine's own estimate alone - the harmless direction.
			double confidence = data.optDouble("confidence", 0);
			if (Double.isNaN(confidence)) {
				confidence = 0;
			}
			confidence = Math.max(0, Math.min(1, confidence));
			return new CairoDriveProviders.FlowSample(at, currentMps, freeFlowMps, confidence);
		} catch (Throwable t) {
			return null;
		}
	}

	// ------------------------------------------------------------------ budget

	/**
	 * The budget accountant, for the single incident request.
	 *
	 * <p>Counters live in persisted settings rather than in memory for one reason: you cannot reset
	 * the bill by force-stopping the app. Same construction as
	 * {@code GoogleTrafficHelper.claimRequestTier}, including the UTC-day integer - a day number
	 * rather than a date string so the comparison is one int and cannot be confused by a locale.
	 *
	 * <p>Called only from the worker, and only one worker runs at a time
	 * ({@link #inFlight}), so the read-modify-write needs no further lock.
	 */
	private static boolean claimIncidentRequest(@NonNull OsmandApplication app) {
		try {
			OsmandSettings settings = app.getSettings();
			SettingsAPI api = settings.getSettingsAPI();
			Object prefs = settings.getPreferences(true);
			int[] spent = rollDay(api, prefs);
			int used = spent[0];
			if (used >= INCIDENT_DAILY_CAP) {
				logBudgetExhausted(used, spent[1]);
				return false;
			}
			// The stamp is written with the claim, not with the response, so a request that times out
			// still counts as a poll attempt. Otherwise a run of failures would leave the stamp cold
			// and a restart would fire immediately - which is the case seedCadence exists to close.
			api.edit(prefs)
					.putInt(PREF_INCIDENT_COUNT, used + 1)
					.putLong(PREF_LAST_INCIDENT_MS, System.currentTimeMillis())
					.commit();
			return true;
		} catch (Throwable t) {
			// A budget that cannot be accounted for is a budget that must not be spent.
			CairoDriveLogger.getInstance().log(TRACE_TAG, "incident budget claim failed", t);
			return false;
		}
	}

	/**
	 * Claims up to {@code wanted} flow points and returns how many were granted, which may be
	 * fewer near the cap.
	 *
	 * <p>Granting a partial sweep rather than refusing outright is deliberate: the near points are
	 * the useful ones, so three of six still describes the next few minutes. The caller enforces
	 * {@link #FLOW_MIN_POINTS} below which a "sweep" is really a single reading dressed up as one.
	 */
	private static int claimFlowRequests(@NonNull OsmandApplication app, int wanted) {
		try {
			OsmandSettings settings = app.getSettings();
			SettingsAPI api = settings.getSettingsAPI();
			Object prefs = settings.getPreferences(true);
			int[] spent = rollDay(api, prefs);
			int used = spent[1];
			int granted = Math.max(0, Math.min(wanted, FLOW_DAILY_CAP - used));
			if (granted < FLOW_MIN_POINTS) {
				logBudgetExhausted(spent[0], used);
				return 0;
			}
			api.edit(prefs)
					.putInt(PREF_FLOW_COUNT, used + granted)
					.putLong(PREF_LAST_FLOW_MS, System.currentTimeMillis())
					.commit();
			return granted;
		} catch (Throwable t) {
			CairoDriveLogger.getInstance().log(TRACE_TAG, "flow budget claim failed", t);
			return 0;
		}
	}

	/**
	 * Carries the poll cadence across a process restart, once per process.
	 *
	 * <h3>Why the daily cap is not enough on its own</h3>
	 *
	 * {@link #lastIncidentPoll} and {@link #lastFlowPoll} start at zero, which reads as "never
	 * polled" and fires a full sweep on the first fix. That is correct for a first launch and wrong
	 * for the case that actually happens on a drive: Android Auto reconnecting, or the app being
	 * killed and relaunched, turns a 2.5 minute cadence into "once per launch" and can spend an
	 * incident request plus six flow points every time the head unit drops the connection. The
	 * persisted daily caps bound that to a day's worth rather than to nothing, but they bound the
	 * disaster, not the waste - the same trap {@code OpenWeatherHazardProvider} documents.
	 *
	 * <p>Seeding rather than checking storage per poll is the point. Once the in-memory values are
	 * primed, the cadence logic is unchanged and {@link #onNewRoute()} can still legitimately pull a
	 * deadline forward after a reroute - which an authoritative persisted interval check would
	 * silently veto, quietly removing the one thing that gets fresh incidents after a route change.
	 *
	 * <p>One preference read per process, on the first fix that gets past the arbitration gate, so a
	 * build that does not serve either capability never performs it.
	 */
	private static void seedCadence(@NonNull OsmandApplication app) {
		if (cadenceSeeded) {
			return;
		}
		cadenceSeeded = true;
		try {
			OsmandSettings settings = app.getSettings();
			SettingsAPI api = settings.getSettingsAPI();
			Object prefs = settings.getPreferences(true);
			long now = System.currentTimeMillis();
			long incidents = api.getLong(prefs, PREF_LAST_INCIDENT_MS, 0);
			long flow = api.getLong(prefs, PREF_LAST_FLOW_MS, 0);
			// A stamp in the future means the clock moved backwards - NTP correcting a head unit that
			// booted with a dead RTC. Ignoring it polls once too often; honouring it would disable the
			// feature for however far the clock jumped.
			if (incidents > 0 && incidents <= now) {
				lastIncidentPoll = incidents;
			}
			if (flow > 0 && flow <= now) {
				lastFlowPoll = flow;
			}
		} catch (Throwable t) {
			// Seeding is an optimisation. Failing it costs one extra sweep, never a wrong answer.
			CairoDriveLogger.getInstance().log(TRACE_TAG, "cadence seed failed", t);
		}
	}

	/**
	 * Zeroes both counters when the UTC day has rolled, and returns what is spent today as
	 * {@code {incidentRequests, flowPoints}}.
	 *
	 * <p>Returns the figures rather than letting the caller re-read them so a claim decision and the
	 * log line that explains it are made from ONE read of the store. Two reads either side of a day
	 * boundary would print a cap breach next to a zeroed counter.
	 *
	 * <p>The global preference file is a plain UTC day number, not a date string: no calendar, no
	 * time zone, and one integer comparison that no locale can reinterpret.
	 */
	@NonNull
	private static int[] rollDay(@NonNull SettingsAPI api, @NonNull Object prefs) {
		int today = (int) (System.currentTimeMillis() / 86_400_000L);
		if (api.getInt(prefs, PREF_DAY, 0) != today) {
			api.edit(prefs)
					.putInt(PREF_DAY, today)
					.putInt(PREF_INCIDENT_COUNT, 0)
					.putInt(PREF_FLOW_COUNT, 0)
					.commit();
			budgetExhaustedLogged = false;
			return new int[] {0, 0};
		}
		return new int[] {api.getInt(prefs, PREF_INCIDENT_COUNT, 0),
				api.getInt(prefs, PREF_FLOW_COUNT, 0)};
	}

	private static void logBudgetExhausted(int incidentsUsed, int flowPointsUsed) {
		if (budgetExhaustedLogged) {
			return;
		}
		budgetExhaustedLogged = true;
		CairoDriveLogger.getInstance().log(TRACE_TAG, "daily budget spent (incidents="
				+ incidentsUsed + "/" + INCIDENT_DAILY_CAP
				+ " flowPoints=" + flowPointsUsed + "/" + FLOW_DAILY_CAP
				+ ") - no further requests until the UTC day rolls");
	}

	/**
	 * A counter's value for the log line only. Never used to make a spending decision - those go
	 * through {@link #rollDay} so the day roll cannot be missed.
	 */
	private static int used(@NonNull OsmandApplication app, @NonNull String key) {
		try {
			OsmandSettings settings = app.getSettings();
			return settings.getSettingsAPI().getInt(settings.getPreferences(true), key, 0);
		} catch (Throwable t) {
			return -1;
		}
	}

	// ------------------------------------------------------------------ HTTP

	/** Status code plus body, so one call site can log both without a second read of the stream. */
	private static final class Response {
		final int code;
		@NonNull
		final String body;

		Response(int code, @NonNull String body) {
			this.code = code;
			this.body = body;
		}
	}

	/**
	 * One GET. Never throws: every failure becomes a {@link Response} with a non-OK code, because
	 * a traffic lookup is not allowed to be the thing that interrupts navigation.
	 */
	@NonNull
	private static Response request(@NonNull String url) {
		HttpURLConnection connection = null;
		try {
			connection = NetworkUtils.getHttpURLConnection(url);
			connection.setRequestMethod("GET");
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setRequestProperty("Accept", "application/json");
			int code = connection.getResponseCode();
			String body = read(code == HttpURLConnection.HTTP_OK
					? connection.getInputStream() : connection.getErrorStream());
			return new Response(code, body);
		} catch (Throwable t) {
			// 0, not an HTTP code: a timeout in a tunnel is not a server refusal and must not latch
			// anything off. It is simply the next poll's problem.
			return new Response(0, "");
		} finally {
			if (connection != null) {
				try {
					connection.disconnect();
				} catch (Throwable ignored) {
				}
			}
		}
	}

	/**
	 * Turns the two failures that will not fix themselves into latched state.
	 *
	 * @param language the language sent, or null when the request had none - a 400 is only read as
	 *                 a language rejection for a request that carried one
	 */
	private static void handleHttpFailure(int code, @Nullable String language) {
		if (code == HttpURLConnection.HTTP_FORBIDDEN) {
			if (!keyRejected) {
				keyRejected = true;
				CairoDriveLogger.getInstance().log(TRACE_TAG,
						"HTTP 403 - the TomTom key was refused (missing, wrong, or the endpoint is"
								+ " not enabled on the account). Polling is now OFF for this process;"
								+ " a rejected key is not fixed by retrying it every 2.5 minutes");
			}
			return;
		}
		if (code == HttpURLConnection.HTTP_BAD_REQUEST && language != null && !languageFallback) {
			languageFallback = true;
			CairoDriveLogger.getInstance().log(TRACE_TAG, "HTTP 400 with language=" + language
					+ " - falling back to " + LANGUAGE_FALLBACK + " from the next poll."
					+ " Not retried immediately: that would spend a second transaction on the same"
					+ " tick to recover one poll, once per process");
		}
	}

	@NonNull
	private static String read(@Nullable InputStream stream) {
		if (stream == null) {
			return "";
		}
		try (InputStream in = stream) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[4096];
			int n;
			while ((n = in.read(chunk)) > 0) {
				buffer.write(chunk, 0, n);
			}
			return buffer.toString("UTF-8");
		} catch (Throwable t) {
			return "";
		}
	}

	// ------------------------------------------------------------------ formatting

	/**
	 * {@link Locale#US} explicitly. The device runs in Arabic, and the default locale would format
	 * a coordinate with an Arabic-Indic decimal separator - which TomTom answers with a 400 that
	 * looks exactly like a malformed bounding box.
	 */
	@NonNull
	private static String fixed(double value, int decimals) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return "0";
		}
		return String.format(Locale.US, "%." + decimals + "f", value);
	}

	@NonNull
	private static String encode(@Nullable String value) {
		if (value == null) {
			return "";
		}
		try {
			return URLEncoder.encode(value, "UTF-8");
		} catch (Throwable t) {
			return "";
		}
	}

	@NonNull
	private static String trim(@NonNull String value, int max) {
		return value.length() <= max ? value : value.substring(0, max);
	}
}
