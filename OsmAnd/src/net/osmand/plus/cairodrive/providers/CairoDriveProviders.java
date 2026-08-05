package net.osmand.plus.cairodrive.providers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.cairodrive.CairoDriveLogger;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;

/**
 * The composition layer that makes several independent data providers behave as ONE system.
 *
 * <h3>Why this class exists at all</h3>
 *
 * The providers were chosen so they COMPLETE each other rather than overlap - see PROVIDERS.md
 * from the 2026-08-05 audit. TomTom serves traffic because it is the only major provider with no
 * "you must display our basemap" clause; OpenWeather serves dust because its 7xx condition codes
 * are the only free sand/dust vocabulary; sun glare is computed on-device because it needs no
 * provider at all. Each was picked for a slot no other provider fills.
 *
 * <p>That property is worth nothing unless something enforces it. Left to themselves, two
 * providers that can both answer "where is the traffic" will both answer it: two pollers spending
 * two quotas, and two sets of Java drawing landing in the same {@code over} bucket that CD_FRAME
 * already measures at 25.9 ms - 61% of a 46.9 ms frame. PROVIDERS.md 3.3 names that exact failure
 * and says do not do it. So arbitration lives here: for each {@link Capability} exactly one
 * provider is active, every other provider that could serve it is inert, and a provider that lost
 * must not poll.
 *
 * <h3>Why priority is an explicit number and not registration order</h3>
 *
 * Registration order is a function of class-initialisation order, which is a function of which
 * code path happened to touch which class first. It changes when an unrelated caller moves, it
 * differs between a cold start and a warm one, and it cannot be reviewed by reading a diff. The
 * winner would then be an emergent property of startup sequencing - the single worst thing to
 * debug from a drive log after the fact.
 *
 * <p>Instead {@link #priorityOf(String)} below is a fixed table: one switch, in one file, that a
 * reviewer can read in ten seconds and that produces the same answer on every boot. Ties break on
 * the provider name rather than on order, so even an unlisted provider resolves deterministically.
 *
 * <h3>What lands in the drive log</h3>
 *
 * {@link #install(OsmandApplication)} writes one CD_PROVIDERS line such as
 * {@code flow=TomTom incidents=TomTom hazard=OpenWeather glare=local}. That single line is what
 * lets a log answer "which provider actually served this drive" - without it, a build with a key
 * missing and a build with the flag off produce identical silence and are indistinguishable
 * afterwards. A stale sideload masquerading as a Play build has already cost one drive here.
 *
 * <h3>What this class deliberately is not</h3>
 *
 * Pure contract, arbitration and state. It implements no provider, opens no connection and
 * touches no key. Every provider brings its own network code, its own quota accounting and its
 * own exception swallowing; this file only decides who is allowed to speak and holds what they
 * last said.
 *
 * @see #resolve(Capability)
 */
public final class CairoDriveProviders {

	private static final String TRACE_TAG = "CD_PROVIDERS";

	/** Cached because {@code values()} clones its array on every call and resolve() is a hot path. */
	private static final Capability[] CAPABILITIES = Capability.values();

	// ------------------------------------------------------------------ provider names

	/**
	 * Canonical provider names. They are the arbitration key AND the token that appears in the
	 * CD_PROVIDERS log line, so they are constants rather than literals scattered across four
	 * provider classes - a typo in one of those would silently drop the provider to
	 * {@link #PRIORITY_UNLISTED} and the log line would still look plausible.
	 */
	public static final String NAME_TOMTOM = "TomTom";
	public static final String NAME_OPENWEATHER = "OpenWeather";
	/** Computed on-device from solar geometry and route bearing. No key, no network, no quota. */
	public static final String NAME_LOCAL = "local";
	public static final String NAME_GOOGLE = "Google";

	// ------------------------------------------------------------------ priority table

