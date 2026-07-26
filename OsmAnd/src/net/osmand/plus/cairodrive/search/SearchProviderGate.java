package net.osmand.plus.cairodrive.search;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.search.core.SearchPhrase;

import java.util.Locale;

/**
 * Decides, for one search phrase at a time, whether the offline OSM providers are allowed
 * to contribute.
 * <p>
 * CairoDrive shows Google Places results and nothing else while Google is answering. OSM is
 * a fallback, not a supplement: it runs only when Google could not answer - no key, no
 * connection, a failed request, or an empty response.
 * <p>
 * The mechanism relies on {@code SearchUICore} re-evaluating
 * {@link net.osmand.search.core.SearchCoreAPI#getSearchPriority} for each provider at its
 * turn in the run loop, not once up front. {@link GooglePlacesSearchApi} is ordered first
 * and records its outcome here; every OSM provider is wrapped so that it reports "do not
 * run" for exactly the phrase Google already satisfied.
 * <p>
 * State is keyed by phrase so a stale verdict from the previous keystroke can never
 * suppress the fallback for the next one.
 */
public class SearchProviderGate {

	@Nullable
	private volatile String satisfiedPhrase;

	/** Google answered this phrase with at least one result - stand the OSM providers down. */
	public void markSatisfied(@NonNull SearchPhrase phrase) {
		satisfiedPhrase = keyOf(phrase);
	}

	/**
	 * Google could not answer - a transport failure, a non-200 response, or zero results.
	 * Cleared rather than merely left unset, so that a repeat of a phrase Google answered
	 * a moment ago still falls back when the retry fails.
	 */
	public void markUnsatisfied() {
		satisfiedPhrase = null;
	}

	/** True when Google already answered this exact phrase. */
	public boolean isSatisfied(@NonNull SearchPhrase phrase) {
		String satisfied = satisfiedPhrase;
		return satisfied != null && satisfied.equals(keyOf(phrase));
	}

	@NonNull
	private static String keyOf(@NonNull SearchPhrase phrase) {
		String text = phrase.getFullSearchPhrase();
		return text == null ? "" : text.trim().toLowerCase(Locale.US);
	}
}
