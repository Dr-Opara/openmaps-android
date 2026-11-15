package com.openmaps.app.core.vision.recording

/*
 * On-disk format of a recorded drive (docs/VISUAL_NAVIGATION_ENGINE.md, "Recording and replay").
 *
 *   <session>/session.properties   manifest: device, camera, calibration, clocks
 *   <session>/frames.csv           one row per saved frame
 *   <session>/frames/000001.jpg    the frame as delivered by the camera (not rotated)
 *   <session>/imu.csv              accelerometer, gyroscope, rotation vectors, magnetometer
 *   <session>/location.csv         raw GPS fixes
 *   <session>/route.csv            route geometry, one block per route version
 *   <session>/progress.csv         map-matched navigation progress
 *
 * All timestamps are nanoseconds. IMU and location rows are on the boot clock
 * (`SystemClock.elapsedRealtimeNanos`). Frame rows carry both the camera's own sensor timestamp and
 * the boot-clock time the frame reached the app, which is what lets [ClockAlignment] put frames on
 * the boot clock even when the camera uses a different one.
 */
object SessionFormat {
    const val VERSION = 1
    const val MANIFEST = "session.properties"
    const val FRAMES = "frames.csv"
    const val FRAMES_DIR = "frames"
    const val IMU = "imu.csv"
    const val LOCATION = "location.csv"
    const val ROUTE = "route.csv"
    const val PROGRESS = "progress.csv"

    const val FRAMES_HEADER = "index,sensorTimestampNanos,arrivalBootNanos,width,height,rotationDegrees,file"
    const val IMU_HEADER = "bootNanos,sensor,v0,v1,v2,v3"
    const val LOCATION_HEADER =
        "bootNanos,utcMillis,latitude,longitude,accuracyMeters,bearingDegrees,bearingAccuracyDegrees," +
            "speedMetersPerSecond,speedAccuracyMetersPerSecond,altitudeMeters,provider"
    const val ROUTE_HEADER = "routeVersion,index,latitude,longitude"
    const val PROGRESS_HEADER =
        "bootNanos,routeVersion,snappedShapeIndex,snappedLatitude,snappedLongitude,lateralDistanceMeters," +
            "distanceToNextManeuverMeters,remainingDistanceMeters,maneuverType,matchConfidence,offRoute"

    // Manifest keys.
    const val KEY_VERSION = "formatVersion"
    const val KEY_DEVICE_MODEL = "device.model"
    const val KEY_DEVICE_MANUFACTURER = "device.manufacturer"
    const val KEY_ANDROID_RELEASE = "device.androidRelease"
    const val KEY_APP_VERSION = "app.version"
    const val KEY_START_BOOT_NANOS = "clock.startBootNanos"
    const val KEY_START_UTC_MILLIS = "clock.startUtcMillis"
    const val KEY_CAMERA_TIMESTAMP_REALTIME = "clock.cameraTimestampSourceRealtime"
    const val KEY_DISPLAY_ROTATION = "display.rotationDegrees"
    const val KEY_LOCATION_RECORDED = "location.recorded"
    const val PREFIX_CAMERA = "camera."
    const val PREFIX_PROFILE = "profile."
}

enum class ImuSensor { Accelerometer, Gyroscope, GameRotationVector, RotationVector, MagneticField }

data class FrameRecord(
    val index: Int,
    val sensorTimestampNanos: Long,
    val arrivalBootNanos: Long,
    val width: Int,
    val height: Int,
    val rotationDegrees: Int,
    val file: String,
)

data class ImuRecord(val bootNanos: Long, val sensor: ImuSensor, val values: FloatArray)

data class LocationRecord(
    val bootNanos: Long,
    val utcMillis: Long,
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val bearingDegrees: Float?,
    val bearingAccuracyDegrees: Float?,
    val speedMetersPerSecond: Float?,
    val speedAccuracyMetersPerSecond: Float?,
    val altitudeMeters: Double?,
    val provider: String,
)

data class RoutePointRecord(val routeVersion: Int, val index: Int, val latitude: Double, val longitude: Double)

data class ProgressRecord(
    val bootNanos: Long,
    val routeVersion: Int,
    val snappedShapeIndex: Int,
    val snappedLatitude: Double?,
    val snappedLongitude: Double?,
    val lateralDistanceMeters: Double,
    val distanceToNextManeuverMeters: Double,
    val remainingDistanceMeters: Double,
    val maneuverType: String,
    val matchConfidence: String,
    val offRoute: Boolean,
)

/**
 * How camera frame timestamps relate to the boot clock that IMU and GPS use.
 *
 * @property offsetNanos add this to a frame's sensor timestamp to get boot-clock time.
 */
data class ClockAlignment(val kind: Kind, val offsetNanos: Long) {
    enum class Kind {
        /** The camera reports `TIMESTAMP_SOURCE_REALTIME`: same clock, no correction. */
        SameClock,

        /**
         * The camera does not declare its clock, but every frame arrived a plausible pipeline delay
         * after its timestamp, so the clocks are taken to be the same. Not guaranteed.
         */
        AssumedSameClock,

        /**
         * Different clock. The offset is the smallest (arrival - timestamp) seen, so frame times are
         * **late by the camera pipeline's minimum latency** (unknown, typically tens of ms).
         */
        Estimated,

        /** No frames to estimate from. */
        Unknown,
    }

    fun frameBootNanos(frame: FrameRecord): Long = frame.sensorTimestampNanos + offsetNanos

    companion object {
        /** A frame cannot reach the app before it is exposed, nor plausibly take longer than this. */
        const val MAX_PLAUSIBLE_PIPELINE_NANOS = 500_000_000L

        fun estimate(cameraTimestampSourceRealtime: Boolean?, frames: List<FrameRecord>): ClockAlignment {
            if (cameraTimestampSourceRealtime == true) return ClockAlignment(Kind.SameClock, 0L)
            if (frames.isEmpty()) return ClockAlignment(Kind.Unknown, 0L)
            val smallest = frames.minOf { it.arrivalBootNanos - it.sensorTimestampNanos }
            return if (smallest in 0..MAX_PLAUSIBLE_PIPELINE_NANOS) {
                ClockAlignment(Kind.AssumedSameClock, 0L)
            } else {
                ClockAlignment(Kind.Estimated, smallest)
            }
        }
    }
}
