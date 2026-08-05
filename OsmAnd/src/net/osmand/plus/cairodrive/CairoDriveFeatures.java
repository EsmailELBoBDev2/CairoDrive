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

	/**
	 * N4. How far ahead the position marker is projected along the route, as a percentage of the
	 * distance covered in the last fix interval. 0 is upstream behaviour.
	 *
	 * <p><b>N4 was written down as "raise the GNSS fix rate", and that turned out to be the wrong
	 * diagnosis.</b> Nothing needed raising: the AOSP helper already requests
	 * {@code requestLocationUpdates(provider, 0, 0)} - unthrottled - and the Play build's fused
	 * request is already {@code PRIORITY_HIGH_ACCURACY} on a 100 ms interval, ten times faster
	 * than the hardware delivers. Asking harder was never going to help.
	 *
	 * <p>The lag comes from upstream's animation contract instead. {@code ANIMATE_MY_LOCATION}
	 * animates the marker from the previous fix to the CURRENT one over the interval between
	 * them, so it arrives exactly as the next fix lands - smooth, and permanently one fix behind
	 * reality. Upstream also ships the cure, {@code RoutingHelperUtils.predictLocations}, and then
	 * defaults its gate to 0. The feature was complete and switched off.
	 *
	 * <p>The default is 90, simulated rather than guessed - see
	 * {@code tools/sim_location_interpolation.py}. The first guess was 50, on the reasoning that
	 * prediction overshoots under braking and Cairo is stop-go; steady cruise refutes it, because
	 * there 100 is exact and 50 sits 8.35 m behind. Halving the projection does not halve the
	 * error, it just keeps the arrow behind rather than ahead. 90 is the robust point: within a
	 * fraction of optimal whether overshoot is weighted equally with lag or ten times worse, where
	 * 100 degrades sharply as soon as overshoot is penalised at all.
	 *
	 * <p>This returns the DEFAULT only. Settings &gt; profile &gt; Position animation exposes the
	 * same preference, so it can be tuned or zeroed mid-drive without a rebuild.
	 *
	 * @return 0-100, validated at build time by cairodrive.gradle
	 */
	public static int getLocationInterpolationPercent() {
		return BuildConfig.CAIRODRIVE_LOCATION_INTERPOLATION;
	}
}
