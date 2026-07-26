package net.osmand.plus.cairodrive.search;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.search.core.SearchPhrase;

import java.lang.ref.WeakReference;

/**
 * Decides, for one search execution at a time, whether the offline OSM providers are allowed
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
 * run" for exactly the search Google already satisfied.
 * <p>
 * <b>The verdict is keyed on the phrase's identity, not on its text.</b> {@code SearchUICore}
 * builds a fresh {@link SearchPhrase} for every execution through
 * {@code SearchPhrase.generateNewPhrase}, so identity makes a verdict valid for exactly one
 * run and unable to outlive it. Keying on the text looks equivalent and is not: the same text
 * is searched again when the screen resumes, on "search more", and on every scroll to the end
 * of the list, and a verdict left over from the previous run then suppresses OSM for a run
 * Google may not even take part in. That produced two failures, both fully reproducible:
 * <ul>
 *     <li>Search while online, lose connectivity, come back to the app - which re-runs the
 *     unchanged query. Google declines because there is no connection, so it never reaches
 *     the code that would have cleared a text-keyed verdict; the OSM providers saw their own
 *     text still marked satisfied and stood down; the list came back empty on a device with
 *     the whole country downloaded offline.</li>
 *     <li>The same repeat with connectivity, where Google then fails or returns nothing. A
 *     closed gate makes the wrapped providers report "do not run" while priorities are being
 *     sorted, which sorts them <em>ahead</em> of Google rather than behind it, so they were
 *     visited and skipped before Google ever ran and released the gate.</li>
 * </ul>
 * The reference is weak because this object outlives individual searches and must not keep a
 * finished phrase, or the results it refers to, alive.
 */
public class SearchProviderGate {

	@Nullable
	private volatile WeakReference<SearchPhrase> satisfied;

	/** Google answered this search with at least one result - stand the OSM providers down. */
	public void markSatisfied(@NonNull SearchPhrase phrase) {
		satisfied = new WeakReference<>(phrase);
	}

	/**
	 * Google could not answer - a transport failure, a non-200 response, or zero results.
	 * Cleared rather than merely left unset, so a retry that fails still falls back.
	 */
	public void markUnsatisfied() {
		satisfied = null;
	}

	/** True when Google already answered this exact search execution. */
	public boolean isSatisfied(@NonNull SearchPhrase phrase) {
		WeakReference<SearchPhrase> reference = satisfied;
		return reference != null && reference.get() == phrase;
	}
}
