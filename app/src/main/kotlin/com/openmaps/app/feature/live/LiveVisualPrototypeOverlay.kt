package com.openmaps.app.feature.live

import android.os.Build
import android.os.SystemClock
import android.view.Surface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openmaps.app.core.navigation.MapMatchConfidence
import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.core.routing.RouteResult
import com.openmaps.app.core.vision.CalibrationGrid
import com.openmaps.app.core.vision.CalibrationProfile
import com.openmaps.app.core.vision.CameraAttitude
import com.openmaps.app.core.vision.CameraCalibration
import com.openmaps.app.core.vision.DeviceTargets
import com.openmaps.app.core.vision.FillCenterTransform
import com.openmaps.app.core.vision.GateEvidence
import com.openmaps.app.core.vision.GridSegment
import com.openmaps.app.core.vision.GroundPlaneProjector
import com.openmaps.app.core.vision.GuidanceConfidenceGate
import com.openmaps.app.core.vision.GuidanceDecision
import com.openmaps.app.core.vision.MountDriftDetector
import com.openmaps.app.feature.map.NavigationState
import com.openmaps.app.vision.CalibrationProfileStore
import com.openmaps.app.vision.CameraXFrameProvider
import com.openmaps.app.vision.DeviceAttitudeSource
import com.openmaps.app.vision.recording.RecordingStore
import com.openmaps.app.vision.recording.SessionRecorder
import java.util.Locale

/**
 * Visual-navigation prototype controls, composed only when `BuildConfig.LIVE_VISUAL_PROTOTYPE` is
 * true (never in a release build):
 *
 *  - the **calibration test pattern** (horizon line, and a flat-ground grid once a lens height has
 *    been entered) with the numbers it is computed from;
 *  - the **calibration profile** editor for this phone and mount;
 *  - the **session recorder**, with consent, a recording indicator and delete controls.
 *
 * None of this is navigation or road detection. The grid is centred on the camera and assumes level
 * ground; it does not follow the road. The existing Live HUD above and below it is untouched.
 */
