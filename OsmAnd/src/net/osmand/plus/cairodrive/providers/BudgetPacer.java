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
 * <h3>The tail is HEADROOM; the ladder peaks at hour six</h3>
 *
 * An early shape degraded monotonically towards whatever interval made the arithmetic reach 24
 * hours - 70 minutes for incidents, 120 for spans. That satisfies "still running at hour thirteen"
 * and fails the thing the running was for: a two-hour-old congestion colour is not traffic data,
 * it is a decoration.
 *
 * <p>The fix for that was a floor the ladder could never degrade past. The fix was then aimed at
 * the wrong hours: it reserved the floor for hours 4 to 24, buying twenty hours of guarantee of
 * which about eighteen never happen, and paying for it in the only currency there is - coarser
 * data at the hours that do. The owner drives about six hours typically and has never exceeded
 * twelve.
 *
 * <p>So the ladders PEAK at six hours and step down past it WITHOUT a cliff, because "past six
 * hours" still has to mean current data rather than a stopped clock:
 *
 * <pre>
 *   stream            45 m /  2 h /  4 h /  6 h |  8 h / 12 h | past 12 h  covers
 *   TomTom flow        2 m /  5 m /  5 m /  5 m |  8 m /  8 m |   17 m     24.4 h
 *   TomTom incidents   4 m /  7 m / 10 m / 13 m | 20 m / 20 m |   45 m     24.8 h
 *   Google delay       2 m /  4 m /  5 m /  5 m | 11 m / 11 m |   41 m     24.3 h
 *   Google spans      25 m / 25 m / 25 m / 45 m | 45 m / 45 m |  120 m     24.2 h
 * </pre>
 *
 * The shape is deliberate. One long rung holds the best sustainable rate FLAT across the whole
 * six-hour window - 5-minute flow sweeps from hour two, 5-minute delay polls from hour 2.4 -
 * rather than degrading through it, so nothing gets worse while the drive is still in progress.
 * Then each stream steps ONCE, to a rate still inside what its data is used for, and holds that to
 * hour twelve. Only past twelve is there a headroom tail.
 *
 * <p>Spans is the exception and is left alone: 32 requests a day cannot be improved early without
 * collapsing the 8-12 hour band, so it keeps the arrangement holding 45 minutes across all of it.
 * The cheap DELAY stream carries freshness on that route instead.
 *
 * <h3>Why the numbers differ so much between streams</h3>
 *
 * They are not preferences, they are what the budget divides into. 1440 minutes against the daily
 * cap gives a hard flat maximum of 2.2 min for flow, 18 min for incidents, 9 min for delay and 45
 * min for spans - and any burst at all pushes the tail above that. Flow stays tightest because it
 * degrades on a SECOND axis: dropping a sweep from 8 sample points to 2 costs spatial resolution,
 * not freshness, so it holds a 5-minute refresh through hour six for a fraction of the
 * cost. Spans has no second
 * axis and only 32 requests, so its figures are near the arithmetic limit rather than a choice -
 * which is why the layer's paint TTL follows the tier instead of the colours blinking off, and why
 * the cheap DELAY stream is the one carrying freshness.
 *
 * <h3>Coverage, computed rather than hoped for</h3>
 *
 * Each ladder is still solved so the integral of (budget slice / units per call) x interval exceeds
 * 24 hours while spending exactly 100% of the cap. {@link #coverageHours} recomputes it from the
 * constants, and {@code tools/cd-ladder-check.py} additionally asserts that every consumer's
 * staleness window outlasts the slowest poll the ladder can issue - the defect that once left three
 * of four streams expiring their data before the poll that would have replaced it.
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

	/**
	 * The floor interval - the slowest this ladder can ever get, i.e. its last rung.
	 *
	 * <p>Exists so a consumer can size a staleness window off the ladder instead of hard-coding a
	 * number that silently becomes wrong when a ladder is retuned. The spans overlay uses it for
	 * exactly that: a fixed 10-minute paint TTL against a 65-minute floor would have blanked the
	 * colours for 85% of a long drive while the budget was being spent correctly.
	 */
	public static long floorIntervalMs(@NonNull Tier[] ladder) {
		return ladder.length == 0 ? 0 : ladder[ladder.length - 1].intervalMs;
	}

	/**
	 * Today's allowance, from what is LEFT of the month rather than from a fixed share of it.
	 *
	 * <h3>Why a fixed daily cap wastes most of the tier</h3>
	 *
	 * {@code floor(monthlyFree / 31)} is sized for a 24-hour driving day. Actual use is about 45
	 * minutes, so on a normal month 32-73% of the allowance expires unused - 6,500 TomTom flow
	 * points, 3,650 Google delay polls. Meanwhile the integer rounding that a fixed cap loses is
	 * 0.8%, which is the wrong thing to have been optimising.
	 *
	 * <pre>
	 *   allowance = (monthlyFree - usedThisMonth) / daysRemainingInMonth
	 * </pre>
	 *
	 * <p>Ten quiet days therefore RAISE the allowance for the rest of the month instead of
	 * forfeiting it, and the tier is consumed to ~100% whenever there is driving to spend it on.
	 *
	 * <h3>Why it cannot overrun</h3>
	 *
	 * The divisor is days remaining INCLUDING today, so the worst case is spending exactly the
	 * remainder on the final day. Every earlier day can only claim a fraction of what is left, and
	 * each day's spend is subtracted before the next division - the sequence is strictly decreasing
	 * in remainder and cannot sum past the tier.
	 *
	 * @param daysRemaining days left in the month including today; clamped to at least 1
	 */
	public static int dailyAllowance(int monthlyFree, int usedThisMonth, int daysRemaining) {
		int left = Math.max(0, monthlyFree - Math.max(0, usedThisMonth));
		int days = Math.max(1, daysRemaining);
		// Floor, so rounding can only ever leave budget unspent rather than overrun it. The
		// remainder is not lost: it returns to `left` tomorrow and raises that day's allowance.
		return left / days;
	}

	/**
	 * A floor under {@link #dailyAllowance}, as a fraction of the flat share.
	 *
	 * <p>Without one, a month whose budget was spent early would leave the last days at zero -
	 * traffic simply off. This guarantees a usable minimum by borrowing against the overrun the
	 * flat cap would have allowed anyway, which is safe because the flat cap itself was inside the
	 * tier: worst case the month lands slightly under 100% of free rather than over it.
	 */
	public static int dailyAllowanceWithFloor(int monthlyFree, int usedThisMonth,
	                                          int daysRemaining, int daysInMonth) {
		int flat = monthlyFree / Math.max(1, daysInMonth);
		int paced = dailyAllowance(monthlyFree, usedThisMonth, daysRemaining);
		int floor = Math.max(1, flat / 4);
		return Math.max(floor, paced);
	}

	@NonNull
	public static String describe(int used, int cap, @NonNull Tier[] ladder) {
		Tier tier = tierFor(used, cap, ladder);
		return String.format(Locale.US,
				"tier=%d/%d used=%d/%d units=%d intervalS=%d floorS=%d coversH=%.1f",
				tierIndex(used, cap, ladder) + 1, ladder.length, used, cap,
				tier.unitsPerCall, tier.intervalMs / 1000, floorIntervalMs(ladder) / 1000,
				coverageHours(cap, ladder));
	}
}
