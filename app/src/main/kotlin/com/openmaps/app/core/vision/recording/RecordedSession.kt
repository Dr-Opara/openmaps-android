package com.openmaps.app.core.vision.recording

import java.io.File
import java.io.FileInputStream
import java.util.Properties

/** One thing that happened during a recorded drive, at [bootNanos] on the shared boot clock. */
sealed interface SessionEvent {
    val bootNanos: Long

    data class Frame(override val bootNanos: Long, val record: FrameRecord, val file: File) : SessionEvent
    data class Imu(override val bootNanos: Long, val record: ImuRecord) : SessionEvent
    data class Location(override val bootNanos: Long, val record: LocationRecord) : SessionEvent
    data class Progress(override val bootNanos: Long, val record: ProgressRecord) : SessionEvent
}

/**
 * A recorded session loaded back from disk, for replay on the JVM or on a device.
 *
 * Reading is strict about structure (a row with the wrong number of columns is dropped and counted
 * in [skippedRows]) so a truncated file from a crash still loads everything before the damage.
 */
class RecordedSession private constructor(
    val directory: File,
    val manifest: Map<String, String>,
    val frames: List<FrameRecord>,
    val imu: List<ImuRecord>,
    val locations: List<LocationRecord>,
    val route: List<RoutePointRecord>,
    val progress: List<ProgressRecord>,
    val skippedRows: Int,
) {
    val clockAlignment: ClockAlignment = ClockAlignment.estimate(
        cameraTimestampSourceRealtime = manifest[SessionFormat.KEY_CAMERA_TIMESTAMP_REALTIME]?.toBooleanStrictOrNull(),
        frames = frames,
    )

    /** Route geometry for one route version, in order. */
    fun routeGeometry(routeVersion: Int): List<Pair<Double, Double>> =
        route.filter { it.routeVersion == routeVersion }.sortedBy { it.index }.map { it.latitude to it.longitude }

    /**
     * Every event in boot-clock order. Ties keep a fixed order (IMU, location, progress, frame) so a
     * replay is identical every time: a frame sees all sensor data stamped at or before it.
     */
    fun events(): List<SessionEvent> {
        val all = ArrayList<SessionEvent>(frames.size + imu.size + locations.size + progress.size)
        imu.mapTo(all) { SessionEvent.Imu(it.bootNanos, it) }
        locations.mapTo(all) { SessionEvent.Location(it.bootNanos, it) }
        progress.mapTo(all) { SessionEvent.Progress(it.bootNanos, it) }
        frames.mapTo(all) {
            SessionEvent.Frame(clockAlignment.frameBootNanos(it), it, File(File(directory, SessionFormat.FRAMES_DIR), it.file))
        }
        // sortedWith is stable, so the insertion order above breaks ties.
        return all.sortedWith(compareBy { it.bootNanos })
    }

    /** Feeds every event to [listener] in order. Synchronous and deterministic: no clocks, no threads. */
    fun replay(listener: (SessionEvent) -> Unit) = events().forEach(listener)

    companion object {
        fun read(directory: File): RecordedSession {
            var skipped = 0
            fun <T> rows(name: String, columns: Int, parse: (List<String>) -> T?): List<T> {
                val file = File(directory, name)
                if (!file.isFile) return emptyList()
                val out = ArrayList<T>()
                file.useLines { lines ->
                    lines.drop(1).forEach { line ->
                        if (line.isBlank()) return@forEach
                        val cells = line.split(',')
                        val parsed = if (cells.size == columns) runCatching { parse(cells) }.getOrNull() else null
                        if (parsed != null) out += parsed else skipped++
                    }
                }
                return out
            }

            val manifest = File(directory, SessionFormat.MANIFEST).takeIf { it.isFile }?.let { file ->
                Properties().apply { FileInputStream(file).use { load(it) } }
                    .entries.associate { it.key.toString() to it.value.toString() }
            }.orEmpty()

            val frames = rows(SessionFormat.FRAMES, 7) { c ->
                FrameRecord(c[0].toInt(), c[1].toLong(), c[2].toLong(), c[3].toInt(), c[4].toInt(), c[5].toInt(), c[6])
            }
            val imu = rows(SessionFormat.IMU, 6) { c ->
                ImuRecord(
                    bootNanos = c[0].toLong(),
                    sensor = ImuSensor.valueOf(c[1]),
                    values = floatArrayOf(c[2].toFloat(), c[3].toFloat(), c[4].toFloat(), c[5].toFloat())
                        .let { v -> if (v[3].isNaN()) v.copyOf(3) else v },
                )
            }
            val locations = rows(SessionFormat.LOCATION, 11) { c ->
                LocationRecord(
                    bootNanos = c[0].toLong(),
                    utcMillis = c[1].toLong(),
                    latitude = c[2].toDouble(),
                    longitude = c[3].toDouble(),
                    accuracyMeters = c[4].toFloatOrNull(),
                    bearingDegrees = c[5].toFloatOrNull(),
                    bearingAccuracyDegrees = c[6].toFloatOrNull(),
                    speedMetersPerSecond = c[7].toFloatOrNull(),
                    speedAccuracyMetersPerSecond = c[8].toFloatOrNull(),
                    altitudeMeters = c[9].toDoubleOrNull(),
                    provider = c[10],
                )
            }
            val route = rows(SessionFormat.ROUTE, 4) { c ->
                RoutePointRecord(c[0].toInt(), c[1].toInt(), c[2].toDouble(), c[3].toDouble())
            }
            val progress = rows(SessionFormat.PROGRESS, 11) { c ->
                ProgressRecord(
                    bootNanos = c[0].toLong(),
                    routeVersion = c[1].toInt(),
                    snappedShapeIndex = c[2].toInt(),
                    snappedLatitude = c[3].toDoubleOrNull(),
                    snappedLongitude = c[4].toDoubleOrNull(),
                    lateralDistanceMeters = c[5].toDouble(),
                    distanceToNextManeuverMeters = c[6].toDouble(),
                    remainingDistanceMeters = c[7].toDouble(),
                    maneuverType = c[8],
                    matchConfidence = c[9],
                    offRoute = c[10].toBooleanStrict(),
                )
            }
            return RecordedSession(directory, manifest, frames, imu, locations, route, progress, skipped)
        }
    }
}

/**
 * Rebuilds camera attitude during a replay exactly as the live overlay computes it: the latest
 * game-rotation-vector sample at or before each frame, through the same [RotationVector] and
 * [com.openmaps.app.core.vision.CameraAttitude] code.
 */
class AttitudeReplay(private val displayRotationDegrees: Int) {
    private var latest: com.openmaps.app.core.vision.CameraAttitude? = null

    fun onEvent(event: SessionEvent): com.openmaps.app.core.vision.CameraAttitude? {
        if (event is SessionEvent.Imu && event.record.sensor == ImuSensor.GameRotationVector) {
            val matrix = com.openmaps.app.core.vision.RotationVector.toRotationMatrix(event.record.values)
            if (matrix != null) {
                latest = com.openmaps.app.core.vision.CameraAttitude.fromRotationMatrix(
                    matrix,
                    displayRotationDegrees,
                    event.bootNanos,
                )
            }
        }
        return latest
    }
}
