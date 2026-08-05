package net.osmand.plus.cairodrive.providers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * What every external API is actually doing, and in plain words WHY when it is not working.
 *
 * <h3>Why this exists</h3>
 *
 * Four separate faults this week had the same shape: a provider read an empty string, decided it
 * had nothing to do, and returned silently. TomTom traffic and the dust warnings shipped with no
 * key because a GitHub secret was named differently; three more keys were read from string
 * resources nobody filled; the Arabic language tag was rejected on every incident request. Not one
 * of them announced itself. The app looked identical whether a feature was working, switched off,
 * out of budget, or broken.
 *
 * <p>So the useful question is not "is the server up" - it usually is - but <b>"why am I not
 * seeing anything"</b>, and the honest answers are mostly not HTTP at all: no key in this build,
 * the toggle is off, the daily budget is spent. Those are reported first-class here, alongside the
 * status codes, because they are the common cases.
 *
 * <h3>Contract</h3>
 *
 * Providers call {@link #recordOk}, {@link #recordFailure} or {@link #recordSkipped} on every
 * attempt. Everything is in-memory and per-process: this describes THIS session, which is what a
 * driver looking at a status screen wants to know. It is deliberately not persisted - a stale
 * "working" from yesterday would be worse than saying nothing.
 *
 * <p>No key material, no URLs and no request bodies are stored. A failure detail is truncated and
 * comes from the response body, which is where these APIs put their reason.
 */
public final class ApiHealth {

	/** Longest failure detail kept. Enough for a provider's error message, short enough to show. */
	private static final int MAX_DETAIL = 160;

	public enum Api {
		GOOGLE_PLACES("Google Places (search)"),
		GOOGLE_ROUTES("Google Routes (live traffic)"),
		TOMTOM_FLOW("TomTom flow (ETA correction)"),
		TOMTOM_INCIDENTS("TomTom incidents (closures)"),
		OPENWEATHER("OpenWeather (dust, air quality)"),
		BESTTIME("BestTime (popular times)"),
		HERE("HERE (traffic overlay)");

		public final String label;

		Api(String label) {
			this.label = label;
		}
	}

	/** Why a call was not even attempted. These are the common cases, not the exotic ones. */
	public enum Skip {
		NO_KEY("No API key in this build"),
		DISABLED("Turned off in settings"),
		BUDGET_SPENT("Daily request budget spent - resets around 3am"),
		NO_INTERNET("No internet connection"),
		NOT_APPLICABLE("Not used in this situation");

		public final String reason;

		Skip(String reason) {
			this.reason = reason;
		}
	}

	public static final class Status {
		public final Api api;
		public volatile long lastAttemptMs;
		public volatile long lastOkMs;
		public volatile int lastCode;
		@Nullable
		public volatile String lastDetail;
		@Nullable
		public volatile Skip lastSkip;
		public volatile int okCount;
		public volatile int failCount;

		Status(Api api) {
			this.api = api;
		}

		public boolean everTried() {
			return lastAttemptMs != 0 || lastSkip != null;
		}

		public boolean healthy() {
			return okCount > 0 && lastOkMs >= lastAttemptMs;
		}
	}

	private static final Map<Api, Status> STATUS = new LinkedHashMap<>();

	static {
		for (Api api : Api.values()) {
			STATUS.put(api, new Status(api));
		}
	}

	private ApiHealth() {
	}

	@NonNull
	public static Status get(@NonNull Api api) {
		Status s = STATUS.get(api);
		return s != null ? s : new Status(api);
	}

	@NonNull
	public static List<Status> all() {
		return new ArrayList<>(STATUS.values());
	}

	public static void recordOk(@NonNull Api api) {
		Status s = get(api);
		long now = System.currentTimeMillis();
		s.lastAttemptMs = now;
		s.lastOkMs = now;
		s.lastCode = 200;
		s.lastDetail = null;
		s.lastSkip = null;
		s.okCount++;
	}

	/**
	 * @param code HTTP status, or 0 when the request never got a response at all
	 * @param body the response body; truncated, and only ever read for its reason text
	 */
	public static void recordFailure(@NonNull Api api, int code, @Nullable String body) {
		Status s = get(api);
		s.lastAttemptMs = System.currentTimeMillis();
		s.lastCode = code;
		s.lastSkip = null;
		s.failCount++;
		if (body != null && !body.isEmpty()) {
			String trimmed = body.trim().replaceAll("\\s+", " ");
			s.lastDetail = trimmed.length() > MAX_DETAIL
					? trimmed.substring(0, MAX_DETAIL) + "..." : trimmed;
		} else {
			s.lastDetail = null;
		}
	}

	/** No call was made, and this is why. The most common real answer on a working install. */
	public static void recordSkipped(@NonNull Api api, @NonNull Skip skip) {
		Status s = get(api);
		s.lastSkip = skip;
	}

	/**
	 * One line a driver can act on.
	 *
	 * <h3>Why the codes are translated rather than shown raw</h3>
	 *
	 * "403" is not an answer, and worse, it means different things per provider: TomTom returns it
	 * for an exhausted FREE ALLOWANCE as well as for a key that lacks the product, so the honest
	 * text has to name both possibilities rather than assert one. Google returns 400 with a reason
	 * in the body, which is why the body is kept.
	 */
	@NonNull
	public static String explain(@NonNull Status s) {
		if (s.lastSkip != null && s.lastAttemptMs == 0) {
			return s.lastSkip.reason;
		}
		if (s.lastAttemptMs == 0) {
			return "Not used yet this session";
		}
		if (s.healthy()) {
			return "Working";
		}
		String detail = s.lastDetail != null ? "  -  " + s.lastDetail : "";
		switch (s.lastCode) {
			case 0:
				return "No response. No internet, DNS blocked, or the request timed out." + detail;
			case 400:
				return "Rejected the request. Something the app sent is not accepted." + detail;
			case 401:
				return "Key rejected. It is wrong, or not activated for this API yet." + detail;
			case 403:
				// Deliberately both, because TomTom uses 403 for over-quota and Google uses it for
				// a restricted key. Asserting one would send you to the wrong console page.
				return "Refused. Either the free allowance is used up, or the key is not allowed "
						+ "for this API / is blocked by its package + SHA-1 restriction." + detail;
			case 404:
				return "Endpoint not found. The app is calling a URL this API no longer serves."
						+ detail;
			case 429:
				return "Too many requests, rate limited. Should recover on its own." + detail;
			default:
				if (s.lastCode >= 500) {
					return "The provider's own servers are failing. Nothing to fix here." + detail;
				}
				return "HTTP " + s.lastCode + detail;
		}
	}

	/** {@code label: Working (12 ok)} or {@code label: Refused ... (0 ok, 3 failed)}. */
	@NonNull
	public static String describe(@NonNull Status s) {
		String counts = s.okCount == 0 && s.failCount == 0 ? ""
				: String.format(Locale.US, "  (%d ok, %d failed)", s.okCount, s.failCount);
		return s.api.label + ": " + explain(s) + counts;
	}

	/**
	 * The whole picture as one block, for the status screen and for the drive log.
	 *
	 * <p>Written to the log at the end of a session as well as shown on screen, because the
	 * question "was this provider working on the drive I just did" is asked afterwards far more
	 * often than during.
	 */
	@NonNull
	public static String summary() {
		StringBuilder sb = new StringBuilder();
		for (Status s : all()) {
			if (sb.length() > 0) {
				sb.append('\n');
			}
			sb.append(describe(s));
		}
		return sb.toString();
	}
}
