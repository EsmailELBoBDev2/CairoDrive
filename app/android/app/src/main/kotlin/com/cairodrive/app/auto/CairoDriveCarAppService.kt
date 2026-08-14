package com.cairodrive.app.auto

import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator

/**
 * CairoDrive's Android Auto entry point.
 *
 * Two things this deliberately does NOT do, per the project brief:
 *  - it has no dependency on any third-party entitlement or subscription check;
 *    Android Auto here is gated only by the host validator, and
 *  - it carries no code ported from the proprietary artifact — the declarations
 *    mirror what the recon report documented, rebuilt against the public
 *    AndroidX Car App Library.
 */
class CairoDriveCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            // Debug builds accept the Desktop Head Unit so the flow can be
            // exercised without a car.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = CairoDriveSession()
}

class CairoDriveSession : Session() {
    override fun onCreateScreen(intent: android.content.Intent): Screen =
        CarSearchScreen(carContext)
}

/**
 * Search on the head unit.
 *
 * The car UI intentionally goes through the same conceptual flow as the phone —
 * query in, results out, tap to route — so behaviour stays consistent. Results
 * are supplied by the Flutter layer over a platform channel, which keeps the
 * single [SearchCoordinator] (Google primary, engine fallback) as the one place
 * search policy lives, rather than duplicating provider logic in Kotlin.
 */
class CarSearchScreen(carContext: CarContext) : Screen(carContext) {

    private var results: List<CarSearchResult> = emptyList()
    private var loading = false

    data class CarSearchResult(
        val title: String,
        val subtitle: String?,
        val latitude: Double,
        val longitude: Double,
    )

    override fun onGetTemplate(): Template {
        val itemListBuilder = ItemList.Builder()
            .setNoItemsMessage("No places found")

        results.forEach { result ->
            itemListBuilder.addItem(
                Row.Builder()
                    .setTitle(result.title)
                    .apply { result.subtitle?.let { addText(it) } }
                    .setOnClickListener { onResultSelected(result) }
                    .build()
            )
        }

        return SearchTemplate.Builder(object : SearchTemplate.SearchCallback {
            override fun onSearchTextChanged(searchText: String) {
                requestSearch(searchText)
            }

            override fun onSearchSubmitted(searchText: String) {
                requestSearch(searchText)
            }
        })
            .setHeaderAction(Action.BACK)
            .setShowKeyboardByDefault(false)
            .setSearchHint("Search places in Cairo")
            .setItemList(itemListBuilder.build())
            .setLoading(loading && results.isEmpty())
            .build()
    }

    private fun requestSearch(query: String) {
        // Bridged to the Flutter SearchCoordinator; debounce and cancellation
        // are handled there so the car and phone share one policy.
        loading = query.isNotBlank()
        CarSearchBridge.search(query) { incoming ->
            results = incoming
            loading = false
            invalidate()
        }
        invalidate()
    }

    private fun onResultSelected(result: CarSearchResult) {
        // Routing and guidance are the engine's, reached through the same
        // destination handoff the phone UI uses.
        CarSearchBridge.navigateTo(result)
    }
}
