package net.osmand.plus.cairodrive.providers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.api.SettingsAPI;
import net.osmand.plus.settings.backend.OsmandSettings;

import java.util.Locale;

/**
 * How much longer this drive will last, when there is no route to ask.
 *
 * <h3>The gap this fills</h3>
 *
 * {@link BudgetPacer#routeHorizonMinutes} reads the router's ETA, which is the honest answer and
 * needs no model at all - but it only exists while NAVIGATING. Free driving has no destination and
 * therefore no ETA, so the pacer falls back to the static ladder: correct, and conservative to the
 * point of leaving budget unspent on a twenty-minute errand.
 *
 * <h3>Mean residual life, estimated from the owner's own driving</h3>
 *
 * The question "how much longer will this last, given it has already lasted t" is not a new one -
 * it is the <b>mean residual life</b> of a duration distribution, the standard object in survival
 * analysis. The textbook trap is assuming the exponential distribution, whose residual life is
 * memoryless: it would answer "the same as when you started" no matter how long you had been
 * driving, which is exactly wrong here. Human trip lengths are heavier-tailed than that - a drive
 * that has already run two hours is far more likely to be a long trip than a short one running
 * late.
 *
 * <p>So rather than assume a shape, this measures one. Completed free-driving sessions are counted
 * into a fixed set of duration buckets, and the estimate is the conditional quantile:
 *
 * <pre>
 *   remaining(t) = quantile(durations that exceeded t, {@value #SAFE_QUANTILE_PCT}th) - t
 * </pre>
 *
 * <p>Conditioning on {@code > t} is the whole trick. It automatically produces "you are probably
 * nearly home" early on and "this is one of your long days" once the short trips have been ruled
 * out, without any rule saying so, and it adapts to this driver rather than to drivers in general.
 *
 * <h3>Why a high quantile rather than the mean</h3>
 *
 * An UNDER-estimate of remaining time is the dangerous direction: it spends the budget for a short
 * drive that turns out to be long, and then the long part runs on the cheap end of the ladder. The
 * {@value #SAFE_QUANTILE_PCT}th percentile deliberately over-estimates, matching the same
 * bias-to-under-spend already used for {@code HORIZON_SLACK} on the router's ETA.
 *
 * <h3>Why it cannot make anything worse</h3>
 *
 * The estimate is only ever fed to {@link BudgetPacer#forHorizon}, which is clamped so it can speed
 * a stream up but never slow one down. A wrong answer here therefore costs budget - bounded by
 * {@code TRIP_RESERVE} - and can never break a coverage guarantee. And with fewer than
 * {@value #MIN_SAMPLES} recorded sessions it returns 0, which means "use the ladder": a fresh
 * install behaves exactly as it does today until it has earned an opinion.
 */
public final class FreeDriveHorizon {

	private static final String PREF_HISTOGRAM = "cairodrive_freedrive_hist";

	/**
	 * Upper edges in minutes. Log-ish spacing because the interesting resolution is at the short
	 * end - the difference between a 20 and a 40 minute errand matters to pacing, the difference
	 * between 8 and 9 hours does not.
	 */
	private static final int[] EDGES = {15, 30, 45, 60, 90, 120, 180, 240, 360, 480, 720, 1440};

	/** Deliberately pessimistic: over-estimating remaining time under-spends, which is safe. */
	private static final int SAFE_QUANTILE_PCT = 75;
	/** Below this the histogram is noise and the ladder is the better answer. */
	private static final int MIN_SAMPLES = 6;
	/**
	 * Counts are halved when any bucket reaches this, which keeps the histogram a slow EWMA rather
	 * than a permanent record. Driving habits change; a year-old commute should not outvote this
	 * month's.
	 */
	private static final int DECAY_AT = 64;
	/** A gap longer than this ends the session - parked, not stopped at a light. */
	public static final long IDLE_GAP_MS = 10 * 60 * 1000L;

	private static volatile long sessionStartMs;
	private static volatile long lastFixMs;

	private FreeDriveHorizon() {
	}

	/**
	 * Call on every location update while NOT navigating.
	 *
	 * <p>Closes the previous session and records it when the gap since the last fix exceeds
	 * {@link #IDLE_GAP_MS}. Recording lazily on the next session's first fix rather than on a stop
	 * event means nothing is lost when the process is killed while parked, which is the normal way
	 * an Android app ends.
	 */
	public static void onFreeDriveFix(@Nullable OsmandApplication app, long nowMs) {
		try {
			long last = lastFixMs;
			if (last == 0 || nowMs - last > IDLE_GAP_MS) {
				if (last != 0 && sessionStartMs != 0) {
					int minutes = (int) ((last - sessionStartMs) / 60000L);
					if (minutes >= EDGES[0] / 2) {
						record(app, minutes);
					}
				}
				sessionStartMs = nowMs;
			}
			lastFixMs = nowMs;
		} catch (Throwable t) {
			// Pacing input only. Never let it touch the fix path.
		}
	}

