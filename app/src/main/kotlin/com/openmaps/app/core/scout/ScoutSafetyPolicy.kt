package com.openmaps.app.core.scout

object ScoutSafetyPolicy {
    fun sanitize(response: ScoutResponse): ScoutResponse {
        val hasPlaceAction = response.actions.any { it is ScoutAction.ShowPlaces }

        require(!hasPlaceAction || ScoutGroundingSource.Places in response.grounding) {
            "Scout place output must be grounded by the places repository"
        }
        return response
    }
}
