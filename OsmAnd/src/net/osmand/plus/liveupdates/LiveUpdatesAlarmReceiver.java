package net.osmand.plus.liveupdates;

import static net.osmand.plus.liveupdates.LiveUpdatesHelper.LOCAL_INDEX_INFO;
import static net.osmand.plus.liveupdates.LiveUpdatesHelper.preferenceDownloadViaWiFi;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmAndTaskManager;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.cairodrive.CairoDriveDataSaver;
import net.osmand.plus.settings.backend.OsmandSettings;

import org.apache.commons.logging.Log;

public class LiveUpdatesAlarmReceiver extends BroadcastReceiver {

	private static final Log LOG = PlatformUtil.getLog(LiveUpdatesAlarmReceiver.class);

	@Override
	public void onReceive(Context context, Intent intent) {
		String fileName = intent.getAction();
		String localIndexInfoFile = intent.getStringExtra(LOCAL_INDEX_INFO);
		if (localIndexInfoFile == null) {
			LOG.error("Unexpected: localIndexInfoFile is null");
			return;
		}
		OsmandApplication application = (OsmandApplication) context.getApplicationContext();
		OsmandSettings settings = application.getSettings();

		// Upstream asks WifiManager.isWifiEnabled(), which reports the state of the radio and
		// not of any connection: a phone driving through Cairo with Wi-Fi left switched on but
		// nothing in range answers yes, so the alarm proceeded and the "only over Wi-Fi"
		// preference bought nothing. What matters is whether the connection now costs money.
		boolean allowed = !preferenceDownloadViaWiFi(localIndexInfoFile, settings).get()
				|| !CairoDriveDataSaver.isMetered(context);
		if (allowed) {
			OsmAndTaskManager.executeTask(new PerformLiveUpdateAsyncTask(context, localIndexInfoFile, false), fileName);
		} else {
			PerformLiveUpdateAsyncTask.tryRescheduleDownload(context, settings, localIndexInfoFile);
		}
	}
}
