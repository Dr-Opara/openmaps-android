package com.openmaps.app.feature.live

import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openmaps.app.core.vision.CalibrationGrid
import com.openmaps.app.core.vision.FillCenterTransform
import com.openmaps.app.core.vision.GateEvidence
import com.openmaps.app.core.vision.GridSegment
import com.openmaps.app.core.vision.GroundPlaneProjector
import com.openmaps.app.core.vision.GuidanceConfidenceGate
import com.openmaps.app.core.vision.GuidanceDecision
import com.openmaps.app.core.vision.MountConfiguration
import com.openmaps.app.vision.CameraXFrameProvider
import com.openmaps.app.vision.DeviceAttitudeSource
import java.util.Locale

/**
 * Stage 1 calibration test pattern: a flat-ground grid and horizon line drawn from the camera's
 * reported geometry and the phone's tilt, plus the numbers they were computed from.
 *
 * **This is not navigation and not road detection.** The grid is centred on the camera and assumes
 * level ground; it does not follow the road, and it stays put when the road curves. Its only job is
 * to let a person check, on a real phone in a real mount, that projection and orientation are right
 * before anything road-relative is built on them. It is composed only when
 * `BuildConfig.LIVE_VISUAL_PROTOTYPE` is true, which release builds never are.
 */
@Composable
internal fun LiveCalibrationOverlay(
    frameProvider: CameraXFrameProvider,
    modifier: Modifier = Modifier,
    mount: MountConfiguration = MountConfiguration.UnmeasuredPrototype,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val attitudeSource = remember(context) {
        DeviceAttitudeSource(context) {
            when (view.display?.rotation) {
                Surface.ROTATION_90 -> 90
                Surface.ROTATION_180 -> 180
                Surface.ROTATION_270 -> 270
                else -> 0
            }
        }
    }
    DisposableEffect(attitudeSource) {
        attitudeSource.start()
        onDispose { attitudeSource.stop() }
    }

    val frame by frameProvider.latestFrame.collectAsState()
    val calibration by frameProvider.latestCalibration.collectAsState()
    val frameIntervalMillis by frameProvider.frameIntervalMillis.collectAsState()
    val attitude by attitudeSource.attitude.collectAsState()

    val currentFrame = frame
    val uprightIntrinsics = if (currentFrame != null) {
        calibration?.bufferIntrinsics?.rotatedUpright(currentFrame.rotationDegrees)
    } else {
        null
    }
    val currentAttitude = attitude
    // Sensor and camera timestamps share a clock on most devices; where they do not, the age is
    // meaningless and the gate (correctly) treats it as stale.
    val attitudeAgeNanos = if (currentFrame != null && currentAttitude != null) {
        kotlin.math.abs(currentFrame.timestampNanos - currentAttitude.timestampNanos)
    } else {
        null
    }
    val decision = GuidanceConfidenceGate.decide(
        GateEvidence(
            intrinsics = uprightIntrinsics,
            mount = mount,
            attitudeAgeNanos = attitudeAgeNanos,
        ),
    )

    Box(modifier.fillMaxSize()) {
        if (uprightIntrinsics != null && currentAttitude != null) {
            val projector = remember(uprightIntrinsics, mount) {
                GroundPlaneProjector(uprightIntrinsics, mount.cameraHeightMeters)
            }
            val segments = CalibrationGrid.segments(projector, currentAttitude)
            Canvas(Modifier.fillMaxSize()) {
                val transform = FillCenterTransform(
                    imageWidth = uprightIntrinsics.imageWidth,
                    imageHeight = uprightIntrinsics.imageHeight,
                    viewWidth = size.width,
                    viewHeight = size.height,
                )
                // Dashed on purpose: a test pattern should not look like a finished road overlay.
                val dashed = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
                for (segment in segments) {
                    drawLine(
                        color = when (segment.kind) {
                            GridSegment.Kind.Horizon -> Color(0xFFFFB84D)
                            GridSegment.Kind.Cross -> Color(0xFF7FE0FF)
                            GridSegment.Kind.Longitudinal -> Color(0xFFFFFFFF)
                        },
                        start = Offset(transform.toViewX(segment.from.u), transform.toViewY(segment.from.v)),
                        end = Offset(transform.toViewX(segment.to.u), transform.toViewY(segment.to.v)),
                        strokeWidth = if (segment.kind == GridSegment.Kind.Horizon) 3f else 2f,
                        pathEffect = dashed,
                    )
                }
            }
        }

        Text(
            text = buildString {
                append("CALIBRATION TEST PATTERN - not road detection\n")
                append("grid: +/-1.8 m, cross lines at 5/10/20/40 m, flat ground assumed\n")
                if (currentFrame != null) {
                    append("frame ${currentFrame.bufferWidth}x${currentFrame.bufferHeight} rot ${currentFrame.rotationDegrees}")
                    if (frameIntervalMillis > 0) append(String.format(Locale.US, " %.1f fps", 1000.0 / frameIntervalMillis))
                    append('\n')
                } else {
                    append("frame: none yet\n")
                }
                if (uprightIntrinsics != null) {
                    append(
                        String.format(
                            Locale.US,
                            "fx %.0f px, FOV %.1f x %.1f deg (%s)\n",
                            uprightIntrinsics.focalXPixels,
                            uprightIntrinsics.horizontalFovDegrees,
                            uprightIntrinsics.verticalFovDegrees,
                            uprightIntrinsics.source.name,
                        ),
                    )
                } else {
                    append("intrinsics: not reported by this camera\n")
                }
                calibration?.let {
                    append(
                        String.format(
                            Locale.US,
                            "lens %.2f mm, sensor %.2f x %.2f mm, orient %s\n",
                            it.focalLengthMm ?: Double.NaN,
                            it.sensorWidthMm ?: Double.NaN,
                            it.sensorHeightMm ?: Double.NaN,
                            it.sensorOrientationDegrees?.toString() ?: "?",
                        ),
                    )
                }
                if (currentAttitude != null) {
                    append(
                        String.format(
                            Locale.US,
                            "pitch %.1f deg down, roll %.1f deg\n",
                            Math.toDegrees(currentAttitude.pitchDownRadians),
                            Math.toDegrees(currentAttitude.rollRadians),
                        ),
                    )
                } else {
                    append(if (attitudeSource.isAvailable) "attitude: waiting\n" else "attitude: no rotation sensor\n")
                }
                append(
                    String.format(
                        Locale.US,
                        "mount %.2f m (%s)\n",
                        mount.cameraHeightMeters,
                        if (mount.heightMeasured) "measured" else "NOT measured",
                    ),
                )
                when (decision) {
                    is GuidanceDecision.RoadAnchored -> append("gate: road-anchored allowed")
                    is GuidanceDecision.ConventionalOnly ->
                        append("gate: standard guidance only\n  " + decision.reasons.joinToString(", ") { it.name })
                }
            },
            color = Color.White,
            fontSize = 10.sp,
            lineHeight = 13.sp,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .widthIn(max = 260.dp)
                .background(Color(0xB3050A14), RoundedCornerShape(8.dp))
                .padding(8.dp),
        )
    }
}
