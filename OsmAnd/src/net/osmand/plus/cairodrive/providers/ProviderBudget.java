package net.osmand.plus.cairodrive.providers;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.helpers.CairoDriveLog;

/**
 * The daily request budget shared by every paid provider.
 *
 * <p>This was four private {@code claim()} copies - Geoapify, LocationIQ, Azure and Tomorrow -
 * that were written by copying one another. They had already diverged, and the divergence was
 * not cosmetic:
 *
 * <ul>
 *   <li><b>Azure and Tomorrow never called {@link ApiHealth#recordBudget}.</b> Geoapify and
 *       LocationIQ did. So two of the four providers burned their quota without ever publishing
 *       a number, and the API status screen showed them with no budget line at all - not "0 of
 *       40", nothing. Deduplicating fixes that by construction: there is now one code path and
 *       it always records.</li>
 *   <li><b>Each copy synchronized on its own class object</b> while all four read and wrote the
 *       SAME {@code cairodrive_providers} SharedPreferences file. That is four locks guarding
 *       one resource. It happens not to lose counts today only because each provider uses
 *       distinct key names and SharedPreferences is internally thread-safe - so the per-class
 *       lock was protecting nothing the framework was not already protecting. One lock here is
 *       both simpler and the only version whose correctness does not depend on that accident.</li>
 * </ul>
 *
 * <p>The day number is UTC, not Cairo local, and that is deliberately unchanged from the four
 * copies: it means the quota rolls over at 02:00 or 03:00 local depending on DST. For a cap that
 * exists to bound a bill rather than to be fair to a user, the rollover instant does not matter,
 * and changing it would silently give one day a double allowance on the build that changed it.
 *
 * <p>Every decision is logged under {@code CD_BUDGET}, including the refusals. A refusal is the
 * one moment the number matters and the one moment no request is made, so nothing else in the
 * pipeline would record it - a provider that goes quiet mid-drive because it hit its cap used to
 * be indistinguishable in the log from one that was never called.
 */
public final class ProviderBudget {

	/**
	 * NO "CD_" prefix - {@link CairoDriveLog#log} adds it. Passing "CD_BUDGET" here would write
	 * every line under CD_CD_BUDGET, which is the mistake already documented in GeoapifyProvider.
	 */
	private static final String TRACE_TAG = "BUDGET";

	private static final String PREFS_FILE = "cairodrive_providers";

	private static final long MS_PER_DAY = 24L * 60 * 60 * 1000;

	private ProviderBudget() {
	}

	/**
	 * Take one request from {@code api}'s daily allowance.
	 *
	 * @return true if the caller may make the request. False means the cap is spent, or that the
	 *         counter could not be read - see the catch below for why those are the same answer.
	 */
	public static boolean claim(@NonNull OsmandApplication app, @NonNull ApiHealth.Api api,
	                            @NonNull String dayPref, @NonNull String countPref, int cap) {
		try {
			SharedPreferences prefs = app.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE);
			int today = (int) (System.currentTimeMillis() / MS_PER_DAY);
			synchronized (ProviderBudget.class) {
				int day = prefs.getInt(dayPref, -1);
				int count = day == today ? prefs.getInt(countPref, 0) : 0;
				if (count >= cap) {
					// Published on the refusal too. This is the exact moment the number matters,
					// and it is the one moment no request is made and nothing else records it.
					ApiHealth.recordBudget(api, count, cap);
					// Deliberately NOT recordSkipped(BUDGET_SPENT) here: all eight call sites
					// already do that on a false return, and doing it in both places would just
					// overwrite lastSkipMs with the same value a microsecond later.
					CairoDriveLog.log(TRACE_TAG, api.name() + " REFUSED " + count + "/" + cap
							+ " - daily cap spent");
					return false;
				}
				prefs.edit().putInt(dayPref, today).putInt(countPref, count + 1).apply();
				ApiHealth.recordBudget(api, count + 1, cap);
				CairoDriveLog.log(TRACE_TAG, api.name() + " " + (count + 1) + "/" + cap);
				return true;
			}
		} catch (Throwable t) {
			// A failed counter must not become a free pass: if the budget cannot be accounted
			// for, no request is made. Logged rather than swallowed, because the four copies
			// this replaces returned false silently and an unaccountable budget looked exactly
			// like a provider that was simply never called.
			CairoDriveLog.log(TRACE_TAG, api.name() + " counter unreadable, refusing: " + t);
			return false;
		}
	}
}
