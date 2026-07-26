package net.osmand.plus.cairodrive.search;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.data.Amenity;
import net.osmand.data.LatLon;
import net.osmand.osm.MapPoiTypes;
import net.osmand.osm.PoiCategory;
import net.osmand.osm.PoiType;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.search.SearchUICore.SearchResultMatcher;
import net.osmand.search.core.ObjectType;
import net.osmand.search.core.SearchCoreFactory;
import net.osmand.search.core.SearchCoreFactory.SearchBaseAPI;
import net.osmand.search.core.SearchPhrase;
import net.osmand.search.core.SearchResult;
import net.osmand.util.Algorithms;
import net.osmand.util.TransliterationHelper;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Place search backed by the Google Places API (New) Text Search endpoint.
 * <p>
 * CairoDrive keeps OsmAnd's offline map rendering and routing and only replaces where the
 * <em>places</em> come from, because Google's POI coverage is far denser than OSM's in
 * Egypt. When this API is active it is the only provider registered, so the result list is
 * exactly what Google returned - see {@code QuickSearchHelper.initSearchUICore}. With no
 * connection, or with no key compiled in, it stands down and the stock OSM offline search
 * is registered instead.
 * <p>
 * Results are converted to {@link Amenity} objects so that everything downstream - the map
 * pin, the context menu, "directions to" - works exactly as it does for an OSM POI.
 * <p>
 * <b>Billing.</b> Text Search is charged per request and OsmAnd issues a search on every
 * keystroke, so this class debounces ({@link #DEBOUNCE_MS}), ignores queries shorter than
 * {@link #MIN_QUERY_LENGTH}, and caches recent responses. Typing one query costs one or two
 * billed requests rather than one per character.
 */
public class GooglePlacesSearchApi extends SearchBaseAPI {

	private static final Log LOG = PlatformUtil.getLog(GooglePlacesSearchApi.class);

	private static final String TEXT_SEARCH_URL = "https://places.googleapis.com/v1/places:searchText";
	/**
	 * Google bills by the fields requested, so this asks for the cheapest set that still
	 * supports a map pin and a context menu. Adding fields here can change the SKU charged.
	 */
	private static final String FIELD_MASK = "places.id,places.displayName,places.formattedAddress,"
			+ "places.location,places.types,places.primaryType";

	/** Shorter queries match half the city and are not worth a billed request. */
	private static final int MIN_QUERY_LENGTH = 3;
	/** Wait for typing to settle before spending a request. */
	private static final long DEBOUNCE_MS = 400;
	private static final int MAX_RESULTS = 20;
	private static final int CONNECT_TIMEOUT_MS = 10000;
	private static final int READ_TIMEOUT_MS = 15000;

	private static final int CACHE_SIZE = 64;
	private static final long CACHE_TTL_MS = 5 * 60 * 1000;
	/** Cache key granularity for the bias centre - ~1 km, so small map moves still hit. */
	private static final double CACHE_LOCATION_PRECISION = 0.01;

	/**
	 * When this provider runs relative to the others, not a quality score. Deliberately
	 * after the local category providers: this one sleeps for the debounce and then waits
	 * on the network, so running it first would stall the instant category rows behind it.
	 */
	private static final int SEARCH_PRIORITY = 350;
	/**
	 * Where the results land in the finished list. Below SEARCH_AMENITY_TYPE_PRIORITY so
	 * actual places outrank the category row they arrived after.
	 */
	private static final int RESULT_PRIORITY = 50;

	/** Google place types that do not share a name with their OsmAnd equivalent. */
	private static final Map<String, String> POI_TYPE_ALIASES = new HashMap<>();

	static {
		POI_TYPE_ALIASES.put("gas_station", "fuel");
		POI_TYPE_ALIASES.put("drugstore", "pharmacy");
		POI_TYPE_ALIASES.put("doctor", "doctors");
		POI_TYPE_ALIASES.put("lodging", "hotel");
		POI_TYPE_ALIASES.put("meal_takeaway", "fast_food");
		POI_TYPE_ALIASES.put("meal_delivery", "fast_food");
		POI_TYPE_ALIASES.put("shopping_mall", "mall");
		POI_TYPE_ALIASES.put("grocery_store", "supermarket");
		POI_TYPE_ALIASES.put("subway_station", "subway_entrance");
		POI_TYPE_ALIASES.put("bus_station", "bus_station");
		POI_TYPE_ALIASES.put("train_station", "railway_station");
		POI_TYPE_ALIASES.put("light_rail_station", "railway_station");
		POI_TYPE_ALIASES.put("primary_school", "school");
		POI_TYPE_ALIASES.put("secondary_school", "school");
		POI_TYPE_ALIASES.put("place_of_worship", "place_of_worship");
		POI_TYPE_ALIASES.put("mosque", "place_of_worship");
		POI_TYPE_ALIASES.put("church", "place_of_worship");
		POI_TYPE_ALIASES.put("parking", "parking");
		POI_TYPE_ALIASES.put("atm", "atm");
		POI_TYPE_ALIASES.put("hospital", "hospital");
		POI_TYPE_ALIASES.put("cafe", "cafe");
		POI_TYPE_ALIASES.put("restaurant", "restaurant");
		POI_TYPE_ALIASES.put("bank", "bank");
		POI_TYPE_ALIASES.put("pharmacy", "pharmacy");
	}

	private static final Map<String, CachedResponse> CACHE =
			new LinkedHashMap<String, CachedResponse>(CACHE_SIZE, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, CachedResponse> eldest) {
					return size() > CACHE_SIZE;
				}
			};

	private final OsmandApplication app;

	public GooglePlacesSearchApi(@NonNull OsmandApplication app) {
		super(ObjectType.POI);
		this.app = app;
	}

	/** True when a key was compiled in. See {@code OsmAnd/cairodrive.gradle}. */
	public static boolean isConfigured() {
		return !Algorithms.isEmpty(BuildConfig.GOOGLE_PLACES_API_KEY);
	}

	/**
	 * Whether Google should own the search results right now. False with no key or no
	 * connection, which is what hands the search back to the offline OSM providers.
	 */
	public static boolean isActive(@NonNull OsmandApplication app) {
		return isConfigured() && app.getSettings().isInternetConnectionAvailable();
	}

	@Override
	public int getSearchPriority(SearchPhrase phrase) {
		if (!isActive(app) || queryOf(phrase).length() < MIN_QUERY_LENGTH) {
			return -1;
		}
		return SEARCH_PRIORITY;
	}

	@Override
	public boolean isSearchMoreAvailable(SearchPhrase phrase) {
		// One page only. Paging costs another billed request and Text Search already
		// returns the top MAX_RESULTS matches for the query.
		return false;
	}

	@Override
	public boolean search(SearchPhrase phrase, SearchResultMatcher matcher) throws IOException {
		String query = queryOf(phrase);
		if (query.length() < MIN_QUERY_LENGTH || !isActive(app)) {
			return true;
		}
		// Debounce: OsmAnd cancels the running search when the text changes, so sleeping
		// here means an abandoned keystroke never reaches the network.
		try {
			Thread.sleep(DEBOUNCE_MS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return true;
		}
		if (matcher.isCancelled()) {
			return true;
		}

		LatLon location = phrase.getSettings().getOriginalLocation();
		String key = cacheKey(query, location);
		String body = cached(key);
		if (body == null) {
			body = request(query, location);
			if (body == null) {
				return true;
			}
			store(key, body);
		}
		if (matcher.isCancelled()) {
			return true;
		}
		publish(phrase, matcher, body);
		return true;
	}

	@NonNull
	private static String queryOf(@NonNull SearchPhrase phrase) {
		String text = phrase.getFullSearchPhrase();
		return text == null ? "" : text.trim();
	}

	@Nullable
	private String request(@NonNull String query, @Nullable LatLon location) {
		HttpURLConnection connection = null;
		try {
			connection = (HttpURLConnection) new URL(TEXT_SEARCH_URL).openConnection();
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			connection.setRequestProperty("X-Goog-Api-Key", BuildConfig.GOOGLE_PLACES_API_KEY);
			connection.setRequestProperty("X-Goog-FieldMask", FIELD_MASK);

			byte[] payload = requestBody(query, location).getBytes(StandardCharsets.UTF_8);
			try (OutputStream out = connection.getOutputStream()) {
				out.write(payload);
			}

			int code = connection.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) {
				// 403 here almost always means the key's Android app restriction does not
				// match this build's package name and signing certificate.
				LOG.error("Google Places search failed: HTTP " + code + " "
						+ read(connection.getErrorStream()));
				return null;
			}
			return read(connection.getInputStream());
		} catch (IOException | RuntimeException e) {
			LOG.error("Google Places search failed", e);
			return null;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	@NonNull
	private String requestBody(@NonNull String query, @Nullable LatLon location) {
		try {
			JSONObject body = new JSONObject();
			body.put("textQuery", query);
			body.put("maxResultCount", MAX_RESULTS);
			body.put("languageCode", app.getLanguage());
			if (location != null) {
				// A bias, not a restriction: a match outside the circle is still returned,
				// just ranked lower, so searching a landmark in another city still works.
				JSONObject center = new JSONObject();
				center.put("latitude", location.getLatitude());
				center.put("longitude", location.getLongitude());
				JSONObject circle = new JSONObject();
				circle.put("center", center);
				circle.put("radius", 50000.0);
				JSONObject bias = new JSONObject();
				bias.put("circle", circle);
				body.put("locationBias", bias);
			}
			return body.toString();
		} catch (JSONException e) {
			LOG.error("Could not build Google Places request", e);
			return "{\"textQuery\":\"" + query.replace("\"", "") + "\"}";
		}
	}

	private void publish(@NonNull SearchPhrase phrase, @NonNull SearchResultMatcher matcher,
			@NonNull String body) {
		try {
			JSONArray places = new JSONObject(body).optJSONArray("places");
			if (places == null) {
				return;
			}
			MapPoiTypes poiTypes = app.getPoiTypes();
			for (int i = 0; i < places.length() && !matcher.isCancelled(); i++) {
				JSONObject place = places.optJSONObject(i);
				if (place == null) {
					continue;
				}
				SearchResult result = toSearchResult(phrase, place, poiTypes, i);
				if (result != null) {
					matcher.publish(result);
				}
			}
		} catch (JSONException e) {
			LOG.error("Could not parse Google Places response", e);
		}
	}

	@Nullable
	private SearchResult toSearchResult(@NonNull SearchPhrase phrase, @NonNull JSONObject place,
			@NonNull MapPoiTypes poiTypes, int index) {
		JSONObject location = place.optJSONObject("location");
		if (location == null) {
			return null;
		}
		double lat = location.optDouble("latitude", Double.NaN);
		double lon = location.optDouble("longitude", Double.NaN);
		if (Double.isNaN(lat) || Double.isNaN(lon)) {
			return null;
		}
		String placeId = place.optString("id", "");
		String name = place.optJSONObject("displayName") != null
				? place.optJSONObject("displayName").optString("text", "")
				: "";
		String address = place.optString("formattedAddress", "");
		if (Algorithms.isEmpty(name)) {
			name = Algorithms.isEmpty(address) ? placeId : address;
		}

		Amenity amenity = new Amenity();
		amenity.setLocation(lat, lon);
		// Synthetic, derived from the Google place id: these have no OSM identity, and the
		// id only needs to be stable so the same place resolves to the same map object.
		amenity.setId(syntheticId(placeId, lat, lon));
		amenity.setName(name);
		amenity.setEnName(TransliterationHelper.transliterate(name));
		applyPoiType(amenity, place, poiTypes);
		if (!Algorithms.isEmpty(address)) {
			amenity.setAdditionalInfo("description", address);
		}

		SearchResult result = new SearchResult(phrase);
		result.localeName = name;
		result.alternateName = address;
		result.object = amenity;
		result.objectType = ObjectType.POI;
		result.location = new LatLon(lat, lon);
		result.preferredZoom = SearchCoreFactory.PREFERRED_POI_ZOOM;
		// Google already ranked the response; keep that order rather than re-sorting by
		// distance, which is the whole reason for preferring it over the offline index.
		result.priority = RESULT_PRIORITY + index;
		return result;
	}

	private void applyPoiType(@NonNull Amenity amenity, @NonNull JSONObject place,
			@NonNull MapPoiTypes poiTypes) {
		JSONArray types = place.optJSONArray("types");
		String primary = place.optString("primaryType", "");
		if (!Algorithms.isEmpty(primary)) {
			PoiType matched = resolvePoiType(primary, poiTypes);
			if (matched != null) {
				amenity.setSubType(matched.getKeyName());
				amenity.setType(matched.getCategory());
				return;
			}
		}
		if (types != null) {
			for (int i = 0; i < types.length(); i++) {
				PoiType matched = resolvePoiType(types.optString(i, ""), poiTypes);
				if (matched != null) {
					amenity.setSubType(matched.getKeyName());
					amenity.setType(matched.getCategory());
					return;
				}
			}
		}
		// Unmapped Google type: keep it as the subtype so the label still says something
		// useful, and file it under the catch-all category for the icon.
		String fallback = !Algorithms.isEmpty(primary) ? primary
				: types != null && types.length() > 0 ? types.optString(0, "") : "";
		PoiCategory other = poiTypes.getOtherPoiCategory();
		amenity.setSubType(Algorithms.isEmpty(fallback) ? other.getKeyName() : fallback);
		amenity.setType(other);
	}

	@Nullable
	private PoiType resolvePoiType(@Nullable String googleType, @NonNull MapPoiTypes poiTypes) {
		if (Algorithms.isEmpty(googleType)) {
			return null;
		}
		String key = googleType.toLowerCase(Locale.US);
		String alias = POI_TYPE_ALIASES.get(key);
		if (alias != null) {
			PoiType byAlias = poiTypes.getPoiTypeByKey(alias);
			if (byAlias != null) {
				return byAlias;
			}
		}
		return poiTypes.getPoiTypeByKey(key);
	}

	/**
	 * Google place ids are opaque strings; OsmAnd wants a long. The coordinates are folded
	 * in so that two places whose ids happen to collide in 63 bits still differ.
	 */
	private static long syntheticId(@Nullable String placeId, double lat, double lon) {
		long hash = Algorithms.isEmpty(placeId) ? 0 : placeId.hashCode();
		hash = hash * 31 + Double.doubleToLongBits(lat);
		hash = hash * 31 + Double.doubleToLongBits(lon);
		// Masked rather than Math.abs, which returns a negative for Long.MIN_VALUE.
		return hash & Long.MAX_VALUE;
	}

	@NonNull
	private static String read(@Nullable InputStream stream) throws IOException {
		if (stream == null) {
			return "";
		}
		StringBuilder builder = new StringBuilder();
		try (BufferedReader reader = new BufferedReader(
				new InputStreamReader(stream, StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				builder.append(line);
			}
		}
		return builder.toString();
	}

	@NonNull
	private static String cacheKey(@NonNull String query, @Nullable LatLon location) {
		if (location == null) {
			return query.toLowerCase(Locale.US);
		}
		long lat = Math.round(location.getLatitude() / CACHE_LOCATION_PRECISION);
		long lon = Math.round(location.getLongitude() / CACHE_LOCATION_PRECISION);
		return query.toLowerCase(Locale.US) + "@" + lat + "," + lon;
	}

	@Nullable
	private static String cached(@NonNull String key) {
		synchronized (CACHE) {
			CachedResponse cached = CACHE.get(key);
			if (cached == null) {
				return null;
			}
			if (System.currentTimeMillis() - cached.storedAt > CACHE_TTL_MS) {
				CACHE.remove(key);
				return null;
			}
			return cached.body;
		}
	}

	private static void store(@NonNull String key, @NonNull String body) {
		synchronized (CACHE) {
			CACHE.put(key, new CachedResponse(body, System.currentTimeMillis()));
		}
	}

	private static class CachedResponse {
		final String body;
		final long storedAt;

		CachedResponse(String body, long storedAt) {
			this.body = body;
			this.storedAt = storedAt;
		}
	}
}
