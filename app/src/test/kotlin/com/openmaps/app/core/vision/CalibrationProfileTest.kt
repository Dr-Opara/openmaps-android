package com.openmaps.app.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationProfileTest {
    private val profile = CalibrationProfile(
        deviceModel = "SM-S948U1",
        cameraId = "0",
        displayRotationDegrees = 0,
        mountDescription = "windshield",
        lensHeightMeters = 1.32,
        pitchTrimDegrees = -0.4,
        rollTrimDegrees = 0.2,
        referencePitchDownDegrees = 3.5,
        referenceRollDegrees = -0.5,
        verifiedOnDevice = true,
        savedAtEpochMillis = 1_800_000_000_000,
    )

    @Test
    fun aNewProfileHasNoHeightAndNoMountAngle() {
        val empty = CalibrationProfile("SM-S948U1", "0", 0)
        assertNull("there is no default lens height", empty.lensHeightMeters)
        assertFalse(empty.hasLensHeight)
        assertFalse(empty.hasMountAngle)
        assertFalse(empty.verifiedOnDevice)
    }

    @Test
    fun roundTripsThroughStorage() = assertEquals(profile, CalibrationProfile.fromMap(profile.toMap()))

    @Test
    fun anUnsetHeightStaysUnsetThroughStorage() {
        val restored = CalibrationProfile.fromMap(CalibrationProfile("SM-S948U1", null, 0).toMap())!!
        assertNull(restored.lensHeightMeters)
        assertNull(restored.cameraId)
        assertNull(restored.referencePitchDownDegrees)
    }

    @Test
    fun damagedStorageYieldsNoProfileRatherThanAHalfProfile() {
        assertNull(CalibrationProfile.fromMap(emptyMap()))
        assertNull(CalibrationProfile.fromMap(profile.toMap() - "displayRotationDegrees"))
        assertNull(CalibrationProfile.fromMap(profile.toMap() + ("lensHeightMeters" to "40")))
        assertNull(CalibrationProfile.fromMap(profile.toMap() + ("lensHeightMeters" to "abc")))
        assertNull(CalibrationProfile.fromMap(profile.toMap() + ("pitchTrimDegrees" to "90")))
        assertNull(CalibrationProfile.fromMap(profile.toMap() + ("rollTrimDegrees" to "NaN")))
    }

    @Test
    fun lensHeightEntryAcceptsOnlyPlausibleValues() {
        assertEquals(1.3, CalibrationProfile.parseLensHeight(" 1.3 ")!!, 1e-9)
        assertEquals(1.3, CalibrationProfile.parseLensHeight("1,3")!!, 1e-9)
        assertNull(CalibrationProfile.parseLensHeight(""))
        assertNull(CalibrationProfile.parseLensHeight("0.1"))
        assertNull(CalibrationProfile.parseLensHeight("12"))
        assertNull(CalibrationProfile.parseLensHeight("NaN"))
        assertNull(CalibrationProfile.parseLensHeight("-1.3"))
    }

    @Test
    fun appliesOnlyToTheSamePhoneCameraAndOrientation() {
        assertTrue(profile.appliesTo("SM-S948U1", "0", 0))
        assertFalse(profile.appliesTo("SM-S948U1", "0", 90))
        assertFalse(profile.appliesTo("SM-S948U1", "2", 0))
        assertFalse(profile.appliesTo("Pixel 9", "0", 0))
    }

    @Test
    fun trimsAreAddedToTheSensorAttitude() {
        val corrected = profile.corrected(CameraAttitude(Math.toRadians(5.0), Math.toRadians(1.0), 42))
        assertEquals(4.6, Math.toDegrees(corrected.pitchDownRadians), 1e-9)
        assertEquals(1.2, Math.toDegrees(corrected.rollRadians), 1e-9)
        assertEquals(42, corrected.timestampNanos)
    }

    @Test
    fun primaryTargetIsRecognised() {
        assertTrue(DeviceTargets.isPrimary("SM-S948U1"))
        assertTrue(DeviceTargets.isPrimary(" sm-s948u1 "))
        assertFalse(DeviceTargets.isPrimary("SM-S948B"))
        assertFalse(DeviceTargets.isPrimary(null))
    }

    // --- drift ------------------------------------------------------------------------------------

    private val second = 1_000_000_000L

    private fun feed(d: MountDriftDetector, fromSecond: Int, toSecond: Int, pitch: Double, roll: Double): Boolean {
        var moved = false
        // 10 Hz
        for (tick in fromSecond * 10..toSecond * 10) moved = d.update(tick * second / 10, pitch, roll, profile)
        return moved
    }

    @Test
    fun steadyMountIsNotReportedAsMoved() {
        val d = MountDriftDetector()
        assertFalse(feed(d, 0, 120, 3.6, -0.4))
        assertTrue(d.deviationDegrees < 1.0)
    }

    @Test
    fun aShortDisturbanceDoesNotTrip() {
        // Hard braking or a speed bump: several degrees for a few seconds, then back.
        val d = MountDriftDetector()
        feed(d, 0, 10, 3.5, -0.5)
        assertFalse(feed(d, 10, 18, 11.0, -0.5))
        assertFalse(feed(d, 18, 60, 3.5, -0.5))
    }

    @Test
    fun aPersistentChangeTripsAndStaysTripped() {
        val d = MountDriftDetector()
        feed(d, 0, 10, 3.5, -0.5)
        assertTrue("phone knocked 8 degrees down and left there", feed(d, 10, 45, 11.5, -0.5))
        assertTrue("returning to the old angle does not silently re-arm guidance", feed(d, 45, 90, 3.5, -0.5))
        d.reset()
        assertFalse(d.moved)
        assertFalse(feed(d, 90, 100, 3.5, -0.5))
    }

    @Test
    fun persistentRollChangeTripsToo() {
        val d = MountDriftDetector()
        assertTrue(feed(d, 0, 40, 3.5, 9.0))
    }

    @Test
    fun withoutACapturedMountAngleThereIsNothingToCompare() {
        val d = MountDriftDetector()
        val noReference = profile.copy(referencePitchDownDegrees = null, referenceRollDegrees = null)
        var moved = false
        for (tick in 0..600) moved = d.update(tick * second / 10, 30.0, 30.0, noReference)
        assertFalse(moved)
    }

    @Test
    fun rollComparisonWrapsAroundHalfTurns() {
        val d = MountDriftDetector()
        val upsideDown = profile.copy(referenceRollDegrees = 179.5)
        var moved = false
        for (tick in 0..600) moved = d.update(tick * second / 10, 3.5, -179.5, upsideDown)
        assertFalse("179.5 and -179.5 are one degree apart", moved)
    }

    // --- reported intrinsics and rotation vectors -----------------------------------------------------

    @Test
    fun reportedIntrinsicsScaleWithTheCroppedBuffer() {
        // 4000x3000 array, 16:9 buffer at 1280x720: full width kept, scale 0.32.
        val k = CameraIntrinsics.fromDeviceCalibration(3000.0, 3010.0, 2010.0, 1490.0, 4000, 3000, 1280, 720)!!
        assertEquals(960.0, k.focalXPixels, 1e-9)
        assertEquals(3010.0 * 0.32, k.focalYPixels, 1e-9)
        assertEquals(640.0 + 10 * 0.32, k.principalXPixels, 1e-9)
        assertEquals(360.0 - 10 * 0.32, k.principalYPixels, 1e-9)
        assertEquals(IntrinsicsSource.DeviceReported, k.source)
    }

    @Test
    fun implausibleReportedIntrinsicsAreRejected() {
        assertNull(CameraIntrinsics.fromDeviceCalibration(0.0, 3000.0, 2000.0, 1500.0, 4000, 3000, 1280, 720))
        assertNull(CameraIntrinsics.fromDeviceCalibration(Double.NaN, 3000.0, 2000.0, 1500.0, 4000, 3000, 1280, 720))
        assertNull(CameraIntrinsics.fromDeviceCalibration(3000.0, 3000.0, 9000.0, 1500.0, 4000, 3000, 1280, 720))
        assertNull(CameraIntrinsics.fromDeviceCalibration(3000.0, 3000.0, 2000.0, 1500.0, 0, 3000, 1280, 720))
    }

    @Test
    fun rotationVectorMatchesTheAndroidDefinition() {
        val identity = RotationVector.toRotationMatrix(floatArrayOf(0f, 0f, 0f, 1f))!!
        assertEquals(listOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), identity.toList())

        // Quarter turn about the device x axis: a phone lying flat stood upright, camera at the horizon.
        val h = Math.sqrt(0.5).toFloat()
        val upright = RotationVector.toRotationMatrix(floatArrayOf(h, 0f, 0f, h))!!
        val attitude = CameraAttitude.fromRotationMatrix(upright, 0)!!
        assertEquals(0.0, Math.toDegrees(attitude.pitchDownRadians), 1e-3)
        assertEquals(0.0, Math.toDegrees(attitude.rollRadians), 1e-3)

        // The three-element form (w omitted) gives the same matrix.
        val threeElement = RotationVector.toRotationMatrix(floatArrayOf(h, 0f, 0f))!!
        for (i in 0 until 9) assertEquals(upright[i], threeElement[i], 1e-5f)
    }

    @Test
    fun malformedRotationVectorsAreRejected() {
        assertNull(RotationVector.toRotationMatrix(floatArrayOf(0f, 0f)))
        assertNull(RotationVector.toRotationMatrix(floatArrayOf(Float.NaN, 0f, 0f, 1f)))
        assertNotNull(RotationVector.toRotationMatrix(floatArrayOf(0f, 0f, 0f)))
    }
}
