package net.osmand.plus.cairodrive.search;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.osm.AbstractPoiType;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.poi.PoiUIFilter;
import net.osmand.search.core.SearchResult;
import net.osmand.util.Algorithms;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OsmAnd POI category to Google Places {@code includedTypes}.
 *
 * <p>Needed because the two taxonomies do not line up. OsmAnd's categories come from OSM tags and
 * are open-ended - a user filter can be any tag combination at all - while {@code SearchNearby}
 * accepts only a fixed enumeration of Google's own type strings and REJECTS THE WHOLE REQUEST if
 * it is given one it does not recognise. So an unmapped category has to return nothing and skip
 * the call, rather than guess a type and spend a request on a 400.
 *
 * <p>The map below is deliberately short. It covers the categories a driver actually searches for
 * from behind the wheel in Cairo - fuel, food, pharmacy, ATM, parking, hospital - and stops there.
 * A long speculative table would mostly be entries nobody exercises, each of which is a chance to
 * have guessed a type string wrong and to discover it as a failed request in the car.
 *
 * <p>Everything here is matched against the OSM keyword, lower-cased, so it is insensitive to how
 * the category was localised on screen. Matching the DISPLAYED name would break the moment the map
 * locale is Arabic, which is the locale this app is actually driven in.
 */
public final class GooglePlaceTypes {

	/**
	 * OSM keyword -> Google types.
	 *
	 * <p>Several map to more than one type because Google splits what OSM keeps together:
	 * {@code amenity=pharmacy} is both {@code pharmacy} and {@code drugstore}, and a Cairo
	 * pharmacy is frequently tagged as the latter.
	 */
	private static final Map<String, List<String>> BY_KEYWORD = new HashMap<>();

	static {
		put("fuel", "gas_station");
		put("charging_station", "electric_vehicle_charging_station");
		put("parking", "parking");
		put("pharmacy", "pharmacy", "drugstore");
		put("hospital", "hospital");
		put("clinic", "doctor");
		put("doctors", "doctor");
		put("atm", "atm");
		put("bank", "bank");
		put("restaurant", "restaurant");
		put("fast_food", "fast_food_restaurant");
		put("cafe", "cafe");
		put("bakery", "bakery");
		put("supermarket", "supermarket");
		put("convenience", "convenience_store");
		put("hotel", "hotel");
		put("car_repair", "car_repair");
		put("car_wash", "car_wash");
		put("police", "police");
		put("post_office", "post_office");
		put("mosque", "mosque");
		put("church", "church");
		put("toilets", "public_bath");
	}

	private GooglePlaceTypes() {
	}

	private static void put(String keyword, String... types) {
		BY_KEYWORD.put(keyword, Collections.unmodifiableList(Arrays.asList(types)));
	}

	/**
	 * The Google types for a tapped category, or an EMPTY list when there is no honest mapping.
	 *
	 * <p>Empty is the common and correct answer, and the caller is expected to skip the request on
	 * it. A wrong type string does not degrade gracefully - the Places API rejects the entire
	 * request - so returning a plausible guess would turn a category with no mapping into a billed
	 * failure instead of a silent no-op.
	 */
	@NonNull
	public static List<String> forCategory(@Nullable SearchResult category,
	                                       @Nullable OsmandApplication app) {
		if (category == null || app == null) {
			return Collections.emptyList();
		}
		Object object = category.object;
		String keyword = null;
		if (object instanceof AbstractPoiType) {
			keyword = ((AbstractPoiType) object).getKeyName();
		} else if (object instanceof PoiUIFilter) {
			// A filter is a SET of types by construction, so there is no single keyword to read.
			// The standard filters carry theirs as the last segment of the filter id
			// ("std_fuel" / "poi_fuel"), which is the case worth resolving; a genuinely composite
			// user filter yields a keyword that matches nothing and correctly falls through to
			// empty rather than being approximated by whichever of its types happens to be first.
			String id = ((PoiUIFilter) object).getFilterId();
			if (id != null) {
				int cut = Math.max(id.lastIndexOf('.'), id.lastIndexOf('_'));
				keyword = cut >= 0 && cut + 1 < id.length() ? id.substring(cut + 1) : id;
			}
		}
		if (Algorithms.isEmpty(keyword)) {
			return Collections.emptyList();
		}
		List<String> types = BY_KEYWORD.get(keyword.toLowerCase(Locale.US));
		return types != null ? types : Collections.<String>emptyList();
	}
}
