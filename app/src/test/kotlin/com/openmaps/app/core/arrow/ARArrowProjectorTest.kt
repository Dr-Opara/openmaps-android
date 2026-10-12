package com.openmaps.app.core.arrow

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.spatial.LocalizationQuality
import com.openmaps.app.core.spatial.SpatialPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Projection of sampled arrows into the camera view, and the quality hysteresis that keeps the
 * arrow set from flickering.
 *
 * Both are the Phase 7 contract in practice: if projection puts an arrow behind the driver or the
 * gate oscillates, the feature is unusable in a way no amount of correct geometry saves.
 */
class ARArrowProjectorTest {

    private val viewportWidth = 1080
    private val viewportHeight = 2400

    private fun pose(heading: Double) = SpatialPose(
        latitude = 0.0,
        longitude = 0.0,
        altitudeMeters = null,
        headingDegrees = heading,
        horizontalAccuracyMeters = 3.0,
        verticalAccuracyMeters = null,
        headingAccuracyDegrees = 2.0,
        quality = LocalizationQuality.Good,
    )

    private fun node(id: Int, lon: Double, lat: Double, distance: Double) = ARArrowNode(
        id = id,
        worldPosition = Coordinate(lat, lon),
        headingRadians = Math.toRadians(90.0),
        distanceAheadMeters = distance,
        visibility = true,
        scale = 26f,
        isApproachingManeuver = false,
    )

