package net.osmand.plus.notifications;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat.Builder;
import androidx.core.app.NotificationManagerCompat;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.auto.CarAppNotification;
import net.osmand.plus.notifications.OsmandNotification.NotificationType;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.List;

public class NotificationHelper {

	public static final Log LOG = PlatformUtil.getLog(NotificationHelper.class);

	public static final String NOTIFICATION_CHANEL_ID = "osmand_background_service";
	private final OsmandApplication app;

	private NavigationNotification navigationNotification;
	private GpxNotification gpxNotification;
	private AisNotification aisNotification;
	private CarAppNotification carAppNotification;
	private DownloadNotification downloadNotification;
	private FallbackNotification fallbackNotification;
	private final List<OsmandNotification> all = new ArrayList<>();

	public NotificationHelper(@NonNull OsmandApplication app) {
		this.app = app;
		init();
	}

	private void init() {
		navigationNotification = new NavigationNotification(app);
		gpxNotification = new GpxNotification(app);
		aisNotification = new AisNotification(app);
		downloadNotification = new DownloadNotification(app);
		carAppNotification = new CarAppNotification(app);
		fallbackNotification = new FallbackNotification(app);
		all.add(navigationNotification);
		all.add(gpxNotification);
		all.add(aisNotification);
		all.add(downloadNotification);
		all.add(carAppNotification);
	}

	@Nullable
	public Notification buildTopNotification(@NonNull Service service, @NonNull NotificationType type) {
		List<OsmandNotification> notifications = acquireTopNotifications(service);
		for (OsmandNotification notification : notifications) {
			if (notification.getType() == type) {
				Notification topNotification = buildTopNotification(service, notification);
				if (topNotification != null) {
					return topNotification;
				}
			}
		}
		for (OsmandNotification notification : notifications) {
			if (notification.getType() != type) {
				Notification topNotification = buildTopNotification(service, notification);
				if (topNotification != null) {
					return topNotification;
				}
			}
		}
		return null;
	}

	@Nullable
	private Notification buildTopNotification(@NonNull Service service, @NonNull OsmandNotification notification) {
		removeNotification(notification.getType());
		setTopNotification(notification);
		Builder notificationBuilder = notification.buildNotification(service, false);
		if (notificationBuilder != null) {
			return notificationBuilder.build();
		}
		return null;
	}

	@NonNull
	public Notification buildDownloadNotification() {
		return downloadNotification.buildNotification(null, false).build();
	}

	@NonNull
	public Notification buildCarAppNotification() {
		return carAppNotification.buildNotification(null, false).build();
	}

	@NonNull
	public Notification buildFallbackNotification() {
		return fallbackNotification.buildNotification(null, false).build();
	}

	@NonNull
	private List<OsmandNotification> acquireTopNotifications(@Nullable Service service) {
		List<OsmandNotification> res = new ArrayList<>();
		if (navigationNotification.isEnabled(service)) {
			res.add(navigationNotification);
		}
		if (gpxNotification.isEnabled(service)) {
			res.add(gpxNotification);
		}
		if (aisNotification.isEnabled(service)) {
			res.add(aisNotification);
		}
		return res;
	}

	public void resetTopNotification() {
		for (OsmandNotification n : all) {
			n.setTop(false);
		}
	}

	public void updateTopNotification() {
		List<OsmandNotification> notifications = acquireTopNotifications(null);
		if (!notifications.isEmpty()) {
			setTopNotification(notifications.get(0));
		}
	}

	private void setTopNotification(OsmandNotification notification) {
		for (OsmandNotification n : all) {
			n.setTop(n == notification);
		}
	}

	public void showNotifications() {
		if (!hasAnyTopNotification()) {
			removeTopNotification();
		}
		for (OsmandNotification notification : all) {
			notification.showNotification();
		}
	}

	/**
	 * D7. Coalesced, because the caller is on the per-fix path.
	 *
	 * <p>{@code SavingTrackHelper.updateLocation} calls this for {@link NotificationType#GPX}
	 * after EVERY recorded point - roughly once a second while recording, and more when the
	 * logging interval is tightened. Each call rebuilds a NotificationCompat.Builder and crosses
	 * the binder to NotificationManager, for a notification whose text a driver is not reading
	 * while the phone is projecting to the car.
	 *
	 * <p>This was previously written off as not reproducing, on the basis that SavingTrackHelper
	 * did not call it. It does - one line, right after insertData. The throttle already existed on
	 * {@link #refreshNotifications()}, the plural sweep, and simply had not been applied to the
	 * singular form, which is the one on the hot path.
	 *
	 * <p>Throttled PER TYPE, not globally: a rate-limited GPX refresh must not delay a navigation
	 * notification arriving in the same window. The trailing call is what makes it safe to drop
	 * the intermediate ones - the final state is always delivered, just once.
	 */
	public void refreshNotification(NotificationType notificationType) {
		long now = android.os.SystemClock.elapsedRealtime();
		Long last = lastTypeRefreshMs.get(notificationType);
		long since = last == null ? Long.MAX_VALUE : now - last;
		if (since < REFRESH_MIN_INTERVAL_MS) {
			if (pendingTypes.add(notificationType)) {
				app.runInUIThread(() -> {
					pendingTypes.remove(notificationType);
					refreshNotification(notificationType);
				}, REFRESH_MIN_INTERVAL_MS - since);
			}
			return;
		}
		lastTypeRefreshMs.put(notificationType, now);
		for (OsmandNotification notification : all) {
			if (notification.getType() == notificationType) {
				notification.refreshNotification();
				break;
			}
		}
	}