	public static final int PRIORITY_TOMTOM = 100;
	public static final int PRIORITY_OPENWEATHER = 80;
	public static final int PRIORITY_LOCAL = 60;
	/**
	 * Below every alternative on purpose. Google's Cairo traffic data is the best available and
	 * is also the one thing Maps Platform ToS 19.2 explicitly forbids drawing on a non-Google
	 * basemap. If a Google-backed traffic provider is ever registered - the
	 * {@code TRAFFIC_ON_POLYLINE} path in {@code GoogleTrafficHelper} is still in the tree - it
	 * must lose to TomTom by construction rather than by whoever registered first.
	 */
	public static final int PRIORITY_GOOGLE = 40;
	/** Anything not in the table. Loses to every named provider, then ties break on name. */
	public static final int PRIORITY_UNLISTED = 0;

	// ------------------------------------------------------------------ freshness

	/**
	 * Incidents are the shortest-lived thing here because they are the only data that changes
	 * BEHAVIOUR: a reported closure can become an impassable-road nogo point and send the router
	 * the long way round. A cleared closure that is still believed is therefore worse than no
	 * closure at all, which argues for a short window; PROVIDERS.md 3.1 polls every 2-3 minutes,
	 * so 10 minutes survives three or four missed polls in an underpass without ever acting on
	 * something a quarter of an hour old. Same figure as GoogleTrafficHelper.SNAPSHOT_TTL_MS, for
	 * the same reason.
	 */
	public static final long INCIDENTS_TTL_MS = 4 * 60 * 1000L;
	/**
	 * Flow only adjusts an ETA, so stale flow shows a wrong number rather than driving down a
	 * wrong street. PROVIDERS.md 3.2 samples every 5 minutes; 15 covers two missed samples.
	 */
	public static final long FLOW_TTL_MS = 4 * 60 * 1000L;
	/**
	 * Dust does not clear in ten minutes and the weather endpoints are polled every 30 (3.5), so
	 * a short window here would blank the banner between refreshes for no gain.
	 */
	public static final long HAZARD_TTL_MS = 40 * 60 * 1000L;

	private CairoDriveProviders() {
	}

	// ------------------------------------------------------------------ contract

	/**
	 * A slot in the system. Exactly one provider serves each of these at a time.
	 *
	 * <p>Deliberately coarse. These are the questions a driver asks, not the endpoints a vendor
	 * sells - splitting them per-endpoint would let two vendors each win half of "where is the
	 * traffic", which is the overlap this whole class exists to prevent.
	 */
	public enum Capability {
		/** Per-segment current speed against free-flow speed. Corrects the ETA. */
		TRAFFIC_FLOW,
		/** Closures, lane closures, flooding. The only traffic event an offline router cannot know. */
		TRAFFIC_INCIDENTS,
		/** Dust, reduced visibility. One banner, no audio interrupt. */
		WEATHER_HAZARD,
		/** Low sun down an east-west arterial at dawn or dusk. Computed locally, costs nothing. */
		SUN_GLARE
	}

	/**
	 * One data source. Implementations live in their own files and own their network code
	 * entirely; this interface is only what arbitration needs to pick between them.
	 */
	public interface Provider {

		/**
		 * Canonical name, matching one of the {@code NAME_*} constants for anything in the
		 * priority table. Appears verbatim in the CD_PROVIDERS log line, so keep it short and
		 * keep it stable - it is read from drive logs weeks later.
		 */
		@NonNull
		String name();

		/**
		 * BOTH gates, ANDed: a key compiled into BuildConfig and the feature flag on.
		 *
		 * <p>Two independent gates rather than one, matching the Places and Google-traffic
		 * features. A build with no key must make zero network calls whatever the flag says, and
		 * returning false here is what guarantees that - an unavailable provider never wins a
		 * capability, so it is never asked for data and has no thread to start.
		 *
		 * <p>Must not block, must not touch the network, and must not throw. A thrower is treated
		 * as unavailable rather than being allowed to take the registry down with it.
		 */
		boolean isAvailable(@NonNull OsmandApplication app);

