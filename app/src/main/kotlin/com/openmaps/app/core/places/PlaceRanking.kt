package com.openmaps.app.core.places

/**
 * Deterministic, explainable place ranking (Phase 9) — no ML, by design (see ARCHITECTURE.md).
 * [rank] sorts most-relevant first; callers cap the list afterward (Map and Live apply different
 * caps — see "Visual density" in ARCHITECTURE.md).
 */
object PlaceRanking {

    /**
     * @param isNavigating utility categories (parking/gas/EV) are weighted higher mid-trip, and
     *   a place behind the driver is penalized — neither applies while just browsing.
     * @param aheadPlaceIds [Place.id]s known to be ahead of the driver on the route (see
     *   [RouteCorridor.isAhead]). Ignored unless [isNavigating] is true.
     * @param utilityWeightMultiplier Phase 11 environment tuning (see `EnvironmentNavigationProfile`):
     *   multiplies the utility-category weight while navigating. Defaults to `1.0`, reproducing the
     *   exact pre-Phase-11 Phase 9 weighting.
     * @param generalWeightMultiplier same as [utilityWeightMultiplier], for general categories.
     */
    data class Context(
        val isNavigating: Boolean,
        val aheadPlaceIds: Set<String> = emptySet(),
        val utilityWeightMultiplier: Double = 1.0,
        val generalWeightMultiplier: Double = 1.0,
    )

    fun rank(places: List<Place>, context: Context): List<Place> =
        places.sortedByDescending { score(it, context) }

    private fun score(place: Place, context: Context): Double {
        val distance = place.routeDetourMeters ?: place.distanceMeters ?: DEFAULT_DISTANCE_METERS
        val distancePenalty = distance / DISTANCE_SCALE_METERS
        val behindPenalty = if (context.isNavigating && place.id !in context.aheadPlaceIds) BEHIND_PENALTY else 0.0
        return categoryWeight(place.category, context) - distancePenalty - behindPenalty
    }

    private fun categoryWeight(category: PlaceCategory, context: Context): Double {
        if (!context.isNavigating) return BROWSING_WEIGHT
        val isUtility = category == PlaceCategory.Parking ||
            category == PlaceCategory.Gas ||
            category == PlaceCategory.EvCharging
        return if (isUtility) {
            NAVIGATING_UTILITY_WEIGHT * context.utilityWeightMultiplier
        } else {
            NAVIGATING_GENERAL_WEIGHT * context.generalWeightMultiplier
        }
    }

    private const val DEFAULT_DISTANCE_METERS = 2_000.0
    private const val DISTANCE_SCALE_METERS = 200.0
    private const val BROWSING_WEIGHT = 10.0
    private const val NAVIGATING_UTILITY_WEIGHT = 12.0
    private const val NAVIGATING_GENERAL_WEIGHT = 8.0
    private const val BEHIND_PENALTY = 6.0
}
