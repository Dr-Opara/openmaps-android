package com.openmaps.app.arrow

import com.openmaps.app.core.anchor.AnchorTrackingState
import com.openmaps.app.core.anchor.AnchoredRoutePoint
import com.openmaps.app.core.anchor.WorldPose
import com.openmaps.app.core.anchor.WorldTransform
import com.openmaps.app.core.arrow.ARArrowConfig
import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.core.routing.RouteManeuver
import com.openmaps.app.core.routing.RouteResult
import com.openmaps.app.core.spatial.LocalizationQuality
import com.openmaps.app.core.spatial.SpatialLocalizationState
import com.openmaps.app.core.spatial.SpatialPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 1 guidance gate: real AR chevrons only during valid active navigation.
 *
 * These tests drive [ARArrowPipeline.update] through the exact state transitions the Live screen
 * performs — Active -> OffRoute -> Rerouting -> Active (replacement route), Active -> Arrived,
 * Active -> Idle (stop), tracking loss/restoration, and destination selected but never started —
 * and assert zero drawable arrows in every unsafe state. The OffRoute and Arrived cases use
 * *progress objects that still carry geometry*, so they cannot pass by geometry exhaustion alone:
 * without the gate, the sampler would draw arrows for them.
 *
 * The call-site half of the gate (LiveScreen passing guidance-active only, rather than broad
 * trip-in-progress, into both `update` calls) is not unit-testable at the composable level; the
 * pipeline backstop tested here is what makes the contract hold even if a caller gets it wrong.
 */
class ARArrowGuidanceGateTest {

    private val viewportWidth = 1080
    private val viewportHeight = 2400
    private val mpd = 111_320.0

    private val camera = WorldTransform(WorldPose(0.0, 0.0, 0.0))

    /** A 400 m route running due north, one vertex per metre. */
    private val route = RouteResult(
        geometry = (0..400).map { Coordinate(it / mpd, 0.0) },
        distanceMeters = 400.0,
        durationSeconds = 400.0,
    )

    /** A 400 m replacement route running due east, one vertex per metre. */
    private val routeEast = RouteResult(
        geometry = (0..400).map { Coordinate(0.0, it / mpd) },
        distanceMeters = 400.0,
        durationSeconds = 400.0,
    )

    private fun progress(
        snappedShapeIndex: Int = 0,
        remaining: Double = 400.0,
        fraction: Double = snappedShapeIndex / 400.0,
        isOffRoute: Boolean = false,
    ) = RouteProgress(
        snappedShapeIndex = snappedShapeIndex,
        lateralDistanceMeters = 0.0,
        distanceTraveledMeters = snappedShapeIndex.toDouble(),
        remainingDistanceMeters = remaining,
        remainingDurationSeconds = remaining,
        progressFraction = fraction,
        currentManeuver = maneuver(0),
        nextManeuver = null,
        distanceToNextManeuverMeters = 5_000.0,
        isOffRoute = isOffRoute,
    )

    private fun maneuver(index: Int) = RouteManeuver(
        instruction = "Maneuver $index",
        verbalInstruction = null,
        type = ManeuverType.Continue,
        streetName = null,
        distanceMeters = 100.0,
        beginShapeIndex = index * 3,
        endShapeIndex = index * 3,
    )

    private fun pose(quality: LocalizationQuality = LocalizationQuality.Good, headingDegrees: Double = 0.0) = SpatialPose(
        latitude = 0.0,
        longitude = 0.0,
        altitudeMeters = 5.0,
        headingDegrees = headingDegrees,
        horizontalAccuracyMeters = 2.0,
        verticalAccuracyMeters = 2.0,
        headingAccuracyDegrees = 1.0,
        quality = quality,
    )

    private fun tracking(quality: LocalizationQuality = LocalizationQuality.Good, headingDegrees: Double = 0.0) =
        SpatialLocalizationState.Tracking(pose(quality, headingDegrees))

    private fun anchors(
        vararg distances: Double,
    ): List<AnchoredRoutePoint> = distances.mapIndexed { i, d ->
        AnchoredRoutePoint(
            id = i,
            anchorKey = d.toLong(),
            latitude = d / mpd,
            longitude = 0.0,
            altitudeMeters = 0.0,
            heightAboveRoadMeters = 0.35,
            headingRadians = 0.0,
            worldPose = WorldPose(0.0, 0.0, -d),
            trackingState = AnchorTrackingState.Tracking,
            distanceAheadMeters = d,
        )
    }

    private fun pipeline() = ARArrowPipeline(ARArrowConfig())

    private fun update(
        pipeline: ARArrowPipeline,
        anchored: List<AnchoredRoutePoint> = emptyList(),
        cameraTransform: WorldTransform? = camera,
        progress: RouteProgress? = progress(),
        state: SpatialLocalizationState = tracking(),
        isNavigating: Boolean = true,
        route: RouteResult? = this.route,
    ) = pipeline.update(
        route = route,
        progress = progress,
        pose = (state as? SpatialLocalizationState.Tracking)?.pose,
        spatialState = state,
        viewportWidthPx = viewportWidth,
        viewportHeightPx = viewportHeight,
        isNavigating = isNavigating,
        anchoredPoints = anchored,
        cameraTransform = cameraTransform,
    )

    /** Drives [update] until the quality gate opens, exactly as the real screen does. */
    private fun settled(
        pipeline: ARArrowPipeline,
        anchored: List<AnchoredRoutePoint> = emptyList(),
        cameraTransform: WorldTransform? = camera,
        progress: RouteProgress? = progress(),
        state: SpatialLocalizationState = tracking(),
        isNavigating: Boolean = true,
        route: RouteResult? = this.route,
    ) {
        repeat(ARArrowConfig().qualityHysteresisSteps + 2) {
            update(pipeline, anchored, cameraTransform, progress, state, isNavigating, route)
        }
    }

