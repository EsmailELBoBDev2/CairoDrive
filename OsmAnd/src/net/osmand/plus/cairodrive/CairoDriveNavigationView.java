package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;

import net.osmand.plus.BuildConfig;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.views.OsmandMapTileView;

/**
 * How the map camera behaves when navigation starts.
 *
 * <p>Upstream fits the whole route into the view when a route finishes calculating, which is
 * useful while planning and wrong the moment you are driving: the fit also calls
 * {@code setMapLinkedToLocation(false)}, so the map stops following the vehicle and stops
 * rotating to the direction of travel until the AUTO_FOLLOW_ROUTE timer expires. CairoDrive
 * wants what every other navigation app does - the camera already on the vehicle arrow, close
 * in, tilted, pointing where you are going, from the first frame.
 *
 * <p>Two separate things produce the symptom, and both are handled:
 * <ul>
 *     <li>the fit is scheduled with a 300 ms delay but its "am I still planning a route?"
 *     guard is evaluated when it is <em>scheduled</em>. Tapping Go inside that window lets
 *     the fit land after navigation has already started, undoing the driving camera. The
 *     guard is now re-checked when the fit actually runs.</li>
 *     <li>navigation start restores the last known elevation angle, which is flat for anyone
 *     who has never manually tilted the map, so the drive begins in 2D. It now falls back to
 *     the configured 3D angle instead.</li>
 * </ul>
 *
 * <p>The route overview during planning is deliberately left alone - seeing the route before
 * committing to it is the point of that screen.
 *
 * <p>Set {@code CAIRODRIVE_DRIVING_VIEW=false} at build time for stock behaviour.
 * See {@code OsmAnd/cairodrive.gradle}.
 */
public class CairoDriveNavigationView {

	private CairoDriveNavigationView() {
	}

	/** True when navigation should begin in the close 3D follow view. */
	public static boolean isEnabled() {
		return BuildConfig.CAIRODRIVE_DRIVING_VIEW;
	}

	/**
	 * Elevation angle to start navigating with.
	 *
	 * <p>{@code getLastKnownMapElevation()} is 90 - flat - until the user tilts the map by
	 * hand, so honouring it means most drives start in 2D. When it is flat this substitutes
	 * the same angle the auto-zoom code uses for its 3D view, which is a real preference the
	 * user can change under Map during navigation.
	 *
	 * @param stored the angle upstream was about to apply
	 */
	public static float startingElevationAngle(@NonNull OsmandSettings settings, float stored) {
		if (!isEnabled() || stored != OsmandMapTileView.DEFAULT_ELEVATION_ANGLE) {
			return stored;
		}
		float configured = settings.AUTO_ZOOM_3D_ANGLE.get();
		// Guard the preference rather than trusting it: an out-of-range angle would either do
		// nothing visible or tip the camera past the horizon.
		if (configured < OsmandMapTileView.MIN_ALLOWED_ELEVATION_ANGLE
				|| configured > OsmandMapTileView.DEFAULT_ELEVATION_ANGLE) {
			return stored;
		}
		return configured;
	}
}
