package net.osmand.plus.cairodrive;

import net.osmand.plus.BuildConfig;

/**
 * Feature switches for the CairoDrive fork.
 *
 * <p>Upstream gates a number of capabilities behind {@code isOsmAndProAvailable}, which covers
 * two quite different things: rendering that happens entirely on the device, and services that
 * OsmAnd hosts and pays for. CairoDrive enables the first group and leaves the second alone.
 *
 * <p>Enabled here (device-side only, no network service behind them):
 * <ul>
 *     <li>3D / terrain maps - relief rendering from terrain files the app already downloads</li>
 *     <li>Pro map widgets - additional readouts drawn from data the app already has</li>
 *     <li>Route coloring types - colouring an existing track by slope, altitude and so on</li>
 * </ul>
 *
 * <p>Deliberately NOT enabled, because they consume OsmAnd's own paid infrastructure rather
 * than unlocking local code: the weather forecast (forecast files served from OsmAnd's
 * servers) and OsmAnd Cloud backup (storage on OsmAnd's servers). Those are metered services,
 * not feature flags, and a fork switching them on would be drawing on someone else's hosting.
 * CairoDrive's own weather support is meant to come from an independent provider instead.
 *
 * <p>The value is a build config field so the behaviour is visible in the build rather than
 * buried in a conditional, and so a build can turn it off without touching source.
 * See {@code OsmAnd/cairodrive.gradle}.
 */
public class CairoDriveFeatures {

	private CairoDriveFeatures() {
	}

	/**
	 * True when the device-side capabilities listed above are unlocked without a subscription.
	 */
	public static boolean isClientSideProUnlocked() {
		return BuildConfig.CAIRODRIVE_UNLOCK_CLIENT_FEATURES;
	}
}
