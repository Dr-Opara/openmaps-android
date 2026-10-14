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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openmaps.app.anchor.ArCoreAnchorBackend
import com.openmaps.app.core.anchor.RouteAnchorManager
import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.RouteResult
import com.openmaps.app.core.spatial.arcore.ArCoreSpatialLocalization
import com.openmaps.app.feature.map.NavigationState
import com.openmaps.app.BuildConfig
import com.openmaps.app.R
import com.openmaps.app.camera.CameraEngineEvent
import com.openmaps.app.camera.CameraFailure
import com.openmaps.app.camera.CameraXRearEngine
import com.openmaps.app.camera.RearCameraHost
import com.openmaps.app.camera.RearCameraState
import com.openmaps.app.core.camera.CameraPermissionState
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.OpenMapsWordmark
import com.openmaps.app.core.places.LivePlaceProjector
import com.openmaps.app.core.places.Place
import com.openmaps.app.core.roadintelligence.RoadIntelligenceState
import com.openmaps.app.core.roadintelligence.RouteAlert
import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.core.spatial.LiveLocalizationStatus
import com.openmaps.app.core.spatial.LiveLocalizationStatusMapper
import com.openmaps.app.core.spatial.SpatialLocalizationState
import com.openmaps.app.feature.map.MapModeToggle
import com.openmaps.app.feature.map.RoadAlertPresentation
import com.openmaps.app.feature.map.displayName
import com.openmaps.app.feature.map.markerColor
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
    /**
     * The active route and navigation state, read from the one MapViewModel.
     *
     * Supplied rather than derived inside Live, because Live already *has* this state in [hudState]
     * terms and the AR content must consume exactly the same instance the map is showing — a second
     * copy is how "the map rerouted but the arrows did not" bugs happen.
     */
    route: RouteResult? = null,
    navigationState: NavigationState = NavigationState.Idle,
    modifier: Modifier = Modifier,
    /** Ranked, capped contextual places (Phase 9) — the same list Map mode renders markers for. */
    places: List<Place> = emptyList(),
    /** Hazards, weather, speed limit and prioritized alerts (Phase 10) — same state Map mode reads. */
    roadIntelligenceState: RoadIntelligenceState = RoadIntelligenceState(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Reactive: flips from Spatial to CameraX mid-session if AR fails, which is what guarantees a
    // camera is always available in Live.
    val previewProvider by viewModel.previewProvider.collectAsState()

    Box(
        modifier
            .fillMaxSize()
            .background(OpenMapsColors.Navy950),
    ) {
        when {
            state.cameraPermission == CameraPermissionState.Granted ->
                if (previewProvider == PreviewProvider.Spatial) {
                    // ARCore owns the camera and renders the preview itself. CameraX is NOT
                    // composed here at all — two camera consumers would fight (see
                    // ArCoreSpatialLocalization for the reasoning).
                    ArPreviewSurface(
                        viewModel = viewModel,
                        route = route,
                        navigationState = navigationState,
                        isNavigating = isNavigating,
                        places = places,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    RearCameraPreview(state.cameraState, viewModel::onCameraEvent, Modifier.fillMaxSize())
                }

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

        // Localization status (Phase 6). Collected here (not passed down) so the Live HUD does not
        // recompose on every navigation tick. Debug diagnostics are strictly opt-in and only
        // compiled into debug builds.
        val spatialState by viewModel.spatialLocalization.state
            .collectAsState(initial = SpatialLocalizationState.Idle)
        val localizationStatus = remember(spatialState) { LiveLocalizationStatusMapper.of(spatialState) }

        if (localizationStatus != LiveLocalizationStatus.None) {
            LocalizationChip(
                status = localizationStatus,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 96.dp, start = 16.dp, end = 16.dp),
            )
        }

        // Phase 10: road intelligence. Priority in Live is deliberately
        // AR arrows > maneuver guidance > safety alerts > lane guidance > speed info > places, so
        // this sits below the maneuver/status card, is capped to one hazard line plus one speed
        // line, and never adds icons scattered across the feed — see docs/ARCHITECTURE.md's Road
        // intelligence section ("Live mode rendering").
        LiveRoadAlertHud(
            roadState = roadIntelligenceState,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 230.dp, start = 16.dp, end = 16.dp),
        )

        if (BuildConfig.DEBUG) {
            LocalDebugOverlay(
                state = spatialState,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 8.dp),
            )
        }
    }
}

