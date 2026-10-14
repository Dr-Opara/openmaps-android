package com.openmaps.app.core.roadintelligence

/** Unit a [SpeedLimitInfo.value] (or a current-speed reading) is expressed in. */
enum class SpeedUnit {
    Mph,
    Kmh,
}

/**
 * A provider-neutral speed limit (Phase 10). [source] names where the value came from (e.g.
 * `"osm:maxspeed"`) so a details surface could show provenance without this type needing a closed
 * set of known providers.
 *
 * There is deliberately no "inferred" constructor path anywhere in this package: per section 7 of
 * the Phase 10 brief, a [SpeedLimitInfo] is only ever created from a value a real data source
 * actually stated. [confidence], when a provider supplies one, is carried through rather than
 * invented — most providers (including OSM `maxspeed`) don't have one, hence the default null.
 */
data class SpeedLimitInfo(
    val value: Double,
    val unit: SpeedUnit,
    val source: String,
    val confidence: Float? = null,
)
