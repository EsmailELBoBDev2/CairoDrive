package net.osmand.plus.cairodrive;

import android.app.Activity;
import android.app.ActivityManager;
import android.app.Application;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.StatFs;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmAndLocationProvider.GPSInfo;
import net.osmand.plus.OsmandApplication;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Exhaustive on-device diagnostic logger for CairoDrive test builds.
 * <p>
 * It captures four independent streams into the same rotating file set:
 * <ol>
 *     <li><b>logcat</b> - the process' own logcat output at verbose level, drained
 *         continuously so nothing is lost to the system ring buffer overwriting itself;</li>
 *     <li><b>location</b> - every fix delivered by {@link OsmAndLocationProvider},
 *         plus a fixed-interval sample that records the position even when it has not
 *         changed, so a stationary period is as visible in the trace as a moving one;</li>
 *     <li><b>lifecycle</b> - activity transitions, foreground/background, compass updates;</li>
 *     <li><b>crashes</b> - uncaught exceptions on any thread, flushed before the process dies.</li>
 * </ol>
 * Files live under {@code Android/data/<applicationId>/files/cairodrive-logs} on the
 * shared storage, so they can be pulled off the device over USB without root and
 * without any runtime permission.
 * <p>
 * Enabled by {@code BuildConfig.CAIRODRIVE_FULL_LOGGING} - on for debug builds, off for
 * release builds unless {@code CAIRODRIVE_FULL_LOGGING=true} was set at build time
 * (see {@code OsmAnd/cairodrive.gradle}).
 */
public class CairoDriveLogger {

	public static final String LOG_DIR_NAME = "cairodrive-logs";

	/** How often the position is sampled even when no new fix arrived. */
	private static final long LOCATION_SAMPLE_INTERVAL_MS = 1000;
	/** How often battery/memory/storage are snapshotted. */
	private static final long SYSTEM_SAMPLE_INTERVAL_MS = 5000;
	/** Compass updates arrive at sensor rate; log at most one per this interval. */
	private static final long COMPASS_LOG_INTERVAL_MS = 500;
	private static final long LOGCAT_RESTART_DELAY_MS = 2000;

	private static final CairoDriveLogger INSTANCE = new CairoDriveLogger();

