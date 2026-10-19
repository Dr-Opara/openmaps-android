package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.ManeuverType

/**
 * Provider-neutral lane guidance seam.
 *
 * Implementations must return null whenever provider data is absent, inaccessible, ambiguous or
 * cannot identify a maneuver-compatible lane. Geometry alone is never enough to invent lanes.
 */
interface LaneGuidanceRepository {
    suspend fun guidance(
        trace: List<Coordinate>,
        maneuverType: ManeuverType,
    ): Result<LaneGuidance?>
}
