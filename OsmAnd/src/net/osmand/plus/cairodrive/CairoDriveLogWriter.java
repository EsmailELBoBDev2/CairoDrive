package net.osmand.plus.cairodrive;

import android.os.SystemClock;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.TimeZone;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Rotating file writer behind a single background thread.
 * <p>
 * Unlike the logcat ring buffer - which the system silently overwrites after a few
 * megabytes - the files here are only ever touched by this class. A new file is started
 * once the current one passes {@link #MAX_FILE_BYTES} <em>or</em> spans
 * {@link #MAX_FILE_AGE_MS}, whichever comes first, and a file is deleted once its first
 * entry is older than {@link #MAX_FILE_RETENTION_MS} or once the {@link #MAX_FILES} /
 * {@link #MAX_TOTAL_BYTES} budget is exceeded. Log data is therefore cleared on a fixed
 * cycle - nothing older than the retention window survives - while a long driving
 * session inside that window is captured in full.
 * <p>
 * The newest file is reopened and appended to on process start rather than being
 * superseded, so restarting the app does not consume the rotation window or the
 * retention budget.
 * <p>
 * Callers never touch the filesystem: {@link #write(String)} only enqueues. When the
 * queue is full the oldest pending line is dropped and counted, so a logging burst can
 * never block the UI thread or grow the heap without bound.
 */
public class CairoDriveLogWriter {

	private static final long DAY_MS = 24L * 60 * 60 * 1000;

	/** Start a new file once the current one passes this size. */
	public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
	/**
	 * ...or once it spans this much time. Deletion works on whole files, so this is what
	 * bounds how precisely {@link #MAX_FILE_RETENTION_MS} can be honoured: with a one day
	 * file the retention sweep is accurate to the day.
	 */
	public static final long MAX_FILE_AGE_MS = DAY_MS;
	/**
	 * Log data is cleared on this cycle: a file is deleted once its first entry is older
	 * than this, so nothing older than four days is ever kept.
	 */
	public static final long MAX_FILE_RETENTION_MS = 4 * DAY_MS;
	/** Keep at most this many files. */
	public static final int MAX_FILES = 40;
	/** ...and at most this many bytes in total (~320 MB with the values above). */
	public static final long MAX_TOTAL_BYTES = 320L * 1024 * 1024;

	private static final int QUEUE_CAPACITY = 32768;
	private static final long FLUSH_INTERVAL_MS = 2000;
	/**
	 * How often the retention sweep runs on its own. Rotation triggers a sweep too, but a
	 * device that logs lightly might not rotate for a day, and the clearing has to happen
	 * on schedule rather than whenever the next file happens to be started.
	 */
	private static final long PRUNE_INTERVAL_MS = 60L * 60 * 1000;
	/** Back-off after a failed open or write, so a full disk cannot spawn a file per line. */
	private static final long IO_RETRY_DELAY_MS = 10000;
	private static final String FILE_PREFIX = "cairodrive-";
	private static final String FILE_SUFFIX = ".log";

	private final File directory;
	private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
	private final AtomicLong droppedLines = new AtomicLong();
	/**
	 * Pinned to UTC. Retention reads the start time back out of the file name, so the stamp
	 * has to denote an absolute instant: in local time a file written before the device
	 * crossed a timezone - or before a DST change - would parse back shifted by the offset
	 * delta and be cleared early or kept past the window. The lines inside carry UTC stamps
	 * too, so a name and the entries under it can be read against each other directly.
	 */
	private final SimpleDateFormat fileNameFormat;
	/**
	 * Names written by a build that predates the UTC switch, kept only so that logs already
	 * on a tester's device age out on schedule instead of falling back to their last write.
	 */
	private final SimpleDateFormat legacyFileNameFormat;

	private volatile boolean running;
	private Thread thread;
	private BufferedWriter writer;
	private File currentFile;
	private long currentFileBytes;
	/** Wall clock, matching the file name. Only ever used to decide retention. */
	private long currentFileStartedAt;
	/** Monotonic deadline for age rotation - immune to the clock being corrected. */
	private long currentFileRotateAt;
	/** Monotonic. */
	private long nextIoAttemptAt;
	private boolean resumeChecked;

	public CairoDriveLogWriter(@NonNull File directory) {
		this.directory = directory;
		this.fileNameFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss.SSS'Z'", Locale.US);
		this.fileNameFormat.setTimeZone(TimeZone.getTimeZone("UTC"));
		this.legacyFileNameFormat = new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss.SSS", Locale.US);
	}

	@NonNull
	public File getDirectory() {
		return directory;
	}

	@Nullable
	public File getCurrentFile() {
		return currentFile;
	}

	public long getDroppedLines() {
		return droppedLines.get();
	}

	public synchronized void start() {
		if (running) {
			return;
		}
		running = true;
		thread = new Thread(this::pump, "CairoDriveLogWriter");
		thread.setPriority(Thread.MIN_PRIORITY);
		thread.setDaemon(true);
		thread.start();
	}

	public synchronized void stop() {
		running = false;
		if (thread != null) {
			thread.interrupt();
			thread = null;
		}
	}

	/**
	 * Enqueues a single already formatted line. Never blocks; when the queue is
	 * saturated the oldest pending line is discarded and reported in the log header
	 * of the next file so gaps are always visible rather than silent.
	 */
	public void write(@Nullable String line) {
		if (line == null || !running) {
			return;
		}
		while (!queue.offer(line)) {
			if (queue.poll() == null) {
				return;
			}
			droppedLines.incrementAndGet();
		}
	}

	/** Blocks until everything queued so far has reached the disk, up to {@code timeoutMs}. */
	public void flushBlocking(long timeoutMs) {
		long deadline = SystemClock.elapsedRealtime() + timeoutMs;
		while (!queue.isEmpty() && SystemClock.elapsedRealtime() < deadline) {
			try {
				Thread.sleep(20);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		synchronized (this) {
			flushWriter();
		}
	}

	/**
	 * All log files currently on disk, oldest first.
	 * <p>
	 * Ordered by the same age {@link #prune()} judges by, not by the raw start time, so a
	 * file dated in the future sorts to the front where the oldest-first budget sweep can
	 * reach it, instead of sitting at the end pretending to be the newest.
	 */
	@NonNull
	public List<File> listFiles() {
		File[] files = directory.listFiles((dir, name) ->
				name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX));
		if (files == null) {
			return new ArrayList<>();
		}
		long now = System.currentTimeMillis();
		List<File> result = new ArrayList<>(Arrays.asList(files));
		result.sort((first, second) -> {
			// Larger span means older, and older sorts first.
			int byAge = Long.compare(spanOf(second, now), spanOf(first, now));
			return byAge != 0 ? byAge : first.getName().compareTo(second.getName());
		});
		return result;
	}

	@NonNull
	public static String stackTraceToString(@NonNull Throwable throwable) {
		StringWriter sw = new StringWriter();
		try (PrintWriter pw = new PrintWriter(sw)) {
			throwable.printStackTrace(pw);
		}
		return sw.toString();
	}

	private void pump() {
		// Intervals run off the monotonic clock: a head unit that boots with a dead RTC and
		// then picks up the real time over the network would otherwise stall the sweep for
		// as long as the correction was large.
		long lastFlush = SystemClock.elapsedRealtime();
		long lastPrune = SystemClock.elapsedRealtime();
		while (running) {
			try {
				String line = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
				synchronized (this) {
					if (line != null) {
						appendLine(line);
					}
					long elapsed = SystemClock.elapsedRealtime();
					if (elapsed - lastFlush >= FLUSH_INTERVAL_MS) {
						flushWriter();
						lastFlush = elapsed;
					}
					// A device can log lightly enough not to rotate for a whole day, and
					// the clearing has to keep to its schedule regardless.
					if (elapsed - lastPrune >= PRUNE_INTERVAL_MS) {
						prune();
						lastPrune = elapsed;
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Throwable t) {
				// A logger must never take the process down with it.
				//
				// Under the lock: both fields are guarded by it everywhere else, and
				// flushBlocking() takes the same lock from the crash handler. Recovering out
				// here raced that flush over `writer` - closing it underneath a flush in
				// progress - at exactly the moment the crash log is worth having.
				synchronized (this) {
					closeWriter();
					nextIoAttemptAt = SystemClock.elapsedRealtime() + IO_RETRY_DELAY_MS;
				}
			}
		}
		synchronized (this) {
			// Drain whatever is left so a clean stop() does not lose the tail.
			String line;
			while ((line = queue.poll()) != null) {
				appendLine(line);
			}
			flushWriter();
			closeWriter();
		}
	}

	private void appendLine(@NonNull String line) {
		long elapsed = SystemClock.elapsedRealtime();
		if (writer == null
				|| currentFileBytes >= MAX_FILE_BYTES
				|| elapsed >= currentFileRotateAt) {
			if (writer == null && elapsed < nextIoAttemptAt) {
				// Storage is unavailable; drop the line rather than retrying per line.
				// Counted, because the whole point of droppedLines is that a gap in the log
				// is visible rather than silent - and a full disk drops far more here than
				// the queue ever does.
				droppedLines.incrementAndGet();
				return;
			}
			rollFile();
		}
		if (writer == null) {
			return;
		}
		try {
			writer.write(line);
			writer.write('\n');
			// Byte count is approximate for non-ASCII payloads, which is fine: it only
			// decides when to rotate.
			currentFileBytes += line.length() + 1;
		} catch (IOException e) {
			closeWriter();
			nextIoAttemptAt = SystemClock.elapsedRealtime() + IO_RETRY_DELAY_MS;
		}
	}

	private void rollFile() {
		closeWriter();
		//noinspection ResultOfMethodCallIgnored
		directory.mkdirs();

		File file = null;
		long startedAt = 0;
		boolean resumed = false;
		long now = System.currentTimeMillis();
		long elapsed = SystemClock.elapsedRealtime();

		// A writer torn down by an IO error is reopened on the SAME file - only the size
		// and age caps mint a new one. Otherwise a failing disk would fill the directory
		// with header-only stubs and prune() would evict real logs to make room for them.
		if (currentFile != null
				&& currentFileBytes < MAX_FILE_BYTES
				&& elapsed < currentFileRotateAt) {
			file = currentFile;
			startedAt = currentFileStartedAt;
			resumed = true;
		}
		if (file == null && !resumeChecked) {
			// First write of this process. Continue the newest file while it still has
			// room in both budgets, so that frequent app restarts do not shorten the
			// rotation window or churn through the retention budget.
			resumeChecked = true;
			List<File> existing = listFiles();
			if (!existing.isEmpty()) {
				File newest = existing.get(existing.size() - 1);
				// Derived from the same clamped age the guard uses, never from the raw
				// parse: a name stamped in the future would otherwise collapse the
				// back-dating below and hand the file a full fresh rotation window.
				long newestSpan = spanOf(newest, now);
				if (newest.length() < MAX_FILE_BYTES && newestSpan < MAX_FILE_AGE_MS) {
					file = newest;
					startedAt = now - newestSpan;
					resumed = true;
				}
			}
		}
		if (file == null) {
			startedAt = now;
			String stamp;
			synchronized (fileNameFormat) {
				stamp = fileNameFormat.format(new Date(startedAt));
			}
			file = new File(directory, FILE_PREFIX + stamp + FILE_SUFFIX);
		}

		try {
			writer = new BufferedWriter(new OutputStreamWriter(
					new FileOutputStream(file, true), StandardCharsets.UTF_8), 32768);
			currentFile = file;
			currentFileStartedAt = startedAt;
			currentFileBytes = file.length();
			// A resumed file has already used part of its window, so back-date the
			// monotonic deadline by however much of the day it already covers.
			currentFileRotateAt = elapsed
					+ Math.max(0, MAX_FILE_AGE_MS - Math.max(0, now - startedAt));
			nextIoAttemptAt = 0;
			long dropped = droppedLines.get();
			writer.write("=== CairoDrive log " + (resumed ? "resumed" : "started") + " "
					+ file.getName() + ", rotates at " + (MAX_FILE_BYTES / (1024 * 1024))
					+ " MB or " + (MAX_FILE_AGE_MS / DAY_MS) + "d, cleared after "
					+ (MAX_FILE_RETENTION_MS / DAY_MS) + "d"
					+ (dropped > 0 ? ", dropped lines so far: " + dropped : "") + " ===\n");
		} catch (IOException e) {
			// currentFile is deliberately left alone so the retry after the back-off
			// targets the same path instead of minting yet another file.
			writer = null;
			nextIoAttemptAt = elapsed + IO_RETRY_DELAY_MS;
		}
		prune();
	}

	private void prune() {
		List<File> files = listFiles();
		long now = System.currentTimeMillis();
		long total = 0;
		for (File file : files) {
			total += file.length();
		}
		// Retention window first: a file whose first entry is older than
		// MAX_FILE_RETENTION_MS goes regardless of how much of the size budget is free.
		// Keyed on the start time rather than the last write, so that the age of the
		// data being dropped is bounded - keying on the last write would let a file
		// survive a full retention window past its own span.
		for (Iterator<File> iterator = files.iterator(); iterator.hasNext(); ) {
			File file = iterator.next();
			if (file.equals(currentFile) || spanOf(file, now) <= MAX_FILE_RETENTION_MS) {
				continue;
			}
			long size = file.length();
			if (file.delete()) {
				total -= size;
				iterator.remove();
			}
		}
		// Then the count and total size budget, oldest first. The open file is skipped
		// rather than ending the sweep: if the device clock jumps backwards the newly
		// started file sorts ahead of older ones, and stopping there would leave every
		// file behind it unreclaimable and the budget unenforced for good.
		int remaining = files.size();
		for (File file : files) {
			if (remaining <= MAX_FILES && total <= MAX_TOTAL_BYTES) {
				break;
			}
			if (file.equals(currentFile)) {
				continue;
			}
			long size = file.length();
			if (file.delete()) {
				total -= size;
				remaining--;
			}
		}
	}

	/**
	 * How long ago the file was started, never negative.
	 * <p>
	 * A name stamped while the device clock was ahead parses to a start time in the future.
	 * The last write is tried next, but it usually carries the same wrong clock, so a file
	 * that still dates from the future after both readings is treated as expired rather
	 * than as brand new: otherwise it would be exempt from the sweep, be picked as the
	 * newest file to resume on every start, and grow without ever facing retention. The
	 * open file is never at risk - {@link #prune()} skips it unconditionally.
	 */
	private long spanOf(@NonNull File file, long now) {
		long span = now - startTimeOf(file);
		if (span < 0) {
			span = now - file.lastModified();
		}
		if (span < 0) {
			return MAX_FILE_RETENTION_MS + 1;
		}
		return span;
	}

	/**
	 * When the file was started, read back from the timestamp this class embeds in the
	 * name. {@link File#lastModified()} cannot answer that - it moves with every append -
	 * so it is only the fallback for a name that does not parse.
	 */
	private long startTimeOf(@NonNull File file) {
		String name = file.getName();
		if (name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)) {
			String stamp = name.substring(FILE_PREFIX.length(), name.length() - FILE_SUFFIX.length());
			try {
				synchronized (fileNameFormat) {
					return fileNameFormat.parse(stamp).getTime();
				}
			} catch (ParseException | NullPointerException ignored) {
			}
			try {
				synchronized (legacyFileNameFormat) {
					return legacyFileNameFormat.parse(stamp).getTime();
				}
			} catch (ParseException | NullPointerException ignored) {
			}
		}
		return file.lastModified();
	}

	private void flushWriter() {
		if (writer != null) {
			try {
				writer.flush();
			} catch (IOException e) {
				// Where the filesystem uses delayed allocation an out-of-space error
				// surfaces here rather than at write() time, so this path has to arm the
				// back-off too - otherwise the next line reopens immediately and the
				// writer thrashes once per flush interval.
				closeWriter();
				nextIoAttemptAt = SystemClock.elapsedRealtime() + IO_RETRY_DELAY_MS;
			}
		}
	}

	private void closeWriter() {
		if (writer != null) {
			try {
				writer.close();
			} catch (IOException ignored) {
			}
			writer = null;
		}
	}
}
