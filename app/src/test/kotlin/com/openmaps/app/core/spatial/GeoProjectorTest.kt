package com.openmaps.app.core.spatial

import com.openmaps.app.core.routing.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The projection seam Phase 7 builds road-anchored arrows on.
 *
 * These are deliberately geometric and hand-checkable: if `project()` is wrong, every future AR
 * overlay is wrong, and on a real device that is far harder to debug than a JVM assertion. No ARCore
 * and no device are involved.
 */
class GeoProjectorTest {

    private val viewportWidth = 1080
    private val viewportHeight = 2400

    private fun pose(headingDegrees: Double, lat: Double = 0.0, lon: Double = 0.0) = SpatialPose(
        latitude = lat,
        longitude = lon,
        altitudeMeters = null,
        headingDegrees = headingDegrees,
        horizontalAccuracyMeters = 3.0,
        verticalAccuracyMeters = null,
        headingAccuracyDegrees = 2.0,
        quality = LocalizationQuality.Good,
    )

    @Test
    fun pointDirectlyAheadLandsAtScreenCenter() {
        // Facing north (0 deg); a point due north of the user is dead ahead -> horizontal centre.
        val point = GeoProjector.project(
            world = Coordinate(latitude = 0.001, longitude = 0.0), // ~111 m north
            userPose = pose(headingDegrees = 0.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )

        assertNotNull(point)
        assertTrue(point!!.visible)
        assertEquals(viewportWidth / 2.0, point.x.toDouble(), 1.0)
    }

    @Test
    fun pointToTheRightLandsRightOfCenter() {
        // Facing north; a point ~20 deg to the right is inside the 60 deg field of view, so it must
        // render to the right of centre AND on screen.
        val point = GeoProjector.project(
            world = Coordinate(latitude = 0.001, longitude = 0.000364), // ~20 deg right of north
            userPose = pose(headingDegrees = 0.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )

        assertNotNull(point)
        assertEquals(20.0, point!!.bearingDegrees, 0.5)
        assertTrue("a point to the right must render right of centre", point.x > viewportWidth / 2.0)
        assertTrue(point.visible)
        assertTrue("inside the field of view means on screen", point.onScreen)
    }

    @Test
    fun pointBeyondTheFieldOfViewIsVisibleButOffScreen() {
        // 45 deg off-axis is inside the 90 deg forward half-plane but outside the 60 deg field of
        // view, so it must be reported visible-but-off-screen rather than clamped to the bezel.
        val point = GeoProjector.project(
            world = Coordinate(latitude = 0.001, longitude = 0.001), // NE, 45 deg off-axis
            userPose = pose(headingDegrees = 0.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )

        assertNotNull(point)
        assertTrue(point!!.visible)
        assertFalse("past the field of view is off screen", point.onScreen)
        assertTrue(point.x > viewportWidth)
    }

    @Test
    fun pointToTheLeftLandsLeftOfCenter() {
        val point = GeoProjector.project(
            world = Coordinate(latitude = 0.001, longitude = -0.000364), // ~20 deg left of north
            userPose = pose(headingDegrees = 0.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertNotNull(point)
        assertTrue(point!!.x < viewportWidth / 2.0)
        assertTrue(point.visible)
        assertTrue(point.onScreen)
    }

    @Test
    fun pointExactlyNinetyDegreesToTheSideIsNotVisible() {
        // On the camera plane: projects to infinity, so it must be reported not-visible rather than
        // being clamped to a screen edge (which would pin a phantom arrow to the bezel).
        val point = GeoProjector.project(
            world = Coordinate(latitude = 0.0, longitude = 0.001), // due east, ~111 m out
            userPose = pose(headingDegrees = 0.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertNotNull(point)
        assertFalse(point!!.visible)
        assertFalse(point.onScreen)
    }

    @Test
    fun pointBehindIsMarkedNotVisible() {
        // Facing north; a point due south is behind and must never be drawn as an arrow ahead.
        val point = GeoProjector.project(
            world = Coordinate(latitude = -0.001, longitude = 0.0),
            userPose = pose(headingDegrees = 0.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )

        assertNotNull(point)
        assertFalse("behind the device must not be visible", point!!.visible)
        assertFalse(point.onScreen)
    }

    @Test
    fun rotationIsRelativeToHeadingNotNorth() {
        // A north-east point. Facing north-east (45 deg) it is dead ahead...
        val world = Coordinate(latitude = 0.001, longitude = 0.001)
        val ahead = GeoProjector.project(
            world = world,
            userPose = pose(headingDegrees = 45.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertNotNull(ahead)
        assertEquals(viewportWidth / 2.0, ahead!!.x.toDouble(), 1.0)

        // ...while facing north it is 45 deg to the right.
        val toTheRight = GeoProjector.project(
            world = world,
            userPose = pose(headingDegrees = 0.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertNotNull(toTheRight)
        assertTrue(toTheRight!!.x > viewportWidth / 2.0)
        assertTrue(toTheRight.visible)
    }

    @Test
    fun distanceIsReportedInMeters() {
        val point = GeoProjector.project(
            world = Coordinate(latitude = 0.001, longitude = 0.0),
            userPose = pose(headingDegrees = 0.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertNotNull(point)
        // 0.001 degrees latitude is ~111.32 m.
        assertEquals(111.32, point!!.distanceMeters, 0.5)
    }

    @Test
    fun samePointZeroDistanceIsHandledWithoutCrashing() {
        val point = GeoProjector.project(
            world = Coordinate(latitude = 0.0, longitude = 0.0),
            userPose = pose(headingDegrees = 0.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        // Degenerate (point == user): must not divide by zero or throw.
        assertNotNull(point)
        assertEquals(0.0, point!!.distanceMeters, 1e-6)
    }

    @Test
    fun pointAtTheUserIsNotRenderable() {
        val point = GeoProjector.project(
            world = Coordinate(latitude = 0.0, longitude = 0.0),
            userPose = pose(headingDegrees = 0.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )
        assertNotNull(point)
        // Too close to project meaningfully; caller must not draw here.
        assertFalse(point!!.visible)
    }

    @Test
    fun zeroWidthViewportReturnsNullRatherThanDividingByZero() {
        val point = GeoProjector.project(
            world = Coordinate(latitude = 0.001, longitude = 0.0),
            userPose = pose(headingDegrees = 0.0),
            viewportWidthPx = 0,
            viewportHeightPx = viewportHeight,
        )
        assertNull(point)
    }

    @Test
    fun fartherPointProjectsFartherFromCenter() {
        val near = GeoProjector.project(
            world = Coordinate(latitude = 0.001, longitude = 0.0),
            userPose = pose(headingDegrees = 0.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )!!
        val far = GeoProjector.project(
            // Same bearing (north) but rotated 20 deg so it is off-axis and its offset grows with range.
            world = Coordinate(latitude = 0.010, longitude = 0.003),
            userPose = pose(headingDegrees = 0.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )!!

        assertTrue(far.distanceMeters > near.distanceMeters)
        assertTrue(
            "a more distant off-axis point must be further from centre",
            kotlin.math.abs(far.x - viewportWidth / 2.0) > kotlin.math.abs(near.x - viewportWidth / 2.0),
        )
    }

    @Test
    fun enuOffsetIsCorrectAtEquatorAndAwayFromIt() {
        val (east, north) = GeoProjector.enuOffsetMeters(
            world = Coordinate(latitude = 0.001, longitude = 0.001),
            userLatitude = 0.0,
            userLongitude = 0.0,
        )
        assertEquals(111.32, north, 0.5)
        assertEquals(111.32, east, 0.5)

        // At 60 deg latitude, a degree of longitude is roughly half as wide.
        val (east60, _) = GeoProjector.enuOffsetMeters(
            world = Coordinate(latitude = 60.0, longitude = 0.001),
            userLatitude = 60.0,
            userLongitude = 0.0,
        )
        assertTrue("longitude degrees narrow toward the poles", east60 < 60.0)
    }

    @Test
    fun projectionIsStableAcrossTheZeroThreeSixtySeam() {
        // Heading 359 vs 1 degree must give near-identical geometry (no discontinuity at north).
        val nearNorth = GeoProjector.project(
            world = Coordinate(latitude = 0.001, longitude = 0.00005),
            userPose = pose(headingDegrees = 359.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )!!
        val justPastNorth = GeoProjector.project(
            world = Coordinate(latitude = 0.001, longitude = 0.00005),
            userPose = pose(headingDegrees = 1.0),
            viewportWidthPx = viewportWidth,
            viewportHeightPx = viewportHeight,
        )!!

        assertEquals(
            "a 2 degree heading change must not flip the projection",
            nearNorth.x,
            justPastNorth.x,
            40f,
        )
    }
}