@Composable
internal fun LiveVisualPrototypeOverlay(
    frameProvider: CameraXFrameProvider,
    route: RouteResult?,
    navigationState: NavigationState,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val view = LocalView.current
    val displayRotation = {
        when (view.display?.rotation) {
            Surface.ROTATION_90 -> 90
            Surface.ROTATION_180 -> 180
            Surface.ROTATION_270 -> 270
            else -> 0
        }
    }
    val attitudeSource = remember(context) { DeviceAttitudeSource(context, displayRotation) }
    val profileStore = remember(context) { CalibrationProfileStore(context) }
    val recordingStore = remember(context) { RecordingStore(context) }
    val recorder = remember(frameProvider) { SessionRecorder(context, recordingStore, frameProvider) }
    DisposableEffect(attitudeSource, recorder) {
        attitudeSource.start()
        onDispose {
            recorder.stop(SessionRecorder.StopReason.CameraReleased)
            attitudeSource.stop()
        }
    }

    val frame by frameProvider.latestFrame.collectAsState()
    val calibration by frameProvider.latestCalibration.collectAsState()
    val frameIntervalMillis by frameProvider.frameIntervalMillis.collectAsState()
    val pipelineLatencyMillis by frameProvider.pipelineLatencyMillis.collectAsState()
    val attitude by attitudeSource.attitude.collectAsState()
    val recording by recorder.state.collectAsState()

    val deviceModel = Build.MODEL.orEmpty()
    val rotation = displayRotation()
    val cameraId = calibration?.cameraId
    var profileVersion by remember { mutableIntStateOf(0) }
    val profile = remember(deviceModel, cameraId, rotation, profileVersion) {
        profileStore.load(deviceModel, cameraId, rotation)
    }

    val currentFrame = frame
    val currentAttitude = attitude
    val uprightIntrinsics = if (currentFrame != null) {
        calibration?.bufferIntrinsics?.rotatedUpright(currentFrame.rotationDegrees)
    } else {
        null
    }
    val projectionAttitude = if (currentAttitude != null) profile?.corrected(currentAttitude) ?: currentAttitude else null

    // Has the phone moved since the mount angle was captured? Latches until the profile is saved again.
    val driftDetector = remember(profile) { MountDriftDetector() }
    val mountMoved = remember(driftDetector, currentAttitude) {
        if (profile != null && currentAttitude != null) {
            driftDetector.update(
                timestampNanos = currentAttitude.timestampNanos,
                pitchDownDegrees = Math.toDegrees(currentAttitude.pitchDownRadians),
                rollDegrees = Math.toDegrees(currentAttitude.rollRadians),
                profile = profile,
            )
        } else {
            false
        }
    }

    val progress = navigationState.activeProgress()
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
            profile = profile,
            mountMoved = mountMoved,
            attitudeAgeNanos = attitudeAgeNanos,
            mapMatchConfident = progress != null && progress.mapMatchConfidence == MapMatchConfidence.High && !progress.isOffRoute,
        ),
    )

    LaunchedEffect(route, recording is SessionRecorder.State.Recording) { route?.let(recorder::onRoute) }
    LaunchedEffect(progress) { progress?.let(recorder::onProgress) }

    var showCalibration by remember { mutableStateOf(false) }
    var showConsent by remember { mutableStateOf(false) }
    var showRecordings by remember { mutableStateOf(false) }
    val startRecording = {
        recorder.start(calibration = calibration, profile = profile, displayRotationDegrees = rotation, route = route)
    }

    Box(modifier.fillMaxSize()) {
        if (uprightIntrinsics != null && projectionAttitude != null) {
            val lensHeight = profile?.lensHeightMeters
            val segments = if (lensHeight != null && !mountMoved) {
                CalibrationGrid.segments(GroundPlaneProjector(uprightIntrinsics, lensHeight), projectionAttitude)
            } else {
                // The horizon needs no lens height, so it is the only thing drawn until one is entered.
                CalibrationGrid.horizonOnly(GroundPlaneProjector(uprightIntrinsics, cameraHeightMeters = 1.0), projectionAttitude)
            }
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

        // Recording indicator: always visible while anything is being recorded.
        (recording as? SessionRecorder.State.Recording)?.let { active ->
            val seconds = ((SystemClock.elapsedRealtimeNanos() - active.startedBootNanos) / 1_000_000_000L).coerceAtLeast(0)
            Row(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp)
                    .background(Color(0xE6B00020), RoundedCornerShape(50))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.size(10.dp).background(Color.White, CircleShape))
                Text(
                    text = String.format(Locale.US, "REC %02d:%02d  %d frames", seconds / 60, seconds % 60, active.frames),
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 8.dp)
                .widthIn(max = 270.dp)
                .background(Color(0xB3050A14), RoundedCornerShape(8.dp))
                .padding(8.dp),
        ) {
            Text(
                text = readout(
                    deviceModel = deviceModel,
                    calibration = calibration,
                    frameText = currentFrame?.let { "${it.bufferWidth}x${it.bufferHeight} rot ${it.rotationDegrees}" },
                    frameIntervalMillis = frameIntervalMillis,
                    pipelineLatencyMillis = pipelineLatencyMillis,
                    uprightIntrinsics = uprightIntrinsics,
                    attitude = currentAttitude,
                    attitudeAvailable = attitudeSource.isAvailable,
                    profile = profile,
                    mountMoved = mountMoved,
                    driftDegrees = driftDetector.deviationDegrees,
                    decision = decision,
                    recording = recording,
                ),
                color = Color.White,
                fontSize = 10.sp,
                lineHeight = 13.sp,
                fontFamily = FontFamily.Monospace,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                TextButton(onClick = { showCalibration = true }) { Text("Calibrate", fontSize = 12.sp) }
                if (recording is SessionRecorder.State.Recording) {
                    TextButton(onClick = { recorder.stop() }) { Text("Stop", fontSize = 12.sp, color = Color(0xFFFF8A80)) }
                } else {
                    TextButton(
                        onClick = {
                            recorder.dismissStopped()
                            if (recordingStore.consentGiven) {
                                startRecording()
                            } else {
                                showConsent = true
                            }
                        },
                    ) { Text("Record", fontSize = 12.sp) }
                }
                TextButton(onClick = { showRecordings = true }) { Text("Files", fontSize = 12.sp) }
            }
        }
    }

    if (showConsent) {
        AlertDialog(
            onDismissRequest = { showConsent = false },
            title = { Text("Record test drives on this phone?") },
            text = {
                Text(
                    "Recording saves, for engineering tests only:\n\n" +
                        "- camera frames of the road ahead (other people, vehicles and plates may be visible)\n" +
                        "- your precise GPS location and speed\n" +
                        "- motion-sensor readings\n" +
                        "- the route being navigated and the calibration values\n\n" +
                        "Files stay in this app's private storage on this phone. OpenMaps does not upload " +
                        "them anywhere. They leave the phone only if you copy them off yourself.\n\n" +
                        "A red REC badge shows whenever recording is on. You can delete recordings, and " +
                        "withdraw this permission, at any time under Files.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        recordingStore.consentGiven = true
                        showConsent = false
                        startRecording()
                    },
                ) { Text("I agree, start recording") }
            },
            dismissButton = { TextButton(onClick = { showConsent = false }) { Text("Not now") } },
        )
    }

    if (showRecordings) {
        var refresh by remember { mutableIntStateOf(0) }
        val sessions = remember(refresh, recording) { recordingStore.sessions() }
        AlertDialog(
            onDismissRequest = { showRecordings = false },
            title = { Text("Recordings on this phone") },
            text = {
                Column(Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState())) {
                    Text("Stored in: ${recordingStore.root.path}", fontSize = 11.sp)
                    Text(
                        if (recordingStore.consentGiven) "Recording permission: given" else "Recording permission: not given",
                        fontSize = 11.sp,
                    )
                    if (sessions.isEmpty()) Text("No recordings.", modifier = Modifier.padding(top = 12.dp))
                    for (session in sessions) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = String.format(
                                    Locale.US,
                                    "%s\n%d frames, %.1f MB",
                                    session.name,
                                    session.frameCount,
                                    session.bytes / 1_048_576.0,
                                ),
                                fontSize = 12.sp,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(
                                enabled = recording !is SessionRecorder.State.Recording,
                                onClick = {
                                    recordingStore.delete(session)
                                    refresh++
                                },
                            ) { Text("Delete") }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showRecordings = false }) { Text("Close") } },
            dismissButton = {
                Row {
                    TextButton(
                        enabled = recording !is SessionRecorder.State.Recording,
                        onClick = {
                            recordingStore.deleteAll()
                            refresh++
                        },
                    ) { Text("Delete all") }
                    TextButton(
                        enabled = recordingStore.consentGiven,
                        onClick = {
                            recorder.stop()
                            recordingStore.consentGiven = false
                            refresh++
                        },
                    ) { Text("Withdraw permission") }
                }
            },
        )
    }

    if (showCalibration) {
        CalibrationDialog(
            deviceModel = deviceModel,
            cameraId = cameraId,
            rotation = rotation,
            existing = profile,
            attitude = currentAttitude,
            onSave = { updated ->
                profileStore.save(updated)
                profileVersion++
                showCalibration = false
            },
            onClear = {
                profileStore.clear(deviceModel, cameraId, rotation)
                profileVersion++
                showCalibration = false
            },
            onDismiss = { showCalibration = false },
        )
    }
}

