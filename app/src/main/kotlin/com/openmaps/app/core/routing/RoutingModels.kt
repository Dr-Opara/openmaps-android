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
    val durationSeconds: Double,
    val bounds: RouteBounds? = null,
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
