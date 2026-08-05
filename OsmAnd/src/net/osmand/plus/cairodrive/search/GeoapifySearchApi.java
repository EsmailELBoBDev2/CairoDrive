package net.osmand.plus.cairodrive.search;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.data.Amenity;
import net.osmand.data.LatLon;
import net.osmand.osm.AbstractPoiType;
import net.osmand.osm.MapPoiTypes;
import net.osmand.osm.PoiCategory;
import net.osmand.osm.PoiType;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.cairodrive.providers.AddressLookup;
import net.osmand.plus.cairodrive.providers.GeoapifyProvider;
import net.osmand.plus.helpers.CairoDriveLog;
import net.osmand.plus.helpers.TransliterationHelper;
import net.osmand.search.core.ObjectType;
import net.osmand.search.core.SearchCoreFactory;
import net.osmand.search.core.SearchCoreFactory.SearchBaseAPI;
import net.osmand.search.core.SearchPhrase;
import net.osmand.search.core.SearchResult;
import net.osmand.search.core.SearchSettings.SortType;
import net.osmand.search.core.SearchWord;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;

import java.io.IOException;
import java.util.List;

/**
 * Geoapify as the search provider BETWEEN Google and the offline index.
 *
 * <h3>Where this sits, and why exactly here</h3>
 *
 * {@link GooglePlacesSearchApi} runs first and, when it answers, stands every other provider down
 * through {@link SearchProviderGate}. That is right: nothing matches Google's Cairo corpus for
 * informal, unbranded, Arabic-named businesses.
 *
 * <p>The question this class answers is what happens when Google <b>cannot</b> - no key compiled
 * in, no connection, a failed request, a suspended key, or simply zero results. Until now the
 * answer was the offline .obf alone. The provider audit named Geoapify "the documented escape
 * hatch if the Google key is ever suspended", and this is that escape hatch made real: a second
 * online source that costs nothing, needs no card, and is allowed to be cached.
 *
 * <p>It cannot displace Google, because it only ever runs on a phrase Google left unsatisfied. It
 * cannot hide the offline index either, for the same reason in the other direction - if Geoapify
 * finds nothing it leaves the gate open and the .obf answers exactly as in stock OsmAnd.
 *
 * <h3>Two endpoints, chosen by what the user actually did</h3>
 *
 * <ul>
 *   <li><b>A tapped CATEGORY</b> - "Fuel", "Pharmacy", i.e. a phrase with a selected word - goes
 *       to Geoapify <b>Places</b>, which is a structured category query over 800+ OSM categories
 *       and is billed per 20 places rather than per request. Google's own Nearby Search sits at a
 *       deliberate console quota of ZERO, so before this there was no online answer to "petrol
 *       near me" at all.</li>
 *   <li><b>Typed text</b> goes to Geoapify <b>Autocomplete</b>.</li>
 * </ul>
 *
 * Note that this is the opposite of Google's rule, and deliberately so. {@code
 * GooglePlacesSearchApi} returns -1 for a selected word because Text Search would bill a Pro
 * request to answer a category worse than the offline index does. Geoapify has a real category
 * endpoint, so the same situation is the one case where it has something to add.
 *
 * <h3>The typing path is the risk, and it is flagged</h3>
 *
 * Per-keystroke work is what made a previous batch of search features unusable, and the standing
 * rule is that it must be judged while TYPING rather than after. Two things follow from that and
 * both are deliberate: the debounce lives inside {@link GeoapifyProvider} so every caller shares
 * one, and every request here writes a CD_SEARCH-style line so a drive log can show the request
 * count and latency under real typing instead of a guess.
 */
public class GeoapifySearchApi extends SearchBaseAPI {

	private static final Log LOG = PlatformUtil.getLog(GeoapifySearchApi.class);
	private static final String TRACE_TAG = "CD_SEARCH2";

