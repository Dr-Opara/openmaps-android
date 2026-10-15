package com.openmaps.app.feature.map

import com.openmaps.app.core.places.Place
import com.openmaps.app.core.places.PlaceCategory
import com.openmaps.app.core.places.PlaceQueryResult

/**
 * Lifecycle of the current contextual-places fetch, mirroring [RouteState]'s one-sealed-field
 * approach.
 *
 * A failed refresh deliberately does **not** overwrite an existing [Ready]: places are additive
 * context, never a core dependency, so a transient provider error must not clear markers that are
 * still perfectly good.
 *
 * [Ready.fromCache] lets the UI mark an answer that came from cache, so a stale list is never
 * presented as live.
 */
sealed interface PlacesState {
    data object Idle : PlacesState

    /**
     * A fetch is running for [categories]. Carried so the sheet can say
     * "Finding nearby restaurants…" rather than a generic "Finding places nearby…".
     */
    data class Loading(val categories: Set<PlaceCategory> = PlaceCategoryMapperAll) : PlacesState

    data class Ready(
        val places: List<Place>,
        val fromCache: Boolean = false,
        val stale: Boolean = false,
    ) : PlacesState

    data class Error(val reason: PlacesError) : PlacesState
}

/**
 * Every category, used as the default for a [Loading] state that was not triggered by an explicit
 * category choice (e.g. the first automatic fetch on a location fix).
 */
internal val PlaceCategoryMapperAll: Set<PlaceCategory>
    get() = com.openmaps.app.core.places.PlaceCategoryMapper.supportedCategories

/** Builds a [PlacesState.Ready] from a composite result, preserving provenance. */
internal fun PlaceQueryResult.toReadyState(): PlacesState.Ready = PlacesState.Ready(
    places = places,
    fromCache = isCached,
    stale = source == com.openmaps.app.core.places.PlaceSource.StaleCache,
)

/** User-facing places failure reasons. Mirrors [RouteError]'s shape. */
enum class PlacesError {
    NoNetwork,
    Timeout,
    ServerError,
    RateLimited,
    Malformed,
}