package net.osmand.plus.cairodrive;

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
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Size-rotating file writer behind a single background thread.
 * <p>
 * Unlike the logcat ring buffer - which the system silently overwrites after a few
 * megabytes - the files produced here are only ever removed by this class, and only
 * once {@link #MAX_FILES} or {@link #MAX_TOTAL_BYTES} is exceeded. That is what makes
 * the capture survive long driving sessions.
 * <p>
 * Callers never touch the filesystem: {@link #write(String)} only enqueues. When the
 * queue is full the oldest pending line is dropped and counted, so a logging burst can
 * never block the UI thread or grow the heap without bound.
 */
public class CairoDriveLogWriter {

	/** Rotate to a new file once the current one passes this size. */
	public static final long MAX_FILE_BYTES = 8L * 1024 * 1024;
	/** Keep at most this many rotated files. */
	public static final int MAX_FILES = 40;
	/** ...and at most this many bytes in total (~320 MB with the values above). */
	public static final long MAX_TOTAL_BYTES = 320L * 1024 * 1024;

	private static final int QUEUE_CAPACITY = 32768;
	private static final long FLUSH_INTERVAL_MS = 2000;
	private static final String FILE_PREFIX = "cairodrive-";
	private static final String FILE_SUFFIX = ".log";

	private final File directory;
	private final BlockingQueue<String> queue = new ArrayBlockingQueue<>(QUEUE_CAPACITY);
	private final AtomicLong droppedLines = new AtomicLong();
	private final SimpleDateFormat fileNameFormat =
			new SimpleDateFormat("yyyy-MM-dd_HH-mm-ss.SSS", Locale.US);

	private volatile boolean running;
	private Thread thread;
	private BufferedWriter writer;
	private File currentFile;
	private long currentFileBytes;

	public CairoDriveLogWriter(@NonNull File directory) {
		this.directory = directory;
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
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!queue.isEmpty() && System.currentTimeMillis() < deadline) {
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

	/** All log files currently on disk, newest last. */
	@NonNull
	public List<File> listFiles() {
		File[] files = directory.listFiles((dir, name) ->
				name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX));
		if (files == null) {
			return new ArrayList<>();
		}
		List<File> result = new ArrayList<>(Arrays.asList(files));
		result.sort((first, second) -> {
			int byTime = Long.compare(first.lastModified(), second.lastModified());
			return byTime != 0 ? byTime : first.getName().compareTo(second.getName());
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
		long lastFlush = System.currentTimeMillis();
		while (running) {
			try {
				String line = queue.poll(FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
				synchronized (this) {
					if (line != null) {
						appendLine(line);
					}
					long now = System.currentTimeMillis();
					if (now - lastFlush >= FLUSH_INTERVAL_MS) {
						flushWriter();
						lastFlush = now;
					}
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			} catch (Throwable t) {
				// A logger must never take the process down with it.
				closeWriter();
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
		if (writer == null || currentFileBytes >= MAX_FILE_BYTES) {
			rotate();
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
		}
	}

	private void rotate() {
		closeWriter();
		//noinspection ResultOfMethodCallIgnored
		directory.mkdirs();
		String name = FILE_PREFIX + fileNameFormat.format(new Date()) + FILE_SUFFIX;
		File file = new File(directory, name);
		try {
			writer = new BufferedWriter(new OutputStreamWriter(
					new FileOutputStream(file, true), StandardCharsets.UTF_8), 32768);
			currentFile = file;
			currentFileBytes = file.length();
			long dropped = droppedLines.get();
			writer.write("=== CairoDrive log file " + name
					+ (dropped > 0 ? " (dropped lines so far: " + dropped + ")" : "") + " ===\n");
		} catch (IOException e) {
			writer = null;
			currentFile = null;
			currentFileBytes = 0;
		}
		prune();
	}

	private void prune() {
		List<File> files = listFiles();
		long total = 0;
		for (File file : files) {
			total += file.length();
		}
		int index = 0;
		while (index < files.size()
				&& (files.size() - index > MAX_FILES || total > MAX_TOTAL_BYTES)) {
			File oldest = files.get(index);
			if (oldest.equals(currentFile)) {
				break;
			}
			long size = oldest.length();
			if (oldest.delete()) {
				total -= size;
			}
			index++;
		}
	}

	private void flushWriter() {
		if (writer != null) {
			try {
				writer.flush();
			} catch (IOException e) {
				closeWriter();
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
