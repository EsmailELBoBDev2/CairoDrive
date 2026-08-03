package net.osmand.plus.routing;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.R;
import net.osmand.router.TurnType;

/**
 * Turns a lane layout into one of exactly three sentences: stay on the left side, stay in the
 * middle, stay on the right side.
 *
 * <p>The lane strip that OsmAnd draws - a row of arrows with the usable ones highlighted - is the
 * densest piece of information on the navigation screen and the one most often misread, because
 * understanding it means counting arrows and spotting a shade difference at a glance while driving.
 * Google Maps and Waze both draw the same strip AND say the same thing in words; this supplies the
 * words, for the Android Auto cue and for the spoken prompt. The strip is unchanged - this is
 * additive.
 *
 * <p>LANE ARRAY ENCODING, since it is not obvious from the type:
 * {@code int[] lanes} has one entry per physical lane, ordered left to right in the direction of
 * travel - always, in every country, regardless of which side people drive on. Bit 0 of each entry
 * is "this lane can be used for the upcoming manoeuvre"; the turn arrows themselves are packed into
 * the higher bits (see {@link TurnType#getPrimaryTurn}). So "left" and "right" below are absolute
 * positions in the array, not driving-side-relative, and need no left-hand-traffic special case.
 */
public class LaneHint {

	/**
	 * @param ctx   used only to resolve strings, so pass a locale-specific context when the text is
	 *              destined for TTS in a voice language that differs from the UI language.
	 * @param lanes lane layout for the upcoming turn, as stored on {@link TurnType#getLanes()}.
	 * @return a human-readable instruction, or null when there is nothing worth saying.
	 */
	@Nullable
	public static String getHint(@NonNull Context ctx, @Nullable int[] lanes) {
		if (lanes == null || lanes.length < 2) {
			// One lane is not a choice, so naming it is noise.
			return null;
		}
		int first = -1;
		int last = -1;
		int count = 0;
		for (int i = 0; i < lanes.length; i++) {
			if (isActive(lanes[i])) {
				if (first < 0) {
					first = i;
				}
				last = i;
				count++;
			}
		}
		if (count == 0 || count == lanes.length) {
			// Either the data is unusable, or every lane works - in both cases there is no
			// decision to announce, and announcing one anyway would train the driver to ignore it.
			return null;
		}
		if (last - first + 1 != count) {
			// A split run ("leftmost and rightmost lanes but not the middle") has no short phrasing
			// that is not more confusing than the arrows themselves. Stay silent and let the strip
			// speak; a wrong sentence is worse than no sentence. This is vanishingly rare in
			// practice - a single manoeuvre almost always maps to adjacent lanes.
			return null;
		}

		// Three possible sentences, and never a fourth. Which side of the road to be on - that is
		// the whole vocabulary.
		//
		// An earlier version of this had six phrasings, including lane counts ("the 2 right lanes")
		// and offsets ("lane 2 from the right"). More precise, and worse: the owner's verdict on
		// reading them was "I'm still lost". A hint the driver has to decode is not a hint. Six
		// sentences is six things to learn while driving; three is a fact about where you are.
		//
		// The precision is not really lost either, because the lane arrows are still drawn right
		// next to this text and still carry the exact layout. This is the caption, not the diagram,
		// and a caption that is instantly right beats one that is exactly right.
		if (first == 0) {
			return ctx.getString(R.string.lane_hint_left_side);
		}
		if (last == lanes.length - 1) {
			return ctx.getString(R.string.lane_hint_right_side);
		}
		return ctx.getString(R.string.lane_hint_middle);
	}

	private static boolean isActive(int lane) {
		return lane % 2 == 1;
	}
}