/**
 * A quiet "Locating… / Improving accuracy… / Ready" chip. Deliberately restrained: it is
 * informational, not a warning, and it must never compete with the maneuver for attention.
 */
@Composable
private fun LocalizationChip(status: LiveLocalizationStatus, modifier: Modifier = Modifier) {
    val label = when (status) {
        LiveLocalizationStatus.Locating -> stringResource(R.string.spatial_locating)
        LiveLocalizationStatus.Improving -> stringResource(R.string.spatial_improving)
        LiveLocalizationStatus.Ready -> stringResource(R.string.spatial_ready)
        LiveLocalizationStatus.None -> return
    }
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(OpenMapsColors.Navy950.copy(alpha = 0.6f))
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(
            Icons.Filled.LocationOn,
            contentDescription = null,
            tint = OpenMapsColors.ElectricBlue,
            modifier = Modifier.size(14.dp),
        )
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = Color.White)
    }
}

/**
 * Phase 10's entire Live road-intelligence surface: at most one hazard/weather line (whichever
 * [com.openmaps.app.core.roadintelligence.RoadAlertPriority] ranks highest, excluding the speed
 * limit) plus one persistent speed line (current reading over the posted limit, when both are
 * known). Never more than two short lines, and never icons scattered over the camera feed — see
 * section 12 of the Phase 10 brief ("Live must remain restrained").
 */
@Composable
private fun LiveRoadAlertHud(roadState: RoadIntelligenceState, modifier: Modifier = Modifier) {
    val topAlert = roadState.activeAlerts.firstOrNull { it !is RouteAlert.SpeedLimit }
    val speedLimitAlert = roadState.activeAlerts.filterIsInstance<RouteAlert.SpeedLimit>().firstOrNull()
    val currentSpeed = roadState.currentSpeedMetersPerSecond
    if (topAlert == null && speedLimitAlert == null) return

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        topAlert?.let { alert ->
            val distance = RoadAlertPresentation.distanceAheadLabel(alert)
            LiveCompactPill(
                text = if (distance != null) "${RoadAlertPresentation.headline(alert)} $distance" else RoadAlertPresentation.headline(alert),
                background = OpenMapsColors.Warning,
                foreground = OpenMapsColors.Navy900,
            )
        }
        speedLimitAlert?.let { alert ->
            val text = if (currentSpeed != null) {
                "${RoadAlertPresentation.formatCurrentSpeed(currentSpeed, alert.info.unit)} · ${RoadAlertPresentation.headline(alert)}"
            } else {
                RoadAlertPresentation.headline(alert)
            }
            LiveCompactPill(text = text, background = OpenMapsColors.Navy950.copy(alpha = 0.72f), foreground = Color.White)
        }
    }
}

