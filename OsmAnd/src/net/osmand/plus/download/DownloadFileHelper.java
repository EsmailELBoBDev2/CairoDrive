package net.osmand.plus.download;

import androidx.annotation.NonNull;

import net.osmand.IProgress;
import net.osmand.IndexConstants;
import net.osmand.PlatformUtil;
import net.osmand.osm.io.NetworkUtils;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.Version;
import net.osmand.plus.download.IndexItem.DownloadEntry;
import net.osmand.plus.helpers.FileNameTranslationHelper;
import net.osmand.plus.resources.ResourceManager;
import net.osmand.plus.utils.AndroidNetworkUtils;
import net.osmand.plus.utils.FileUtils;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

public class DownloadFileHelper {
	
	private static final Log log = PlatformUtil.getLog(DownloadFileHelper.class);

	private static final int BUFFER_SIZE = 32256;
	protected static final int TRIES_TO_DOWNLOAD = 15;
	protected static final long TIMEOUT_BETWEEN_DOWNLOADS = 8000;

	private final OsmandApplication ctx;
	private boolean interruptDownloading;


	public DownloadFileHelper(OsmandApplication ctx){
		this.ctx = ctx;
	}
	
	public interface DownloadFileShowWarning {
		
		void showWarning(String warning);
	}
	
	public static boolean isInterruptedException(IOException e) {
		return e != null && e.getMessage().equals("Interrupted");
	}
	
	public InputStream getInputStreamToDownload(URL url, boolean forceWifi) throws IOException {
		return getInputStreamToDownload(url, forceWifi, 0);
	}