    @Test
    fun project_arrowAheadOfUser_landsOnScreen() {
        val projected = ARArrowProjector.project(
            nodes = listOf(node(0, lon = 0.0005, lat = 0.0, distance = 55.0)), // ~55 m east, facing east
            pose = pose(heading = 90.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertEquals(1, projected.size)
        assertTrue(projected.single().onScreen)
    }

    @Test
    fun project_arrowBehindUser_isCulled() {
        // Facing east; a point due west is behind and must never be drawn.
        val projected = ARArrowProjector.project(
            nodes = listOf(node(0, lon = -0.0005, lat = 0.0, distance = 55.0)),
            pose = pose(heading = 90.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertTrue("an arrow behind the driver must not be drawn", projected.isEmpty())
    }

    @Test
    fun project_returnsFarToNearSoNearerArrowsDrawOnTop() {
        val nodes = listOf(
            node(0, lon = 0.0003, lat = 0.0, distance = 33.0),
            node(1, lon = 0.0006, lat = 0.0, distance = 66.0),
            node(2, lon = 0.0009, lat = 0.0, distance = 100.0),
        )
        val projected = ARArrowProjector.project(nodes, pose(90.0), viewportWidth, viewportHeight)

        assertTrue(projected.size >= 2)
        val distances = projected.map { it.node.distanceAheadMeters }
        assertEquals("far-to-near ordering", distances.sortedDescending(), distances)
    }

    @Test
    fun project_invisibleNodesAreSkipped() {
        val hidden = node(0, lon = 0.0005, lat = 0.0, distance = 55.0).copy(visibility = false)
        val projected = ARArrowProjector.project(listOf(hidden), pose(90.0), viewportWidth, viewportHeight)
        assertTrue(projected.isEmpty())
    }

    @Test
    fun project_zeroViewportIsSafe() {
        val projected = ARArrowProjector.project(
            nodes = listOf(node(0, lon = 0.0005, lat = 0.0, distance = 55.0)),
            pose = pose(90.0),
            viewportWidthPx = 0,
            viewportHeightPx = 0,
        )
        assertTrue(projected.isEmpty())
    }

    @Test
    fun project_emptyInputIsSafe() {
        assertNotNull(ARArrowProjector.project(emptyList(), pose(0.0), viewportWidth, viewportHeight))
    }

    @Test
    fun project_scaleComesFromTheNodeSoDistanceAttenuationIsPreserved() {
        val big = node(0, lon = 0.0003, lat = 0.0, distance = 10.0).copy(scale = 50f)
        val small = node(1, lon = 0.0010, lat = 0.0, distance = 110.0).copy(scale = 12f)
        val projected = ARArrowProjector.project(listOf(big, small), pose(90.0), viewportWidth, viewportHeight)
        assertEquals(50f, projected.first { it.node.distanceAheadMeters == 10.0 }.radiusPx, 0.01f)
        assertEquals(12f, projected.first { it.node.distanceAheadMeters == 110.0 }.radiusPx, 0.01f)
    }
}

/**
 * The quality gate exists purely to stop the arrow set flickering, so the tests are about stability
 * rather than about geometry.
 */
class ARArrowQualityGateTest {

    private val config = ARArrowConfig()

    @Test
    fun gate_startsUnavailable() {
        assertEquals(LocalizationQuality.Unavailable, ARArrowQualityGate(config).applied)
    }

    @Test
    fun gate_improvingQualityIsNotAppliedImmediately() {
        val gate = ARArrowQualityGate(config)
        // A single good frame after a bad fix must not pop arrows in — that is the flicker.
        assertEquals(LocalizationQuality.Unavailable, gate.observe(LocalizationQuality.Good))
        repeat(config.qualityHysteresisSteps - 1) { gate.observe(LocalizationQuality.Good) }
        assertEquals(LocalizationQuality.Good, gate.observe(LocalizationQuality.Good))
    }

    @Test
    fun gate_degradingQualityAppliesQuicklyButNotInstantly() {
        val gate = ARArrowQualityGate(config)
        repeat(config.qualityHysteresisSteps) { gate.observe(LocalizationQuality.Good) }
        assertEquals(LocalizationQuality.Good, gate.applied)

        // One bad frame must NOT be enough — that was the original flicker.
        assertEquals(
            "a single degraded frame must not thin the arrows",
            LocalizationQuality.Good,
            gate.observe(LocalizationQuality.Fair),
        )
        // Two consecutive does apply, so a genuinely lost fix drops arrows promptly.
        assertEquals(LocalizationQuality.Fair, gate.observe(LocalizationQuality.Fair))
    }

    @Test
    fun gate_losingTheFixComesDownFast() {
        val gate = ARArrowQualityGate(config)
        repeat(config.qualityHysteresisSteps) { gate.observe(LocalizationQuality.Good) }

        assertEquals(LocalizationQuality.Good, gate.observe(LocalizationQuality.Unavailable))
        assertEquals(LocalizationQuality.Unavailable, gate.observe(LocalizationQuality.Unavailable))
    }

    @Test
    fun gate_oscillatingQualityDoesNotFlickerTheAppliedValue() {
        val gate = ARArrowQualityGate(config)
        repeat(config.qualityHysteresisSteps) { gate.observe(LocalizationQuality.Good) }
        assertEquals(LocalizationQuality.Good, gate.applied)

        // Quality wobbles Good/Fair every frame. It must never actually change without persisting.
        repeat(40) {
            gate.observe(if (it % 2 == 0) LocalizationQuality.Fair else LocalizationQuality.Good)
        }
        assertEquals("a wobbling signal must not change the applied quality", LocalizationQuality.Good, gate.applied)
    }

    @Test
    fun gate_resetDropsPendingCandidate() {
        val gate = ARArrowQualityGate(config)
        gate.observe(LocalizationQuality.Good)
        gate.reset(LocalizationQuality.Unavailable)
        // A fresh single frame must not immediately restore Good.
        assertEquals(LocalizationQuality.Unavailable, gate.observe(LocalizationQuality.Good))
    }

    @Test
    fun gate_steadyGoodSustainsAcrossManyFrames() {
        val gate = ARArrowQualityGate(config)
        repeat(config.qualityHysteresisSteps) { gate.observe(LocalizationQuality.Good) }
        repeat(100) { assertEquals(LocalizationQuality.Good, gate.observe(LocalizationQuality.Good)) }
    }
}