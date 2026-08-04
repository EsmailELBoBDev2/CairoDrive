package net.osmand.plus.auto.screens;

import static android.text.Spanned.SPAN_INCLUSIVE_INCLUSIVE;

import android.text.SpannableString;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.car.app.CarContext;
import androidx.car.app.constraints.ConstraintManager;
import androidx.car.app.model.Action;
import androidx.car.app.model.ActionStrip;
import androidx.car.app.model.DistanceSpan;
import androidx.car.app.model.Header;
import androidx.car.app.model.Pane;
import androidx.car.app.model.PaneTemplate;
import androidx.car.app.model.Row;
import androidx.car.app.model.Template;
import androidx.car.app.navigation.model.MapWithContentTemplate;

import net.osmand.data.Amenity;
import net.osmand.data.LatLon;
import net.osmand.data.PointDescription;
import net.osmand.plus.BuildConfig;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.R;
import net.osmand.plus.auto.TripUtils;
import net.osmand.plus.cairodrive.CairoDriveLogger;
import net.osmand.plus.search.listitems.QuickSearchListItem;
import net.osmand.plus.utils.OsmAndFormatter;
import net.osmand.search.core.ObjectType;
import net.osmand.search.core.SearchResult;
import net.osmand.util.Algorithms;
import net.osmand.util.MapUtils;

/**
 * B2 - the place-detail pane for Android Auto.
 *
 * <p>Before this there was no place-detail view on the head unit at all: tapping a search result
 * went straight to route preview, so a driver committed to a destination without ever seeing its
 * address, its category or how far away it was, and the app had nowhere to put anything else it
 * knew about a place. That second half is the real reason this exists - the deferred Google Places
 * fields (opening hours, phone, rating, photos) have no surface to render on, and adding one of
 * them per drive is impossible until there is a pane to add them to. See the extension point at
 * the bottom of this class; nothing here calls the Places API.
 *
 * <h3>Why the pane looks as sparse as it does</h3>
 * <ul>
 *   <li><b>Android Auto caps a row's secondary text at 2 lines while driving.</b> Anything past
 *       the second {@code addText} is dropped by the host, silently - so the pane is built to
 *       spend exactly those two lines on the two things that identify a place from the driver's
 *       seat: how far away and in which direction it is, and what kind of place it is.</li>
 *   <li><b>NHTSA guidance puts a glance at 2 s.</b> That is roughly one row of text plus a
 *       heading, which is why the whole "where is it" story is one row rather than a labelled
 *       list of Address / Distance / Category rows. A denser pane is not more informative at
 *       70 km/h on the Ring Road, it is just a longer glance.</li>
 *   <li><b>Template quota.</b> The host caps a task at five templates, so a pane that costs a
 *       template has to be paid for out of a fixed budget. Search is
 *       Landing -> Search -> <b>PlaceDetails</b> -> RoutePreview, which is four. The POI-category
 *       flow is a screen deeper before the pane exists, so there the pane hands the destination
 *       back instead of stacking on top of route preview - see {@link Origin}.</li>
 * </ul>
 *
 * <h3>RTL</h3>
 * Every composed line goes through the {@code ltr_or_rtl_combine_*} string resources, the same way
 * {@link TripUtils#getNextTurnDescription} does, so the separator and the ordering of the two
 * halves are decided by the Arabic resource rather than by a {@code +} in Java. This app is driven
 * in Cairo with an Arabic map locale; string concatenation here produces a line that reads
 * backwards under RTL.
 *
 * <h3>Kill switch</h3>
 * {@code CAIRODRIVE_PLACE_DETAILS}, default true. {@code SESSION} records it, so a drive log says
 * whether the pane was compiled in. Setting it false restores the direct-to-preview path.
 */
public final class PlaceDetailsScreen extends BaseAndroidAutoScreen {

	/**
	 * Grep handle for the pane's trace, matching the {@code CD_SEARCH} convention in
	 * {@code GooglePlacesSearchApi} - one tag per subsystem so a drive log can be read with
	 * {@code grep} without knowing which class wrote what.
	 */
	private static final String TRACE_TAG = "CD_AUTO";

