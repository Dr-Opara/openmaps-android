package com.openmaps.app.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.tan

class GroundPlaneProjectorTest {
    private val k = CameraIntrinsics(500.0, 500.0, 180.0, 320.0, 360, 640, IntrinsicsSource.SensorGeometry)
    private val projector = GroundPlaneProjector(k, cameraHeightMeters = 1.25)
    private val level = CameraAttitude(0.0, 0.0)
    private fun pitched(deg: Double, rollDeg: Double = 0.0) = CameraAttitude(Math.toRadians(deg), Math.toRadians(rollDeg))

    @Test
    fun levelCamera_pointStraightAheadFollowsSimilarTriangles() {
        val p = projector.project(10.0, 0.0, level)!!
        assertEquals(180.0, p.u, 1e-9)
        assertEquals(320.0 + 500.0 * 1.25 / 10.0, p.v, 1e-9)
    }

    @Test
    fun levelCamera_lateralOffsetScalesWithDistance() {
        val near = projector.project(10.0, 1.8, level)!!
        val far = projector.project(20.0, 1.8, level)!!
        assertEquals(180.0 + 500.0 * 1.8 / 10.0, near.u, 1e-9)
        assertEquals(180.0 + 500.0 * 1.8 / 20.0, far.u, 1e-9)
        assertTrue("farther ground is higher in the image", far.v < near.v)
        assertTrue("ground is always below a level camera's horizon", far.v > 320.0)
    }

    @Test
    fun leftAndRightAreMirrorImages() {
        val a = pitched(8.0)
        val left = projector.project(15.0, -1.8, a)!!
        val right = projector.project(15.0, 1.8, a)!!
        assertEquals(left.v, right.v, 1e-9)
        assertEquals(180.0 - left.u, right.u - 180.0, 1e-9)
    }

    @Test
    fun pitchingDownRaisesTheHorizonByFocalTimesTanPitch() {
        val (l, r) = projector.horizon(pitched(10.0))!!
        val expected = 320.0 - 500.0 * tan(Math.toRadians(10.0))
        assertEquals(expected, l.v, 1e-6)
        assertEquals(expected, r.v, 1e-6)
        assertTrue(l.u < 180.0 && r.u > 180.0)
    }

    @Test
    fun levelHorizonPassesThroughThePrincipalPoint() {
        val (l, r) = projector.horizon(level)!!
        assertEquals(320.0, l.v, 1e-9)
        assertEquals(320.0, r.v, 1e-9)
    }

    @Test
    fun distantGroundConvergesOnTheHorizon() {
        val a = pitched(6.0)
        val horizonV = projector.horizon(a)!!.first.v
        val veryFar = projector.project(5_000.0, 0.0, a)!!
        assertEquals(horizonV, veryFar.v, 0.2)
        assertTrue(veryFar.v > horizonV)
    }

    @Test
    fun positiveRollLiftsTheRightEndOfTheHorizon() {
        // Right edge of the phone lower => the scene, and the horizon, tilt the other way on screen.
        val (l, r) = projector.horizon(pitched(0.0, rollDeg = 10.0))!!
        assertTrue(r.v < l.v)
        val (l2, r2) = projector.horizon(pitched(0.0, rollDeg = -10.0))!!
        assertTrue(r2.v > l2.v)
    }

    @Test
    fun rollRotatesAboutThePrincipalPointWithoutChangingDepth() {
        val flat = projector.project(12.0, 0.0, pitched(5.0))!!
        val rolled = projector.project(12.0, 0.0, pitched(5.0, rollDeg = 90.0))!!
        val radiusFlat = Math.hypot(flat.u - 180.0, flat.v - 320.0)
        val radiusRolled = Math.hypot(rolled.u - 180.0, rolled.v - 320.0)
        assertEquals(radiusFlat, radiusRolled, 1e-6)
    }

    @Test
    fun pointsBehindOrTooCloseAreNotProjected() {
        assertNull(projector.project(-5.0, 0.0, level))
        assertNull(projector.project(0.2, 0.0, level))
        // Looking steeply up: the ground ahead is behind the image plane.
        assertNull(projector.project(1.0, 0.0, pitched(-80.0)))
    }

    @Test
    fun horizonIsUndefinedLookingStraightDown() {
        assertNull(projector.horizon(pitched(89.5)))
    }

    @Test
    fun fillCenterCoversTheViewAndKeepsTheCentreCentred() {
        val t = FillCenterTransform(360, 640, 1080f, 2340f)
        // Height is the binding dimension: 2340/640 > 1080/360.
        assertEquals(1080f / 2f, t.toViewX(180.0), 1e-3f)
        assertEquals(2340f / 2f, t.toViewY(320.0), 1e-3f)
        assertEquals(0f, t.toViewY(0.0), 1e-3f)
        assertEquals(2340f, t.toViewY(640.0), 1e-3f)
        assertTrue("sides are cropped, so the image's left edge is off-screen", t.toViewX(0.0) < 0f)
    }

    @Test
    fun fillCenterWithMatchingAspectIsAPureScale() {
        val t = FillCenterTransform(360, 640, 720f, 1280f)
        assertEquals(0f, t.toViewX(0.0), 1e-3f)
        assertEquals(720f, t.toViewX(360.0), 1e-3f)
        assertEquals(200f, t.toViewY(100.0), 1e-3f)
    }

    @Test
    fun calibrationGridHasOrderedCrossLinesAndAHorizon() {
        val segments = CalibrationGrid.segments(projector, pitched(6.0))
        val cross = segments.filter { it.kind == GridSegment.Kind.Cross }
        assertEquals(CalibrationGrid.CROSS_DISTANCES_METERS.size, cross.size)
        // 5 m, 10 m, 20 m, 40 m: each farther line is higher on screen and narrower.
        for (i in 1 until cross.size) {
            assertTrue(cross[i].from.v < cross[i - 1].from.v)
            assertTrue(cross[i].to.u - cross[i].from.u < cross[i - 1].to.u - cross[i - 1].from.u)
        }
        val horizon = segments.single { it.kind == GridSegment.Kind.Horizon }
        assertTrue("the whole grid lies below the horizon", cross.all { it.from.v > horizon.from.v })
        assertNotNull(segments.firstOrNull { it.kind == GridSegment.Kind.Longitudinal })
    }

    @Test
    fun calibrationGridIsEmptyOfGroundLinesWhenTheCameraLooksAtTheSky() {
        val segments = CalibrationGrid.segments(projector, pitched(-90.0))
        assertTrue(segments.none { it.kind == GridSegment.Kind.Cross })
    }
}