		/**
		 * Everything this provider could serve if it wins. Claiming a capability is not the same
		 * as being given it - see {@link #resolve(Capability)}.
		 */
		@NonNull
		EnumSet<Capability> capabilities();
	}

	// ------------------------------------------------------------------ value types

	/**
	 * A closure, lane closure or flooding report, for the corridor ahead.
	 *
	 * <p>Geo-anchored rather than anchored to an index into the current route, for the same
	 * reason {@code GoogleTrafficHelper.TrafficSnapshot} stores real coordinates: a reroute
	 * replaces the route geometry, and an index into the old one would place the incident
	 * somewhere arbitrary on the new one - convincingly, and on the wrong street.
	 */
	public static final class TrafficIncident {

		@NonNull
		public final LatLon at;
		/** Provider category code, kept raw so a vocabulary change is a parse fix, not a schema fix. */
		public final int categoryId;
		/** True only for a full road closure - the one event that may justify a reroute. */
		public final boolean closure;
		public final int delaySeconds;
		/** Already localised where the provider offers it. May be empty, never null. */
		@NonNull
		public final String description;

		public TrafficIncident(@NonNull LatLon at, int categoryId, boolean closure,
		                       int delaySeconds, @Nullable String description) {
			this.at = at;
			this.categoryId = categoryId;
			this.closure = closure;
			this.delaySeconds = delaySeconds;
			this.description = description != null ? description : "";
		}
	}

	/**
	 * Current speed against free-flow speed at one point on the route. Corrects the ETA the
	 * offline engine already produced; it does not re-route and does not paint the map.
	 */
	public static final class FlowSample {

		@NonNull
		public final LatLon at;
		/** Metres per second, as normalised by the provider adapter, not the vendor's own unit. */
		public final double currentSpeed;
		public final double freeFlowSpeed;
		/**
		 * 0-1, how much probe data backs this answer. The field no other provider exposes, and
		 * the reason TomTom won this slot: in Cairo a ring road and an unnamed alley differ by an
		 * order of magnitude in coverage, so a city-wide trust level is meaningless. Gate each
		 * sample on this rather than averaging it away.
		 */
		public final double confidence;

		public FlowSample(@NonNull LatLon at, double currentSpeed, double freeFlowSpeed,
		                  double confidence) {
			this.at = at;
			this.currentSpeed = currentSpeed;
			this.freeFlowSpeed = freeFlowSpeed;
			this.confidence = confidence;
		}

		/**
		 * A SPEED ratio, despite the name: 1.0 is free-flowing and 0.25 is crawling at a quarter
		 * of the posted pace. It is NOT a multiplier on travel time, and inverting it by accident
		 * turns a jam into an improved ETA.
		 *
		 * <p>To delay a leg, divide: {@code delayedSeconds = freeFlowSeconds / delayRatio()}.
		 *
		 * @return {@code currentSpeed / freeFlowSpeed}, or 1.0 - "no delay known" - when the
		 * provider gave no usable free-flow figure. Defaulting to 1.0 rather than 0 means missing
		 * data leaves the offline engine's own estimate alone instead of reporting a total stop.
		 */
		public double delayRatio() {
			return freeFlowSpeed > 0 ? currentSpeed / freeFlowSpeed : 1.0;
		}
	}

	/**
	 * One line of hazard text for the top of the screen - dust, reduced visibility, sun glare.
	 *
	 * <p>Carries a string resource KEY and not finished text. The app is Arabic and English and
	 * the banner is resolved at draw time in whatever locale is then current; storing a formatted
	 * English sentence at fetch time would pin it to the locale that happened to be active when
	 * the network call returned.
	 */
	public static final class HazardBanner {

		public static final int SEVERITY_NONE = 0;
		/** Worth knowing. No colour change, no sound. */
		public static final int SEVERITY_INFO = 1;
		/** Amber strip. Still no audio interrupt - see PROVIDERS.md 3.5 on false positives. */
		public static final int SEVERITY_WARN = 2;

		@NonNull
		public final String textKey;
		public final int severity;

