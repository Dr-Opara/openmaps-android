package com.openmaps.app.core.vision.recording

import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.util.Properties

/**
 * Writes one recorded session to a directory. Plain `java.io`, so the same code is exercised by JVM
 * tests and by the device. Each stream has its own lock: frames, IMU and location arrive on
 * different threads.
 */
class SessionWriter(val directory: File) {
    @Volatile private var frames: BufferedWriter? = null
    @Volatile private var imu: BufferedWriter? = null
    @Volatile private var location: BufferedWriter? = null
    @Volatile private var route: BufferedWriter? = null
    @Volatile private var progress: BufferedWriter? = null

    val framesDirectory: File get() = File(directory, SessionFormat.FRAMES_DIR)

    @Synchronized
    fun open() {
        framesDirectory.mkdirs()
        frames = create(SessionFormat.FRAMES, SessionFormat.FRAMES_HEADER)
        imu = create(SessionFormat.IMU, SessionFormat.IMU_HEADER)
        location = create(SessionFormat.LOCATION, SessionFormat.LOCATION_HEADER)
        route = create(SessionFormat.ROUTE, SessionFormat.ROUTE_HEADER)
        progress = create(SessionFormat.PROGRESS, SessionFormat.PROGRESS_HEADER)
    }

    private fun create(name: String, header: String): BufferedWriter =
        File(directory, name).bufferedWriter().also {
            it.write(header)
            it.newLine()
        }

    /** Values are stored as written; callers must never put a credential in the manifest. */
    fun writeManifest(values: Map<String, String>) {
        val properties = Properties()
        properties[SessionFormat.KEY_VERSION] = SessionFormat.VERSION.toString()
        values.forEach { (key, value) -> properties[key] = value }
        FileOutputStream(File(directory, SessionFormat.MANIFEST)).use { properties.store(it, "OpenMaps vision recording") }
    }

    fun frameFile(index: Int): File = File(framesDirectory, frameFileName(index))

    fun frame(record: FrameRecord) = line(frames) {
        "${record.index},${record.sensorTimestampNanos},${record.arrivalBootNanos},${record.width},${record.height}," +
            "${record.rotationDegrees},${record.file}"
    }

    fun imu(bootNanos: Long, sensor: ImuSensor, values: FloatArray) = line(imu) {
        val v = FloatArray(4) { if (it < values.size) values[it] else Float.NaN }
        "$bootNanos,${sensor.name},${v[0]},${v[1]},${v[2]},${v[3]}"
    }

    fun location(record: LocationRecord) = line(location) {
        with(record) {
            "$bootNanos,$utcMillis,$latitude,$longitude,${accuracyMeters.orBlank()},${bearingDegrees.orBlank()}," +
                "${bearingAccuracyDegrees.orBlank()},${speedMetersPerSecond.orBlank()}," +
                "${speedAccuracyMetersPerSecond.orBlank()},${altitudeMeters.orBlank()},${provider.replace(',', ' ')}"
        }
    }

    fun route(routeVersion: Int, points: List<Pair<Double, Double>>) {
        val writer = route ?: return
        synchronized(writer) {
            points.forEachIndexed { index, (latitude, longitude) ->
                writer.write("$routeVersion,$index,$latitude,$longitude")
                writer.newLine()
            }
            writer.flush()
        }
    }

    fun progress(record: ProgressRecord) = line(progress) {
        with(record) {
            "$bootNanos,$routeVersion,$snappedShapeIndex,${snappedLatitude.orBlank()},${snappedLongitude.orBlank()}," +
                "$lateralDistanceMeters,$distanceToNextManeuverMeters,$remainingDistanceMeters,$maneuverType," +
                "$matchConfidence,$offRoute"
        }
    }

    @Synchronized
    fun close() {
        listOf(frames, imu, location, route, progress).forEach { writer ->
            try {
                writer?.let { synchronized(it) { it.close() } }
            } catch (_: Exception) {
                // Closing is best effort: a failed flush must not stop the other files closing.
            }
        }
        frames = null
        imu = null
        location = null
        route = null
        progress = null
    }

    private inline fun line(writer: BufferedWriter?, text: () -> String) {
        val target = writer ?: return
        synchronized(target) {
            try {
                target.write(text())
                target.newLine()
            } catch (_: java.io.IOException) {
                // Stream already closed by a concurrent stop(): drop the sample.
            }
        }
    }

    private fun Any?.orBlank(): String = this?.toString().orEmpty()

    companion object {
        fun frameFileName(index: Int): String = String.format(java.util.Locale.US, "%06d.jpg", index)
    }
}
