package com.openmaps.app.core.routing

/**
 * Provider-neutral maneuver type. Mapped from Valhalla's `DirectionsLeg_Maneuver_Type` (see
 * [ValhallaResponseParser] for the authoritative 0-45 mapping, sourced from
 * https://github.com/valhalla/valhalla/blob/master/proto/descriptors/directions.proto).
 */
enum class ManeuverType {
    Start,
    Continue,
    SlightLeft,
    SlightRight,
    TurnLeft,
    TurnRight,
    SharpLeft,
    SharpRight,
    UTurn,
    Merge,
    Ramp,
    Roundabout,
    Destination,
    Unknown,
}

/**
 * One step of a route: travel along a segment, then perform [instruction] at its end
 * ([endShapeIndex] into [RouteResult.geometry]). No Valhalla JSON leaks past this — see
 * [ValhallaResponseParser].
 */
data class RouteManeuver(
    val instruction: String,
    val verbalInstruction: String?,
    val type: ManeuverType,
    val streetName: String?,
    val distanceMeters: Double,
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
    /** Provider-backed exit number/name; null when the provider did not supply one. */
    val exitSign: String? = null,
    /** Provider-backed signpost destination text; null when unavailable. */
    val signpost: String? = null,
)
