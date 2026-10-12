package com.openmaps.app.core.routing

import org.json.JSONObject

/**
 * Parses a Valhalla `/route` response body. Pure and Android-free (only `org.json`), so it is
 * unit-testable without a real HTTP call. Throws [org.json.JSONException] on anything that is
 * not the expected shape; throws [RoutingException.NoRouteFound] when Valhalla's own `status`
 * says it could not find a path. Callers translate [org.json.JSONException] into
 * [RoutingException.Malformed].
 */
internal object ValhallaResponseParser {

    fun parse(body: String): RouteResult {
        val trip = JSONObject(body).getJSONObject("trip")
        if (trip.optInt("status", -1) != 0) throw RoutingException.NoRouteFound

        val legs = trip.getJSONArray("legs")
        val geometry = buildList {
            for (i in 0 until legs.length()) {
                addAll(PolylineDecoder.decode(legs.getJSONObject(i).getString("shape")))
            }
        }
        if (geometry.isEmpty()) throw RoutingException.NoRouteFound

        val summary = trip.getJSONObject("summary")
        return RouteResult(
            geometry = geometry,
            distanceMeters = summary.getDouble("length") * METERS_PER_KILOMETER,
            durationSeconds = summary.getDouble("time"),
            bounds = summary.toBoundsOrNull(),
        )
    }

    /** `min_lat`/`min_lon`/`max_lat`/`max_lon` are present on a successful response but kept optional to match the domain model. */
    private fun JSONObject.toBoundsOrNull(): RouteBounds? {
        if (!has("min_lat") || !has("min_lon") || !has("max_lat") || !has("max_lon")) return null
        return RouteBounds(
            south = getDouble("min_lat"),
            west = getDouble("min_lon"),
            north = getDouble("max_lat"),
            east = getDouble("max_lon"),
        )
    }

    private const val METERS_PER_KILOMETER = 1000.0
}
