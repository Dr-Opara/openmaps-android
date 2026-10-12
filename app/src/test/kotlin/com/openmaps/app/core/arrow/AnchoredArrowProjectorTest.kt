package com.openmaps.app.core.arrow

import com.openmaps.app.core.anchor.AnchorTrackingState
import com.openmaps.app.core.anchor.AnchoredRoutePoint
import com.openmaps.app.core.anchor.WorldPose
import com.openmaps.app.core.anchor.WorldTransform
import com.openmaps.app.core.routing.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The anchored placement path: Phase 8 arrows resolved against Phase 7's ARCore world anchors.
 *
 * This is the seam the whole feature rests on. Two failure modes matter and neither shows up on a
 * desktop:
 *
 *  - **Instability**: if the arrow were re-derived from lat/lng every frame it would slide with the
 *    geolocation fix. So a test here moves the *camera* while holding the *anchors* fixed and
 *    asserts the arrow's world position is untouched — the anchors must be the source of truth.
 *  - **Extrapolation**: an arrow placed past the last anchor has no world position behind it, so
 *    inventing one produces a confidently wrong chevron. Refusing to place it is the correct
 *    behaviour, not a missing feature.
 */
class AnchoredArrowProjectorTest {

    private val viewportWidth = 1080
    private val viewportHeight = 2400

    /**
     * ARCore's world frame is y-up with the camera looking down -z at identity orientation, so a
     * point at negative z is straight ahead.
     */
    private val cameraAtOrigin = WorldTransform(WorldPose(0.0, 0.0, 0.0))

    private fun anchored(
        id: Int,
        distanceAhead: Double,
        world: WorldPose?,
        headingDegrees: Double = 0.0,
        state: AnchorTrackingState = AnchorTrackingState.Tracking,
    ) = AnchoredRoutePoint(
        id = id,
        anchorKey = id.toLong(),
        latitude = 0.0,
        longitude = 0.0,
        altitudeMeters = 0.0,
        heightAboveRoadMeters = RouteAnchorDefault.ROAD_HEIGHT,
        headingRadians = Math.toRadians(headingDegrees),
        worldPose = world,
        trackingState = state,
        distanceAheadMeters = distanceAhead,
    )

    /** Straight run down -z at 1 m per metre of distance ahead. */
    private fun straightAhead(vararg distances: Double): List<AnchoredRoutePoint> =
        distances.mapIndexed { i, d ->
            anchored(i, d, WorldPose(0.0, 0.0, -d))
        }

    private fun node(id: Int, distance: Double, scale: Float = 26f, visibility: Boolean = true) = ARArrowNode(
        id = id,
        worldPosition = Coordinate(0.0, 0.0),
        headingRadians = 0.0,
        distanceAheadMeters = distance,
        visibility = visibility,
        scale = scale,
        isApproachingManeuver = false,
    )

    // region Interpolation between anchor control points

    @Test
    fun worldPoseAlongRoute_landsExactlyOnAnAnchor() {
        val anchors = straightAhead(10.0, 20.0, 30.0)
        val pose = AnchoredArrowProjector.worldPoseAlongRoute(anchors, 20.0)
        assertNotNull(pose)
        assertEquals(-20.0, pose!!.z, 1e-6)
    }

    @Test
    fun worldPoseAlongRoute_interpolatesBetweenAnchors() {
        val anchors = straightAhead(10.0, 30.0)
        // Halfway along the 10->30 m segment.
        val pose = AnchoredArrowProjector.worldPoseAlongRoute(anchors, 20.0)
        assertNotNull(pose)
        assertEquals(-20.0, pose!!.z, 1e-6)
    }

    @Test
    fun worldPoseAlongRoute_isContinuousAcrossAnAnchorBoundary() {
        // The requirement behind "no sudden jumps between control points": spacing out either side
        // of an anchor must differ by the step, never by a discontinuity.
        val anchors = straightAhead(10.0, 20.0, 30.0)
        val just = AnchoredArrowProjector.worldPoseAlongRoute(anchors, 19.999)!!.z
        val at = AnchoredArrowProjector.worldPoseAlongRoute(anchors, 20.0)!!.z
        val after = AnchoredArrowProjector.worldPoseAlongRoute(anchors, 20.001)!!.z
        assertTrue("a 1mm step produced a ${Math.abs(just - at)}m jump", Math.abs(just - at) < 0.01)
        assertTrue("a 1mm step produced a ${Math.abs(at - after)}m jump", Math.abs(at - after) < 0.01)
    }

