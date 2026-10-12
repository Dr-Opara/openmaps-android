package com.openmaps.app.core.places

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.spatial.GeoProjector
import com.openmaps.app.core.spatial.SpatialPose

/**
 * Projects nearby places into Live's camera view (Phase 9) by reusing [GeoProjector] — the same
 * pure, GL-free geographic-to-screen seam Phase 6 built for AR arrows (see "AR / Live positioning"
 * in ARCHITECTURE.md). This is deliberately the *simpler* screen-space fallback the Phase 9 plan
 * calls for rather than a second, world-locked AR anchoring system: a place is informational
 * context, not a safety-critical path cue, so a 2D screen-space label is enough.
 *
 * Returns an empty list whenever [pose] is null. **Do not fake precision**: without a tracked pose
 * there is no reliable device heading to project against, so Live simply shows no contextual labels
 * rather than guessing a position (the same reasoning `ARArrowPipeline` applies to arrows).
 */
object LivePlaceProjector {

    data class LivePlaceLabel(
        val place: Place,
        val x: Float,
        val y: Float,
        val distanceMeters: Double,
    )

    /**
     * Projects [places], keeping only those on-screen and in front of the device, nearest first,
     * capped at [maxVisible] — Live must stay visually restrained (see "Visual density" in
     * ARCHITECTURE.md), so this never returns more than the cap regardless of how many places were
     * passed in.
     */
    fun project(
        places: List<Place>,
        pose: SpatialPose?,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        maxVisible: Int,
    ): List<LivePlaceLabel> {
        if (pose == null || places.isEmpty() || maxVisible <= 0) return emptyList()
        return places
            .mapNotNull { place ->
                val screen = GeoProjector.project(
                    world = Coordinate(place.latitude, place.longitude),
                    userPose = pose,
                    viewportWidthPx = viewportWidthPx,
                    viewportHeightPx = viewportHeightPx,
                )
                if (screen == null || !screen.visible || !screen.onScreen) null else {
                    LivePlaceLabel(place = place, x = screen.x, y = screen.y, distanceMeters = screen.distanceMeters)
                }
            }
            .sortedBy { it.distanceMeters }
            .take(maxVisible)
    }
}
