package com.openmaps.app.core.routing

import org.json.JSONArray
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
        val geometry = mutableListOf<Coordinate>()
        val maneuvers = mutableListOf<RouteManeuver>()
        for (i in 0 until legs.length()) {
            val leg = legs.getJSONObject(i)
            // Valhalla's begin/end_shape_index on each maneuver is relative to that leg's own
            // shape array, but geometry is concatenated across legs below, so later legs' indexes
            // need to be offset by every prior leg's point count to stay valid against `geometry`.
            val shapeOffset = geometry.size
            geometry.addAll(PolylineDecoder.decode(leg.getString("shape")))
            leg.optJSONArray("maneuvers")?.let { maneuvers.addAll(it.toRouteManeuvers(shapeOffset)) }
        }
        if (geometry.isEmpty()) throw RoutingException.NoRouteFound

        val summary = trip.getJSONObject("summary")
        return RouteResult(
            geometry = geometry,
            distanceMeters = summary.getDouble("length") * METERS_PER_KILOMETER,
            durationSeconds = summary.getDouble("time"),
            bounds = summary.toBoundsOrNull(),
            maneuvers = maneuvers,
        )
    }

    private fun JSONArray.toRouteManeuvers(shapeOffset: Int): List<RouteManeuver> =
        buildList {
            for (i in 0 until length()) {
                val maneuver = getJSONObject(i)
                add(
                    RouteManeuver(
                        instruction = maneuver.getString("instruction"),
                        verbalInstruction = maneuver.optStringOrNull("verbal_pre_transition_instruction"),
                        type = maneuver.optInt("type", -1).toManeuverType(),
                        streetName = maneuver.optJSONArray("street_names")?.joinToStringOrNull(),
                        distanceMeters = maneuver.optDouble("length", 0.0) * METERS_PER_KILOMETER,
                        beginShapeIndex = shapeOffset + maneuver.getInt("begin_shape_index"),
                        endShapeIndex = shapeOffset + maneuver.getInt("end_shape_index"),
                    ),
                )
            }
        }

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name)) getString(name) else null

    private fun JSONArray.joinToStringOrNull(): String? {
        if (length() == 0) return null
        return (0 until length()).joinToString("/") { getString(it) }
    }

    /**
     * Maps Valhalla's `DirectionsLeg_Maneuver_Type` (0-45) to our provider-neutral
     * [ManeuverType]. Authoritative source:
     * https://github.com/valhalla/valhalla/blob/master/proto/descriptors/directions.proto
     * Verified against a live captured response (kStartLeft=3 for the first maneuver of a
     * route, kDestination=4 for "You have arrived at your destination.").
     */
    private fun Int.toManeuverType(): ManeuverType = when (this) {
        1, 2, 3 -> ManeuverType.Start // kStart, kStartRight, kStartLeft
        4, 5, 6 -> ManeuverType.Destination // kDestination, kDestinationRight, kDestinationLeft
        7, 8, 22 -> ManeuverType.Continue // kBecomes, kContinue, kStayStraight
        9, 23 -> ManeuverType.SlightRight // kSlightRight, kStayRight
        10 -> ManeuverType.TurnRight // kRight
        11 -> ManeuverType.SharpRight // kSharpRight
        12, 13 -> ManeuverType.UTurn // kUturnRight, kUturnLeft
        14 -> ManeuverType.SharpLeft // kSharpLeft
        15 -> ManeuverType.TurnLeft // kLeft
        16, 24 -> ManeuverType.SlightLeft // kSlightLeft, kStayLeft
        17, 18, 19, 20, 21 -> ManeuverType.Ramp // kRampStraight/Right/Left, kExitRight, kExitLeft
        25, 37, 38 -> ManeuverType.Merge // kMerge, kMergeRight, kMergeLeft
        26, 27 -> ManeuverType.Roundabout // kRoundaboutEnter, kRoundaboutExit
        28, 29 -> ManeuverType.Continue // kFerryEnter, kFerryExit
        45 -> ManeuverType.Destination // kParkVehicle
        else -> ManeuverType.Unknown // kNone and transit/pedestrian-only types (auto costing won't produce these)
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