	/**
	 * Runs after Google (5) and before every offline provider (500 and below).
	 *
	 * <p>LOWER RUNS FIRST here - {@code SearchUICore} sorts providers by ascending
	 * {@code getSearchPriority}. Sizing this relative to
	 * {@code SEARCH_AMENITY_BY_NAME_PRIORITY} reads like the careful choice and is the exact
	 * opposite of one: 499 would place this AFTER the offline providers, so the .obf would answer
	 * first, satisfy the phrase, and the fallback would never be reached at all.
	 */
	private static final int SEARCH_PRIORITY = 6;

	/**
	 * One below Google's result priority. They can never appear together - the gate sees to that -
	 * so this only matters if that ever changes, and then Google should still win.
	 */
	private static final int RESULT_PRIORITY = 51;

	/** Same floor as the Google provider: shorter than this is a prefix nobody can resolve. */
	private static final int MIN_QUERY_LENGTH = 3;

	/** Category search radius. Wide enough for "the next petrol station", not for a city sweep. */
	private static final int NEARBY_RADIUS_M = 8000;

	private final OsmandApplication app;
	private final SearchProviderGate gate;

	public GeoapifySearchApi(@NonNull OsmandApplication app, @NonNull SearchProviderGate gate) {
		super(ObjectType.POI);
		this.app = app;
		this.gate = gate;
	}

	public static boolean isConfigured() {
		return GeoapifyProvider.hasKey();
	}

	private boolean isActive() {
		return isConfigured() && app.getSettings().isInternetConnectionAvailable();
	}

	@Override
	public int getSearchPriority(SearchPhrase phrase) {
		if (!isActive()) {
			return -1;
		}
		// Already answered by Google. Reported as "do not run" rather than checked inside search(),
		// because SearchUICore re-evaluates priority per provider at its turn in the run loop -
		// the same mechanism SearchProviderGate documents at length.
		if (gate.isSatisfied(phrase)) {
			return -1;
		}
		if (phrase.getLastSelectedWord() == null
				&& queryOf(phrase).length() < MIN_QUERY_LENGTH) {
			return -1;
		}
		return SEARCH_PRIORITY;
	}

	@Override
	public boolean isSearchMoreAvailable(SearchPhrase phrase) {
		// One page. A second page is another credit for results nobody scrolls to from a car.
		return false;
	}

	@Override
	public boolean search(SearchPhrase phrase, SearchResultMatcher matcher) throws IOException {
		if (!isActive() || gate.isSatisfied(phrase)) {
			return true;
		}
		LatLon origin = phrase.getSettings().getOriginalLocation();
		SearchWord selected = phrase.getLastSelectedWord();

		long started = System.currentTimeMillis();
		List<GeoapifyProvider.Suggestion> found;
		String mode;
		if (selected != null && origin != null) {
			mode = "places";
			String categories = categoriesFor(selected);
			if (categories == null) {
				// An unmapped category is not an error and must not become a text search for the
				// category's NAME - that would answer "Fuel" with places called Fuel.
				return true;
			}
			found = GeoapifyProvider.nearby(app, origin, categories, NEARBY_RADIUS_M);
		} else {
			mode = "autocomplete";
			// The CHAIN, not Geoapify directly: this is the one place a suggestion can come from
			// LocationIQ instead, and going straight to the provider would have made the second
			// geocoder unreachable for the only feature that can use it.
			found = AddressLookup.suggest(app, queryOf(phrase));
		}
		long ms = System.currentTimeMillis() - started;

		int published = 0;
		MapPoiTypes poiTypes = app.getPoiTypes();
		for (int i = 0; i < found.size() && !matcher.isCancelled(); i++) {
			SearchResult result = toSearchResult(phrase, found.get(i), poiTypes, i);
			if (result != null && matcher.publish(result)) {
				published++;
			}
		}
		if (published > 0) {
			// Stand the offline providers down for THIS phrase only, exactly as Google does.
			// Leaving the gate open on a hit would show the same place twice, once from each
			// source, which is what the gate exists to prevent.
			gate.markSatisfied(phrase);
		}
		CairoDriveLog.log(TRACE_TAG, "geoapify " + mode + " ms=" + ms
				+ " published=" + published + " osmSuppressed=" + (published > 0));
		return true;
	}

