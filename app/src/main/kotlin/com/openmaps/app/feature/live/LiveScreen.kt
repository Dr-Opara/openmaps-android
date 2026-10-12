package com.openmaps.app.feature.live

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openmaps.app.R
import com.openmaps.app.camera.CameraEngineEvent
import com.openmaps.app.camera.CameraFailure
import com.openmaps.app.camera.CameraXRearEngine
import com.openmaps.app.camera.RearCameraHost
import com.openmaps.app.camera.RearCameraState
import com.openmaps.app.core.camera.CameraPermissionState
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.OpenMapsWordmark
import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.feature.map.MapModeToggle
import com.openmaps.app.map.MapMode
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Live: the rear-camera view with a navigation HUD over it (Phase 5).
 *
 * **Ownership**: this screen *consumes* navigation state and never owns it. `hudState` comes
 * straight from the app's single `MapViewModel`, and `LiveViewModel` holds only camera
 * permission/health. Nothing here starts, stops, copies or mutates a trip — which is exactly why
 * Map -> Live -> Map preserves navigation with no extra bookkeeping.
 *
 * Readability over a live camera feed is the design constraint that shapes everything below: the
 * background is unpredictable, so text sits on a directional scrim (strongest behind the text,
 * fading out) rather than on blurred glass, and no panel uses backdrop blur — that reads as
 * generic glassmorphism and costs real GPU time on every frame.
 */
@Composable
fun LiveScreen(
    viewModel: LiveViewModel,
    hudState: LiveHudState,
    isNavigating: Boolean,
    onModeSelected: (MapMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier
            .fillMaxSize()
            .background(OpenMapsColors.Navy950),
    ) {
        when {
            state.cameraPermission == CameraPermissionState.Granted ->
                RearCameraPreview(state.cameraState, viewModel::onCameraEvent, Modifier.fillMaxSize())

            else ->
                // No camera permission: an opaque fallback so the map underneath never shows
                // through, and the user always has a way back.
                CameraUnavailableBackdrop()
        }

        // Transparent touch sink over the camera. It sits *above* the preview but *below* every
        // control, so HUD buttons still win the hit test while stray gestures cannot reach the
        // always-composed MapLibre view underneath (a native view receives touches regardless of
        // Compose z-order, which is why a sink layer is needed rather than relying on opacity).
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) { detectTapGestures { } },
        )

        // Scrims: top for the bar/maneuver, bottom for the summary. Not blur, not glass.
        Box(
            Modifier
                .fillMaxWidth()
                .height(300.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(OpenMapsColors.Navy950.copy(alpha = 0.85f), Color.Transparent),
                    ),
                ),
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(260.dp)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, OpenMapsColors.Navy950.copy(alpha = 0.9f)),
                    ),
                ),
        )

        LiveTopBar(
            isNavigating = isNavigating,
            onBackToMap = { onModeSelected(MapMode.Map) },
            onModeSelected = onModeSelected,
            modifier = Modifier.align(Alignment.TopCenter),
        )

        // The camera gate comes FIRST, independently of navigation state. Navigation state and
        // camera permission are unrelated, so gating the HUD on `hudState` alone meant an active
        // trip rendered turn-by-turn text over a black screen with no way to grant the camera.
        // Guidance is only actionable when the road is actually visible.
        val cameraUsable = state.isCameraUsable
        if (!cameraUsable) {
            CameraPermissionOrFailurePanel(
                state = state,
                onRequestPermission = viewModel::onRequestCameraPermission,
                onBackToMap = { onModeSelected(MapMode.Map) },
                modifier = Modifier.align(Alignment.Center),
            )
            return@Box
        }

        when (val hud = hudState) {
            LiveHudState.Idle -> Unit

            is LiveHudState.Maneuver -> LiveManeuverHud(
                hud = hud,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp, start = 16.dp, end = 16.dp),
            )

            is LiveHudState.Rerouting -> LiveStatusHud(
                title = stringResource(R.string.live_rerouting),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp, start = 16.dp, end = 16.dp),
            )

            is LiveHudState.OffRoute -> LiveStatusHud(
                title = stringResource(R.string.live_off_route),
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp, start = 16.dp, end = 16.dp),
            )

            is LiveHudState.Arrived -> LiveArrivedHud(
                destinationName = hud.destinationName,
                onBackToMap = { onModeSelected(MapMode.Map) },
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 40.dp),
            )
        }

        // Bottom trip summary: present for every navigating state except arrival, which has its own
        // call to action instead.
        val summaryHud = hudState
        if (summaryHud is LiveHudState.Maneuver) {
            LiveTripSummary(
                hud = summaryHud,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 40.dp),
            )
        }
    }
}