@Composable
private fun CalibrationDialog(
    deviceModel: String,
    cameraId: String?,
    rotation: Int,
    existing: CalibrationProfile?,
    attitude: CameraAttitude?,
    onSave: (CalibrationProfile) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    var heightText by remember { mutableStateOf(existing?.lensHeightMeters?.toString().orEmpty()) }
    var mount by remember { mutableStateOf(existing?.mountDescription?.takeIf { it.isNotBlank() } ?: "windshield") }
    var pitchTrim by remember { mutableStateOf(existing?.pitchTrimDegrees ?: 0.0) }
    var rollTrim by remember { mutableStateOf(existing?.rollTrimDegrees ?: 0.0) }
    var referencePitch by remember { mutableStateOf(existing?.referencePitchDownDegrees) }
    var referenceRoll by remember { mutableStateOf(existing?.referenceRollDegrees) }
    var verified by remember { mutableStateOf(existing?.verifiedOnDevice ?: false) }
    val parsedHeight = CalibrationProfile.parseLensHeight(heightText)
    val heightInvalid = heightText.isNotBlank() && parsedHeight == null

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Calibration profile") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                Text(
                    text = "$deviceModel" +
                        (if (DeviceTargets.isPrimary(deviceModel)) " (primary test device)" else " (not the primary test device ${DeviceTargets.PRIMARY_MODEL})") +
                        "\ncamera ${cameraId ?: "?"}, display rotation $rotation",
                    fontSize = 12.sp,
                )
                OutlinedTextField(
                    value = heightText,
                    onValueChange = { heightText = it },
                    label = { Text("Lens height above road (m)") },
                    supportingText = {
                        Text(
                            if (heightInvalid) {
                                "Enter metres between ${CalibrationProfile.MIN_LENS_HEIGHT_METERS} and ${CalibrationProfile.MAX_LENS_HEIGHT_METERS}"
                            } else {
                                "Tape-measure from the road to the centre of the main lens"
                            },
                        )
                    },
                    isError = heightInvalid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = mount,
                    onValueChange = { mount = it.take(40) },
                    label = { Text("Mount position") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                TrimRow("Pitch trim", pitchTrim) { pitchTrim = it }
                TrimRow("Roll trim", rollTrim) { rollTrim = it }
                Text(
                    text = if (referencePitch != null && referenceRoll != null) {
                        String.format(Locale.US, "Mount angle captured: pitch %.1f, roll %.1f deg", referencePitch, referenceRoll)
                    } else {
                        "Mount angle: not captured"
                    },
                    fontSize = 12.sp,
                    modifier = Modifier.padding(top = 8.dp),
                )
                TextButton(
                    enabled = attitude != null,
                    onClick = {
                        attitude?.let {
                            referencePitch = Math.toDegrees(it.pitchDownRadians)
                            referenceRoll = Math.toDegrees(it.rollRadians)
                        }
                    },
                ) { Text("Capture mount angle now (vehicle parked, level ground)") }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = verified, onCheckedChange = { verified = it })
                    Text("I checked the grid against a tape-measured mark on this phone and mount", fontSize = 12.sp)
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = !heightInvalid,
                onClick = {
                    onSave(
                        CalibrationProfile(
                            deviceModel = deviceModel,
                            cameraId = cameraId,
                            displayRotationDegrees = rotation,
                            mountDescription = mount.trim(),
                            lensHeightMeters = parsedHeight,
                            pitchTrimDegrees = pitchTrim,
                            rollTrimDegrees = rollTrim,
                            referencePitchDownDegrees = referencePitch,
                            referenceRollDegrees = referenceRoll,
                            // A profile cannot be "verified" without the things verification depends on.
                            verifiedOnDevice = verified && parsedHeight != null && referencePitch != null,
                            savedAtEpochMillis = System.currentTimeMillis(),
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("Clear") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun TrimRow(label: String, value: Double, onChange: (Double) -> Unit) {
    val limit = CalibrationProfile.MAX_TRIM_DEGREES
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(String.format(Locale.US, "%s %+.1f deg", label, value), fontSize = 12.sp, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange(((value - 0.1) * 10).let { Math.round(it) / 10.0 }.coerceIn(-limit, limit)) }) { Text("-0.1") }
        TextButton(onClick = { onChange(((value + 0.1) * 10).let { Math.round(it) / 10.0 }.coerceIn(-limit, limit)) }) { Text("+0.1") }
    }
}

private fun NavigationState.activeProgress(): RouteProgress? = when (this) {
    is NavigationState.Active -> progress
    is NavigationState.OffRoute -> progress
    is NavigationState.Arrived -> progress
    else -> null
}

private fun readout(
    deviceModel: String,
    calibration: CameraCalibration?,
    frameText: String?,
    frameIntervalMillis: Double,
    pipelineLatencyMillis: Double,
    uprightIntrinsics: com.openmaps.app.core.vision.CameraIntrinsics?,
    attitude: CameraAttitude?,
    attitudeAvailable: Boolean,
    profile: CalibrationProfile?,
    mountMoved: Boolean,
    driftDegrees: Double,
    decision: GuidanceDecision,
    recording: SessionRecorder.State,
): String = buildString {
    append("CALIBRATION TEST PATTERN - not road detection\n")
    append(deviceModel)
    append(if (DeviceTargets.isPrimary(deviceModel)) " (primary target)\n" else " (not primary target)\n")
    if (calibration != null) {
        append("camera id ${calibration.cameraId ?: "?"}")
        if (calibration.physicalCameraIds.isNotEmpty()) append(" [physical ${calibration.physicalCameraIds.joinToString(",")}]")
        append('\n')
    }
    if (frameText != null) {
        append("frame $frameText")
        if (frameIntervalMillis > 0) append(String.format(Locale.US, " %.1f fps", 1000.0 / frameIntervalMillis))
        append('\n')
        if (pipelineLatencyMillis > 0) append(String.format(Locale.US, "camera latency %.0f ms\n", pipelineLatencyMillis))
    } else {
        append("frame: none yet\n")
    }
    if (uprightIntrinsics != null) {
        append(
            String.format(
                Locale.US,
                "fx %.0f px, FOV %.1f x %.1f deg\nintrinsics: %s\n",
                uprightIntrinsics.focalXPixels,
                uprightIntrinsics.horizontalFovDegrees,
                uprightIntrinsics.verticalFovDegrees,
                when (uprightIntrinsics.source) {
                    com.openmaps.app.core.vision.IntrinsicsSource.SensorGeometry -> "ESTIMATED from lens+sensor"
                    com.openmaps.app.core.vision.IntrinsicsSource.DeviceReported -> "REPORTED by device"
                    com.openmaps.app.core.vision.IntrinsicsSource.Measured -> "MEASURED"
                },
            ),
        )
        val reported = calibration?.reportedIntrinsics
        val estimated = calibration?.estimatedIntrinsics
        if (reported != null && estimated != null && estimated.focalXPixels > 0) {
            append(
                String.format(
                    Locale.US,
                    "reported vs estimated fx: %+.1f%%\n",
                    (reported.focalXPixels / estimated.focalXPixels - 1) * 100,
                ),
            )
        }
    } else {
        append("intrinsics: not available\n")
    }
    if (calibration != null) {
        append(
            String.format(
                Locale.US,
                "lens %.2f mm, sensor %.2f x %.2f mm, orient %s\n",
                calibration.focalLengthMm ?: Double.NaN,
                calibration.sensorWidthMm ?: Double.NaN,
                calibration.sensorHeightMm ?: Double.NaN,
                calibration.sensorOrientationDegrees?.toString() ?: "?",
            ),
        )
        append("device intrinsic cal: ${if (calibration.deviceReportsIntrinsicCalibration) "yes" else "no"}")
        append(", distortion: ${if (calibration.deviceReportsDistortion) "yes (not applied)" else "no"}\n")
        append(
            "frame clock: " + when (calibration.timestampSourceRealtime) {
                true -> "boot clock"
                false -> "own clock (offset estimated)"
                null -> "not reported"
            } + "\n",
        )
    }
    if (attitude != null) {
        append(
            String.format(
                Locale.US,
                "sensor pitch %.1f down, roll %.1f deg\n",
                Math.toDegrees(attitude.pitchDownRadians),
                Math.toDegrees(attitude.rollRadians),
            ),
        )
    } else {
        append(if (attitudeAvailable) "attitude: waiting\n" else "attitude: no rotation sensor\n")
    }
    if (profile == null) {
        append("profile: NONE - tap Calibrate\n")
    } else {
        append(
            if (profile.lensHeightMeters != null) {
                String.format(Locale.US, "lens height %.2f m (entered)\n", profile.lensHeightMeters)
            } else {
                "lens height: NOT ENTERED - grid hidden\n"
            },
        )
        append(String.format(Locale.US, "trim pitch %+.1f roll %+.1f deg\n", profile.pitchTrimDegrees, profile.rollTrimDegrees))
        append(
            when {
                !profile.hasMountAngle -> "mount angle: not captured\n"
                mountMoved -> "MOUNT MOVED - recalibrate (grid hidden)\n"
                else -> String.format(Locale.US, "mount drift %.1f deg\n", driftDegrees)
            },
        )
        append(if (profile.verifiedOnDevice) "grid check: confirmed by tester\n" else "grid check: not confirmed\n")
    }
    when (recording) {
        is SessionRecorder.State.Recording -> append(
            String.format(
                Locale.US,
                "recording: %d frames, write %.0f ms%s\n",
                recording.frames,
                recording.lastFrameWriteMillis,
                if (recording.locationRecorded) "" else ", NO GPS",
            ),
        )
        is SessionRecorder.State.Stopped -> append("recording stopped (${recording.reason.name}): ${recording.frames} frames\n")
        SessionRecorder.State.Idle -> Unit
    }
    when (decision) {
        is GuidanceDecision.RoadAnchored -> append("gate: road-anchored allowed")
        is GuidanceDecision.ConventionalOnly ->
            append("gate: standard guidance only\n  " + decision.reasons.joinToString(", ") { it.name })
    }
}
