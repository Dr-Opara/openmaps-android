package com.openmaps.app.core.arrow

import com.openmaps.app.core.anchor.AnchoredRoutePoint
import com.openmaps.app.core.anchor.WorldPose
import com.openmaps.app.core.anchor.WorldTransform
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The **primary** arrow placement path: real ARCore world anchors.
 *
 * Phase 8's sampler still decides *where* arrows go (metric spacing, turn density, the near
 * cut-off, the visible cap) and *which way they point* (route tangent). This class only decides where
 * each of those arrows lives in space, and it does so from the Phase 7 anchor world poses rather
 * than from raw lat/lng. That is the whole difference between the two paths:
 *
 *  - Anchored: the arrow's world position is an ARCore-owned transform. Move the phone and the
 *    arrow stays where the road is, because the road really has not moved.
 *  - Projection fallback ([ARArrowProjector]): the arrow's position is recomputed from lat/lng
 *    against a fresh device pose every frame, so it is only as stable as the geolocation fix and
 *    visibly slides or jitters when that fix is imperfect.
 *
 * Anchors are intentionally **sparse** (see `RouteAnchorConfig`): they are expensive, so they define
 * the road's shape at intervals rather than one per arrow. [worldPoseAlongRoute] fills the gaps by
 * linear interpolation between the two bracketing anchors, which keeps spacing metric and, crucially,
 * means an arrow crossing an anchor boundary does not jump.
 *
 * Everything here is pure arithmetic on numbers, so the projection and interpolation are unit
 * tested without ARCore hardware.
 */
object AnchoredArrowProjector {

    /**
     * Interpolates the anchored world position at [distanceAheadMeters] along the route.
     *
     * Anchors are control points, not a continuous track, so an arrow between two anchors is placed
     * on the segment between them in **world space**. Doing the interpolation in world space (rather
     * than in lat/lng and re-projecting) is what makes the result stable: the segment is a real line
     * in ARCore's frame, so it stays put as the device moves.
     *
     * Returns null — deliberately, rather than extrapolating — when [distanceAheadMeters] lies beyond
     * the anchored window. An arrow invented by extrapolating past the last anchor would be drawn
     * with no world position behind it, which is precisely the "misleading spatial arrow" that
     * degraded anchoring is supposed to avoid. The caller treats null as "drop this arrow".
     *
     * @param anchors anchored points; only tracked ones are used, and they are sorted by distance.
     */
    fun worldPoseAlongRoute(
        anchors: List<AnchoredRoutePoint>,
        distanceAheadMeters: Double,
    ): WorldPose? {
        val tracked = anchors
            .filter { it.isSpatiallyTracked && it.distanceAheadMeters != null }
            .sortedBy { it.distanceAheadMeters!! }
        if (tracked.isEmpty()) return null

        // Outside the anchored window: no extrapolation, the arrow is simply not placeable yet.
        val first = tracked.first().distanceAheadMeters!!
        val last = tracked.last().distanceAheadMeters!!
        if (distanceAheadMeters < first - WINDOW_TOLERANCE_METERS) return null
        if (distanceAheadMeters > last + WINDOW_TOLERANCE_METERS) return null

        for (i in 0 until tracked.lastIndex) {
            val a = tracked[i]
            val b = tracked[i + 1]
            val da = a.distanceAheadMeters!!
            val db = b.distanceAheadMeters!!
            if (distanceAheadMeters < da || distanceAheadMeters > db) continue

            val pa = a.worldPose ?: continue
            val pb = b.worldPose ?: continue
            val span = db - da
            // Coincident anchors (duplicate points, or a route that doubles back) give a zero-width
            // segment. Take the nearer anchor rather than dividing by zero.
            if (abs(span) < MIN_SEGMENT_METERS) return pa
            val t = (distanceAheadMeters - da) / span
            return WorldPose(
                x = pa.x + (pb.x - pa.x) * t,
                y = pa.y + (pb.y - pa.y) * t,
                z = pa.z + (pb.z - pa.z) * t,
            )
        }

        // Beyond the last segment but inside tolerance: clamp to the nearest anchor.
        return when {
            distanceAheadMeters <= first -> tracked.first().worldPose
            else -> tracked.last().worldPose
        }
    }

