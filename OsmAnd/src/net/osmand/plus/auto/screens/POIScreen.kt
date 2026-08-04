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
            templateBuilder.setItemList(itemList)
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
            invalidate()
        }
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