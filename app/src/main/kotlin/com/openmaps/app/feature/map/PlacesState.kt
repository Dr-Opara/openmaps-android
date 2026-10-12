package com.openmaps.app.feature.map

import com.openmaps.app.core.places.Place

/**
 * Lifecycle of the current contextual-places fetch (Phase 9), mirroring [RouteState]'s
 * one-sealed-field approach rather than scattered nullable flags.
 *
 * A failed refresh deliberately does **not** overwrite an existing [Ready]: see
 * `MapViewModel.fetchPlaces` — places are additive context, never a core dependency, so a
 * transient provider error must not clear markers that are still perfectly good.
 */
sealed interface PlacesState {
    data object Idle : PlacesState
    data object Loading : PlacesState
    data class Ready(val places: List<Place>) : PlacesState
    data class Error(val reason: PlacesError) : PlacesState
}

/** User-facing places failure reasons. Mirrors [RouteError]'s shape. */
enum class PlacesError {
    NoNetwork,
    Timeout,
    ServerError,
    RateLimited,
    Malformed,
}
