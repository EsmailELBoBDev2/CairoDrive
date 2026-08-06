package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.router.RouteCalculationProgress;
import net.osmand.router.RouteCalculationProgress.HHIteration;

/**
 * Attributes an offline route calculation to the native engine's own phases, WITHOUT touching C++.
 *
 * <h3>The one number this exists to produce</h3>
 *
 * A four-agent investigation on 2026-08-06 concluded that the 4-8 s offline calculation is
 * dominated by FIXED per-query cost rather than route length, and named a specific suspect:
 * {@code initHHPoints} / {@code readPointBox} read the ENTIRE Highway-Hierarchy point index for
 * the region on every single calculation, with a heap allocation per point, and nothing caches it
 * between calls. If that is where the seconds are, the only fix is a native change. If it is
 * 400 ms of a 6 s search, the same native change buys under 7% and is not worth its risk.
 *
 * <p>That native change was designed and then REJECTED - an adversarial review found four ways it
 * would produce a silently wrong route, and this fork's standard is that a wrong route is far
 * worse than a slow one. But the review also pointed out that the prize can be priced before
 * anything is paid for it, which is what this class does.
 *
 * <h3>Why sampling works here, and why it needs no C++</h3>
 *
 * The native engine already reports its current phase live: {@code java_wrap.cpp} writes
 * {@link RouteCalculationProgress#hhIterationStep} through JNI as the calculation proceeds, and
 * the enum is {@code SELECT_REGIONS, LOAD_POINTS, START_END_POINT, ROUTING, DETAILED,
 * RECALCULATION}. So a reader that samples that field on a timer can attribute wall-clock to each
 * phase to within its sampling interval - no native build, no patch to a pinned external repo.
 *
 * <p>The existing progress poller cannot be used: {@code updateProgressWithDelay} only re-arms
 * while a listener is attached, and a deviation reroute frequently has none, so on exactly the
 * calculations that matter it would not run.
 *
 * <h3>Cost and safety</h3>
 *
 * One daemon thread per calculation, reading a single {@code int} every 40 ms and incrementing a
 * long. It writes nothing the router reads, holds no lock the router takes, and cannot fail the
 * calculation: every path is wrapped and returns silently. The thread is bounded by the
 * calculation - {@link #stop} joins it briefly - and is a daemon so a leaked one cannot hold the
 * process open.
 *
 * <p>Sampling is a lower bound on resolution, not on truth: a phase shorter than 40 ms may be
 * missed entirely. That is acceptable because the question is "which phase owns SECONDS", and a
 * phase that never shows up in a sample did not own seconds.
 */
public final class CairoDriveRoutePhases {

	/** NO "CD_" prefix: the logger adds it. */
	private static final String TRACE_TAG = "ROUTE_PHASE";

	private static final long SAMPLE_INTERVAL_MS = 40;

	private final RouteCalculationProgress progress;
	private final long[] millisPerPhase;
	private final Thread sampler;
	private volatile boolean running = true;

	private CairoDriveRoutePhases(@NonNull RouteCalculationProgress progress) {
		this.progress = progress;
		this.millisPerPhase = new long[HHIteration.values().length];
		this.sampler = new Thread(this::sampleLoop, "cd-route-phases");
		this.sampler.setDaemon(true);
		// Below the calculation, which is itself below the UI. This must never take CPU from the
		// thing it is measuring - a sampler that changes the number it reports is worthless.
		this.sampler.setPriority(Thread.MIN_PRIORITY);
	}

	/** @return a sampler to pass to {@link #stop}, or null if sampling is unavailable. */
	@Nullable
	public static CairoDriveRoutePhases start(@Nullable RouteCalculationProgress progress) {
		if (progress == null) {
			return null;
		}
		try {
			CairoDriveRoutePhases p = new CairoDriveRoutePhases(progress);
			p.sampler.start();
			return p;
		} catch (Throwable t) {
			// A measurement must never cost a route.
			return null;
		}
	}

	private void sampleLoop() {
		long last = System.currentTimeMillis();
		while (running) {
			try {
				Thread.sleep(SAMPLE_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return;
			}
			try {
				long now = System.currentTimeMillis();
				int step = progress.getHHIterationStep();
				if (step >= 0 && step < millisPerPhase.length) {
					// Attribute the whole elapsed slice to the phase seen at its END. Over a
					// multi-second phase the boundary error is one interval.
					millisPerPhase[step] += now - last;
				}
				last = now;
			} catch (Throwable t) {
				return;
			}
		}
	}

	/**
	 * Stop sampling and return a log fragment, or "" if nothing was attributed.
	 *
	 * <p>Only phases that actually accumulated time are named, so a line stays readable and the
	 * dominant phase is the one to look at.
	 */
	@NonNull
	public String stop() {
		running = false;
		try {
			sampler.join(200);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		try {
			StringBuilder sb = new StringBuilder();
			long total = 0;
			for (long ms : millisPerPhase) {
				total += ms;
			}
			if (total <= 0) {
				return "";
			}
			HHIteration[] phases = HHIteration.values();
			for (int i = 0; i < millisPerPhase.length; i++) {
				if (millisPerPhase[i] > 0) {
					sb.append(' ').append(phases[i].name().toLowerCase()).append('=')
							.append(millisPerPhase[i]);
				}
			}
			return sb.toString();
		} catch (Throwable t) {
			return "";
		}
	}

	/** One line naming the phase that owned the calculation, which is the decision this feeds. */
	public void log() {
		try {
			String breakdown = stop();
			if (breakdown.isEmpty()) {
				return;
			}
			int worst = 0;
			for (int i = 1; i < millisPerPhase.length; i++) {
				if (millisPerPhase[i] > millisPerPhase[worst]) {
					worst = i;
				}
			}
			CairoDriveLog.log(TRACE_TAG, "dominant=" + HHIteration.values()[worst].name()
					+ breakdown);
		} catch (Throwable t) {
			// ignored - this is a measurement, never a dependency
		}
	}
}
