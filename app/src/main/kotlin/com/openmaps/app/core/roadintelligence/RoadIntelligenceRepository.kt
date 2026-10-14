package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.Coordinate

/**
 * The road-intelligence seam (Phase 10), same shape as
 * [com.openmaps.app.core.places.PlaceRepository]/[com.openmaps.app.core.routing.RoutingRepository]:
 * a provider-neutral interface the feature layer depends on, with `Result.failure` carrying a typed
 * [RoadIntelligenceException] instead of a thrown exception. No provider JSON crosses this seam.
 *
 * Each method is independent and independently optional: a caller that only wants weather must not
 * have to touch hazards, and a failure in one must never be inferred from (or block) another. See
 * `DefaultRoadIntelligenceRepository` for the implementation, and ARCHITECTURE.md's Road
 * intelligence section for exactly which of these three a real data source backs today versus which
 * return empty/null because none exists yet.
 */
interface RoadIntelligenceRepository {

    /**
     * Hazards/incidents within [corridorMeters] of [routeGeometry], nearest-first, each stamped with
     * [RoadEvent.routeDistanceMeters] by the implementation (see [RoadEventRouteMatcher]). A corridor
     * **approximation** over one query, the same contract as
     * [com.openmaps.app.core.places.PlaceRepository.placesAlongRoute] — not per-segment detour
     * routing.
     */
    suspend fun roadEventsAlongRoute(
        routeGeometry: List<Coordinate>,
        corridorMeters: Double,
    ): Result<List<RoadEvent>>

    /** The posted speed limit nearest [location], or `Result.success(null)` when none is known. */
    suspend fun speedLimitNear(location: Coordinate): Result<SpeedLimitInfo?>

    /** Driving-relevant weather at [location], or `Result.success(null)` when none is known. */
    suspend fun weatherNear(location: Coordinate): Result<WeatherCondition?>
}