	/**
	 * The host renders at most this many secondary text lines per row while the car is moving.
	 * Not a soft limit - extra {@code addText} calls are dropped without warning, so a field
	 * added past it looks implemented and is invisible on the road.
	 */
	private static final int MAX_SECONDARY_LINES = 2;

	/**
	 * Stand-in character the {@link DistanceSpan} is attached to.
	 *
	 * <p>A {@code DistanceSpan} replaces the text it covers with the host's own distance
	 * rendering, so the string only has to reserve one character in the right place. It has to be
	 * a character the localized template cannot also contain, because the position is found with
	 * {@code indexOf} - a plain space would match the one the template puts around the separator.
	 * U+2007 FIGURE SPACE is invisible and appears nowhere in these resources.
	 */
	private static final String DISTANCE_PLACEHOLDER = "\u2007";

	/** Build-time kill switch: CAIRODRIVE_PLACE_DETAILS=false restores the direct-to-preview path. */
	private static final boolean PLACE_DETAILS_ENABLED = BuildConfig.CAIRODRIVE_PLACE_DETAILS;

	/**
	 * What the host allows a single task to spend, from the Car App Library's template
	 * restrictions: "The host limits the number of templates to display for a given task to a
	 * maximum of five", and "if the template quota is exhausted and the app attempts to send a new
	 * template, the host displays an error message to the user before closing the app."
	 *
	 * <p>Recorded here only so the {@code CD_AUTO} line can print the budget alongside the depth it
	 * measured. Nothing in {@code androidx.car.app} enforces it - {@code ScreenManager.push} has no
	 * size check at all, the stack is a plain {@code Deque} - so the first sign of getting this
	 * wrong is the head unit closing the app mid-drive, which is exactly the failure a log line is
	 * cheaper than.
	 */
	private static final int MAX_TEMPLATES_PER_TASK = 5;

	/**
	 * Which flow opened the pane, and therefore what Navigate has to do to stay inside
	 * {@link #MAX_TEMPLATES_PER_TASK}.
	 *
	 * <h3>The accounting</h3>
	 * The quota counts <b>templates sent in a task</b>, not screens; a back operation returns
	 * quota ("the host detects when an app is popping a Screen from the ScreenManager stack and
	 * updates the remaining quota based on the number of templates that the app is going backward
	 * by"), and a same-type refresh costs nothing. So the number that matters is the deepest point
	 * a flow reaches, not how many screens it visits.
	 *
	 * <p>Search reaches four: Landing, Search, PlaceDetails, RoutePreview. The POI flow already
	 * reaches four without a pane - Landing, POICategories, POIScreen, RoutePreview - so pushing
	 * the pane as a fifth is not merely "at the limit", it leaves the flow with no headroom, and
	 * route preview is not a leaf:
	 * <ul>
	 *   <li>{@code NavigationSession.showMissingMapsScreen} pushes {@code MissingMapsScreen} on
	 *       top of it, driven from {@code RoutePreviewScreen.updateRoute};</li>
	 *   <li>{@code NavigationSession.onRequestPrivateAccessRouting} pushes
	 *       {@code PrivateAccessScreen} on top of it - a routine prompt on Cairo side streets;</li>
	 *   <li>the settings action in every one of these action strips pushes {@code SettingsScreen},
	 *       which itself pushes {@code MapMagnifierScreen}.</li>
	 * </ul>
	 * Any of those is a sixth template, and a sixth template closes the app.
	 *
	 * <p>{@link Origin#POI_LIST} therefore hands back rather than stacking: Navigate calls
	 * {@code setResult} + {@code finish()}, the host sees a pop and returns the template, and
	 * {@code POIScreen} - which is still alive underneath - makes the same {@code openRoutePreview}
	 * call it made before B2. The flow's peak stays at four, identical to today, and the fifth slot
	 * stays free for the interstitials above.
	 *
	 * <p>{@code ScreenManager.remove(this)} after pushing preview would look equivalent and is not:
	 * removing a screen that is not on top skips {@code popInternal} entirely, so the host never
	 * sees a back operation and never returns the quota. The pop has to happen first.
	 */
	public enum Origin {
		/** The search screen's own result list. Depth 3 at the pane; preview makes 4. */
		SEARCH("search", false),
		/** The results screen reached from a submitted query. Same depth as {@link #SEARCH}. */
		SEARCH_RESULTS("searchResults", false),
		/** The POI-category drill-down. Depth 4 at the pane, so Navigate hands back. */
		POI_LIST("poiList", true);