	/** Minutes this free-driving session has been running, or 0 if none is open. */
	public static int elapsedMinutes(long nowMs) {
		long start = sessionStartMs;
		if (start == 0 || nowMs < start) {
			return 0;
		}
		return (int) ((nowMs - start) / 60000L);
	}

	/**
	 * Estimated minutes still to drive, or 0 when there is not enough history to say.
	 *
	 * <p>0 is not "the drive is over" - it is "no opinion", and every caller reads it as "use the
	 * ladder". That is the same contract {@link BudgetPacer#routeHorizonMinutes} uses when not
	 * navigating, so the two horizon sources are interchangeable at the call site.
	 */
	public static int estimateRemainingMinutes(@Nullable OsmandApplication app, long nowMs) {
		try {
			int elapsed = elapsedMinutes(nowMs);
			if (elapsed <= 0) {
				return 0;
			}
			int[] counts = load(app);
			if (counts == null) {
				return 0;
			}
			// Only sessions that lasted LONGER than the current one are evidence about what this
			// one might still do. Everything shorter has already been ruled out by the clock.
			int surviving = 0;
			for (int i = 0; i < EDGES.length; i++) {
				if (EDGES[i] > elapsed) {
					surviving += counts[i];
				}
			}
			if (surviving < MIN_SAMPLES) {
				return 0;
			}
			int target = (int) Math.ceil(surviving * (SAFE_QUANTILE_PCT / 100.0));
			int seen = 0;
			for (int i = 0; i < EDGES.length; i++) {
				if (EDGES[i] <= elapsed) {
					continue;
				}
				seen += counts[i];
				if (seen >= target) {
					return Math.max(0, EDGES[i] - elapsed);
				}
			}
			return Math.max(0, EDGES[EDGES.length - 1] - elapsed);
		} catch (Throwable t) {
			return 0;
		}
	}

	private static void record(@Nullable OsmandApplication app, int minutes) {
		int[] counts = load(app);
		if (counts == null) {
			return;
		}
		int bucket = EDGES.length - 1;
		for (int i = 0; i < EDGES.length; i++) {
			if (minutes <= EDGES[i]) {
				bucket = i;
				break;
			}
		}
		counts[bucket]++;
		if (counts[bucket] >= DECAY_AT) {
			for (int i = 0; i < counts.length; i++) {
				counts[i] /= 2;
			}
		}
		save(app, counts);
	}

	@Nullable
	private static int[] load(@Nullable OsmandApplication app) {
		if (app == null) {
			return null;
		}
		try {
			OsmandSettings settings = app.getSettings();
			SettingsAPI api = settings.getSettingsAPI();
			String raw = api.getString(settings.getPreferences(true), PREF_HISTOGRAM, "");
			int[] counts = new int[EDGES.length];
			if (raw == null || raw.isEmpty()) {
				return counts;
			}
			String[] parts = raw.split(",");
			for (int i = 0; i < counts.length && i < parts.length; i++) {
				try {
					counts[i] = Math.max(0, Integer.parseInt(parts[i].trim()));
				} catch (NumberFormatException e) {
					counts[i] = 0;
				}
			}
			return counts;
		} catch (Throwable t) {
			return null;
		}
	}

	private static void save(@Nullable OsmandApplication app, @NonNull int[] counts) {
		if (app == null) {
			return;
		}
		try {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < counts.length; i++) {
				if (i > 0) {
					sb.append(',');
				}
				sb.append(counts[i]);
			}
			OsmandSettings settings = app.getSettings();
			SettingsAPI api = settings.getSettingsAPI();
			api.edit(settings.getPreferences(true)).putString(PREF_HISTOGRAM, sb.toString())
					.commit();
		} catch (Throwable t) {
			// Losing a sample is free. Throwing here is not.
		}
	}

	/** One line for the drive log: what it knows and what it concluded. */
	@NonNull
	public static String describe(@Nullable OsmandApplication app, long nowMs) {
		int[] counts = load(app);
		int total = 0;
		if (counts != null) {
			for (int c : counts) {
				total += c;
			}
		}
		return String.format(Locale.US, "freeDrive elapsedMin=%d samples=%d estRemainMin=%d",
				elapsedMinutes(nowMs), total, estimateRemainingMinutes(app, nowMs));
	}
}
