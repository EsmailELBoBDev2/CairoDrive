package net.osmand.plus.cairodrive;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;

import org.apache.commons.logging.Log;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * How long a voice prompt will take to say, in milliseconds.
 *
 * <p>Nothing in OsmAnd knows this. Every announcement threshold in
 * {@code AnnounceTimeDistances} is a lead <em>distance</em>: the prompt is triggered at a fixed
 * time-to-junction and whatever the TTS engine then does with the text is unaccounted for. That is
 * fine for "turn right" and wrong for "turn right onto Ring Road, Salah Salem Street toward Nasr
 * City and the Airport" - which, in Arabic, is still being spoken when the junction goes past.
 * {@code VOICE_PROMPT_DELAY} does not help: it is a fixed silence prepended to the utterance, and
 * {@code isDistanceLess} already pads the trigger by exactly the same amount, so it cancels out. The
 * speech itself is compensated nowhere.
 *
 * <h3>The model</h3>
 * <p>{@code overhead + chars * msPerChar}, with a separate {@code msPerChar} per script, because
 * Arabic and Latin pack very different amounts of speech into a character.
 *
 * <p><b>Latin, 67 ms/char (~15 chars/s).</b> Android TTS at {@code setSpeechRate(1.0)} lands around
 * 150-160 words per minute - the "normal conversational" target every TTS engine is tuned to.
 * English averages ~4.7 letters per word, ~5.7 counting the following space. 155 wpm * 5.7 = 884
 * chars/min = 14.7 chars/s = 68 ms/char.
 *
 * <p><b>Arabic, 100 ms/char (~10 chars/s).</b> Cross-language work on speech rate (Pellegrino,
 * Coupe &amp; Marsico, "A cross-language perspective on speech information rate", Language 87(3),
 * 2011) puts syllable rates in a 5.2-7.8 syll/s band across the languages measured; Arabic is not in
 * that set, so take the middle, ~6 syll/s. Undiacritised Modern Standard Arabic does not write short
 * vowels, so a CV syllable is typically one letter and the average is roughly 1.8 letters per
 * syllable including inter-word spaces. 6 * 1.8 = 10.8 chars/s, call it 10.
 *
 * <p><b>Overhead, 350 ms.</b> Audio focus request, engine synthesis before the first sample, and the
 * leading silence most voices put in front of an utterance.
 *
 * <p>Sanity check against a real prompt: "شارع جمال عبد الناصر" is 20 characters and takes about
 * 1.8 s to say. The model gives 2.2 s. Close enough to schedule with, and it does not have to stay a
 * guess - see below.
 *
 * <h3>Why it calibrates itself</h3>
 * <p>Those numbers are a cold-start prior, not a measurement, and the thing they are predicting -
 * this phone, this TTS engine, this voice, this speech rate - is sitting right there. So
 * {@link #onUtteranceStarted} / {@link #onUtteranceCompleted} time real utterances end to end and
 * fold the result into the per-script rate with an EWMA. Only utterances that started with an empty
 * TTS queue are used, because a queued utterance's elapsed time includes everything ahead of it.
 * After a few prompts the numbers above stop mattering.
 *
 * <p>Every accepted sample is written to {@code CD_VOICE ttsSample=}, so a drive log says what the
 * device actually does rather than what this class assumed. That is the point of measuring first.
 */
public class CairoDriveSpeechClock {

	private static final Log log = PlatformUtil.getLog(CairoDriveSpeechClock.class);

	private static final CairoDriveSpeechClock INSTANCE = new CairoDriveSpeechClock();

	/** Cold-start priors - see the class comment for where each number comes from. */
	public static final float PRIOR_MS_PER_CHAR_LATIN = 67f;
	public static final float PRIOR_MS_PER_CHAR_ARABIC = 100f;
	public static final int UTTERANCE_OVERHEAD_MS = 350;

	/**
	 * EWMA weight for a new sample. Deliberately slow: a single utterance that got stuck behind an
	 * audio-focus stall must not be able to move the estimate far enough to change a prompt's
	 * timing on the next turn.
	 */
	private static final float SAMPLE_WEIGHT = 0.2f;

	/** Below this, the fixed overhead dominates and the division is noise, not a measurement. */
	private static final int MIN_SAMPLE_CHARS = 12;
	/** A sample has to be at least this pure to be attributed to one script. */
	private static final float MIN_SCRIPT_PURITY = 0.8f;
	/** Guard rails: anything outside is a stall, a truncated utterance, or a clock artefact. */
	private static final float MIN_PLAUSIBLE_MS_PER_CHAR = 20f;
	private static final float MAX_PLAUSIBLE_MS_PER_CHAR = 400f;

	/** Utterances in flight. Two is already generous; the map is bounded so a lost callback cannot leak. */
	private static final int MAX_PENDING = 4;

	private volatile float msPerCharLatin = PRIOR_MS_PER_CHAR_LATIN;
	private volatile float msPerCharArabic = PRIOR_MS_PER_CHAR_ARABIC;
	private volatile int latinSamples;
	private volatile int arabicSamples;

	private final Map<String, Pending> pending = new LinkedHashMap<String, Pending>() {
		@Override
		protected boolean removeEldestEntry(Map.Entry<String, Pending> eldest) {
			return size() > MAX_PENDING;
		}
	};

	private static class Pending {
		final String text;
		final long startedAt;

		Pending(String text, long startedAt) {
			this.text = text;
			this.startedAt = startedAt;
		}
	}

	private CairoDriveSpeechClock() {
	}

	@NonNull
	public static CairoDriveSpeechClock getInstance() {
		return INSTANCE;
	}

	/**
	 * Milliseconds this text will occupy from the moment the player is asked to speak it until the
	 * last word is out - synthesis included, since that is dead time on the road just the same.
	 */
	public long estimateMs(@Nullable String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		int arabic = 0;
		int length = text.length();
		for (int i = 0; i < length; i++) {
			if (isArabic(text.charAt(i))) {
				arabic++;
			}
		}
		// Everything that is not an Arabic letter is charged at the Latin rate, spaces and
		// punctuation included: they are not silent, they are the pauses between words.
		int other = length - arabic;
		return UTTERANCE_OVERHEAD_MS + Math.round(arabic * msPerCharArabic + other * msPerCharLatin);
	}

	/**
	 * Called when the TTS engine is handed an utterance that starts speaking immediately - i.e. the
	 * queue was empty. Anything queued behind another utterance is not passed here, because its
	 * elapsed time would measure the queue rather than the speech.
	 */
	public synchronized void onUtteranceStarted(@Nullable String utteranceId, @Nullable String text) {
		if (utteranceId == null || text == null || text.isEmpty()) {
			return;
		}
		pending.put(utteranceId, new Pending(text, System.currentTimeMillis()));
	}

	/** Called from the TTS completion callback. Unknown ids - queued utterances - are ignored. */
	public void onUtteranceCompleted(@Nullable String utteranceId) {
		Pending p;
		synchronized (this) {
			p = utteranceId == null ? null : pending.remove(utteranceId);
		}
		if (p != null) {
			observe(p.text, System.currentTimeMillis() - p.startedAt);
		}
	}

	/**
	 * Folds one measured utterance into the per-script rate. Mixed-script text is discarded rather
	 * than split, because there is one measurement and two unknowns.
	 */
	public void observe(@Nullable String text, long measuredMs) {
		if (text == null) {
			return;
		}
		int length = text.length();
		if (length < MIN_SAMPLE_CHARS || measuredMs <= UTTERANCE_OVERHEAD_MS) {
			return;
		}
		int arabic = 0;
		int letters = 0;
		for (int i = 0; i < length; i++) {
			char c = text.charAt(i);
			if (isArabic(c)) {
				arabic++;
				letters++;
			} else if (Character.isLetter(c)) {
				letters++;
			}
		}
		if (letters == 0) {
			return;
		}
		float arabicShare = (float) arabic / letters;
		boolean isArabicSample;
		if (arabicShare >= MIN_SCRIPT_PURITY) {
			isArabicSample = true;
		} else if (arabicShare <= 1f - MIN_SCRIPT_PURITY) {
			isArabicSample = false;
		} else {
			return;
		}
		float perChar = (float) (measuredMs - UTTERANCE_OVERHEAD_MS) / length;
		if (perChar < MIN_PLAUSIBLE_MS_PER_CHAR || perChar > MAX_PLAUSIBLE_MS_PER_CHAR) {
			return;
		}
		float updated;
		if (isArabicSample) {
			updated = msPerCharArabic + SAMPLE_WEIGHT * (perChar - msPerCharArabic);
			msPerCharArabic = updated;
			arabicSamples++;
		} else {
			updated = msPerCharLatin + SAMPLE_WEIGHT * (perChar - msPerCharLatin);
			msPerCharLatin = updated;
			latinSamples++;
		}
		try {
			CairoDriveLogger.getInstance().log("CD_VOICE", String.format(Locale.US,
					"ttsSample script=%s chars=%d measuredMs=%d perChar=%.1f -> %s=%.1f %s",
					isArabicSample ? "ar" : "lat", length, measuredMs, perChar,
					isArabicSample ? "msPerCharAr" : "msPerCharLat", updated, describe()));
		} catch (Exception e) {
			log.error("CD_VOICE ttsSample logging failed", e);
		}
	}

	/** Compact state for the CD_VOICE lines. */
	@NonNull
	public String describe() {
		return String.format(Locale.US, "cal=lat:%.1f/%d,ar:%.1f/%d",
				msPerCharLatin, latinSamples, msPerCharArabic, arabicSamples);
	}

	/**
	 * Arabic script, including the presentation forms a TTS engine may be handed after shaping.
	 * Persian/Urdu letters live in the same blocks and are spoken at a comparable rate, so they are
	 * deliberately not separated out.
	 */
	private static boolean isArabic(char c) {
		return (c >= 0x0600 && c <= 0x06FF)     // Arabic
				|| (c >= 0x0750 && c <= 0x077F) // Arabic Supplement
				|| (c >= 0x08A0 && c <= 0x08FF) // Arabic Extended-A
				|| (c >= 0xFB50 && c <= 0xFDFF) // Arabic Presentation Forms-A
				|| (c >= 0xFE70 && c <= 0xFEFF); // Arabic Presentation Forms-B
	}
}
