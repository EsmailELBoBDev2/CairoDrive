package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.plugins.PluginsHelper;
import net.osmand.plus.plugins.osmedit.OsmEditingPlugin;
import net.osmand.plus.plugins.osmedit.data.OsmNotesPoint;
import net.osmand.plus.plugins.osmedit.helpers.OsmBugsRemoteUtil;
import net.osmand.plus.plugins.osmedit.data.OsmPoint.Action;
import net.osmand.util.MapUtils;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OSM write-back for the one signal this fork generates and cannot otherwise act on.
 *
 * <h3>Why this exists at all</h3>
 *
 * CLAUDE.md records the CD_NARROW finding: across Cairo, roughly 16.6% of ways carry an alley NAME
 * (حارة, زقاق, درب, ممر, عطفة) while only about 2.5% carry a machine-readable narrowness TAG. The
 * router reads tags; it cannot read names. So the app can see, from the name, that a way is almost
 * certainly a two-metre alley, and can do nothing with that knowledge - the conclusion in the notes
 * is that only a custom Egypt {@code .obf} could act on it.
 *
 * <p>There is a second way to act on it, and it is the one that compounds: put the observation back
 * into OSM. A note at that location saying "this is named as an alley and has no width or lanes
 * tag" is exactly the kind of survey lead a local mapper can resolve in minutes, and once it is
 * tagged, every OsmAnd user's router benefits - including this one, on the next map update. The
 * fork's own data problem is upstream of the fork.
 *
 * <h3>Nothing is uploaded without being asked</h3>
 *
 * This is an outward-facing write to a public database under the owner's own OSM identity, so it is
 * off by default, gated on a preference the driver sets, and it NEVER fires from the driving path.
 * Candidates accumulate in memory during a drive and go nowhere until {@link #uploadPending} is
 * called explicitly. A note carries a coordinate, and a coordinate is a place the owner drove -
 * uploading that silently would be publishing their movements.
 *
 * <h3>What is deliberately NOT done</h3>
 *
 * No POI edits, no way modifications, no automatic tagging. A note is a QUESTION addressed to a
 * human mapper; an edit is an assertion. This app infers narrowness from a name pattern, which is
 * good enough to be worth a mapper's attention and nowhere near good enough to write into the map
 * as fact. Getting that distinction wrong is how an automated client gets its edits reverted and
 * its account blocked.
 */
public final class CairoDriveOsmFeedback {

	private static final Log LOG = PlatformUtil.getLog(CairoDriveOsmFeedback.class);
	private static final String TAG = "CD_OSM_FEEDBACK";

	/**
	 * Two candidates closer together than this are treated as the same street.
	 *
	 * <p>Without it a single alley driven at 20 km/h with a fix a second would produce a note
	 * every few metres - dozens of duplicates on one way, which is spam rather than feedback and
	 * is the fastest way to have a mapping account treated as abusive.
	 */
	private static final double DEDUPE_RADIUS_M = 120;

	/** A cap, because a note is a request for a human's time and this is one driver's opinion. */
	private static final int MAX_PENDING = 20;

	private static final Map<String, Candidate> PENDING = new LinkedHashMap<>();

	/** One observation: where, and what the way was called. */
	public static final class Candidate {
		public final LatLon location;
		public final String name;

		Candidate(@NonNull LatLon location, @NonNull String name) {
			this.location = location;
			this.name = name;
		}
	}

	private CairoDriveOsmFeedback() {
	}

	public static boolean isEnabled(@Nullable OsmandApplication app) {
		return app != null && app.getSettings().OSM_NARROW_FEEDBACK.get();
	}

	/**
	 * Records a way whose NAME says alley and whose TAGS do not.
	 *
	 * <p>Cheap and side-effect free: a map lookup and, at most, an insert. Safe to call per fix,
	 * which is what the caller does. It never touches the network - see the class comment.
	 */
	public static void observeNarrowCandidate(@Nullable OsmandApplication app,
	                                          @Nullable LatLon location, @Nullable String name) {
		if (!isEnabled(app) || location == null || name == null || name.trim().isEmpty()) {
			return;
		}
		synchronized (PENDING) {
			if (PENDING.size() >= MAX_PENDING) {
				return;
			}
			for (Candidate existing : PENDING.values()) {
				if (MapUtils.getDistance(existing.location, location) < DEDUPE_RADIUS_M) {
					return;
				}
			}
			PENDING.put(key(location), new Candidate(location, name.trim()));
		}
	}

	private static String key(@NonNull LatLon location) {
		return String.format(Locale.US, "%.5f,%.5f", location.getLatitude(), location.getLongitude());
	}

	public static int pendingCount() {
		synchronized (PENDING) {
			return PENDING.size();
		}
	}

	@NonNull
	public static List<Candidate> pending() {
		synchronized (PENDING) {
			return new ArrayList<>(PENDING.values());
		}
	}

	public static void clearPending() {
		synchronized (PENDING) {
			PENDING.clear();
		}
	}

	/**
	 * Uploads the accumulated candidates as OSM notes. Blocking - call it off the main thread.
	 *
	 * <p>Uses the OSM Editing plugin's own remote util, so it goes through the OAuth identity
	 * configured in {@code OsmOAuthAuthorizationAdapter} - this fork's own OSM application when
	 * the build carries one - and inherits its error handling and its dev/prod endpoint switch
	 * rather than reimplementing any of it.
	 *
	 * <p>A candidate that fails is KEPT rather than dropped, so a flaky connection costs a retry
	 * instead of the observation. One that succeeds is removed immediately, so a partial failure
	 * cannot re-upload the ones that already landed.
	 *
	 * @return how many notes were accepted
	 */
	public static int uploadPending(@Nullable OsmandApplication app) {
		if (!isEnabled(app)) {
			return 0;
		}
		OsmEditingPlugin plugin = PluginsHelper.getPlugin(OsmEditingPlugin.class);
		if (plugin == null) {
			log("skipped - OSM editing plugin not active");
			return 0;
		}
		List<Candidate> candidates = pending();
		if (candidates.isEmpty()) {
			return 0;
		}
		OsmBugsRemoteUtil util = new OsmBugsRemoteUtil(app);
		int uploaded = 0;
		for (Candidate candidate : candidates) {
			OsmNotesPoint point = new OsmNotesPoint();
			point.setLatitude(candidate.location.getLatitude());
			point.setLongitude(candidate.location.getLongitude());
			try {
				Object result = util.commit(point, noteText(candidate), Action.CREATE);
				boolean ok = result != null && !hasWarning(result);
				if (ok) {
					uploaded++;
					synchronized (PENDING) {
						PENDING.remove(key(candidate.location));
					}
				}
			} catch (Throwable t) {
				LOG.error("OSM note upload failed", t);
			}
		}
		log("uploaded=" + uploaded + "/" + candidates.size() + " remaining=" + pendingCount());
		return uploaded;
	}

	/**
	 * True when the util reported a warning rather than success.
	 *
	 * <p>Read reflectively because {@code OsmBugResult} is an inner class of an upstream helper
	 * and its shape is not part of any contract this fork controls. A rename upstream then makes
	 * this treat every upload as failed - candidates are kept and retried, which is the harmless
	 * direction - rather than failing to compile the whole fork on the next sync.
	 */
	private static boolean hasWarning(@NonNull Object result) {
		try {
			Object warning = result.getClass().getField("warning").get(result);
			return warning != null;
		} catch (Throwable t) {
			// TRUE, not false. The javadoc above promised "candidates are kept and retried, which
			// is the harmless direction" and the code did the opposite: false here makes
			// `ok = result != null && !hasWarning(result)` true, so an unreadable result counted
			// as an accepted upload and the candidate was DELETED. An upstream rename of
			// OsmBugResult.warning would have silently discarded the entire queue while reporting
			// success. Treating an unreadable answer as a failure keeps the observation.
			LOG.error("OsmBugResult.warning unreadable - treating upload as failed", t);
			return true;
		}
	}

	/**
	 * The note text.
	 *
	 * <p>Bilingual, and says what was OBSERVED rather than what should be done. A mapper reading
	 * it needs to know why a stranger thinks this way is narrow; "add width=2" from an automated
	 * client is an instruction nobody asked for. It also states plainly that it came from an app,
	 * which is what the OSM community expects of any automated contribution.
	 */
	@NonNull
	static String noteText(@NonNull Candidate candidate) {
		return "Possible narrow street / احتمال شارع ضيق: \"" + candidate.name + "\"."
				+ " The name matches an alley pattern (حارة / زقاق / درب / ممر / عطفة)"
				+ " but the way carries no width, est_width or lanes tag,"
				+ " so routers cannot avoid it."
				+ " Could a local mapper confirm the width?"
				+ " (Reported automatically by CairoDrive, an OsmAnd fork, from a driven trace.)";
	}

	private static void log(String message) {
		if (CairoDriveLogger.isEnabled()) {
			CairoDriveLogger.getInstance().log(TAG, message);
		}
	}
}
