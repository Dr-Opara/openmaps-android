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

/** A provider-neutral route. No Valhalla (or any provider) types leak past this. */
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
    /** Free-flow duration when the provider supplies it. */
    val baseDurationSeconds: Double? = null,
    /** Historical "typical for this time" duration when supplied by the provider. */
    val typicalDurationSeconds: Double? = null,
    /** Live traffic delay over free-flow, never fabricated when unavailable. */
    val trafficDelaySeconds: Double? = null,
    /** True only when the provider explicitly supplied traffic-aware timing. */
    val isTrafficAware: Boolean = false,
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
