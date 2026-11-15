package com.openmaps.app.core.vision.recording

import com.openmaps.app.core.vision.CameraAttitude
import com.openmaps.app.core.vision.CameraIntrinsics
import com.openmaps.app.core.vision.GroundPlaneProjector
import com.openmaps.app.core.vision.IntrinsicsSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.math.tan

class SessionRecordingTest {
    @get:Rule
    val temp = TemporaryFolder()

    private val ms = 1_000_000L

    private fun frame(index: Int, sensorNanos: Long, arrivalNanos: Long) =
        FrameRecord(index, sensorNanos, arrivalNanos, 1280, 720, 90, SessionWriter.frameFileName(index))

    private fun location(bootNanos: Long, lat: Double) = LocationRecord(
        bootNanos = bootNanos,
        utcMillis = 1_800_000_000_000,
        latitude = lat,
        longitude = -95.78,
        accuracyMeters = 4.5f,
        bearingDegrees = null,
        bearingAccuracyDegrees = null,
        speedMetersPerSecond = 13.4f,
        speedAccuracyMetersPerSecond = 0.5f,
        altitudeMeters = 41.0,
        provider = "gps",
    )

    /** A level quarter-turn-about-x quaternion: phone upright, camera at the horizon. */
    private val upright = floatArrayOf(Math.sqrt(0.5).toFloat(), 0f, 0f, Math.sqrt(0.5).toFloat())

    /** Upright, then tipped so the camera looks [degrees] below the horizon. */
    private fun pitchedDown(degrees: Double): FloatArray {
        val half = Math.toRadians(90.0 - degrees) / 2
        return floatArrayOf(Math.sin(half).toFloat(), 0f, 0f, Math.cos(half).toFloat())
    }

    private fun writeSession(directory: File, realtime: String?): File {
        val writer = SessionWriter(directory)
        directory.mkdirs()
        writer.open()
        writer.writeManifest(
            buildMap {
                put(SessionFormat.KEY_DEVICE_MODEL, "SM-S948U1")
                put(SessionFormat.KEY_DISPLAY_ROTATION, "0")
                realtime?.let { put(SessionFormat.KEY_CAMERA_TIMESTAMP_REALTIME, it) }
            },
        )
        writer.route(1, listOf(29.70 to -95.78, 29.71 to -95.78))
        writer.imu(1_000 * ms, ImuSensor.GameRotationVector, upright)
        writer.imu(1_000 * ms, ImuSensor.Gyroscope, floatArrayOf(0.01f, -0.02f, 0.03f))
        writer.location(location(1_010 * ms, 29.7001))
        writer.frame(frame(1, 1_033 * ms, 1_070 * ms))
        writer.imu(1_050 * ms, ImuSensor.GameRotationVector, pitchedDown(10.0))
        writer.progress(
            ProgressRecord(1_060 * ms, 1, 0, 29.7001, -95.78, 1.5, 320.0, 4100.0, "TurnRight", "High", false),
        )
        writer.frame(frame(2, 1_066 * ms, 1_101 * ms))
        writer.close()
        return directory
    }

    @Test
    fun everythingWrittenIsReadBackExactly() {
        val session = RecordedSession.read(writeSession(temp.newFolder("s1"), realtime = "true"))

        assertEquals(0, session.skippedRows)
        assertEquals("SM-S948U1", session.manifest[SessionFormat.KEY_DEVICE_MODEL])
        assertEquals(SessionFormat.VERSION.toString(), session.manifest[SessionFormat.KEY_VERSION])
        assertEquals(listOf(frame(1, 1_033 * ms, 1_070 * ms), frame(2, 1_066 * ms, 1_101 * ms)), session.frames)
        assertEquals(listOf(location(1_010 * ms, 29.7001)), session.locations)
        assertEquals(listOf(29.70 to -95.78, 29.71 to -95.78), session.routeGeometry(1))
        assertEquals("TurnRight", session.progress.single().maneuverType)
        assertEquals(3, session.imu.size)
        val gyro = session.imu.single { it.sensor == ImuSensor.Gyroscope }
        assertEquals("three-axis sensors read back with three values", 3, gyro.values.size)
        assertEquals(-0.02f, gyro.values[1], 0f)
        assertEquals(4, session.imu.first { it.sensor == ImuSensor.GameRotationVector }.values.size)
    }

    @Test
    fun eventsComeBackInBootClockOrder() {
        val session = RecordedSession.read(writeSession(temp.newFolder("s2"), realtime = "true"))
        val times = session.events().map { it.bootNanos }
        assertEquals(times.sorted(), times)
        assertEquals(
            listOf("Imu", "Imu", "Location", "Frame", "Imu", "Progress", "Frame"),
            session.events().map { it.javaClass.simpleName },
        )
    }