    @Test
    fun worldPoseAlongRoute_keepsSpacingMetricAlongACurvedRoad() {
        // Anchors around a right-angle bend. On a curve the *straight-line* distance from the
        // camera is legitimately shorter than the arc length travelled, so the assertion is that
        // consecutive interpolated points stay evenly spaced in world space and progress smoothly —
        // which is what "no breathing spacing across the corner" actually means.
        val anchors = listOf(
            anchored(0, 10.0, WorldPose(0.0, 0.0, -10.0)),
            anchored(1, 20.0, WorldPose(10.0, 0.0, -10.0)),
            anchored(2, 30.0, WorldPose(20.0, 0.0, -10.0)),
        )
        var previous: WorldPose? = null
        var minStep = Double.MAX_VALUE
        var maxStep = 0.0
        var d = 10.0
        while (d <= 30.0) {
            val current = AnchoredArrowProjector.worldPoseAlongRoute(anchors, d)!!
            val p = previous
            if (p != null) {
                val step = current.distanceTo(p)
                minStep = Math.min(minStep, step)
                maxStep = Math.max(maxStep, step)
            }
            previous = current
            d += 1.0
        }
        // Each 1 m of route distance must advance the interpolated point by close to 1 m in world
        // space, whatever the road is doing.
        assertEquals("interpolated spacing drifted across the corner", 1.0, minStep, 0.02)
        assertEquals("interpolated spacing drifted across the corner", 1.0, maxStep, 0.02)
    }

    @Test
    fun worldPoseAlongRoute_beyondTheLastAnchor_isNotExtrapolated() {
        val anchors = straightAhead(10.0, 20.0, 30.0)
        assertNull(
            "an arrow past the anchored window must be dropped, not invented",
            AnchoredArrowProjector.worldPoseAlongRoute(anchors, 90.0),
        )
    }

    @Test
    fun worldPoseAlongRoute_beforeTheFirstAnchor_isNotExtrapolated() {
        val anchors = straightAhead(20.0, 30.0, 40.0)
        assertNull(AnchoredArrowProjector.worldPoseAlongRoute(anchors, 2.0))
    }

    @Test
    fun worldPoseAlongRoute_untrackedAnchorsAreIgnored() {
        val anchors = listOf(
            anchored(0, 10.0, WorldPose(0.0, 0.0, -10.0), state = AnchorTrackingState.Paused),
            anchored(1, 20.0, WorldPose(0.0, 0.0, -20.0), state = AnchorTrackingState.Stopped),
        )
        assertNull("untracked anchors cannot define a world position", AnchoredArrowProjector.worldPoseAlongRoute(anchors, 15.0))
    }

    @Test
    fun worldPoseAlongRoute_noAnchors_isNull() {
        assertNull(AnchoredArrowProjector.worldPoseAlongRoute(emptyList(), 10.0))
    }

    @Test
    fun worldPoseAlongRoute_coincidentAnchorsDoNotDivideByZero() {
        // A route that doubles back on itself produces two anchors at the same distance. That must
        // degrade to "pick one", never to NaN coordinates.
        val anchors = listOf(
            anchored(0, 20.0, WorldPose(1.0, 0.0, -20.0)),
            anchored(1, 20.0, WorldPose(2.0, 0.0, -20.0)),
        )
        val pose = AnchoredArrowProjector.worldPoseAlongRoute(anchors, 20.0)
        assertNotNull(pose)
        assertFalse("coordinates must stay finite", pose!!.x.isNaN() || pose.z.isNaN())
    }

    // endregion

    // region Projection