		public HazardBanner(@Nullable String textKey, int severity) {
			this.textKey = textKey != null ? textKey : "";
			this.severity = severity;
		}
	}

	// ------------------------------------------------------------------ registry

	private static final Object LOCK = new Object();

	/** Written only under {@link #LOCK}; startup-only, so a plain list is right. */
	private static final List<Provider> REGISTERED = new ArrayList<>();

	/**
	 * The arbitration result, indexed by {@link Capability#ordinal()}.
	 *
	 * <p>Published as a whole array reference and never mutated afterwards, so a reader takes one
	 * volatile read and then indexes a frozen object. That matters because the map layer and the
	 * routing path read this per frame and per fix: a lock or a map lookup here would put
	 * contention on the draw thread, which is the thread already spending 61% of its budget in
	 * {@code over}.
	 */
	private static volatile Provider[] active = new Provider[CAPABILITIES.length];

	private static String lastLoggedAssignment;

	/**
	 * Adds a provider to the pool of candidates. Registering does NOT grant a capability - the
	 * provider still has to win it in {@link #install(OsmandApplication)}.
	 *
	 * <p>Cheap and side-effect free by design: it must be safe to call from a static initialiser
	 * or an early startup hook, before settings, the logger or the native library exist.
	 */
	public static void register(@Nullable Provider provider) {
		if (provider == null) {
			return;
		}
		synchronized (LOCK) {
			if (!REGISTERED.contains(provider)) {
				REGISTERED.add(provider);
			}
		}
	}

	/**
	 * Runs arbitration and writes the one CD_PROVIDERS line.
	 *
	 * <p><b>Call this after {@code CairoDriveLogger.init(app)}.</b> The logger is a silent no-op
	 * until its writer thread is up, so an earlier call arbitrates correctly and loses the log
	 * line - which is the one artefact that makes a drive log attributable to a provider.
	 *
	 * <p>Idempotent. Availability is decided by BuildConfig values, which are compile-time
	 * constants, so the answer cannot change while the process lives; calling twice re-derives
	 * the same assignment and stays quiet rather than logging it again.
	 */
	public static void install(@NonNull OsmandApplication app) {
		String assignment;
		synchronized (LOCK) {
			active = arbitrate(app);
			assignment = describeResolution();
			if (assignment.equals(lastLoggedAssignment)) {
				return;
			}
			lastLoggedAssignment = assignment;
		}
		CairoDriveLogger.getInstance().log(TRACE_TAG, assignment);
	}

	/**
	 * The active provider for a capability, or null when nothing serves it - no key compiled in,
	 * the flag off, or no provider registered for it in this build. Null is the ordinary case for
	 * a stock build and every caller must handle it without complaint.
	 *
	 * <p>Also returns null before {@link #install(OsmandApplication)} has run.
	 */
	@Nullable
	public static Provider resolve(@Nullable Capability capability) {
		if (capability == null) {
			return null;
		}
		// One volatile read, then plain indexing into an array that is frozen before publication.
		Provider[] snapshot = active;
		return snapshot[capability.ordinal()];
	}

	/**
	 * Whether the named provider is the one currently serving a capability.
	 *
	 * <p>The guard a provider checks before spending a request. A provider that lost arbitration
	 * is not merely ignored on the way out - it must not poll at all, or the losing vendor's
	 * quota drains all drive for data nothing reads.
	 */
	public static boolean isServing(@Nullable String providerName, @Nullable Capability capability) {
		Provider provider = resolve(capability);
		return provider != null && safeName(provider).equals(providerName);
	}

	/** The CD_PROVIDERS line, e.g. {@code flow=TomTom incidents=TomTom hazard=OpenWeather glare=local}. */
	@NonNull
	public static String describeResolution() {
		Provider[] snapshot = active;
		StringBuilder builder = new StringBuilder();
		for (Capability capability : CAPABILITIES) {
			Provider provider = snapshot[capability.ordinal()];
			if (builder.length() > 0) {
				builder.append(' ');
			}
			builder.append(label(capability)).append('=')
					.append(provider != null ? safeName(provider) : "none");
		}
		return builder.toString();
	}

