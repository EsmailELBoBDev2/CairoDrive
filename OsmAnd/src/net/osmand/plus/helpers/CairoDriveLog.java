package net.osmand.plus.helpers;

import androidx.annotation.NonNull;

import net.osmand.plus.cairodrive.CairoDriveLogger;

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
		// AND to the drive log file, which is the whole point and did not happen before.
		//
		// The line above alone reached logcat and nothing else. CairoDriveLogger's pump takes a
		// TAG WHITELIST - net.osmand, NavigationSession, SurfaceRenderer, System.out - with a
		// "*:W" floor under everything else, and this class logs at INFO under the tag
		// "CairoDrive". So every line it has ever written was dropped before reaching the file
		// the drive analysis is pulled from: ClosureSyncHelper's decisions, the detour install,
		// the settings changes. Present in logcat on a tethered phone, absent from the artefact
		// anyone actually reads afterwards.
		//
		// Writing directly rather than widening the whitelist, because that also removes the
		// dependency on the pump having started at all.
		try {
			if (CairoDriveLogger.isEnabled()) {
				CairoDriveLogger.getInstance().log("CD_" + feature, text);
			}
		} catch (Throwable t) {
			// Logging must never be the thing that breaks a drive.
		}
	}
}
