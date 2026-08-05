package net.osmand.plus.auto.screens;

import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.model.Action;
import androidx.car.app.model.ItemList;
import androidx.car.app.model.Row;
import androidx.car.app.model.SearchTemplate;
import androidx.car.app.model.SearchTemplate.SearchCallback;
import androidx.car.app.model.Template;
import androidx.lifecycle.DefaultLifecycleObserver;
import androidx.lifecycle.LifecycleOwner;

import net.osmand.PlatformUtil;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.osm.AbstractPoiType;
import net.osmand.plus.AppInitializer;
import net.osmand.plus.AppInitializeListener;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.auto.SearchHelper;
import net.osmand.plus.auto.SearchHelper.SearchHelperListener;
import net.osmand.plus.cairodrive.search.GooglePlacesDetailsApi;
import net.osmand.plus.search.history.SearchHistoryHelper;
import net.osmand.plus.helpers.TargetPointsHelper;
import net.osmand.plus.helpers.TargetPoint;
import net.osmand.plus.mapmarkers.MapMarker;
import net.osmand.plus.poi.PoiUIFilter;
import net.osmand.plus.routing.RoutingHelper;
import net.osmand.plus.search.listitems.QuickSearchListItem;
import net.osmand.plus.settings.backend.ApplicationMode;
import net.osmand.plus.settings.backend.preferences.OsmandPreference;
import net.osmand.plus.settings.enums.HistorySource;
import net.osmand.search.SearchUICore;
import net.osmand.search.core.ObjectType;
import net.osmand.search.core.SearchPhrase;
import net.osmand.search.core.SearchResult;
import net.osmand.search.core.SearchWord;
import net.osmand.util.Algorithms;

import org.apache.commons.logging.Log;

import java.util.ArrayList;
import java.util.List;