/** Camera preview plus whatever the camera itself needs to say. */
@Composable
private fun RearCameraPreview(
    cameraState: RearCameraState,
    onCameraEvent: (CameraEngineEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val engine = remember { CameraXRearEngine() }
    Box(modifier) {
        RearCameraHost(engine = engine, onEvent = onCameraEvent, modifier = Modifier.fillMaxSize())

        when (cameraState) {
            is RearCameraState.Starting, RearCameraState.Idle -> CameraStatusOverlay(
                text = stringResource(R.string.live_camera_starting),
                showSpinner = true,
            )

            is RearCameraState.Unavailable -> CameraStatusOverlay(
                text = stringResource(cameraState.failure.messageRes()),
                showSpinner = false,
            )

            RearCameraState.Ready -> Unit
        }
    }
}

/** Permission prompt, denial explanation, or camera failure — all recoverable, none fatal. */
@Composable
private fun CameraPermissionOrFailurePanel(
    state: LiveUiState,
    onRequestPermission: () -> Unit,
    onBackToMap: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        when (state.cameraPermission) {
            CameraPermissionState.NotRequested, CameraPermissionState.Unknown -> {
                Text(
                    text = stringResource(R.string.live_permission_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = OpenMapsColors.TextPrimary,
                )
                Text(
                    text = stringResource(R.string.live_permission_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                )
                Button(onClick = onRequestPermission) {
                    Text(stringResource(R.string.live_permission_action_allow))
                }
            }

            CameraPermissionState.Denied, CameraPermissionState.PermanentlyDenied -> {
                Text(
                    text = stringResource(R.string.live_permission_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = OpenMapsColors.TextPrimary,
                )
                Text(
                    text = if (state.cameraPermission == CameraPermissionState.PermanentlyDenied) {
                        stringResource(R.string.live_permission_blocked_body)
                    } else {
                        stringResource(R.string.live_permission_denied_body)
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                )
                Button(onClick = onRequestPermission) {
                    Text(
                        if (state.cameraPermission == CameraPermissionState.PermanentlyDenied) {
                            stringResource(R.string.live_permission_action_settings)
                        } else {
                            stringResource(R.string.live_permission_action_allow)
                        },
                    )
                }
            }

            CameraPermissionState.Granted -> {
                // Permission is fine but the camera failed: explain, offer retry, never trap the user.
                val failure = state.cameraFailure
                Text(
                    text = stringResource(R.string.live_camera_unavailable),
                    style = MaterialTheme.typography.titleLarge,
                    color = OpenMapsColors.TextPrimary,
                )
                Text(
                    text = stringResource(failure?.messageRes() ?: R.string.live_camera_unknown_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onBackToMap) {
                        Text(stringResource(R.string.live_arrived_action))
                    }
                    Button(onClick = onRequestPermission) {
                        Text(stringResource(R.string.live_camera_retry))
                    }
                }
            }
        }
    }
}

/**
 * Brand + Live active state + the way back to the map. Deliberately two controls only: on a bright
 * road at speed, more chrome is more distraction.
 */
@Composable
private fun LiveTopBar(
    isNavigating: Boolean,
    onBackToMap: () -> Unit,
    onModeSelected: (MapMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            OpenMapsWordmark(Modifier.padding(start = 4.dp))
            if (isNavigating) {
                Spacer(Modifier.width(10.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(OpenMapsColors.ElectricBlueFilled)
                        .padding(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Box(
                        Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color.White),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.live_navigating),
                        style = MaterialTheme.typography.labelMedium,
                        color = Color.White,
                    )
                }
            }
        }
        // A direct close affordance in addition to the toggle, so returning to the map is always one
        // tap and never requires hitting the correct half of a segmented control.
        IconButton(onClick = onBackToMap) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.cd_back_to_map),
                tint = OpenMapsColors.TextPrimary,
            )
        }
        MapModeToggle(selected = MapMode.Live, onSelected = onModeSelected)
    }
}

/**
 * The maneuver row: arrow, "in X", the instruction, and the following maneuver as a secondary line.
 *
 * Fixed min height so the row does not reflow every time the distance text changes width, which
 * would be distracting at speed.
 */
