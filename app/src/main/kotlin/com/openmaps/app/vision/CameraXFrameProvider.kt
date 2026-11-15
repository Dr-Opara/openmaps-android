package com.openmaps.app.vision

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.os.Build
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import com.openmaps.app.camera.CameraXRearEngine
import com.openmaps.app.core.vision.CameraCalibration
import com.openmaps.app.core.vision.CameraFrameProvider
import com.openmaps.app.core.vision.CameraIntrinsics
import com.openmaps.app.core.vision.FrameMetadata
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * [CameraFrameProvider] on the existing CameraX rear-camera binding.
 *
 * Reports each frame's timestamp, size and rotation and everything the device publishes about the
 * camera. It processes no pixels itself; a [FrameSink] (the session recorder) may be attached to
 * receive frames, and each frame is closed as soon as the sink returns.
 *
 * **Which camera.** The default CameraX back camera is normally the main wide camera, but that is
 * a convention, not a contract. [cameraSelector] makes the choice explicit and repeatable: the
 * back-facing camera with the lowest numeric Camera2 id (id "0" on current Samsung, Pixel and most
 * other phones is the main camera). The chosen id, focal length and field of view are shown in the
 * overlay and stored with every recording so the choice can be confirmed on the actual phone.
 */
@Suppress("LogNotTimber")
class CameraXFrameProvider(context: Context) : CameraFrameProvider, CameraXRearEngine.CameraCompanion {

    private val appContext: Context = context.applicationContext


    /** Receives frames on the analysis thread. Must not close the image or keep it after returning. */
    fun interface FrameSink {
        fun onFrame(image: ImageProxy, metadata: FrameMetadata, arrivalBootNanos: Long)
    }

    @Volatile
    var frameSink: FrameSink? = null

    private val _latestFrame = MutableStateFlow<FrameMetadata?>(null)
    val latestFrame: StateFlow<FrameMetadata?> = _latestFrame.asStateFlow()
    override val frames: Flow<FrameMetadata> = _latestFrame.filterNotNull()

    private val _latestCalibration = MutableStateFlow<CameraCalibration?>(null)
    val latestCalibration: StateFlow<CameraCalibration?> = _latestCalibration.asStateFlow()
    override val calibration: Flow<CameraCalibration> = _latestCalibration.filterNotNull()

    /** Smoothed interval between analysed frames, in milliseconds; 0 until two frames have arrived. */
    private val _frameIntervalMillis = MutableStateFlow(0.0)
    val frameIntervalMillis: StateFlow<Double> = _frameIntervalMillis.asStateFlow()

    /** Smoothed time from sensor timestamp to the frame reaching the app, in milliseconds (same-clock devices only). */
    private val _pipelineLatencyMillis = MutableStateFlow(0.0)
    val pipelineLatencyMillis: StateFlow<Double> = _pipelineLatencyMillis.asStateFlow()

    private var executor: ExecutorService? = null

    @Volatile
    private var raw: RawCalibration? = null

    @OptIn(ExperimentalCamera2Interop::class)
    override fun cameraSelector(): CameraSelector = CameraSelector.Builder()
        .requireLensFacing(CameraSelector.LENS_FACING_BACK)
        .addCameraFilter { cameras ->
            val main = cameras.minByOrNull { info ->
                try {
                    Camera2CameraInfo.from(info).cameraId.toIntOrNull() ?: Int.MAX_VALUE
                } catch (t: Throwable) {
                    Int.MAX_VALUE
                }
            }
            if (main != null) listOf(main) else cameras
        }
        .build()

