package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Attributes the {@code over} bucket of {@code CD_FRAME} to individual map layers.
 *
 * <p>Why this exists: the 2026-08-04 drive produced the first frame data this project has ever had
 * - 32,200 frames with the head unit actually connected - and it inverted the plan. The split was
 *
 * <pre>over 25.9 ms (61%) | blit 9.2 (22%) | read 4.5 (11%) | lock 1.6 | post 1.0 | wdgt 0.1</pre>
 *
 * against a 46.9 ms average frame and a 20 fps self-imposed cap. CLAUDE.md's decision table says a
 * read/blit-dominant split means the VirtualDisplay rewrite; it is not read/blit-dominant. It is
 * {@code over} - this app's own Java overlay drawing - by a factor of two.
 *
 * <p>And the nine long-deferred performance findings are not the explanation: measured statically
 * they come to 0.2-0.4% of a frame combined. So 25.9 ms is going somewhere that has never been
 * looked at. With 23 registered layers that is an average of ~1.1 ms each, but averages are exactly
 * what hides a single expensive layer - which is why this measures per layer instead of reasoning
 * about it. Standing rule in this project: measure before optimising. Two things were nearly
 * optimised blind here and both would have been wrong.
 *
 * <p>Deliberately cheap: two {@code System.nanoTime()} calls and a {@code HashMap} put per layer
 * per frame, only while the car surface is the draw target, and only a summary line every
 * {@link #FRAMES_PER_WINDOW} frames rather than per-frame output. It reports the worst offenders
 * rather than all 23, because the point is to find the one to fix.
 *
 * <p>Remove this once the answer is known and acted on - it is a probe, not a feature.
 */
public class CairoDriveLayerTiming {

	private static final int FRAMES_PER_WINDOW = 200;

	/** Only layers averaging at least this many microseconds are worth naming. */
	private static final long REPORT_THRESHOLD_US = 200;

	private static final int MAX_REPORTED = 6;

	private static final Map<String, long[]> TOTALS = new HashMap<>();
	private static int frames;
	private static long frameTotalNs;

	public static boolean isEnabled(boolean carView) {
		return carView && CairoDriveLogger.isEnabled();
	}

	public static synchronized void record(@NonNull Object layer, long elapsedNs) {
		String name = layer.getClass().getSimpleName();
		long[] slot = TOTALS.get(name);
		if (slot == null) {
			slot = new long[2];
			TOTALS.put(name, slot);
		}
		slot[0] += elapsedNs;
		slot[1]++;
		frameTotalNs += elapsedNs;
	}

	public static synchronized void endFrame() {
		if (++frames < FRAMES_PER_WINDOW) {
			return;
		}
		List<Map.Entry<String, long[]>> entries = new ArrayList<>(TOTALS.entrySet());
		entries.sort((a, b) -> Long.compare(b.getValue()[0], a.getValue()[0]));

		StringBuilder sb = new StringBuilder();
		sb.append("frames=").append(frames)
				.append(" totalPerFrameUs=").append(frameTotalNs / 1000 / frames);
		int reported = 0;
		for (Map.Entry<String, long[]> e : entries) {
			long perFrameUs = e.getValue()[0] / 1000 / frames;
			if (perFrameUs < REPORT_THRESHOLD_US || reported >= MAX_REPORTED) {
				break;
			}
			sb.append(' ').append(e.getKey()).append('=').append(perFrameUs);
			reported++;
		}
		if (reported == 0) {
			sb.append(" (no layer above ").append(REPORT_THRESHOLD_US).append("us)");
		}
		CairoDriveLogger.getInstance().log("CD_LAYER", sb.toString());

		TOTALS.clear();
		frames = 0;
		frameTotalNs = 0;
	}

	@NonNull
	public static synchronized String snapshot() {
		return String.format(Locale.US, "frames=%d layers=%d", frames, TOTALS.size());
	}
}