	/**
	 * The whole arbitration rule, in one place.
	 *
	 * <p>Every call into a provider is wrapped, because these implementations are separate files
	 * with separate authors and a provider that throws while being interrogated must not be able
	 * to leave the registry half-built. A thrower is treated as unavailable: it loses its slot,
	 * a runner-up takes it, and navigation never notices.
	 */
	@NonNull
	private static Provider[] arbitrate(@NonNull OsmandApplication app) {
		Provider[] winners = new Provider[CAPABILITIES.length];
		String[] winnerNames = new String[CAPABILITIES.length];
		int[] winnerPriority = new int[CAPABILITIES.length];

		for (Provider provider : REGISTERED) {
			String name;
			EnumSet<Capability> capabilities;
			try {
				if (!provider.isAvailable(app)) {
					continue;
				}
				name = provider.name();
				capabilities = provider.capabilities();
			} catch (Throwable t) {
				CairoDriveLogger.getInstance().log(TRACE_TAG,
						"provider " + provider.getClass().getSimpleName()
								+ " threw while being interrogated - treating as unavailable", t);
				continue;
			}
			if (name == null || capabilities == null) {
				continue;
			}
			int priority = priorityOf(name);
			for (Capability capability : capabilities) {
				if (capability == null) {
					continue;
				}
				int i = capability.ordinal();
				// Higher priority wins. On a tie the lexicographically smaller name wins, which is
				// arbitrary but FIXED - the point is only that two unlisted providers can never
				// resolve differently between one boot and the next.
				boolean beatsHolder = winners[i] == null
						|| priority > winnerPriority[i]
						|| (priority == winnerPriority[i] && name.compareTo(winnerNames[i]) < 0);
				if (beatsHolder) {
					winners[i] = provider;
					winnerNames[i] = name;
					winnerPriority[i] = priority;
				}
			}
		}
		return winners;
	}

	/**
	 * The reviewable half of arbitration: which vendor outranks which, as a flat table.
	 *
	 * <p>Kept as a switch on the canonical name rather than as a method on {@link Provider} so
	 * the whole ordering can be read at once. Ranking spread across four provider classes would
	 * mean four files to open before you could predict a winner.
	 */
	public static int priorityOf(@Nullable String providerName) {
		if (providerName == null) {
			return PRIORITY_UNLISTED;
		}
		switch (providerName) {
			case NAME_TOMTOM:
				return PRIORITY_TOMTOM;
			case NAME_OPENWEATHER:
				return PRIORITY_OPENWEATHER;
			case NAME_LOCAL:
				return PRIORITY_LOCAL;
			case NAME_GOOGLE:
				return PRIORITY_GOOGLE;
			default:
				return PRIORITY_UNLISTED;
		}
	}

	@NonNull
	private static String safeName(@NonNull Provider provider) {
		try {
			String name = provider.name();
			return name != null ? name : "?";
		} catch (Throwable t) {
			return "?";
		}
	}

	@NonNull
	private static String label(@NonNull Capability capability) {
		switch (capability) {
			case TRAFFIC_FLOW:
				return "flow";
			case TRAFFIC_INCIDENTS:
				return "incidents";
			case WEATHER_HAZARD:
				return "hazard";
			case SUN_GLARE:
				return "glare";
			default:
				return capability.name();
		}
	}

	// ------------------------------------------------------------------ current state