	/**
	 * D1, the half of it that is actually possible.
	 *
	 * <p>Resume WITHIN a session already worked: {@code fileread} counts bytes taken from the
	 * server and the reconnect loop sends {@code Range: bytes=fileread-}. What did not survive was
	 * closing the app, because {@code fileread} lives only in this anonymous class.
	 *
	 * <p>This was previously written off as architecturally blocked, and that was only ever true
	 * of HALF the downloads. A {@code .obf} map arrives as a zip stream and is decompressed on the
	 * fly ({@code unzipFile}), so the bytes on disk are decompressed while the offset the server
	 * needs is compressed - and no mapping exists between them. But
	 * {@code DownloadActivityType.isZipStream()} is FALSE for hillshade, slope, GeoTIFF, sqlite,
	 * wikivoyage and gpx: those are copied raw, byte for byte. For them the part-file's length IS
	 * the resume offset, and those are also the biggest single downloads in the app - a GeoTIFF
	 * or hillshade region dwarfs a Cairo map.
	 *
	 * <p>So the offset comes in from the caller, which is the only place that knows whether the
	 * bytes on disk are comparable with the bytes on the wire.
	 *
	 * @param resumeFrom byte offset already on disk, or 0 to start from the beginning
	 */
	public InputStream getInputStreamToDownload(URL url, boolean forceWifi, int resumeFrom) throws IOException {
		final int startOffset = Math.max(0, resumeFrom);
		InputStream cis = new InputStream() {
			final byte[] buffer = new byte[BUFFER_SIZE];
			int bufLen;
			int bufRead;
			int length;
			// Seeded from the part-file so the very first connection asks for the remaining range,
			// not just the reconnect loop. `length` is still the FULL size, so the completeness
			// check at the end of reconnect() compares like with like.
			int fileread = startOffset;
			int triesDownload = TRIES_TO_DOWNLOAD;
			boolean notFound;
			boolean first = true;
			private InputStream is;
			
			private void reconnect() throws IOException {
				while (triesDownload > 0) {
					try {
						if (!first) {
							log.info("Reconnecting"); //$NON-NLS-1$
							try {
								Thread.sleep(TIMEOUT_BETWEEN_DOWNLOADS);
							} catch (InterruptedException e) {
							}
						}
						HttpURLConnection conn = NetworkUtils.getHttpURLConnection(url);
						conn.setRequestProperty("User-Agent", Version.getFullVersion(ctx)); //$NON-NLS-1$
						conn.setReadTimeout(AndroidNetworkUtils.READ_TIMEOUT);
						if (fileread > 0) {
							String range = "bytes="+fileread + "-" + (length -1); //$NON-NLS-1$ //$NON-NLS-2$
							conn.setRequestProperty("Range", range);  //$NON-NLS-1$
						}
						conn.setConnectTimeout(AndroidNetworkUtils.CONNECT_TIMEOUT);
						log.info(conn.getResponseMessage() + " " + conn.getResponseCode()); //$NON-NLS-1$
						boolean wifiConnectionBroken = forceWifi && !isWifiConnected();
						if(conn.getResponseCode() == HttpURLConnection.HTTP_NOT_FOUND){
							notFound = true;
							break;
						}
						if ((conn.getResponseCode() != HttpURLConnection.HTTP_PARTIAL  && 
								conn.getResponseCode() != HttpURLConnection.HTTP_OK ) || wifiConnectionBroken) {
							conn.disconnect();
							triesDownload--;
							continue;
						}
						is = conn.getInputStream();
						if (first) {
							// Content-Length means different things depending on the answer, and
							// getting this wrong silently corrupts a resumed file rather than
							// failing it.
							//
							// 206 Partial: the server honoured the Range, so Content-Length is the
							// REMAINDER. `length` has to be the total, because available() is
							// length - fileread and the completeness check at the end of this loop
							// compares the two directly.
							//
							// 200 OK with an offset requested: the server IGNORED the Range and is
							// sending the whole file from byte zero. Appending that to the bytes
							// already on disk would produce a file of the right length and
							// completely wrong contents - which nothing downstream detects. So the
							// resume is abandoned and the caller truncates instead.
							if (conn.getResponseCode() == HttpURLConnection.HTTP_PARTIAL) {
								length = conn.getContentLength() + startOffset;
							} else {
								length = conn.getContentLength();
								if (startOffset > 0) {
									log.info("Server ignored Range, restarting download from 0");
									resumeAccepted = false;
									fileread = 0;
								}
							}
						}

						first = false;
						return;
					} catch (IOException e) {
						log.error("IOException", e); //$NON-NLS-1$
						triesDownload--;
					}
				}
				if(notFound) {
					throw new IOException("File not found "); //$NON-NLS-1$
				} else if(length == 0){
					throw new IOException("File was not fully read"); //$NON-NLS-1$
				} else if(triesDownload == 0 && length != fileread) {
					throw new IOException("File was not fully read"); //$NON-NLS-1$
				}
			}
			// use as prepare
			@Override
			public synchronized void reset() throws IOException {
				reconnect();
			}
			
			@Override
			public int read(byte[] buffer, int offset, int len) throws IOException {
				if (bufLen == -1) {
					return -1;
				}
				if (bufRead >= bufLen) {
					refillBuffer();
				}
				if (bufLen == -1) {
					return -1;
				}
				int av = bufLen - bufRead;
				int min = Math.min(len, av);
				System.arraycopy(this.buffer, bufRead, buffer, offset, min);
				bufRead += min;
				return min;
			}
			
			@Override
			public int read() throws IOException {
				int r = -1;
				if(bufLen == -1) {
					return -1;
				}
				refillBuffer();
				if(bufRead < bufLen) {
					byte b = buffer[bufRead++];
					return b >= 0 ? b : b + 256;
				}
				if (length <= fileread) {
					throw new IOException("File was not fully read"); //$NON-NLS-1$
				}
				return r;
			}
			private void refillBuffer() throws IOException {
				boolean readAgain = bufRead >= bufLen;
				while (readAgain) {
					if (is == null) {
						reconnect();
					}
					try {
						readAgain = false;
						bufRead = 0;
						if ((bufLen = is.read(buffer)) != -1) {
							fileread += bufLen;
							if (interruptDownloading) {
								break;
							}
						}
					} catch (IOException e) {
						if(interruptDownloading) 
						log.error("IOException", e); //$NON-NLS-1$
						triesDownload--;
						reconnect();
						readAgain = true;
					}
				}
				if (interruptDownloading) {
					throw new IOException("Interrupted");
				}
			}
			
			@Override
			public void close() throws IOException {
				if (is != null) {
					is.close();
				}
			}
			
			@Override
			public int available() throws IOException {
				if (is == null) {
					reconnect();
				}
				return length - fileread;
			}
		};
		cis.reset();
		return cis;
	}
	
	public boolean isWifiConnected(){
		return ctx.getSettings().isWifiConnected();
	}

