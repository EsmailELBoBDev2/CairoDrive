package net.osmand.plus.auto.screens

import android.text.SpannableString
import android.text.Spanned
import androidx.appcompat.content.res.AppCompatResources
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.CarLocation
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.PlaceListNavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.LifecycleObserver
import androidx.lifecycle.LifecycleOwner
import net.osmand.data.Amenity
import net.osmand.data.LatLon
import net.osmand.data.QuadRect
import net.osmand.plus.R
import net.osmand.plus.auto.TripUtils
import net.osmand.plus.cairodrive.search.GooglePlaceTypes
import net.osmand.plus.cairodrive.search.GooglePlacesDetailsApi
import net.osmand.plus.search.listitems.QuickSearchListItem
import net.osmand.plus.settings.enums.CompassMode
import net.osmand.plus.utils.AndroidUtils
import net.osmand.plus.views.layers.base.OsmandMapLayer
import net.osmand.search.core.ObjectType
import net.osmand.search.core.SearchCoreFactory
import net.osmand.search.core.SearchPhrase
import net.osmand.search.core.SearchResult
import net.osmand.util.Algorithms
import net.osmand.util.MapUtils
import net.osmand.util.OpeningHoursParser

class POIScreen(
    carContext: CarContext,
    private val settingsAction: Action,
    private val categoryResult: SearchResult
) : BaseSearchScreen(carContext), LifecycleObserver {
    private lateinit var itemList: ItemList
    private var searchRadius = 0.0
    private var initialCompassMode: CompassMode? = null

    init {
        lifecycle.addObserver(this)
    }

    override fun shouldRestoreMapState() = true

    override fun onFirstGetTemplate() {
        super.onFirstGetTemplate()
        loadPOI()
    }

    override fun getTemplate(): Template {
        val templateBuilder = PlaceListNavigationTemplate.Builder()
        if (loading) {
            templateBuilder.setLoading(true)
        } else {
            templateBuilder.setLoading(false)
            templateBuilder.setItemList(withNearby(itemList))
        }
        var title = QuickSearchListItem.getName(app, categoryResult)
        if (Algorithms.isEmpty(title)) {
            title = QuickSearchListItem.getTypeName(app, categoryResult)
        }
        return templateBuilder
            .setTitle(title)
            .setActionStrip(ActionStrip.Builder()
                .addAction(createSearchAction())
                .build())
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun withNoResults(builder: ItemList.Builder): ItemList.Builder {
        return builder.setNoItemsMessage(carContext.getString(R.string.no_poi_for_category))
    }

    /**
     * The offline list with Google's nearby results appended.
     *
     * Appended, never merged or reordered: the offline entries are the ones that work with no
     * signal and that the map layer is already showing pins for, so they stay where they are and
     * in the order the index produced. A result with no coordinates is dropped rather than shown -
     * it cannot be routed to, and a row that does nothing when tapped is worse than no row.
     *
     * The whole list is still capped by [contentLimit]; the host rejects a template whose list
     * exceeds it, which would blank the screen rather than truncate it.
     */
    private fun withNearby(base: ItemList): ItemList {
        val extra = nearbyResults
        val ev = evStations()
        if (extra.isEmpty() && ev.isEmpty()) {
            return base
        }
        val builder = ItemList.Builder()
        var count = 0
        for (item in base.items) {
            if (count >= contentLimit) break
            builder.addItem(item)
            count++
        }
        val location = app.mapViewTrackingUtilities.defaultLocation
        for (station in ev) {
            if (count >= contentLimit) break
            val rowBuilder = Row.Builder().setTitle(station.name ?: continue)
            // Membership first, because 65% of Egyptian sites require it and a driver who arrives
            // at a charger they cannot use has wasted the range that got them there.
            val note = StringBuilder()
            if (station.membershipRequired) {
                note.append(carContext.getString(R.string.cairodrive_ev_membership))
            }
            if (station.maxPowerKw > 0) {
                if (note.isNotEmpty()) note.append(" • ")
                note.append("${station.maxPowerKw.toInt()} kW")
            }
            if (!Algorithms.isEmpty(station.operator)) {
                if (note.isNotEmpty()) note.append(" • ")
                note.append(station.operator)
            }
            // A charger with no operator, no power figure and open access would otherwise be a
            // bare title indistinguishable from an OSM POI row. Say what it is.
            if (note.isEmpty()) note.append(carContext.getString(R.string.cairodrive_ev_charging))
            rowBuilder.addText(note.toString())
            rowBuilder.setMetadata(
                Metadata.Builder().setPlace(
                    Place.Builder(
                        CarLocation.create(
                            station.location.latitude,
                            station.location.longitude)).build()).build())
            rowBuilder.setOnClickListener {
                openRoutePreview(settingsAction, asEvResult(station))
            }
            builder.addItem(rowBuilder.build())
            count++
        }
        for (place in extra) {
            if (count >= contentLimit) break
            val where = place.location ?: continue
            val name = place.name ?: continue
            val rowBuilder = Row.Builder().setTitle(name)
            if (location != null) {
                val dist = MapUtils.getDistance(
                    where.latitude, where.longitude, location.latitude, location.longitude)
                val address = SpannableString(" ")
                address.setSpan(
                    DistanceSpan.create(TripUtils.getDistance(app, dist)), 0, 1,
                    Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                rowBuilder.addText(address)
            } else if (!Algorithms.isEmpty(place.address)) {
                rowBuilder.addText(place.address)
            }
            rowBuilder.setMetadata(
                Metadata.Builder().setPlace(
                    Place.Builder(
                        CarLocation.create(where.latitude, where.longitude)).build()).build())
            rowBuilder.setOnClickListener { openRoutePreview(settingsAction, asGoogleResult(place, where)) }
            builder.addItem(rowBuilder.build())
            count++
        }
        return builder.build()
    }

    /**
     * A Google nearby hit as the plain destination the rest of the car flow consumes.
     *
     * [categoryResult]'s phrase is carried over for the same reason [asDestination] carries it:
     * `SearchResult()` installs an empty phrase whose `settings` is null, and the POI branch of
     * `QuickSearchListItem.getName` dereferences it without a null check.
     *
     * Straight to route preview rather than through the detail pane, unlike a tapped offline POI.
     * This flow is already Landing -> POICategories -> POIScreen, and the host caps a task at five
     * templates - the pane would make route preview the fifth with nothing left for
     * MissingMapsScreen or PrivateAccessScreen, which is how a task runs out of quota and the host
     * closes the app. See [PlaceDetailsScreen.Origin].
     */
    private fun asGoogleResult(
        place: GooglePlacesDetailsApi.PlaceDetails, where: LatLon): SearchResult {
        val result = SearchResult(categoryResult.requiredSearchPhrase)
        result.location = LatLon(where.latitude, where.longitude)
        result.objectType = ObjectType.LOCATION
        result.localeName = place.name
        result.addressName = place.address
        result.preferredZoom = categoryResult.preferredZoom
        return result
    }

    override fun onClickSearchMore() {
        invalidate()
    }

    override fun onSearchDone(
        phrase: SearchPhrase,
        searchResults: List<SearchResult>?,
        itemList: ItemList?,
        resultsCount: Int) {
        if(resultsCount < contentLimit && searchRadius < SearchCoreFactory.MAX_DEFAULT_SEARCH_RADIUS) {
            searchRadius++
            loadPOI()
        } else {
            loading = false
            if (resultsCount == 0) {
                this.itemList = withNoResults(ItemList.Builder()).build()
            } else {
                val builder = ItemList.Builder()
                setupPOI(builder, searchResults)
                this.itemList = builder.build()
            }
            // Google Nearby, but ONLY when the offline index came up thin. The .obf answers
            // instantly, offline, and is the right answer for a category like "petrol" in a city
            // it maps well; paying a SearchNearby call to append to a full list would be spending
            // the most restricted quota in the account to duplicate what is already on screen.
            maybeRequestNearby(resultsCount)
            invalidate()
        }
    }

    /**
     * The bundled Egypt EV dataset, for the charging-station category only.
     *
     * Costs nothing and cannot fail: no key, no quota, no network, 485 records read from the APK.
     * So unlike [maybeRequestNearby] there is no thin-results gate - there is nothing to spend and
     * nothing to save by withholding it. OSM's own charging-station coverage in Egypt is patchy
     * enough that this is usually the majority of what the driver sees.
     *
     * Deduped against the offline results by distance: OCM and OSM both know about the big
     * operators' sites, and showing Infinity EV twice is worse than showing it once.
     */
    private fun evStations(): List<net.osmand.plus.cairodrive.providers.EvChargingBundle.Station> {
        if (!net.osmand.plus.cairodrive.providers.EvChargingBundle.isEnabled()) {
            return emptyList()
        }
        val types = GooglePlaceTypes.forCategory(categoryResult, app)
        if (!types.contains("electric_vehicle_charging_station")) {
            return emptyList()
        }
        val here = app.mapViewTrackingUtilities.defaultLocation ?: return emptyList()
        return net.osmand.plus.cairodrive.providers.EvChargingBundle.nearest(
            app, LatLon(here.latitude, here.longitude), EV_MAX_RESULTS, EV_RADIUS_M)
    }

    /**
     * Fills a thin category result from Google, once per screen.
     *
     * The gate is the offline result COUNT, not the category: this is about coverage, and Cairo's
     * .obf covers some categories well and others barely at all. Below [NEARBY_THIN_RESULTS] the
     * driver is looking at a list that does not answer their question, which is the only situation
     * where a billed call buys anything.
     *
     * Note the quota reality recorded in CLAUDE.md: SearchNearby is deliberately capped at 0/day
     * in the console until the owner raises it. Until then this logs a refusal and changes
     * nothing on screen - which is the correct behaviour for a feature whose quota is the switch.
     */
    private fun maybeRequestNearby(offlineCount: Int) {
        if (nearbyRequested
            || offlineCount >= NEARBY_THIN_RESULTS
            || !GooglePlacesDetailsApi.nearbyEnabled()) {
            return
        }
        val types = GooglePlaceTypes.forCategory(categoryResult, app)
        if (types.isEmpty()) {
            return
        }
        nearbyRequested = true
        val around = app.mapViewTrackingUtilities.defaultLocation ?: return
        val centre = LatLon(around.latitude, around.longitude)
        Thread({
            val found = GooglePlacesDetailsApi(app)
                .nearby(centre, types, NEARBY_MAX_RESULTS, NEARBY_RADIUS_M)
            carContext.mainExecutor.execute {
                if (found.isNotEmpty()) {
                    nearbyResults = found
                    invalidate()
                }
            }
        }, "cd-nearby").start()
    }

    private var nearbyRequested = false
    private var nearbyResults: List<GooglePlacesDetailsApi.PlaceDetails> = emptyList()

    /** A bundled EV station as a routable destination. Same phrase-carrying rule as [asDestination]. */
    private fun asEvResult(
        station: net.osmand.plus.cairodrive.providers.EvChargingBundle.Station): SearchResult {
        val result = SearchResult(categoryResult.requiredSearchPhrase)
        result.location = LatLon(station.location.latitude, station.location.longitude)
        result.objectType = ObjectType.LOCATION
        result.localeName = station.name
        result.addressName = station.address
        result.preferredZoom = categoryResult.preferredZoom
        return result
    }

    private companion object {
        /** At or above this many offline hits, the list already answers the question. */
        const val NEARBY_THIN_RESULTS = 3
        const val NEARBY_MAX_RESULTS = 10
        const val NEARBY_RADIUS_M = 5000.0
        const val EV_MAX_RESULTS = 8
        /** Wider than the POI radius: chargers are sparse and worth a longer detour than a cafe. */
        const val EV_RADIUS_M = 25000.0
    }

    private fun setupPOI(listBuilder: ItemList.Builder, searchResults: List<SearchResult>?) {
        val location = app.mapViewTrackingUtilities.defaultLocation
        val mapPoint = ArrayList<Amenity>()
        val mapRect = QuadRect()
        searchResults?.let {
            var counter = 0
            for (point in searchResults) {
                if (point.location == null) {
                    continue
                }
                if (counter >= contentLimit) {
                    break
                }
                var description = ""
                var openHour = ""
                if (point.`object` is Amenity) {
                    val amenity = point.`object` as Amenity
                    mapPoint.add(amenity)
                    val latLon = amenity.location
                    Algorithms.extendRectToContainPoint(mapRect, latLon.longitude, latLon.latitude)
                    val openHourInfo = OpeningHoursParser.getInfo(amenity.openingHours)
                    if(openHourInfo != null && openHourInfo.isNotEmpty()) {
                        openHour = " • ${openHourInfo[0].shortInfo}"
                    }
                    if(!Algorithms.isEmpty(amenity.streetName)) {
                        description = " • ${amenity.streetName}"
                    }
                }
                val title = point.localeName
                var groupIcon = QuickSearchListItem.getIcon(app, point)
                if (groupIcon == null) {
                    groupIcon = AppCompatResources.getDrawable(app, R.drawable.mx_special_custom_category)
                }
                val icon = if (groupIcon != null) CarIcon.Builder(
                    IconCompat.createWithBitmap(AndroidUtils.drawableToBitmap(groupIcon)))
                    .build() else null
                val dist = MapUtils.getDistance(
                    point.location.latitude, point.location.longitude,
                    location.latitude, location.longitude)
                val address =
                    SpannableString(" $openHour$description")
                val distanceSpan = DistanceSpan.create(TripUtils.getDistance(app, dist))
                address.setSpan(distanceSpan, 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
                val rowBuilder = Row.Builder()
                    .setTitle(title)
                    .addText(address)
                    .setOnClickListener { onClickSearchResult(point) }
                    .setMetadata(
                        Metadata.Builder().setPlace(
                            Place.Builder(
                                CarLocation.create(
                                    point.location.latitude,
                                    point.location.longitude)).build()).build())
                icon?.let { rowBuilder.setImage(it) }
                listBuilder.addItem(rowBuilder.build())
                counter++
            }
            if (counter > 0) {
                initialCompassMode = app.settings.compassMode
                app.mapViewTrackingUtilities.switchCompassModeTo(CompassMode.NORTH_IS_UP)
            }
        }
        adjustMapToRect(location, mapRect)
        app.osmandMap.mapLayers.poiMapLayer.setCustomMapObjects(mapPoint)
    }

    private fun loadPOI() {
        categoryResult.priorityDistance = searchRadius
        searchHelper?.completeQueryWithObject(categoryResult)
        loading = true
    }

    /**
     * Rebuilds the tapped result as the plain POI destination the rest of the car flow consumes.
     *
     * The phrase is carried over deliberately, and it is not optional. `SearchResult()` installs
     * `SearchPhrase.emptyPhrase()`, whose `settings` field is null, and the POI branch of
     * `QuickSearchListItem.getName` reads `requiredSearchPhrase.getSettings().getLang()` with no
     * null check - so a POI result built with the no-arg constructor throws
     * `NullPointerException` the moment anything asks it for its name. Every result reaching this
     * screen comes from `SearchUICore` via `SearchHelper.runSearch`, so it always carries a phrase
     * with real settings; the fix is to keep it rather than to guard the reader.
     *
     * The locale-resolved names and the OSM address ride along for the same reason: they are what
     * `getName`, `getTypeName` and the detail pane's address row are built from, and dropping them
     * made the destination anonymous once it left this screen.
     */
    private fun asDestination(point: SearchResult): SearchResult {
        val result = SearchResult(point.requiredSearchPhrase)
        result.location = LatLon(point.location.latitude, point.location.longitude)
        result.objectType = ObjectType.POI
        result.`object` = point.`object`
        result.file = point.file
        result.preferredZoom = point.preferredZoom
        result.localeName = point.localeName
        result.alternateName = point.alternateName
        result.otherNames = point.otherNames
        result.addressName = point.addressName
        result.cityName = point.cityName
        result.relatedObject = point.relatedObject
        result.localeRelatedObjectName = point.localeRelatedObjectName
        return result
    }

    /**
     * B2 - a tapped POI gets the detail pane, the same as a tapped search result.
     *
     * Unlike the search flow the pane does NOT stay under route preview here: this task is one
     * screen deeper already (Landing -> POICategories -> POIScreen), and the head unit caps a task
     * at five templates. The pane therefore hands the destination back with `setResult` /
     * `finish()` - a back operation, which returns the template to the host's quota - and this
     * screen makes the same `openRoutePreview` call it made before B2. See
     * [PlaceDetailsScreen.Origin] for the full accounting.
     */
    override fun onClickSearchResult(point: SearchResult) {
        val result = asDestination(point)
        if (PlaceDetailsScreen.canShow(result)) {
            screenManager.pushForResult(
                PlaceDetailsScreen(
                    carContext, settingsAction, result, PlaceDetailsScreen.Origin.POI_LIST)
            ) { obj: Any? ->
                (obj as? SearchResult)?.let { openRoutePreview(settingsAction, it) }
            }
        } else {
            openRoutePreview(settingsAction, result)
        }
    }

	override fun onDestroy(owner: LifecycleOwner) {
		super.onDestroy(owner)
		app.osmandMap.mapLayers.poiMapLayer.setCustomMapObjects(null)
		app.osmandMap.mapLayers.poiMapLayer.customObjectsDelegate = null
		app.osmandMap.mapView.backToLocation()
		initialCompassMode?.let {
			app.mapViewTrackingUtilities.switchCompassModeTo(it)
		}
	}

	override fun onCreate(owner: LifecycleOwner) {
		super.onCreate(owner)
		app.osmandMap.mapLayers.poiMapLayer.customObjectsDelegate = OsmandMapLayer.CustomMapObjects()
	}
}