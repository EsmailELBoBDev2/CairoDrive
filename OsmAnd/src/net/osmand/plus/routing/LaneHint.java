package net.osmand.plus.routing;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.R;
import net.osmand.router.TurnType;

/**
 * Turns a lane layout into a sentence: "stay on the right side".
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

		int fromLeft = first;
		int fromRight = lanes.length - 1 - last;

		if (count == 1) {
			if (fromLeft == 0) {
				return ctx.getString(R.string.lane_hint_left_lane);
			}
			if (fromRight == 0) {
				return ctx.getString(R.string.lane_hint_right_lane);
			}
			if (fromLeft == fromRight) {
				// Dead centre of an odd-numbered set - "the middle lane" is how a passenger would
				// say it, and it is unambiguous only in this exact case.
				return ctx.getString(R.string.lane_hint_middle_lane);
			}
			// Count from whichever edge is closer: "2nd from the right" is easier to verify at
			// speed than "4th from the left" on a six-lane road.
			return fromLeft < fromRight
					? ctx.getString(R.string.lane_hint_nth_lane_from_left, fromLeft + 1)
					: ctx.getString(R.string.lane_hint_nth_lane_from_right, fromRight + 1);
		}
		// More than one lane works, so there is no single lane to name. Say which SIDE of the road
		// to be on and stop there - deliberately dropping the count, which is the part drivers
		// report not understanding. "Two lanes in from the edge" is also not something anyone can
		// verify at speed on a Cairo road, where lane paint is routinely worn away or ignored,
		// whereas "which side am I on" always answers itself.
		if (fromLeft == 0) {
			return ctx.getString(R.string.lane_hint_left_side);
		}
		if (fromRight == 0) {
			return ctx.getString(R.string.lane_hint_right_side);
		}
		return ctx.getString(R.string.lane_hint_middle_lanes);
	}

	private static boolean isActive(int lane) {
		return lane % 2 == 1;
	}
}
