package com.openmaps.app.core.scout

import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.places.Place
import com.openmaps.app.core.places.PlaceCategory
import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.RouteResult

data class ScoutContext(
    val origin: Coordinate? = null,
    val selectedDestination: SearchResult? = null,
    val activeRoute: RouteResult? = null,
)

sealed interface ScoutIntent {
    data class NavigateTo(val query: String) : ScoutIntent
    data class ArrivalPlan(val query: String) : ScoutIntent
    data object FindParking : ScoutIntent
    data class FindNearby(val category: PlaceCategory) : ScoutIntent
    data object ExplainRoute : ScoutIntent
    data object Unknown : ScoutIntent
}

sealed interface ScoutAction {
    data class SelectDestination(val destination: SearchResult) : ScoutAction
    data class PreviewRoute(val route: RouteResult, val destination: SearchResult) : ScoutAction
    data class ShowPlaces(val places: List<Place>) : ScoutAction
}

enum class ScoutGroundingSource {
    Geocoding,
    Routing,
    Places,
    ActiveNavigation,
}

data class ScoutResponse(
    val message: String,
    val actions: List<ScoutAction> = emptyList(),
    val grounding: Set<ScoutGroundingSource> = emptySet(),
    val canRetry: Boolean = false,
)
