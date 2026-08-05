package net.osmand.plus.cairodrive.providers;

import androidx.annotation.NonNull;

import java.util.Locale;

/**
 * Spends a daily API budget so that it lasts a 24-hour drive without making a 45-minute one slow.
 *
 * <h3>The problem a fixed interval cannot solve</h3>
 *
 * The daily caps are {@code floor(monthlyFree / 31)} - 645 TomTom flow points, 80 incident
 * requests, 161 Google delay polls, 32 span polls. Spread flat across 24 hours of driving those
 * are one flow sweep every 22 minutes and one span poll every 45. Spend them at the rate a short
 * commute deserves and they are gone in 40 minutes, after which the feature is simply off - which
 * is the failure mode the owner asked to remove: <i>"1 hour or 12 hours driving I'm covered in
 * both"</i>.
 *
 * <p>Those two requirements look contradictory and are not, because <b>a short drive never reaches
 * the slow tiers</b>. The ladder below starts at the data's own refresh rate and steps down as the
 * budget is consumed, so the first 20% of the budget is spent fast, and only a drive long enough
 * to exhaust it ever sees the cheap end.
 *
 * <h3>Why the ladder is keyed on BUDGET, not on elapsed time</h3>
 *
 * A timer would have to guess how long the drive will be, and would be wrong on both ends: pacing
 * for 24 hours makes every normal day crawl, pacing for one hour runs dry on a long one. Budget
 * consumed is the honest state variable - it is what actually determines what is left, it survives
 * the app being killed and restarted mid-day because the counters are persisted, and it treats
 * "two long drives" and "one continuous drive of the same length" identically, which is correct
 * because the cap is daily rather than per-trip.
 *
 * <h3>Coverage, computed rather than hoped for</h3>
 *
 * Each ladder is solved so the integral of (budget slice / units per call) x interval exceeds 24
 * hours while spending exactly 100% of the cap:
 *
 * <pre>
 *   stream             covers    tier-1 lasts    interval at 45 min / 3 h / 24 h
 *   TomTom flow        28.0 h      13 min          2 m  /  8 m  / 20 m
 *   TomTom incidents   27.1 h      24 min          3 m  /  7 m  / 70 m
 *   Google delay       25.8 h      32 min          2 m  /  5 m  / 30 m
 *   Google spans       24.5 h      32 min         15 m  / 30 m  / 120 m
 * </pre>
 */
public final class BudgetPacer {

	/** One rung: how much of the budget it may spend, and how it spends it. */
	public static final class Tier {
		public final double budgetFraction;
		/** Units consumed per call - points for a flow sweep, 1 for a single request. */
		public final int unitsPerCall;
		public final long intervalMs;

		public Tier(double budgetFraction, int unitsPerCall, long intervalSeconds) {
			this.budgetFraction = budgetFraction;
			this.unitsPerCall = unitsPerCall;
			this.intervalMs = intervalSeconds * 1000L;
		}
	}

	private BudgetPacer() {
	}

	/**
	 * The rung the given consumption falls on.
	 *
	 * <p>Returns the LAST tier once the budget is spent rather than null: at that point the caller
	 * is refused by its own cap check anyway, and returning a tier keeps every caller free of a
	 * null branch on a path that runs per GPS fix.
	 */
	@NonNull
	public static Tier tierFor(int used, int cap, @NonNull Tier[] ladder) {
		if (cap <= 0 || ladder.length == 0) {
			return ladder.length > 0 ? ladder[ladder.length - 1] : new Tier(1, 1, 60);
		}
		double consumed = 0;
		for (Tier tier : ladder) {
			consumed += tier.budgetFraction;
			if (used < cap * consumed) {
				return tier;
			}
		}
		return ladder[ladder.length - 1];
	}

	/** Zero-based index of {@link #tierFor}'s answer, for logging. */
	public static int tierIndex(int used, int cap, @NonNull Tier[] ladder) {
		if (cap <= 0) {
			return 0;
		}
		double consumed = 0;
		for (int i = 0; i < ladder.length; i++) {
			consumed += ladder[i].budgetFraction;
			if (used < cap * consumed) {
				return i;
			}
		}
		return ladder.length - 1;
	}

	/**
	 * Hours of continuous driving this ladder covers on a full budget.
	 *
	 * <p>Not used at runtime - it exists so the SESSION header can state the coverage each stream
	 * was built for, and so a change to a ladder that silently breaks the 24-hour guarantee shows
	 * up in a drive log instead of on hour thirteen of a drive.
	 */
	public static double coverageHours(int cap, @NonNull Tier[] ladder) {
		double minutes = 0;
		for (Tier tier : ladder) {
			double calls = cap * tier.budgetFraction / Math.max(1, tier.unitsPerCall);
			minutes += calls * (tier.intervalMs / 60000.0);
		}
		return minutes / 60.0;
	}

	@NonNull
	public static String describe(int used, int cap, @NonNull Tier[] ladder) {
		Tier tier = tierFor(used, cap, ladder);
		return String.format(Locale.US, "tier=%d/%d used=%d/%d units=%d intervalS=%d coversH=%.1f",
				tierIndex(used, cap, ladder) + 1, ladder.length, used, cap,
				tier.unitsPerCall, tier.intervalMs / 1000, coverageHours(cap, ladder));
	}
}
