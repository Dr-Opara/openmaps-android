package com.openmaps.app.vision.recording

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import androidx.core.content.ContextCompat
import com.openmaps.app.BuildConfig
import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.core.routing.RouteResult
import com.openmaps.app.core.vision.CalibrationProfile
import com.openmaps.app.core.vision.CameraCalibration
import com.openmaps.app.core.vision.FrameMetadata
import com.openmaps.app.core.vision.recording.FrameRecord
import com.openmaps.app.core.vision.recording.ImuSensor
import com.openmaps.app.core.vision.recording.LocationRecord
import com.openmaps.app.core.vision.recording.ProgressRecord
import com.openmaps.app.core.vision.recording.SessionFormat
import com.openmaps.app.core.vision.recording.SessionWriter
import com.openmaps.app.vision.CameraXFrameProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.FileOutputStream

/**
 * Records one drive: camera frames, raw IMU, raw GPS, navigation progress and the calibration in
 * force, all timestamped so they can be replayed together (see `SessionFormat`).
 *
 * Clocks: IMU (`SensorEvent.timestamp`) and GPS (`Location.elapsedRealtimeNanos`) are on the boot
 * clock. Each frame stores the camera's own timestamp **and** the boot-clock time it reached this
 * process, so replay can align a camera that runs on another clock (`ClockAlignment`).
 *
 * Privacy: starts only after explicit consent ([RecordingStore.consentGiven]), writes only under
 * [RecordingStore.root], and never touches the network. It stops by itself after
 * [MAX_DURATION_NANOS] or when free space drops below [MIN_FREE_BYTES].
 */
