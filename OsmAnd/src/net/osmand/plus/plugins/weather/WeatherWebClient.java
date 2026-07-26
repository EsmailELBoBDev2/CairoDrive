package net.osmand.plus.plugins.weather;

import androidx.annotation.NonNull;

import net.osmand.PlatformUtil;
import net.osmand.core.jni.IQueryController;
import net.osmand.core.jni.IWebClient.DataRequest;
import net.osmand.core.jni.SWIGTYPE_p_QByteArray;
import net.osmand.core.jni.SwigUtilities;
import net.osmand.core.jni.interface_IWebClient;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.cairodrive.CairoDriveDataSaver;
import net.osmand.plus.utils.AndroidNetworkUtils;
import net.osmand.plus.utils.AndroidNetworkUtils.NetworkProgress;

import org.apache.commons.logging.Log;

import java.io.File;
import java.util.concurrent.atomic.AtomicInteger;

public class WeatherWebClient extends interface_IWebClient {

	private final Log LOG = PlatformUtil.getLog(WeatherWebClient.class);

	private final OsmandApplication app;
	private final OfflineForecastHelper offlineForecastHelper;

	private WeatherWebClientListener downloadStateListener;
	private final AtomicInteger activeRequestsCounter = new AtomicInteger(0);

	public enum DownloadState {
		IDLE,
		STARTED,
		FINISHED
	}

	public interface WeatherWebClientListener {
		void onDownloadStateChanged(@NonNull DownloadState downloadState, int activeRequestsCounter);
	}

	public WeatherWebClient(@NonNull OsmandApplication app,
	                        @NonNull OfflineForecastHelper offlineForecastHelper) {
		this.app = app;
		this.offlineForecastHelper = offlineForecastHelper;
	}

	@Override
	public SWIGTYPE_p_QByteArray downloadData(String url, DataRequest dataRequest, String userAgent) {
		return SwigUtilities.emptyQByteArray();
	}

	@Override
	public String downloadString(String url, DataRequest dataRequest) {
		return "";
	}

	@Override
	public long downloadFile(String url, String fileName, long lastTime, DataRequest dataRequest) {
		// Every online weather byte passes through here: the native tile manager calls back
		// into this client for any geotile the on-disk cache is missing, so panning the map
		// with a weather layer on pulls raster tiles for as long as the map keeps moving.
		// Nothing upstream of this point asks whether the connection is metered - the
		// "download over Wi-Fi only" preference exists only for whole-region offline
		// forecasts, and CairoDrive unlocks the whole weather feature for free, so on a
		// cellular-only phone this is the largest unattended transfer in the app.
		//
		// An explicit region download is exempt: that traffic was asked for, and refusing it
		// here would make the offline forecast impossible to obtain rather than merely
		// deferred. It has its own Wi-Fi preference, which now defaults to on.
		if (CairoDriveDataSaver.blocksBulkTransfer(app)
				&& !offlineForecastHelper.isDownloadInProgress()) {
			return -1;
		}
		IQueryController queryController = dataRequest.getQueryController();
		return AndroidNetworkUtils.downloadModifiedFile(url, new File(fileName), false, lastTime, new NetworkProgress() {
			@Override
			public boolean isInterrupted() {
				if (queryController != null) {
					return queryController.isAborted();
				}
				return false;
			}

			@Override
			public void startTask(String taskName, int work) {
				super.startTask(taskName, work);
				int requestsCount = activeRequestsCounter.incrementAndGet();
				notifyDownloadStateChanged(DownloadState.STARTED, requestsCount);
			}

			@Override
			public void finishTask() {
				super.finishTask();
				int requestsCount = activeRequestsCounter.decrementAndGet();
				notifyDownloadStateChanged(DownloadState.FINISHED, requestsCount);
			}
		});
	}

	int getActiveRequestsCount() {
		return activeRequestsCounter.get();
	}

	void setDownloadStateListener(@NonNull WeatherWebClientListener listener) {
		downloadStateListener = listener;
	}

	void cleanupResources() {
		downloadStateListener = null;
	}

	private void notifyDownloadStateChanged(@NonNull DownloadState downloadState, int requestsCount) {
		if (downloadStateListener != null) {
			downloadStateListener.onDownloadStateChanged(downloadState, requestsCount);
		}
	}
}
