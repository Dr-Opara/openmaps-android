package com.openmaps.app.core.scout

import com.openmaps.app.core.geocoding.GeocodingRepository
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.places.PlaceCategory
import com.openmaps.app.core.places.PlaceRepository
import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.RoutingRepository

class OpenMapsScout(
    private val geocodingRepository: GeocodingRepository,
    private val routingRepository: RoutingRepository,
    private val placesRepository: PlaceRepository,
    private val intentParser: ScoutIntentParser = RuleBasedScoutIntentParser(),
) {
    suspend fun ask(input: String, context: ScoutContext): ScoutResponse {
        val response = when (val intent = intentParser.parse(input)) {
            is ScoutIntent.NavigateTo -> navigate(intent.query, context)
            is ScoutIntent.ArrivalPlan -> arrivalPlan(intent.query, context)
            ScoutIntent.FindParking -> findParking(context)
            is ScoutIntent.FindNearby -> findNearby(intent.category, context)
            ScoutIntent.ExplainRoute -> explainRoute(context)
            ScoutIntent.Unknown -> ScoutResponse(
                message = "Try Navigate to, Plan my arrival to, Find parking, or Find coffee nearby.",
            )
        }
        return ScoutSafetyPolicy.sanitize(response)
    }

    private suspend fun navigate(query: String, context: ScoutContext): ScoutResponse {
        if (query.isBlank()) return ScoutResponse("Tell me where you want to go.")
        val origin = context.origin ?: return ScoutResponse(
            message = "I need your current location before I can build a route.",
            canRetry = true,
        )
        val destination = resolveDestination(query) ?: return ScoutResponse(
            message = "I couldn't verify that destination.",
            canRetry = true,
        )
        val route = routingRepository.route(origin, destination.coordinate()).getOrNull()
            ?: return ScoutResponse(
                message = "I found the destination, but I couldn't build a verified route right now.",
                actions = listOf(ScoutAction.SelectDestination(destination)),
                grounding = setOf(ScoutGroundingSource.Geocoding),
                canRetry = true,
            )

        return ScoutResponse(
            message = "Route ready to " + destination.name + ". Review it, then start Live navigation.",
            actions = listOf(
                ScoutAction.SelectDestination(destination),
                ScoutAction.PreviewRoute(route, destination),
            ),
            grounding = setOf(ScoutGroundingSource.Geocoding, ScoutGroundingSource.Routing),
        )
    }

    private suspend fun arrivalPlan(query: String, context: ScoutContext): ScoutResponse {
        if (query.isBlank()) return ScoutResponse("Tell me the destination for your arrival plan.")
        val destination = resolveDestination(query) ?: return ScoutResponse(
            message = "I couldn't verify that destination.",
            canRetry = true,
        )

        val actions = mutableListOf<ScoutAction>(ScoutAction.SelectDestination(destination))
        val grounding = mutableSetOf(ScoutGroundingSource.Geocoding)
        val origin = context.origin
        if (origin != null) {
            routingRepository.route(origin, destination.coordinate()).getOrNull()?.let { route ->
                actions += ScoutAction.PreviewRoute(route, destination)
                grounding += ScoutGroundingSource.Routing
            }
        }

        val parking = placesRepository.nearbyPlaces(
            center = destination.coordinate(),
            radiusMeters = ARRIVAL_PARKING_RADIUS_METERS,
            categories = setOf(PlaceCategory.Parking),
        ).getOrNull()?.places.orEmpty()
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
            .take(MAX_ARRIVAL_PARKING_OPTIONS)

        if (parking.isNotEmpty()) {
            actions += ScoutAction.ShowPlaces(parking)
            grounding += ScoutGroundingSource.Places
        }

        val message = when {
            origin != null && ScoutGroundingSource.Routing in grounding && parking.isNotEmpty() ->
                "I verified the destination, built the route, and found " + parking.size + " parking options near arrival."
            origin != null && ScoutGroundingSource.Routing in grounding ->
                "I verified the destination and built the route. I couldn't verify nearby parking right now."
            parking.isNotEmpty() ->
                "I verified the destination and found " + parking.size + " nearby parking options. I still need your location to build the route."
            else ->
                "I verified the destination. I still need your location to build the route, and I couldn't verify nearby parking right now."
        }

        return ScoutResponse(message = message, actions = actions, grounding = grounding)
    }

    private suspend fun findParking(context: ScoutContext): ScoutResponse {
        val center = context.selectedDestination?.coordinate() ?: context.origin
            ?: return ScoutResponse("Select a destination or enable location so I know where to search.")

        val places = placesRepository.nearbyPlaces(
            center = center,
            radiusMeters = ARRIVAL_PARKING_RADIUS_METERS,
            categories = setOf(PlaceCategory.Parking),
        ).getOrNull()?.places.orEmpty()
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
            .take(MAX_VISIBLE_SCOUT_PLACES)

        if (places.isEmpty()) {
            return ScoutResponse(
                message = "I couldn't verify parking options in this area right now.",
                canRetry = true,
            )
        }
        return ScoutResponse(
            message = "I found " + places.size + " verified parking options nearby.",
            actions = listOf(ScoutAction.ShowPlaces(places)),
            grounding = setOf(ScoutGroundingSource.Places),
        )
    }

    private suspend fun findNearby(category: PlaceCategory, context: ScoutContext): ScoutResponse {
        val center = context.origin ?: context.selectedDestination?.coordinate()
            ?: return ScoutResponse("Enable location or select a destination so I know where to search.")

        val places = placesRepository.nearbyPlaces(
            center = center,
            radiusMeters = NEARBY_RADIUS_METERS,
            categories = setOf(category),
        ).getOrNull()?.places.orEmpty()
            .sortedBy { it.distanceMeters ?: Double.MAX_VALUE }
            .take(MAX_VISIBLE_SCOUT_PLACES)

        if (places.isEmpty()) {
            return ScoutResponse(
                message = "I couldn't verify nearby " + category.label() + " options right now.",
                canRetry = true,
            )
        }
        return ScoutResponse(
            message = "I found " + places.size + " verified " + category.label() + " options nearby.",
            actions = listOf(ScoutAction.ShowPlaces(places)),
            grounding = setOf(ScoutGroundingSource.Places),
        )
    }

    private fun explainRoute(context: ScoutContext): ScoutResponse {
        val route = context.activeRoute ?: return ScoutResponse("Start or preview a route first.")
        val miles = route.distanceMeters / METERS_PER_MILE
        val minutes = route.durationSeconds / SECONDS_PER_MINUTE
        return ScoutResponse(
            message = "Your active route is %.1f miles and about %.0f minutes based on the routing engine.".format(miles, minutes),
            grounding = setOf(ScoutGroundingSource.ActiveNavigation),
        )
    }

    private suspend fun resolveDestination(query: String): SearchResult? =
        geocodingRepository.search(query).getOrNull()?.firstOrNull()

    private fun SearchResult.coordinate() = Coordinate(latitude, longitude)

    private fun PlaceCategory.label(): String = name
        .replace(Regex("([a-z])([A-Z])"), "$1 $2")
        .lowercase()

    companion object {
        const val ARRIVAL_PARKING_RADIUS_METERS = 1200.0
        const val NEARBY_RADIUS_METERS = 1500.0
        const val MAX_ARRIVAL_PARKING_OPTIONS = 5
        const val MAX_VISIBLE_SCOUT_PLACES = 8
        const val METERS_PER_MILE = 1609.344
        const val SECONDS_PER_MINUTE = 60.0
    }
}
