package net.osmand.plus.helpers;

import androidx.annotation.NonNull;

/**
 * Field-diagnosis log for the traffic stack. Same tag and same "FEATURE | detail" line shape as
 * the fork's tiered recorder, so existing logcat filters (adb logcat -s CairoDrive) keep working;
 * the tiered file sink, kv builders and token buckets are deliberately not ported - nothing in the
 * traffic stack calls them.
 *
 * A fixed tag is the point: PlatformUtil.getLog tags per class, which would scatter these lines
 * across five tags and break the single-filter workflow this exists for.
 *
 * Call sites must pass already-formatted text and must never pass an API key or user query text.
 */
public final class CairoDriveLog {

	private static final String TAG = "CairoDrive";
	// Provider JSON influences closure counts, so detail strings are attacker-adjacent.
	// Truncation is free insurance against a pathological line.
	private static final int MAX_DETAIL_CHARS = 400;

	private CairoDriveLog() {
	}

	/** One flat line: feature bucket + already-formatted detail. */
	public static void log(@NonNull String feature, @NonNull String detail) {
		String text = detail.length() > MAX_DETAIL_CHARS
				? detail.substring(0, MAX_DETAIL_CHARS) : detail;
		android.util.Log.i(TAG, feature + " | " + text);
	}
}
