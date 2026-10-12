package com.openmaps.app.map

/**
 * Where the basemap comes from. The Map screen and the engine depend only on this
 * abstraction, so moving to PMTiles / Protomaps / self-hosted vector tiles later is a
 * new [MapStyleProvider] implementation, not a Map screen rewrite.
 */
sealed interface MapStyleSource {
    /** Human-readable provider name (shown in Settings). */
    val name: String

    /** True when the source must not be relied on for production traffic. */
    val isDevelopmentOnly: Boolean

    /** Attribution text required by the data/tile license. */
    val attribution: String

    /** A MapLibre style JSON served over HTTP(S). */
    data class Url(
        val url: String,
        override val name: String,
        override val isDevelopmentOnly: Boolean,
        override val attribution: String = OSM_ATTRIBUTION,
    ) : MapStyleSource

    /**
     * A style document supplied inline (e.g. generated at runtime or bundled in assets).
     * This is the hook for a self-hosted / PMTiles-backed style: build the JSON with the
     * right source entry and hand it over here.
     */
    data class InlineJson(
        val json: String,
        override val name: String,
        override val isDevelopmentOnly: Boolean,
        override val attribution: String = OSM_ATTRIBUTION,
    ) : MapStyleSource

    companion object {
        const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"
    }
}

fun interface MapStyleProvider {
    fun resolve(): MapStyleSource
}
