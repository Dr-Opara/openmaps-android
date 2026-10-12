package com.openmaps.app.core.places

import com.openmaps.app.core.routing.Coordinate

/**
 * The places seam (Phase 9), same shape as [com.openmaps.app.core.geocoding.GeocodingRepository]
 * and [com.openmaps.app.core.routing.RoutingRepository]: a provider-neutral interface the feature
 * layer depends on, with `Result.failure` carrying a typed [PlaceException] instead of a thrown
 * exception. Provider-specific JSON/models never cross this seam — see `OverpassPlaceRepository`.
 *
 * Both methods are bounded by construction: [nearbyPlaces] takes an explicit radius and
 * [placesAlongRoute] an explicit corridor width, and implementations must cap the result count —
 * Places is additive context, never an unbounded fetch.
 */
interface PlaceRepository {

    /** Places within [radiusMeters] of [center], restricted to [categories]. */
    suspend fun nearbyPlaces(
        center: Coordinate,
        radiusMeters: Double,
        categories: Set<PlaceCategory>,
    ): Result<List<Place>>

    /**
     * Places within [corridorMeters] of [routeGeometry], restricted to [categories].
     *
     * A corridor **approximation** is sufficient for Phase 9 (see ROADMAP.md): this is not a
     * detour-routing engine, just a filter over the places a normal nearby query already returns.
     */
    suspend fun placesAlongRoute(
        routeGeometry: List<Coordinate>,
        corridorMeters: Double,
        categories: Set<PlaceCategory>,
    ): Result<List<Place>>
}