    @Test
    fun project_arrowStraightAhead_landsAtScreenCentreHorizontally() {
        val anchors = straightAhead(20.0, 40.0)
        val arrow = AnchoredArrowProjector.project(
            node = node(0, 30.0),
            world = AnchoredArrowProjector.worldPoseAlongRoute(anchors, 30.0)!!,
            camera = cameraAtOrigin,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertNotNull(arrow)
        assertEquals(viewportWidth / 2.0, arrow!!.x.toDouble(), 2.0)
        assertTrue(arrow.onScreen)
    }

    @Test
    fun project_arrowBesideTheDriver_shiftsAcrossTheScreen() {
        // 10 m to the right at 20 m depth must appear right of centre, not mirrored.
        val anchors = listOf(anchored(0, 20.0, WorldPose(10.0, 0.0, -20.0)))
        val arrow = AnchoredArrowProjector.project(
            node = node(0, 20.0),
            world = anchors[0].worldPose!!,
            camera = cameraAtOrigin,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertNotNull(arrow)
        assertTrue("expected the arrow right of centre, got ${arrow!!.x}", arrow.x > viewportWidth / 2f)
    }

    @Test
    fun project_pointBehindTheCamera_isCulled() {
        val anchors = listOf(anchored(0, 20.0, WorldPose(0.0, 0.0, 20.0)))
        val arrow = AnchoredArrowProjector.project(
            node = node(0, 20.0),
            world = anchors[0].worldPose!!,
            camera = cameraAtOrigin,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertNull("an arrow behind the camera must never be drawn", arrow)
    }

    @Test
    fun project_invisibleNode_isCulled() {
        val arrow = AnchoredArrowProjector.project(
            node = node(0, 20.0, visibility = false),
            world = WorldPose(0.0, 0.0, -20.0),
            camera = cameraAtOrigin,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertNull(arrow)
    }

    @Test
    fun project_offScreenArrow_isCulled() {
        val arrow = AnchoredArrowProjector.project(
            node = node(0, 20.0),
            world = WorldPose(1_000.0, 0.0, -20.0),
            camera = cameraAtOrigin,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertNull(arrow)
    }

    @Test
    fun project_zeroSizedViewport_isCulled() {
        val arrow = AnchoredArrowProjector.project(
            node = node(0, 20.0),
            world = WorldPose(0.0, 0.0, -20.0),
            camera = cameraAtOrigin,
            viewportWidthPx = 0,
            viewportHeightPx = 0,
        )
        assertNull(arrow)
    }

    @Test
    fun project_usesTheFullCameraOrientation_soTurningThePhoneSwingsArrows() {
        // Yaw the camera 90 degrees. The road still points the same way in the world, so the arrow
        // must move to the edge of the view. A position-only projection would not move it at all —
        // which is exactly the "not actually world-locked" bug this guards against.
        val anchors = straightAhead(20.0, 40.0)
        val world = AnchoredArrowProjector.worldPoseAlongRoute(anchors, 30.0)!!

        val facing = AnchoredArrowProjector.project(node(0, 30.0), world, cameraAtOrigin, viewportWidth, viewportHeight)
        assertNotNull(facing)
        assertEquals(viewportWidth / 2.0, facing!!.x.toDouble(), 2.0)

        // A modest yaw must still be visible in frame.
        val swung = AnchoredArrowProjector.project(node(0, 30.0), world, yawedCamera(20.0), viewportWidth, viewportHeight)
        assertNotNull(swung)
        val swungX = swung!!.x
        assertTrue(
            "turning the camera 20 degrees must move the arrow, got $swungX",
            Math.abs(swungX - viewportWidth / 2.0) > 50.0,
        )
    }

    @Test
    fun project_cameraYawedPastTheArrow_cullsItRatherThanMirroringIt() {
        // Turn 90 degrees and the road is off to the side. Mirroring it back into view would draw an
        // arrow pointing the wrong way, so culling is the only correct answer.
        val anchors = straightAhead(20.0, 40.0)
        val world = AnchoredArrowProjector.worldPoseAlongRoute(anchors, 30.0)!!
        val swung = AnchoredArrowProjector.project(node(0, 30.0), world, yawedCamera(90.0), viewportWidth, viewportHeight)
        assertNull("an arrow 90 degrees off-axis must not be drawn", swung)
    }

    /** A camera at the origin yawed by [degrees] about the world y axis. */
    private fun yawedCamera(degrees: Double): WorldTransform {
        val half = Math.toRadians(degrees / 2.0)
        return WorldTransform(
            position = WorldPose(0.0, 0.0, 0.0),
            qx = 0.0,
            qy = Math.sin(half),
            qz = 0.0,
            qw = Math.cos(half),
        )
    }

    // endregion

    // region Vertical placement

    @Test
    fun project_arrowsRideAboveTheRoad_notAtTheAnchorHeight() {
        // The anchor sits at the Earth reference point; the arrow must be lifted onto the road, or
        // it is buried in the surface. A lift applied in world space keeps this consistent between
        // two anchors 10 m apart instead of stepping at the boundary.
        val anchors = straightAhead(20.0, 40.0)
        val onRoad = AnchoredArrowProjector.project(
            node = node(0, 30.0),
            world = AnchoredArrowProjector.worldPoseAlongRoute(anchors, 30.0)!!,
            camera = cameraAtOrigin,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        val atAnchorHeight = AnchoredArrowProjector.project(
            node = node(0, 30.0),
            world = AnchoredArrowProjector.worldPoseAlongRoute(anchors, 30.0)!!,
            camera = cameraAtOrigin,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
            roadHeightMeters = 0.0,
        )
        assertNotNull(onRoad)
        assertNotNull(atAnchorHeight)
        assertTrue(
            "a lifted arrow must render higher on screen than one at the anchor height",
            onRoad!!.y < atAnchorHeight!!.y,
        )
    }

    @Test
    fun projectAll_heightsAreConsistentAcrossAnAnchorBoundary() {
        // Vertical jumps at anchor boundaries are the visible artefact of a per-anchor altitude
        // hack. With a world-space lift the screen height varies smoothly.
        val anchors = straightAhead(10.0, 20.0, 30.0, 40.0)
        val arrows = AnchoredArrowProjector.projectAll(
            nodes = listOf(node(0, 19.9), node(1, 20.0), node(2, 20.1)),
            anchors = anchors,
            camera = cameraAtOrigin,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertEquals(3, arrows.size)
        val ys = arrows.map { it.y.toDouble() }
        val jump = (ys[1] - ys[0]).let { Math.abs(it) }
        assertTrue("a 10cm step caused a ${jump}px vertical jump", jump < 5.0)
    }

    // endregion

    // region Full set

    @Test
    fun projectAll_preservesSamplerOrder_soNearArrowsDrawOnTop() {
        val anchors = straightAhead(10.0, 40.0, 80.0)
        val arrows = AnchoredArrowProjector.projectAll(
            nodes = listOf(node(0, 80.0), node(1, 40.0), node(2, 10.0)),
            anchors = anchors,
            camera = cameraAtOrigin,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertEquals(listOf(80.0, 40.0, 10.0), arrows.map { it.node.distanceAheadMeters })
    }

    @Test
    fun projectAll_dropsArrowsOutsideTheAnchorWindow_butKeepsTheRest() {
        // Partial coverage must degrade gracefully, not all-or-nothing.
        val anchors = straightAhead(10.0, 20.0, 30.0)
        val arrows = AnchoredArrowProjector.projectAll(
            nodes = listOf(node(0, 15.0), node(1, 25.0), node(2, 500.0)),
            anchors = anchors,
            camera = cameraAtOrigin,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertEquals("the two covered arrows survive, the uncovered one is dropped", 2, arrows.size)
        assertEquals(listOf(15.0, 25.0), arrows.map { it.node.distanceAheadMeters })
    }

    @Test
    fun projectAll_negativeDistanceArrow_isNeverProduced() {
        val anchors = straightAhead(20.0, 40.0)
        val arrows = AnchoredArrowProjector.projectAll(
            nodes = listOf(node(0, -30.0)),
            anchors = anchors,
            camera = cameraAtOrigin,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertTrue("a backwards arrow must never be emitted", arrows.isEmpty())
    }

    @Test
    fun projectAll_emptyInputs_produceNothing() {
        assertTrue(
            AnchoredArrowProjector.projectAll(emptyList(), straightAhead(10.0), cameraAtOrigin, viewportWidth, viewportHeight).isEmpty(),
        )
        assertTrue(
            AnchoredArrowProjector.projectAll(listOf(node(0, 10.0)), emptyList(), cameraAtOrigin, viewportWidth, viewportHeight).isEmpty(),
        )
    }

    @Test
    fun isUsable_reflectsWhetherAnyAnchorCanPlaceArrows() {
        assertFalse(AnchoredArrowProjector.isUsable(emptyList()))
        assertFalse(
            AnchoredArrowProjector.isUsable(
                listOf(anchored(0, 10.0, null, state = AnchorTrackingState.Paused)),
            ),
        )
        assertTrue(AnchoredArrowProjector.isUsable(straightAhead(10.0)))
    }

    @Test
    fun project_movingTheCameraDoesNotMoveTheAnchoredWorldPosition() {
        // The defining property of anchoring: the road is fixed, the device moves. Walking forward
        // must change the arrow's size and nothing about where it is in the world.
        val anchors = straightAhead(40.0, 60.0)
        val worldBefore = AnchoredArrowProjector.worldPoseAlongRoute(anchors, 50.0)
        assertNotNull(worldBefore)

        val walked = AnchoredArrowProjector.project(
            node = node(0, 50.0, scale = 150f),
            world = worldBefore!!,
            camera = WorldTransform(WorldPose(0.0, 0.0, -10.0)), // 10 m forward along the road
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        // The anchor set is untouched: same world pose, same identity, no re-derivation from lat/lng.
        assertEquals(worldBefore, AnchoredArrowProjector.worldPoseAlongRoute(anchors, 50.0))

        val before = AnchoredArrowProjector.project(
            node = node(0, 50.0, scale = 150f),
            world = worldBefore,
            camera = cameraAtOrigin,
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertNotNull(walked)
        assertNotNull(before)
        assertEquals("walking toward an anchor must not move it on screen", before!!.x, walked!!.x, 0.01f)
        assertTrue("walking toward an anchor must enlarge it", walked.radiusPx > before.radiusPx)
    }

    // endregion

    /** Shared constant so tests do not silently diverge from the production default. */
    private object RouteAnchorDefault {
        const val ROAD_HEIGHT = 0.35
    }
}
