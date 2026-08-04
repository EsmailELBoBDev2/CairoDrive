package net.osmand.plus.cairodrive;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.data.QuadPointDouble;
import net.osmand.plus.OsmandApplication;
import net.osmand.util.MapUtils;

import java.util.Locale;

/**
 * N6, second half: the ONLY consumer of a map match. Turns
 * {@link CairoDriveMapMatchService#getLastMatch()} into a corrected position for the map, and
 * writes {@code CD_MATCH applied=}.
 *
 * <h3>Display only, and structurally so</h3>
 *
 * This fork keeps two streams on purpose - raw fixes for logic, corrected positions for display -
 * and {@link CairoDriveStationary} already follows it. The rule here is the same one and it is
 * enforced by WHERE this is called rather than by a comment: {@code OsmAndLocationProvider}
 * invokes {@link #apply} only after {@code setLocationForRouting} has already handed the
 * untouched fix to {@code RoutingHelper}. Off-route detection, the ETA and route recalculation
 * therefore see exactly what they saw before this class existed.
 *
 * <p>That ordering is the important safety property. A matcher that is wrong about which road the
 * car is on would, if routing consumed it, produce a reroute - and a spurious reroute in Cairo
 * traffic is far worse than an arrow drawn six metres off. An arrow in the wrong lane is a
 * cosmetic error the driver can see through; a recalculated route is an instruction they cannot.
 *
 * <p>The same ordering also breaks the feedback loop. The matcher is fed the RAW fix, never
 * {@code this.location}. Feeding a matcher its own output makes every corrected position look
 * like a perfect on-road observation, which would let one wrong road reinforce itself forever.
 *
 * <p><b>Residual exposure, stated rather than hidden.</b> A handful of one-shot, user-initiated
 * entry points do read {@code getLastKnownLocation()} into routing -
 * {@code RoutingHelper.resumeNavigation}, {@code MapActions:517},
 * {@code NavigateGpxHelper:213}, {@code RoutingHelperUtils:93}. None of them is on the per-fix
 * path; they fire when the driver starts or resumes navigation. Upstream already routes a
 * display-snapped position through them whenever {@code SNAP_TO_ROAD} is on, and this class
 * stands down entirely in that case (see {@code route-snapped} below), so it adds no new
 * behaviour there beyond a one-off, gated, on-road start point.
 *
 * <h3>The correction is a re-projection, not a snap</h3>
 *
 * The obvious implementation - move the arrow to {@code match.lat/lon} - is wrong, and the reason
 * is the service's asynchrony. A match is computed for the fix that produced it, and by the time
 * anything reads it the car has moved. Snapping to the stored point pays that movement as
 * along-track error: the arrow is placed on the right road at the wrong point along it.
 *
 * <p>So the match carries its SEGMENT, and the live fix is projected onto that segment instead.
 * Cross-track error is removed exactly, as with a snap; along-track position comes from the
 * current fix and is untouched. Simulated (n6_applysim.py), mean distance from the displayed
 * point to ground truth on the fixes where a correction was applied:
 *
 * <pre>
 *   trace     shape           err|applied   raw|applied
 *   flyover   snap               11.83m        17.53m
 *   flyover   project            11.41m        17.53m   &lt;- chosen
 *   flyover   vector             11.72m        17.53m
 *   flyover   blend 0.50         13.77m        17.53m
 *   flyover   blend 0.75         12.42m        17.53m
 *   street    snap               12.42m        18.66m
 *   street    project            12.15m        18.66m   &lt;- chosen
 *   street    vector             12.69m        18.66m
 *   street    blend 0.50         14.54m        18.66m
 *   street    blend 0.75         13.07m        18.66m
 * </pre>
 *
 * <p><b>Full correction, not a blend</b>, and the table is only half the reason. Blending is
 * worse at every ratio tried, which is enough on its own - but the qualitative argument is
 * stronger than the numbers. The point of the correction is to put the arrow ON a road. Any
 * ratio below 1.0 leaves it in the gap between the flyover and the street, visibly on neither,
 * which is a worse thing for a driver glancing at the screen than being confidently on the wrong
 * one. And a "vector" correction - adding the stored offset to the live fix - carries the source
 * fix's noise into the answer on top of the live fix's own, doubling the positional variance;
 * that is why it loses to a plain snap despite being staleness-aware.
 *
 * <p>Nothing is smoothed here on purpose. A correction appearing or disappearing changes the
 * position by up to {@link CairoDriveMapMatching#MAX_CORRECTION_M}, but the map already animates
 * position changes over the fix interval ({@code MapViewTrackingUtilities.setMyLocationV1/V2}
 * take a {@code movingTime}), so the arrow slides rather than teleports without any help.
 *
 * <h3>Does a WRONG match hurt?</h3>
 *
 * This is the question that decides whether consuming the match is allowed at all, and the
 * answer is measured, conditioned on the matcher having chosen the wrong road:
 *
 * <pre>
 *   trace     wrong% of applied   err|wrong   raw|wrong   worse than raw   mean excess when worse
 *   flyover         0.5%           17.95m      19.04m         61.8%              2.83m
 *   street          1.0%           16.10m      20.01m         43.5%              1.48m
 * </pre>
 *
 * A wrong match still lands the arrow CLOSER to the truth on average than the raw fix did, for
 * the plain geometric reason that the roads the matcher confuses run a few metres apart while a
 * {@code satsUsed=0} fix is tens of metres out. It is worse on some of those fixes, and the
 * expected cost of that is 0.005 x 0.618 x 2.83 = 0.009 m per applied fix on the flyover trace
 * and 0.015 m on the street trace, against a mean gain of 3.05 m and 3.99 m respectively - a
 * ratio of roughly 200 to 1 in favour of correcting.
 *
 * <h3>Threading</h3>
 *
 * {@link #apply} runs on whichever thread delivered the fix ({@code setLocation} on the main
 * thread, {@code setLocationFromService} on the service's). It is synchronized because of the
 * log rate-limit counters, not the decision - the decision reads one volatile reference and does
 * arithmetic. It is called once per fix, so the lock is uncontended.
 */
