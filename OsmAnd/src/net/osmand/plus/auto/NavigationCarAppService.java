package net.osmand.plus.auto;

import android.Manifest;
import android.app.Notification;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.car.app.CarAppService;
import androidx.car.app.Session;
import androidx.car.app.validation.HostValidator;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.plus.notifications.OsmandNotification.NotificationType;

import java.util.Arrays;
import java.util.List;

/**
 * Entry point for the templated app.
 *
 * <p>{@link CarAppService} is the main interface between the app and the car host. For more
 * details, see the <a href="https://developer.android.com/training/cars/navigation">Android for
 * Cars Library developer guide</a>.
 */
public final class NavigationCarAppService extends CarAppService implements ActivityCompat.OnRequestPermissionsResultCallback {

	private static final org.apache.commons.logging.Log LOG = PlatformUtil.getLog(NavigationCarAppService.class);
	/**
	 * CD_ is written here rather than passed to {@code CairoDriveLog}, which prepends its own.
	 * {@link CairoDriveLogger#log} takes the tag verbatim.
	 */
	private static final String DEVICE_TAG = "CD_DEVICE";
	private boolean foreground = false;
	/** Monotonic, so the session length survives a clock correction from the head unit. */
	private long sessionStartedAtMs;

	private OsmandApplication getApp() {
		return (OsmandApplication) getApplication();
	}

	/**
	 * Create a deep link URL from the given deep link action.
	 */
	@NonNull
	public static Uri createDeepLinkUri(@NonNull String deepLinkAction) {
		return Uri.fromParts(NavigationSession.URI_SCHEME, NavigationSession.URI_HOST, deepLinkAction);
	}

	@Override
	public int onStartCommand(Intent intent, int flags, int startId) {
		int result = super.onStartCommand(intent, flags, startId);
		getApp().setNavigationCarAppService(this);
		return result;
	}

	@Override
	public void onCreate() {
		super.onCreate();
		getApp().setCarAppPermissionListener(this);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		getApp().setCarAppPermissionListener(null);
		getApp().setNavigationCarAppService(null);
	}

	@Override
	@NonNull
	public Session onCreateSession() {
		startForegroundWithPermission();
		NavigationSession session = new NavigationSession();
		// When the head unit actually attached and detached, which nothing recorded.
		//
		// Everything this project reads out of a drive log is scoped to the projected session -
		// CD_FRAME only exists while the car surface does, and CD_PRESENT's fallback decision is
		// taken once per connect. Without these two lines the boundaries have to be inferred from
		// where CD_FRAME starts and stops, which cannot distinguish "the cable came out" from
		// "the renderer stopped producing frames" - opposite conclusions about the same silence.
		sessionStartedAtMs = SystemClock.elapsedRealtime();
		logCar("androidAuto CONNECTED uptimeMs=" + sessionStartedAtMs
				+ " foreground=" + foreground);
		session.getLifecycle()
				.addObserver(new DefaultLifecycleObserver() {
					@Override
					public void onDestroy(@NonNull LifecycleOwner owner) {
						foreground = false;
						logCar("androidAuto DISCONNECTED sessionMs="
								+ (SystemClock.elapsedRealtime() - sessionStartedAtMs));
						stopForeground(STOP_FOREGROUND_REMOVE);
					}
				});

		return session;
	}

	/** Guarded: a diagnostic must never be what stops the car session being created or torn down. */
	private static void logCar(@NonNull String message) {
		try {
			CairoDriveLogger.getInstance().log(DEVICE_TAG, message);
		} catch (Throwable ignored) {
		}
	}

	private void startForegroundWithPermission() {
		if (!foreground && OsmAndLocationProvider.isLocationPermissionAvailable(getApp())) {
			Notification notification = getApp().getNotificationHelper().buildCarAppNotification();
			try {
				if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
					startForeground(getApp().getNotificationHelper().getOsmandNotificationId(NotificationType.CAR_APP), notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION);
				} else {
					startForeground(getApp().getNotificationHelper().getOsmandNotificationId(NotificationType.CAR_APP), notification);
				}
				foreground = true;
			} catch (SecurityException e) {
				try {
					startForeground(getApp().getNotificationHelper().getOsmandNotificationId(NotificationType.CAR_APP), notification);
					foreground = true;
				} catch (SecurityException e2) {
					LOG.error("Can't startForegroundWithPermission");
				}
			}
		}
	}

	public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
		List<String> permissionsList = Arrays.asList(permissions);
		if (getApp().getCarNavigationSession() != null && permissionsList.contains(Manifest.permission.ACCESS_FINE_LOCATION) ||
				permissionsList.contains(Manifest.permission.ACCESS_COARSE_LOCATION)) {
			startForegroundWithPermission();
		}
	}

	@NonNull
	@Override
	public HostValidator createHostValidator() {
		if ((getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) {
			return HostValidator.ALLOW_ALL_HOSTS_VALIDATOR;
		} else {
			return new HostValidator.Builder(getApplicationContext())
					.addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
					.build();
		}
	}
}