@Composable
private fun LiveManeuverHud(hud: LiveHudState.Maneuver, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(OpenMapsColors.Navy950.copy(alpha = 0.72f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = OpenMapsColors.ElectricBlue,
                modifier = Modifier
                    .size(44.dp)
                    .graphicsLayer { rotationZ = hud.type.arrowRotationDegrees },
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.live_turn_in_format, formatLiveDistance(hud.distanceToManeuverMeters)),
                    style = MaterialTheme.typography.labelLarge,
                    color = OpenMapsColors.ElectricBlue,
                )
                Text(
                    text = hud.instruction,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        hud.nextInstruction?.let { then ->
            Spacer(Modifier.height(8.dp))
            Text(
                text = stringResource(R.string.live_then_format, then),
                style = MaterialTheme.typography.bodySmall,
                color = OpenMapsColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Transient status (rerouting / off route). Camera keeps running underneath. */
@Composable
private fun LiveStatusHud(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(OpenMapsColors.Navy950.copy(alpha = 0.72f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(
            modifier = Modifier.size(18.dp),
            strokeWidth = 2.dp,
            color = OpenMapsColors.ElectricBlue,
        )
        Text(text = title, style = MaterialTheme.typography.titleMedium, color = Color.White)
    }
}

/** Bottom strip: destination, remaining distance and time. No recomputation — HUD values only. */
@Composable
private fun LiveTripSummary(hud: LiveHudState.Maneuver, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(OpenMapsColors.Navy950.copy(alpha = 0.72f))
            .padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        hud.destinationName?.let { name ->
            Text(
                text = stringResource(R.string.live_destination_format, name),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            text = stringResource(
                R.string.live_remaining_format,
                formatLiveDistance(hud.remainingDistanceMeters),
                formatLiveDuration(hud.remainingDurationSeconds),
            ),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
    }
}

@Composable
private fun LiveArrivedHud(destinationName: String?, onBackToMap: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(OpenMapsColors.Navy950.copy(alpha = 0.8f))
            .padding(horizontal = 18.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Icon(Icons.Filled.Check, contentDescription = null, tint = OpenMapsColors.ElectricBlue, modifier = Modifier.size(22.dp))
            Text(
                text = stringResource(R.string.live_arrived_title),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
            )
        }
        destinationName?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextSecondary,
            )
        }
        // Arrival is a resting state, not an error: one obvious way onward, and the app stays open.
        Button(onClick = onBackToMap) { Text(stringResource(R.string.live_arrived_action)) }
    }
}

@Composable
private fun CameraStatusOverlay(text: String, showSpinner: Boolean) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(OpenMapsColors.Navy950),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.padding(horizontal = 40.dp),
        ) {
            if (showSpinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(28.dp),
                    strokeWidth = 2.dp,
                    color = OpenMapsColors.ElectricBlue,
                )
            }
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextSecondary,
            )
        }
    }
}

/**
 * Opaque stand-in when there is no camera permission: never let the map show through Live.
 *
 * This layer, not the Live container, is where touches are swallowed. Live is layered over an
 * always-composed Map, so taps that miss the controls would otherwise land on the map underneath.
 * Putting the sink *behind* the controls means the controls still win the hit test — a
 * screen-sized `pointerInput` on the container swallows them instead.
 */
@Composable
private fun CameraUnavailableBackdrop(modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(OpenMapsColors.Navy800, OpenMapsColors.Navy950)))
            .pointerInput(Unit) { detectTapGestures { } },
    )
}

private fun CameraFailure.messageRes(): Int = when (this) {
    CameraFailure.NoRearCamera -> R.string.live_camera_no_rear_body
    CameraFailure.BindingFailed -> R.string.live_camera_binding_failed_body
    CameraFailure.InUse -> R.string.live_camera_in_use_body
    CameraFailure.Unknown -> R.string.live_camera_unknown_body
}

/**
 * Maneuver arrow rotation. Shares the same convention as the Map banner: the glyph points east at
 * 0°, so "straight ahead" is -90° and each step is a quarter turn.
 */
private val ManeuverType.arrowRotationDegrees: Float
    get() = (pointingDegrees - EAST_DEGREES).toFloat()

private val ManeuverType.pointingDegrees: Int
    get() = when (this) {
        ManeuverType.Start, ManeuverType.Continue, ManeuverType.Merge, ManeuverType.Destination, ManeuverType.Unknown -> 0
        ManeuverType.SlightLeft -> -45
        ManeuverType.SlightRight -> 45
        ManeuverType.TurnLeft -> -90
        ManeuverType.TurnRight -> 90
        ManeuverType.SharpLeft -> -135
        ManeuverType.SharpRight -> 135
        ManeuverType.UTurn -> 180
        ManeuverType.Ramp -> 45
        ManeuverType.Roundabout -> 90
    }

private const val EAST_DEGREES = 90

/** Feet below a mile: on a navigation HUD "in 0.2 mi" is useless next to an arrow. */
private fun formatLiveDistance(meters: Double): String {
    val feet = meters * FEET_PER_METER
    val miles = meters / METERS_PER_MILE
    return when {
        feet < 1000.0 -> "${feet.roundToInt()} ft"
        miles < 10 -> String.format(Locale.US, "%.1f mi", miles)
        else -> "${miles.roundToInt()} mi"
    }
}

private fun formatLiveDuration(seconds: Double): String {
    val totalMinutes = (seconds / 60).roundToInt().coerceAtLeast(1)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours hr $minutes min" else "$minutes min"
}

private const val FEET_PER_METER = 3.28084
private const val METERS_PER_MILE = 1609.344