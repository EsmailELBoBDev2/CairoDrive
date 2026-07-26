package net.osmand.plus.cairodrive;

import net.osmand.plus.BuildConfig;

/**
 * Feature switches for the CairoDrive fork.
 *
 * <p>Upstream already ships a build with the whole Pro tier unlocked and no subscription:
 * {@code InAppPurchaseUtils.isOsmAndProAvailable} succeeds for any build that
 * {@code Version.isDeveloperBuild} recognises, which is any build whose displayed name
 * contains a tilde - the OsmAnd~ package published on F-Droid by the OsmAnd project itself.
 * CairoDrive takes the same position through an explicit switch rather than by smuggling a
 * tilde into its product name.
 *
 * <p>This unlocks the weather forecast, OsmAnd Cloud, 3D and terrain maps, the Pro map
 * widgets and the route colouring types. The older OsmAnd+ tier - contour lines, depth
 * contours, astronomy, unlimited downloads - needs nothing here: {@code Version.isFreeVersion}
 * only recognises the two net.osmand package names, so the CairoDrive application id already
 * reads as a paid version.
 *
 * <p>Two things this does not do. OsmAnd Cloud still needs an OsmAnd account to sign in to,
 * so unlocking the gate removes the paywall screen but does not conjure storage. And the
 * weather forecast still downloads its data from OsmAnd's servers, which is worth remembering
 * when deciding how widely to distribute a build with this enabled.
 *
 * <p>The value is a build config field so what a build enables is visible in the build rather
 * than buried in a conditional, and so {@code CAIRODRIVE_UNLOCK_PRO=false} produces stock
 * behaviour without editing source. See {@code OsmAnd/cairodrive.gradle}.
 */
public class CairoDriveFeatures {

	private CairoDriveFeatures() {
	}

	/**
	 * True when the Pro tier is available without a subscription, as in upstream's own
	 * OsmAnd~ build.
	 */
	public static boolean isProUnlocked() {
		return BuildConfig.CAIRODRIVE_UNLOCK_PRO;
	}
}