    @Test
    fun offRouteProgress_withBroadNavigatingFlag_drawsNothing() {
        // The pipeline backstop, independent of the call-site gate: even a caller that passes
        // broad trip-in-progress must not get arrows for OffRoute progress.
        val pipeline = pipeline()
        update(pipeline, anchored = anchors(10.0, 40.0, 80.0), progress = progress(isOffRoute = true))
        assertEquals("OffRoute progress must never draw, whatever the navigating flag says", 0, pipeline.arrowCount())
        assertFalse(pipeline.usingAnchors)
    }

    @Test
    fun offRouteProgress_clearsExistingArrowsImmediately() {
        val pipeline = pipeline()
        settled(pipeline, anchored = anchors(10.0, 40.0, 80.0))
        assertTrue("precondition: arrows are showing", pipeline.arrowCount() > 0)

        update(pipeline, anchored = anchors(10.0, 40.0, 80.0), progress = progress(isOffRoute = true))
        assertEquals("going off route must drop the old route's arrows at once", 0, pipeline.arrowCount())
        assertFalse(pipeline.usingAnchors)
        assertNull(pipeline.degradedReason)
    }

    @Test
    fun arrivedProgress_withPlentifulGeometryAhead_drawsNothing() {
        // Gate-isolating construction: arrived (remaining 0 m, fraction 1.0) but the driver
        // position is at the start, so 400 m of geometry still lies ahead. Without the gate the
        // sampler would draw arrows for this progress; geometry exhaustion cannot explain a pass.
        val pipeline = pipeline()
        update(
            pipeline,
            anchored = anchors(10.0, 40.0, 80.0),
            progress = progress(snappedShapeIndex = 0, remaining = 0.0, fraction = 1.0),
        )
        assertEquals("arrived progress must never draw, even with geometry ahead", 0, pipeline.arrowCount())
    }

    @Test
    fun arrivedProgress_clearsExistingArrowsImmediately() {
        val pipeline = pipeline()
        settled(pipeline, anchored = anchors(10.0, 40.0, 80.0))
        assertTrue("precondition: arrows are showing", pipeline.arrowCount() > 0)
        assertTrue(pipeline.sampledCount() > 0)

        update(
            pipeline,
            anchored = anchors(10.0, 40.0, 80.0),
            progress = progress(snappedShapeIndex = 0, remaining = 0.0, fraction = 1.0),
        )
        assertEquals("arrival must drop every arrow at once", 0, pipeline.arrowCount())
        assertEquals("arrival must reset the sampled set, not just hide it", 0, pipeline.sampledCount())
        assertFalse(pipeline.usingAnchors)
    }

    @Test
    fun reroutingNullProgress_clearsExistingArrowsImmediately() {
        // Rerouting carries no progress in the Live screen; the pipeline must treat that as
        // "nothing to draw", never as a reason to keep the old route's set.
        val pipeline = pipeline()
        settled(pipeline, anchored = anchors(10.0, 40.0, 80.0))
        assertTrue("precondition: arrows are showing", pipeline.arrowCount() > 0)

        update(pipeline, anchored = anchors(10.0, 40.0, 80.0), progress = null)
        assertEquals("rerouting must drop the old route's arrows at once", 0, pipeline.arrowCount())
    }

    @Test
    fun sequence_activeOffRouteReroutingActiveReplacement_drawsOnlyReplacementArrows() {
        val pipeline = pipeline()
        settled(pipeline, anchored = anchors(10.0, 40.0, 80.0))
        assertTrue("precondition: arrows are showing", pipeline.arrowCount() > 0)

        update(pipeline, anchored = anchors(10.0, 40.0, 80.0), progress = progress(isOffRoute = true))
        assertEquals("OffRoute must clear", 0, pipeline.arrowCount())

        update(pipeline, anchored = anchors(10.0, 40.0, 80.0), progress = null)
        assertEquals("Rerouting must stay clear", 0, pipeline.arrowCount())

        // Replacement route arrives and guidance resumes; its anchors do not exist yet, so the
        // fallback projection draws — but the set is derived from the new route only. The driver
        // now faces east (heading 90) onto the replacement road: with the north-facing pose the
        // due-east road would sit 90 degrees off-axis, outside the 60-degree FOV, and the
        // projection would correctly drop every node — which is a test-harness artifact, not a
        // guidance result.
        settled(pipeline, anchored = emptyList(), route = routeEast, state = tracking(headingDegrees = 90.0))
        assertTrue("the replacement route must draw arrows again", pipeline.arrowCount() > 0)
    }

    @Test
    fun trackingLoss_clearsArrowsAndRestorationRedraws() {
        val pipeline = pipeline()
        settled(pipeline, anchored = anchors(10.0, 40.0, 80.0))
        assertTrue("precondition: arrows are showing", pipeline.arrowCount() > 0)

        update(pipeline, anchored = anchors(10.0, 40.0, 80.0), state = SpatialLocalizationState.Idle)
        assertEquals("tracking loss must clear, never freeze, the arrows", 0, pipeline.arrowCount())

        settled(pipeline, anchored = anchors(10.0, 40.0, 80.0))
        assertTrue("restored tracking must draw again", pipeline.arrowCount() > 0)
    }

    @Test
    fun notStartedNavigation_drawsNothing() {
        // Destination selected but navigation never started: the gate is off from the first
        // update, so route + progress + tracking together must still draw nothing.
        val pipeline = pipeline()
        update(pipeline, anchored = anchors(10.0, 40.0, 80.0), isNavigating = false)
        assertEquals("nothing may draw before Start", 0, pipeline.arrowCount())
        assertFalse(pipeline.usingAnchors)
    }
}