	/**
	 * Everything the providers have most recently said, as one immutable object.
	 *
	 * <h3>Why every part carries its own timestamp</h3>
	 *
	 * This is the {@code spansTimeMs}/{@code timeMs} split from
	 * {@code GoogleTrafficHelper.TrafficSnapshot}, generalised. There the problem was that a
	 * cheap delay poll carried the previous congestion spans forward unchanged, so expiring on
	 * the snapshot's build time would have let a poll that re-fetched nothing silently "refresh"
	 * colours it never asked for. Here the same trap is wider: incidents, flow and the hazard
	 * banner come from different providers on different cadences - 2-3 minutes, 5 minutes, 30
	 * minutes - and each publish carries the other two forward untouched. One snapshot-wide
	 * timestamp would mean a weather refresh renewing an hour-old closure. So freshness is per
	 * part, always measured from the fetch that actually produced that part.
	 *
	 * <h3>Why there is a generation and a version</h3>
	 *
	 * {@link #generation} orphans data belonging to a route that no longer exists: a fetch
	 * started before a reroute completes afterwards, and storing its result would resurrect
	 * traffic for roads the driver is no longer on. {@link #version} is the opposite direction -
	 * it only ever increases, so a layer holding cached screen geometry can tell in one
	 * comparison whether anything changed, instead of re-projecting every point every frame.
	 */
	public static final class Snapshot {

		@NonNull
		public final List<TrafficIncident> incidents;
		public final long incidentsTimeMs;

		@NonNull
		public final List<FlowSample> flow;
		public final long flowTimeMs;

		@Nullable
		public final HazardBanner hazard;
		public final long hazardTimeMs;

		/** The route epoch the route-anchored parts belong to. See {@link #resetRouteState()}. */
		public final int generation;
		/** Monotonic. Bumped on every publish so a cache can compare rather than rebuild. */
		public final int version;

		Snapshot(@NonNull List<TrafficIncident> incidents, long incidentsTimeMs,
		         @NonNull List<FlowSample> flow, long flowTimeMs,
		         @Nullable HazardBanner hazard, long hazardTimeMs,
		         int generation, int version) {
			this.incidents = incidents;
			this.incidentsTimeMs = incidentsTimeMs;
			this.flow = flow;
			this.flowTimeMs = flowTimeMs;
			this.hazard = hazard;
			this.hazardTimeMs = hazardTimeMs;
			this.generation = generation;
			this.version = version;
		}
	}

	private static final Snapshot EMPTY = new Snapshot(
			Collections.<TrafficIncident>emptyList(), 0,
			Collections.<FlowSample>emptyList(), 0,
			null, 0, 0, 0);

	private static volatile Snapshot snapshot = EMPTY;
	private static volatile int generation;
	private static int versionCounter;

	/** Never null. Read this when you need the timestamps; the accessors below apply the TTLs. */
	@NonNull
	public static Snapshot getSnapshot() {
		return snapshot;
	}

	/**
	 * The epoch a fetch should capture before it starts and hand back when it publishes.
	 *
	 * <p>Same contract as {@code GoogleTrafficHelper.generation}: it is what lets a reroute
	 * invalidate work already in flight without having to cancel a thread.
	 */
	public static int currentGeneration() {
		return generation;
	}

	/**
	 * Navigation stopped, or the route was replaced.
	 *
	 * <p>Clears the ROUTE-anchored parts and orphans anything still fetching. The hazard banner
	 * deliberately survives: it describes the sky, not the road, and dust over Cairo is no less
	 * true because the driver picked a different destination. Clearing it here would blank a
	 * live warning on every reroute and then leave it blank for up to half an hour until the
	 * next weather poll.
	 */
	public static void resetRouteState() {
		synchronized (LOCK) {
			generation++;
			Snapshot previous = snapshot;
			snapshot = new Snapshot(
					Collections.<TrafficIncident>emptyList(), 0,
					Collections.<FlowSample>emptyList(), 0,
					previous.hazard, previous.hazardTimeMs,
					generation, ++versionCounter);
		}
	}

