package net.osmand.plus.cairodrive.search;

import androidx.annotation.NonNull;

import net.osmand.osm.MapPoiTypes;
import net.osmand.search.core.SearchCoreFactory.SearchAmenityTypesAPI;
import net.osmand.search.core.SearchPhrase;

/**
 * The POI-category provider, gated like the rest of the OSM set.
 * <p>
 * Subclassed rather than wrapped in {@link GatedSearchApi} because this is the one provider
 * the app looks up by type: {@code shallowSearch(SearchAmenityTypesAPI.class, ...)} fills the
 * Categories tab and the Android Auto category screen, and
 * {@code SearchUICore#addCustomSearchPoiFilter} finds it with an {@code instanceof} test. A
 * delegating wrapper would fail both. Extending keeps the type identity intact.
 * <p>
 * Only {@link #getSearchPriority} is gated, and {@code shallowSearch} calls {@code search}
 * directly without consulting it - so the category screens keep working while Google is
 * answering, and only the typed result list is left to Google.
 */
public class GatedAmenityTypesAPI extends SearchAmenityTypesAPI {

	private final SearchProviderGate gate;

	public GatedAmenityTypesAPI(@NonNull MapPoiTypes types, @NonNull SearchProviderGate gate) {
		super(types);
		this.gate = gate;
	}

	@Override
	public int getSearchPriority(SearchPhrase phrase) {
		return gate.isSatisfied(phrase) ? -1 : super.getSearchPriority(phrase);
	}

	@Override
	public boolean isSearchMoreAvailable(SearchPhrase phrase) {
		return !gate.isSatisfied(phrase) && super.isSearchMoreAvailable(phrase);
	}
}