		@NonNull
		private final String label;
		private final boolean handsBack;

		Origin(@NonNull String label, boolean handsBack) {
			this.label = label;
			this.handsBack = handsBack;
		}

		/** Short, stable token for the {@code CD_AUTO} line. */
		@NonNull
		public String getLabel() {
			return label;
		}

		/**
		 * Whether Navigate should return the destination to the screen below instead of pushing
		 * route preview itself.
		 */
		public boolean handsBackToCaller() {
			return handsBack;
		}
	}

	@NonNull
	private final Action settingsAction;
	@NonNull
	private final SearchResult searchResult;
	/**
	 * Named {@code flow}, not {@code origin}: {@link #resolveOrigin} and {@link #logOpened} already
	 * use "origin" for the {@link LatLon} distances are measured from, and a field by that name
	 * would be silently shadowed by that parameter inside the one method that needs both.
	 */
	@NonNull
	private final Origin flow;

	/**
	 * Which fallback produced the address and the measuring origin on the last build of the
	 * template, for the {@code CD_AUTO} line. Recorded rather than re-derived because both
	 * resolutions have several branches and a log that says "coords" is only useful if it is the
	 * branch that actually ran.
	 */
	@NonNull
	private String addressSource = "none";
	@NonNull
	private String originSource = "none";
	/** Guards {@link #logOpened} to one line per pane - see the call site. */
	private boolean logged;

	public PlaceDetailsScreen(@NonNull CarContext carContext, @NonNull Action settingsAction,
	                          @NonNull SearchResult searchResult, @NonNull Origin flow) {
		super(carContext);
		this.settingsAction = settingsAction;
		this.searchResult = searchResult;
		this.flow = flow;
		setMarker(PlaceDetailsScreen.class.getSimpleName());
	}

	/** Whether the pane is compiled in at all. */
	public static boolean isEnabled() {
		return PLACE_DETAILS_ENABLED;
	}

	/**
	 * Whether a tapped result should get the pane, or go straight to route preview as it did
	 * before B2.
	 *
	 * <p>Two exclusions, both deliberate:
	 * <ul>
	 *   <li>{@code GPX_TRACK} and {@code ROUTE} are not places. A track has no address, no
	 *       category and no single position, and {@code ROUTE} is the "resume previous route"
	 *       card on the search screen - putting a confirmation pane in front of a one-tap resume
	 *       is a regression, not a feature.</li>
	 *   <li>A result with no location cannot show the one thing the pane exists to show first -
	 *       distance and bearing - so it is not worth the extra screen against the quota.</li>
	 * </ul>
	 */
	public static boolean canShow(@NonNull SearchResult result) {
		if (!isEnabled() || result.location == null) {
			return false;
		}
		return result.objectType != ObjectType.GPX_TRACK && result.objectType != ObjectType.ROUTE;
	}

	@Override
	protected int getConstraintLimitType() {
		// A pane is not a list; the host allows far fewer rows here, and asking for the list
		// limit would let the deferred Places rows below overflow it.
		return ConstraintManager.CONTENT_LIMIT_TYPE_PANE;
	}

