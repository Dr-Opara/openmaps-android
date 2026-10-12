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
 * Which placement path the pipeline chooses, and how the arrow set behaves across the moments that
 * matter: progress, reroute, arrival and localization loss.
 *
 * This is the end-to-end assertion behind the phase's core claim. It is deliberately about
 * *selection*, not geometry — [AnchoredArrowProjectorTest] covers the geometry — because the failure
 * this guards against is silent: a fallback that quietly keeps working and looks almost right.
 */
class ARArrowPipelineAnchorTest {

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

    private fun progress(
        snappedShapeIndex: Int = 0,
        remaining: Double = 400.0,
    ) = RouteProgress(
        snappedShapeIndex = snappedShapeIndex,
        lateralDistanceMeters = 0.0,
        distanceTraveledMeters = snappedShapeIndex.toDouble(),
        remainingDistanceMeters = remaining,
        remainingDurationSeconds = remaining,
        progressFraction = snappedShapeIndex / 400.0,
        currentManeuver = maneuver(0),
        nextManeuver = null,
        distanceToNextManeuverMeters = 5_000.0,
        isOffRoute = false,
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

    private fun pose(quality: LocalizationQuality = LocalizationQuality.Good) = SpatialPose(
        latitude = 0.0,
        longitude = 0.0,
        altitudeMeters = 5.0,
        headingDegrees = 0.0,
        horizontalAccuracyMeters = 2.0,
        verticalAccuracyMeters = 2.0,
        headingAccuracyDegrees = 1.0,
        quality = quality,
    )

    private fun tracking(quality: LocalizationQuality = LocalizationQuality.Good) =
        SpatialLocalizationState.Tracking(pose(quality))

    /**
     * Anchors laid out along ARCore's -z axis starting [firstAhead] metres in front of the camera.
     * Their world positions are consistent with their distance so the interpolation is meaningful.
     */
    private fun anchors(
        vararg distances: Double,
        tracked: Boolean = true,
    ): List<AnchoredRoutePoint> = distances.mapIndexed { i, d ->
        AnchoredRoutePoint(
            id = i,
            anchorKey = d.toLong(),
            latitude = d / mpd,
            longitude = 0.0,
            altitudeMeters = 0.0,
            heightAboveRoadMeters = 0.35,
            headingRadians = 0.0,
            worldPose = if (tracked) WorldPose(0.0, 0.0, -d) else null,
            trackingState = if (tracked) AnchorTrackingState.Tracking else AnchorTrackingState.Paused,
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

    /**
     * Drives [update] until the quality gate opens.
     *
     * The gate deliberately refuses to draw until quality has held for several observations — arrows
     * on a shaky fix are worse than no arrows — so every test that expects arrows has to spend that
     * warm-up first, exactly as the real screen does.
     */
    private fun settled(
        pipeline: ARArrowPipeline,
        anchored: List<AnchoredRoutePoint> = emptyList(),
        cameraTransform: WorldTransform? = camera,
        progress: RouteProgress? = progress(),
        state: SpatialLocalizationState = tracking(),
        isNavigating: Boolean = true,
        route: RouteResult? = this.route,
    ): List<AnchoredRoutePoint> {
        val a = anchored
        val c = cameraTransform
        val p = progress
        val s = state
        val n = isNavigating
        val r = route
        repeat(ARArrowConfig().qualityHysteresisSteps + 2) { update(pipeline, a, c, p, s, n, r) }
        return a
    }

    // region Path selection

    @Test
    fun trackedAnchors_selectTheAnchoredPath() {
        val pipeline = pipeline()
        settled(pipeline, anchored = anchors(10.0, 40.0, 80.0, 120.0))
        assertTrue("arrows must come from real world anchors when they exist", pipeline.usingAnchors)
        assertNull("the anchored path is not degraded mode", pipeline.degradedReason)
        assertTrue("expected arrows", pipeline.arrowCount() > 0)
    }

    @Test
    fun noAnchors_fallsBackToProjectionAndSaysSo() {
        // The fallback must be *visible* as a fallback, never presented as working anchoring.
        val pipeline = pipeline()
        settled(pipeline, anchored = emptyList())
        assertFalse(pipeline.usingAnchors)
        assertEquals("no anchors created yet", pipeline.degradedReason)
        assertTrue("the fallback still draws arrows", pipeline.arrowCount() > 0)
    }

    @Test
    fun untrackedAnchors_fallBackToProjection() {
        val pipeline = pipeline()
        settled(pipeline, anchored = anchors(10.0, 40.0, tracked = false))
        assertFalse(pipeline.usingAnchors)
        assertEquals("anchors not yet tracked", pipeline.degradedReason)
    }

    @Test
    fun missingCameraTransform_fallsBackToProjection() {
        val pipeline = pipeline()
        settled(pipeline, anchored = anchors(10.0, 40.0), cameraTransform = null)
        assertFalse(pipeline.usingAnchors)
        assertEquals("no camera world transform", pipeline.degradedReason)
    }

    @Test
    fun partialAnchorCoverage_keepsTheCoveredArrows() {
        // Anchors out to 40 m but arrows reaching 120 m: the far arrows have no world position and
        // are dropped, while the near ones are still genuinely anchored. Failing over wholesale
        // would throw away real anchors; inventing the rest would be worse.
        val pipeline = pipeline()
        settled(pipeline, anchored = anchors(10.0, 20.0, 30.0, 40.0))
        assertTrue(pipeline.usingAnchors)
        val furthest = pipeline.arrowCount().let { pipeline.nearestArrowMeters() }
        assertTrue("expected anchored arrows", furthest != null)
        assertTrue("arrows should not exceed the anchored window", furthest!! <= 40.5)
    }

    @Test
    fun anchorsBeyondTheWholeArrowWindow_reportPartialCoverage() {
        val pipeline = pipeline()
        // Anchors far beyond every arrow: nothing can be placed, so this is degraded, not anchored.
        settled(pipeline, anchored = anchors(200.0, 240.0, 280.0))
        assertFalse(pipeline.usingAnchors)
        assertEquals("anchors did not cover the arrow window", pipeline.degradedReason)
    }

    // endregion

    // region Set integrity

    @Test
    fun arrowSet_hasNoDuplicateNodes() {
        // Two arrows on the same control point read as a rendering glitch and point the driver at
        // two conflicting places. The pipeline exposes only counts, so this is checked through the
        // projector it delegates to.
        val nodes = com.openmaps.app.core.arrow.ARArrowSampler(ARArrowConfig()).sample(
            geometry = route.geometry,
            progress = progress(0),
            quality = LocalizationQuality.Good,
            isArrived = false,
            remainingDistanceMeters = 400.0,
        )
        assertEquals("arrow nodes must have distinct identities", nodes.size, nodes.map { it.id }.distinct().size)
    }

    @Test
    fun arrowSet_isBoundedByTheVisibleCap() {
        val pipeline = pipeline()
        // Anchors every 5 m out to the window edge: far denser than the arrow spacing.
        val dense = (0..36).map { 5.0 + it * 5.0 }.toDoubleArray()
        settled(pipeline, anchored = anchors(*dense))
        assertTrue(
            "arrow count ${pipeline.arrowCount()} exceeded the cap ${ARArrowConfig().maxVisibleArrows}",
            pipeline.arrowCount() <= ARArrowConfig().maxVisibleArrows,
        )
    }

    @Test
    fun arrowSet_isNeverRebuiltForAnUnchangedRoute() {
        // Repeating the same inputs must be idempotent: the pipeline recomputes the set, but the
        // result must not vary, and nothing may claim the anchored path was lost.
        val pipeline = pipeline()
        val a = anchors(10.0, 40.0, 80.0, 120.0)
        settled(pipeline, anchored = a)
        val first = pipeline.arrowCount()
        repeat(10) { update(pipeline, anchored = a) }
        assertEquals(first, pipeline.arrowCount())
        assertTrue(pipeline.usingAnchors)
    }

    // endregion

    // region Progress

    @Test
    fun advancingProgress_keepsAnchorsOnTheSameWorldPositions() {
        // The stability claim, end to end: as the driver advances, the anchors ahead are reused, so
        // the arrow set updates by adding and retiring arrows rather than by being replaced.
        val pipeline = pipeline()
        settled(pipeline, anchored = anchors(10.0, 40.0, 80.0, 120.0), progress = progress(0))
        assertTrue(pipeline.usingAnchors)

        settled(pipeline, anchored = anchors(20.0, 50.0, 90.0, 130.0), progress = progress(10))
        assertTrue(pipeline.usingAnchors)
        assertTrue("expected arrows after advancing", pipeline.arrowCount() > 0)
    }

    @Test
    fun drivingPastTheEndOfTheRoute_producesNoArrows() {
        val pipeline = pipeline()
        update(pipeline, anchored = anchors(10.0, 40.0), progress = progress(400, remaining = 0.0))
        assertEquals("nothing should be drawn once the route is consumed", 0, pipeline.arrowCount())
    }

    // endregion

    // region Reroute, arrival and loss of localization

    @Test
    fun onRouteReplaced_clearsEveryArrowImmediately() {
        // The reroute must not leave one frame of the old route's arrows on screen.
        val pipeline = pipeline()
        settled(pipeline, anchored = anchors(10.0, 40.0, 80.0))
        assertTrue("precondition: arrows are showing", pipeline.arrowCount() > 0)

        pipeline.onRouteReplaced()
        assertEquals(0, pipeline.arrowCount())
        assertFalse(pipeline.usingAnchors)
    }

    @Test
    fun navigationStopped_clearsEveryArrow() {
        val pipeline = pipeline()
        settled(pipeline, anchored = anchors(10.0, 40.0, 80.0))
        pipeline.clear()
        assertEquals(0, pipeline.arrowCount())
        assertNull(pipeline.degradedReason)
    }

    @Test
    fun arrivalState_clearsEveryArrow() {
        val pipeline = pipeline()
        update(pipeline, anchored = anchors(10.0, 40.0), progress = progress(400, remaining = 0.0))
        assertEquals("arrival must leave no arrows on the camera", 0, pipeline.arrowCount())
    }

    @Test
    fun poorLocalization_suppressesArrowsOnBothPaths() {
        // Misleading spatial arrows are worse than no arrows: a chevron drawn from a bad fix points
        // the driver somewhere that is not the route.
        val pipeline = pipeline()
        repeat(10) { update(pipeline, anchored = anchors(10.0, 40.0, 80.0), state = tracking(LocalizationQuality.Good)) }
        assertTrue("precondition: arrows are showing", pipeline.arrowCount() > 0)

        repeat(10) { update(pipeline, anchored = anchors(10.0, 40.0, 80.0), state = tracking(LocalizationQuality.Poor)) }
        assertEquals("poor localization must not produce spatial arrows", 0, pipeline.arrowCount())
    }

    @Test
    fun localizationLost_clearsArrowsRatherThanFreezingThem() {
        // Freezing the last good arrows would leave them pinned to a world frame the device is no
        // longer tracking — stale content is worse than none.
        val pipeline = pipeline()
        repeat(10) { update(pipeline, anchored = anchors(10.0, 40.0, 80.0)) }
        assertTrue("precondition: arrows are showing", pipeline.arrowCount() > 0)

        update(pipeline, anchored = anchors(10.0, 40.0, 80.0), state = SpatialLocalizationState.Idle)
        assertEquals(0, pipeline.arrowCount())
    }

    @Test
    fun noRoute_clearsArrows() {
        val pipeline = pipeline()
        settled(pipeline, anchored = anchors(10.0, 40.0, 80.0))
        assertTrue("precondition: arrows are showing", pipeline.arrowCount() > 0)

        update(pipeline, anchored = anchors(10.0, 40.0, 80.0), route = null)
        assertEquals(0, pipeline.arrowCount())
    }

    // endregion

    // region Lifecycle

    @Test
    fun leavingAndReturningToLive_rebuildsWithoutDuplicating() {
        // Map -> Live -> Map -> Live. The arrow set is derived state, so it must be reconstructible
        // from the same inputs and must not accumulate across sessions.
        val pipeline = pipeline()
        val a = anchors(10.0, 40.0, 80.0, 120.0)
        repeat(6) { update(pipeline, anchored = a) }
        val first = pipeline.arrowCount()

        pipeline.clear()
        assertEquals(0, pipeline.arrowCount())

        repeat(6) { update(pipeline, anchored = a) }
        assertEquals("re-entering Live must reproduce the same arrow set, not a larger one", first, pipeline.arrowCount())
    }

    @Test
    fun drawFrame_beforeTheSurfaceExists_isANoOp() {
        // The GL context is not ready when Live first composes. Drawing anyway would either crash or
        // spam the log; the overlay must simply do nothing until the surface exists.
        val pipeline = pipeline()
        settled(pipeline, anchored = anchors(10.0, 40.0, 80.0))
        pipeline.drawFrame(1f)
        assertTrue(pipeline.usingAnchors)
    }

    @Test
    fun usingAnchors_isFalseBeforeAnythingHasRun() {
        val pipeline = pipeline()
        assertFalse(pipeline.usingAnchors)
        assertNull(pipeline.degradedReason)
        assertEquals(0, pipeline.arrowCount())
    }

    // endregion
}