    /**
     * Projects one sampled arrow onto the screen using its anchored world position.
     *
     * The camera's **full** orientation is used, so turning the phone swings arrows across the
     * screen correctly; a position-only projection would leave them pinned, which is the classic
     * symptom of AR content that is not actually world-locked.
     *
     * @return null when the arrow is behind the camera, outside the viewport, or unplaceable.
     */
    fun project(
        node: ARArrowNode,
        world: WorldPose,
        camera: WorldTransform,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        horizontalFovDegrees: Double = DEFAULT_HORIZONTAL_FOV_DEGREES,
        roadHeightMeters: Double = DEFAULT_ROAD_HEIGHT_METERS,
    ): ScreenArrow? {
        if (!node.visibility) return null
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return null

        // The anchor sits at the Earth reference height; the arrow rides on the road surface above
        // it. Applying the lift in world space (not as a screen-space nudge) is what keeps the height
        // consistent between two anchors that are 10 m apart instead of stepping at each boundary.
        val lifted = WorldPose(world.x, world.y + roadHeightMeters, world.z)

        // World -> camera space. ARCore camera axes are x right, y up, z backward.
        val cam = camera.offsetFrom(lifted)
        val depth = -cam[2]
        // Behind or level with the camera: never drawn. Without this an arrow beside or behind the
        // driver projects to a mirrored, wrong-way screen position.
        if (depth <= MIN_DEPTH_METERS) return null

        val focal = (viewportWidthPx / 2.0) / tan(horizontalFovDegrees * Math.PI / 360.0)
        val x = viewportWidthPx / 2.0 + (cam[0] / depth) * focal
        val y = viewportHeightPx / 2.0 - (cam[1] / depth) * focal

        // Culling off-screen arrows is a real saving (they still cost the sampler and the anchor
        // interpolation) and avoids the renderer stretching a chevron across the whole view.
        if (x < 0 || x > viewportWidthPx || y < 0 || y > viewportHeightPx) return null

        val distance = sqrt(
            (lifted.x - camera.position.x) * (lifted.x - camera.position.x) +
                (lifted.y - camera.position.y) * (lifted.y - camera.position.y) +
                (lifted.z - camera.position.z) * (lifted.z - camera.position.z),
        )

        return ScreenArrow(
            node = node,
            x = x.toFloat(),
            y = y.toFloat(),
            // The sampler already applied its distance taper to `node.scale`, so this multiplies the
            // screen-space term on top of it rather than replacing it.
            radiusPx = (node.scale * perspectiveScale(distance)).coerceIn(MIN_RADIUS_PX, MAX_RADIUS_PX),
            onScreen = true,
        )
    }

    /**
     * Places a whole sampled arrow set against the anchored world frame.
     *
     * Ordering is preserved (the sampler emits far-to-near), so the renderer keeps its painter's
     * order and near arrows still draw over far ones.
     */
    fun projectAll(
        nodes: List<ARArrowNode>,
        anchors: List<AnchoredRoutePoint>,
        camera: WorldTransform,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        horizontalFovDegrees: Double = DEFAULT_HORIZONTAL_FOV_DEGREES,
        roadHeightMeters: Double = DEFAULT_ROAD_HEIGHT_METERS,
    ): List<ScreenArrow> {
        if (nodes.isEmpty() || anchors.isEmpty()) return emptyList()
        val out = ArrayList<ScreenArrow>(nodes.size)
        for (node in nodes) {
            // Arrows are never behind the driver (the sampler's near cut-off guarantees a positive
            // distance), but guarding here means a bad sample cannot produce a backwards arrow.
            if (node.distanceAheadMeters < 0) continue
            val world = worldPoseAlongRoute(anchors, node.distanceAheadMeters) ?: continue
            project(node, world, camera, viewportWidthPx, viewportHeightPx, horizontalFovDegrees, roadHeightMeters)
                ?.let(out::add)
        }
        return out
    }

    /** True when at least one anchor is tracked well enough to place arrows. */
    fun isUsable(anchors: List<AnchoredRoutePoint>): Boolean =
        anchors.any { it.isSpatiallyTracked }

    /** Screen-space shrink with distance, so distant arrows stay legible but stop dominating. */
    private fun perspectiveScale(distanceMeters: Double): Float {
        val clamped = distanceMeters.coerceAtLeast(MIN_SCALE_DISTANCE_METERS)
        return (SCALE_REFERENCE_METERS / clamped).toFloat()
    }

    /** Bearing of an offset in camera space, in degrees clockwise from the camera's forward axis. */
    fun bearingDegrees(camX: Double, depth: Double): Double {
        val deg = Math.toDegrees(atan2(camX, depth))
        return if (deg < 0) deg + 360.0 else deg
    }

    /** Vertical field of view for a viewport, derived from aspect ratio and horizontal FOV. */
    fun verticalFovDegrees(viewportWidthPx: Int, viewportHeightPx: Int, horizontalFovDegrees: Double): Double {
        if (viewportWidthPx <= 0 || viewportHeightPx <= 0) return horizontalFovDegrees
        val aspect = viewportWidthPx.toDouble() / viewportHeightPx
        return Math.toDegrees(2.0 * atan2(tan(horizontalFovDegrees * Math.PI / 360.0), aspect))
    }

    const val DEFAULT_HORIZONTAL_FOV_DEGREES = 60.0

    /**
     * Vertical lift from the Earth reference point to the road surface, in metres. Matches
     * `RouteAnchorConfig.roadHeightOffsetMeters`; documented there as a fixed offset rather than
     * true road elevation.
     */
    const val DEFAULT_ROAD_HEIGHT_METERS = 0.35

    /** Anything nearer than this is effectively at the camera and cannot be projected meaningfully. */
    private const val MIN_DEPTH_METERS = 0.5

    /** Two anchors closer than this define a zero-width segment; treated as coincident. */
    private const val MIN_SEGMENT_METERS = 0.01

    /** Slack allowed at the ends of the anchored window so the boundary arrow is still placed. */
    private const val WINDOW_TOLERANCE_METERS = 0.5

    private const val MIN_SCALE_DISTANCE_METERS = 1.0
    private const val SCALE_REFERENCE_METERS = 10.0
    private const val MIN_RADIUS_PX = 11f
    private const val MAX_RADIUS_PX = 54f
}