	@NonNull
	@Override
	public Template getTemplate() {
		OsmandApplication app = getApp();

		String name = resolveName(app);
		LatLon origin = resolveOrigin(app);
		String category = resolveCategory(app);
		String address = resolveAddress(app, name);

		Pane.Builder paneBuilder = new Pane.Builder();

		Row.Builder whereBuilder = new Row.Builder().setTitle(address);
		int lines = 0;
		CharSequence distanceLine = buildDistanceLine(app, origin);
		if (distanceLine != null) {
			whereBuilder.addText(distanceLine);
			lines++;
		}
		// Skipped when it only repeats the header - an unnamed POI falls back to its type for the
		// title, and a pane that says "Pharmacy / Pharmacy" spends a glance on nothing. Same
		// dedupe SearchHelper.buildSearchRow applies between a row's title and its description.
		if (!Algorithms.isEmpty(category) && !category.equals(name) && lines < MAX_SECONDARY_LINES) {
			whereBuilder.addText(category);
			lines++;
		}
		paneBuilder.addRow(whereBuilder.build());

		int extraRows = addDeferredPlacesRows(paneBuilder, 1);

		// Navigate is the only pane button. Back is the header's start action instead of a second
		// button: the host draws it where drivers already look for it, and a duplicate control
		// would spend part of the 2 s glance budget on something that is already on screen.
		paneBuilder.addAction(new Action.Builder()
				.setTitle(app.getString(R.string.auto_place_details_navigate))
				.setOnClickListener(this::onNavigate)
				.build());

		Header header = new Header.Builder()
				// The name is the header rather than a row title so it survives as the pane grows
				// - the deferred Places rows land underneath, and the row real estate is worth
				// more spent on facts the driver does not already know than on repeating the
				// label they just tapped.
				.setTitle(Algorithms.isEmpty(name) ? app.getString(R.string.shared_string_details) : name)
				.setStartHeaderAction(Action.BACK)
				.build();

		Template content = new PaneTemplate.Builder(paneBuilder.build())
				.setHeader(header)
				.build();

		// Once per pane, not once per template build. getTemplate() is a host callback and the
		// host may rebuild for reasons of its own; the question the log answers is "did the pane
		// have anything to show when the driver opened it", and repeating it would bury the
		// answer in a drive log that already runs to megabytes.
		if (!logged) {
			logged = true;
			logOpened(name, category, origin, lines, extraRows);
		}

		// Wrapped the same way RoutePreviewScreen wraps its pane. A bare PaneTemplate inside a
		// navigation session hides the map surface, and the surface tearing down and coming back
		// costs more than the pane saves. The camera is deliberately NOT moved here: adjustMapToRect
		// resets rotation and tilt and would have to be animated back on every dismissal, and on
		// this device the map redraw is the expensive part of a frame (CD_FRAME 'over').
		return new MapWithContentTemplate.Builder()
				.setActionStrip(new ActionStrip.Builder().addAction(settingsAction).build())
				.setContentTemplate(content)
				.build();
	}

	/**
	 * Exactly what tapping the result used to do before this screen existed - by two routes that
	 * differ only in which screen makes the call.
	 *
	 * <p>{@code openRoutePreview} is the shared path in {@link BaseAndroidAutoScreen}: it pushes
	 * {@code RoutePreviewScreen} for a result and, when the driver confirms, pops the whole task
	 * back to the root, starts navigation and finishes. Calling it from here rather than from the
	 * search screen changes nothing about that - {@code popToRoot} clears this screen and the
	 * search screen alike.
	 *
	 * <p>When the {@link Origin} hands back, this screen finishes first and the screen underneath
	 * makes that same call with that same result. The driver sees the identical route preview and
	 * the identical Start behaviour; the only difference is that Back from route preview returns to
	 * the POI list rather than to a pane the driver has already acted on, which is the better of
	 * the two anyway. What it buys is the template - see {@link Origin} for why the POI flow cannot
	 * spend it.
	 */
	private void onNavigate() {
		logNavigate();
		// The depth test is not decoration: ScreenManager.remove() returns silently when the stack
		// holds one screen, so a hand-back from a root pane would leave Navigate doing nothing at
		// all. That cannot happen from POIScreen, which is always pushed onto something - but
		// "nothing happens when the driver presses the only button" is a bad way to find out that
		// an unforeseen entry point exists.
		if (flow.handsBackToCaller() && stackDepth() > 1) {
			// setResult before finish: Screen.setResult only records the value, and the host
			// propagates it while the screen is being destroyed.
			setResult(searchResult);
			finish();
		} else {
			openRoutePreview(settingsAction, searchResult);
		}
	}