	public boolean downloadFile(IndexItem.DownloadEntry de, IProgress progress,
								List<File> toReIndex, DownloadFileShowWarning showWarningCallback, boolean forceWifi) throws InterruptedException {
		try {
			List<InputStream> downloadInputStreams = new ArrayList<InputStream>();
			URL url = new URL(de.urlToDownload); //$NON-NLS-1$
			log.info("Url downloading " + de.urlToDownload);
			de.fileToDownload = de.targetFile;
			if (!de.unzipFolder) {
				de.fileToDownload = FileUtils.getFileWithDownloadExtension(de.targetFile);
			}
			// D1. Resume across an app restart, for the downloads where it is meaningful.
			//
			// Only when the payload is copied RAW (see getInputStreamToDownload): then the
			// part-file's length is exactly how many bytes the server already sent, and asking for
			// the rest is correct. On the zip path the file holds DECOMPRESSED bytes and this
			// number would be a fiction that silently corrupts the download - hence the
			// isZipStream guard rather than a general "does a part-file exist" check.
			//
			// unzipFolder is excluded because fileToDownload is then a DIRECTORY, and its length()
			// is meaningless.
			int resumeFrom = 0;
			if (!de.zipStream && !de.unzipFolder && de.fileToDownload.isFile()) {
				long existing = de.fileToDownload.length();
				// int, because the whole counting path is int-based. A part-file at or beyond 2 GB
				// cannot be expressed here, so it restarts rather than resuming from a wrapped
				// negative offset.
				if (existing > 0 && existing < Integer.MAX_VALUE) {
					resumeFrom = (int) existing;
					log.info("Resuming " + de.fileToDownload.getName() + " at " + resumeFrom + " bytes");
				}
			}
			resumeOffset = resumeFrom;
			// Assumed until the server says otherwise inside reconnect().
			resumeAccepted = resumeFrom > 0;

			// D1, second half: resume a ZIPPED download too - .obf maps included.
			//
			// The blocker was real and is stated above: on the streaming path the file on disk
			// holds DECOMPRESSED bytes, so its length says nothing about how many COMPRESSED bytes
			// the server sent, and no offset maps between them. That is a fact about streaming
			// download-and-decompress together, not about zip files.
			//
			// So this splits them. Phase one downloads the raw compressed bytes to a .part file,
			// where the length IS the number of bytes received and Range resume is exactly correct.
			// Phase two decompresses that completed file locally, with no network involved. An
			// interrupted download then resumes at the byte it stopped on instead of restarting a
			// multi-hundred-megabyte Egypt map from zero.
			//
			// The cost is disk: compressed and decompressed exist together for the length of phase
			// two. That is why it is checked for space first and falls back to streaming rather
			// than failing - a device that cannot afford the temporary copy still gets its map.
			if (useTwoPhase(de)) {
				if (downloadZippedInTwoPhases(de, progress, url, forceWifi)) {
					return finishDownload(de, toReIndex, showWarningCallback);
				}
				// Fell through: not resumable, no space, or the raw phase failed in a way that
				// leaves streaming a better bet. Reset the resume state the streaming path reads.
				resumeOffset = 0;
				resumeAccepted = false;
			}
			downloadInputStreams.add(getInputStreamToDownload(url, forceWifi, resumeFrom));
			unzipFile(de, progress, downloadInputStreams);
			return finishDownload(de, toReIndex, showWarningCallback);
		} catch (IOException e) {
			log.error("Exception ocurred", e);
			showWarningCallback.showWarning(ctx.getString(R.string.shared_string_io_error) + ": " + e.getMessage());
			// Possibly file is corrupted
			Algorithms.removeAllFiles(de.fileToDownload);
			return false;
		}
	}

	private void removeFilesAfterSuccessfulInstall(@NonNull DownloadEntry entry) {
		if (entry.targetFile == null || entry.filesToDeleteAfterSuccessfulInstall == null) {
			return;
		}
		File targetFile = entry.targetFile.getAbsoluteFile();
		boolean filesRemoved = false;
		for (File file : entry.filesToDeleteAfterSuccessfulInstall) {
			if (file != null && !targetFile.equals(file.getAbsoluteFile())
					&& removeFileAndCloseResource(file)) {
				filesRemoved = true;
			}
		}
		if (filesRemoved) {
			ctx.getDownloadThread().updateLoadedFiles();
		}
	}