	private final SimpleDateFormat timestampFormat =
			new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US);

	private CairoDriveLogWriter writer;
	private HandlerThread samplerThread;
	private Handler samplerHandler;
	private Thread logcatThread;
	private volatile boolean started;
	private volatile boolean attached;

	private OsmandApplication app;
	private OsmAndLocationProvider.OsmAndLocationListener locationListener;
	private OsmAndLocationProvider.OsmAndCompassListener compassListener;

	private Location lastLoggedLocation;
	private long lastCompassLogTime;
	private long sampleCounter;

	private CairoDriveLogger() {
	}

	@NonNull
	public static CairoDriveLogger getInstance() {
		return INSTANCE;
	}

	public static boolean isEnabled() {
		return BuildConfig.CAIRODRIVE_FULL_LOGGING;
	}

	/**
	 * Starts file capture. Safe to call from {@code Application#onCreate} before any
	 * OsmAnd subsystem is initialised - only {@link Context} file APIs are touched here.
	 */
	public synchronized void init(@NonNull OsmandApplication app) {
		if (started || !isEnabled()) {
			return;
		}
		File directory = resolveLogDirectory(app);
		if (directory == null) {
			return;
		}
		this.app = app;
		writer = new CairoDriveLogWriter(directory);
		writer.start();
		started = true;

		// android.util.Log#isLoggable reports INFO as the floor unless a device-wide
		// property is set with adb, which is not an option on a user's phone. Forcing the
		// flag makes every PlatformUtil trace/debug call actually reach logcat, and from
		// there this file.
		PlatformUtil.setVerboseLoggingForced(true);

		installCrashHandler();
		startLogcatPump();
		startSampler();
		registerLifecycleCallbacks(app);
		logSessionHeader();
	}

	/**
	 * Attaches the location and compass taps. Called once OsmAnd's own subsystems exist.
	 */
	public synchronized void attach(@NonNull OsmandApplication app) {
		if (!started || attached) {
			return;
		}
		OsmAndLocationProvider provider = app.getLocationProvider();
		if (provider == null) {
			return;
		}
		locationListener = location -> logLocation("FIX", location, true);
		compassListener = value -> {
			long now = System.currentTimeMillis();
			if (now - lastCompassLogTime >= COMPASS_LOG_INTERVAL_MS) {
				lastCompassLogTime = now;
				log("COMPASS", "heading=" + format(Math.toDegrees(value)) + "deg raw=" + value);
			}
		};
		provider.addLocationListener(locationListener);
		provider.addCompassListener(compassListener);
		attached = true;
		log("LIFECYCLE", "location and compass taps attached");
	}

	public synchronized void stop() {
		if (!started) {
			return;
		}
		log("LIFECYCLE", "logger stopping");
		if (attached && app != null) {
			OsmAndLocationProvider provider = app.getLocationProvider();
			if (provider != null) {
				if (locationListener != null) {
					provider.removeLocationListener(locationListener);
				}
				if (compassListener != null) {
					provider.removeCompassListener(compassListener);
				}
			}
			attached = false;
		}
		if (samplerThread != null) {
			samplerThread.quit();
			samplerThread = null;
			samplerHandler = null;
		}
		if (logcatThread != null) {
			logcatThread.interrupt();
			logcatThread = null;
		}
		writer.flushBlocking(2000);
		writer.stop();
		started = false;
	}

	/** Directory the log files are written to, or {@code null} when logging is off. */
	@Nullable
	public File getLogDirectory() {
		return writer != null ? writer.getDirectory() : null;
	}

	/** Writes one tagged line. No-op when logging is disabled. */
	public void log(@NonNull String tag, @Nullable String message) {
		CairoDriveLogWriter writer = this.writer;
		if (writer == null || !started) {
			return;
		}
		writer.write(timestamp() + " " + tag + ": " + message);
	}

	public void log(@NonNull String tag, @Nullable String message, @NonNull Throwable throwable) {
		log(tag, message + "\n" + CairoDriveLogWriter.stackTraceToString(throwable));
	}

	@Nullable
	private File resolveLogDirectory(@NonNull Context context) {
		File base = context.getExternalFilesDir(null);
		if (base == null) {
			// No shared storage mounted - fall back to the private data directory. Still
			// readable through `adb run-as` / the app's own share sheet.
			base = context.getFilesDir();
		}
		if (base == null) {
			return null;
		}
		File directory = new File(base, LOG_DIR_NAME);
		return directory.mkdirs() || directory.isDirectory() ? directory : null;
	}

	private void logSessionHeader() {
		log("SESSION", "==================== CairoDrive session start ====================");
		log("SESSION", "app=" + BuildConfig.APPLICATION_ID + " flavor=" + BuildConfig.FLAVOR
				+ " buildType=" + BuildConfig.BUILD_TYPE + " debuggable=" + BuildConfig.DEBUG);
		try {
			PackageInfo info = app.getPackageManager().getPackageInfo(app.getPackageName(), 0);
			log("SESSION", "versionName=" + info.versionName + " versionCode=" + info.versionCode);
		} catch (PackageManager.NameNotFoundException e) {
			log("SESSION", "package info unavailable", e);
		}
		log("SESSION", "device=" + Build.MANUFACTURER + " " + Build.MODEL
				+ " brand=" + Build.BRAND + " product=" + Build.PRODUCT
				+ " hardware=" + Build.HARDWARE + " abis=" + TextUtils.join(",", Build.SUPPORTED_ABIS));
		log("SESSION", "android=" + Build.VERSION.RELEASE + " sdk=" + Build.VERSION.SDK_INT
				+ " fingerprint=" + Build.FINGERPRINT);
		log("SESSION", "locale=" + Locale.getDefault() + " timezone=" + java.util.TimeZone.getDefault().getID());
		log("SESSION", "logDir=" + writer.getDirectory().getAbsolutePath()
				+ " maxFileBytes=" + CairoDriveLogWriter.MAX_FILE_BYTES
				+ " maxFiles=" + CairoDriveLogWriter.MAX_FILES
				+ " maxTotalBytes=" + CairoDriveLogWriter.MAX_TOTAL_BYTES);
		logSystemSample();
	}

	private void installCrashHandler() {
		Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
			try {
				log("CRASH", "uncaught exception on thread '" + thread.getName() + "'", throwable);
				logSystemSample();
				logLocation("CRASH_POSITION", lastKnownLocation(), false);
				writer.flushBlocking(3000);
			} catch (Throwable ignored) {
				// Never mask the original crash.
			}
			if (previous != null) {
				previous.uncaughtException(thread, throwable);
			}
		});
	}

	private void startSampler() {
		samplerThread = new HandlerThread("CairoDriveSampler");
		samplerThread.setPriority(Thread.MIN_PRIORITY);
		samplerThread.start();
		samplerHandler = new Handler(samplerThread.getLooper());
		samplerHandler.post(new Runnable() {
			@Override
			public void run() {
				try {
					sample();
				} catch (Throwable t) {
					log("SAMPLER", "sampling failed", t);
				}
				Handler handler = samplerHandler;
				if (handler != null) {
					handler.postDelayed(this, LOCATION_SAMPLE_INTERVAL_MS);
				}
			}
		});
	}

	private void sample() {
		sampleCounter++;
		logLocation("SAMPLE", lastKnownLocation(), false);
		if (sampleCounter % Math.max(1, SYSTEM_SAMPLE_INTERVAL_MS / LOCATION_SAMPLE_INTERVAL_MS) == 0) {
			logSystemSample();
		}
	}

	@Nullable
	private Location lastKnownLocation() {
		OsmandApplication app = this.app;
		if (app == null) {
			return null;
		}
		OsmAndLocationProvider provider = app.getLocationProvider();
		if (provider == null) {
			return null;
		}
		Location location = provider.getLastKnownLocation();
		return location != null ? location : provider.getLastStaleKnownLocation();
	}

	/**
	 * @param newFix {@code true} for a fix pushed by the provider, {@code false} for a
	 *               periodic sample. Samples are what make a stationary device visible in
	 *               the trace, so they are written even when the position is unchanged -
	 *               the {@code state} field distinguishes the two.
	 */
	private void logLocation(@NonNull String tag, @Nullable Location location, boolean newFix) {
		if (location == null) {
			log(tag, "state=NO_LOCATION " + gpsStateSuffix());
			return;
		}
		StringBuilder builder = new StringBuilder();
		String state;
		if (newFix) {
			state = "FIX";
		} else if (lastLoggedLocation == null) {
			state = "FIRST";
		} else {
			float distance = location.distanceTo(lastLoggedLocation);
			state = distance > 0 ? "MOVED" : "STILL";
			builder.append("movedM=").append(format(distance)).append(' ');
		}
		builder.insert(0, "state=" + state + " ");
		builder.append("lat=").append(location.getLatitude())
				.append(" lon=").append(location.getLongitude())
				.append(" provider=").append(location.getProvider())
				.append(" fixTime=").append(location.getTime())
				.append(" ageMs=").append(System.currentTimeMillis() - location.getTime());
		if (location.hasAltitude()) {
			builder.append(" altM=").append(format(location.getAltitude()));
		}
		if (location.hasSpeed()) {
			builder.append(" speedMs=").append(format(location.getSpeed()))
					.append(" speedKmh=").append(format(location.getSpeed() * 3.6f));
		}
		if (location.hasBearing()) {
			builder.append(" bearing=").append(format(location.getBearing()));
		}
		if (location.hasAccuracy()) {
			builder.append(" accuracyM=").append(format(location.getAccuracy()));
		}
		if (location.hasVerticalAccuracy()) {
			builder.append(" vAccuracyM=").append(format(location.getVerticalAccuracy()));
		}
		builder.append(' ').append(gpsStateSuffix());
		log(tag, builder.toString());
		lastLoggedLocation = new Location(location);
	}

	@NonNull
	private String gpsStateSuffix() {
		OsmandApplication app = this.app;
		OsmAndLocationProvider provider = app != null ? app.getLocationProvider() : null;
		if (provider == null) {
			return "gps=unknown";
		}
		StringBuilder builder = new StringBuilder();
		try {
			GPSInfo info = provider.getGPSInfo();
			if (info != null) {
				builder.append("satsFound=").append(info.foundSatellites)
						.append(" satsUsed=").append(info.usedSatellites)
						.append(" fixed=").append(info.fixed).append(' ');
			}
			builder.append("gpsEnabled=").append(provider.isGPSEnabled())
					.append(" networkEnabled=").append(provider.isNetworkEnabled());
			Float heading = provider.getHeading();
			if (heading != null) {
				builder.append(" heading=").append(format(heading));
			}
		} catch (Throwable t) {
			builder.append("gpsStateError=").append(t.getClass().getSimpleName());
		}
		return builder.toString();
	}

	private void logSystemSample() {
		Runtime runtime = Runtime.getRuntime();
		long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
		StringBuilder builder = new StringBuilder();
		builder.append("heapUsedMb=").append(usedMb)
				.append(" heapTotalMb=").append(runtime.totalMemory() / (1024 * 1024))
				.append(" heapMaxMb=").append(runtime.maxMemory() / (1024 * 1024))
				.append(" uptimeMs=").append(android.os.SystemClock.elapsedRealtime());

		OsmandApplication app = this.app;
		if (app != null) {
			try {
				ActivityManager manager = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
				if (manager != null) {
					ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
					manager.getMemoryInfo(memoryInfo);
					builder.append(" sysAvailMb=").append(memoryInfo.availMem / (1024 * 1024))
							.append(" sysLowMemory=").append(memoryInfo.lowMemory);
				}
			} catch (Throwable ignored) {
			}
			builder.append(' ').append(batteryState(app));
			File logDir = writer.getDirectory();
			try {
				StatFs stat = new StatFs(logDir.getAbsolutePath());
				builder.append(" freeStorageMb=")
						.append(stat.getAvailableBytes() / (1024 * 1024));
			} catch (Throwable ignored) {
			}
		}
		long dropped = writer.getDroppedLines();
		if (dropped > 0) {
			builder.append(" droppedLines=").append(dropped);
		}
		File current = writer.getCurrentFile();
		if (current != null) {
			builder.append(" logFile=").append(current.getName())
					.append(" logFileBytes=").append(current.length());
		}
		log("SYSTEM", builder.toString());
	}

	@NonNull
	private String batteryState(@NonNull Context context) {
		try {
			Intent intent = context.registerReceiver(null,
					new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
			if (intent == null) {
				return "battery=unknown";
			}
			int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
			int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
			int status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
			int percent = level >= 0 && scale > 0 ? level * 100 / scale : -1;
			return "batteryPct=" + percent + " batteryStatus=" + status
					+ " batteryTempC=" + intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, -1) / 10f;
		} catch (Throwable t) {
			return "battery=error";
		}
	}

	/**
	 * Drains the app's own logcat output. Android only exposes the calling process' logs
	 * to an unprivileged app, which is exactly what is wanted here; the buffer is read
	 * continuously (no {@code -d}) so entries reach the file before the kernel ring buffer
	 * recycles them.
	 */
	private void startLogcatPump() {
		logcatThread = new Thread(() -> {
			String[][] commands = {
					{"logcat", "-v", "threadtime,year,uid", "-b", "main,system,crash", "*:V"},
					{"logcat", "-v", "threadtime", "*:V"},
			};
			int attempt = 0;
			while (started && !Thread.currentThread().isInterrupted()) {
				String[] command = commands[Math.min(attempt, commands.length - 1)];
				Process process = null;
				try {
					process = new ProcessBuilder(command).redirectErrorStream(true).start();
					log("LOGCAT", "pump started: " + TextUtils.join(" ", command));
					try (BufferedReader reader = new BufferedReader(new InputStreamReader(
							process.getInputStream(), StandardCharsets.UTF_8), 32768)) {
						String line;
						while (started && (line = reader.readLine()) != null) {
							writer.write("LOGCAT| " + line);
						}
					}
				} catch (IOException e) {
					log("LOGCAT", "pump failed for " + TextUtils.join(" ", command)
							+ ": " + e.getMessage());
				} catch (Throwable t) {
					log("LOGCAT", "pump crashed", t);
				} finally {
					if (process != null) {
						process.destroy();
					}
				}
				attempt++;
				if (!started) {
					break;
				}
				try {
					Thread.sleep(LOGCAT_RESTART_DELAY_MS);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
					break;
				}
			}
		}, "CairoDriveLogcat");
		logcatThread.setPriority(Thread.MIN_PRIORITY);
		logcatThread.setDaemon(true);
		logcatThread.start();
	}

	private void registerLifecycleCallbacks(@NonNull OsmandApplication app) {
		app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
			@Override
			public void onActivityCreated(@NonNull Activity activity, @Nullable Bundle state) {
				log("ACTIVITY", "created " + name(activity) + " restored=" + (state != null));
			}

			@Override
			public void onActivityStarted(@NonNull Activity activity) {
				log("ACTIVITY", "started " + name(activity));
			}

			@Override
			public void onActivityResumed(@NonNull Activity activity) {
				log("ACTIVITY", "resumed " + name(activity));
			}

			@Override
			public void onActivityPaused(@NonNull Activity activity) {
				log("ACTIVITY", "paused " + name(activity));
			}

			@Override
			public void onActivityStopped(@NonNull Activity activity) {
				log("ACTIVITY", "stopped " + name(activity));
			}

			@Override
			public void onActivitySaveInstanceState(@NonNull Activity activity, @NonNull Bundle state) {
				log("ACTIVITY", "saveInstanceState " + name(activity));
			}

			@Override
			public void onActivityDestroyed(@NonNull Activity activity) {
				log("ACTIVITY", "destroyed " + name(activity));
			}

			private String name(@NonNull Activity activity) {
				return activity.getClass().getSimpleName() + "@"
						+ Integer.toHexString(System.identityHashCode(activity));
			}
		});
		ContextCompat.registerReceiver(app, new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				log("SCREEN", String.valueOf(intent.getAction()));
			}
		}, screenIntentFilter(), ContextCompat.RECEIVER_NOT_EXPORTED);
	}

	@NonNull
	private static IntentFilter screenIntentFilter() {
		IntentFilter filter = new IntentFilter();
		filter.addAction(Intent.ACTION_SCREEN_ON);
		filter.addAction(Intent.ACTION_SCREEN_OFF);
		filter.addAction(Intent.ACTION_USER_PRESENT);
		filter.addAction(Intent.ACTION_POWER_CONNECTED);
		filter.addAction(Intent.ACTION_POWER_DISCONNECTED);
		filter.addAction(Intent.ACTION_AIRPLANE_MODE_CHANGED);
		return filter;
	}

	@NonNull
	private String timestamp() {
		synchronized (timestampFormat) {
			return timestampFormat.format(new Date());
		}
	}

	@NonNull
	private static String format(double value) {
		return String.format(Locale.US, "%.3f", value);
	}
}