	/**
	 * Screens currently on the stack, which is one per template this task has outstanding.
	 *
	 * <p>{@code getScreenStack()} hands back a defensive copy, so this is a read with no way to
	 * disturb the stack - the same call {@code NavigationSession.isRoutePreviewPresent} makes.
	 */
	private int stackDepth() {
		return getScreenManager().getScreenStack().size();
	}

	@NonNull
	private String resolveName(@NonNull OsmandApplication app) {
		String name = QuickSearchListItem.getName(app, searchResult);
		if (Algorithms.isEmpty(name)) {
			name = QuickSearchListItem.getTypeName(app, searchResult);
		}
		return name == null ? "" : name;
	}

	@Nullable
	private String resolveCategory(@NonNull OsmandApplication app) {
		String typeName = QuickSearchListItem.getTypeName(app, searchResult);
		if (Algorithms.isEmpty(typeName)) {
			return null;
		}
		// Same trim SearchHelper.composeDescription applies: the type name can carry a trailing
		// comma-separated tail that is address material, and it is about to be shown as a
		// category on a line that has to be readable in a glance.
		int comma = typeName.indexOf(',');
		return comma > 0 ? typeName.substring(0, comma).trim() : typeName;
	}

	/**
	 * The row title: what the driver checks to confirm this is the right branch of the chain
	 * they searched for.
	 *
	 * <p>Cairo OSM data frequently has no {@code addr:*} at all, so this degrades rather than
	 * showing an empty row (a {@link Row} cannot have an empty title anyway): OSM address ->
	 * street name from the amenity -> the localized coordinate name OsmAnd uses everywhere else
	 * -> a plain "no address" string. The name is never reused as the address - it is already the
	 * header, and repeating it reads as a bug.
	 */
	@NonNull
	private String resolveAddress(@NonNull OsmandApplication app, @NonNull String name) {
		String address = searchResult.addressName;
		if (Algorithms.isEmpty(address) && searchResult.object instanceof Amenity amenity) {
			String street = amenity.getStreetName();
			String house = amenity.getHousenumber();
			if (!Algorithms.isEmpty(street) && !Algorithms.isEmpty(house)) {
				// Ordering left to the resource: "12 Al-Nozha St" and its Arabic equivalent do
				// not put the number on the same side.
				address = app.getString(R.string.ltr_or_rtl_combine_via_space, house, street);
			} else if (!Algorithms.isEmpty(street)) {
				address = street;
			}
		}
		if (!Algorithms.isEmpty(address) && !address.equals(name)) {
			addressSource = Algorithms.isEmpty(searchResult.addressName) ? "osmStreet" : "osmAddr";
			return address;
		}
		LatLon location = searchResult.location;
		if (location != null) {
			// replace('\n', ' ') because getLocationName returns a two-line form for the phone UI
			// and a newline inside a car row title is dropped by the host, not wrapped.
			String coordinates = PointDescription.getLocationName(app,
					location.getLatitude(), location.getLongitude(), true);
			if (!Algorithms.isEmpty(coordinates)) {
				addressSource = "coords";
				return coordinates.replace('\n', ' ');
			}
		}
		addressSource = "none";
		return app.getString(R.string.auto_place_details_no_address);
	}

	/**
	 * Where "distance and bearing from here" is measured from.
	 *
	 * <p>{@code getDefaultLocation()} is the app-wide answer to "where is the user" - live fix,
	 * then last stale fix, then the map centre - and it is what {@code POIScreen} measures from,
	 * so the pane agrees with the distance on the row the driver just tapped rather than inventing
	 * a second convention. Which of the three it fell back to is probed separately and recorded on
	 * the {@code CD_AUTO} line, because "the distance looked wrong" and "there was no GPS fix" are
	 * otherwise the same log entry.
	 */
	@NonNull
	private LatLon resolveOrigin(@NonNull OsmandApplication app) {
		if (app.getLocationProvider().getLastKnownLocation() != null) {
			originSource = "fix";
		} else if (app.getLocationProvider().getLastStaleKnownLocation() != null) {
			originSource = "staleFix";
		} else {
			originSource = "mapCentre";
		}
		return app.getMapViewTrackingUtilities().getDefaultLocation();
	}

