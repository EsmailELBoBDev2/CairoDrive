package net.osmand.plus.cairodrive;

import android.content.Context;
import android.net.ConnectivityManager;

import androidx.annotation.NonNull;

import net.osmand.PlatformUtil;
import net.osmand.plus.BuildConfig;

import org.apache.commons.logging.Log;

/**
 * Metered-connection policy for the CairoDrive fork.
 *
 * <p>Upstream assumes a user who downloads maps at home on Wi-Fi and drives with the phone in
 * flight mode. This fork is driven on Egyptian mobile data, where a background transfer nobody
 * asked for shows up on a bill. {@code CAIRODRIVE_UNLOCK_PRO} makes that worse rather than
 * better: it hands the build the whole Pro tier, and the weather layers in particular fetch
 * raster tiles from OsmAnd's servers on their own as the map moves.
 *
 * <p>Two things are decided here, and they are deliberately different in kind:
 *
 * <ul>
 * <li>{@link #blocksBulkTransfer} is a hard stop. It refuses a transfer outright and is only
 * used where there is no per-item preference for the user to have set - currently the weather
 * tile fetch, which has no switch of its own at all.</li>
 * <li>{@link #wifiOnlyByDefault} only moves a <em>default</em>. Anywhere the user already has
 * an "only download over Wi-Fi" switch, the stored value still wins; this just decides what
 * that switch reads before it is ever touched.</li>
 * </ul>
 *
 * <p>"Metered" rather than "not Wi-Fi": {@code ConnectivityManager.isActiveNetworkMetered}
 * counts a phone's own tethering hotspot and a Wi-Fi network the user has flagged as metered,
 * both of which look like Wi-Fi to {@code OsmandSettings.isWifiConnected} and both of which
 * cost exactly as much as cellular. An unknown answer is treated as metered, because the
 * expensive mistake is the one that transfers.
 *
 * <p>Set {@code CAIRODRIVE_DATA_SAVER=false} to build stock behaviour. See
 * {@code OsmAnd/cairodrive.gradle}.
 */
public class CairoDriveDataSaver {

	private static final Log LOG = PlatformUtil.getLog(CairoDriveDataSaver.class);

	private CairoDriveDataSaver() {
	}

	/** True when this build keeps bulk transfers off a metered connection. */
	public static boolean isEnabled() {
		return BuildConfig.CAIRODRIVE_DATA_SAVER;
	}

	/**
	 * Whether an "only download over Wi-Fi" style preference should read as on before the user
	 * has ever touched it. Callers must pass this as the registration default and nothing more,
	 * so that a stored value keeps winning.
	 */
	public static boolean wifiOnlyByDefault() {
		return isEnabled();
	}

	/**
	 * Whether a transfer that the user did not explicitly ask for should be refused right now.
	 */
	public static boolean blocksBulkTransfer(@NonNull Context ctx) {
		return isEnabled() && isMetered(ctx);
	}

	private static volatile boolean lastVetoState;
	private static volatile boolean vetoEverReported;
	private static volatile long lastVetoLogAt;
	private static final long VETO_LOG_INTERVAL_MS = 60_000;

	/**
	 * Records what the tile gate decided, so a blank overlay is explained in the drive log instead
	 * of just being absent. Only writes a line when the answer CHANGES, or once a minute while it
	 * stays blocking - the gate is asked per tile, so an unconditional log would be a flood.
	 *
	 * @param allow what the gate returned; {@code false} means tiles are being refused.
	 */
	public static void noteTileVeto(boolean allow) {
		long now = System.currentTimeMillis();
		boolean changed = !vetoEverReported || allow != lastVetoState;
		if (!changed && (allow || now - lastVetoLogAt < VETO_LOG_INTERVAL_MS)) {
			return;
		}
		lastVetoState = allow;
		vetoEverReported = true;
		lastVetoLogAt = now;
		CairoDriveLogger.getInstance().log("CD_DATA",
				"tileGate=" + (allow ? "ALLOW" : "BLOCK metered")
						+ " saver=" + isEnabled());
	}

	/** Memoised: isActiveNetworkMetered() is a binder call, and the tile path can ask per tile. */
	private static volatile long meteredCheckedAt;
	private static volatile boolean meteredCached = true;
	private static final long METERED_CACHE_MS = 5000;

	public static boolean isMeteredCached(@NonNull Context ctx) {
		long now = System.currentTimeMillis();
		if (now - meteredCheckedAt > METERED_CACHE_MS) {
			meteredCached = isMetered(ctx);
			meteredCheckedAt = now;
		}
		return meteredCached;
	}

	public static boolean isMetered(@NonNull Context ctx) {
		try {
			ConnectivityManager manager =
					(ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
			return manager == null || manager.isActiveNetworkMetered();
		} catch (Exception e) {
			LOG.warn("Could not determine whether the active network is metered", e);
			return true;
		}
	}
}