    override fun useCases(): List<UseCase> {
        val analysisExecutor = executor ?: Executors.newSingleThreadExecutor().also { executor = it }
        val analysis = ImageAnalysis.Builder()
            // Same aspect ratio as the preview so both show the same field of view.
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        ResolutionStrategy(
                            Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                            ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                        ),
                    )
                    .build(),
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(analysisExecutor) { image ->
            try {
                val arrivalBootNanos = SystemClock.elapsedRealtimeNanos()
                val frame = FrameMetadata(
                    timestampNanos = image.imageInfo.timestamp,
                    bufferWidth = image.width,
                    bufferHeight = image.height,
                    rotationDegrees = image.imageInfo.rotationDegrees,
                )
                val previous = _latestFrame.value
                if (previous != null) {
                    val interval = (frame.timestampNanos - previous.timestampNanos) / 1_000_000.0
                    if (interval > 0 && interval < 2_000) _frameIntervalMillis.value = smooth(_frameIntervalMillis.value, interval)
                }
                val latency = (arrivalBootNanos - frame.timestampNanos) / 1_000_000.0
                if (latency >= 0 && latency < 500) _pipelineLatencyMillis.value = smooth(_pipelineLatencyMillis.value, latency)
                if (previous == null || previous.bufferWidth != frame.bufferWidth || previous.bufferHeight != frame.bufferHeight) {
                    publishCalibration(frame.bufferWidth, frame.bufferHeight)
                }
                _latestFrame.value = frame
                frameSink?.onFrame(image, frame, arrivalBootNanos)
            } catch (t: Throwable) {
                Log.w(TAG, "Frame handling failed", t)
            } finally {
                image.close()
            }
        }
        return listOf(analysis)
    }

    private fun smooth(current: Double, sample: Double): Double =
        if (current == 0.0) sample else current * 0.9 + sample * 0.1

    @OptIn(ExperimentalCamera2Interop::class)
    override fun onBound(camera: Camera) {
        raw = try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            val physicalSize = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            // LENS_INTRINSIC_CALIBRATION is expressed in the pre-correction active array.
            val array = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PRE_CORRECTION_ACTIVE_ARRAY_SIZE)
                ?: info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val distortion = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                info.getCameraCharacteristic(CameraCharacteristics.LENS_DISTORTION)
            } else {
                null
            }
            val physicalIds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    // Platform API, no camera permission needed: which physical lenses sit behind this id.
                    val manager = appContext.getSystemService(Context.CAMERA_SERVICE) as? CameraManager
                    manager?.getCameraCharacteristics(info.cameraId)?.physicalCameraIds?.sorted().orEmpty()
                } catch (t: Throwable) {
                    emptyList()
                }
            } else {
                emptyList()
            }
            RawCalibration(
                cameraId = info.cameraId,
                physicalCameraIds = physicalIds,
                focalLengthMm = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull()?.toDouble(),
                sensorWidthMm = physicalSize?.width?.toDouble(),
                sensorHeightMm = physicalSize?.height?.toDouble(),
                sensorOrientationDegrees = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION),
                intrinsicCalibration = info.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION)
                    ?.map { it.toDouble() },
                distortion = distortion?.map { it.toDouble() },
                arrayWidth = array?.width(),
                arrayHeight = array?.height(),
                timestampRealtime = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
                    ?.let { it == CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME },
                hardwareLevel = info.getCameraCharacteristic(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL),
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Camera characteristics unavailable", t)
            null
        }
        _latestFrame.value?.let { publishCalibration(it.bufferWidth, it.bufferHeight) }
    }

    override fun onReleased() {
        frameSink = null
        executor?.shutdown()
        executor = null
        raw = null
        _latestFrame.value = null
        _latestCalibration.value = null
        _frameIntervalMillis.value = 0.0
        _pipelineLatencyMillis.value = 0.0
    }

    private fun publishCalibration(bufferWidth: Int, bufferHeight: Int) {
        val r = raw ?: return
        val estimated = if (r.focalLengthMm != null && r.sensorWidthMm != null && r.sensorHeightMm != null) {
            CameraIntrinsics.fromSensorGeometry(r.focalLengthMm, r.sensorWidthMm, r.sensorHeightMm, bufferWidth, bufferHeight)
        } else {
            null
        }
        val k = r.intrinsicCalibration
        val reported = if (k != null && k.size >= 4 && r.arrayWidth != null && r.arrayHeight != null) {
            CameraIntrinsics.fromDeviceCalibration(k[0], k[1], k[2], k[3], r.arrayWidth, r.arrayHeight, bufferWidth, bufferHeight)
        } else {
            null
        }
        _latestCalibration.value = CameraCalibration(
            cameraId = r.cameraId,
            physicalCameraIds = r.physicalCameraIds,
            bufferIntrinsics = reported ?: estimated,
            reportedIntrinsics = reported,
            estimatedIntrinsics = estimated,
            sensorOrientationDegrees = r.sensorOrientationDegrees,
            focalLengthMm = r.focalLengthMm,
            sensorWidthMm = r.sensorWidthMm,
            sensorHeightMm = r.sensorHeightMm,
            rawIntrinsicCalibration = k,
            lensDistortion = r.distortion,
            activeArrayWidth = r.arrayWidth,
            activeArrayHeight = r.arrayHeight,
            timestampSourceRealtime = r.timestampRealtime,
            hardwareLevel = r.hardwareLevel,
        )
    }

    private data class RawCalibration(
        val cameraId: String?,
        val physicalCameraIds: List<String>,
        val focalLengthMm: Double?,
        val sensorWidthMm: Double?,
        val sensorHeightMm: Double?,
        val sensorOrientationDegrees: Int?,
        val intrinsicCalibration: List<Double>?,
        val distortion: List<Double>?,
        val arrayWidth: Int?,
        val arrayHeight: Int?,
        val timestampRealtime: Boolean?,
        val hardwareLevel: Int?,
    )

    private companion object {
        const val TAG = "CameraXFrameProvider"
        const val ANALYSIS_WIDTH = 1280
        const val ANALYSIS_HEIGHT = 720
    }
}