public final class CairoDriveMatchedPosition {

	private static volatile CairoDriveMatchedPosition instance;

	private final OsmandApplication app;

	// Log state. Guarded by the instance lock.
	private long lastLogAt;
	private String lastReason;
	private boolean lastApplied;
	private long summaryAt;
	private int decisions;
	private int applications;
	private int rejectedSettled;
	private int rejectedStale;
	private int rejectedDrift;
	private int rejectedFar;
	private int rejectedSnapped;
	private int rejectedNoMatch;
	private double correctionSum;
	private double correctionMax;

	private CairoDriveMatchedPosition(@NonNull OsmandApplication app) {
		this.app = app;
	}

	@NonNull
	public static CairoDriveMatchedPosition getInstance(@NonNull OsmandApplication app) {
		CairoDriveMatchedPosition local = instance;
		if (local == null) {
			synchronized (CairoDriveMatchedPosition.class) {
				local = instance;
				if (local == null) {
					local = new CairoDriveMatchedPosition(app);
					instance = local;
				}
			}
		}
		return local;
	}

	/**
	 * Decides whether the newest match may correct the position that will be DISPLAYED.
	 *
	 * @param rawFix          the fix as it arrived from the provider, already handed to routing
	 *                        unchanged by the caller. Used as the staleness reference and as the
	 *                        thing projected onto the matched road.
	 * @param displayLocation what would be published without this feature. Identical to
	 *                        {@code rawFix} unless {@code RoutingHelper} already snapped it to
	 *                        the calculated route.
	 * @return {@code displayLocation} itself when no correction is applied - never a copy, so a
	 *         caller can test for correction by object identity - or a corrected copy of it.
	 */
	@Nullable
	public Location apply(@Nullable Location rawFix, @Nullable Location displayLocation) {
		if (!CairoDriveMapMatching.isEnabled() || rawFix == null || displayLocation == null) {
			return displayLocation;
		}
		try {
			return decide(rawFix, displayLocation);
		} catch (Throwable t) {
			// Never let the display path die for this. A feature that is off is exactly as good
			// as not having built it; a feature that throws inside setLocation is a dead map.
			CairoDriveMapMatching.disable("apply-" + t.getClass().getSimpleName());
			CairoDriveLogger.getInstance().log("CD_MATCH", "apply failed", t);
			return displayLocation;
		}
	}

