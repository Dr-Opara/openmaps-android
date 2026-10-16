package com.openmaps.app.core.routing

/** A plain lat/lon pair, independent of any provider's location/search models. */
data class Coordinate(val latitude: Double, val longitude: Double)

/** Axis-aligned bounds a route's geometry fits inside, for one-time camera fitting. */
data class RouteBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
)

/**
 * One selectable route option returned alongside the primary route.
 *
 * Alternatives carry full navigation geometry/maneuvers so selecting one never requires a second
 * provider request. [label] is provider-backed (for example "via I-10") when available.
 */
data class RouteAlternative(
    val id: String?,
    val label: String?,
    val geometry: List<Coordinate>,
    val distanceMeters: Double,
    val durationSeconds: Double,
    val bounds: RouteBounds? = null,
    val maneuvers: List<RouteManeuver> = emptyList(),
    val baseDurationSeconds: Double? = null,
    val typicalDurationSeconds: Double? = null,
    val trafficDelaySeconds: Double? = null,
    val isTrafficAware: Boolean = false,
) {
    fun asRouteResult(otherAlternatives: List<RouteAlternative> = emptyList()): RouteResult =
        RouteResult(
            id = id,
            label = label,
            geometry = geometry,
            distanceMeters = distanceMeters,
            durationSeconds = durationSeconds,
            bounds = bounds,
            maneuvers = maneuvers,
            baseDurationSeconds = baseDurationSeconds,
            typicalDurationSeconds = typicalDurationSeconds,
            trafficDelaySeconds = trafficDelaySeconds,
            isTrafficAware = isTrafficAware,
            alternatives = otherAlternatives,
        )
}

/** A provider-neutral route. No HERE/Valhalla types leak past this. */
data class RouteResult(
    val geometry: List<Coordinate>,
    val distanceMeters: Double,
    /**
     * Best current ETA duration. For a production traffic-aware provider this includes live and
     * historical traffic; fallback providers may provide a non-traffic-aware estimate.
     */
    val durationSeconds: Double,
    val bounds: RouteBounds? = null,
    val maneuvers: List<RouteManeuver> = emptyList(),
    /** Provider route identifier, useful for diagnostics only. */
    val id: String? = null,
    /** Provider-backed differentiating label, e.g. "via I-10". */
    val label: String? = null,
    /** Free-flow duration when the provider supplies it. */
    val baseDurationSeconds: Double? = null,
    /** Historical "typical for this time" duration when supplied by the provider. */
    val typicalDurationSeconds: Double? = null,
    /** Live traffic delay over free-flow, never fabricated when unavailable. */
    val trafficDelaySeconds: Double? = null,
    /** True only when the provider explicitly supplied traffic-aware timing. */
    val isTrafficAware: Boolean = false,
    /** Selectable provider-returned alternatives, already normalized and fully navigable. */
    val alternatives: List<RouteAlternative> = emptyList(),
)

/**
 * Why a route request failed, classified so the UI layer never has to inspect raw
 * [java.io.IOException]/HTTP details. Mirrors [com.openmaps.app.core.geocoding.GeocodingException]'s shape.
 */
sealed class RoutingException(message: String) : Exception(message) {
    object NoNetwork : RoutingException("No network connection")
    object Timeout : RoutingException("The request timed out")
    class Server(val code: Int) : RoutingException("Server error ($code)")
    object Malformed : RoutingException("Malformed response")
    object NoRouteFound : RoutingException("No route found")
}
