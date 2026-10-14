package com.openmaps.app.core.environment

import com.openmaps.app.core.routing.Coordinate

/**
 * The road-classification seam (Phase 11), same shape as every other provider interface in this
 * codebase: a provider-neutral method the feature layer depends on, `Result.failure` instead of a
 * thrown exception, no provider JSON crossing this seam.
 *
 * Deliberately a **separate** interface/package from `core.roadintelligence.RoadIntelligenceRepository`
 * rather than a fourth method bolted onto it: Phase 11 must not modify Phase 10's seam (see
 * ARCHITECTURE.md's Road intelligence section and the Phase 11 brief's "do NOT replace Phase 10 Road
 * Intelligence"), and road classification is an environment-classification input, not a road-hazard/
 * weather/speed-limit concern. The two providers happen to both query Overpass, independently.
 */
interface RoadClassRepository {
    /** The nearest `highway=*` way's OSM classification near [location], or null when none is known. */
    suspend fun roadClassNear(location: Coordinate): Result<RoadClass?>
}

/**
 * Why a road-class request failed, classified so the caller never inspects raw IO/HTTP details.
 * Mirrors [com.openmaps.app.core.roadintelligence.RoadIntelligenceException]'s shape.
 */
sealed class RoadClassException(message: String) : Exception(message) {
    object NoNetwork : RoadClassException("No network connection")
    object Timeout : RoadClassException("The request timed out")
    class Server(val code: Int) : RoadClassException("Server error ($code)")
    object Malformed : RoadClassException("Malformed response")
}