	/**
	 * "1.2 km &bull; NE" - the first and most valuable of the two secondary lines.
	 *
	 * <p>The distance is a {@link DistanceSpan} rather than formatted text so the head unit
	 * renders it in its own units and typography, which is what the rest of this codebase does
	 * ({@code SearchHelper.buildSearchRow}, {@code POIScreen}). The bearing is a localized
	 * cardinal abbreviation, not degrees: a driver cannot act on "47&deg;" inside a 2 s glance,
	 * but "north-east" tells them whether the place is ahead or behind them.
	 */
	@Nullable
	private CharSequence buildDistanceLine(@NonNull OsmandApplication app, @NonNull LatLon origin) {
		LatLon location = searchResult.location;
		if (location == null) {
			return null;
		}
		double distance = MapUtils.getDistance(location, origin.getLatitude(), origin.getLongitude());
		String cardinal = OsmAndFormatter.getLocalizedCardinalDirection(app,
				bearingDegrees(origin, location));

		// Composed through the resource, not with '+': under RTL the separator and the order of
		// the two halves belong to the Arabic string, and a concatenation here renders reversed.
		String template = app.getString(R.string.ltr_or_rtl_combine_via_bold_point,
				DISTANCE_PLACEHOLDER, cardinal);
		int at = template.indexOf(DISTANCE_PLACEHOLDER);
		if (at < 0) {
			// A translation dropped the first argument. Show the direction alone rather than
			// throwing inside getTemplate.
			return cardinal;
		}
		SpannableString line = new SpannableString(template);
		line.setSpan(DistanceSpan.create(TripUtils.getDistance(app, distance)),
				at, at + 1, SPAN_INCLUSIVE_INCLUSIVE);
		return line;
	}

	/**
	 * Initial great-circle bearing from {@code from} to {@code to}, normalized to [0, 360).
	 *
	 * <p>Written out rather than calling {@code net.osmand.Location#bearingTo}, which caches its result
	 * inside the receiver: the receiver here would be the location provider's own live fix, and
	 * mutating it from a UI callback is the same hazard {@code CairoDriveLogger} documents for
	 * {@code distanceTo}. This is stateless and its accuracy is far beyond what an eight-point
	 * compass label can express.
	 */
	private static double bearingDegrees(@NonNull LatLon from, @NonNull LatLon to) {
		double lat1 = Math.toRadians(from.getLatitude());
		double lat2 = Math.toRadians(to.getLatitude());
		double deltaLon = Math.toRadians(to.getLongitude() - from.getLongitude());
		double y = Math.sin(deltaLon) * Math.cos(lat2);
		double x = Math.cos(lat1) * Math.sin(lat2) - Math.sin(lat1) * Math.cos(lat2) * Math.cos(deltaLon);
		double degrees = Math.toDegrees(Math.atan2(y, x));
		return (degrees + 360) % 360;
	}

	// -----------------------------------------------------------------------------------------
	// EXTENSION POINT - deferred Google Places fields. Nothing below calls the Places API.
	//
	// The pane exists so that hours / phone / rating / review count / photos have somewhere to
	// go. Each is a separate SKU and, more importantly, a separate build: CLAUDE.md records that
	// all of them went in at once before, the app was "buggy as hell", nothing could be
	// attributed, and the whole lot had to come out. One per build, each judged on a drive log.
	//
	// When adding one:
	//   1. Fetch OFF the main thread and render from cache. A GetPlace call on the click path
	//      shows up in CD_FRAME's 'over' bucket, and a photo shows up as a GC pause in maxMs.
	//      This method is called from getTemplate(), which is a host callback - it must not
	//      block. Kick the fetch off elsewhere, keep the result on the screen, invalidate().
	//   2. One row per field, and mind MAX_SECONDARY_LINES on each - the third addText is
	//      dropped silently by the host, so a field can look shipped and be invisible.
	//   3. Mind getContentLimit(): CONTENT_LIMIT_TYPE_PANE is small, and rows past it are
	//      rejected. That is the cap this method's return value is checked against.
	//   4. Compose every string through an ltr_or_rtl_combine_* resource. "Open until 22:00"
	//      concatenated in Java reads backwards in Arabic.
	//   5. Photos are Pane.setImage(CarIcon) - a large image is a bitmap over the binder on
	//      every template refresh, so cache the CarIcon, do not rebuild it per getTemplate().
	//   6. Raising the endpoint's daily quota in the Google console is part of shipping the
	//      feature; today Nearby Search is at 0 and the rest are capped low on purpose.
	//
	// Suggested row shapes, so the first one to land does not have to redesign the pane:
	//   hours   title = "Open until 22:00" / "Closed",   text = weekday line
	//   phone   title = the number,                      text = R.string.shared_string_call
	//   rating  title = "4.4",                           text = "(1,207 reviews)"
	// -----------------------------------------------------------------------------------------

