package com.openmaps.app.core.scout

object ScoutSafetyPolicy {
    fun sanitize(response: ScoutResponse): ScoutResponse {
        val hasRouteAction = response.actions.any { it is ScoutAction.PreviewRoute }
        val hasPlaceAction = response.actions.any { it is ScoutAction.ShowPlaces }

        require(!hasRouteAction || ScoutGroundingSource.Routing in response.grounding) {
            "Scout route output must be grounded by the routing engine"
        }
        require(!hasPlaceAction || ScoutGroundingSource.Places in response.grounding) {
            "Scout place output must be grounded by the places repository"
        }
        return response
    }
}