	private boolean removeFileAndCloseResource(@NonNull File file) {
		if (file.exists() && Algorithms.removeAllFiles(file)) {
			ctx.getResourceManager().closeFile(file.getName());
			return true;
		}
		return false;
	}

	private void removePreviousSrtmFile(DownloadEntry entry) {
		String meterExt = IndexConstants.BINARY_SRTM_MAP_INDEX_EXT;
		String feetExt = IndexConstants.BINARY_SRTM_FEET_MAP_INDEX_EXT;

		String fileName = entry.targetFile.getAbsolutePath();
		if (fileName.endsWith(meterExt)) {
			fileName = fileName.replace(meterExt, feetExt);
		} else if (fileName.endsWith(feetExt)) {
			fileName = fileName.replace(feetExt, meterExt);
		}

		removeFileAndCloseResource(new File(fileName));
	}

	/**
	 * The install step, shared by the streaming path and the two-phase path.
	 *
	 * <p>Extracted rather than duplicated: it renames the part-file over the target, records the
	 * file for re-indexing and removes the superseded SRTM variant. Two copies of that would
	 * eventually disagree about which of those three steps a new download type needs.
	 */
	private boolean finishDownload(IndexItem.DownloadEntry de, List<File> toReIndex,
	                               DownloadFileShowWarning showWarningCallback) {
		if (!de.targetFile.getAbsolutePath().equals(de.fileToDownload.getAbsolutePath())) {
			ResourceManager rm = ctx.getResourceManager();
			boolean success = FileUtils.replaceTargetFile(rm, de.fileToDownload, de.targetFile);
			if (!success) {
				showWarningCallback.showWarning(ctx.getString(R.string.shared_string_io_error) + ": old file can't be deleted");
				return false;
			}
		}
		removeFilesAfterSuccessfulInstall(de);
		if (de.type == DownloadActivityType.SRTM_COUNTRY_FILE) {
			removePreviousSrtmFile(de);
		}
		toReIndex.add(de.targetFile);
		return true;
	}

	/** Suffix of the raw compressed part-file. Distinct from the {@code .download} target. */
	private static final String RAW_PART_EXT = ".zipart";

	/**
	 * Multiplier on the compressed size used as the free-space requirement for phase two.
	 *
	 * <p>An {@code .obf} compresses to very roughly a third, so the decompressed file plus the
	 * retained compressed one is about four times the download. Four is therefore not padding, it
	 * is the actual peak - and being wrong in the optimistic direction means filling the device
	 * mid-extract, which is a worse outcome than not resuming.
	 */
	private static final long TWO_PHASE_SPACE_FACTOR = 4;

	/**
	 * Whether this entry can use download-then-decompress.
	 *
	 * <p>Zipped, single-file entries only. {@code unzipFolder} writes many files into a directory
	 * and has no single target to rename, and {@code .gz} is excluded only because it is used for
	 * small auxiliary payloads where the temporary copy costs more than the resume saves.
	 */
	private boolean useTwoPhase(IndexItem.DownloadEntry de) {
		return net.osmand.plus.BuildConfig.CAIRODRIVE_RESUME_ZIP
				&& de.zipStream
				&& !de.unzipFolder
				&& de.urlToDownload != null
				&& !de.urlToDownload.contains(".gz");
	}

