package com.openmaps.app.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class CameraCalibrationTest {
    private val eps = 1e-6

    @Test
    fun widerImageThanSensorUsesTheFullSensorWidth() {
        // 4:3 sensor, 16:9 buffer: the buffer is cropped top and bottom, never left and right.
        val k = CameraIntrinsics.fromSensorGeometry(5.4, 7.2, 5.4, 640, 360)!!
        assertEquals(480.0, k.focalXPixels, eps)
        assertEquals(480.0, k.focalYPixels, eps)
        assertEquals(320.0, k.principalXPixels, eps)
        assertEquals(180.0, k.principalYPixels, eps)
        assertEquals(IntrinsicsSource.SensorGeometry, k.source)
    }

    @Test
    fun narrowerImageThanSensorUsesTheFullSensorHeight() {
        val k = CameraIntrinsics.fromSensorGeometry(4.0, 6.4, 3.6, 640, 480)!!
        assertEquals(4.0 * 480 / 3.6, k.focalXPixels, eps)
    }

    @Test
    fun sensorSizeReportedPortraitIsNormalised() {
        val landscape = CameraIntrinsics.fromSensorGeometry(5.4, 7.2, 5.4, 640, 360)!!
        val portrait = CameraIntrinsics.fromSensorGeometry(5.4, 5.4, 7.2, 640, 360)!!
        assertEquals(landscape.focalXPixels, portrait.focalXPixels, eps)
    }

    @Test
    fun missingOrNonsenseGeometryYieldsNoIntrinsics() {
        assertNull(CameraIntrinsics.fromSensorGeometry(0.0, 7.2, 5.4, 640, 360))
        assertNull(CameraIntrinsics.fromSensorGeometry(5.4, -1.0, 5.4, 640, 360))
        assertNull(CameraIntrinsics.fromSensorGeometry(5.4, 7.2, 5.4, 0, 360))
    }

    @Test
    fun fieldOfViewMatchesTheFocalLength() {
        val k = CameraIntrinsics.fromSensorGeometry(5.4, 7.2, 5.4, 640, 360)!!
        // tan(hfov/2) = (w/2)/fx = 320/480
        assertEquals(Math.toDegrees(2 * Math.atan(320.0 / 480.0)), k.horizontalFovDegrees, eps)
    }

    @Test
    fun rotatingUprightSwapsAxesAndMovesThePrincipalPoint() {
        val k = CameraIntrinsics(500.0, 510.0, 300.0, 170.0, 640, 360, IntrinsicsSource.SensorGeometry)
        val r90 = k.rotatedUpright(90)
        assertEquals(360, r90.imageWidth)
        assertEquals(640, r90.imageHeight)
        assertEquals(510.0, r90.focalXPixels, eps)
        assertEquals(500.0, r90.focalYPixels, eps)
        assertEquals(360 - 170.0, r90.principalXPixels, eps)
        assertEquals(300.0, r90.principalYPixels, eps)

        val r270 = k.rotatedUpright(270)
        assertEquals(170.0, r270.principalXPixels, eps)
        assertEquals(640 - 300.0, r270.principalYPixels, eps)

        val r180 = k.rotatedUpright(180)
        assertEquals(340.0, r180.principalXPixels, eps)
        assertEquals(190.0, r180.principalYPixels, eps)
        assertEquals(k, k.rotatedUpright(0))
        assertEquals(k, k.rotatedUpright(360))
    }

    @Test
    fun fourQuarterTurnsReturnTheOriginalCamera() {
        val k = CameraIntrinsics(500.0, 510.0, 300.0, 170.0, 640, 360, IntrinsicsSource.SensorGeometry)
        assertEquals(k, k.rotatedUpright(90).rotatedUpright(90).rotatedUpright(90).rotatedUpright(90))
        assertEquals(k.rotatedUpright(270), k.rotatedUpright(-90))
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonQuarterTurnIsRejected() {
        CameraIntrinsics(500.0, 500.0, 320.0, 180.0, 640, 360, IntrinsicsSource.SensorGeometry).rotatedUpright(45)
    }

    // --- attitude -------------------------------------------------------------------------------

    /** Row-major device-to-world matrix from the world directions of the device x, y, z axes. */
    private fun matrix(x: DoubleArray, y: DoubleArray, z: DoubleArray) = floatArrayOf(
        x[0].toFloat(), y[0].toFloat(), z[0].toFloat(),
        x[1].toFloat(), y[1].toFloat(), z[1].toFloat(),
        x[2].toFloat(), y[2].toFloat(), z[2].toFloat(),
    )

    private fun deg(d: Double) = Math.toRadians(d)

    @Test
    fun uprightPortraitPhoneFacingTheHorizonIsLevel() {
        // x east, y up, z towards the user (south): the rear camera looks north at the horizon.
        val a = CameraAttitude.fromRotationMatrix(
            matrix(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(0.0, -1.0, 0.0)),
            displayRotationDegrees = 0,
        )!!
        assertEquals(0.0, a.pitchDownRadians, 1e-6)
        assertEquals(0.0, a.rollRadians, 1e-6)
    }

    @Test
    fun tippingTheTopOfThePhoneForwardPitchesTheCameraDown() {
        val t = deg(20.0)
        val a = CameraAttitude.fromRotationMatrix(
            matrix(doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, sin(t), cos(t)), doubleArrayOf(0.0, -cos(t), sin(t))),
            displayRotationDegrees = 0,
        )!!
        assertEquals(20.0, Math.toDegrees(a.pitchDownRadians), 1e-4)
        assertEquals(0.0, a.rollRadians, 1e-6)
    }

    @Test
    fun loweringTheRightEdgeIsPositiveRoll() {
        val r = deg(10.0)
        val a = CameraAttitude.fromRotationMatrix(
            matrix(doubleArrayOf(cos(r), 0.0, -sin(r)), doubleArrayOf(sin(r), 0.0, cos(r)), doubleArrayOf(0.0, -1.0, 0.0)),
            displayRotationDegrees = 0,
        )!!
        assertEquals(10.0, Math.toDegrees(a.rollRadians), 1e-4)
        assertEquals(0.0, a.pitchDownRadians, 1e-6)
    }

    @Test
    fun phoneFlatOnATableLooksStraightDown() {
        val a = CameraAttitude.fromRotationMatrix(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), 0)!!
        assertEquals(90.0, Math.toDegrees(a.pitchDownRadians), 1e-4)
    }

    @Test
    fun landscapeHeldLevelHasZeroRollOnceDisplayRotationIsApplied() {
        // Turned counter-clockwise (ROTATION_90): the phone's natural right edge (+x) points up and
        // its natural top (+y) points west.
        val m = matrix(doubleArrayOf(0.0, 0.0, 1.0), doubleArrayOf(-1.0, 0.0, 0.0), doubleArrayOf(0.0, -1.0, 0.0))
        assertEquals(0.0, CameraAttitude.fromRotationMatrix(m, 90)!!.rollRadians, 1e-6)
        // The opposite landscape: natural right edge points down.
        val n = matrix(doubleArrayOf(0.0, 0.0, -1.0), doubleArrayOf(1.0, 0.0, 0.0), doubleArrayOf(0.0, -1.0, 0.0))
        assertEquals(0.0, CameraAttitude.fromRotationMatrix(n, 270)!!.rollRadians, 1e-6)
    }

    @Test
    fun malformedInputYieldsNoAttitude() {
        assertNull(CameraAttitude.fromRotationMatrix(FloatArray(4), 0))
        assertNull(CameraAttitude.fromRotationMatrix(FloatArray(9), 45))
    }
}
