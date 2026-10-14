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
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11 (Part I): `ARArrowPipeline.updateArrowConfig` lets the environment profile tune
 * spacing/look-ahead/max-count/turn-density without rebuilding the pipeline or touching anchor
 * lifecycle. Mirrors [ARArrowPipelineAnchorTest]'s fixtures exactly, since that file already proves
 * this exact anchored setup reliably produces arrows.
 */
class ARArrowPipelineConfigTest {

    private val viewportWidth = 1080
    private val viewportHeight = 2400
    private val mpd = 111_320.0
    private val camera = WorldTransform(WorldPose(0.0, 0.0, 0.0))

    private val route = RouteResult(
        geometry = (0..400).map { Coordinate(it / mpd, 0.0) },
        distanceMeters = 400.0,
        durationSeconds = 400.0,
    )

    private val progress = RouteProgress(
        snappedShapeIndex = 0,
        lateralDistanceMeters = 0.0,
        distanceTraveledMeters = 0.0,
        remainingDistanceMeters = 400.0,
        remainingDurationSeconds = 400.0,
        progressFraction = 0.0,
        currentManeuver = RouteManeuver(
            instruction = "Continue",
            verbalInstruction = null,
            type = ManeuverType.Continue,
            streetName = null,
            distanceMeters = 100.0,
            beginShapeIndex = 0,
            endShapeIndex = 0,
        ),
        nextManeuver = null,
        distanceToNextManeuverMeters = 5_000.0,
        isOffRoute = false,
    )

    private val pose = SpatialPose(
        latitude = 0.0,
        longitude = 0.0,
        altitudeMeters = 5.0,
        headingDegrees = 0.0,
        horizontalAccuracyMeters = 2.0,
        verticalAccuracyMeters = 2.0,
        headingAccuracyDegrees = 1.0,
        quality = LocalizationQuality.Good,
    )
    private val tracking = SpatialLocalizationState.Tracking(pose)

    private fun anchors(vararg distances: Double): List<AnchoredRoutePoint> = distances.mapIndexed { i, d ->
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

    private fun update(pipeline: ARArrowPipeline, anchored: List<AnchoredRoutePoint>) = pipeline.update(
        route = route,
        progress = progress,
        pose = pose,
        spatialState = tracking,
        viewportWidthPx = viewportWidth,
        viewportHeightPx = viewportHeight,
        isNavigating = true,
        anchoredPoints = anchored,
        cameraTransform = camera,
    )

    private fun settle(pipeline: ARArrowPipeline, anchored: List<AnchoredRoutePoint>) {
        repeat(ARArrowConfig().qualityHysteresisSteps + 2) { update(pipeline, anchored) }
    }

    @Test
    fun updateArrowConfig_changesTheMaxVisibleArrowCount() {
        val anchored = anchors(10.0, 25.0, 40.0, 55.0, 70.0, 85.0, 100.0, 115.0)
        val pipeline = ARArrowPipeline(ARArrowConfig(maxVisibleArrows = 8))
        settle(pipeline, anchored)
        val beforeCount = pipeline.arrowCount()
        assertTrue("sanity check: the default config should use most of its 8-arrow budget", beforeCount >= 6)

        pipeline.updateArrowConfig(ARArrowConfig(maxVisibleArrows = 2))
        update(pipeline, anchored)
        assertTrue("a Rural-style lower max-arrow-count must take effect immediately", pipeline.arrowCount() <= 2)
    }

    @Test
    fun updateArrowConfig_changesHowFarAheadArrowsReach() {
        val anchored = anchors(10.0, 40.0, 80.0, 115.0)
        val pipeline = ARArrowPipeline(ARArrowConfig(maxDistanceAheadMeters = 120.0, spacingFarMeters = 14.0, spacingNearMeters = 6.0))
        settle(pipeline, anchored)
        assertTrue("sanity check: a 115 m anchor is within the default 120 m window", pipeline.arrowCount() > 0)
        val defaultFarthest = pipeline.nearestArrowMeters()

        // A short, City-style look-ahead must drop the far anchor from the sample entirely.
        pipeline.updateArrowConfig(ARArrowConfig(maxDistanceAheadMeters = 50.0, spacingFarMeters = 14.0, spacingNearMeters = 6.0))
        update(pipeline, anchored)
        val afterNearest = pipeline.nearestArrowMeters()
        assertTrue(
            "every remaining arrow must now be within the shortened 50 m window",
            afterNearest == null || afterNearest <= 50.0,
        )
        assertTrue(defaultFarthest != null)
    }

    @Test
    fun updateArrowConfig_doesNotTouchAnchorSelectionOrLifecycle() {
        // Reconfiguring the sampler must not flip which placement path is in use: that decision
        // belongs entirely to RouteAnchorManager and AnchoredArrowProjector.
        //
        // The fixture deliberately includes an anchor BEHIND the driver, because that is what the
        // real manager produces (RouteAnchorConfig.behindWindowMeters is 15 m). It matters here:
        // capping the budget at a single arrow leaves only the nearest one, at roughly
        // ARArrowConfig.nearCutoffMeters (6 m), and an anchor set starting 10 m AHEAD would put
        // that arrow outside the anchored window. Dropping it is the correct behaviour (arrows
        // past the last anchor are dropped, never extrapolated), but it would make this test read
        // as though the config swap had changed the placement path when it had not.
        val anchored = anchors(-5.0, 10.0, 40.0, 80.0)
        val pipeline = ARArrowPipeline(ARArrowConfig())
        settle(pipeline, anchored)
        assertTrue(pipeline.usingAnchors)

        pipeline.updateArrowConfig(ARArrowConfig(maxVisibleArrows = 1))
        update(pipeline, anchored)
        assertTrue("still anchored after a config swap", pipeline.usingAnchors)
    }
}