	/**
	 * OsmAnd POI type -> Geoapify category, for the handful worth driving to.
	 *
	 * <p>Deliberately a short explicit list rather than a generated mapping over all 800+
	 * categories. An automatic mapping would be wrong in ways nobody would notice until a search
	 * quietly returned the wrong kind of place, and the categories a driver actually taps mid-drive
	 * are few. Anything unmapped returns null and this provider simply stands aside.
	 */
	@Nullable
	private static String categoriesFor(@NonNull SearchWord selected) {
		Object object = selected.getResult() != null ? selected.getResult().object : null;
		String key = null;
		if (object instanceof PoiType) {
			key = ((PoiType) object).getKeyName();
		} else if (object instanceof AbstractPoiType) {
			key = ((AbstractPoiType) object).getKeyName();
		}
		if (Algorithms.isEmpty(key)) {
			return null;
		}
		switch (key) {
			case "fuel":
				return "service.vehicle.fuel";
			case "charging_station":
				return "service.vehicle.charging_station";
			case "pharmacy":
				return "healthcare.pharmacy";
			case "hospital":
				return "healthcare.hospital";
			case "atm":
				return "service.financial.atm";
			case "bank":
				return "service.financial.bank";
			case "parking":
				return "parking";
			case "restaurant":
				return "catering.restaurant";
			case "cafe":
				return "catering.cafe";
			case "supermarket":
				return "commercial.supermarket";
			case "car_repair":
				return "service.vehicle.repair";
			default:
				return null;
		}
	}

	@Nullable
	private SearchResult toSearchResult(@NonNull SearchPhrase phrase,
	                                    @NonNull GeoapifyProvider.Suggestion suggestion,
	                                    @NonNull MapPoiTypes poiTypes, int index) {
		if (suggestion.location == null || Algorithms.isEmpty(suggestion.label)) {
			return null;
		}
		double lat = suggestion.location.getLatitude();
		double lon = suggestion.location.getLongitude();

		Amenity amenity = new Amenity();
		amenity.setLocation(lat, lon);
		// Synthetic and derived from the coordinates, for the same reason as the Google provider's:
		// these have no OSM identity and the id only has to be stable enough that the same place
		// resolves to the same map object twice.
		amenity.setId(syntheticId(suggestion.label, lat, lon));
		amenity.setName(suggestion.label);
		amenity.setEnName(TransliterationHelper.transliterate(suggestion.label));
		// Catch-all category, same as the Google provider's unmapped path: it gives the row an
		// icon without claiming a POI type Geoapify did not actually state.
		PoiCategory other = poiTypes.getOtherPoiCategory();
		amenity.setType(other);
		amenity.setSubType(other.getKeyName());

		SearchResult result = new SearchResult(phrase);
		result.localeName = suggestion.label;
		result.object = amenity;
		result.objectType = ObjectType.POI;
		result.location = new LatLon(lat, lon);
		result.preferredZoom = SearchCoreFactory.PREFERRED_POI_ZOOM;
		// Geoapify already ranked these against the bias point, so the index stagger preserves its
		// order - and, as in the Google provider, it is dropped when the user asked for
		// nearest-first, where priority would otherwise drown the distance term entirely.
		boolean sortByDistance = phrase.getSettings().getSortType() == SortType.ONLY_BY_DISTANCE;
		result.priority = RESULT_PRIORITY + (sortByDistance ? 0 : index);
		return result;
	}

	@NonNull
	private static String queryOf(@NonNull SearchPhrase phrase) {
		// getFullSearchPhrase, matching GooglePlacesSearchApi exactly: the two providers must
		// send the SAME text, or a fallback answers a different question from the primary.
		String text = phrase.getFullSearchPhrase();
		return text == null ? "" : text.trim();
	}

	private static long syntheticId(@NonNull String label, double lat, double lon) {
		long hash = label.hashCode();
		hash = hash * 31 + Double.doubleToLongBits(lat);
		hash = hash * 31 + Double.doubleToLongBits(lon);
		return hash & Long.MAX_VALUE;
	}
}