public final class SearchScreen extends BaseSearchScreen implements DefaultLifecycleObserver,
		AppInitializeListener, SearchHelperListener {

	private static final Log LOG = PlatformUtil.getLog(SearchScreen.class);
	private static final int MAP_MARKERS_LIMIT = 3;

	@NonNull
	private final Action settingsAction;

	private ItemList itemList = withNoResults(new ItemList.Builder()).build();

	private String searchText;
	private boolean loading;
	private boolean destroyed;
	private List<SearchResult> recentResults;
	private boolean showResult;

	public SearchScreen(@NonNull CarContext carContext, @NonNull Action settingsAction) {
		super(carContext);
		this.settingsAction = settingsAction;

		getLifecycle().addObserver(this);
	}

	@NonNull
	public SearchUICore getSearchUICore() {
		return getApp().getSearchUICore().getCore();
	}

	@Override
	public void onDestroy(@NonNull LifecycleOwner owner) {
		super.onDestroy(owner);
		// A queued debounced search holds this screen alive through the Handler and would run
		// against a destroyed screen 350 ms later. The other two removals here exist for the same
		// reason; this is the third.
		cancelPendingSearch();
		// The search is over, however it ended. Leaving the token alive would carry one billed
		// autocomplete session into the next unrelated search.
		endAutocompleteSession();
		getApp().getAppInitializer().removeListener(this);
		getLifecycle().removeObserver(this);
		destroyed = true;
	}

	@Override
	protected void onFirstGetTemplate() {
		super.onFirstGetTemplate();
		getApp().getAppInitializer().addListener(this);
		reloadHistory();
	}

	@NonNull
	@Override
	public Template getTemplate() {
		String searchQuery = getSearchHelper().getSearchQuery();
		String searchHint = getSearchHelper().getSearchHint();
		SearchTemplate.Builder builder = new SearchTemplate.Builder(new SearchCallback() {
			@Override
			public void onSearchTextChanged(@NonNull String searchText) {
				SearchScreen.this.searchText = searchText;
				getSearchHelper().resetSearchRadius();
				scheduleSearch(searchText);
			}

			@Override
			public void onSearchSubmitted(@NonNull String searchTerm) {
				// A pending debounced search is now irrelevant - the user has committed, and the
				// top result is being taken. Leaving it queued would fire a search against a
				// screen that is already navigating away.
				cancelPendingSearch();
				// When the user presses the search key use the top item in the list
				// as the result and simulate as if the user had pressed that.
				List<SearchResult> searchResults = getSearchHelper().getSearchResults();
				if (!Algorithms.isEmpty(searchResults)) {
					onClickSearchResult(searchResults.get(0));
				}
			}
		});

		builder.setHeaderAction(Action.BACK)
				.setShowKeyboardByDefault(false)
				.setInitialSearchText(searchQuery == null ? "" : searchQuery);
		if (!Algorithms.isEmpty(searchHint)) {
			builder.setSearchHint(searchHint);
		}
		if (loading || getSearchHelper().isSearching()) {
			builder.setLoading(true);
		} else {
			builder.setLoading(false);
			ItemList merged = withSuggestions(itemList);
			if (merged != null) {
				builder.setItemList(merged);
			}
		}

		return builder.build();
	}

	/**
	 * How long typing must pause before a search actually runs. Long enough that a word typed at
	 * speed produces one search instead of one per letter, short enough not to feel laggy - the
	 * range every major search UI sits in.
	 */
	private static final long SEARCH_DEBOUNCE_MS = 350;

	private final android.os.Handler searchHandler =
			new android.os.Handler(android.os.Looper.getMainLooper());
	@Nullable
	private Runnable pendingSearch;

	/**
	 * Runs the search once typing pauses, instead of once per keystroke.
	 *
	 * <p>Every character typed used to start a full search: an index scan across the loaded
	 * {@code .obf}, on a phone already drawing an Android Auto surface. Typing "مصر الجديدة"
	 * launched eleven of them, ten of which were obsolete the moment the next letter arrived, and
	 * the visible result was a list flickering through the answers to prefixes the driver had
	 * already finished typing.
	 *
	 * <p>An empty box is handled immediately and deliberately: showing recents costs nothing and
	 * making the driver wait 350 ms to see their own history after clearing the field would be a
	 * regression, not a fix.
	 *
	 * <p>This also matters ahead of any Places autocomplete work. That endpoint is billed per
	 * keystroke session, so a per-keystroke trigger is a bill as well as a stall - and CLAUDE.md
	 * records that the previous attempt at typing-time features had to be reverted wholesale.
	 */
	private void scheduleSearch(String searchText) {
		cancelPendingSearch();
		if (searchText.isEmpty()) {
			doSearch(searchText);
			return;
		}
		Runnable task = () -> {
			pendingSearch = null;
			doSearch(searchText);
			requestAutocomplete(searchText);
		};
		pendingSearch = task;
		searchHandler.postDelayed(task, SEARCH_DEBOUNCE_MS);
	}

	/**
	 * Google autocomplete, on the debounced tick and never on the keystroke.
	 *
	 * <p><b>This is the most expensive call in the app and the only one billed per SESSION.</b> A
	 * session is "the keystrokes leading to one selection", so it is charged once if the token is
	 * held across the whole word and once PER CHARACTER if it is not. Three things keep that from
	 * happening, and all three have to hold together:
	 * <ul>
	 *   <li>it hangs off {@link #scheduleSearch}'s existing 350 ms debounce, so "مصر الجديدة"
	 *       is one request rather than eleven;</li>
	 *   <li>{@code GooglePlacesDetailsApi} holds the session token itself and only clears it on
	 *       {@code endAutocompleteSession()}, which is called below on selection and on destroy -
	 *       the two ways a session can actually end;</li>
	 *   <li>a query shorter than {@link #AUTOCOMPLETE_MIN_CHARS} is not sent at all. One or two
	 *       characters cannot produce a useful suggestion in a city of this size, and they are
	 *       exactly the characters a driver types on the way to something longer.</li>
	 * </ul>
	 *
	 * <p>CLAUDE.md is explicit that this feature has to be judged while TYPING rather than after,
	 * because that is where the previous attempt went wrong. {@code CD_SEARCH} carries the request
	 * count for exactly that reading.
	 */
	private void requestAutocomplete(@NonNull String searchText) {
		if (!GooglePlacesDetailsApi.autocompleteEnabled()
				|| searchText.length() < AUTOCOMPLETE_MIN_CHARS) {
			return;
		}
		String query = searchText;
		LatLon around = getSearchLocation();
		GooglePlacesDetailsApi api = placesApi();
		new Thread(() -> {
			List<GooglePlacesDetailsApi.Suggestion> found = api.autocomplete(query, around);
			getCarContext().getMainExecutor().execute(() -> {
				// The driver has typed on since this was sent. Dropping a stale answer is the
				// whole reason the query is compared rather than just assigned - otherwise a slow
				// response overwrites the suggestions for the word actually in the box.
				if (destroyed || !query.equals(searchText)) {
					return;
				}
				suggestions = found;
				invalidate();
			});
		}, "cd-autocomplete").start();
	}

	/** Below this, a suggestion cannot be useful and the session would be paid for nothing. */
	private static final int AUTOCOMPLETE_MIN_CHARS = 3;

	@Nullable
	private List<GooglePlacesDetailsApi.Suggestion> suggestions;

	/** Where to bias suggestions, or null if there is no fix yet. */
	@Nullable
	private LatLon getSearchLocation() {
		net.osmand.Location location = getApp().getLocationProvider().getLastKnownLocation();
		return location == null ? null : new LatLon(location.getLatitude(), location.getLongitude());
	}

	/**
	 * Ends the billed autocomplete session.
	 *
	 * <p>Called on selection and on destroy - the two ways a session genuinely ends. Missing
	 * either one does not fail visibly; it silently keeps one token alive across unrelated
	 * searches, which is the failure mode that only shows up on the invoice.
	 */
	private void endAutocompleteSession() {
		if (placesApi != null) {
			placesApi.endAutocompleteSession();
		}
		suggestions = null;
	}

	/**
	 * ONE instance for the life of this screen, and that is a billing requirement, not tidiness.
	 *
	 * <p>The autocomplete session token is an INSTANCE field on {@code GooglePlacesDetailsApi}.
	 * Constructing a new API object per request would therefore start a new billed session on
	 * every keystroke-group, and calling {@code endAutocompleteSession()} on a second instance
	 * would clear a token that was never used - producing exactly the per-keystroke charge the
	 * token exists to prevent, while looking correct in the code.
	 */
	@Nullable
	private GooglePlacesDetailsApi placesApi;

	@NonNull
	private GooglePlacesDetailsApi placesApi() {
		if (placesApi == null) {
			placesApi = new GooglePlacesDetailsApi(getApp());
		}
		return placesApi;
	}

	private void cancelPendingSearch() {
		Runnable task = pendingSearch;
		if (task != null) {
			searchHandler.removeCallbacks(task);
			pendingSearch = null;
		}
	}

	private void doSearch(String searchText) {
		if (!getApp().isApplicationInitializing()) {
			if (searchText.isEmpty()) {
				showRecents();
			} else {
				getSearchHelper().runSearch(searchText);
			}
		}
		invalidate();
	}

	public void onClickSearchResult(@NonNull SearchResult sr) {
		// A selection is what CLOSES an autocomplete session, in Google's billing sense. This is
		// the one call that turns a word's worth of keystrokes into a single charge.
		endAutocompleteSession();
		if (sr.objectType == ObjectType.POI
				|| sr.objectType == ObjectType.LOCATION
				|| sr.objectType == ObjectType.HOUSE
				|| sr.objectType == ObjectType.FAVORITE
				|| sr.objectType == ObjectType.MAP_MARKER
				|| sr.objectType == ObjectType.ROUTE
				|| sr.objectType == ObjectType.RECENT_OBJ
				|| sr.objectType == ObjectType.WPT
				|| sr.objectType == ObjectType.STREET_INTERSECTION
				|| sr.objectType == ObjectType.GPX_TRACK) {

			showResult(sr);
		} else {
			if (sr.objectType == ObjectType.CITY || sr.objectType == ObjectType.VILLAGE || sr.objectType == ObjectType.STREET) {
				showResult = true;
			}
			getSearchHelper().completeQueryWithObject(sr);
			if (sr.object instanceof AbstractPoiType || sr.object instanceof PoiUIFilter) {
				reloadHistory();
			}
			invalidate();
		}
	}

	private void showResult(SearchResult sr) {
		showResult = false;
		// B2 - a tapped place gets its detail pane first; see the same branch in
		// SearchResultsScreen. ObjectType.ROUTE reaches here too (the "previous route" card built
		// by reloadHistoryInternal) and is deliberately excluded by PlaceDetailsScreen.canShow:
		// that card is a one-tap resume, and a confirmation pane in front of it is a regression.
		if (PlaceDetailsScreen.canShow(sr)) {
			getScreenManager().push(new PlaceDetailsScreen(getCarContext(), settingsAction, sr,
					PlaceDetailsScreen.Origin.SEARCH));
		} else {
			openRoutePreview(settingsAction, sr);
		}
	}

	@Override
	public void onClickSearchMore() {
		invalidate();
	}

	@Override
	public void onSearchDone(@NonNull SearchPhrase phrase, @Nullable List<SearchResult> searchResults,
	                         @Nullable ItemList itemList, int resultsCount) {
		SearchWord lastSelectedWord = phrase.getLastSelectedWord();
		if (showResult && resultsCount == 0 && lastSelectedWord != null) {
			showResult(lastSelectedWord.getResult());
		} else {
			if (resultsCount > 0) {
				showResult = false;
			}
			this.itemList = itemList;
			invalidate();
		}
	}

	/**
	 * The offline results, then Google's suggestions beneath them.
	 *
	 * <p>Offline first, deliberately. The {@code .obf} answers instantly and without a network,
	 * and it is what the driver gets on the Ring Road with no signal; suggestions are an addition
	 * to that list, not a replacement for it. Putting them on top would make the common case feel
	 * slower - the list would visibly reshuffle 300 ms after it had already settled.
	 *
	 * <p>Capped at {@link #MAX_SUGGESTIONS}: the host's content limit rejects rows past its own
	 * cap outright, and a search list a driver has to scroll is not a search list they can use.
	 *
	 * <p>Tapping a suggestion re-runs the OFFLINE search against that text rather than fetching
	 * the place. That keeps this to the one billed endpoint it already pays for - a Place Details
	 * call per tapped suggestion would be a second SKU on a path a driver taps repeatedly - and
	 * the offline index resolves a completed name well even when it could not complete a prefix.
	 */
	@Nullable
	private ItemList withSuggestions(@Nullable ItemList base) {
		List<GooglePlacesDetailsApi.Suggestion> current = suggestions;
		if (current == null || current.isEmpty()) {
			return base;
		}
		ItemList.Builder builder = withNoResults(new ItemList.Builder());
		if (base != null) {
			for (androidx.car.app.model.Item item : base.getItems()) {
				builder.addItem(item);
			}
		}
		int added = 0;
		for (GooglePlacesDetailsApi.Suggestion suggestion : current) {
			if (added >= MAX_SUGGESTIONS) {
				break;
			}
			if (Algorithms.isEmpty(suggestion.text)) {
				continue;
			}
			String text = suggestion.text;
			builder.addItem(new androidx.car.app.model.Row.Builder()
					.setTitle(text)
					.setBrowsable(true)
					.setOnClickListener(() -> {
						searchText = text;
						cancelPendingSearch();
						doSearch(text);
					})
					.build());
			added++;
		}
		return builder.build();
	}

	private static final int MAX_SUGGESTIONS = 3;

	@NonNull
	private ItemList.Builder withNoResults(@NonNull ItemList.Builder builder) {
		return builder.setNoItemsMessage(getCarContext().getString(R.string.search_nothing_found));
	}

	public void reloadHistory() {
		if (getApp().isApplicationInitializing()) {
			loading = true;
		} else {
			reloadHistoryInternal();
		}
	}

	private void reloadHistoryInternal() {
		if (!destroyed) {
			OsmandApplication app = getApp();
			try {
				List<SearchResult> recentResults = new ArrayList<>();

				// Home / work
				/* Disable since points exists at favorites screen
				FavouritesDbHelper favorites = app.getFavorites();
				FavouritePoint homePoint = favorites.getSpecialPoint(FavouritePoint.SpecialPointType.HOME);
				FavouritePoint workPoint = favorites.getSpecialPoint(FavouritePoint.SpecialPointType.WORK);
				if (homePoint != null) {
					SearchResult result = new SearchResult();
					result.location = new LatLon(homePoint.getLatitude(), homePoint.getLongitude());
					result.objectType = ObjectType.FAVORITE;
					result.object = homePoint;
					result.localeName = homePoint.getAddress();
					recentResults.add(result);
				}
				if (workPoint != null) {
					SearchResult result = new SearchResult();
					result.location = new LatLon(workPoint.getLatitude(), workPoint.getLongitude());
					result.objectType = ObjectType.FAVORITE;
					result.object = workPoint;
					result.localeName = workPoint.getAddress();
					recentResults.add(result);
				}
				*/
				// Previous route card
				TargetPointsHelper targetPointsHelper = app.getTargetPointsHelper();
				TargetPoint startPoint = targetPointsHelper.getPointToStartBackup();
				boolean myLocation = false;
				if (startPoint == null) {
					myLocation = true;
					startPoint = targetPointsHelper.getMyLocationToStart();
				}
				TargetPoint destinationPoint = targetPointsHelper.getPointToNavigateBackup();
				if (startPoint != null && destinationPoint != null) {
					StringBuilder startText = new StringBuilder(myLocation ? app.getText(R.string.my_location) : "");
					String startDescr = getPointName(startPoint);
					if (!Algorithms.isEmpty(startDescr)) {
						if (startText.length() > 0) {
							startText.append(" — ");
						}
						startText.append(startDescr);
					}
					String destDescr = getPointName(destinationPoint);
					SearchResult result = new SearchResult();
					result.location = new LatLon(destinationPoint.getLatitude(), destinationPoint.getLongitude());
					result.objectType = ObjectType.ROUTE;
					result.object = destinationPoint;
					result.localeName = destDescr;
					result.relatedObject = startPoint;
					result.localeRelatedObjectName = startText.toString();
					recentResults.add(result);
				}

				// Map markers
				List<MapMarker> mapMarkers = app.getMapMarkersHelper().getMapMarkers();
				int mapMarkersCount = 0;
				for (MapMarker marker : mapMarkers) {
					SearchResult result = new SearchResult();
					result.location = new LatLon(marker.getLatitude(), marker.getLongitude());
					result.objectType = ObjectType.MAP_MARKER;
					result.object = marker;
					result.localeName = marker.getName(app);
					recentResults.add(result);
					mapMarkersCount++;
					if (mapMarkersCount >= MAP_MARKERS_LIMIT) {
						break;
					}
				}

				// History
				SearchHistoryHelper historyHelper = app.getSearchHistoryHelper();
				List<SearchResult> results = historyHelper.getHistoryResults(HistorySource.SEARCH, true, false);
				if (!Algorithms.isEmpty(results)) {
					recentResults.addAll(results);
				}
				this.recentResults = recentResults;
				if (!getSearchHelper().isSearching() && Algorithms.isEmpty(getSearchHelper().getSearchQuery())) {
					showRecents();
					invalidate();
				}
			} catch (Exception e) {
				LOG.error(e.getMessage(), e);
				app.showToastMessage(e.getMessage());
			}
		}
	}

	private String getPointName(TargetPoint targetPoint) {
		OsmandApplication app = getApp();
		String name = "";
		if (targetPoint != null) {
			PointDescription description = targetPoint.getOriginalPointDescription();
			if (description != null && !Algorithms.isEmpty(description.getName()) &&
					!description.getName().equals(app.getString(R.string.no_address_found))) {
				name = description.getName();
			} else {
				name = PointDescription.getLocationName(app, targetPoint.getLatLon().getLatitude(),
						targetPoint.getLatLon().getLongitude(), true).replace('\n', ' ');
			}
		}
		return name;
	}

	private void showRecents() {
		OsmandApplication app = getApp();
		ItemList.Builder itemList = withNoResults(new ItemList.Builder());

		if (Algorithms.isEmpty(recentResults)) {
			this.itemList = itemList.build();
			return;
		}
		int count = 0;
		for (SearchResult result : recentResults) {
			String name = QuickSearchListItem.getName(app, result);
			if (Algorithms.isEmpty(name)) {
				continue;
			}
			SearchHelper helper = getSearchHelper();
			Drawable icon = QuickSearchListItem.getIcon(app, result);
			if (helper != null) {
				String description = helper.addAddress(QuickSearchListItem.getTypeName(app, result), result);
				Row.Builder builder = helper.buildSearchRow(helper.getSearchLocation(), result.location, name, icon, description);
				if (builder != null) {
					builder.setOnClickListener(() -> onClickSearchResult(result));
					itemList.addItem(builder.build());
					count++;
					if (count >= helper.getContentLimit()) {
						break;
					}
				}
			}
		}
		this.itemList = itemList.build();
	}

	@Override
	public void onFinish(@NonNull AppInitializer init) {
		loading = false;
		if (!destroyed) {
			reloadHistoryInternal();
			if (!Algorithms.isEmpty(searchText)) {
				getSearchHelper().runSearch(searchText);
			}
			invalidate();
		}
	}

	private void updateApplicationMode(@NonNull ApplicationMode mode, @NonNull ApplicationMode next) {
		OsmandApplication app = getApp();
		RoutingHelper routingHelper = app.getRoutingHelper();
		OsmandPreference<ApplicationMode> appMode = app.getSettings().APPLICATION_MODE;
		if (routingHelper.isFollowingMode() && appMode.get() == mode) {
			appMode.set(next);
		}
		routingHelper.setAppMode(next);
		app.initVoiceCommandPlayer(app, next, null, true, false, false, true);
		routingHelper.onSettingsChanged(true);
	}

	@Override
	protected void onSearchResultSelected(@NonNull SearchResult sr) {
		if (sr.objectType == ObjectType.ROUTE) {
			ApplicationMode lastAppMode = getApp().getSettings().LAST_ROUTE_APPLICATION_MODE.get();
			ApplicationMode currentAppMode = getApp().getRoutingHelper().getAppMode();
			if (lastAppMode == ApplicationMode.DEFAULT) {
				lastAppMode = currentAppMode;
			}
			updateApplicationMode(currentAppMode, lastAppMode);
			getApp().getTargetPointsHelper().restoreTargetPoints(true);
		}
	}
}
