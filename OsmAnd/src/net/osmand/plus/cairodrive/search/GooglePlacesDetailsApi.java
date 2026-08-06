package net.osmand.plus.cairodrive.search;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.cairodrive.CairoDriveLogger;

import org.apache.commons.logging.Log;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The deferred Google Places features - Place Details, Photos, Reviews, Autocomplete and Nearby
 * Search - added together at the owner's explicit instruction.
 *
 * <h3>Why this is one class with five switches rather than five builds</h3>
 *
 * The standing rule in CLAUDE.md is one feature per build, each judged on a real drive. It exists
 * for a concrete reason: these went in all at once once before, the app was "buggy as hell", and
 * because everything landed together there was no way to tell WHICH addition caused it - so the
 * whole lot had to come out. The rule is not about billing, it is about attribution.
 *
 * <p>The owner has now asked for all of them at once and reaffirmed it. So this keeps the part of
 * the rule that was actually load-bearing and drops only the scheduling: <b>every feature has its
 * own build flag and its own log tag</b>. If the drive is bad, the culprit is identifiable from
 * {@code CD_PLACES} and removable on its own with one environment variable - which is exactly the
 * property whose absence forced the mass revert last time. Batching them costs the ability to
 * A/B one drive against another; it does not cost the ability to isolate a fault.
 *
 * <h3>Cost shape - read before adding a field</h3>
 *
 * Google bills by endpoint AND by requested field, so each of these sits in a different SKU:
 * <ul>
 *   <li>{@code GetPlace} - per tapped result. Enterprise SKU once hours/rating/reviews appear.</li>
 *   <li>{@code GetPhotoMedia} - per photo, plus bandwidth.</li>
 *   <li>{@code AutocompletePlaces} - billed per SESSION, and a session is a burst of keystrokes.
 *       By far the most expensive thing here if the session token is mishandled, which is why
 *       {@link #autocompleteSessionToken} exists and is reused until a search is committed.</li>
 *   <li>{@code SearchNearby} - per call.</li>
 * </ul>
 *
 * <p><b>Quotas are the real control and one of them is currently zero.</b> {@code SearchNearby} is
 * capped at 0/day in the console, so nearby search will return HTTP 429 until that is raised by
 * hand. That is deliberate on the owner's part and cannot be fixed from this repo - the code logs
 * it plainly rather than failing silently.
 *
 * <h3>Threading</h3>
 *
 * Everything here is blocking and must never be called from the main thread; {@link #IO} is the
 * single-thread executor callers should use. The Android Auto screens call
 * {@link #detailsAsync(String, DetailsCallback)}, which does that for them and delivers back on
 * the caller's thread of choice.
 */
public class GooglePlacesDetailsApi {

	private static final Log LOG = PlatformUtil.getLog(GooglePlacesDetailsApi.class);

	/** Distinct from CD_SEARCH so a drive log can attribute cost and latency per feature. */
	public static final String TRACE_TAG = "CD_PLACES";

	private static final String PLACE_URL = "https://places.googleapis.com/v1/places/";
	private static final String AUTOCOMPLETE_URL = "https://places.googleapis.com/v1/places:autocomplete";
	private static final String NEARBY_URL = "https://places.googleapis.com/v1/places:searchNearby";
	private static final String PHOTO_URL = "https://places.googleapis.com/v1/";

	/**
	 * Place Details field mask. Every entry here is something the owner asked for by name - "the
	 * info Google Maps shows for a business". Kept explicit rather than assembled from the enabled
	 * flags, because a field mask that varies per build makes a billing question unanswerable.
	 */
	private static final String DETAILS_FIELDS =
			"id,displayName,formattedAddress,location,types,primaryType,"
					+ "nationalPhoneNumber,internationalPhoneNumber,websiteUri,"
					+ "rating,userRatingCount,priceLevel,businessStatus,"
					+ "currentOpeningHours,regularOpeningHours,editorialSummary";
	private static final String REVIEW_FIELDS = ",reviews";
	private static final String PHOTO_FIELDS = ",photos";

	private static final String AUTOCOMPLETE_FIELDS =
			"suggestions.placePrediction.placeId,suggestions.placePrediction.text";
	private static final String NEARBY_FIELDS =
			"places.id,places.displayName,places.formattedAddress,places.location,"
					+ "places.types,places.primaryType";

	private static final int CONNECT_TIMEOUT_MS = 4000;
	private static final int READ_TIMEOUT_MS = 6000;

	/**
	 * Details are cached by place id for the life of the process. A business's hours do not change
	 * during a drive, and the alternative is paying {@code GetPlace} again every time the driver
	 * reopens the same pane - which is exactly what someone comparing two petrol stations does.
	 */
	private static final int CACHE_SIZE = 48;
	private static final Map<String, PlaceDetails> CACHE =
			new LinkedHashMap<String, PlaceDetails>(CACHE_SIZE, 0.75f, true) {
				@Override
				protected boolean removeEldestEntry(Map.Entry<String, PlaceDetails> eldest) {
					return size() > CACHE_SIZE;
				}
			};

	/** Single thread: these are never urgent and must not compete with routing for cores. */
	private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "cd-places");
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});

	private final OsmandApplication app;

	public GooglePlacesDetailsApi(@NonNull OsmandApplication app) {
		this.app = app;
	}

	// ---------------------------------------------------------------- feature switches

	public static boolean detailsEnabled() {
		return BuildConfig.CAIRODRIVE_PLACES_DETAILS && GooglePlacesSearchApi.isConfigured();
	}

	public static boolean photosEnabled() {
		return BuildConfig.CAIRODRIVE_PLACES_PHOTOS && GooglePlacesSearchApi.isConfigured();
	}

	public static boolean reviewsEnabled() {
		return BuildConfig.CAIRODRIVE_PLACES_REVIEWS && GooglePlacesSearchApi.isConfigured();
	}

	public static boolean autocompleteEnabled() {
		return BuildConfig.CAIRODRIVE_PLACES_AUTOCOMPLETE && GooglePlacesSearchApi.isConfigured();
	}

	public static boolean nearbyEnabled() {
		return BuildConfig.CAIRODRIVE_PLACES_NEARBY && GooglePlacesSearchApi.isConfigured();
	}

	// ---------------------------------------------------------------- model

	/** Everything a pane might show. Fields absent from the response stay null - never empty. */
	public static class PlaceDetails {
		public String id;
		public String name;
		public String address;
		public String phone;
		public String website;
		public Double rating;
		public Integer ratingCount;
		public String priceLevel;
		public String businessStatus;
		public Boolean openNow;
		public String hoursToday;
		public String summary;
		/**
		 * Where the place is, or null.
		 *
		 * <p>{@code location} was in both field masks from the start - and therefore paid for on
		 * every call - but nothing parsed it, so a Nearby result arrived with a name and no
		 * coordinates. That makes it unroutable and unplottable: the entire point of a nearby
		 * search is somewhere to drive to.
		 */
		public LatLon location;
		public final List<String> reviews = new ArrayList<>();
		public final List<String> photoNames = new ArrayList<>();
	}

	public interface DetailsCallback {
		void onDetails(@Nullable PlaceDetails details);
	}

	public static class Suggestion {
		public final String placeId;
		public final String text;

		Suggestion(String placeId, String text) {
			this.placeId = placeId;
			this.text = text;
		}
	}

	// ---------------------------------------------------------------- Place Details

	/** Cached lookup with no network. Lets a screen render instantly on a revisit. */
	@Nullable
	public static PlaceDetails cachedDetails(@Nullable String placeId) {
		if (placeId == null) {
			return null;
		}
		synchronized (CACHE) {
			return CACHE.get(placeId);
		}
	}

	/**
	 * Fetches on {@link #IO} and calls back. Never touches the main thread itself - the caller
	 * decides how to get back to the UI, because an Android Auto Screen wants
	 * {@code invalidate()} while a phone fragment wants a post.
	 */
	public void detailsAsync(@NonNull String placeId, @NonNull DetailsCallback callback) {
		PlaceDetails cached = cachedDetails(placeId);
		if (cached != null) {
			callback.onDetails(cached);
			return;
		}
		if (!detailsEnabled()) {
			callback.onDetails(null);
			return;
		}
		IO.execute(() -> {
			PlaceDetails details = null;
			try {
				details = details(placeId);
			} catch (Throwable t) {
				LOG.error(TRACE_TAG + " details failed", t);
			}
			callback.onDetails(details);
		});
	}

	/** Blocking. {@code GetPlace}, one billed request. */
	@Nullable
	public PlaceDetails details(@NonNull String placeId) {
		if (!detailsEnabled()) {
			return null;
		}
		String fields = DETAILS_FIELDS
				+ (reviewsEnabled() ? REVIEW_FIELDS : "")
				+ (photosEnabled() ? PHOTO_FIELDS : "");
		long start = System.currentTimeMillis();
		// The id already carries the "places/" prefix in some responses and not in others.
		String path = placeId.startsWith("places/") ? placeId.substring("places/".length()) : placeId;
		String body = get(PLACE_URL + enc(path), fields);
		long ms = System.currentTimeMillis() - start;
		if (body == null) {
			log("details id=" + path + " FAILED ms=" + ms);
			return null;
		}
		try {
			PlaceDetails d = parseDetails(new JSONObject(body));
			synchronized (CACHE) {
				CACHE.put(placeId, d);
			}
			log("details id=" + path + " ms=" + ms
					+ " rating=" + (d.rating != null)
					+ " hours=" + (d.hoursToday != null)
					+ " phone=" + (d.phone != null)
					+ " reviews=" + d.reviews.size()
					+ " photos=" + d.photoNames.size());
			return d;
		} catch (Exception e) {
			LOG.error(TRACE_TAG + " details parse failed", e);
			return null;
		}
	}

	@NonNull
	private PlaceDetails parseDetails(@NonNull JSONObject o) {
		PlaceDetails d = new PlaceDetails();
		d.id = o.optString("id", null);
		JSONObject dn = o.optJSONObject("displayName");
		d.name = dn != null ? dn.optString("text", null) : null;
		d.address = o.optString("formattedAddress", null);
		d.phone = o.optString("nationalPhoneNumber",
				o.optString("internationalPhoneNumber", null));
		d.website = o.optString("websiteUri", null);
		if (o.has("rating")) {
			d.rating = o.optDouble("rating");
		}
		if (o.has("userRatingCount")) {
			d.ratingCount = o.optInt("userRatingCount");
		}
		d.priceLevel = o.optString("priceLevel", null);
		d.businessStatus = o.optString("businessStatus", null);

		// Guarded on has() rather than on optDouble's default: 0.0 is a real coordinate in the
		// Gulf of Guinea, so a missing field defaulting to zero would place every unlocated result
		// at Null Island and route the driver towards the Atlantic.
		JSONObject location = o.optJSONObject("location");
		if (location != null && location.has("latitude") && location.has("longitude")) {
			d.location = new LatLon(location.optDouble("latitude"), location.optDouble("longitude"));
		}

		JSONObject hours = o.optJSONObject("currentOpeningHours");
		if (hours == null) {
			hours = o.optJSONObject("regularOpeningHours");
		}
		if (hours != null) {
			if (hours.has("openNow")) {
				d.openNow = hours.optBoolean("openNow");
			}
			JSONArray desc = hours.optJSONArray("weekdayDescriptions");
			if (desc != null && desc.length() > 0) {
				// Google returns Monday-first; today's line is what a driver wants, not seven.
				int idx = todayIndexMondayFirst();
				d.hoursToday = desc.optString(Math.min(idx, desc.length() - 1), null);
			}
		}
		JSONObject summary = o.optJSONObject("editorialSummary");
		if (summary != null) {
			d.summary = summary.optString("text", null);
		}
		JSONArray reviews = o.optJSONArray("reviews");
		if (reviews != null) {
			for (int i = 0; i < reviews.length() && d.reviews.size() < 3; i++) {
				JSONObject r = reviews.optJSONObject(i);
				JSONObject text = r != null ? r.optJSONObject("originalText") : null;
				if (text == null && r != null) {
					text = r.optJSONObject("text");
				}
				String body = text != null ? text.optString("text", null) : null;
				if (body != null && !body.isEmpty()) {
					d.reviews.add(body);
				}
			}
		}
		JSONArray photos = o.optJSONArray("photos");
		if (photos != null) {
			for (int i = 0; i < photos.length() && d.photoNames.size() < 3; i++) {
				JSONObject p = photos.optJSONObject(i);
				String name = p != null ? p.optString("name", null) : null;
				if (name != null && !name.isEmpty()) {
					d.photoNames.add(name);
				}
			}
		}
		return d;
	}

	private static int todayIndexMondayFirst() {
		java.util.Calendar c = java.util.Calendar.getInstance();
		int dow = c.get(java.util.Calendar.DAY_OF_WEEK); // SUNDAY = 1
		return (dow + 5) % 7; // MONDAY -> 0 ... SUNDAY -> 6
	}

	// ---------------------------------------------------------------- Photos

	/**
	 * {@code GetPhotoMedia}. Returns the raw bytes, capped by {@code maxWidth} because the head
	 * unit is ~800 px wide and a full-resolution photo is bandwidth spent on pixels nobody sees.
	 */
	@Nullable
	public byte[] photo(@NonNull String photoName, int maxWidth) {
		if (!photosEnabled()) {
			return null;
		}
		String url = PHOTO_URL + photoName + "/media?maxWidthPx=" + maxWidth + "&skipHttpRedirect=false";
		long start = System.currentTimeMillis();
		byte[] bytes = getBytes(url);
		log("photo name=" + photoName + " bytes=" + (bytes == null ? -1 : bytes.length)
				+ " ms=" + (System.currentTimeMillis() - start));
		return bytes;
	}

	// ---------------------------------------------------------------- Autocomplete

	/**
	 * Session token for {@code AutocompletePlaces}.
	 *
	 * <p>This is the single most expensive thing in this class if it is handled carelessly: Google
	 * bills autocomplete per SESSION, and a session is "the keystrokes leading to one selection".
	 * Sending no token, or a fresh token per keystroke, turns one billable session into one per
	 * character. It is reset only by {@link #endAutocompleteSession()}, which the caller invokes
	 * when a result is chosen or the search is abandoned.
	 */
	private volatile String autocompleteSessionToken;

	public void endAutocompleteSession() {
		autocompleteSessionToken = null;
	}

	/** Blocking. Call from a background thread, and judge it WHILE TYPING, not after. */
	@NonNull
	public List<Suggestion> autocomplete(@NonNull String query, @Nullable LatLon around) {
		List<Suggestion> out = new ArrayList<>();
		if (!autocompleteEnabled() || query.trim().length() < 3) {
			return out;
		}
		if (autocompleteSessionToken == null) {
			autocompleteSessionToken = java.util.UUID.randomUUID().toString();
		}
		JSONObject body = new JSONObject();
		try {
			body.put("input", query);
			body.put("sessionToken", autocompleteSessionToken);
			if (around != null) {
				JSONObject centre = new JSONObject();
				centre.put("latitude", around.getLatitude());
				centre.put("longitude", around.getLongitude());
				JSONObject circle = new JSONObject();
				circle.put("center", centre);
				circle.put("radius", 30000.0);
				JSONObject bias = new JSONObject();
				bias.put("circle", circle);
				body.put("locationBias", bias);
			}
		} catch (Exception e) {
			return out;
		}
		long start = System.currentTimeMillis();
		String response = post(AUTOCOMPLETE_URL, body.toString(), AUTOCOMPLETE_FIELDS);
		long ms = System.currentTimeMillis() - start;
		if (response == null) {
			log("autocomplete q=" + query.length() + "ch FAILED ms=" + ms);
			return out;
		}
		try {
			JSONArray suggestions = new JSONObject(response).optJSONArray("suggestions");
			if (suggestions != null) {
				for (int i = 0; i < suggestions.length(); i++) {
					JSONObject s = suggestions.optJSONObject(i);
					JSONObject p = s != null ? s.optJSONObject("placePrediction") : null;
					if (p == null) {
						continue;
					}
					JSONObject text = p.optJSONObject("text");
					String label = text != null ? text.optString("text", null) : null;
					String id = p.optString("placeId", null);
					if (id != null && label != null) {
						out.add(new Suggestion(id, label));
					}
				}
			}
		} catch (Exception e) {
			LOG.error(TRACE_TAG + " autocomplete parse failed", e);
		}
		log("autocomplete q=" + query.length() + "ch results=" + out.size() + " ms=" + ms);
		return out;
	}

	// ---------------------------------------------------------------- Nearby

	/**
	 * {@code SearchNearby} - "petrol near me".
	 *
	 * <p><b>The console quota for this endpoint is 0/day.</b> Until it is raised by hand this
	 * returns nothing and logs a 429; that is a deliberate setting on the owner's account, not a
	 * bug here, and raising it is part of shipping the feature.
	 */
	@NonNull
	public List<PlaceDetails> nearby(@NonNull LatLon around, @NonNull List<String> includedTypes,
	                                 int maxResults, double radiusMetres) {
		List<PlaceDetails> out = new ArrayList<>();
		if (!nearbyEnabled()) {
			return out;
		}
		JSONObject body = new JSONObject();
		try {
			JSONArray types = new JSONArray();
			for (String t : includedTypes) {
				types.put(t);
			}
			body.put("includedTypes", types);
			body.put("maxResultCount", Math.max(1, Math.min(20, maxResults)));
			JSONObject centre = new JSONObject();
			centre.put("latitude", around.getLatitude());
			centre.put("longitude", around.getLongitude());
			JSONObject circle = new JSONObject();
			circle.put("center", centre);
			circle.put("radius", radiusMetres);
			JSONObject restriction = new JSONObject();
			restriction.put("circle", circle);
			body.put("locationRestriction", restriction);
		} catch (Exception e) {
			return out;
		}
		long start = System.currentTimeMillis();
		String response = post(NEARBY_URL, body.toString(), NEARBY_FIELDS);
		long ms = System.currentTimeMillis() - start;
		if (response == null) {
			log("nearby FAILED ms=" + ms + " (quota for SearchNearby is 0/day unless raised)");
			return out;
		}
		try {
			JSONArray places = new JSONObject(response).optJSONArray("places");
			if (places != null) {
				for (int i = 0; i < places.length(); i++) {
					JSONObject p = places.optJSONObject(i);
					if (p != null) {
						out.add(parseDetails(p));
					}
				}
			}
		} catch (Exception e) {
			LOG.error(TRACE_TAG + " nearby parse failed", e);
		}
		log("nearby types=" + includedTypes.size() + " results=" + out.size() + " ms=" + ms);
		return out;
	}

	// ---------------------------------------------------------------- transport

	@Nullable
	private String get(@NonNull String url, @NonNull String fieldMask) {
		HttpURLConnection connection = null;
		try {
			connection = open(url, fieldMask);
			connection.setRequestMethod("GET");
			int code = connection.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) {
				LOG.error(TRACE_TAG + " GET " + code + " " + readString(connection.getErrorStream()));
				return null;
			}
			return readString(connection.getInputStream());
		} catch (IOException | RuntimeException e) {
			LOG.error(TRACE_TAG + " GET failed", e);
			return null;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	@Nullable
	private byte[] getBytes(@NonNull String url) {
		HttpURLConnection connection = null;
		try {
			// No field mask on photo media - it returns bytes, not JSON.
			connection = open(url, null);
			connection.setRequestMethod("GET");
			int code = connection.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) {
				LOG.error(TRACE_TAG + " photo " + code);
				return null;
			}
			try (InputStream in = connection.getInputStream()) {
				ByteArrayOutputStream buffer = new ByteArrayOutputStream();
				byte[] chunk = new byte[8192];
				int n;
				while ((n = in.read(chunk)) > 0) {
					buffer.write(chunk, 0, n);
				}
				return buffer.toByteArray();
			}
		} catch (IOException | RuntimeException e) {
			LOG.error(TRACE_TAG + " photo failed", e);
			return null;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	@Nullable
	private String post(@NonNull String url, @NonNull String json, @NonNull String fieldMask) {
		HttpURLConnection connection = null;
		try {
			connection = open(url, fieldMask);
			connection.setRequestMethod("POST");
			connection.setDoOutput(true);
			connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
			try (OutputStream out = connection.getOutputStream()) {
				out.write(json.getBytes(StandardCharsets.UTF_8));
			}
			int code = connection.getResponseCode();
			if (code != HttpURLConnection.HTTP_OK) {
				LOG.error(TRACE_TAG + " POST " + code + " " + readString(connection.getErrorStream()));
				return null;
			}
			return readString(connection.getInputStream());
		} catch (IOException | RuntimeException e) {
			LOG.error(TRACE_TAG + " POST failed", e);
			return null;
		} finally {
			if (connection != null) {
				connection.disconnect();
			}
		}
	}

	@NonNull
	private HttpURLConnection open(@NonNull String url, @Nullable String fieldMask)
			throws IOException {
		HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
		connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
		connection.setReadTimeout(READ_TIMEOUT_MS);
		connection.setRequestProperty("X-Goog-Api-Key", BuildConfig.GOOGLE_PLACES_API_KEY);
		if (fieldMask != null) {
			connection.setRequestProperty("X-Goog-FieldMask", fieldMask);
		}
		// Same Android app restriction headers the text search sends. Without them the key is
		// rejected with API_KEY_ANDROID_APP_BLOCKED regardless of how it is configured.
		String cert = GooglePlacesSearchApi.signingCertificateSha1(app);
		if (cert != null) {
			connection.setRequestProperty("X-Android-Package", app.getPackageName());
			connection.setRequestProperty("X-Android-Cert", cert);
		}
		return connection;
	}

	@NonNull
	private static String enc(@NonNull String s) {
		try {
			return URLEncoder.encode(s, "UTF-8").replace("+", "%20");
		} catch (IOException e) {
			return s;
		}
	}

	@NonNull
	private static String readString(@Nullable InputStream stream) throws IOException {
		if (stream == null) {
			return "";
		}
		try (InputStream in = stream) {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			byte[] chunk = new byte[4096];
			int n;
			while ((n = in.read(chunk)) > 0) {
				buffer.write(chunk, 0, n);
			}
			return buffer.toString("UTF-8");
		}
	}

	private static void log(@NonNull String message) {
		CairoDriveLogger.getInstance().log(TRACE_TAG, message);
	}
}