	/**
	 * Adds the deferred Places rows. Returns how many were added - always 0 today.
	 *
	 * @param rowsSoFar rows already in the pane, so an implementation can respect
	 *                  {@link #getContentLimit()} without recounting.
	 */
	@SuppressWarnings("unused")
	private int addDeferredPlacesRows(@NonNull Pane.Builder paneBuilder, int rowsSoFar) {
		return 0;
	}

	/**
	 * One line per pane open, saying what it managed to populate.
	 *
	 * <p>Presence flags and lengths, not the text: the point is "did the pane have anything to
	 * show", and a drive log already carries a continuous position trace - it does not also need
	 * the name of every place the owner looked at. {@code addr=} is the source that won, because
	 * "coords" across a whole drive is the signal that Cairo address coverage, not this screen,
	 * is what needs work.
	 */
	private void logOpened(@NonNull String name, @Nullable String category,
	                       @NonNull LatLon origin, int lines, int extraRows) {
		LatLon location = searchResult.location;
		StringBuilder builder = new StringBuilder(192);
		builder.append("placeDetails open flow=").append(flow.getLabel())
				.append(" depth=").append(stackDepth()).append('/').append(MAX_TEMPLATES_PER_TASK)
				.append(" type=").append(searchResult.objectType)
				.append(" nameLen=").append(name.length())
				.append(" addr=").append(addressSource)
				.append(" cat=").append(Algorithms.isEmpty(category) ? 0 : 1)
				.append(" origin=").append(originSource);
		if (location != null) {
			builder.append(" distM=").append(Math.round(
							MapUtils.getDistance(location, origin.getLatitude(), origin.getLongitude())))
					.append(" bearingDeg=").append(Math.round(bearingDegrees(origin, location)));
		}
		builder.append(" lines=").append(lines).append('/').append(MAX_SECONDARY_LINES)
				.append(" placesRows=").append(extraRows)
				.append(" contentLimit=").append(getContentLimit());
		CairoDriveLogger.getInstance().log(TRACE_TAG, builder.toString());
	}

	/**
	 * One line when Navigate is pressed, saying which flow paid for the pane and how deep the task
	 * is about to get.
	 *
	 * <p>{@code peak} is the answer to the only question that can close the app: the depth once
	 * {@code RoutePreviewScreen} is on top. On the hand-back path this screen pops first, so the
	 * peak equals the depth measured here; on the push path it is one more. If a drive log ever
	 * shows {@code peak=5} the flow has no room left for {@code MissingMapsScreen},
	 * {@code PrivateAccessScreen} or {@code SettingsScreen}, and the next such push closes the app
	 * - which is a thing to read in a log rather than to discover on the Ring Road.
	 */
	private void logNavigate() {
		int depth = stackDepth();
		boolean handsBack = flow.handsBackToCaller();
		CairoDriveLogger.getInstance().log(TRACE_TAG,
				"placeDetails navigate flow=" + flow.getLabel()
						+ " mode=" + (handsBack ? "handBack" : "push")
						+ " depth=" + depth
						+ " peak=" + (handsBack ? depth : depth + 1)
						+ '/' + MAX_TEMPLATES_PER_TASK);
	}
}
