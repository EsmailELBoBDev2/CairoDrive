package com.cairodrive.app.auto

import io.flutter.plugin.common.MethodChannel

/**
 * Platform channel between the car UI and the Flutter search/navigation layer.
 *
 * Keeping the car screen a thin client of the Dart [SearchCoordinator] is what
 * stops provider policy (Google primary, engine fallback, debounce, session
 * tokens) being reimplemented — and drifting — in Kotlin.
 */
object CarSearchBridge {

    const val CHANNEL = "com.cairodrive.app/car"

    @Volatile
    private var channel: MethodChannel? = null

    /** Called from MainActivity once the Flutter engine is up. */
    fun attach(methodChannel: MethodChannel) {
        channel = methodChannel
    }

    fun detach() {
        channel = null
    }

    fun search(
        query: String,
        onResults: (List<CarSearchScreen.CarSearchResult>) -> Unit,
    ) {
        val active = channel
        if (active == null || query.isBlank()) {
            onResults(emptyList())
            return
        }
        active.invokeMethod("search", mapOf("query" to query), object : MethodChannel.Result {
            @Suppress("UNCHECKED_CAST")
            override fun success(result: Any?) {
                val rows = (result as? List<Map<String, Any?>>).orEmpty()
                onResults(
                    rows.mapNotNull { row ->
                        val lat = (row["latitude"] as? Number)?.toDouble()
                        val lng = (row["longitude"] as? Number)?.toDouble()
                        val title = row["title"] as? String
                        if (lat == null || lng == null || title == null) null
                        else CarSearchScreen.CarSearchResult(
                            title = title,
                            subtitle = row["subtitle"] as? String,
                            latitude = lat,
                            longitude = lng,
                        )
                    }
                )
            }

            override fun error(code: String, message: String?, details: Any?) {
                // The Dart side already fell back to the offline engine before
                // reporting an error, so there is genuinely nothing to show.
                onResults(emptyList())
            }

            override fun notImplemented() = onResults(emptyList())
        })
    }

    fun navigateTo(result: CarSearchScreen.CarSearchResult) {
        channel?.invokeMethod(
            "navigate",
            mapOf(
                "title" to result.title,
                "latitude" to result.latitude,
                "longitude" to result.longitude,
            ),
        )
    }
}
