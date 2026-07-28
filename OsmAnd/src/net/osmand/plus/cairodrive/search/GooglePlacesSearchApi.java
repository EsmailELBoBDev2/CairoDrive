package net.osmand.plus.cairodrive.search;

import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.data.Amenity;
import net.osmand.data.LatLon;
import net.osmand.osm.MapPoiTypes;
import net.osmand.osm.PoiCategory;
import net.osmand.osm.PoiType;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.plus.OsmandApplication;
import net.osmand.search.SearchUICore.SearchResultMatcher;
import net.osmand.search.core.ObjectType;
import net.osmand.search.core.SearchCoreFactory;
import net.osmand.search.core.SearchCoreFactory.SearchBaseAPI;
import net.osmand.search.core.SearchPhrase;
import net.osmand.search.core.SearchResult;
import net.osmand.search.core.SearchSettings.SortType;
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
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

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
 * {@link #MIN_QUERY_LENGTH}, and caches recent responses.
 * <p>
 * <b>The prefix rule.</b> An exact-match cache is nearly useless while someone is typing,
 * because "cair", "cairo", "cairo t" are three different keys and therefore three bills. So a
 * cached response is also reused for a <em>longer</em> query, under conditions chosen to make
 * a wrong answer unlikely rather than to maximise the hit rate:
 * <ol>
 * <li>The cached query must be a strict prefix of the new one, after both are normalised
 * (lower-cased, runs of whitespace collapsed).</li>
 * <li>Language and location bucket must match, because both are sent in the request and both
 * change what Google returns.</li>
 * <li>The cached response must have come back with <em>fewer</em> than {@link #MAX_RESULTS}
 * places. At the cap the response was truncated, so a place matching the longer query may well
 * have existed just past the cutoff and there is no way to tell from here. This is the
 * condition that does the real work: it means broad prefixes, which are exactly the ones whose
 * answers cannot be trusted, never serve a longer query.</li>
 * <li>Of the entries that qualify, the longest prefix wins - it is the one that saw the most
 * of what the user has typed.</li>
 * <li>Every whitespace-separated token of the new query must appear somewhere in a place's
 * name or formatted address. A token is matched as a substring so that a half-typed last word
 * ("cairo tow") still matches "Cairo Tower".</li>
 * <li>If that filter leaves nothing, the request is issued. An empty filter result is not
 * evidence that Google knows nothing: the extra words may name a place that was ranked below
 * the prefix query's cutoff, or may not be a name at all.</li>
 * </ol>
 * The residual risk is a longer query for which Google would rank a <em>different</em> place
 * first while the cached response happens to contain a weaker match - "nile" then "nile city"
 * serving one hit when Google had several. That is a slightly shorter list, not a wrong one,
 * and it is the price of not billing for every keystroke. Filtered results are never written
 * back to the cache, so the next keystroke still filters the original full response rather
 * than compounding an already-narrowed one.
 * <p>
 * Be honest about what the rest of it buys. The debounce here sits on top of upstream's own
 * 700 ms {@code TIMEOUT_BETWEEN_CHARS}, so a prefix still escapes to a billed request whenever
 * typing pauses for roughly a second. Typing one name realistically costs a few requests, not
 * one: budget for it, and cap the key's daily quota in the Google console.
 * <p>
 * The honest fix is to debounce at the text field instead of inside a search provider, and to
 * use Autocomplete - which is billed per session rather than per request - while the user is
 * still typing, keeping Text Search for the submitted query. Neither is done here yet.
 */
public class GooglePlacesSearchApi extends SearchBaseAPI {

	private static final Log LOG = PlatformUtil.getLog(GooglePlacesSearchApi.class);

	/**
	 * Grep handle for the per-query trace. Every message this class writes carries it, so
	 * {@code adb logcat | grep CD_SEARCH} shows the whole search story - successes included -
	 * without having to know which class logged what.
	 */
	private static final String TRACE_TAG = "CD_SEARCH";

	private static final String TEXT_SEARCH_URL = "https://places.googleapis.com/v1/places:searchText";
	/**
	 * Google bills by the fields requested, so this asks for the cheapest set that still
	 * supports a map pin and a context menu. Adding fields here can change the SKU charged.
	 */
	private static final String FIELD_MASK = "places.id,places.displayName,places.formattedAddress,"
			+ "places.location,places.types,places.primaryType";

	/**
	 * Shorter queries match half the city and are not worth a billed request. Four rather
	 * than three: three-letter prefixes are common as intermediate states while typing and
	 * almost never the query the user meant to run.
	 */
	private static final int MIN_QUERY_LENGTH = 4;
	/**
	 * Wait for typing to settle before spending a request. 800 rather than 400: upstream
	 * already waits 700 ms, and at 400 on top of that a normal pause mid-name still escaped
	 * to a billed request, so a place name cost several. Long enough to halve that, short
	 * enough that a finished query still feels immediate.
	 */
	private static final long DEBOUNCE_MS = 800;
	private static final int MAX_RESULTS = 20;
	private static final int CONNECT_TIMEOUT_MS = 10000;
	private static final int READ_TIMEOUT_MS = 15000;

	private static final int CACHE_SIZE = 64;
	private static final long CACHE_TTL_MS = 5 * 60 * 1000;
	/** Cache key granularity for the bias centre - ~1 km, so small map moves still hit. */
	private static final double CACHE_LOCATION_PRECISION = 0.01;
	private static final Pattern WHITESPACE = Pattern.compile("\\s+");

	/**
	 * Runs before every other provider. The OSM set is gated on this one's outcome, so it
	 * has to take its turn - and record whether it answered - first.
	 */
	private static final int SEARCH_PRIORITY = 5;
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
	private final SearchProviderGate gate;
	/** Empty once computed and unavailable, null while still unknown. */
	@Nullable
	private volatile String signingCertificate;

	public GooglePlacesSearchApi(@NonNull OsmandApplication app, @NonNull SearchProviderGate gate) {
		super(ObjectType.POI);
		this.app = app;
		this.gate = gate;
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
		// A selected word means the user tapped a category rather than typing a place name -
		// "Fuel", "Restaurants". The offline POI-by-type search answers that correctly and
		// for free, whereas Text Search bills a Pro request for it and, because the gate
		// then stands the offline providers down, replaces a structured category result with
		// a text match. Worse answer, and it costs money.
		if (phrase.getLastSelectedWord() != null) {
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

	/**
	 * Every early return leaves the gate cleared, which is what lets the offline OSM
	 * providers take over: they are suppressed only for a phrase this method answered with
	 * at least one result.
	 */
	@Override
	public boolean search(SearchPhrase phrase, SearchResultMatcher matcher) throws IOException {
		gate.markUnsatisfied();

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
		String normalised = normalise(query);
		String scope = cacheScope(location, app.getLanguage());
		String source = "cache";
		String body = cached(cacheKey(normalised, scope));
		if (body == null) {
			body = cachedByPrefix(normalised, scope);
			source = "prefix";
		}
		if (body == null) {
			source = "network";
			body = request(query, location);
			if (body == null) {
				// Transport failure or a non-200 response - fall through to OSM. The
				// request() call above has already logged why.
				trace("query='" + query + "' source=network result=failed"
						+ " (falling back to the offline index)");
				return true;
			}
			// Only a response Google actually sent is stored. A prefix-filtered body is a
			// subset, and caching it would let the next keystroke narrow an already-narrowed
			// list until nothing is left of the answer.
			store(cacheKey(normalised, scope), scope, normalised, body);
		}
		if (matcher.isCancelled()) {
			return true;
		}
		int published = publish(phrase, matcher, body);
		if (published > 0) {
			gate.markSatisfied(phrase);
		}
		// One line per query that got as far as an answer, so "is Google search actually
		// working" is a grep rather than an inference from the absence of an error. source
		// says whether this cost a billed request: network did, cache and prefix did not.
		// published=0 means Google answered and knew nothing, which is not a failure - the
		// offline index gets its turn rather than the user being shown an empty list.
		trace("query='" + query + "' source=" + source
				+ " published=" + published + " osmSuppressed=" + (published > 0));
		return true;
	}

	/**
	 * SHA-1 of the certificate this build is signed with, uppercase hex without separators -
	 * the form the Google API console stores and the X-Android-Cert header expects.
	 * <p>
	 * Read from the installed package rather than configured, because the answer differs
	 * between the debug keystore, the upload key and the key Play App Signing re-signs with,
	 * and only the running app knows which one it ended up with. All of them have to be
	 * registered against the key for every distribution channel to work.
	 *
	 * @return the fingerprint, or null if it could not be determined - in which case the
	 * headers are omitted and an unrestricted key still works.
	 */
	@Nullable
	private String signingCertificateSha1() {
		String cached = signingCertificate;
		if (cached != null) {
			return cached.isEmpty() ? null : cached;
		}
		String computed = "";
		try {
			PackageManager manager = app.getPackageManager();
			Signature[] signatures;
			if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
				SigningInfo info = manager.getPackageInfo(app.getPackageName(),
						PackageManager.GET_SIGNING_CERTIFICATES).signingInfo;
				signatures = info.hasMultipleSigners()
						? info.getApkContentsSigners() : info.getSigningCertificateHistory();
			} else {
				signatures = manager.getPackageInfo(app.getPackageName(),
						PackageManager.GET_SIGNATURES).signatures;
			}
			if (signatures != null && signatures.length > 0) {
				byte[] digest = MessageDigest.getInstance("SHA1").digest(signatures[0].toByteArray());
				StringBuilder hex = new StringBuilder(digest.length * 2);
				for (byte b : digest) {
					hex.append(String.format("%02X", b));
				}
				computed = hex.toString();
			}
		} catch (Exception e) {
			LOG.warn(TRACE_TAG + " could not read the signing certificate, "
					+ "an Android-restricted Places key will be rejected", e);
		}
		signingCertificate = computed;
		return computed.isEmpty() ? null : computed;
	}

	@NonNull
	private static String queryOf(@NonNull SearchPhrase phrase) {
		String text = phrase.getFullSearchPhrase();
		return text == null ? "" : text.trim();
	}

	@Nullable
	private String request(@NonNull String query, @Nullable LatLon location) {
		HttpURLConnection connection;
		try {
			connection = (HttpURLConnection) new URL(TEXT_SEARCH_URL).openConnection();
			connection.setRequestMethod("POST");
			connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(READ_TIMEOUT_MS);
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			connection.setRequestProperty("X-Goog-Api-Key", BuildConfig.GOOGLE_PLACES_API_KEY);
			connection.setRequestProperty("X-Goog-FieldMask", FIELD_MASK);

			// A key restricted to Android apps is checked against these two headers, which
			// Google's own client libraries attach and a plain HTTPS call does not. Without
			// them the request arrives with an empty package name and is rejected with
			// API_KEY_ANDROID_APP_BLOCKED, no matter how the key is configured.
			String androidCert = signingCertificateSha1();
			if (androidCert != null) {
				connection.setRequestProperty("X-Android-Package", app.getPackageName());
				connection.setRequestProperty("X-Android-Cert", androidCert);
			}

			byte[] payload = requestBody(query, location).getBytes(StandardCharsets.UTF_8);
			try (OutputStream out = connection.getOutputStream()) {
				out.write(payload);
			}

			int code = connection.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) {
				// 403 here almost always means the key's Android app restriction does not
				// match this build's package name and signing certificate.
				LOG.error(TRACE_TAG + " request failed: HTTP " + code + " "
						+ read(connection.getErrorStream()));
				return null;
			}
			return read(connection.getInputStream());
		} catch (IOException | RuntimeException e) {
			LOG.error(TRACE_TAG + " request failed", e);
			return null;
		}
		// Deliberately no disconnect(). It tears the socket out of the keep-alive pool, so
		// every search paid a fresh TCP and TLS handshake - 200-600 ms on mobile, before
		// Google even sees the query. The streams are closed with try-with-resources, which
		// is what actually returns the connection to the pool.
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
			LOG.error(TRACE_TAG + " could not build the request", e);
			return "{\"textQuery\":" + JSONObject.quote(query) + "}";
		}
	}

	/** @return how many results reached the matcher; 0 hands the search to OSM. */
	private int publish(@NonNull SearchPhrase phrase, @NonNull SearchResultMatcher matcher,
			@NonNull String body) {
		int published = 0;
		try {
			JSONArray places = new JSONObject(body).optJSONArray("places");
			if (places == null) {
				// A 200 with no "places" is Google's empty result, not a malformed body.
				return 0;
			}
			MapPoiTypes poiTypes = app.getPoiTypes();
			for (int i = 0; i < places.length() && !matcher.isCancelled(); i++) {
				JSONObject place = places.optJSONObject(i);
				if (place == null) {
					continue;
				}
				SearchResult result = toSearchResult(phrase, place, poiTypes, i);
				if (result != null && matcher.publish(result)) {
					published++;
				}
			}
		} catch (JSONException e) {
			LOG.error(TRACE_TAG + " could not parse the response", e);
		}
		return published;
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
		// Carries Google's own ranking into OsmAnd's sort - but only when the user has not
		// asked for something else.
		//
		// getSearchDistance is `priority - 1 / (1 + priorityDistance * metres)`, and that
		// second term can never span more than 1.0. Staggering priority by a whole unit per
		// result therefore drowns it completely: with ONLY_BY_DISTANCE selected the comparator
		// calls getSearchDistance(loc, 1) and nothing but priority decides, so picking
		// "Nearest" left the list in Google's order and looked broken. It was.
		//
		// So the stagger is applied only in the relevance sort. Under ONLY_BY_DISTANCE every
		// result carries the same priority, the distance term is the only thing left to
		// separate them, and nearest-first works as the user asked. priorityDistance stays 0
		// either way, so the default sort is Google's ranking and not a re-sort by proximity -
		// Google already weighed proximity against relevance, and redoing that badly is what
		// this provider exists to avoid.
		//
		// SearchResultComparator only reaches priority at its fourth step; found-word-count and
		// phrase-match weight come first, so a place whose name matches more of what was typed
		// still lifts above Google's order in the relevance sort. Left alone deliberately: half
		// way through typing a name that is usually the better answer.
		boolean sortByDistance = phrase.getSettings().getSortType() == SortType.ONLY_BY_DISTANCE;
		result.priority = RESULT_PRIORITY + (sortByDistance ? 0 : index);
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

	/** Lower case, with runs of whitespace collapsed, so that prefix tests are meaningful. */
	@NonNull
	private static String normalise(@NonNull String query) {
		return WHITESPACE.matcher(query.trim()).replaceAll(" ").toLowerCase(Locale.US);
	}

	/**
	 * The part of a cache entry that has to match exactly before a prefix may be reused.
	 * Language and the bias centre are both sent in the request body, so a response fetched
	 * under different ones is a different answer, not a stale one.
	 */
	@NonNull
	private static String cacheScope(@Nullable LatLon location, @NonNull String language) {
		if (location == null) {
			return language;
		}
		long lat = Math.round(location.getLatitude() / CACHE_LOCATION_PRECISION);
		long lon = Math.round(location.getLongitude() / CACHE_LOCATION_PRECISION);
		return language + "@" + lat + "," + lon;
	}

	@NonNull
	private static String cacheKey(@NonNull String normalisedQuery, @NonNull String scope) {
		return normalisedQuery + "|" + scope;
	}

	@Nullable
	private static String cached(@NonNull String key) {
		synchronized (CACHE) {
			CachedResponse cached = CACHE.get(key);
			if (cached == null) {
				return null;
			}
			if (cached.isExpired()) {
				CACHE.remove(key);
				return null;
			}
			return cached.body;
		}
	}

	/**
	 * A response to an earlier, shorter query, narrowed to the places that still match what
	 * has been typed since. See the class documentation for why each condition is here.
	 *
	 * @return a synthetic response body, or null when no cached prefix can be trusted to
	 * answer this query - in which case the caller must issue the request.
	 */
	@Nullable
	private static String cachedByPrefix(@NonNull String normalisedQuery, @NonNull String scope) {
		CachedResponse best = null;
		synchronized (CACHE) {
			Iterator<Map.Entry<String, CachedResponse>> it = CACHE.entrySet().iterator();
			while (it.hasNext()) {
				CachedResponse candidate = it.next().getValue();
				if (candidate.isExpired()) {
					it.remove();
					continue;
				}
				if (!candidate.scope.equals(scope)
						|| candidate.placeCount >= MAX_RESULTS
						|| candidate.query.length() >= normalisedQuery.length()
						|| !normalisedQuery.startsWith(candidate.query)) {
					continue;
				}
				if (best == null || candidate.query.length() > best.query.length()) {
					best = candidate;
				}
			}
		}
		return best == null ? null : filter(best.body, normalisedQuery);
	}

	/**
	 * @return a response body holding only the places matching every token of the query, or
	 * null if none do - which means the cached prefix cannot answer and a request is owed.
	 */
	@Nullable
	private static String filter(@NonNull String body, @NonNull String normalisedQuery) {
		String[] tokens = normalisedQuery.split(" ");
		try {
			JSONArray places = new JSONObject(body).optJSONArray("places");
			if (places == null) {
				return null;
			}
			JSONArray kept = new JSONArray();
			for (int i = 0; i < places.length(); i++) {
				JSONObject place = places.optJSONObject(i);
				if (place != null && matchesAllTokens(place, tokens)) {
					kept.put(place);
				}
			}
			if (kept.length() == 0) {
				return null;
			}
			return new JSONObject().put("places", kept).toString();
		} catch (JSONException e) {
			LOG.error(TRACE_TAG + " could not filter a cached response", e);
			return null;
		}
	}

	private static boolean matchesAllTokens(@NonNull JSONObject place, @NonNull String[] tokens) {
		JSONObject displayName = place.optJSONObject("displayName");
		String haystack = ((displayName != null ? displayName.optString("text", "") : "")
				+ " " + place.optString("formattedAddress", "")).toLowerCase(Locale.US);
		for (String token : tokens) {
			// Substring rather than whole-word: the last token is usually half typed.
			if (!token.isEmpty() && !haystack.contains(token)) {
				return false;
			}
		}
		return true;
	}

	private static void store(@NonNull String key, @NonNull String scope,
			@NonNull String normalisedQuery, @NonNull String body) {
		synchronized (CACHE) {
			CACHE.put(key, new CachedResponse(scope, normalisedQuery, body,
					placeCount(body), System.currentTimeMillis()));
		}
	}

	/**
	 * @return how many places the response carried, or {@link #MAX_RESULTS} if that cannot be
	 * determined - which reads as "truncated" and so keeps the entry out of the prefix path.
	 */
	private static int placeCount(@NonNull String body) {
		try {
			JSONArray places = new JSONObject(body).optJSONArray("places");
			return places == null ? 0 : places.length();
		} catch (JSONException e) {
			return MAX_RESULTS;
		}
	}

	/**
	 * Writes one diagnostic line to logcat <em>and</em>, when the on-device file logger is
	 * running, straight into its rotating files.
	 * <p>
	 * The second half is not redundancy for its own sake. Some vendor ROMs - MIUI among them -
	 * drop third-party tags out of the logcat buffer entirely, so on those devices
	 * {@code adb logcat} shows only the framework classes injected into the app's own process
	 * and nothing the app itself wrote. Writing through {@link CairoDriveLogger} bypasses
	 * logcat and lands in a file that can be pulled off the device, which is the only way this
	 * trace is worth anything on a phone that filters it.
	 */
	private static void trace(@NonNull String message) {
		LOG.info(TRACE_TAG + " " + message);
		CairoDriveLogger.getInstance().log(TRACE_TAG, message);
	}

	private static class CachedResponse {
		final String scope;
		final String query;
		final String body;
		final int placeCount;
		final long storedAt;

		CachedResponse(String scope, String query, String body, int placeCount, long storedAt) {
			this.scope = scope;
			this.query = query;
			this.body = body;
			this.placeCount = placeCount;
			this.storedAt = storedAt;
		}

		boolean isExpired() {
			return System.currentTimeMillis() - storedAt > CACHE_TTL_MS;
		}
	}
}
