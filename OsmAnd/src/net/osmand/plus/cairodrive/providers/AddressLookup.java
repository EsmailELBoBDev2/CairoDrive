package net.osmand.plus.cairodrive.providers;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import net.osmand.data.LatLon;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.helpers.CairoDriveLog;
import net.osmand.util.Algorithms;

import java.util.ArrayList;
import java.util.List;

/**
 * One entry point for "what is this place called", with the providers behind it in a fixed order.
 *
 * <h3>Why a chain rather than a winner</h3>
 *
 * Every other capability in this app arbitrates once and uses the winner - TomTom wins flow, and
 * that is that. Addresses are different, and the difference is worth stating because it is the
 * reason this class exists rather than another {@code CairoDriveProviders.Provider}:
 *
 * <ul>
 *   <li>A traffic provider that returns nothing is <b>answering</b>: there is no congestion. A
 *       geocoder that returns nothing has <b>failed</b> - the street exists either way.</li>
 *   <li>Both free geocoders draw from OSM, so a miss by one is quite likely a miss by both, and
 *       finding that out costs a request. Ordering them by TERMS rather than by quality is
 *       therefore the honest arrangement: they are near enough the same data.</li>
 * </ul>
 *
 * <h3>The order, and what decides it</h3>
 *
 * <ol>
 *   <li><b>Geoapify.</b> 3,000 credits/day and explicit permission to cache and store. That last
 *       part is what makes it the primary - anything the app is allowed to remember has to come
 *       from here.</li>
 *   <li><b>LocationIQ.</b> 5,000/day on a separate vendor, but a 48-hour caching ceiling, so it
 *       is used and dropped. Reached when Geoapify has no key, is out of budget, or errored.</li>
 *   <li><b>Nothing.</b> The caller falls back to whatever the offline .obf knows. That is a real
 *       answer for a named arterial and no answer at all in an alley, which is the gap this whole
 *       chain exists to cover.</li>
 * </ol>
 *
 * <p>Google is deliberately absent. Its Geocoding API is a separate SKU the provider audit rules
 * out, and its Places corpus - which is genuinely the best in Cairo - is reserved for SEARCH,
 * where nothing else comes close. Spending it on "what street is this" would be paying the most
 * for the job the free providers do adequately.
 */
public final class AddressLookup {

	private static final String TRACE_TAG = "CD_GEOCODE";

	private AddressLookup() {
	}

	/** True when at least one geocoder could answer. Cheap - checks keys only, no network. */
	public static boolean available() {
		return GeoapifyProvider.hasKey() || LocationIqProvider.hasKey();
	}

	/**
	 * Street-level address for a point, or null when no provider could supply one.
	 *
	 * <p>BLOCKING - both providers block, and wrapping them in a thread here would hide which one
	 * answered from the caller that has to decide what to do about it.
	 */
	@Nullable
	public static String describe(@NonNull OsmandApplication app, @Nullable LatLon at) {
		if (at == null) {
			return null;
		}
		String primary = GeoapifyProvider.reverseGeocode(app, at);
		if (!Algorithms.isEmpty(primary)) {
			return primary;
		}
		String fallback = LocationIqProvider.reverseGeocode(app, at);
		if (!Algorithms.isEmpty(fallback)) {
			// Worth a line: a drive log where every address came from the fallback means the
			// primary is broken, and that is invisible from the result alone.
			CairoDriveLog.log(TRACE_TAG, "address from FALLBACK (LocationIQ) - Geoapify gave nothing");
			return fallback;
		}
		return null;
	}

	// There is deliberately no cacheable() here.
	//
	// One was written, asking whether the string describe() returned may be stored - Geoapify
	// permits it outright, LocationIQ's free tier caps it at 48 hours. Nothing ever called it,
	// because nothing in this chain caches anything: every result goes straight to its caller and
	// is dropped. An advisory method that no caller consults is not a safeguard, it is a claim
	// that one exists. If a cache is ever added here, the LocationIQ term is the reason it must
	// hold Geoapify results only.

	/**
	 * As-you-type suggestions, primary only unless it is unavailable.
	 *
	 * <p>The fallback is NOT tried when the primary merely returns nothing. Two providers per
	 * keystroke is how a typing path becomes the thing that made the app "buggy as hell" the last
	 * time features went in together - and an empty suggestion list while typing is a normal,
	 * survivable state, unlike a missing address.
	 */
	@NonNull
	public static List<GeoapifyProvider.Suggestion> suggest(@NonNull OsmandApplication app,
	                                                        @Nullable String query) {
		if (GeoapifyProvider.hasKey()) {
			return GeoapifyProvider.autocomplete(app, query);
		}
		if (LocationIqProvider.hasKey()) {
			return LocationIqProvider.autocomplete(app, query);
		}
		return new ArrayList<>();
	}
}