	/**
	 * Phase one to a {@code .zipart} file, then phase two out of it. See the D1 note in
	 * {@link #downloadFile}.
	 *
	 * @return true when the target file is fully written; false to fall back to streaming, in
	 * which case nothing has been left behind that the streaming path would misread
	 */
	private boolean downloadZippedInTwoPhases(IndexItem.DownloadEntry de, IProgress progress,
	                                          URL url, boolean forceWifi) throws IOException {
		File part = new File(de.fileToDownload.getAbsolutePath() + RAW_PART_EXT);
		part.getParentFile().mkdirs();

		long already = part.isFile() ? part.length() : 0;
		if (already >= Integer.MAX_VALUE) {
			// The counting path below is int-based, like the rest of this class. Start over rather
			// than resume from a wrapped negative offset.
			part.delete();
			already = 0;
		}
		resumeOffset = (int) already;
		resumeAccepted = already > 0;

		// getInputStreamToDownload ends with cis.reset(), which connects - so by the time this
		// returns, the server has already answered and resumeAccepted reflects what it actually
		// did with the Range header.
		InputStream in = getInputStreamToDownload(url, forceWifi, (int) already);
		try {
			// available() is length - fileread, i.e. what is LEFT to fetch, not the whole file.
			// Reading it as the total would under-state the space needed on every resume and make
			// the progress bar jump backwards, so the full size is reconstructed here.
			int remaining = in.available();
			boolean append = resumeAccepted;
			long fullSize = append ? already + remaining : remaining;

			// The server ignored the Range and is re-sending from byte zero. Appending that to the
			// existing part file would produce a file of plausible length and entirely wrong
			// contents - a corrupt map that nothing downstream detects. Truncating is the only
			// correct response, and it is why `append` comes from resumeAccepted rather than from
			// "a part file existed".
			if (already > 0 && !append) {
				log.info("Server ignored Range on " + part.getName() + ", restarting from 0");
			}

			long needed = fullSize * TWO_PHASE_SPACE_FACTOR;
			long free = part.getParentFile().getUsableSpace();
			if (free > 0 && free < needed) {
				log.info("Two-phase download declined: need ~" + (needed >> 20) + " MB, have "
						+ (free >> 20) + " MB. Streaming instead.");
				return false;
			}
			String name = FileNameTranslationHelper.getFileName(ctx, ctx.getRegions(), de.baseName);
			progress.startTask(String.format(
					ctx.getString(R.string.shared_string_downloading_formatted), name),
					(int) (fullSize / 1024));
			if (!writeRaw(in, part, append, fullSize, progress)) {
				return false;
			}
		} finally {
			try {
				in.close();
			} catch (IOException ignored) {
			}
		}

		// Phase two: local only. A failure here is NOT a reason to keep the part file - the bytes
		// are on disk and complete, so a corrupt archive means the download itself was bad and
		// retrying the same bytes would fail identically.
		FileInputStream raw = new FileInputStream(part);
		try {
			resumeOffset = 0;
			resumeAccepted = false;
			List<InputStream> streams = new ArrayList<InputStream>();
			streams.add(raw);
			unzipFile(de, progress, streams);
		} catch (IOException e) {
			part.delete();
			throw e;
		} finally {
			try {
				raw.close();
			} catch (IOException ignored) {
			}
		}
		part.delete();
		return true;
	}

	/**
	 * Streams the raw compressed bytes to the part file, appending when resuming.
	 *
	 * @return false when the download was interrupted; the part file is KEPT in that case, which
	 * is the entire point of this path
	 */
	private boolean writeRaw(InputStream in, File part, boolean append, long fullSize,
	                         IProgress progress) throws IOException {
		// Read BEFORE opening the stream: `new FileOutputStream(part, false)` truncates on
		// construction, so asking afterwards would always answer 0 and the progress bar would
		// restart at the top of a resumed download.
		long written = append ? part.length() : 0;
		FileOutputStream out = new FileOutputStream(part, append);
		try {
			byte[] buffer = new byte[BUFFER_SIZE];
			int read;
			while ((read = in.read(buffer)) != -1) {
				if (interruptDownloading) {
					// Flushed and closed by the finally below, and deliberately NOT deleted:
					// everything received so far is valid compressed bytes and the next attempt
					// continues from exactly here. This is the whole point of the two-phase path.
					log.info("Interrupted, keeping " + part.getName() + " at " + written
							+ " of " + fullSize + " bytes for resume");
					return false;
				}
				out.write(buffer, 0, read);
				written += read;
				progress.remaining((int) ((fullSize - written) / 1024));
			}
		} finally {
			out.close();
		}
		return true;
	}