@Suppress("LogNotTimber")
class SessionRecorder(
    context: Context,
    private val store: RecordingStore,
    private val frameProvider: CameraXFrameProvider,
) : SensorEventListener, LocationListener {

    sealed interface State {
        data object Idle : State
        data class Recording(
            val sessionName: String,
            val startedBootNanos: Long,
            val frames: Int,
            val locationRecorded: Boolean,
            val lastFrameWriteMillis: Double,
        ) : State
        data class Stopped(val sessionName: String, val frames: Int, val reason: StopReason) : State
    }

    enum class StopReason { User, MaxDuration, LowStorage, CameraReleased, WriteError }

    private val appContext = context.applicationContext
    private val sensorManager = appContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as? LocationManager

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    @Volatile private var writer: SessionWriter? = null
    private var thread: HandlerThread? = null
    @Volatile private var frameIndex = 0
    @Volatile private var lastSavedSensorNanos = 0L
    @Volatile private var routeVersion = 0
    private var lastRoute: RouteResult? = null

    /** @return false when consent has not been given or a session is already running. */
    @Synchronized
    fun start(
        calibration: CameraCalibration?,
        profile: CalibrationProfile?,
        displayRotationDegrees: Int,
        route: RouteResult?,
    ): Boolean {
        if (!store.consentGiven || writer != null) return false
        val directory = store.newSessionDirectory()
        val sessionWriter = SessionWriter(directory)
        try {
            sessionWriter.open()
        } catch (t: Throwable) {
            Log.w(TAG, "Could not open recording", t)
            return false
        }
        val startBootNanos = SystemClock.elapsedRealtimeNanos()
        val handlerThread = HandlerThread("vision-recorder").also { it.start() }
        val handler = Handler(handlerThread.looper)
        thread = handlerThread

        val locationRecorded = startLocation(handlerThread)
        sessionWriter.writeManifest(manifest(calibration, profile, displayRotationDegrees, startBootNanos, locationRecorded))

        frameIndex = 0
        lastSavedSensorNanos = 0L
        routeVersion = 0
        lastRoute = null
        writer = sessionWriter
        route?.let { onRoute(it) }

        for ((type, _) in SENSORS) {
            sensorManager?.getDefaultSensor(type)?.let {
                sensorManager.registerListener(this, it, IMU_PERIOD_MICROS, handler)
            }
        }
        frameProvider.frameSink = CameraXFrameProvider.FrameSink(::onFrame)
        _state.value = State.Recording(directory.name, startBootNanos, 0, locationRecorded, 0.0)
        return true
    }

    @Synchronized
    fun stop(reason: StopReason = StopReason.User) {
        val sessionWriter = writer ?: return
        writer = null
        frameProvider.frameSink = null
        sensorManager?.unregisterListener(this)
        try {
            locationManager?.removeUpdates(this)
        } catch (t: Throwable) {
            Log.w(TAG, "removeUpdates failed", t)
        }
        thread?.quitSafely()
        thread = null
        sessionWriter.close()
        val current = _state.value
        _state.value = State.Stopped(
            sessionName = (current as? State.Recording)?.sessionName ?: sessionWriter.directory.name,
            frames = frameIndex,
            reason = reason,
        )
    }

    fun dismissStopped() {
        if (_state.value is State.Stopped) _state.value = State.Idle
    }

    /** Stores a new route's geometry. Called whenever the active route changes (start, reroute). */
    fun onRoute(route: RouteResult) {
        val sessionWriter = writer ?: return
        if (route === lastRoute) return
        lastRoute = route
        routeVersion += 1
        sessionWriter.route(routeVersion, route.geometry.map { it.latitude to it.longitude })
    }

    fun onProgress(progress: RouteProgress) {
        val sessionWriter = writer ?: return
        sessionWriter.progress(
            ProgressRecord(
                bootNanos = SystemClock.elapsedRealtimeNanos(),
                routeVersion = routeVersion,
                snappedShapeIndex = progress.snappedShapeIndex,
                snappedLatitude = progress.snappedCoordinate?.latitude,
                snappedLongitude = progress.snappedCoordinate?.longitude,
                lateralDistanceMeters = progress.lateralDistanceMeters,
                distanceToNextManeuverMeters = progress.distanceToNextManeuverMeters,
                remainingDistanceMeters = progress.remainingDistanceMeters,
                maneuverType = progress.currentManeuver.type.name,
                matchConfidence = progress.mapMatchConfidence.name,
                offRoute = progress.isOffRoute,
            ),
        )
    }

    private fun onFrame(image: ImageProxy, metadata: FrameMetadata, arrivalBootNanos: Long) {
        val sessionWriter = writer ?: return
        val recording = _state.value as? State.Recording ?: return
        if (arrivalBootNanos - recording.startedBootNanos > MAX_DURATION_NANOS) return stop(StopReason.MaxDuration)
        if (metadata.timestampNanos - lastSavedSensorNanos < MIN_FRAME_INTERVAL_NANOS && lastSavedSensorNanos != 0L) return
        val index = frameIndex + 1
        if (index % STORAGE_CHECK_EVERY_FRAMES == 1 && store.freeBytes() < MIN_FREE_BYTES) return stop(StopReason.LowStorage)

        val startedNanos = System.nanoTime()
        try {
            // Saved exactly as the camera delivered it; the rotation needed to view it upright is in frames.csv.
            val bitmap = image.toBitmap()
            try {
                FileOutputStream(sessionWriter.frameFile(index)).use { bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, it) }
            } finally {
                bitmap.recycle()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "Frame write failed", t)
            return stop(StopReason.WriteError)
        }
        sessionWriter.frame(
            FrameRecord(
                index = index,
                sensorTimestampNanos = metadata.timestampNanos,
                arrivalBootNanos = arrivalBootNanos,
                width = metadata.bufferWidth,
                height = metadata.bufferHeight,
                rotationDegrees = metadata.rotationDegrees,
                file = SessionWriter.frameFileName(index),
            ),
        )
        frameIndex = index
        lastSavedSensorNanos = metadata.timestampNanos
        val writeMillis = (System.nanoTime() - startedNanos) / 1_000_000.0
        (_state.value as? State.Recording)?.let {
            _state.value = it.copy(frames = index, lastFrameWriteMillis = writeMillis)
        }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val sensor = SENSORS[event.sensor.type] ?: return
        writer?.imu(event.timestamp, sensor, event.values)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onLocationChanged(location: Location) {
        writer?.location(
            LocationRecord(
                bootNanos = location.elapsedRealtimeNanos,
                utcMillis = location.time,
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                bearingDegrees = if (location.hasBearing()) location.bearing else null,
                bearingAccuracyDegrees = if (location.hasBearingAccuracy()) location.bearingAccuracyDegrees else null,
                speedMetersPerSecond = if (location.hasSpeed()) location.speed else null,
                speedAccuracyMetersPerSecond = if (location.hasSpeedAccuracy()) location.speedAccuracyMetersPerSecond else null,
                altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                provider = location.provider ?: "unknown",
            ),
        )
    }

    // Older platform versions call these abstractly; newer ones have defaults.
    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) = Unit
    override fun onProviderEnabled(provider: String) = Unit
    override fun onProviderDisabled(provider: String) = Unit

    private fun startLocation(handlerThread: HandlerThread): Boolean {
        val manager = locationManager ?: return false
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return try {
            if (!manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) return false
            manager.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, this, handlerThread.looper)
            true
        } catch (t: Throwable) {
            Log.w(TAG, "GPS updates unavailable for recording", t)
            false
        }
    }

    private fun manifest(
        calibration: CameraCalibration?,
        profile: CalibrationProfile?,
        displayRotationDegrees: Int,
        startBootNanos: Long,
        locationRecorded: Boolean,
    ): Map<String, String> = buildMap {
        put(SessionFormat.KEY_DEVICE_MODEL, Build.MODEL.orEmpty())
        put(SessionFormat.KEY_DEVICE_MANUFACTURER, Build.MANUFACTURER.orEmpty())
        put(SessionFormat.KEY_ANDROID_RELEASE, Build.VERSION.RELEASE.orEmpty())
        put(SessionFormat.KEY_APP_VERSION, BuildConfig.VERSION_NAME)
        put(SessionFormat.KEY_START_BOOT_NANOS, startBootNanos.toString())
        put(SessionFormat.KEY_START_UTC_MILLIS, System.currentTimeMillis().toString())
        put(SessionFormat.KEY_DISPLAY_ROTATION, displayRotationDegrees.toString())
        put(SessionFormat.KEY_LOCATION_RECORDED, locationRecorded.toString())
        calibration?.let { c ->
            c.timestampSourceRealtime?.let { put(SessionFormat.KEY_CAMERA_TIMESTAMP_REALTIME, it.toString()) }
            val p = SessionFormat.PREFIX_CAMERA
            c.cameraId?.let { put(p + "id", it) }
            put(p + "physicalIds", c.physicalCameraIds.joinToString(" "))
            c.sensorOrientationDegrees?.let { put(p + "sensorOrientationDegrees", it.toString()) }
            c.focalLengthMm?.let { put(p + "focalLengthMm", it.toString()) }
            c.sensorWidthMm?.let { put(p + "sensorWidthMm", it.toString()) }
            c.sensorHeightMm?.let { put(p + "sensorHeightMm", it.toString()) }
            c.activeArrayWidth?.let { put(p + "activeArrayWidth", it.toString()) }
            c.activeArrayHeight?.let { put(p + "activeArrayHeight", it.toString()) }
            c.hardwareLevel?.let { put(p + "hardwareLevel", it.toString()) }
            c.rawIntrinsicCalibration?.let { put(p + "reported.intrinsicCalibration", it.joinToString(" ")) }
            c.lensDistortion?.let { put(p + "reported.distortion", it.joinToString(" ")) }
            c.bufferIntrinsics?.let { k ->
                put(p + "used.source", k.source.name)
                put(p + "used.fx", k.focalXPixels.toString())
                put(p + "used.fy", k.focalYPixels.toString())
                put(p + "used.cx", k.principalXPixels.toString())
                put(p + "used.cy", k.principalYPixels.toString())
                put(p + "used.width", k.imageWidth.toString())
                put(p + "used.height", k.imageHeight.toString())
            }
            c.estimatedIntrinsics?.let { put(p + "estimated.fx", it.focalXPixels.toString()) }
        }
        profile?.toMap()?.forEach { (key, value) -> put(SessionFormat.PREFIX_PROFILE + key, value) }
    }

    private companion object {
        const val TAG = "SessionRecorder"
        const val IMU_PERIOD_MICROS = 10_000 // 100 Hz
        const val MIN_FRAME_INTERVAL_NANOS = 95_000_000L // about 10 frames per second
        const val JPEG_QUALITY = 85
        const val MAX_DURATION_NANOS = 20L * 60 * 1_000_000_000L
        const val MIN_FREE_BYTES = 500L * 1024 * 1024
        const val STORAGE_CHECK_EVERY_FRAMES = 50

        val SENSORS: Map<Int, ImuSensor> = mapOf(
            Sensor.TYPE_ACCELEROMETER to ImuSensor.Accelerometer,
            Sensor.TYPE_GYROSCOPE to ImuSensor.Gyroscope,
            Sensor.TYPE_GAME_ROTATION_VECTOR to ImuSensor.GameRotationVector,
            Sensor.TYPE_ROTATION_VECTOR to ImuSensor.RotationVector,
            Sensor.TYPE_MAGNETIC_FIELD to ImuSensor.MagneticField,
        )
    }
}