@Composable
private fun LiveCompactPill(text: String, background: Color, foreground: Color, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = foreground,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(CircleShape)
            .background(background)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/**
 * Debug-build-only localization diagnostics (accuracy values, Earth state). Stripped from release
 * builds by the `if (BuildConfig.DEBUG)` guard at the call site. Provides the accuracy visibility
 * needed to tune [com.openmaps.app.core.spatial.LocalizationQualityMapper] thresholds on real
 * hardware — without it there is no way to know whether a quality gate is too strict in the field.
 */
@Composable
private fun LocalDebugOverlay(
    state: SpatialLocalizationState,
    modifier: Modifier = Modifier,
) {
    val pose = (state as? SpatialLocalizationState.Tracking)?.pose
    Column(
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(OpenMapsColors.Navy950.copy(alpha = 0.75f))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalAlignment = Alignment.End,
    ) {
        Text(
            text = "AR: ${state::class.simpleName}",
            style = MaterialTheme.typography.labelSmall,
            color = OpenMapsColors.Cyan,
        )
        if (pose != null) {
            Text(
                text = "hAcc=${pose.horizontalAccuracyMeters?.let { "%.1f".format(it) } ?: "-"}m " +
                    "vAcc=${pose.verticalAccuracyMeters?.let { "%.1f".format(it) } ?: "-"}m " +
                    "hdgAcc=${pose.headingAccuracyDegrees?.let { "%.0f".format(it) } ?: "-"}°",
                style = MaterialTheme.typography.labelSmall,
                color = OpenMapsColors.TextSecondary,
            )
            Text(
                text = "%.5f,%.5f hdg=%.0f°".format(pose.latitude, pose.longitude, pose.headingDegrees),
                style = MaterialTheme.typography.labelSmall,
                color = OpenMapsColors.TextSecondary,
            )
        }
    }
}

/**
 * Hosts the ARCore-owned camera preview and ties the session to the Live lifecycle.
 *
 * This is the Phase 6 camera/AR integration point: ARCore holds the camera and draws the preview via
 * its own [android.opengl.GLSurfaceView], and this composable hosts that surface while starting and
 * stopping localization against the composition's lifecycle owner (so backgrounding pauses the
 * session and leaving Live closes it).
 *
 * The session is created by [LiveViewModel.spatialLocalization]; when AR is unsupported or fails the
 * engine reports a non-preview state and [LiveScreen] keeps using the CameraX preview instead.
 */
@Composable
private fun ArPreviewSurface(
    viewModel: LiveViewModel,
    route: RouteResult?,
    navigationState: NavigationState,
    isNavigating: Boolean,
    places: List<Place>,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val engine = viewModel.spatialLocalization
    val arEngine = engine as? ArCoreSpatialLocalization

    if (arEngine == null) {
        // An engine that claims a preview but is not the ARCore one: fall back rather than render
        // nothing. Defensive; the container only ever supplies ArCoreSpatialLocalization here.
        Box(modifier.background(OpenMapsColors.Navy950))
        return
    }

    val glView = remember(arEngine) { arEngine.previewView() }
    // Cumulative route distance per vertex, recomputed only when the route itself changes.
    val routeDistanceAtIndex = remember(route) { route?.let { cumulativeDistances(it.geometry) } }

    // Phase 7: the real ARCore anchor backend, created once per engine. Both suppliers are lambdas
    // so the backend reads live state rather than capturing a snapshot that would go stale.
    val anchorBackend = remember(arEngine) {
        ArCoreAnchorBackend(
            sessionProvider = { arEngine.session() },
            cameraTransformProvider = { arEngine.cameraTransform() },
        )
    }
    val anchorManager = remember(arEngine, anchorBackend) { RouteAnchorManager(anchorBackend) }
    DisposableEffect(anchorManager) {
        viewModel.routeAnchorManager = anchorManager
        onDispose {
            // Release the ARCore anchors the moment Live stops hosting them. Leaving them attached
            // would keep ARCore refining world anchors nobody is drawing.
            anchorManager.clear()
            viewModel.routeAnchorManager = null
        }
    }

    // Phase 8: register the chevron renderer with the AR engine. Detached on dispose so a torn-down
    // Live cannot keep drawing.
    DisposableEffect(arEngine) {
        arEngine.overlayRenderer = viewModel.arrowPipeline
        onDispose { arEngine.overlayRenderer = null }
    }

    Box(modifier) {
        // AndroidView requires `update`; the GL surface is fully owned by the AR engine, so there
        // is nothing to push — an empty block keeps the view out of Compose's recomposition path.
        // The AR arrow renderer draws into this surface directly (via `arEngine.overlayRenderer`),
        // so it stays the dominant navigational layer; place labels below are a separate, later
        // Compose overlay and never touch the GL draw path.
        AndroidView(factory = { glView }, update = { }, modifier = Modifier.fillMaxSize())

        val spatialState by viewModel.spatialLocalization.state
            .collectAsState(initial = SpatialLocalizationState.Idle)

        LaunchedEffect(arEngine, lifecycleOwner) {
            viewModel.startSpatialLocalization(lifecycleOwner)
        }

        // Anchors are created/destroyed here, on state change — NOT per frame. Creating an ARCore anchor
        // is a real cost (a server-refined object) and must never sit in the render loop.
        val progress = when (val nav = navigationState) {
            is NavigationState.Active -> nav.progress
            is NavigationState.OffRoute -> nav.progress
            is NavigationState.Arrived -> nav.progress
            else -> null
        }

        LaunchedEffect(route, navigationState, spatialState) {
            val manager = viewModel.routeAnchorManager
            val anchored = if (route != null && manager != null) {
                manager.update(
                    routeKey = routeKeyFor(route),
                    geometry = route.geometry,
                    startIndex = progress?.snappedShapeIndex ?: 0,
                    distanceToNextManeuverMeters = progress?.distanceToNextManeuverMeters ?: Double.MAX_VALUE,
                    remainingDistanceMeters = progress?.remainingDistanceMeters ?: Double.MAX_VALUE,
                    routeDistanceAtIndex = routeDistanceAtIndex,
                    spatialState = spatialState,
                    isNavigating = isNavigating,
                )
            } else {
                emptyList()
            }
            viewModel.arrowPipeline.update(
                route = route,
                progress = progress,
                pose = (spatialState as? SpatialLocalizationState.Tracking)?.pose,
                spatialState = spatialState,
                viewportWidthPx = glView.width,
                viewportHeightPx = glView.height,
                isNavigating = isNavigating,
                anchoredPoints = anchored,
                cameraTransform = anchorManager.backendCameraTransform(),
            )
            // Hide the overlay entirely when nothing is drawn, rather than paying for a cleared GL
            // pass every frame while empty.
            arEngine.setOverlayAlpha(if (viewModel.arrowPipeline.arrowCount() > 0) 1f else 0f)
        }

        DisposableEffect(arEngine) {
            onDispose { viewModel.stopSpatialLocalization() }
        }

        // Phase 9: contextual place labels — a separate, capped, lightweight overlay layered above
        // the AR surface but never replacing or covering the arrow renderer (arrows are baked into
        // the GL surface above; this is plain Compose content). Recomputed when the candidate list
        // or the spatial state changes, not per frame. Deliberately keyed off the AR pose path only:
        // in the CameraX fallback there is no tracked device heading to project against (see
        // `LiveViewModel.updateLivePlaces`), so Live simply shows no place labels there rather than
        // guessing a position.
        LaunchedEffect(places, spatialState, glView) {
            viewModel.updateLivePlaces(
                places = places,
                spatialState = spatialState,
                viewportWidthPx = glView.width,
                viewportHeightPx = glView.height,
            )
        }
        val labels by viewModel.livePlaceLabels.collectAsState()
        LivePlaceLabels(labels = labels, modifier = Modifier.fillMaxSize())
    }
}

/**
 * Restrained contextual labels (Phase 9) — name, category, approximate distance, no giant cards.
 * Positioned at each label's projected screen point ([LivePlaceProjector.LivePlaceLabel.x]/`y`),
 * staggered vertically by rank so near-simultaneous labels do not stack exactly on top of each
 * other; this is a presentation offset only, not a precision claim.
 */
@Composable
private fun LivePlaceLabels(labels: List<LivePlaceProjector.LivePlaceLabel>, modifier: Modifier = Modifier) {
    Box(modifier) {
        labels.forEachIndexed { index, label ->
            LivePlaceChip(
                label = label,
                modifier = Modifier.offset {
                    IntOffset(label.x.roundToInt(), label.y.roundToInt() + index * LABEL_VERTICAL_STAGGER_PX)
                },
            )
        }
    }
}

@Composable
private fun LivePlaceChip(label: LivePlaceProjector.LivePlaceLabel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(OpenMapsColors.Navy950.copy(alpha = 0.68f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(label.place.category.markerColor()),
        )
        Column {
            Text(
                text = label.place.name,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${label.place.category.displayName()} · ${formatLiveDistance(label.distanceMeters)}",
                style = MaterialTheme.typography.labelSmall,
                color = OpenMapsColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private const val LABEL_VERTICAL_STAGGER_PX = 72

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

private const val METERS_PER_DEGREE = 111_320.0

/**
 * Stable identity for a route, so the anchor manager can tell "same road" from "rerouted road".
 *
 * [RouteResult] is a data class, so its hash reflects its contents; a reroute produces different
 * geometry and therefore a different key, which is what makes the manager drop the old anchors
 * instead of reusing them on the new road.
 */
private fun routeKeyFor(route: RouteResult?): String =
    route?.let { "geo:${it.geometry.size}:${it.hashCode()}" } ?: "none"

/**
 * Cumulative distance along the route geometry, in metres, one entry per vertex.
 *
 * Computed here rather than in the anchor manager so it is memoised per route object and recomputed
 * only on a reroute — this feeds the metre-based anchor window, so recomputing it per location
 * update would be wasted work.
 */
private fun cumulativeDistances(geometry: List<Coordinate>): DoubleArray {
    val out = DoubleArray(geometry.size)
    for (i in 1 until geometry.size) {
        out[i] = out[i - 1] + metersBetween(geometry[i - 1], geometry[i])
    }
    return out
}

private fun metersBetween(a: Coordinate, b: Coordinate): Double {
    val mid = Math.toRadians((a.latitude + b.latitude) / 2.0)
    val dLat = (b.latitude - a.latitude) * METERS_PER_DEGREE
    val dLon = (b.longitude - a.longitude) * METERS_PER_DEGREE * Math.cos(mid)
    return Math.sqrt(dLat * dLat + dLon * dLon)
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