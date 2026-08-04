package net.osmand.plus.voice;

import static net.osmand.IndexConstants.TTSVOICE_INDEX_EXT_JS;
import static net.osmand.IndexConstants.VOICE_PROVIDER_SUFFIX;

import android.content.Intent;
import android.media.AudioAttributes;
import android.os.Build;
import android.speech.tts.TextToSpeech;
import android.speech.tts.Voice;
import android.speech.tts.TextToSpeech.OnUtteranceCompletedListener;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.plus.cairodrive.CairoDriveSpeechClock;
import net.osmand.plus.R;
import net.osmand.plus.api.AudioFocusHelperImpl;
import net.osmand.plus.routing.VoiceRouter;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.preferences.OsmandPreference;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class JsTtsCommandPlayer extends CommandPlayer {

	private static final Log log = PlatformUtil.getLog(JsTtsCommandPlayer.class);

	private static final String PEBBLE_ALERT = "PEBBLE_ALERT";

	private static TextToSpeech mTts;

	private final HashMap<String, String> params = new HashMap<>();

	/**
	 * Since TTS requests are asynchronous, playCommands() can be called before
	 * the TTS engine is done. We use this field to keep track of concurrent tts
	 * activity. Where tts activity is defined as the time between tts.speak()
	 * and the call back to onUtteranceCompletedListener().  This allows us to
	 * optimize use of requesting and abandoning audio focus.
	 */
	private static int ttsRequests;
	private float cSpeechRate = 1;
	private boolean speechAllowed;

	// Only for debugging
	private static String ttsVoiceStatus = "-";
	private static String ttsVoiceUsed = "-";

	protected JsTtsCommandPlayer(@NonNull OsmandApplication app,
	                             @NonNull ApplicationMode applicationMode,
	                             @NonNull VoiceRouter voiceRouter,
	                             @NonNull File voiceProviderDir) throws CommandPlayerException {
		super(app, applicationMode, voiceRouter, voiceProviderDir);

		if (app.accessibilityEnabled()) {
			cSpeechRate = settings.SPEECH_RATE.get();
		}
		initializeEngine();
		params.put(TextToSpeech.Engine.KEY_PARAM_STREAM, settings.AUDIO_MANAGER_STREAM
				.getModeValue(app.getRoutingHelper().getAppMode()).toString());
	}

	@NonNull
	@Override
	public File getTtsFileFromDir(@NonNull File voiceProviderDir) {
		String fileName = voiceProviderDir.getName().replace(VOICE_PROVIDER_SUFFIX, "_" + TTSVOICE_INDEX_EXT_JS);
		return new File(voiceProviderDir, fileName);
	}

	private void initializeEngine() {
		internalClear();

		if (mTts == null) {
			ttsVoiceStatus = "-";
			ttsVoiceUsed = "-";
			ttsRequests = 0;

			final TextToSpeech[] textToSpeech = new TextToSpeech[1];
			textToSpeech[0] = new TextToSpeech(app, status -> {
				if (mTts != textToSpeech[0]) {
					log.info("Obsolete TTS instance finished initializing. Shutting it down.");
					if (textToSpeech[0] != null) {
						textToSpeech[0].shutdown();
					}
					return;
				}
				if (status != TextToSpeech.SUCCESS) {
					ttsVoiceStatus = "NO INIT SUCCESS";
					internalClear();
					app.showToastMessage(R.string.tts_initialization_error);
				} else {
					TextToSpeech tts = mTts;
					if (tts != null) {
						Locale locale = new LocaleBuilder(app, tts, language).buildLocale();
						onSuccessfulTtsInit(locale, cSpeechRate);
					}
				}
			});
			mTts = textToSpeech[0];

			mTts.setOnUtteranceCompletedListener(new OnUtteranceCompletedListener() {
				// The call back is on a binder thread.
				@Override
				public synchronized void onUtteranceCompleted(String utteranceId) {
					// N7. Closes the timing loop on the utterances that started with an empty
					// queue - see playCommands below. Ids this never saw are ignored, so a queued
					// utterance cannot pollute the measurement with the time it spent waiting.
					// First, before the audio-focus bookkeeping: that path can throw on some head
					// units, and losing the sample to it would silently freeze the estimate at its
					// cold-start prior for the whole drive.
					CairoDriveSpeechClock.getInstance().onUtteranceCompleted(utteranceId);
					if (--ttsRequests <= 0) {
						abandonAudioFocus();
					}
					log.debug("ttsRequests=" + ttsRequests);
					if (ttsRequests < 0) {
						ttsRequests = 0;
					}
				}
			});
		}
	}

	private void onSuccessfulTtsInit(@NonNull Locale locale, float speechRate) {
		speechAllowed = true;
		TextToSpeech mTts = JsTtsCommandPlayer.mTts;
		if(mTts != null) {
			switch (mTts.isLanguageAvailable(locale)) {
				case TextToSpeech.LANG_NOT_SUPPORTED:
					ttsVoiceStatus = locale.getDisplayName() + ": LANG_NOT_SUPPORTED";
					ttsVoiceUsed = getVoiceUsed();
					break;
				case TextToSpeech.LANG_MISSING_DATA:
					ttsVoiceStatus = locale.getDisplayName() + ": LANG_MISSING_DATA";
					ttsVoiceUsed = getVoiceUsed();
					break;
				case TextToSpeech.LANG_AVAILABLE:
					ttsVoiceStatus = locale.getDisplayName() + ": LANG_AVAILABLE";
				case TextToSpeech.LANG_COUNTRY_AVAILABLE:
					ttsVoiceStatus = "-".equals(ttsVoiceStatus)
							? locale.getDisplayName() + ": LANG_COUNTRY_AVAILABLE"
							: ttsVoiceStatus;
				case TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE:
					try {
						mTts.setLanguage(locale);
					} catch (Exception e) {
						log.error(e);
						if (mTts.isLanguageAvailable(Locale.getDefault()) > 0) {
							mTts.setLanguage(Locale.getDefault());
						} else {
							app.showToastMessage("TTS language not available");
						}
					}
					if (speechRate != 1) {
						mTts.setSpeechRate(speechRate);
					}
					ttsVoiceStatus = "-".equals(ttsVoiceStatus)
							? locale.getDisplayName() + ": LANG_COUNTRY_VAR_AVAILABLE"
							: ttsVoiceStatus;
					ttsVoiceUsed = getVoiceUsed();
					break;
			}
			logVoiceState(locale);
		}
	}

	/**
	 * CD_VOICE. Until this existed, CLAUDE.md carried a rule keyed on seeing LANG_MISSING_DATA in a
	 * drive log - and that could never happen: the status only ever landed in the private static
	 * ttsVoiceStatus, which nothing but the Development plugin's test screen reads, and no AOSP code
	 * prints that string either. The rule was undiagnosable by construction.
	 * <p>
	 * The rule's description was also backwards. speechAllowed is set to true BEFORE the
	 * availability switch above, and the two failure arms break without calling setLanguage(), so
	 * prompts are not silent - they are spoken by whatever locale the engine defaulted to. An Arabic
	 * street name read by an English voice is noise, not silence.
	 * <p>
	 * network= is the other thing worth having: AOSP documents LATENCY_HIGH as "network based,
	 * around 200 ms" and VERY_HIGH as ">200 ms". If the Arabic voice turns out to be a network voice,
	 * that alone explains late prompts on Cairo mobile data with no code change at all.
	 */
	private void logVoiceState(@NonNull Locale locale) {
		try {
			TextToSpeech tts = JsTtsCommandPlayer.mTts;
			if (tts == null) {
				return;
			}
			int availability = tts.isLanguageAvailable(locale);
			StringBuilder sb = new StringBuilder();
			sb.append("provider=").append(app.getSettings().VOICE_PROVIDER.get())
					.append(" locale=").append(locale)
					.append(" availability=").append(availabilityName(availability))
					.append(" speechAllowed=").append(speechAllowed)
					.append(" engine=").append(tts.getDefaultEngine());
			Voice voice = null;
			try {
				voice = tts.getVoice();
			} catch (Exception ignored) {
				// some engines throw here before they are fully ready
			}
			if (voice != null) {
				sb.append(" voice=").append(voice.getName())
						.append(" latency=").append(voice.getLatency())
						.append(" network=").append(voice.isNetworkConnectionRequired())
						.append(" quality=").append(voice.getQuality());
			} else {
				sb.append(" voice=null");
			}
			int stream = app.getSettings().AUDIO_MANAGER_STREAM.get();
			sb.append(" stream=").append(stream);
			if (stream >= 0 && stream < app.getSettings().AUDIO_USAGE.length
					&& app.getSettings().AUDIO_USAGE[stream] != null) {
				sb.append(" usage=").append(app.getSettings().AUDIO_USAGE[stream].get());
			}
			// Milliseconds, not seconds - AnnounceTimeDistances divides this by 1000. Same bounds
			// check as AUDIO_USAGE above: an out-of-range index would throw inside the try and the
			// catch would then swallow the ENTIRE CD_VOICE line, losing the diagnostic precisely
			// when the configuration is odd enough to be worth diagnosing.
			if (stream >= 0 && stream < app.getSettings().VOICE_PROMPT_DELAY.length
					&& app.getSettings().VOICE_PROMPT_DELAY[stream] != null) {
				sb.append(" promptDelayMs=").append(app.getSettings().VOICE_PROMPT_DELAY[stream].get());
			}
			CairoDriveLogger.getInstance().log("CD_VOICE", sb.toString());
		} catch (Exception e) {
			log.error("CD_VOICE logging failed", e);
		}
	}

	@NonNull
	private static String availabilityName(int availability) {
		switch (availability) {
			case TextToSpeech.LANG_NOT_SUPPORTED:
				return "LANG_NOT_SUPPORTED";
			case TextToSpeech.LANG_MISSING_DATA:
				return "LANG_MISSING_DATA";
			case TextToSpeech.LANG_AVAILABLE:
				return "LANG_AVAILABLE";
			case TextToSpeech.LANG_COUNTRY_AVAILABLE:
				return "LANG_COUNTRY_AVAILABLE";
			case TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE:
				return "LANG_COUNTRY_VAR_AVAILABLE";
			default:
				return "UNKNOWN(" + availability + ")";
		}
	}

	@NonNull
	private String getVoiceUsed() {
		try {
			if (mTts.getVoice() != null) {
				return mTts.getVoice().toString() + " (API " + Build.VERSION.SDK_INT + ")";
			}
		} catch (Exception e) {
			log.error(e);
		}
		return "-";
	}

	// Called from the calculating route thread.
	@NonNull
	@Override
	public synchronized List<String> playCommands(@NonNull CommandBuilder builder) {
		List<String> execute = builder.execute(); //list of strings, the speech text, play it
		StringBuilder bld = new StringBuilder();
		for (String s : execute) {
			bld.append(s).append(' ');
		}
		sendAlertToPebble(bld.toString());
		if (mTts != null && !voiceRouter.isMute() && speechAllowed) {
			// N7. Whether this utterance will start speaking straight away or sit behind another
			// one. Only the former can be timed: with QUEUE_ADD, a queued utterance's elapsed time
			// measures the queue, not the speech. Captured before the increment below, which is
			// what consumes the information.
			boolean startsImmediately = ttsRequests == 0;
			if (ttsRequests++ == 0) {
				requestAudioFocus();
				mTts.setAudioAttributes(new AudioAttributes.Builder()
						.setUsage(settings.AUDIO_USAGE[settings.AUDIO_MANAGER_STREAM.getModeValue(app.getRoutingHelper().getAppMode())].get())
						.setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
						.build());
				// Delay first prompt of each batch to allow BT SCO link being established, or when VOICE_PROMPT_DELAY is set >0 for the other stream types
				if (app != null) {
					Integer streamModeValue = settings.AUDIO_MANAGER_STREAM.getModeValue(app.getRoutingHelper().getAppMode());
					OsmandPreference<Integer> pref = settings.VOICE_PROMPT_DELAY[streamModeValue];
					int vpd = pref == null ? 0 : pref.getModeValue(app.getRoutingHelper().getAppMode());
					if (vpd > 0) {
						ttsRequests++;
						mTts.playSilentUtterance(vpd, TextToSpeech.QUEUE_ADD, "" + System.currentTimeMillis());
						// The silence is now ahead of us in the queue, so this utterance no longer
						// starts immediately and its elapsed time would include vpd milliseconds
						// of nothing.
						startsImmediately = false;
					}
				}
			}
			log.debug("ttsRequests=" + ttsRequests);
			String utteranceId = "" + System.currentTimeMillis();
			params.put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId);
			if (AudioFocusHelperImpl.playbackAuthorized) {
				// N7. Start the clock on this utterance only if it is genuinely first in the queue.
				// The completion callback above stops it, giving a real measured duration for a
				// known piece of text - which is how CairoDriveSpeechClock stops guessing at this
				// device's speaking rate. Registered before speak() so a very short utterance
				// cannot complete before we are watching for it.
				if (startsImmediately) {
					CairoDriveSpeechClock.getInstance().onUtteranceStarted(utteranceId, bld.toString());
				}
				mTts.speak(bld.toString(), TextToSpeech.QUEUE_ADD, params);
			} else {
				stop();
			}
			// Audio focus will be released when onUtteranceCompleted() completed is called by the TTS engine.
		}
		// #5966: TTS Utterance for debugging
		if (app != null && settings.DISPLAY_TTS_UTTERANCE.get()) {
			app.showToastMessage(bld.toString());
		}
		return execute;
	}

	private void sendAlertToPebble(@NonNull String bld) {
		Intent i = new Intent("com.getpebble.action.SEND_NOTIFICATION");
		Map<String, Object> data = new HashMap<>();
		data.put("title", "Voice");
		data.put("body", bld);
		JSONObject jsonData = new JSONObject(data);
		String notificationData = new JSONArray().put(jsonData).toString();
		i.putExtra("messageType", PEBBLE_ALERT);
		i.putExtra("sender", "OsmAnd");
		i.putExtra("notificationData", notificationData);
		if (app != null) {
			app.sendBroadcast(i);
			log.info("Send message to pebble " + bld);
		}
	}

	@NonNull
	@Override
	public CommandBuilder newCommandBuilder() {
		JsCommandBuilder commandBuilder = new JsCommandBuilder(this);
		commandBuilder.setJSContext(jsScope);
		commandBuilder.setParameters(settings.METRIC_SYSTEM.get().toTTSString(), true);
		return commandBuilder;
	}

	@Override
	public void updateAudioStream(int streamType) {
		super.updateAudioStream(streamType);
		params.put(TextToSpeech.Engine.KEY_PARAM_STREAM, streamType + "");
	}

	@Override
	public void stop() {
		ttsRequests = 0;
		if (mTts != null) {
			mTts.stop();
		}
		abandonAudioFocus();
	}

	@Override
	public void clear() {
		super.clear();
		internalClear();
	}

	private void internalClear() {
		ttsRequests = 0;
		speechAllowed = false;
		if (mTts != null) {
			mTts.shutdown();
			mTts = null;
		}
		abandonAudioFocus();
		ttsVoiceStatus = "-";
		ttsVoiceUsed = "-";
	}

	@Override
	public boolean supportsStructuredStreetNames() {
		return true;
	}

	@Override
	public boolean supportsFreeText() {
		// Every string in the list is handed straight to the TTS engine, so a sentence the voice
		// grammar never produced is spoken exactly as written.
		return true;
	}

	@NonNull
	public static String getTtsVoiceStatus() {
		return ttsVoiceStatus;
	}

	@NonNull
	public static String getTtsVoiceUsed() {
		return ttsVoiceUsed;
	}

	public static boolean isMyData(@NonNull File dir) {
		String name = dir.getName();
		if (!name.contains("tts")) {
			return false;
		}
		File file = getLangFile(dir);
		return file != null && file.exists();
	}

	@Nullable
	public static File getLangFile(@NonNull File dir) {
		String name = dir.getName();
		if (!name.contains("tts")) {
			return null;
		}
		String langName = name.replace(VOICE_PROVIDER_SUFFIX, "");
		return new File(dir, langName + "_" + TTSVOICE_INDEX_EXT_JS);
	}
}