	/** Main-thread only, like every other caller here, so plain collections are correct. */
	private final java.util.Map<NotificationType, Long> lastTypeRefreshMs =
			new java.util.EnumMap<>(NotificationType.class);
	private final java.util.Set<NotificationType> pendingTypes =
			java.util.EnumSet.noneOf(NotificationType.class);

	public void onNotificationDismissed(NotificationType notificationType) {
		for (OsmandNotification notification : all) {
			if (notification.getType() == notificationType) {
				notification.onNotificationDismissed();
				break;
			}
		}
	}

	public int getOsmandNotificationId(NotificationType notificationType) {
		for (OsmandNotification notification : all) {
			if (notification.getType() == notificationType) {
				return notification.getOsmandNotificationId();
			}
		}
		return -1;
	}

	public boolean hasAnyTopNotification() {
		for (OsmandNotification notification : all) {
			if (notification.isTop()) {
				return true;
			}
		}
		return false;
	}

	/**
	 * D7. Coalesces refreshes so a burst cannot become a burst of notification rebuilds.
	 *
	 * <p>The original finding claimed the recording path did "2 builds + 2 notify() per recorded
	 * point". That was checked and does not reproduce in this tree - {@code SavingTrackHelper}
	 * never calls this, so nothing fires per GPS point. The finding was either about an older
	 * version or simply wrong, and it was recorded as not-reproducing rather than fixed.
	 *
	 * <p>The throttle goes in anyway, because the two things are separable: the CLAIM was
	 * unverified, but the HAZARD is real and cheap to close. Every notification here rebuilds a
	 * RemoteViews and crosses into NotificationManagerService, and this method is reachable from
	 * several event paths whose rates nobody controls. A caller added later that does fire per fix
	 * would reintroduce the exact problem, silently, on a device already at 46.9 ms per frame.
	 *
	 * <p>Deliberately NOT a drop: the last suppressed refresh is remembered and replayed, so a
	 * state change arriving inside the window still reaches the notification a moment later rather
	 * than being lost. Dropping would be the wrong trade - a stale "recording" notification is
	 * worse than a slightly late one.
	 */
	private static final long REFRESH_MIN_INTERVAL_MS = 500;
	private long lastRefreshMs;
	private boolean refreshPending;

	public void refreshNotifications() {
		long now = android.os.SystemClock.elapsedRealtime();
		long since = now - lastRefreshMs;
		if (since < REFRESH_MIN_INTERVAL_MS) {
			if (!refreshPending) {
				refreshPending = true;
				app.runInUIThread(() -> {
					refreshPending = false;
					refreshNotifications();
				}, REFRESH_MIN_INTERVAL_MS - since);
			}
			return;
		}
		lastRefreshMs = now;
		if (!hasAnyTopNotification()) {
			removeTopNotification();
		}
		for (OsmandNotification notification : all) {
			notification.refreshNotification();
		}
	}

	public void removeTopNotification() {
		NotificationManagerCompat notificationManager = NotificationManagerCompat.from(app);
		notificationManager.cancel(OsmandNotification.TOP_NOTIFICATION_SERVICE_ID);
	}

	public void removeNotification(NotificationType notificationType) {
		for (OsmandNotification notification : all) {
			if (notification.getType() == notificationType) {
				notification.removeNotification();
				break;
			}
		}
	}

	public void removeNotifications(boolean inactiveOnly) {
		for (OsmandNotification notification : all) {
			if (!inactiveOnly || !notification.isEnabled()) {
				notification.removeNotification();
			}
		}
	}

	@TargetApi(26)
	public void createNotificationChannel() {
		if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
			NotificationChannel channel = new NotificationChannel(NOTIFICATION_CHANEL_ID,
					app.getString(R.string.osmand_service), NotificationManager.IMPORTANCE_LOW);
			channel.enableVibration(false);
			channel.setDescription(app.getString(R.string.osmand_service_descr));
			NotificationManagerCompat.from(app).createNotificationChannel(channel);
		}
	}
}
