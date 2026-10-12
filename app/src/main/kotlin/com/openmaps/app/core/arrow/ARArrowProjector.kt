package com.openmaps.app.core.arrow

import com.openmaps.app.core.spatial.GeoProjector
import com.openmaps.app.core.spatial.SpatialPose

/**
 * Projects sampled arrow nodes into the live camera view using the Phase 6 [GeoProjector] seam.
 *
 * Pure and separate from [ARArrowSampler] on purpose: sampling answers "where should arrows be on
 * the road", projection answers "where on screen does that road point land given where the phone is
 * pointing". Splitting them means a change to one never silently invalidates the other, and both
 * stay unit-testable.
 *
 * Results are returned **far-to-near** so the renderer can draw back-to-front without sorting per
 * frame (arrows nearer the user must sit on top of further ones).
 */
object ARArrowProjector {

    fun project(
        nodes: List<ARArrowNode>,
        pose: SpatialPose,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        horizontalFovDegrees: Double = GeoProjector.DEFAULT_HORIZONTAL_FOV_DEGREES,
    ): List<ScreenArrow> {
        if (nodes.isEmpty() || viewportWidthPx <= 0 || viewportHeightPx <= 0) return emptyList()

        val projected = ArrayList<ScreenArrow>(nodes.size)
        for (node in nodes) {
            if (!node.visibility) continue
            val point = GeoProjector.project(
                world = node.worldPosition,
                userPose = pose,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
                horizontalFovDegrees = horizontalFovDegrees,
            ) ?: continue
            if (!point.onScreen) continue // behind the driver, off the near plane, or past the FOV

            projected += ScreenArrow(
                node = node,
                x = point.x,
                // Vertically centred on the horizon line: with a pitch-free CPU projection there is
                // no true ground plane, so anchoring to the vertical centre keeps the run of arrows
                // reading as a path receding toward the vanishing point rather than as a flat row.
                y = point.y,
                radiusPx = node.scale,
                onScreen = true,
            )
        }
        // Far -> near, so nearer arrows are drawn last (on top).
        return projected.sortedByDescending { it.node.distanceAheadMeters }
    }
}