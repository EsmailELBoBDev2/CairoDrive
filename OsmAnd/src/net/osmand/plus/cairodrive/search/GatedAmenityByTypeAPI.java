package net.osmand.plus.cairodrive.search;

import androidx.annotation.NonNull;

import net.osmand.osm.MapPoiTypes;
import net.osmand.search.core.SearchCoreFactory.SearchAmenityByTypeAPI;
import net.osmand.search.core.SearchCoreFactory.SearchAmenityTypesAPI;
import net.osmand.search.core.SearchPhrase;

/**
 * The POI-by-type provider, gated like the rest of the OSM set.
 * <p>
 * Subclassed for the same reason as {@link GatedAmenityTypesAPI}: {@code SearchUICore} looks
 * this one up by type too. {@code getUnselectedPoiType()} and {@code getCustomNameFilter()}
 * both scan the registered providers with {@code instanceof SearchAmenityByTypeAPI}, and a
 * delegating wrapper is not an instance of it, so both silently returned null.
 * <p>
 * What that broke: searching a category such as "pharmacy" and tapping <i>Show on map</i>.
 * {@code PoiFiltersHelper#getShowOnMapFilter} reads the unselected POI type to build the
 * filter; with null it fell through to a name-substring filter, so the map showed everything
 * whose name happens to contain the word rather than the actual pharmacies. Any custom name
 * filter attached to a category search was dropped the same way.
 * <p>
 * Only the two gating methods are overridden - the search behaviour is entirely upstream's.
 */
public class GatedAmenityByTypeAPI extends SearchAmenityByTypeAPI {

	private final SearchProviderGate gate;

	public GatedAmenityByTypeAPI(@NonNull MapPoiTypes types, @NonNull SearchAmenityTypesAPI typesAPI,
			@NonNull SearchProviderGate gate) {
		super(types, typesAPI);
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