    @Test
    fun replayIsDeterministic() {
        val session = RecordedSession.read(writeSession(temp.newFolder("s3"), realtime = "true"))
        val first = ArrayList<SessionEvent>().also { session.replay(it::add) }.map { it.toString() }
        val second = ArrayList<SessionEvent>().also { session.replay(it::add) }.map { it.toString() }
        assertEquals(first.size, 7)
        assertEquals(first.filterNot { it.contains("Imu(") }, second.filterNot { it.contains("Imu(") })
        assertEquals(session.events().map { it.bootNanos }, session.events().map { it.bootNanos })
    }

    @Test
    fun replayedAttitudeDrivesTheSameProjectionAsLive() {
        val session = RecordedSession.read(writeSession(temp.newFolder("s4"), realtime = "true"))
        val intrinsics = CameraIntrinsics(900.0, 900.0, 360.0, 640.0, 720, 1280, IntrinsicsSource.SensorGeometry)
        val projector = GroundPlaneProjector(intrinsics, cameraHeightMeters = 1.3)
        val attitudes = AttitudeReplay(displayRotationDegrees = 0)
        val horizonAtFrame = LinkedHashMap<Int, Double>()
        var current: CameraAttitude? = null
        session.replay { event ->
            current = attitudes.onEvent(event)
            if (event is SessionEvent.Frame) {
                horizonAtFrame[event.record.index] = projector.horizon(current!!)!!.first.v
            }
        }
        // Frame 1 saw only the level sample; frame 2 saw the 10-degree sample stamped before it.
        assertEquals(640.0, horizonAtFrame[1]!!, 0.05)
        assertEquals(640.0 - 900.0 * tan(Math.toRadians(10.0)), horizonAtFrame[2]!!, 0.05)
    }

    @Test
    fun aRealtimeCameraClockNeedsNoCorrection() {
        val session = RecordedSession.read(writeSession(temp.newFolder("s5"), realtime = "true"))
        assertEquals(ClockAlignment(ClockAlignment.Kind.SameClock, 0), session.clockAlignment)
    }

    @Test
    fun anUndeclaredClockWithPlausibleLatencyIsAssumedShared() {
        val session = RecordedSession.read(writeSession(temp.newFolder("s6"), realtime = null))
        assertEquals(ClockAlignment.Kind.AssumedSameClock, session.clockAlignment.kind)
        assertEquals(0, session.clockAlignment.offsetNanos)
    }

    @Test
    fun aDifferentCameraClockIsMovedOntoTheBootClock() {
        // Camera counts from its own epoch, 5 hours behind the boot clock.
        val skew = 5 * 3600 * 1_000_000_000L
        val frames = listOf(
            frame(1, 1_033 * ms - skew, 1_070 * ms),
            frame(2, 1_066 * ms - skew, 1_101 * ms),
        )
        val alignment = ClockAlignment.estimate(cameraTimestampSourceRealtime = false, frames = frames)
        assertEquals(ClockAlignment.Kind.Estimated, alignment.kind)
        // Smallest (arrival - timestamp) is frame 2's 35 ms pipeline delay plus the skew.
        assertEquals(skew + 35 * ms, alignment.offsetNanos)
        assertEquals(1_101 * ms, alignment.frameBootNanos(frames[1]))
        // Frame spacing is preserved exactly; only the common offset is uncertain.
        assertEquals(33 * ms, alignment.frameBootNanos(frames[1]) - alignment.frameBootNanos(frames[0]))
    }

    @Test
    fun noFramesMeansTheClockRelationIsUnknown() {
        assertEquals(ClockAlignment.Kind.Unknown, ClockAlignment.estimate(null, emptyList()).kind)
    }

    @Test
    fun aTruncatedFileLoadsEverythingBeforeTheDamage() {
        val directory = writeSession(temp.newFolder("s7"), realtime = "true")
        File(directory, SessionFormat.IMU).appendText("1070000000,Gyroscope,0.1\n")
        File(directory, SessionFormat.FRAMES).appendText("3,notanumber,1,1280,720,90,000003.jpg\n")
        val session = RecordedSession.read(directory)
        assertEquals(2, session.skippedRows)
        assertEquals(2, session.frames.size)
        assertEquals(3, session.imu.size)
    }

    @Test
    fun aMissingSessionReadsAsEmptyNotAsACrash() {
        val session = RecordedSession.read(File(temp.root, "does-not-exist"))
        assertTrue(session.frames.isEmpty() && session.imu.isEmpty() && session.manifest.isEmpty())
        assertNull(session.events().firstOrNull())
    }

    @Test
    fun writesAfterCloseAreDropped() {
        val directory = temp.newFolder("s8")
        val writer = SessionWriter(directory)
        writer.open()
        writer.close()
        writer.imu(1, ImuSensor.Gyroscope, floatArrayOf(1f, 2f, 3f))
        writer.frame(frame(1, 1, 2))
        assertTrue(RecordedSession.read(directory).imu.isEmpty())
    }

    @Test
    fun frameFilesAreNamedByIndexAndLiveInTheFramesFolder() {
        val writer = SessionWriter(temp.newFolder("s9"))
        assertEquals("000042.jpg", SessionWriter.frameFileName(42))
        assertEquals(SessionFormat.FRAMES_DIR, writer.frameFile(42).parentFile!!.name)
    }
}