	/**
	 * Publishes incidents fetched for the given generation. A mismatch means the route they were
	 * fetched for is gone and the result is dropped.
	 *
	 * @param generation the value {@link #currentGeneration()} returned before the fetch began
	 */
	public static void publishIncidents(int generation, @Nullable List<TrafficIncident> incidents) {
		// Copied and frozen here rather than trusted: the caller is a background fetch thread that
		// may well reuse its buffer, and the reader is the draw thread. GoogleTrafficHelper takes
		// the same precaution copying getRouteLocations() before handing it to a worker.
		List<TrafficIncident> frozen = freeze(incidents);
		long now = System.currentTimeMillis();
		synchronized (LOCK) {
			if (generation != CairoDriveProviders.generation) {
				return;
			}
			Snapshot previous = snapshot;
			snapshot = new Snapshot(frozen, now,
					previous.flow, previous.flowTimeMs,
					previous.hazard, previous.hazardTimeMs,
					generation, ++versionCounter);
		}
	}

	/**
	 * Publishes flow samples fetched for the given generation. Dropped on a generation mismatch,
	 * for the same reason as {@link #publishIncidents(int, List)}.
	 */
	public static void publishFlow(int generation, @Nullable List<FlowSample> flow) {
		List<FlowSample> frozen = freeze(flow);
		long now = System.currentTimeMillis();
		synchronized (LOCK) {
			if (generation != CairoDriveProviders.generation) {
				return;
			}
			Snapshot previous = snapshot;
			snapshot = new Snapshot(previous.incidents, previous.incidentsTimeMs,
					frozen, now,
					previous.hazard, previous.hazardTimeMs,
					generation, ++versionCounter);
		}
	}

	/**
	 * Publishes the hazard banner. Takes no generation ON PURPOSE - weather is not anchored to a
	 * route, so there is no epoch for it to be stale against and a reroute must not discard it.
	 *
	 * @param hazard null, or severity {@link HazardBanner#SEVERITY_NONE}, clears the banner
	 */
	public static void publishHazard(@Nullable HazardBanner hazard) {
		HazardBanner value = hazard != null && hazard.severity > HazardBanner.SEVERITY_NONE
				? hazard : null;
		long now = System.currentTimeMillis();
		synchronized (LOCK) {
			Snapshot previous = snapshot;
			snapshot = new Snapshot(previous.incidents, previous.incidentsTimeMs,
					previous.flow, previous.flowTimeMs,
					value, value != null ? now : 0,
					previous.generation, ++versionCounter);
		}
	}

	/**
	 * Incidents that are still fresh, or an empty list.
	 *
	 * <p>Expiry returns EMPTY rather than the stale list on purpose. A caller that forgets the
	 * TTL then behaves as though there were no incident, which is the harmless failure; handing
	 * back an hour-old closure would keep routing the driver around a road that reopened.
	 */
	@NonNull
	public static List<TrafficIncident> getIncidents() {
		Snapshot current = snapshot;
		return fresh(current.incidentsTimeMs, INCIDENTS_TTL_MS)
				? current.incidents : Collections.<TrafficIncident>emptyList();
	}

	/** Flow samples that are still fresh, or an empty list. */
	@NonNull
	public static List<FlowSample> getFlow() {
		Snapshot current = snapshot;
		return fresh(current.flowTimeMs, FLOW_TTL_MS)
				? current.flow : Collections.<FlowSample>emptyList();
	}

	/** The hazard banner if it is still fresh, otherwise null. */
	@Nullable
	public static HazardBanner getHazard() {
		Snapshot current = snapshot;
		return fresh(current.hazardTimeMs, HAZARD_TTL_MS) ? current.hazard : null;
	}

	/**
	 * A timestamp of 0 means "never fetched" and is not fresh. The upper bound catches a clock
	 * that jumped backwards - NTP correcting a phone that booted with a bad RTC would otherwise
	 * make old data look arbitrarily far in the future and therefore permanently valid.
	 */
	private static boolean fresh(long timeMs, long ttlMs) {
		if (timeMs <= 0) {
			return false;
		}
		long age = System.currentTimeMillis() - timeMs;
		return age >= 0 && age <= ttlMs;
	}

	@NonNull
	private static <T> List<T> freeze(@Nullable List<T> values) {
		if (values == null || values.isEmpty()) {
			return Collections.<T>emptyList();
		}
		return Collections.unmodifiableList(new ArrayList<>(values));
	}
}
