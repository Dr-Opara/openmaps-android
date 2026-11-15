package com.openmaps.app.vision

import android.hardware.camera2.CameraCharacteristics
import android.util.Log
import android.util.Size
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.ImageAnalysis
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
 * Stage 1 [CameraFrameProvider]: rides on the existing CameraX rear-camera binding and reports each
 * frame's timestamp, size and rotation plus the camera's reported lens/sensor geometry.
 *
 * It reads **no pixels** — every frame is closed immediately — so it proves the frame clock and
 * the calibration inputs without paying for image processing that Stage 2 has not earned yet.
 */
@Suppress("LogNotTimber")
class CameraXFrameProvider : CameraFrameProvider, CameraXRearEngine.CameraCompanion {

    private val _latestFrame = MutableStateFlow<FrameMetadata?>(null)
    val latestFrame: StateFlow<FrameMetadata?> = _latestFrame.asStateFlow()
    override val frames: Flow<FrameMetadata> = _latestFrame.filterNotNull()

    private val _latestCalibration = MutableStateFlow<CameraCalibration?>(null)
    val latestCalibration: StateFlow<CameraCalibration?> = _latestCalibration.asStateFlow()
    override val calibration: Flow<CameraCalibration> = _latestCalibration.filterNotNull()

    /** Smoothed interval between analysed frames, in milliseconds; 0 until two frames have arrived. */
    private val _frameIntervalMillis = MutableStateFlow(0.0)
    val frameIntervalMillis: StateFlow<Double> = _frameIntervalMillis.asStateFlow()

    private var executor: ExecutorService? = null

    @Volatile
    private var rawCalibration: RawCalibration? = null

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
                val frame = FrameMetadata(
                    timestampNanos = image.imageInfo.timestamp,
                    bufferWidth = image.width,
                    bufferHeight = image.height,
                    rotationDegrees = image.imageInfo.rotationDegrees,
                )
                val previous = _latestFrame.value
                if (previous != null) {
                    val interval = (frame.timestampNanos - previous.timestampNanos) / 1_000_000.0
                    if (interval > 0 && interval < 2_000) {
                        val smoothed = _frameIntervalMillis.value
                        _frameIntervalMillis.value = if (smoothed == 0.0) interval else smoothed * 0.9 + interval * 0.1
                    }
                }
                if (previous == null || previous.bufferWidth != frame.bufferWidth || previous.bufferHeight != frame.bufferHeight) {
                    publishCalibration(frame.bufferWidth, frame.bufferHeight)
                }
                _latestFrame.value = frame
            } finally {
                image.close()
            }
        }
        return listOf(analysis)
    }

    @OptIn(ExperimentalCamera2Interop::class)
    override fun onBound(camera: Camera) {
        rawCalibration = try {
            val info = Camera2CameraInfo.from(camera.cameraInfo)
            val physicalSize = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE)
            RawCalibration(
                focalLengthMm = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)
                    ?.firstOrNull()?.toDouble(),
                sensorWidthMm = physicalSize?.width?.toDouble(),
                sensorHeightMm = physicalSize?.height?.toDouble(),
                sensorOrientationDegrees = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_ORIENTATION),
                reportsIntrinsicCalibration =
                info.getCameraCharacteristic(CameraCharacteristics.LENS_INTRINSIC_CALIBRATION) != null,
            )
        } catch (t: Throwable) {
            Log.w(TAG, "Camera characteristics unavailable", t)
            null
        }
        _latestFrame.value?.let { publishCalibration(it.bufferWidth, it.bufferHeight) }
    }

    override fun onReleased() {
        executor?.shutdown()
        executor = null
        rawCalibration = null
        _latestFrame.value = null
        _latestCalibration.value = null
        _frameIntervalMillis.value = 0.0
    }

    private fun publishCalibration(bufferWidth: Int, bufferHeight: Int) {
        val raw = rawCalibration ?: return
        val intrinsics = if (raw.focalLengthMm != null && raw.sensorWidthMm != null && raw.sensorHeightMm != null) {
            CameraIntrinsics.fromSensorGeometry(
                focalLengthMm = raw.focalLengthMm,
                sensorWidthMm = raw.sensorWidthMm,
                sensorHeightMm = raw.sensorHeightMm,
                imageWidth = bufferWidth,
                imageHeight = bufferHeight,
            )
        } else {
            null
        }
        _latestCalibration.value = CameraCalibration(
            bufferIntrinsics = intrinsics,
            sensorOrientationDegrees = raw.sensorOrientationDegrees,
            focalLengthMm = raw.focalLengthMm,
            sensorWidthMm = raw.sensorWidthMm,
            sensorHeightMm = raw.sensorHeightMm,
            deviceReportsIntrinsicCalibration = raw.reportsIntrinsicCalibration,
        )
    }

    private data class RawCalibration(
        val focalLengthMm: Double?,
        val sensorWidthMm: Double?,
        val sensorHeightMm: Double?,
        val sensorOrientationDegrees: Int?,
        val reportsIntrinsicCalibration: Boolean,
    )

    private companion object {
        const val TAG = "CameraXFrameProvider"
        const val ANALYSIS_WIDTH = 640
        const val ANALYSIS_HEIGHT = 360
    }
}
