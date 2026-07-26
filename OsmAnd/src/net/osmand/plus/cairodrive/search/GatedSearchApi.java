package net.osmand.plus.cairodrive.search;

import androidx.annotation.NonNull;

import net.osmand.search.SearchUICore.SearchResultMatcher;
import net.osmand.search.core.SearchCoreAPI;
import net.osmand.search.core.SearchPhrase;

import java.io.IOException;

/**
 * Wraps an offline OSM search provider so it only contributes when Google did not answer.
 * <p>
 * Everything is delegated except {@link #getSearchPriority}, which reports "do not run"
 * for a phrase {@link GooglePlacesSearchApi} already satisfied.
 * <p>
 * The added {@link #ORDER_OFFSET} keeps the wrapped providers sorted behind Google in the
 * run loop. That ordering is what makes the gate work at all: the sort happens before any
 * provider runs, so Google has to take its turn - and record its outcome - before these are
 * asked whether they should run.
 */
public class GatedSearchApi implements SearchCoreAPI {

	/**
	 * Added to the delegate's priority so every gated provider sorts after Google. Larger
	 * than any priority in SearchCoreFactory or QuickSearchHelper.
	 */
	private static final int ORDER_OFFSET = 10000;

	private final SearchCoreAPI delegate;
	private final SearchProviderGate gate;

	public GatedSearchApi(@NonNull SearchCoreAPI delegate, @NonNull SearchProviderGate gate) {
		this.delegate = delegate;
		this.gate = gate;
	}

	@Override
	public int getSearchPriority(SearchPhrase phrase) {
		if (gate.isSatisfied(phrase)) {
			return -1;
		}
		int priority = delegate.getSearchPriority(phrase);
		// -1 is the delegate's own "do not run" and must survive the offset unchanged.
		return priority < 0 ? priority : priority + ORDER_OFFSET;
	}

	@Override
	public boolean search(SearchPhrase phrase, SearchResultMatcher matcher) throws IOException {
		return delegate.search(phrase, matcher);
	}

	@Override
	public boolean isSearchMoreAvailable(SearchPhrase phrase) {
		return !gate.isSatisfied(phrase) && delegate.isSearchMoreAvailable(phrase);
	}

	@Override
	public boolean isSearchAvailable(SearchPhrase phrase) {
		return delegate.isSearchAvailable(phrase);
	}

	@Override
	public int getMinimalSearchRadius(SearchPhrase phrase) {
		return delegate.getMinimalSearchRadius(phrase);
	}

	@Override
	public int getNextSearchRadius(SearchPhrase phrase) {
		return delegate.getNextSearchRadius(phrase);
	}
}