	private void unzipFile(IndexItem.DownloadEntry de, IProgress progress,  List<InputStream> is) throws IOException {
		CountingMultiInputStream fin = new CountingMultiInputStream(is);
		int len = fin.available();
		int mb = (int) (len / (1024f*1024f));
		if(mb == 0) {
			mb = 1;
		}
		StringBuilder taskName = new StringBuilder();
		//+ de.baseName /*+ " " + mb + " MB"*/;
		taskName.append(FileNameTranslationHelper.getFileName(ctx, ctx.getRegions(), de.baseName));
		if (de.type != null) {
			taskName.append(" ").append(de.type.getString(ctx));
		}
		progress.startTask(String.format(ctx.getString(R.string.shared_string_downloading_formatted), taskName), len / 1024);
		if (!de.zipStream) {
			copyFile(de, progress, fin, len, fin, de.fileToDownload);
		} else if(de.urlToDownload.contains(".gz")) {
			GZIPInputStream zipIn = new GZIPInputStream(fin);
			copyFile(de, progress, fin, len, zipIn, de.fileToDownload);
		} else {
			if (de.unzipFolder) {
				de.fileToDownload.mkdirs();
			} 
			ZipInputStream zipIn = new ZipInputStream(fin);
			ZipEntry entry = null;
			boolean first = true;
			while ((entry = zipIn.getNextEntry()) != null) {
				if (entry.isDirectory() || entry.getName().endsWith(IndexConstants.GEN_LOG_EXT)) {
					continue;
				}
				File fs;
				if (!de.unzipFolder) {
					if (first) {
						fs = de.fileToDownload;
						first = false;
					} else {
						String name = entry.getName();
						// small simplification
						int ind = name.lastIndexOf('_');
						if (ind > 0) {
							// cut version
							int i = name.indexOf('.', ind);
							if (i > 0) {
								name = name.substring(0, ind) + name.substring(i);
							}
						}
						fs = new File(de.fileToDownload.getParent(), name);
					}
				} else {
					fs = new File(de.fileToDownload, entry.getName());
				}
				copyFile(de, progress, fin, len, zipIn, fs);
			}
			zipIn.close();
		}
		fin.close();
	}

	/**
	 * Set by downloadFile for the current entry; see the D1 note there. Zero means "start fresh",
	 * which is every zipped download and every first attempt.
	 */
	private int resumeOffset;
	/**
	 * Cleared when a server answers a ranged request with 200 rather than 206. The bytes then
	 * start at zero, so the part-file must be overwritten, not appended to.
	 */
	private volatile boolean resumeAccepted;

	private void copyFile(IndexItem.DownloadEntry de, IProgress progress, 
			CountingMultiInputStream countIS, int length, InputStream toRead, File targetFile)
			throws IOException {
		targetFile.getParentFile().mkdirs();
		// Append when resuming, or the Range request's bytes land at offset 0 and overwrite the
		// beginning of the file - producing a file of the right SIZE and entirely wrong contents,
		// which is worse than a failed download because nothing detects it.
		boolean append = resumeOffset > 0 && resumeAccepted && targetFile.equals(de.fileToDownload);
		FileOutputStream out = new FileOutputStream(targetFile, append);
		try {
			int read;
			byte[] buffer = new byte[BUFFER_SIZE];
			int remaining = length;
			while ((read = toRead.read(buffer)) != -1) {
				out.write(buffer, 0, read);
				remaining -= countIS.getAndClearReadCount();
				progress.remaining(remaining / 1024);
			}
		} finally {
			out.close();
		}
		targetFile.setLastModified(de.dateModified);
	}
	
	
	public void setInterruptDownloading(boolean interruptDownloading) {
		this.interruptDownloading = interruptDownloading;
	}
	
	public boolean isInterruptDownloading() {
		return interruptDownloading;
	}
	
	private static class CountingMultiInputStream extends InputStream {

		private final InputStream[] delegate;
		private int count;
		private int currentRead;

		public CountingMultiInputStream(List<InputStream> streams) {
			this.delegate = streams.toArray(new InputStream[0]);
		}
		
		@Override
		public int read(byte[] buffer, int offset, int length)
				throws IOException {
			int r = -1;
			while (r == -1 && currentRead < delegate.length) {
				r = delegate[currentRead].read(buffer, offset, length);
				if (r == -1) {
					delegate[currentRead].close();
					currentRead++;
				}
			}
			if (r > 0) {
				this.count += r;
			}
			return r;
		}
		
		@Override
		public int read() throws IOException {
			if (currentRead >= delegate.length) {
				return -1;
			}
			int r = -1;
			while (r == -1 && currentRead < delegate.length) {
				r = delegate[currentRead].read();
				if (r == -1) {
					delegate[currentRead].close();
					currentRead++;
				} else {
					this.count++;
				}
			}
			return r;
		}

		@Override
		public int available() throws IOException {
			int av = 0;
			for (int i = currentRead; i < delegate.length; i++) {
				av += delegate[i].available();
			}
			return av;
		}

		public int getAndClearReadCount() {
			int last = count;
			count = 0;
			return last;
		}
	}
}