	private synchronized Location decide(@NonNull Location rawFix, @NonNull Location displayLocation) {
		decisions++;

		// Routing already placed this on the road it routed the driver onto. That is strictly
		// more information than the matcher has, and two correctors fighting over one pixel is
		// the "buggy as hell" failure mode this fork has already paid for once. Stand down, and
		// let CD_MATCH report how often - if it is most of a navigating drive, then the honest
		// conclusion is that N6 earns its keep in free-drive, and the log will have shown it.
		if (displayLocation != rawFix) {
			rejectedSnapped++;
			return reject(displayLocation, "route-snapped");
		}

		CairoDriveMapMatcher.Match match = CairoDriveMapMatchService.getInstance(app).getLastMatch();
		if (match == null) {
			rejectedNoMatch++;
			return reject(displayLocation, "no-match");
		}

		// 1. Confidence. settledDepth, not the posterior - see MAX_SETTLED_DEPTH.
		if (match.settledDepth < 0 || match.settledDepth > CairoDriveMapMatching.MAX_SETTLED_DEPTH) {
			rejectedSettled++;
			return reject(displayLocation, "settled=" + match.settledDepth);
		}

		// 2. Staleness in time.
		long now = rawFix.getTime() > 0 ? rawFix.getTime() : System.currentTimeMillis();
		long ageMs = now - match.fixTime;
		if (ageMs < 0 || ageMs > CairoDriveMapMatching.MAX_MATCH_AGE_MS) {
			rejectedStale++;
			return reject(displayLocation, "ageMs=" + ageMs);
		}

		// 3. Staleness in distance - the one that actually bounds the error.
		int x31 = MapUtils.get31TileNumberX(rawFix.getLongitude());
		int y31 = MapUtils.get31TileNumberY(rawFix.getLatitude());
		double driftM = MapUtils.squareRootDist31(match.fixX31, match.fixY31, x31, y31);
		if (driftM > CairoDriveMapMatching.MAX_SOURCE_DRIFT_M) {
			rejectedDrift++;
			return reject(displayLocation, "driftM=" + fmt(driftM));
		}

		// 4. Re-project the LIVE fix onto the matched segment, rather than snapping it back to
		// where the car was when the match was computed. getProjectionPoint31 clamps to the
		// segment ends, so a fix that has run past the segment lands on its far node - still on
		// the road, and bounded by the drift gate above.
		QuadPointDouble projected = MapUtils.getProjectionPoint31(x31, y31,
				match.segAX31, match.segAY31, match.segBX31, match.segBY31);
		int px = (int) projected.x;
		int py = (int) projected.y;
		double correctionM = MapUtils.squareRootDist31(px, py, x31, y31);
		if (correctionM > CairoDriveMapMatching.MAX_CORRECTION_M) {
			rejectedFar++;
			return reject(displayLocation, "corrM=" + fmt(correctionM));
		}

		Location corrected = new Location(displayLocation);
		corrected.setLatitude(MapUtils.get31LatitudeY(py));
		corrected.setLongitude(MapUtils.get31LongitudeX(px));

		applications++;
		correctionSum += correctionM;
		correctionMax = Math.max(correctionMax, correctionM);
		accept(match, correctionM, driftM, ageMs);
		return corrected;
	}

	// ------------------------------------------------------------------ CD_MATCH applied=

