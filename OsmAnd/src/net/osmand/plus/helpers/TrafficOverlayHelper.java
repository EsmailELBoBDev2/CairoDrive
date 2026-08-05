package net.osmand.plus.helpers;

import net.osmand.IndexConstants;
import net.osmand.PlatformUtil;
import net.osmand.map.TileSourceManager.TileSourceTemplate;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.views.OsmandMap;
import net.osmand.plus.R;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;

import java.io.File;

/**
 * Installs "TomTom Traffic (flow)" and "HERE Traffic (flow)" raster overlay tile sources when the
 * matching key is present in the build, so live congestion colors can be layered over the offline
 * map. No key = nothing installed, app stays fully offline.
 *
 * The overlay is installed but never SELECTED for the user: a selected flow overlay decodes a
 * bitmap per tile into native memory and refreshes every 3 minutes across the whole visible map,
 * which is the single largest runtime cost in this stack. Selecting it is a deliberate opt-in via
 * Plugins > Online maps (the raster-maps plugin is not enabled by default, so the "Overlay map"
 * row is absent until it is), then Configure map > Overlay map.
 *
 * Installs run once per app run, off the UI thread (disk writes in onResume were audit-flagged),
 * and only when the source is not already installed - so user edits to an installed source are
 * never silently reverted on the next app start.
 */
public class TrafficOverlayHelper {

	private static final Log log = PlatformUtil.getLog(TrafficOverlayHelper.class);

	// Flow tiles below city zoom are unreadable clutter and wasted (3-min-refreshing) requests.
	private static final int TRAFFIC_MIN_ZOOM = 7;
	// OsmAnd's untouched overlay transparency default. Exactly this value means "never touched";
	// any other value is a user choice and is left alone.
	private static final int UNTOUCHED_OVERLAY_ALPHA = 100;
	private static final int READABLE_OVERLAY_ALPHA = 220;

	private static boolean installedThisRun;

	private TrafficOverlayHelper() {
	}

	public static void installTrafficOverlaySource(OsmandApplication app) {
		// Deliberately OUTSIDE the once-per-run latch: the visibility fix has to fire on the resume
		// after the user first selects the overlay, not only on the run that installed the sources.
		// Two cached preference reads - cheap enough for every onResume.
		ensureTrafficOverlayReadable(app);
		if (installedThisRun) {
			return;
		}
		installedThisRun = true;
		Thread t = new Thread(() -> {
			android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
			// TomTom is the primary traffic overlay: 50,000 free tile requests/day (vs Azure's
			// 5,000/MONTH, which one drive would exhaust - and Azure just resells TomTom data
			// anyway). Installs only if the key is present. 3-min expiry = industry freshness.
			String tomtomKey = app.getString(R.string.tomtom_routing_api_key);
			if (!Algorithms.isEmpty(tomtomKey)) {
				install(app, "TomTom Traffic (flow)",
						"https://api.tomtom.com/traffic/map/4/tile/flow/relative0/{0}/{1}/{2}.png?key="
								+ tomtomKey + "&thickness=10", 3);
			}
			// HERE is a genuinely INDEPENDENT source (own map/probes, not TomTom-reheated) and
			// covers Cairo - a real second opinion. Its freemium budget is far smaller
			// (~250k transactions/MONTH), so its tiles refresh at 10 min instead of 3.
			String hereKey = app.getString(R.string.here_api_key);
			if (!Algorithms.isEmpty(hereKey)) {
				install(app, "HERE Traffic (flow)",
						"https://traffic.maps.hereapi.com/v3/flow/mc/{0}/{1}/{2}/256/png?apiKey=" + hereKey, 10);
			}
		}, "cairo-tile-sources");
		t.setPriority(Thread.MIN_PRIORITY);
		t.start();
	}

	/**
	 * Makes an already-selected traffic overlay actually visible. OsmAnd's default overlay
	 * transparency is 100/255 (~40%), at which the flow colors are nearly invisible over the base
	 * map (owner field report: "no congestion showing"). Flow tiles are mostly empty pixels with
	 * colored road lines, so near-opaque is safe and readable.
	 *
	 * No-op unless OUR overlay is the selected one and its alpha is still the untouched default.
	 */
	public static void ensureTrafficOverlayReadable(OsmandApplication app) {
		try {
			String current = app.getSettings().MAP_OVERLAY.get();
			if (Algorithms.isEmpty(current)
					|| !(current.startsWith("TomTom Traffic") || current.startsWith("HERE Traffic"))) {
				return;
			}
			if (app.getSettings().MAP_OVERLAY_TRANSPARENCY.get() != UNTOUCHED_OVERLAY_ALPHA) {
				return;
			}
			app.getSettings().MAP_OVERLAY_TRANSPARENCY.set(READABLE_OVERLAY_ALPHA);
			log.info("Traffic overlay alpha bumped 100 -> 220 (visibility fix)");
			app.runInUIThread(() -> {
				OsmandMap map = app.getOsmandMap();
				if (map != null) {
					map.refreshMap();
				}
			});
		} catch (Throwable t) {
			log.error("Traffic overlay visibility fix failed", t);
		}
	}

	private static void install(OsmandApplication app, String name, String url, int expiryMinutes) {
		try {
			// Already installed (this or an earlier run): leave it alone - reinstalling would
			// overwrite the metainfo and revert any user edits to the source.
			File metainfo = new File(new File(app.getAppPath(IndexConstants.TILES_INDEX_DIR), name), ".metainfo");
			if (metainfo.exists()) {
				return;
			}
			TileSourceTemplate template = new TileSourceTemplate(name, url, ".png", 19, TRAFFIC_MIN_ZOOM, 256, 16, 18000);
			template.setExpirationTimeMinutes(expiryMinutes); // traffic goes stale fast - keep the overlay live
			app.getSettings().installTileSource(template);
			log.info("Traffic overlay installed: " + name);
		} catch (Throwable t) {
			log.error("Traffic overlay install failed: " + name, t);
		}
	}
}
