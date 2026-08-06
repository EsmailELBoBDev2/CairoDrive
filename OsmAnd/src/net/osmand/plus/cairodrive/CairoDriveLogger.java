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
import android.hardware.display.DisplayManager;
import android.location.LocationManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Debug;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.StatFs;
import android.os.SystemClock;
import android.system.Os;
import android.system.OsConstants;
import android.view.Display;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.cairodrive.providers.ApiHealth;
import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmAndLocationProvider.GPSInfo;
import net.osmand.plus.OsmandApplication;
import net.osmand.util.MapUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.TimeZone;
import java.util.concurrent.atomic.AtomicLong;

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
 * shared storage, so they can be pulled off the device over USB or through the phone's file
 * manager without root, without {@code adb run-as}, and without any runtime permission - which
 * matters because the release build that Android Auto requires is not debuggable.
 * <p>
 * Enabled by {@code BuildConfig.CAIRODRIVE_FULL_LOGGING} - on by default for both debug and
 * release builds (this fork's release track is internal testing), and to be turned off with
 * {@code CAIRODRIVE_FULL_LOGGING=false} for any public production release
 * (see {@code OsmAnd/cairodrive.gradle}).
 */
public class CairoDriveLogger {

	public static final String LOG_DIR_NAME = "cairodrive-logs";

	/**
	 * How often the position is sampled when no new fix arrived.
	 * <p>
	 * This was 1 Hz, which cost a line, a heap copy of the fix and two {@code LocationManager}
	 * binder round-trips every second for the entire life of the process. During navigation
	 * it also bought almost nothing: the provider pushes a fix at roughly 1 Hz and each one
	 * is already logged as FIX, so the sampler was mostly restating a line that had just
	 * been written. What the sampler is actually for is the case where fixes <em>stop</em> -
	 * a stationary device, a tunnel, a provider that went away - and 5 s resolution answers
	 * "when did the fixes stop" as well as 1 s does while costing a fifth as much.
	 * <p>
	 * Rejected: making the sampler purely change-triggered. A sample that only fires when
	 * something changed cannot record that nothing changed, which is the one thing the
	 * sampler exists to record.
	 */
	private static final long LOCATION_SAMPLE_INTERVAL_MS = 5000;
	/** How often heap, battery and log-file state are snapshotted. All cheap, no syscalls. */
	private static final long SYSTEM_SAMPLE_INTERVAL_MS = 5000;
	/**
	 * How often the expensive system probes run - {@code ActivityManager.getMemoryInfo}
	 * (a binder call) and {@code StatFs} (a {@code statvfs} syscall). System-wide free
	 * memory and free storage do not move meaningfully inside five seconds, so paying for
	 * them at the SYSTEM_SAMPLE rate was six times the cost for no extra information. This
	 * also bounds how stale the cached provider-enabled flags can get (see
	 * {@link #refreshProviderState()}).
	 */
	private static final long SYSTEM_PROBE_INTERVAL_MS = 30000;

	/**
	 * How often every provider's health is written to the drive log.
	 *
	 * Three minutes: long enough that it is a rounding error against the frame budget and the log
	 * size, short enough that a provider which fails and recovers inside one trip still leaves a
	 * trace. The alternative - once at the end - loses exactly the transient faults that are
	 * hardest to reproduce on request.
	 */
	private static final long API_STATUS_INTERVAL_MS = 3 * 60 * 1000L;
	/** Compass updates arrive at sensor rate; log at most one per this interval. */
	private static final long COMPASS_LOG_INTERVAL_MS = 500;
	private static final long LOGCAT_RESTART_DELAY_MS = 2000;
	/** A child that lived less than this was killed, not finished - it counts toward backoff. */
	private static final long LOGCAT_RAPID_DEATH_MS = 15000;
	/** After this many consecutive quick deaths the pump stops; the platform clearly refuses it. */
	private static final int LOGCAT_MAX_RAPID_DEATHS = 8;
	/** Caps the exponential backoff at LOGCAT_RESTART_DELAY_MS << this (2s -> ~64s). */
	private static final int LOGCAT_MAX_BACKOFF_SHIFT = 5;
	/** A logcat command that ran at least this long counts as accepted by the platform. */
	private static final long LOGCAT_ACCEPTED_AFTER_MS = 30000;
	/** How long {@link #stop()} waits for each background thread to unwind. */
	private static final long STOP_JOIN_TIMEOUT_MS = 1000;

	/**
	 * Decimal places for latitude and longitude.
	 * <p>
	 * The fifth decimal is about 1.1 m at the equator and less away from it, which is finer
	 * than any consumer GNSS fix this will ever record - the accuracy field on a good fix is
	 * 3-5 m. Writing the full {@code double} therefore added no diagnostic information at
	 * all, while adding roughly 20 bytes per position line and, more to the point, storing a
	 * movement history at a precision that resolves which side of a room someone was on.
	 */
	private static final int COORD_DECIMALS = 5;
	/** Decimal places for metres, m/s, degrees - as {@code %.3f} used to give. */
	private static final int VALUE_DECIMALS = 3;
	private static final long[] POW10 = {1, 10, 100, 1000, 10000, 100000};
	/**
	 * Above this the fixed-point path would overflow the {@code long} it scales through, so
	 * the value is handed to {@link StringBuilder#append(double)} instead. Nothing this
	 * class logs comes near it; the guard exists because a corrupt fix is exactly the kind
	 * of thing being diagnosed, and the logger must not be the thing that throws.
	 */
	private static final double MAX_FIXED_POINT = 1e12;

	/**
	 * Per-thread scratch buffer for the position lines.
	 * <p>
	 * Thread-local rather than one shared instance behind a lock: the location listener
	 * thread, the sensor thread, the sampler thread and the crash handler all build lines,
	 * and a shared buffer would serialise them - putting a lock on the location callback,
	 * which is the one caller that has somewhere else to be.
	 */
	private static final ThreadLocal<StringBuilder> LINE_BUILDER = new ThreadLocal<StringBuilder>() {
		@Override
		protected StringBuilder initialValue() {
			return new StringBuilder(256);
		}
	};

	/**
	 * What the logcat pump asks for, instead of the {@code *:V} firehose it used to take.
	 * <p>
	 * Android only hands an unprivileged app its own process' logs, but "its own process"
	 * still includes every framework and library tag linked into it - the renderer, the
	 * choreographer, the runtime, the media stack - and at verbose that is thousands of
	 * lines a minute written to disk and rotated out of the retention window, crowding out
	 * the entries anyone is actually reading.
	 * <p>
	 * Every OsmAnd log statement routed through {@link PlatformUtil} carries the single tag
	 * {@code net.osmand}, so that one entry keeps the whole app trace, including everything
	 * {@link PlatformUtil#setVerboseLoggingForced(boolean)} unlocks. The Android Auto
	 * classes log under their own tags and are named explicitly because a projected head
	 * unit is one of the harder configurations to diagnose. The trailing {@code *:W} floor
	 * keeps warnings and errors from everything else - the handful of classes that call
	 * {@code android.util.Log} with a private tag, plus the platform's own complaints about
	 * skipped frames and dropped buffers - while dropping their routine chatter.
	 */
	/**
	 * Strips credentials out of a captured logcat line before it is written to disk.
	 *
	 * <p>This is not hypothetical. OsmAnd builds its backup requests by appending every parameter
	 * to the URL as a query string (AndroidNetworkUtils.uploadFile) and then logs the assembled
	 * URL at INFO - and BackupHelper puts accessToken in those parameters. This fork asks logcat
	 * for net.osmand:V and writes what comes back to a file, so without this every OsmAnd Cloud
	 * sync would leave a live bearer token in app-scoped external storage for up to four days.
	 * On API 24-29 any app holding READ_EXTERNAL_STORAGE can read that directory.
	 *
	 * <p>Redacting here rather than at the call site on purpose: the logger cannot know which of
	 * the app's thousands of log statements will one day carry a secret, and upstream adds new
	 * ones without consulting this fork. Filtering the pipe covers all of them, including the
	 * ones that do not exist yet.
	 */
	private static final String SECRET_NAMES =
			"accessToken|access_token|refresh_token|token|password|passwd|pwd|apikey|api_key"
					// subscription-key is Azure Maps' spelling and the hyphen means the plain
					// `key` alternative does NOT match it - \b sits before the whole group, so
					// "subscription-key=" only matched from the hyphen if `key` were listed alone,
					// and the leading word boundary prevents even that. Spelled out explicitly.
					+ "|subscription-key|subscriptionkey"
					+ "|key|secret|authorization|auth|orderId|deviceid|userid|email|sessionid";

	/**
	 * The three shapes a credential actually appears in, because one pattern does not cover them.
	 *
	 * <p>The first version of this matched only {@code name=value} - the query-string form used by
	 * AndroidNetworkUtils.uploadFile, which was the leak that prompted it. That closed one vector
	 * and left two open, and it short-circuited on any line without an '=', which is most of them:
	 * <ul>
	 *   <li>QUERY - {@code ...?accessToken=eyJ...&deviceid=1}</li>
	 *   <li>JSON  - {@code {"accessToken":"eyJ..."}}, which is how AndroidNetworkUtils.sendRequest
	 *       logs a request body, and how the device-registration response comes back</li>
	 *   <li>HEADER - {@code Authorization: Bearer eyJ...}, plus any {@code name: value} header</li>
	 * </ul>
	 * A bearer token is equally compromising in all three, so all three are stripped.
	 */
	private static final Pattern SECRET_QUERY =
			Pattern.compile("(?i)\\b(" + SECRET_NAMES + ")=([^&\\s\"']*)");
	private static final Pattern SECRET_JSON =
			Pattern.compile("(?i)\"(" + SECRET_NAMES + ")\"\\s*:\\s*\"[^\"]*\"");
	private static final Pattern SECRET_HEADER =
			Pattern.compile("(?i)\\b(" + SECRET_NAMES + ")\\s*:\\s*(Bearer\\s+)?[A-Za-z0-9._~+/=-]{8,}");

	@NonNull
	static String redactSecrets(@NonNull String line) {
		// No cheap pre-filter on '=' any more: the JSON and header forms do not contain one, and
		// skipping them was exactly the gap. Checking for a ':' as well would still miss nothing
		// useful but costs the same as just running the matchers on a miss, so run them.
		String out = line;
		Matcher matcher = SECRET_QUERY.matcher(out);
		if (matcher.find()) {
			out = matcher.replaceAll("$1=<redacted>");
		}
		matcher = SECRET_JSON.matcher(out);
		if (matcher.find()) {
			out = matcher.replaceAll("\"$1\":\"<redacted>\"");
		}
		matcher = SECRET_HEADER.matcher(out);
		if (matcher.find()) {
			out = matcher.replaceAll("$1: <redacted>");
		}
		return out;
	}

	private static final String[] LOGCAT_FILTERS = {
			PlatformUtil.TAG + ":V",
			"NavigationSession:V",
			"SurfaceRenderer:V",
			"AndroidRuntime:E",
			"System.err:W",
			// System.out at INFO, because two numbers we actually want are printed with
			// System.out.println and were being dropped by the "*:W" floor below:
			//   OsmandApplication  "Time to start application ... ms. Should be less < 800 ms"
			//   OsmandApplication  "Time to init plugins ... ms. Should be less < 800 ms"
			// AppInitializer's per-stage "Initialized <EVENT> in N ms" and its
			// "Startup service <class> took too long N ms" go to System.err and were already
			// captured - so with this line the whole startup breakdown is readable from a drive log
			// without attaching a debugger.
			"System.out:I",
			"*:W",
	};

	private static final CairoDriveLogger INSTANCE = new CairoDriveLogger();

	/**
	 * UTC, like the file names and like the logcat stream this interleaves with. A trace
	 * that a driver carries across a timezone stays monotonic, and the device's local zone
	 * is recorded once on the SESSION line instead of being baked into every stamp.
	 */
	private final SimpleDateFormat timestampFormat;

	private CairoDriveLogWriter writer;
	private HandlerThread samplerThread;
	private Handler samplerHandler;
	private Thread logcatThread;
	/**
	 * The running logcat child, so {@link #stop()} can {@code destroy()} it.
	 * <p>
	 * Interrupting the pump thread does not end the pump: it is parked in
	 * {@code BufferedReader.readLine()} on the child's stdout, and a blocking read on a
	 * process stream is not interruptible. Closing the stream from another thread is not
	 * reliable either. Killing the child is what makes the read return, and it is also the
	 * only thing that reaps the child at all - a process spawned by an Android app is not
	 * killed with its parent, it is reparented to init and goes on writing into a pipe
	 * nobody drains until the pipe fills and it blocks there forever.
	 */
	private volatile Process logcatProcess;
	/** Logcat pump thread only: consecutive children that died before LOGCAT_RAPID_DEATH_MS. */
	private int rapidDeaths;
	private volatile boolean started;
	private volatile boolean attached;

	private OsmandApplication app;
	private OsmAndLocationProvider.OsmAndLocationListener locationListener;
	private OsmAndLocationProvider.OsmAndCompassListener compassListener;
	/** Kept only so {@link #stop()} can unregister them. */
	private Application.ActivityLifecycleCallbacks lifecycleCallbacks;
	private BroadcastReceiver screenReceiver;
	private BroadcastReceiver batteryReceiver;
	private BroadcastReceiver providerReceiver;

	/**
	 * Last position written, as an immutable snapshot published through a single volatile
	 * reference.
	 * <p>
	 * This used to be a {@code Location} field assigned {@code new Location(location)} - a
	 * mutable object handed between the sampler thread, the location listener thread and the
	 * crash handler through a plain field, so a reader could legally observe it half
	 * constructed and compute a {@code movedM} of thousands of kilometres from a latitude
	 * that had landed and a longitude that had not. Two coordinates in a final-field holder
	 * behind one volatile write are safely published, and cost a 32-byte object instead of a
	 * full {@code Location} plus its {@code float[]} scratchpad.
	 * <p>
	 * Keeping coordinates rather than the {@code Location} also stops the diagnostic from
	 * reaching into the app it is diagnosing: {@code Location.distanceTo} caches its result
	 * in the receiver, so calling it on the live fix mutated the provider's own object and
	 * invalidated the cache that {@code CurrentPositionHelper} relies on, from a thread that
	 * had no business touching it. {@link MapUtils#getDistance} is stateless. Its haversine
	 * differs from {@code distanceTo}'s ellipsoidal result by well under a percent, which
	 * does not matter to a field whose job is to say whether the device moved.
	 */
	private volatile PositionSnapshot lastLoggedPosition;
	/**
	 * Atomic because the compass listener is called from the sensor thread and the rate
	 * limit has to hold there: a plain field let two callbacks read the same stale stamp and
	 * both decide they were entitled to log.
	 */
	private final AtomicLong lastCompassLogTime = new AtomicLong();
	/** Sampler thread only. */
	private long sampleCounter;

	/**
	 * Battery state, refreshed by {@link #batteryReceiver} instead of polled.
	 * <p>
	 * {@code registerReceiver(null, ACTION_BATTERY_CHANGED)} looks like a cheap field read
	 * and is not - it is a binder round-trip into the activity manager that returns and
	 * unparcels the whole sticky intent, and it was on the five-second sample for the life
	 * of the process. A registered receiver gets the same data pushed, coalesced by the
	 * system, at no cost to us between changes; registering it also returns the current
	 * sticky intent, so the cache starts warm with the one call we would have made anyway.
	 */
	private volatile int batteryPercent = -1;
	private volatile int batteryStatus = -1;
	private volatile int batteryTempTenthsC = Integer.MIN_VALUE;
	private volatile int batteryVoltageMv = -1;
	private volatile int batteryPlugged = -1;
	private volatile int batteryHealth = -1;
	private volatile String batteryTech;
	private volatile boolean batteryKnown;

	/** Sampler thread only: previous CPU jiffies and the moment they were read, for app CPU%. */
	private long lastCpuJiffies = -1;
	private long lastCpuSampleRealtimeMs;

	/**
	 * Whether the GPS and network providers are enabled, refreshed on
	 * {@code PROVIDERS_CHANGED} rather than probed per line.
	 * <p>
	 * {@code isGPSEnabled()} and {@code isNetworkEnabled()} each call
	 * {@code LocationManager.isProviderEnabled}, which is a binder round-trip; both ran on
	 * every FIX and every SAMPLE, so a navigating device paid four of them a second to
	 * re-read a setting that changes a handful of times a day.
	 */
	private volatile boolean gpsProviderEnabled;
	private volatile boolean networkProviderEnabled;
	private volatile boolean providerStateKnown;

	private CairoDriveLogger() {
		timestampFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS'Z'", Locale.US);
		timestampFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
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
		if (started) {
			return;
		}
		if (!isEnabled()) {
			// A build with logging compiled OUT still has to clear what a previous build wrote.
			//
			// Retention - 4 days, 40 files, 320 MB - is enforced only by CairoDriveLogWriter's
			// prune(), which runs on the writer thread. With logging disabled that thread never
			// starts, so nothing ever sweeps the directory: an in-place Play update from this
			// internal-testing build to a logging-disabled public one would strand up to 320 MB
			// of position history and search queries in Android/data indefinitely, until the
			// user uninstalled. The retention window silently stops applying at exactly the
			// moment it is being relied on.
			deleteLogs(app);
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
			long previous = lastCompassLogTime.get();
			// The compare-and-set is what makes the rate limit a rate limit. Read-then-write
			// on a plain field let two sensor callbacks both see the old stamp and both
			// decide the interval had elapsed, so the "at most one per interval" cap was
			// really "at most one per interval, per racing caller".
			if (now - previous >= COMPASS_LOG_INTERVAL_MS
					&& lastCompassLogTime.compareAndSet(previous, now)) {
				// OsmAndLocationProvider#getAngle already returns degrees, normalised to
				// [-180, 180] by MapUtils#unifyRotationTo360 despite its name. Logged as
				// delivered so it matches the heading on the FIX and SAMPLE lines.
				StringBuilder builder = lineBuilder().append("headingDeg=");
				appendFixed(builder, value, VALUE_DECIMALS);
				log("COMPASS", builder.toString());
			}
		};
		provider.addLocationListener(locationListener);
		provider.addCompassListener(compassListener);
		attached = true;
		log("LIFECYCLE", "location and compass taps attached");
	}

	/**
	 * Tears the whole capture down: taps, receivers, both background threads, the logcat
	 * child process and the writer.
	 * <p>
	 * Idempotent, safe from any thread, and never throws - it is called on a teardown path
	 * where an exception would be reported as the app failing to exit cleanly.
	 * <p>
	 * Android gives no callback that reliably runs before a process dies, so this exists for
	 * the paths that do run - {@code Application#onTerminate} and the app's own restart
	 * flow - and for the sake of the logcat child, which outlives the app if it is not
	 * killed here.
	 */
	public synchronized void stop() {
		if (!started) {
			return;
		}
		log("LIFECYCLE", "logger stopping");
		// Cleared before anything is torn down, so the logcat pump loop and every log()
		// caller stop feeding a writer that is on its way out. Everything below then only
		// has to unwind what is already idle.
		started = false;
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
			locationListener = null;
			compassListener = null;
			attached = false;
		}
		unregisterCallbacks();
		// Killing the child is the only thing that unblocks the pump's readLine(); the
		// interrupt afterwards is for the restart back-off sleep, not for the read.
		Process process = logcatProcess;
		logcatProcess = null;
		if (process != null) {
			try {
				process.destroy();
			} catch (Throwable ignored) {
			}
		}
		if (logcatThread != null) {
			logcatThread.interrupt();
			joinQuietly(logcatThread);
			logcatThread = null;
		}
		if (samplerThread != null) {
			samplerHandler = null;
			samplerThread.quit();
			joinQuietly(samplerThread);
			samplerThread = null;
		}
		// Restored so a stopped logger leaves no trace on the rest of the app: the forced
		// flag makes every LOG.trace in the process do work, and there is nothing left here
		// to capture the result.
		PlatformUtil.setVerboseLoggingForced(false);
		if (writer != null) {
			writer.flushBlocking(2000);
			writer.stop();
		}
		app = null;
	}

	private void unregisterCallbacks() {
		OsmandApplication app = this.app;
		if (app == null) {
			return;
		}
		if (lifecycleCallbacks != null) {
			app.unregisterActivityLifecycleCallbacks(lifecycleCallbacks);
			lifecycleCallbacks = null;
		}
		// Each guarded on its own: unregisterReceiver throws IllegalArgumentException for a
		// receiver that never registered, and one that failed to register must not stop the
		// others being cleaned up.
		screenReceiver = unregister(app, screenReceiver);
		batteryReceiver = unregister(app, batteryReceiver);
		providerReceiver = unregister(app, providerReceiver);
	}

	@Nullable
	private static BroadcastReceiver unregister(@NonNull Context context,
	                                            @Nullable BroadcastReceiver receiver) {
		if (receiver != null) {
			try {
				context.unregisterReceiver(receiver);
			} catch (Throwable ignored) {
			}
		}
		return null;
	}

	/**
	 * Waits a bounded time for a thread to unwind. Bounded because {@link #stop()} may be
	 * running on a teardown path with a watchdog on it - a logger that hangs the shutdown it
	 * was asked to tidy up is worse than a thread that outlives it by a moment.
	 */
	private static void joinQuietly(@NonNull Thread thread) {
		try {
			thread.join(STOP_JOIN_TIMEOUT_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
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

	/**
	 * App-scoped external storage ({@code Android/data/<package>/files}), falling back to private
	 * internal storage only if there is no external volume.
	 * <p>
	 * These files are a continuous record of where the user has been - timestamped GPS fixes,
	 * every place searched for - so private internal storage ({@code getFilesDir()}, 0700) would
	 * be the safer default. But it is unreachable without {@code adb run-as}, which only works on
	 * a debuggable build. The build that matters here is the release one: Android Auto requires
	 * the app to be installed through Play, that build is not debuggable, and MIUI blocks
	 * {@code adb pull} of internal storage - so on the one configuration that needs diagnosing,
	 * internal-storage logs cannot be retrieved at all. External app-scoped storage is reachable
	 * from a non-debuggable build through the phone's file manager and MTP, needs no permission,
	 * and is deleted with the app on uninstall.
	 * <p>
	 * The privacy cost is real and deliberately accepted for an internal-testing build: on API
	 * 24-29 anything under {@code Android/data} is readable by an app holding
	 * READ_EXTERNAL_STORAGE and by a USB host; on API 30+ scoped storage keeps it to this app.
	 * That is why {@code CAIRODRIVE_FULL_LOGGING} must be turned off for any public production
	 * release - see {@code OsmAnd/cairodrive.gradle}.
	 */
	/**
	 * Removes every log this fork has ever written. Called only when logging is compiled out -
	 * see {@link #init}. Best effort and silent: a build with the logger disabled must not spend
	 * startup time, or crash, on housekeeping for a feature it does not have.
	 */
	private void deleteLogs(@NonNull Context context) {
		try {
			File base = context.getExternalFilesDir(null);
			File directory = base == null ? null : new File(base, LOG_DIR_NAME);
			if (directory == null || !directory.isDirectory()) {
				return;
			}
			File[] files = directory.listFiles();
			if (files != null) {
				for (File file : files) {
					String name = file.getName();
					if (name.startsWith("cairodrive-") && name.endsWith(".log")) {
						file.delete();
					}
				}
			}
			directory.delete();
		} catch (RuntimeException ignored) {
			// Nothing to report to - the logger is off.
		}
	}

	@Nullable
	private File resolveLogDirectory(@NonNull Context context) {
		File base = context.getExternalFilesDir(null);
		if (base == null) {
			base = context.getFilesDir();
		}
		if (base == null) {
			return null;
		}
		File directory = new File(base, LOG_DIR_NAME);
		return directory.mkdirs() || directory.isDirectory() ? directory : null;
	}

	/**
	 * Whether a credential is compiled in - never what it is.
	 *
	 * <p>The distinction this records cannot be read from any other line: a provider that is
	 * silent because its flag is false and one that is silent because the CI secret was not set
	 * produce identical logs otherwise, and the second has cost a wasted drive before.
	 */
	private static String have(String value) {
		return value != null && !value.trim().isEmpty() ? "yes" : "no";
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
		try {
			ActivityManager am = (ActivityManager) app.getSystemService(Context.ACTIVITY_SERVICE);
			ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
			if (am != null) {
				am.getMemoryInfo(mi);
			}
			log("SESSION", "cpuCores=" + Runtime.getRuntime().availableProcessors()
					+ " totalRamMb=" + (mi.totalMem / (1024 * 1024))
					+ " memClassMb=" + (am != null ? am.getMemoryClass() : -1)
					+ " largeHeapMb=" + (am != null ? am.getLargeMemoryClass() : -1)
					+ " lowRamDevice=" + (am != null && am.isLowRamDevice()));
		} catch (Throwable t) {
			log("SESSION", "hardware probe failed", t);
		}
		// Every fork flag, so a log says which build produced it.
		//
		// Without this a drive log cannot be attributed. The whole working method here is
		// change one thing, drive, compare - and several of these flags have been flipped
		// between drives already. A frame time that moved is meaningless if nobody can tell
		// whether the tilted camera or the render scale was on when it moved, and the answer
		// was previously "ask whoever ran the build", which does not survive a week.
		log("SESSION", "flags"
				+ " drivingView=" + BuildConfig.CAIRODRIVE_DRIVING_VIEW
				+ " offRouteHysteresis=" + BuildConfig.CAIRODRIVE_OFFROUTE_HYSTERESIS
				+ " surfaceOverscan=" + BuildConfig.CAIRODRIVE_SURFACE_OVERSCAN
				+ " renderScale=" + BuildConfig.CAIRODRIVE_RENDER_SCALE
				// The two biggest changes in this build were not recorded here, which is exactly
				// the failure this header exists to prevent - a log whose frame numbers cannot be
				// attributed to the settings that produced them. A stale sideload masquerading as
				// the new build has cost a drive here before.
				//
				// Note these are what the build ASKED for. What actually ran is in CD_FRAME
				// (hwCanvas=on|REFUSED|off, renderMode=) and CD_PRESENT, because the head unit gets
				// a veto on both and a request is not a fact.
				+ " hwCanvas=" + BuildConfig.CAIRODRIVE_HW_CANVAS
				+ " presentation=" + BuildConfig.CAIRODRIVE_PRESENTATION
				+ " routeRepair=" + BuildConfig.CAIRODRIVE_ROUTE_REPAIR
				+ " speculate=" + BuildConfig.CAIRODRIVE_SPECULATE
				+ " placeDetails=" + BuildConfig.CAIRODRIVE_PLACE_DETAILS
				+ " altAB=" + BuildConfig.CAIRODRIVE_ALTERNATE_ALTERNATIVES
				+ " fullLogging=" + BuildConfig.CAIRODRIVE_FULL_LOGGING
				+ " unlockPro=" + BuildConfig.CAIRODRIVE_UNLOCK_PRO
				+ " dataSaver=" + BuildConfig.CAIRODRIVE_DATA_SAVER);
		// The rest of them. Eighteen flags were missing from this header - every provider, every
		// Places feature, map matching, the speech lead, the interpolation percentage - which is
		// the same failure the comment above describes, just for the features added since it was
		// written. A drive log that cannot say whether TomTom traffic was compiled in is a log
		// that cannot explain why the route avoided a road, or why it did not.
		//
		// KEYS ARE NOT LOGGED, only whether one is present. A log is pulled off the device and
		// read in a chat; a key in it is a burned key. `have` is the only fact worth recording -
		// it is what distinguishes "the feature is off" from "the feature has no credentials",
		// which look identical in every other line.
		log("SESSION", "flags2"
				+ " tomtomTraffic=" + BuildConfig.CAIRODRIVE_TOMTOM_TRAFFIC
				+ " weatherHazard=" + BuildConfig.CAIRODRIVE_WEATHER_HAZARD
				+ " sunGlare=" + BuildConfig.CAIRODRIVE_SUN_GLARE
				+ " trafficRouting=" + BuildConfig.CAIRODRIVE_TRAFFIC_ROUTING
				+ " bestTime=" + BuildConfig.CAIRODRIVE_BESTTIME
				+ " openChargeMap=" + BuildConfig.CAIRODRIVE_OPEN_CHARGE_MAP
				+ " resumeZip=" + BuildConfig.CAIRODRIVE_RESUME_ZIP
				+ " mapMatching=" + BuildConfig.CAIRODRIVE_MAP_MATCHING
				+ " speechLead=" + BuildConfig.CAIRODRIVE_SPEECH_LEAD
				+ " rerouteCache=" + BuildConfig.CAIRODRIVE_REROUTE_CACHE
				+ " routeAlternatives=" + BuildConfig.CAIRODRIVE_ROUTE_ALTERNATIVES
				+ " lockExternalApi=" + BuildConfig.CAIRODRIVE_LOCK_EXTERNAL_API
				+ " interpolation=" + BuildConfig.CAIRODRIVE_LOCATION_INTERPOLATION);
		log("SESSION", "places"
				+ " details=" + BuildConfig.CAIRODRIVE_PLACES_DETAILS
				+ " photos=" + BuildConfig.CAIRODRIVE_PLACES_PHOTOS
				+ " reviews=" + BuildConfig.CAIRODRIVE_PLACES_REVIEWS
				+ " autocomplete=" + BuildConfig.CAIRODRIVE_PLACES_AUTOCOMPLETE
				+ " nearby=" + BuildConfig.CAIRODRIVE_PLACES_NEARBY);
		log("SESSION", "keys"
				+ " places=" + have(BuildConfig.GOOGLE_PLACES_API_KEY)
				+ " routes=" + have(BuildConfig.CAIRODRIVE_ROUTES_KEY)
				+ " tomtom=" + have(BuildConfig.CAIRODRIVE_TOMTOM_KEY)
				+ " openweather=" + have(BuildConfig.CAIRODRIVE_OPENWEATHER_KEY)
				+ " besttime=" + have(BuildConfig.CAIRODRIVE_BESTTIME_PRIVATE_KEY)
				+ " besttimePublic=" + have(BuildConfig.CAIRODRIVE_BESTTIME_PUBLIC_KEY)
				+ " mapillary=" + have(BuildConfig.CAIRODRIVE_MAPILLARY_TOKEN)
				+ " osmOauth=" + have(BuildConfig.CAIRODRIVE_OSM_OAUTH_ID)
				// The five that were shipping unreported. `here` has been in the build for weeks;
				// the other four arrived today. A key missing from this line is a key nobody can
				// tell is missing from a drive log, which is the whole failure this header exists
				// to prevent.
				+ " here=" + have(BuildConfig.CAIRODRIVE_HERE_KEY)
				+ " geoapify=" + have(BuildConfig.CAIRODRIVE_GEOAPIFY_KEY)
				+ " locationiq=" + have(BuildConfig.CAIRODRIVE_LOCATIONIQ_KEY)
				+ " azure=" + have(BuildConfig.CAIRODRIVE_AZURE_MAPS_KEY)
				+ " tomorrow=" + have(BuildConfig.CAIRODRIVE_TOMORROW_KEY));
		log("SESSION", "locale=" + Locale.getDefault() + " timezone=" + java.util.TimeZone.getDefault().getID());
		log("SESSION", "logDir=" + writer.getDirectory().getAbsolutePath()
				+ " maxFileBytes=" + CairoDriveLogWriter.MAX_FILE_BYTES
				+ " maxFileAgeMs=" + CairoDriveLogWriter.MAX_FILE_AGE_MS
				+ " retentionMs=" + CairoDriveLogWriter.MAX_FILE_RETENTION_MS
				+ " maxFiles=" + CairoDriveLogWriter.MAX_FILES
				+ " maxTotalBytes=" + CairoDriveLogWriter.MAX_TOTAL_BYTES);
		log("SESSION", "logcatFilters=" + TextUtils.join(" ", LOGCAT_FILTERS)
				+ " locationSampleMs=" + LOCATION_SAMPLE_INTERVAL_MS
				+ " systemSampleMs=" + SYSTEM_SAMPLE_INTERVAL_MS
				+ " systemProbeMs=" + SYSTEM_PROBE_INTERVAL_MS
				+ " coordDecimals=" + COORD_DECIMALS);
		logLocationPermissions();
		logSystemSample(true);
	}

	/**
	 * F2. Whether the location permissions Android Auto needs are actually granted.
	 *
	 * <p>This was written down as "check the permission on the phone" - a thing to go and look at
	 * by hand. Making it a log line is strictly better: a manual check is a snapshot of whatever
	 * the phone happened to be that afternoon, and the answer matters on the drive rather than in
	 * the settings screen.
	 *
	 * <p>It matters because of where the symptom lands. Upstream documents a 3-5 second freeze
	 * when Android Auto starts without location permission, and that freeze surfaces in
	 * {@code CD_FRAME}'s {@code lock} and {@code post} buckets - the two this project reads as
	 * "the head unit is not taking frames, nothing app-side can help". A revoked permission would
	 * therefore look exactly like the one diagnosis that says stop investigating. One line at
	 * session start rules that out for good.
	 *
	 * <p>{@code background=} is separate on purpose: foreground-only is enough to drive with the
	 * app open, and its absence is not a fault - so it is reported rather than warned about.
	 */
	private void logLocationPermissions() {
		try {
			boolean fine = androidx.core.content.ContextCompat.checkSelfPermission(app,
					android.Manifest.permission.ACCESS_FINE_LOCATION)
					== android.content.pm.PackageManager.PERMISSION_GRANTED;
			boolean coarse = androidx.core.content.ContextCompat.checkSelfPermission(app,
					android.Manifest.permission.ACCESS_COARSE_LOCATION)
					== android.content.pm.PackageManager.PERMISSION_GRANTED;
			boolean background = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
					|| androidx.core.content.ContextCompat.checkSelfPermission(app,
					android.Manifest.permission.ACCESS_BACKGROUND_LOCATION)
					== android.content.pm.PackageManager.PERMISSION_GRANTED;
			log("SESSION", "locationPermission fine=" + fine + " coarse=" + coarse
					+ " background=" + background
					+ (fine ? "" : "  <-- WITHOUT FINE LOCATION ANDROID AUTO FREEZES 3-5s AT START,"
					+ " AND IT LOOKS LIKE A HEAD-UNIT STALL IN CD_FRAME lock/post"));
		} catch (Throwable t) {
			log("SESSION", "locationPermission probe failed", t);
		}
	}

	private void installCrashHandler() {
		Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
		Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
			try {
				log("CRASH", "uncaught exception on thread '" + thread.getName() + "'", throwable);
				logSystemSample(true);
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
		boolean probe = sampleCounter % ticksPer(SYSTEM_PROBE_INTERVAL_MS) == 0;
		if (probe) {
			// Belt and braces for the cached provider flags. PROVIDERS_CHANGED is the
			// primary source, but it is a broadcast some vendor builds are careless with,
			// and a stale "gpsEnabled=true" would send a reader looking for a hardware
			// fault that was really a toggle. Re-reading it here bounds the staleness at
			// SYSTEM_PROBE_INTERVAL_MS while still costing two binder calls a minute
			// instead of four a second.
			refreshProviderState();
		}
		if (sampleCounter % ticksPer(SYSTEM_SAMPLE_INTERVAL_MS) == 0) {
			logSystemSample(probe);
		}
		if (sampleCounter % ticksPer(API_STATUS_INTERVAL_MS) == 0) {
			logApiStatus();
		}
	}

	/**
	 * Every provider's state, on a timer, into the drive log.
	 *
	 * <h3>Why this is not left to the status screen</h3>
	 *
	 * ApiHealth already answers "which providers are working and why not", but only when someone
	 * opens the menu item and taps it. That is the wrong instrument for this project: the owner is
	 * DRIVING, the question is asked afterwards from the log far more often than during, and a
	 * provider that failed once at minute three and recovered leaves no trace by the time anyone
	 * looks.
	 *
	 * <p>It also means one drive tests EVERY provider at once instead of one per trip. Nine
	 * providers checked one drive at a time is nine drives; this makes it one, and each costs a
	 * real trip through Cairo traffic to produce.
	 *
	 * <p>No requests are made here - this reports what the providers already recorded. It cannot
	 * spend budget, and it cannot make a provider look healthier than it is.
	 */
	private void logApiStatus() {
		try {
			// Pipe-joined rather than multi-line: one grep-able line per sample keeps it aligned
			// with every other periodic line in this file, and newlines inside a log record are
			// what make a log hard to read back.
			log("APISTATUS", ApiHealth.summary().replace("\n", " | "));
		} catch (Throwable t) {
			// Diagnostics must never be able to take down the thing they diagnose.
			log("APISTATUS", "unavailable: " + t.getClass().getSimpleName());
		}
	}

	private static long ticksPer(long intervalMs) {
		return Math.max(1, intervalMs / LOCATION_SAMPLE_INTERVAL_MS);
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
		StringBuilder builder = lineBuilder();
		if (location == null) {
			builder.append("state=NO_LOCATION ");
			appendGpsState(builder);
			log(tag, builder.toString());
			return;
		}
		// Built front to back rather than with the insert() the previous version used: the
		// state is known before anything is appended, so shifting the whole buffer to put it
		// at the front was work for nothing.
		PositionSnapshot previous = lastLoggedPosition;
		builder.append("state=");
		if (newFix) {
			builder.append("FIX ");
		} else if (previous == null) {
			builder.append("FIRST ");
		} else {
			double distance = MapUtils.getDistance(location.getLatitude(), location.getLongitude(),
					previous.latitude, previous.longitude);
			builder.append(distance > 0 ? "MOVED " : "STILL ").append("movedM=");
			appendFixed(builder, distance, VALUE_DECIMALS);
			builder.append(' ');
		}
		builder.append("lat=");
		appendFixed(builder, location.getLatitude(), COORD_DECIMALS);
		builder.append(" lon=");
		appendFixed(builder, location.getLongitude(), COORD_DECIMALS);
		builder.append(" provider=").append(location.getProvider())
				.append(" fixTime=").append(location.getTime())
				.append(" ageMs=").append(System.currentTimeMillis() - location.getTime());
		if (location.hasAltitude()) {
			builder.append(" altM=");
			appendFixed(builder, location.getAltitude(), VALUE_DECIMALS);
		}
		if (location.hasSpeed()) {
			builder.append(" speedMs=");
			appendFixed(builder, location.getSpeed(), VALUE_DECIMALS);
			builder.append(" speedKmh=");
			appendFixed(builder, location.getSpeed() * 3.6f, VALUE_DECIMALS);
		}
		if (location.hasBearing()) {
			builder.append(" bearing=");
			appendFixed(builder, location.getBearing(), VALUE_DECIMALS);
		}
		if (location.hasAccuracy()) {
			builder.append(" accuracyM=");
			appendFixed(builder, location.getAccuracy(), VALUE_DECIMALS);
		}
		if (location.hasVerticalAccuracy()) {
			builder.append(" vAccuracyM=");
			appendFixed(builder, location.getVerticalAccuracy(), VALUE_DECIMALS);
		}
		builder.append(' ');
		appendGpsState(builder);
		log(tag, builder.toString());
		lastLoggedPosition = new PositionSnapshot(location.getLatitude(), location.getLongitude());
	}

	private void appendGpsState(@NonNull StringBuilder builder) {
		OsmandApplication app = this.app;
		OsmAndLocationProvider provider = app != null ? app.getLocationProvider() : null;
		if (provider == null) {
			builder.append("gps=unknown");
			return;
		}
		try {
			// getGPSInfo() returns the provider's own live counter object - a field read,
			// not a binder call - so it stays on the per-line path.
			GPSInfo info = provider.getGPSInfo();
			if (info != null) {
				builder.append("satsFound=").append(info.foundSatellites)
						.append(" satsUsed=").append(info.usedSatellites)
						.append(" fixed=").append(info.fixed)
						.append(" gnss=").append(gnssHealth.update(info.usedSatellites))
						.append(' ');
			}
			if (providerStateKnown) {
				builder.append("gpsEnabled=").append(gpsProviderEnabled)
						.append(" networkEnabled=").append(networkProviderEnabled);
			} else {
				builder.append("gpsEnabled=unknown networkEnabled=unknown");
			}
			Float heading = provider.getHeading();
			if (heading != null) {
				builder.append(" headingDeg=");
				appendFixed(builder, heading, VALUE_DECIMALS);
			}
		} catch (Throwable t) {
			builder.append("gpsStateError=").append(t.getClass().getSimpleName());
		}
	}

	/**
	 * Re-reads the provider toggles. Two binder calls, so it is only ever run off the main
	 * thread: from the sampler tick and from {@link #providerReceiver}, which is registered
	 * against the sampler's handler for the same reason.
	 */
	private void refreshProviderState() {
		OsmandApplication app = this.app;
		OsmAndLocationProvider provider = app != null ? app.getLocationProvider() : null;
		if (provider == null) {
			return;
		}
		try {
			gpsProviderEnabled = provider.isGPSEnabled();
			networkProviderEnabled = provider.isNetworkEnabled();
			providerStateKnown = true;
		} catch (Throwable ignored) {
			// Leave the last known values in place; a failed probe is not evidence of a
			// disabled provider.
		}
	}

	/**
	 * @param includeProbes whether to run the expensive probes - a binder call for
	 *                      system-wide memory and a {@code statvfs} for free storage. False
	 *                      on most ticks: those two numbers do not move inside five seconds,
	 *                      and paying for them at the sample rate was the second largest
	 *                      fixed cost this class carried. The crash handler passes true,
	 *                      because "the disk was full" is a live hypothesis at that point.
	 */
	private void logSystemSample(boolean includeProbes) {
		Runtime runtime = Runtime.getRuntime();
		long usedMb = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024);
		StringBuilder builder = new StringBuilder(192);
		builder.append("heapUsedMb=").append(usedMb)
				.append(" heapTotalMb=").append(runtime.totalMemory() / (1024 * 1024))
				.append(" heapMaxMb=").append(runtime.maxMemory() / (1024 * 1024))
				// The map/render core is native, so Java heap alone hides its growth. This is a
				// process counter, not a syscall - cheap enough for every sample.
				.append(" nativeHeapMb=").append(Debug.getNativeHeapAllocatedSize() / (1024 * 1024))
				.append(" uptimeMs=").append(SystemClock.elapsedRealtime());

		OsmandApplication app = this.app;
		if (app != null && includeProbes) {
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
			try {
				StatFs stat = new StatFs(writer.getDirectory().getAbsolutePath());
				builder.append(" freeStorageMb=")
						.append(stat.getAvailableBytes() / (1024 * 1024));
			} catch (Throwable ignored) {
			}
			appendThermalState(builder, app);
			appendNetworkState(builder, app);
			appendDisplayState(builder, app);
			appendProcessState(builder);
		}
		builder.append(' ');
		appendBatteryState(builder);
		long dropped = writer.getDroppedLines();
		if (dropped > 0) {
			builder.append(" droppedLines=").append(dropped);
		}
		File current = writer.getCurrentFile();
		if (current != null) {
			// getCurrentFileBytes() rather than current.length(): the writer already knows
			// the count, and length() is a stat() on the sample path for a number that only
			// has to be roughly right.
			builder.append(" logFile=").append(current.getName())
					.append(" logFileBytes=").append(writer.getCurrentFileBytes());
		}
		log("SYSTEM", builder.toString());
	}

	/** Reads the cache {@link #batteryReceiver} fills. No binder call, no allocation. */
	private void appendBatteryState(@NonNull StringBuilder builder) {
		if (!batteryKnown) {
			builder.append("battery=unknown");
			return;
		}
		builder.append("batteryPct=").append(batteryPercent)
				.append(" batteryStatus=").append(batteryStatusName(batteryStatus))
				.append(" batteryPlugged=").append(batteryPluggedName(batteryPlugged))
				.append(" batteryHealth=").append(batteryHealthName(batteryHealth));
		if (batteryVoltageMv > 0) {
			builder.append(" batteryV=");
			appendFixed(builder, batteryVoltageMv / 1000d, 3);
		}
		if (batteryTech != null) {
			builder.append(" batteryTech=").append(batteryTech);
		}
		if (batteryTempTenthsC != Integer.MIN_VALUE) {
			// The framework reports tenths of a degree as an int, so one decimal place is
			// the whole of the resolution there is.
			builder.append(" batteryTempC=");
			appendFixed(builder, batteryTempTenthsC / 10d, 1);
		}
	}

	@NonNull
	private static String batteryStatusName(int status) {
		switch (status) {
			case BatteryManager.BATTERY_STATUS_CHARGING: return "charging";
			case BatteryManager.BATTERY_STATUS_DISCHARGING: return "discharging";
			case BatteryManager.BATTERY_STATUS_FULL: return "full";
			case BatteryManager.BATTERY_STATUS_NOT_CHARGING: return "not_charging";
			default: return "unknown";
		}
	}

	@NonNull
	private static String batteryPluggedName(int plugged) {
		if (plugged <= 0) {
			return "unplugged";
		}
		StringBuilder sb = new StringBuilder();
		if ((plugged & BatteryManager.BATTERY_PLUGGED_AC) != 0) sb.append("ac");
		if ((plugged & BatteryManager.BATTERY_PLUGGED_USB) != 0) sb.append(sb.length() > 0 ? "+usb" : "usb");
		if ((plugged & BatteryManager.BATTERY_PLUGGED_WIRELESS) != 0) sb.append(sb.length() > 0 ? "+wireless" : "wireless");
		return sb.length() > 0 ? sb.toString() : ("plugged" + plugged);
	}

	@NonNull
	private static String batteryHealthName(int health) {
		switch (health) {
			case BatteryManager.BATTERY_HEALTH_GOOD: return "good";
			case BatteryManager.BATTERY_HEALTH_OVERHEAT: return "overheat";
			case BatteryManager.BATTERY_HEALTH_DEAD: return "dead";
			case BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE: return "over_voltage";
			case BatteryManager.BATTERY_HEALTH_COLD: return "cold";
			case BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE: return "failure";
			default: return "unknown";
		}
	}

	/**
	 * Thermal throttling status. When the SoC is throttling, frame times balloon and reroutes
	 * slow down, so this is one of the more useful things on the sample when the phone gets hot in
	 * a windscreen cradle. A binder call, so it only runs on the probe path. API 29+.
	 */
	private void appendThermalState(@NonNull StringBuilder builder, @NonNull Context ctx) {
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
			return;
		}
		try {
			PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
			if (pm != null) {
				builder.append(" thermal=").append(thermalName(pm.getCurrentThermalStatus()));
			}
		} catch (Throwable ignored) {
		}
	}

	@NonNull
	private static String thermalName(int status) {
		switch (status) {
			case PowerManager.THERMAL_STATUS_NONE: return "none";
			case PowerManager.THERMAL_STATUS_LIGHT: return "light";
			case PowerManager.THERMAL_STATUS_MODERATE: return "moderate";
			case PowerManager.THERMAL_STATUS_SEVERE: return "severe";
			case PowerManager.THERMAL_STATUS_CRITICAL: return "critical";
			case PowerManager.THERMAL_STATUS_EMERGENCY: return "emergency";
			case PowerManager.THERMAL_STATUS_SHUTDOWN: return "shutdown";
			default: return "unknown";
		}
	}

	/**
	 * The active network's transport, whether it is metered (a live bill while driving), and its
	 * signal strength. All available without any telephony permission via NetworkCapabilities;
	 * ACCESS_NETWORK_STATE, which this reads, is a normal permission the app already holds.
	 */
	private void appendNetworkState(@NonNull StringBuilder builder, @NonNull Context ctx) {
		try {
			ConnectivityManager cm = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
			if (cm == null) {
				return;
			}
			Network network = cm.getActiveNetwork();
			NetworkCapabilities caps = network != null ? cm.getNetworkCapabilities(network) : null;
			if (caps == null) {
				builder.append(" net=none");
				return;
			}
			String transport = "other";
			if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transport = "wifi";
			else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transport = "cellular";
			else if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transport = "ethernet";
			builder.append(" net=").append(transport)
					.append(" netMetered=").append(cm.isActiveNetworkMetered())
					.append(" netVpn=").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN));
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
				int signal = caps.getSignalStrength();
				if (signal != NetworkCapabilities.SIGNAL_STRENGTH_UNSPECIFIED) {
					builder.append(" netSignalDbm=").append(signal);
				}
				builder.append(" netDownKbps=").append(caps.getLinkDownstreamBandwidthKbps())
						.append(" netUpKbps=").append(caps.getLinkUpstreamBandwidthKbps());
			}
		} catch (Throwable ignored) {
		}
	}

	/**
	 * Display refresh rate and rotation. The refresh rate is what the frame pacing has to hit, so
	 * a 60 vs 120 Hz panel changes what a "good" CD_FRAME time is; rotation catches the sensor
	 * relaunches that blank the head unit.
	 */
	private void appendDisplayState(@NonNull StringBuilder builder, @NonNull Context ctx) {
		try {
			DisplayManager dm = (DisplayManager) ctx.getSystemService(Context.DISPLAY_SERVICE);
			Display display = dm != null ? dm.getDisplay(Display.DEFAULT_DISPLAY) : null;
			if (display != null) {
				builder.append(" displayHz=");
				appendFixed(builder, display.getRefreshRate(), 1);
				builder.append(" displayRotation=").append(display.getRotation());
			}
		} catch (Throwable ignored) {
		}
	}

	/**
	 * The app's own resource use: real memory footprint (PSS, split native/dalvik) and CPU share.
	 * <p>
	 * These are the two numbers that actually explain stutter from the app's own side - a native
	 * heap or PSS that climbs across a drive is a leak, and an appCpuPct pinned near
	 * 100/core-count is the app itself saturating a core rather than the phone being slow. Both
	 * read only this process' own counters, so no permission is involved. PSS is a slowish call
	 * and CPU needs a delta, so both sit on the 30s probe path.
	 */
	private void appendProcessState(@NonNull StringBuilder builder) {
		try {
			Debug.MemoryInfo mem = new Debug.MemoryInfo();
			Debug.getMemoryInfo(mem);
			builder.append(" pssTotalMb=").append(mem.getTotalPss() / 1024)
					.append(" pssNativeMb=").append(mem.nativePss / 1024)
					.append(" pssDalvikMb=").append(mem.dalvikPss / 1024);
		} catch (Throwable ignored) {
		}
		appendAppCpu(builder);
	}

	/**
	 * Percentage of one core-equivalent this process burned since the last probe, from
	 * {@code /proc/self/stat} (own process, always readable, no permission). Normalised by core
	 * count, so 100% means one full core; a value near 100 during navigation is the app pegging a
	 * core. The first probe of a session only seeds the baseline and prints nothing.
	 */
	private void appendAppCpu(@NonNull StringBuilder builder) {
		try {
			String stat = readFirstLine(new File("/proc/self/stat"));
			if (stat == null) {
				return;
			}
			// The comm field (2nd) is wrapped in parens and may itself contain spaces and parens,
			// so fields are read after the LAST ')'. After it: [0]=state ... [11]=utime [12]=stime,
			// both in clock ticks.
			int close = stat.lastIndexOf(')');
			if (close < 0 || close + 2 >= stat.length()) {
				return;
			}
			String[] f = stat.substring(close + 2).trim().split("\\s+");
			if (f.length < 13) {
				return;
			}
			long jiffies = Long.parseLong(f[11]) + Long.parseLong(f[12]);
			long nowMs = SystemClock.elapsedRealtime();
			if (lastCpuJiffies >= 0 && nowMs > lastCpuSampleRealtimeMs) {
				long hz = Os.sysconf(OsConstants._SC_CLK_TCK);
				int cores = Math.max(1, Runtime.getRuntime().availableProcessors());
				double elapsedSec = (nowMs - lastCpuSampleRealtimeMs) / 1000d;
				if (hz > 0 && elapsedSec > 0) {
					double fraction = (jiffies - lastCpuJiffies) / (double) hz / elapsedSec / cores;
					builder.append(" appCpuPct=");
					appendFixed(builder, fraction * 100d, 1);
				}
			}
			lastCpuJiffies = jiffies;
			lastCpuSampleRealtimeMs = nowMs;
		} catch (Throwable ignored) {
		}
	}

	@Nullable
	private static String readFirstLine(@NonNull File file) {
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8), 512)) {
			return reader.readLine();
		} catch (Throwable ignored) {
			return null;
		}
	}

	private void updateBatteryState(@Nullable Intent intent) {
		if (intent == null) {
			return;
		}
		try {
			int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
			int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
			batteryPercent = level >= 0 && scale > 0 ? level * 100 / scale : -1;
			batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
			batteryTempTenthsC = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE,
					Integer.MIN_VALUE);
			batteryVoltageMv = intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, -1);
			batteryPlugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1);
			batteryHealth = intent.getIntExtra(BatteryManager.EXTRA_HEALTH, -1);
			batteryTech = intent.getStringExtra(BatteryManager.EXTRA_TECHNOLOGY);
			batteryKnown = true;
		} catch (Throwable ignored) {
			// A malformed sticky intent is not worth a line, let alone a crash.
		}
	}

	/**
	 * Drains the app's own logcat output. Android only exposes the calling process' logs
	 * to an unprivileged app, which is exactly what is wanted here; the buffer is read
	 * continuously (no {@code -d}) so entries reach the file before the kernel ring buffer
	 * recycles them. What is asked for is narrowed by {@link #LOGCAT_FILTERS} rather than
	 * taken as {@code *:V}.
	 */
	private void startLogcatPump() {
		logcatThread = new Thread(() -> {
			// utc keeps the logcat stamps in the same zone as this class' own lines and the
			// file names. Each rung drops modifiers the previous one might not be accepted
			// with; the last rung still asks for utc, in the alternative spelling, so a
			// degraded pump does not silently start writing local time into a UTC file.
			// UTC is spelled uppercase in AOSP's format-modifier table, and a POCO C85 on
			// Android 15 rejects the lowercase form outright: `logcat: Invalid -v 'utc'.`
			// Uppercase is tried first; the lowercase spelling stays as a rung because other
			// vendors have accepted it.
			String[][] commands = {
					logcatCommand("-v", "threadtime,year,uid,UTC", "-b", "main,system,crash"),
					logcatCommand("-v", "threadtime,year,uid,utc", "-b", "main,system,crash"),
					logcatCommand("-v", "threadtime,UTC"),
					logcatCommand("-v", "threadtime"),
			};
			int attempt = 0;
			while (started && !Thread.currentThread().isInterrupted()) {
				String[] command = commands[Math.min(attempt, commands.length - 1)];
				Process process = null;
				boolean produced = false;
				boolean rejected = false;
				long runStartedAt = SystemClock.elapsedRealtime();
				try {
					process = new ProcessBuilder(command).redirectErrorStream(true).start();
					// Published before the read so stop() can reach it. Set after start()
					// returns, so the field never holds a process that failed to spawn.
					logcatProcess = process;
					log("LOGCAT", "pump started: " + TextUtils.join(" ", command));
					try (BufferedReader reader = new BufferedReader(new InputStreamReader(
							process.getInputStream(), StandardCharsets.UTF_8), 32768)) {
						String line;
						while (started && (line = reader.readLine()) != null) {
							// redirectErrorStream(true) merges logcat's OWN stderr into this
							// stream, so its rejection message arrives as an ordinary line. It
							// used to set produced=true, telling the loop below the command had
							// been accepted, which reset the fallback rung - so a rejected
							// command was retried forever and the ladder never advanced. A real
							// log showed four identical rejected starts in 33 seconds and not one
							// captured line, which is where CD_SEARCH and the LANG_ TTS markers
							// live.
							if (isLogcatRejection(line)) {
								rejected = true;
								log("LOGCAT", "rejected by platform, falling back: " + line);
								continue;
							}
							produced = true;
							writer.write("LOGCAT| " + redactSecrets(line));
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
						// Cleared only if it is still ours: stop() may already have taken
						// it, destroyed it, and be waiting on the join below.
						if (logcatProcess == process) {
							logcatProcess = null;
						}
					}
				}
				// Only step down a rung when the command looks rejected. A child that
				// produced output, or that lived a while, was accepted by this platform -
				// its later death is logd restarting or the process being killed, and
				// degrading the format for that would lose the UTC stamps for good.
				long ranForMs = SystemClock.elapsedRealtime() - runStartedAt;
				if (!rejected && (produced || ranForMs >= LOGCAT_ACCEPTED_AFTER_MS)) {
					attempt = 0;
				} else {
					attempt++;
				}
				if (!started) {
					break;
				}
				// Restart rate limiting, tracked separately from the format rung above.
				// On a real drive this loop spawned 12758 logcat children in 10.5 hours: the
				// platform was killing each one within seconds, and because they had produced
				// output first, the rung reset and the pump respawned after a flat 2s forever.
				// That is a process spawn every ~3 seconds for the whole drive - wasted CPU
				// and battery on the device being measured, which corrupts the measurement.
				// A child that dies quickly now backs the pump off exponentially, and after
				// enough consecutive quick deaths the pump gives up rather than churning.
				if (ranForMs < LOGCAT_RAPID_DEATH_MS) {
					rapidDeaths++;
				} else {
					rapidDeaths = 0;
				}
				if (rapidDeaths >= LOGCAT_MAX_RAPID_DEATHS) {
					log("LOGCAT", "pump giving up after " + rapidDeaths
							+ " consecutive short-lived children - this platform keeps killing it."
							+ " File logging is unaffected; only the logcat mirror stops.");
					break;
				}
				long delay = LOGCAT_RESTART_DELAY_MS << Math.min(rapidDeaths, LOGCAT_MAX_BACKOFF_SHIFT);
				try {
					Thread.sleep(delay);
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

	/** {@code logcat <options...> <filters...>} - filterspecs always come last. */
	@NonNull
	/**
	 * True for logcat's own complaint about the command line, as opposed to a captured log line.
	 * <p>
	 * Deliberately narrow: it has to match what the tool prints when it refuses to start and
	 * nothing an app might legitimately log. Both halves are required - the "logcat: " prefix
	 * that only the tool itself emits at the start of a line, and a refusal word.
	 */
	private static boolean isLogcatRejection(@NonNull String line) {
		if (!line.startsWith("logcat: ")) {
			return false;
		}
		String rest = line.substring(8).toLowerCase(Locale.US);
		return rest.startsWith("invalid")
				|| rest.startsWith("unable")
				|| rest.startsWith("unrecognized")
				|| rest.startsWith("unknown");
	}

	private static String[] logcatCommand(@NonNull String... options) {
		String[] command = new String[1 + options.length + LOGCAT_FILTERS.length];
		command[0] = "logcat";
		System.arraycopy(options, 0, command, 1, options.length);
		System.arraycopy(LOGCAT_FILTERS, 0, command, 1 + options.length, LOGCAT_FILTERS.length);
		return command;
	}

	private void registerLifecycleCallbacks(@NonNull OsmandApplication app) {
		lifecycleCallbacks = new Application.ActivityLifecycleCallbacks() {
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
		};
		app.registerActivityLifecycleCallbacks(lifecycleCallbacks);

		screenReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				log("SCREEN", String.valueOf(intent.getAction()));
			}
		};
		ContextCompat.registerReceiver(app, screenReceiver, screenIntentFilter(),
				ContextCompat.RECEIVER_NOT_EXPORTED);

		// Both of the receivers below are dispatched on the sampler thread rather than the
		// main looper. Neither does real work, but ACTION_BATTERY_CHANGED in particular
		// fires on every level, voltage and temperature change - which while charging and
		// navigating is often - and a diagnostic has no business adding broadcast dispatches
		// to the UI thread of the app it is measuring. refreshProviderState() makes two
		// binder calls, which is a harder rule still.
		Handler handler = samplerHandler;
		batteryReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				updateBatteryState(intent);
			}
		};
		// registerReceiver returns the current sticky intent for ACTION_BATTERY_CHANGED, so
		// the single call that registers the receiver also seeds the cache. That is the one
		// sticky-broadcast round-trip per session that used to happen every five seconds.
		updateBatteryState(ContextCompat.registerReceiver(app, batteryReceiver,
				new IntentFilter(Intent.ACTION_BATTERY_CHANGED), null, handler,
				ContextCompat.RECEIVER_NOT_EXPORTED));

		providerReceiver = new BroadcastReceiver() {
			@Override
			public void onReceive(Context context, Intent intent) {
				refreshProviderState();
				log("PROVIDERS", "gpsEnabled=" + gpsProviderEnabled
						+ " networkEnabled=" + networkProviderEnabled);
			}
		};
		ContextCompat.registerReceiver(app, providerReceiver,
				new IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION), null, handler,
				ContextCompat.RECEIVER_NOT_EXPORTED);
		// Seeded off the main thread; the provider may not exist yet at init() time, in
		// which case the first sampler probe picks it up.
		if (handler != null) {
			handler.post(this::refreshProviderState);
		}
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

	/** The calling thread's scratch buffer, emptied and ready to build one line. */
	@NonNull
	private static StringBuilder lineBuilder() {
		StringBuilder builder = LINE_BUILDER.get();
		builder.setLength(0);
		return builder;
	}

	/**
	 * Appends {@code value} with a fixed number of decimals, in place, with no allocation.
	 * <p>
	 * This replaces {@code String.format(Locale.US, "%.3f", value)}, of which there were
	 * four to six per position line. {@code String.format} re-parses its format string,
	 * builds a {@code Formatter}, resolves the locale's {@code DecimalFormatSymbols} and
	 * allocates an intermediate {@code String} on every single call; it is one of the
	 * slowest routines in the JDK, and it was on a path that ran several times a second for
	 * the whole of a drive.
	 * <p>
	 * Rejected: a cached {@link java.text.DecimalFormat}. It is not thread-safe, and this is
	 * called from the location listener thread, the sensor thread, the sampler and the crash
	 * handler - so it would need either a lock (serialising those callers on the logger) or
	 * a thread-local instance, and a thread-local {@code DecimalFormat} still allocates a
	 * {@code String} and walks a pattern for every value. Integer arithmetic into the
	 * buffer that is being built anyway does neither.
	 * <p>
	 * Rounding is half-up on the scaled magnitude, which can differ from {@code %.3f}'s
	 * half-even by one unit in the last place on an exact tie. At the fifth decimal of a
	 * degree that is a centimetre.
	 */
	private static void appendFixed(@NonNull StringBuilder out, double value, int decimals) {
		if (Double.isNaN(value) || Double.isInfinite(value)
				|| value <= -MAX_FIXED_POINT || value >= MAX_FIXED_POINT) {
			// A fix carrying a nonsensical field is exactly the kind of thing being hunted,
			// so it is written out as it stands rather than dropped or clamped.
			out.append(value);
			return;
		}
		long scale = POW10[decimals];
		boolean negative = value < 0;
		double magnitude = negative ? -value : value;
		long scaled = (long) (magnitude * scale + 0.5d);
		if (negative && scaled != 0) {
			// Guarded so that a small negative that rounds to zero prints "0.000" rather
			// than "-0.000", which reads as a direction it does not have.
			out.append('-');
		}
		out.append(scaled / scale).append('.');
		long fraction = scaled % scale;
		for (long digit = scale / 10; digit > 1 && fraction < digit; digit /= 10) {
			out.append('0');
		}
		out.append(fraction);
	}

	/**
	 * An immutable pair of coordinates. Final fields, so publishing the reference through a
	 * volatile write publishes the values with it - which a mutable {@code Location} passed
	 * through a plain field did not.
	 */
	private static final class PositionSnapshot {

		final double latitude;
		final double longitude;

		PositionSnapshot(double latitude, double longitude) {
			this.latitude = latitude;
			this.longitude = longitude;
		}
	}

	/**
	 * GNSS health, with hysteresis, derived from used-in-fix satellite count.
	 *
	 * <p>The 2026-08-04 drive had {@code satsUsed=0} on 55% of fixes while still reporting 2.1-2.5 m
	 * accuracy. Those are not GNSS positions - the fused provider is answering from Wi-Fi and cell,
	 * and stamping a confident accuracy on it. Below four satellites there is no 3D solution at all,
	 * so the count is the honest signal and the accuracy float is not.
	 *
	 * <p>This matters beyond the log: CairoDriveOffRoute gates its corroboration on accuracy, and an
	 * over-confident accuracy on a non-GNSS fix is exactly the input that makes a deviation look
	 * real. Recording the state first - one field on a line already being written - so the next
	 * drive shows how the two correlate before anything starts acting on it.
	 */
	private static final class GnssHealth {
		private static final int MIN_USED_FOR_FIX = 4;
		private static final int SAMPLES_TO_SWITCH = 3;

		private boolean healthy;
		private int agreeing;

		String update(int usedSatellites) {
			boolean sample = usedSatellites >= MIN_USED_FOR_FIX;
			if (sample == healthy) {
				agreeing = 0;
			} else if (++agreeing >= SAMPLES_TO_SWITCH) {
				healthy = sample;
				agreeing = 0;
			}
			return healthy ? "OK" : "DEGRADED";
		}
	}

	private final GnssHealth gnssHealth = new GnssHealth();

	/**
	 * Whether the last GNSS sample said the fix is network-derived rather than satellite-derived.
	 *
	 * <p>Exposed because CairoDriveStationary needs it: the simulation behind that class shows a
	 * speed-based stop detector suppresses ~48% of parked drift on a real satellite fix and only
	 * ~0.8% on a network one, so the fix quality is what says whether its result means anything.
	 * The hysteresis lives in GnssHealth, so this does not flap.
	 */
	public boolean isGnssDegraded() {
		return !gnssHealth.healthy;
	}

}