	/**
	 * Rate-limited, but never at the cost of a transition.
	 *
	 * <p>A decision is logged when the applied/not-applied state changes, when the REASON for not
	 * applying changes, or once every {@link CairoDriveMapMatching#APPLY_LOG_INTERVAL_MS}. The
	 * transitions are what a drive log is read for - "it stopped correcting here, and this is
	 * why" - and rate-limiting them away would leave only the steady state, which the minute
	 * summary already reports. The steady state is what gets thinned.
	 */
	private boolean shouldLog(boolean applied, @Nullable String reason) {
		long now = SystemClock.elapsedRealtime();
		boolean changed = applied != lastApplied
				|| (reason == null) != (lastReason == null)
				|| (reason != null && !reason.equals(lastReason));
		if (!changed && now - lastLogAt < CairoDriveMapMatching.APPLY_LOG_INTERVAL_MS) {
			return false;
		}
		lastLogAt = now;
		lastApplied = applied;
		lastReason = reason;
		return true;
	}

	private Location reject(@NonNull Location displayLocation, @NonNull String reason) {
		if (shouldLog(false, reasonKey(reason))) {
			CairoDriveLogger.getInstance().log("CD_MATCH", "applied=false reason=" + reason);
		}
		maybeLogSummary();
		return displayLocation;
	}

	private void accept(@NonNull CairoDriveMapMatcher.Match match, double correctionM,
	                    double driftM, long ageMs) {
		if (shouldLog(true, null)) {
			CairoDriveLogger.getInstance().log("CD_MATCH", "applied=true"
					+ " corrM=" + fmt(correctionM)
					+ " driftM=" + fmt(driftM)
					+ " ageMs=" + ageMs
					+ " road=" + match.roadId
					+ " settled=" + match.settledDepth
					+ " conf=" + fmt(match.confidence)
					+ " offM=" + fmt(match.offsetM)
					+ " disagree=" + match.disagreesWithNearest
					+ " degraded=" + match.degraded);
		}
		maybeLogSummary();
	}

	/**
	 * Reasons carry a measured value, so the raw string would make every line look like a
	 * transition and defeat the rate limit. Only the part before {@code =} identifies the reason.
	 */
	private static String reasonKey(@NonNull String reason) {
		int eq = reason.indexOf('=');
		return eq < 0 ? reason : reason.substring(0, eq);
	}

	private void maybeLogSummary() {
		long now = SystemClock.elapsedRealtime();
		if (summaryAt == 0) {
			summaryAt = now;
			return;
		}
		if (now - summaryAt < CairoDriveMapMatching.APPLY_SUMMARY_INTERVAL_MS || decisions == 0) {
			return;
		}
		summaryAt = now;
		CairoDriveLogger.getInstance().log("CD_MATCH", "applySummary"
				+ " decisions=" + decisions
				+ " applied=" + applications
				+ " (" + (100 * applications / Math.max(1, decisions)) + "%)"
				+ " avgCorrM=" + fmt(correctionSum / Math.max(1, applications))
				+ " maxCorrM=" + fmt(correctionMax)
				+ " rejSettled=" + rejectedSettled
				+ " rejStale=" + rejectedStale
				+ " rejDrift=" + rejectedDrift
				+ " rejFar=" + rejectedFar
				+ " rejSnapped=" + rejectedSnapped
				+ " rejNoMatch=" + rejectedNoMatch
				+ " gateSettled=" + CairoDriveMapMatching.MAX_SETTLED_DEPTH
				+ " gateAgeMs=" + CairoDriveMapMatching.MAX_MATCH_AGE_MS
				+ " gateDriftM=" + fmt(CairoDriveMapMatching.MAX_SOURCE_DRIFT_M)
				+ " gateCorrM=" + fmt(CairoDriveMapMatching.MAX_CORRECTION_M));
		decisions = 0;
		applications = 0;
		rejectedSettled = 0;
		rejectedStale = 0;
		rejectedDrift = 0;
		rejectedFar = 0;
		rejectedSnapped = 0;
		rejectedNoMatch = 0;
		correctionSum = 0;
		correctionMax = 0;
	}

	/** Clears the log rate-limit state so the next decision is reported. No positional state. */
	synchronized void reset() {
		lastReason = null;
		lastApplied = false;
		lastLogAt = 0;
	}

	private static String fmt(double value) {
		if (Double.isNaN(value) || Double.isInfinite(value)) {
			return "na";
		}
		return String.format(Locale.US, "%.2f", value);
	}
}
