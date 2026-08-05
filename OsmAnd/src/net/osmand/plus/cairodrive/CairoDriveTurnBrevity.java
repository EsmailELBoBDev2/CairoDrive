package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.routing.data.StreetName;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * N7, the half {@link CairoDriveTurnLead} could not fix: the turn-now sentence itself overrunning
 * the junction.
 *
 * <h3>Why a second mechanism was needed</h3>
 *
 * {@code CairoDriveTurnLead} adds a short cue beforehand, so the driver learns the DIRECTION in
 * time. That is the whole of what an added prompt can do, and it was the right first move because
 * the ladder in {@code VoiceRouter.updateStatus} fires each rung exactly once - moving the turn-now
 * trigger earlier does not add a warning, it deletes the only one there is, which this fork already
 * did once and had to revert.
 *
 * <p>But the long sentence still runs past the junction. "انعطف يمينًا إلى الطريق الدائري، شارع صلاح
 * سالم باتجاه مدينة نصر والمطار" takes about 7.5 s against a 6.7 s window, so the driver is still
 * hearing street names after the turn. Given the trigger cannot move and the speaking rate cannot
 * change, exactly one lever remains: <b>say less</b>.
 *
 * <h3>What gets dropped, and in what order</h3>
 *
 * The sentence is not truncated mid-phrase - a sentence cut in half is worse than a short one. It
 * is composed of named parts, and the parts are dropped whole, least actionable first:
 *
 * <ol>
 *   <li>{@code FROM_DEST}, {@code FROM_REF}, {@code FROM_STREET_NAME} - the road being LEFT. The
 *       driver is on it; they can see it. This is confirmation, not instruction.</li>
 *   <li>{@code TO_DEST} - "toward Nasr City and the airport". A destination clause is the longest
 *       part of a Cairo instruction and the least useful in the last six seconds: it disambiguates
 *       a decision already made.</li>
 *   <li>{@code TO_REF} - "the Ring Road". Useful, but the street name is more specific.</li>
 * </ol>
 *
 * <p>{@code TO_STREET_NAME} is never dropped. If the sentence still does not fit with only the
 * street name left, it is left alone: at that point the remaining text IS the instruction, and a
 * long street name spoken slightly late is strictly better than a turn with no name at all. The
 * cue from {@code CairoDriveTurnLead} has already given the direction in that case.
 *
 * <h3>Only turn-now</h3>
 *
 * This is applied at the turn-now call site alone. Turn-in fires around 22 s out, has all the room
 * it needs, and is precisely where the destination clause earns its place - that is the prompt that
 * tells the driver which way the junction is going to send them, while there is still time to act
 * on it. Trimming both would remove the information from the whole drive rather than moving it to
 * the prompt with room for it.
 */
public final class CairoDriveTurnBrevity {

	/**
	 * Keys in {@link StreetName}'s map, in the order they are given up.
	 *
	 * <p>Duplicated as literals rather than imported from {@code VoiceRouter}: they are declared
	 * there as public constants, but importing the routing class into a cairodrive helper to read
	 * four strings couples the two in the direction that makes upstream syncs painful. If upstream
	 * renames one, {@link #KNOWN_KEYS} stops matching and the trim degrades to a no-op, which is
	 * the safe direction to fail in.
	 */
	private static final String[] DROP_ORDER = {
			"fromDest", "fromRef", "fromStreetName", "toDest", "toRef",
	};

	/** Never dropped - see the class comment. */
	private static final String KEEP = "toStreetName";

	private static final String[] KNOWN_KEYS = {
			"fromDest", "fromRef", "fromStreetName", "toDest", "toRef", "toStreetName",
	};

	private CairoDriveTurnBrevity() {
	}

	/**
	 * Trims the parts of a turn-now street name that will not fit before the junction.
	 *
	 * @param name       the fully composed street name; returned unchanged if null or already short
	 *                   enough
	 * @param speedMps   current ground speed. Non-positive means stopped or unknown, and a window
	 *                   cannot be computed from it, so nothing is trimmed
	 * @param triggerM   the distance at which turn-now fires, in metres
	 * @param prefixMs   estimated duration of the part of the utterance this does NOT control -
	 *                   "turn right", the exit number, the roundabout clause. It is spoken either
	 *                   way and must be charged against the same window
	 * @return the same object when nothing was dropped, otherwise a new {@link StreetName}
	 */
	@NonNull
	public static StreetName trim(@Nullable StreetName name, double speedMps, double triggerM,
	                              long prefixMs) {
		if (name == null) {
			return new StreetName();
		}
		if (speedMps <= 0 || triggerM <= 0 || !CairoDriveTurnLead.ENABLED) {
			return name;
		}
		Map<String, String> parts = name.toMap();
		if (parts == null || parts.isEmpty()) {
			return name;
		}
		// The window is real time, not distance: how long the car takes to cover the trigger
		// distance is exactly how long there is to speak.
		double windowMs = (triggerM / speedMps) * 1000.0;
		CairoDriveSpeechClock clock = CairoDriveSpeechClock.getInstance();

		long total = prefixMs + estimate(clock, parts);
		if (total <= windowMs) {
			return name;
		}

		Map<String, String> trimmed = new LinkedHashMap<>(parts);
		int dropped = 0;
		for (String key : DROP_ORDER) {
			String value = trimmed.get(key);
			if (value == null || value.trim().isEmpty()) {
				continue;
			}
			trimmed.put(key, "");
			dropped++;
			total = prefixMs + estimate(clock, trimmed);
			if (total <= windowMs) {
				break;
			}
		}
		if (dropped == 0) {
			return name;
		}
		if (CairoDriveLogger.isEnabled()) {
			CairoDriveLogger.getInstance().log("CD_BREVITY",
					"trim dropped=" + dropped
							+ " windowMs=" + Math.round(windowMs)
							+ " finalMs=" + total
							+ " fits=" + (total <= windowMs)
							+ " speedMps=" + String.format(java.util.Locale.US, "%.1f", speedMps)
							+ " triggerM=" + Math.round(triggerM));
		}
		return new StreetName(trimmed);
	}

	/**
	 * Estimated speech time of the parts, spoken together.
	 *
	 * <p>Concatenated and estimated ONCE rather than summed per part. The clock charges a
	 * per-utterance overhead for TTS startup, and these are one utterance - summing five separate
	 * estimates would add that overhead five times and make every sentence look far too long to
	 * fit, so the trim would strip clauses that would in fact have fitted.
	 */
	private static long estimate(@NonNull CairoDriveSpeechClock clock,
	                             @NonNull Map<String, String> parts) {
		StringBuilder builder = new StringBuilder(96);
		for (String key : KNOWN_KEYS) {
			String value = parts.get(key);
			if (value != null && !value.trim().isEmpty()) {
				if (builder.length() > 0) {
					builder.append(' ');
				}
				builder.append(value);
			}
		}
		return builder.length() == 0 ? 0 : clock.estimateMs(builder.toString());
	}
}
