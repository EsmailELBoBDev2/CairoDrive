package net.osmand.plus.cairodrive;

import static net.osmand.plus.routing.data.AnnounceTimeDistances.STATE_TURN_IN;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.Location;
import net.osmand.PlatformUtil;
import net.osmand.plus.OsmAndLocationProvider;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.routing.NextDirectionInfo;
import net.osmand.plus.routing.RouteDirectionInfo;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.routing.VoiceRouter;
import net.osmand.plus.routing.data.AnnounceTimeDistances;
import net.osmand.plus.routing.data.StreetName;
import net.osmand.plus.settings.backend.OsmandSettings;
import net.osmand.plus.voice.CommandBuilder;
import net.osmand.plus.voice.CommandPlayer;
import net.osmand.router.TurnType;

import org.apache.commons.logging.Log;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * N7 - schedule the turn instruction so the driver has it BEFORE the junction, not while passing it.
 *
 * <h3>The problem</h3>
 * <p>{@code STATE_TURN_NOW} fires at a fixed lead TIME - 6.7 s of travel for a car, at every speed,
 * because {@code isDistanceLess} scales the lead distance with speed. What it does not account for
 * is how long the sentence takes to say. "Turn right" is under a second. "انعطف يمينًا إلى الطريق
 * الدائري، شارع صلاح سالم باتجاه مدينة نصر والمطار" is around 7.5 s on this device's Arabic voice.
 * The first finishes with 6 s to spare; the second is still talking as the junction goes past, and
 * the driver has heard "turn right onto ..." with the actionable half arriving after the turn.
 * Organic Maps sizes this window as {@code 8 s * speed} clamped to 35-150 m, which is the same fixed
 * -time idea and has the same blind spot. {@code VOICE_PROMPT_DELAY} is not a fix either: it is a
 * silence prepended to the utterance, and both trigger predicates already pad their lead distance by
 * exactly the same amount, so it cancels - see
 * {@link AnnounceTimeDistances#getVoicePromptDelayTimeSec}.
 *
 * <h3>Why this ADDS a prompt and never moves one</h3>
 * <p>The obvious fix - trigger {@code STATE_TURN_NOW} earlier when the text is long - is the exact
 * mistake this fork already made once and had to revert. Each rung of the ladder in
 * {@code VoiceRouter.updateStatus} fires EXACTLY ONCE: {@code nextStatusAfter} advances
 * {@code currentStatus} and {@code statusNotPassed} is permanently false afterwards. So moving a
 * trigger earlier does not add a warning, it MOVES the only one there is. The lane-aware turn-in
 * attempt did precisely that: it fired at 800 m on a four-lane approach at 60 km/h and then said
 * nothing until "turn now" at 111 m - roughly <b>40 seconds of silence</b> on the run-in to the
 * turn, where upstream speaks at 367 m. The rule that came out of it is written into
 * {@code VoiceRouter#isTurnInDue} and into CLAUDE.md: anything in this area has to be ADDITIVE - a
 * new rung - never a moved threshold.
 *
 * <p>So this class does not touch {@code VoiceRouter} at all. It cannot: it holds no reference to
 * the status ladder and has no way to write to it. It watches location fixes, and when the arithmetic
 * says the turn-now prompt will still be speaking at the junction it speaks one extra, short cue
 * beforehand. {@code STATE_TURN_IN} and {@code STATE_TURN_NOW} then fire at their usual distances
 * with their usual text. Whatever this class does or fails to do, the driver hears everything they
 * hear today.
 *
 * <h3>Why the cue is the manoeuvre only, with no street name</h3>
 * <p>Not a stylistic choice - it is forced. Suppose the extra prompt were the full sentence. To
 * finish by the junction it must start {@code speechSec} out; the turn-now prompt starts
 * {@code 6.7 s} out; so the two overlap unless {@code speechSec < 6.7}, which is exactly the case
 * where nothing needed doing. A full early prompt would therefore ALWAYS be queued against the
 * existing one - two voices' worth of text back to back into the junction. The cue is short
 * ("turn right", ~1.2 s) precisely so it can be placed with room to finish before the existing
 * prompt starts, and the scheduler below proves that room exists before it opens its mouth.
 *
 * <p>What the driver gets: the direction in time, then the full sentence with the street name at the
 * normal moment as confirmation. What this does NOT do is make the long sentence itself finish by
 * the junction - that would require moving the turn-now prompt, which is the forbidden change.
 *
 * <h3>The window</h3>
 * <p>Per fix, with {@code v} = current speed:
 * <pre>
 *   nowM      = turn-now trigger distance          (~6.7 s of travel, + v*promptDelay)
 *   inM       = turn-in trigger distance           (~22 s of travel, + v*promptDelay)
 *   fullSec   = estimated duration of the turn-now sentence
 *   cueSec    = estimated duration of the cue
 *
 *   late?      v*fullSec > nowM - v*promptDelay            else NOT_NEEDED, stay silent
 *   neededM  = v*(fullSec + promptDelay)                   finish-on-time point
 *   latestM  = nowM + v*(cueSec + FIX_MARGIN_SEC)          cue must be DONE before turn-now starts
 *   earliestM= inM  - v*fullSec                            turn-in's own prompt must be done first
 *   fire at    min(max(neededM, latestM), earliestM), and only if latestM &lt;= earliestM
 * </pre>
 * {@code FIX_MARGIN_SEC} is there because fixes arrive about once a second, so the fix that trips
 * the trigger can be up to a fix-interval past it; without the margin the cue can end up with less
 * room than it needs and get skipped. Simulated over 5-140 km/h, four prompt delays, both scripts,
 * 10-140 character utterances and 3 s GPS gaps: 0 violations of "fires strictly outside the turn-now
 * trigger", "finishes before the turn-now trigger", and "starts after the turn-in prompt has ended".
 *
 * <p>The room test is re-checked at the actual fix distance, so a GPS gap that jumps the window
 * makes the cue stay silent rather than speak on top of the turn-now prompt.
 *
 * <p>Turned on by {@link #ENABLED}. This is a plain constant rather than a {@code BuildConfig} field
 * only because {@code cairodrive.gradle} was off limits when this landed; it wants to become
 * {@code BuildConfig.CAIRODRIVE_SPEECH_LEAD}, defaulting true.
 */
public class CairoDriveTurnLead implements OsmAndLocationProvider.OsmAndLocationListener {

	private static final Log log = PlatformUtil.getLog(CairoDriveTurnLead.class);

	/** @see #CairoDriveTurnLead - wants to be {@code BuildConfig.CAIRODRIVE_SPEECH_LEAD}. */
	public static final boolean ENABLED = net.osmand.plus.BuildConfig.CAIRODRIVE_SPEECH_LEAD;

	/**
	 * Slack for the fact that location fixes are discrete. One fix interval (~1 s) plus jitter. Drop
	 * it and roughly a fifth of otherwise valid cues get thrown away by the room re-check because
	 * the fix that trips the trigger landed a second late.
	 */
	private static final double FIX_MARGIN_SEC = 1.5;

	private static final int VERDICT_PENDING = 0;
	private static final int VERDICT_DONE = 1;

	private static volatile CairoDriveTurnLead instance;

	private final OsmandApplication app;

	// Per-turn state. Only ever touched from the location callback, which is the same thread
	// VoiceRouter's own prompts are built on (RoutingHelper.updateLocation runs to completion
	// before location listeners are notified), so there is no lock here on purpose.
	private RouteDirectionInfo currentTurn;
	private int verdict = VERDICT_DONE;
	private long estimatedFullMs;
	private long estimatedCueMs;
	private int estimatedFullChars;
	private String cueTurnParam;
	private boolean cueIsUTurn;
	/** Roundabouts are the worst case for late guidance, not a case to skip - see buildCue. */
	private boolean cueIsRoundabout;

	private CairoDriveTurnLead(@NonNull OsmandApplication app) {
		this.app = app;
	}

	/**
	 * Idempotent. Called from {@code CommandPlayer}'s constructor because there is no point
	 * scheduling speech before something exists that can speak.
	 */
	public static synchronized void attach(@NonNull OsmandApplication app) {
		if (!ENABLED || instance != null) {
			return;
		}
		CairoDriveTurnLead lead = new CairoDriveTurnLead(app);
		OsmAndLocationProvider provider = app.getLocationProvider();
		if (provider == null) {
			return;
		}
		// The provider keeps a strong reference to its listeners, and the singleton keeps a strong
		// reference to this - both intentional, this lives as long as the process.
		provider.addLocationListener(lead);
		instance = lead;
	}

	@Override
	public void updateLocation(@Nullable Location location) {
		if (!ENABLED || location == null) {
			return;
		}
		try {
			tick(location);
		} catch (Exception e) {
			// A miscalculated cue must never take navigation down with it. Anything unexpected
			// here means this turn gets upstream's timing, which is the behaviour without this
			// class at all.
			verdict = VERDICT_DONE;
			log.error("CairoDriveTurnLead failed", e);
		}
	}

	private void tick(@NonNull Location location) {
		RoutingHelper routingHelper = app.getRoutingHelper();
		if (routingHelper == null || !routingHelper.isFollowingMode() || !routingHelper.isRouteCalculated()
				|| routingHelper.isPauseNavigation() || routingHelper.isRouteBeingCalculated()) {
			reset(null);
			return;
		}
		VoiceRouter voiceRouter = routingHelper.getVoiceRouter();
		if (voiceRouter == null || voiceRouter.isMute()) {
			reset(null);
			return;
		}
		OsmandSettings settings = app.getSettings();
		if (!settings.TURN_BY_TURN_DIRECTIONS.get()) {
			reset(null);
			return;
		}
		CommandPlayer player = voiceRouter.getPlayer();
		// Only the TTS player. The recorded-voice player maps the same command list to audio file
		// names, so its "text" is a list of filenames and estimating a duration from it is
		// meaningless - and its prompt length is fixed by the recordings anyway.
		if (player == null || !player.supportsFreeText()) {
			reset(null);
			return;
		}

		NextDirectionInfo next = routingHelper.getNextRouteDirectionInfo(new NextDirectionInfo(), true);
		if (next == null || next.directionInfo == null || next.distanceTo <= 0) {
			reset(null);
			return;
		}
		if (next.directionInfo != currentTurn) {
			reset(next.directionInfo);
		}
		if (verdict == VERDICT_DONE) {
			return;
		}

		AnnounceTimeDistances atd = voiceRouter.getAnnounceTimeDistances();
		if (atd == null) {
			return;
		}
		float speed = atd.getSpeed(location);
		double inM = atd.getTurnInTriggerDistance(speed);
		int dist = next.distanceTo;
		if (dist > inM) {
			// Still outside the turn-in prompt's own trigger. Nothing to decide yet, and building
			// the prompt text runs the voice package's JavaScript - keep that off every fix of a
			// 40 minute drive.
			return;
		}
		double promptDelaySec = atd.getVoicePromptDelayTimeSec();
		if (estimatedFullMs == 0 && !buildEstimates(routingHelper, atd, player, next, settings)) {
			finish("SKIP reason=unsupported_manoeuvre", dist, speed, 0, inM, 0, promptDelaySec);
			return;
		}

		double nowM = atd.getTurnNowTriggerDistance(speed);
		double fullSec = estimatedFullMs / 1000.0;
		double cueSec = estimatedCueMs / 1000.0;

		// Is the existing prompt actually late? Compare against the UNPADDED lead: the padding and
		// the prepended silence are the same quantity and cancel.
		double baseM = nowM - speed * promptDelaySec;
		if (speed * fullSec <= baseM) {
			finish("NOT_NEEDED", dist, speed, nowM, inM, 0, promptDelaySec);
			return;
		}
		double neededM = speed * (fullSec + promptDelaySec);
		double latestM = nowM + speed * (cueSec + FIX_MARGIN_SEC);
		double earliestM = inM - speed * fullSec;
		if (latestM > earliestM) {
			// The sentence is so long that a cue placed late enough to follow the turn-in prompt
			// would already be colliding with the turn-now prompt. Nothing useful fits; say
			// nothing extra rather than talk over an existing announcement.
			finish("SKIP reason=no_room", dist, speed, nowM, inM, 0, promptDelaySec);
			return;
		}
		double targetM = Math.min(Math.max(neededM, latestM), earliestM);
		if (dist > targetM) {
			return;
		}
		// Re-checked at the ACTUAL fix distance, not the planned one: if a GPS gap jumped the whole
		// window, the cue would land on top of the turn-now prompt, so drop it instead.
		if (dist - nowM < speed * cueSec) {
			finish("SKIP reason=missed_window", dist, speed, nowM, inM, targetM, promptDelaySec);
			return;
		}
		speakCue(player);
		finish("FIRED", dist, speed, nowM, inM, targetM, promptDelaySec);
	}

	/**
	 * Estimates the turn-now sentence and the cue by BUILDING them and reading the text back, rather
	 * than guessing at the grammar. {@code JsCommandBuilder.addCommand} evaluates the voice
	 * package's JavaScript as each command is added and {@code execute()} just returns the result,
	 * so this is the literal string the engine would be handed - in the voice package's language,
	 * with that package's phrasing. Nothing is spoken: {@code play()} is what speaks.
	 *
	 * @return false if this manoeuvre has no short cue worth saying.
	 */
	private boolean buildEstimates(@NonNull RoutingHelper routingHelper,
	                               @NonNull AnnounceTimeDistances atd,
	                               @NonNull CommandPlayer player,
	                               @NonNull NextDirectionInfo next,
	                               @NonNull OsmandSettings settings) {
		TurnType turnType = next.directionInfo.getTurnType();
		if (turnType == null) {
			return false;
		}
		cueTurnParam = turnParam(turnType);
		cueIsUTurn = cueTurnParam == null
				&& (turnType.getValue() == TurnType.TU || turnType.getValue() == TurnType.TRU);
		cueIsRoundabout = cueTurnParam == null && !cueIsUTurn && turnType.isRoundAbout();
		if (cueTurnParam == null && !cueIsUTurn && !cueIsRoundabout) {
			// Go-aheads stay excluded: they are not spoken at all, so there is nothing to be late.
			return false;
		}

		// Mirrors VoiceRouter.playMakeTurn: the manoeuvre with the full street name, plus the
		// "then ..." clause when the turn after this one is close enough to be tacked on, plus the
		// arrival clause on the final manoeuvre of a route.
		CommandBuilder full = player.newCommandBuilder();
		StreetName streetName = speakableStreetName(next.directionInfo, settings);
		if (cueTurnParam != null) {
			full.turn(cueTurnParam, streetName);
		} else if (cueIsUTurn) {
			full.makeUT(streetName);
		} else {
			// ROUNDABOUTS, previously excluded on the grounds that "take the second exit" is
			// neither short nor safe to say twice. The first half is true and the second is the
			// part that was wrong: nothing here says it twice. This measures how long the FULL
			// phrase takes and, when that would still be talking at the junction, fires an
			// earlier one. Roundabouts are the WORST case for late guidance, not a case to skip -
			// the exit count is unusable once you are already committed to a lane.
			full.roundAbout(turnType.getTurnAngle(), turnType.getExitOut(), streetName);
		}
		NextDirectionInfo after = routingHelper.getNextRouteDirectionInfoAfter(next, new NextDirectionInfo(), true);
		if (after != null && after.directionInfo != null && after.directionInfo.getTurnType() != null
				&& !atd.isTurnStateNotPassed(0, after.distanceTo, STATE_TURN_IN)) {
			String afterParam = turnParam(after.directionInfo.getTurnType());
			if (afterParam != null) {
				full.then();
				full.turn(afterParam, after.distanceTo, new StreetName());
			}
		} else if (after == null || after.directionInfo == null) {
			// ARRIVAL CLAUSE, previously not modelled.
			//
			// When nothing follows this manoeuvre it IS the last one, and VoiceRouter appends
			// "and arrive at your destination" to it. That clause is long - and in Arabic longer
			// still - so leaving it out under-estimated the final turn of every route by roughly
			// a second of speech.
			//
			// Under-estimating under-fires, which is the safe direction and is why this was
			// acceptable to defer. But the final manoeuvre is the one a driver is least able to
			// recover from: miss it and you are not one block out, you are past the destination
			// looking for somewhere to turn around. Modelling it is worth a line.
			net.osmand.plus.helpers.TargetPoint target =
					routingHelper.getApplication().getTargetPointsHelper().getPointToNavigate();
			// Only the LENGTH of this matters here - the cue is timed, never spoken - so the raw
			// name is enough and no speakable-name transformation is needed.
			String destName = target == null ? null : target.getOnlyName();
			full.arrivedAtDestination(destName == null ? "" : destName);
		}
		String fullText = joinText(full.execute());

		CommandBuilder cue = player.newCommandBuilder();
		if (cueTurnParam != null) {
			cue.turn(cueTurnParam, new StreetName());
		} else {
			cue.makeUT(new StreetName());
		}
		String cueText = joinText(cue.execute());

		CairoDriveSpeechClock clock = CairoDriveSpeechClock.getInstance();
		estimatedFullChars = fullText.length();
		estimatedFullMs = clock.estimateMs(fullText);
		estimatedCueMs = clock.estimateMs(cueText);
		return estimatedFullMs > 0 && estimatedCueMs > 0;
	}

	/**
	 * The extra cue. Built fresh rather than reusing the builder made for the estimate, so it is
	 * always spoken through the player that is current now - the voice can be changed mid-drive.
	 *
	 * <p>Played straight through the player instead of through {@code VoiceRouter.play}, which is
	 * the whole point: that method is where the status ladder lives. The visible cost is that
	 * {@code VoiceMessageListener}s - the on-screen prompt echo - do not see this cue. That is the
	 * right trade: those listeners are cosmetic, and routing every extra prompt through VoiceRouter
	 * is exactly the coupling that turned the last attempt here into 40 s of silence.
	 */
	private void speakCue(@NonNull CommandPlayer player) {
		CommandBuilder p = player.newCommandBuilder();
		if (cueTurnParam != null) {
			p.turn(cueTurnParam, new StreetName());
		} else {
			p.makeUT(new StreetName());
		}
		p.play();
	}

	/**
	 * Same three fields {@code VoiceRouter.getSpeakableStreetName} puts on a turn prompt. The
	 * {@code FROM_*} fields are left out: no shipped voice package uses them in a {@code turn}
	 * command, and including them would inflate the estimate and over-fire.
	 */
	@NonNull
	private StreetName speakableStreetName(@NonNull RouteDirectionInfo info, @NonNull OsmandSettings settings) {
		Map<String, String> result = new HashMap<>();
		if (!settings.SPEAK_STREET_NAMES.get()) {
			return new StreetName(result);
		}
		result.put(VoiceRouter.TO_REF, nonNull(info.getRef()));
		result.put(VoiceRouter.TO_STREET_NAME, nonNull(info.getStreetName()));
		result.put(VoiceRouter.TO_DEST, nonNull(info.getDestinationRefAndName()));
		return new StreetName(result);
	}

	@NonNull
	private static String nonNull(@Nullable String s) {
		return s == null ? "" : s;
	}

	@NonNull
	private static String joinText(@Nullable List<String> parts) {
		if (parts == null || parts.isEmpty()) {
			return "";
		}
		StringBuilder sb = new StringBuilder();
		for (String part : parts) {
			if (part != null) {
				sb.append(part).append(' ');
			}
		}
		return sb.toString();
	}

	@Nullable
	private static String turnParam(@NonNull TurnType t) {
		switch (t.getValue()) {
			case TurnType.TL:
				return CommandPlayer.A_LEFT;
			case TurnType.TSHL:
				return CommandPlayer.A_LEFT_SH;
			case TurnType.TSLL:
				return CommandPlayer.A_LEFT_SL;
			case TurnType.TR:
				return CommandPlayer.A_RIGHT;
			case TurnType.TSHR:
				return CommandPlayer.A_RIGHT_SH;
			case TurnType.TSLR:
				return CommandPlayer.A_RIGHT_SL;
			case TurnType.KL:
				return CommandPlayer.A_LEFT_KEEP;
			case TurnType.KR:
				return CommandPlayer.A_RIGHT_KEEP;
			default:
				return null;
		}
	}

	private void reset(@Nullable RouteDirectionInfo turn) {
		currentTurn = turn;
		verdict = turn == null ? VERDICT_DONE : VERDICT_PENDING;
		estimatedFullMs = 0;
		estimatedCueMs = 0;
		estimatedFullChars = 0;
		cueTurnParam = null;
		cueIsUTurn = false;
	}

	/**
	 * One CD_VOICE line per turn, whatever the outcome. The NOT_NEEDED lines are as valuable as the
	 * FIRED ones: together they say how far the duration estimate is from the 6.7 s the existing
	 * prompt gets, which is the number that decides whether this feature earns its place.
	 */
	private void finish(@NonNull String outcome, int dist, float speed,
	                    double nowM, double inM, double targetM, double promptDelaySec) {
		verdict = VERDICT_DONE;
		try {
			CairoDriveLogger.getInstance().log("CD_VOICE", String.format(Locale.US,
					"turnLead=%s turn=%s dist=%d speed=%.1f estFullMs=%d estCueMs=%d fullChars=%d "
							+ "turnNowM=%.0f turnInM=%.0f targetM=%.0f promptDelayS=%.2f %s",
					outcome, cueTurnParam != null ? cueTurnParam : (cueIsUTurn ? "u_turn" : "-"),
					dist, speed, estimatedFullMs, estimatedCueMs, estimatedFullChars,
					nowM, inM, targetM, promptDelaySec,
					CairoDriveSpeechClock.getInstance().describe()));
		} catch (Exception e) {
			log.error("CD_VOICE turnLead logging failed", e);
		}
	}
}
