package com.openmaps.app.feature.live

import android.media.AudioManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openmaps.app.anchor.ArCoreAnchorBackend
import com.openmaps.app.arrow.ARArrowPipeline
import com.openmaps.app.core.anchor.RouteAnchorManager
import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.RouteResult
import com.openmaps.app.core.spatial.arcore.ArCoreSpatialLocalization
import com.openmaps.app.feature.map.NavigationState
import com.openmaps.app.feature.map.ExternalActionStatus
import com.openmaps.app.R
import com.openmaps.app.camera.CameraEngineEvent
import com.openmaps.app.camera.CameraFailure
import com.openmaps.app.camera.CameraXRearEngine
import com.openmaps.app.camera.RearCameraHost
import com.openmaps.app.camera.RearCameraState
import com.openmaps.app.core.camera.CameraPermissionState
import com.openmaps.app.core.designsystem.GlassShapes
import com.openmaps.app.core.designsystem.ManeuverGlyph
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.OpenMapsWordmark
import com.openmaps.app.core.designsystem.glass
import com.openmaps.app.core.environment.EnvironmentNavigationProfile
import com.openmaps.app.core.environment.EnvironmentProfiles
import com.openmaps.app.core.localization.LocalAppLocale
import com.openmaps.app.core.localization.LocalMeasurementSystem
import com.openmaps.app.core.localization.MeasurementSystem
import com.openmaps.app.core.localization.NavigationUnits
import com.openmaps.app.core.places.LivePlaceProjector
import com.openmaps.app.core.places.Place
import com.openmaps.app.core.places.PlaceCategory
import com.openmaps.app.core.roadintelligence.RoadIntelligenceState
import com.openmaps.app.core.roadintelligence.SpeedUnit
import com.openmaps.app.core.roadintelligence.RouteAlert
import com.openmaps.app.core.reporting.DriverReportType
import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.core.spatial.LiveLocalizationStatus
import com.openmaps.app.core.spatial.LiveLocalizationStatusMapper
import com.openmaps.app.core.spatial.SpatialLocalizationState
import com.openmaps.app.feature.map.ArrivalTimeFormatter
import com.openmaps.app.feature.map.LaneGuidanceStrip
import com.openmaps.app.feature.map.MapModeToggle
import com.openmaps.app.feature.map.RoadAlertPresentation
import com.openmaps.app.feature.map.displayName
import com.openmaps.app.feature.map.iconRes
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
    /** Phase 11: the active environment profile — same one Map mode's camera/places/alerts read. */
    environmentProfile: EnvironmentNavigationProfile = EnvironmentProfiles.unknown,
    /** Delta-spec section 25: the compact mute control, state owned by the one `MapViewModel`. */
    voiceEnabled: Boolean = true,
    onVoiceMuteToggled: () -> Unit = {},
    driverReportStatus: ExternalActionStatus = ExternalActionStatus.Idle,
    tripShareStatus: ExternalActionStatus = ExternalActionStatus.Idle,
    onReport: (DriverReportType) -> Unit = {},
    onCommunityReportConfirm: (String) -> Unit = {},
    onCommunityReportDismiss: (String) -> Unit = {},
    onShareTrip: () -> Unit = {},
    onDismissExternalStatus: () -> Unit = {},
    /** Manual alternative-route request from the active driving HUD. */
    onReroute: () -> Unit = {},
    /** Explicit red Exit action: end the trip and return to Discover/Home. */
    onExitNavigation: () -> Unit = {},
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
                        environmentProfile = environmentProfile,
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
            voiceEnabled = voiceEnabled,
            onVoiceMuteToggled = onVoiceMuteToggled,
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

            is LiveHudState.Maneuver -> {
                LiveManeuverHud(
                    hud = hud,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 64.dp, start = 14.dp, end = 14.dp),
                )
                // The mockup stacks a compact "Then" pill directly beneath the maneuver card.
                hud.nextInstruction?.let { then ->
                    LiveNextManeuverHud(
                        nextInstruction = then,
                        nextManeuverType = hud.nextManeuverType,
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .statusBarsPadding()
                            .padding(top = 150.dp, start = 14.dp, end = 14.dp),
                    )
                }
            }

            is LiveHudState.Rerouting -> LiveStatusHud(
                title = stringResource(R.string.live_rerouting),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 72.dp, start = 16.dp, end = 16.dp),
            )

            is LiveHudState.OffRoute -> LiveStatusHud(
                title = stringResource(R.string.live_off_route),
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 72.dp, start = 16.dp, end = 16.dp),
            )

            is LiveHudState.Arrived -> {
                LiveArrivedHud(
                    destinationName = hud.destinationName,
                    onBackToMap = { onModeSelected(MapMode.Map) },
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 16.dp, end = 16.dp, bottom = 40.dp),
                )
            }
        }

        // Bottom trip summary: present for every navigating state except arrival, which has its own
        // call to action instead. `summaryBottomPadding` is shared with the speed block above it so
        // the two always stay stacked rather than overlapping.
        val summaryHud = hudState
        val summaryBottomPadding = if (summaryHud is LiveHudState.Maneuver) 16.dp else 96.dp
        if (summaryHud is LiveHudState.Maneuver) {
            LiveDrivingActions(
                voiceEnabled = voiceEnabled,
                driverReportStatus = driverReportStatus,
                tripShareStatus = tripShareStatus,
                onVoiceMuteToggled = onVoiceMuteToggled,
                onReport = onReport,
                onShareTrip = onShareTrip,
                onDismissExternalStatus = onDismissExternalStatus,
                onReroute = onReroute,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = summaryBottomPadding + 86.dp),
            )
            LiveTripSummary(
                hud = summaryHud,
                roadIntelligenceState = roadIntelligenceState,
                onExitNavigation = onExitNavigation,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = summaryBottomPadding),
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
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = if (isNavigating) 202.dp else 96.dp, start = 16.dp, end = 16.dp),
            )
        }

        // Phase 10: road intelligence. Hazards sit under the maneuver card; speed has its own
        // lower-left block so the mockup's eyeline is respected.
        LiveRoadAlertHud(
            roadState = roadIntelligenceState,
            onCommunityConfirm = onCommunityReportConfirm,
            onCommunityDismiss = onCommunityReportDismiss,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = if (isNavigating) 246.dp else 230.dp, start = 16.dp, end = 16.dp),
        )

        // Mockup: current speed + posted limit, lower-left, above the trip card.
        LiveSpeedBlock(
            currentSpeedMetersPerSecond = roadIntelligenceState.currentSpeedMetersPerSecond,
            speedLimitAlert = roadIntelligenceState.activeAlerts.filterIsInstance<RouteAlert.SpeedLimit>().firstOrNull(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .navigationBarsPadding()
                .padding(start = 16.dp, bottom = summaryBottomPadding + 148.dp),
        )

        // Mockup: mic + map floating circles down the right edge, mirroring the speed block's
        // height on the opposite corner. Only while actively navigating — see LiveTopBar's doc
        // comment for why the header's own controls step aside for this instead of stacking both.
        if (isNavigating && summaryHud is LiveHudState.Maneuver) {
            LiveSideControls(
                onBackToMap = { onModeSelected(MapMode.Map) },
                voiceEnabled = voiceEnabled,
                onVoiceMuteToggled = onVoiceMuteToggled,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(end = 16.dp, bottom = summaryBottomPadding + 140.dp),
            )
        }
    }
}

/**
 * The quiet readiness chip, in **production wording**.
 *
 * Deliberately never says "AR: Idle", "Earth unavailable", "not authorized" or any other ARCore
 * state name: those are diagnostics, not guidance. Technical detail remains in Logcat/internal
 * tooling and is never rendered over the consumer Live screen.
 */
@Composable
private fun LocalizationChip(status: LiveLocalizationStatus, modifier: Modifier = Modifier) {
    val label = when (status) {
        LiveLocalizationStatus.Locating -> stringResource(R.string.ar_aligning)
        LiveLocalizationStatus.Improving -> stringResource(R.string.ar_locating_road)
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
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = OpenMapsColors.TextOnDark)
    }
}

/**
 * Phase 10's entire Live road-intelligence surface: at most one hazard/weather line (whichever
 * [com.openmaps.app.core.roadintelligence.RoadAlertPriority] ranks highest, excluding the speed
 * limit) plus one persistent speed line. Never more than two short lines, and never icons
 * scattered over the camera feed — see section 12 of the Phase 10 brief ("Live must remain
 * restrained").
 *
 * Delta-spec section 8: current speed and the posted limit are **independently real** — current
 * speed shows whenever the device has a reading, with or without a posted limit nearby; the limit
 * badge shows only when Phase 10 actually resolved one. Neither is ever shown without the other
 * implying the other exists — a bare current-speed reading never grows a fabricated "LIMIT" badge.
 */
@Composable
private fun LiveRoadAlertHud(
    roadState: RoadIntelligenceState,
    onCommunityConfirm: (String) -> Unit = {},
    onCommunityDismiss: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Hazards only. Speed moved to its own lower-left block ([LiveSpeedBlock]) because the mockup
    // puts the speedometer there, in the driver's natural eyeline next to the trip card — not in a
    // queue with warnings under the maneuver card.
    val topAlert = roadState.activeAlerts.firstOrNull { it !is RouteAlert.SpeedLimit } ?: return
    if (topAlert is RouteAlert.Lane) {
        LaneGuidanceStrip(
            guidance = topAlert.guidance,
            darkSurface = true,
            modifier = modifier,
        )
        return
    }

    val measurementSystem = LocalMeasurementSystem.current
    val locale = LocalAppLocale.current
    val distance = RoadAlertPresentation.distanceAheadLabel(topAlert, measurementSystem, locale)
    val delay = RoadAlertPresentation.delayLabel(topAlert, locale)
    val detail = listOfNotNull(distance, delay).joinToString(" · ")
    Column(modifier = modifier) {
        LiveAlertPill(
            text = buildString {
                append(RoadAlertPresentation.headline(topAlert, measurementSystem, locale))
                if (detail.isNotBlank()) {
                    append(" · ")
                    append(detail)
                }
            },
            iconRes = if (topAlert is RouteAlert.Congestion) R.drawable.ic_car else R.drawable.ic_warning,
        )
        topAlert.communityReportId()?.let { reportId ->
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .clip(GlassShapes.Pill)
                    .background(OpenMapsColors.Navy950.copy(alpha = 0.68f)),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                TextButton(onClick = { onCommunityConfirm(reportId) }) {
                    Text(stringResource(R.string.community_still_there), style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
                TextButton(onClick = { onCommunityDismiss(reportId) }) {
                    Text(stringResource(R.string.community_not_there), style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
            }
        }
    }
}

/**
 * The mockup's lower-left speed block: the current speed large, with the posted limit beneath it as
 * a "LIMIT nn" chip.
 *
 * The two are **independently real** (delta-spec section 8): the current speed appears whenever the
 * device reports a reading, and the limit chip appears only when Phase 10 actually resolved a posted
 * limit. A speed reading never grows an invented limit, and a limit never implies a speed reading.
 */
/**
 * Original-design instruments: a circular speed dial and a separate posted-limit sign.
 * GPS speed and posted limits remain independent provider-backed values: if either is absent,
 * its instrument is absent. The ring tracks speed across a fixed design scale (120 mph / 200 km/h),
 * never an invented percentage of the legal limit.
 */
@Composable
private fun LiveSpeedBlock(
    currentSpeedMetersPerSecond: Float?,
    speedLimitAlert: RouteAlert.SpeedLimit?,
    modifier: Modifier = Modifier,
) {
    val measurementSystem = LocalMeasurementSystem.current
    val locale = LocalAppLocale.current
    val speedValue = currentSpeedMetersPerSecond?.let {
        NavigationUnits.formatCurrentSpeed(it, measurementSystem, locale).substringBeforeLast(" ")
    }
    val limitValue = speedLimitAlert?.let { alert ->
        NavigationUnits.formatPostedSpeed(
            value = alert.info.value,
            sourceIsMph = alert.info.unit == SpeedUnit.Mph,
            preference = measurementSystem,
            locale = locale,
        ).substringBeforeLast(" ")
    }
    val speedUnit = NavigationUnits.speedUnitLabel(measurementSystem, locale)
    if (speedValue == null && limitValue == null) return

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        if (speedValue != null && currentSpeedMetersPerSecond != null) {
            val arcFraction = speedArcFraction(
                speedMetersPerSecond = currentSpeedMetersPerSecond,
                measurementIsMph = speedUnit.contains("mph", ignoreCase = true),
            )
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color(0xE3101B2B)),
                contentAlignment = Alignment.Center,
            ) {
                Canvas(modifier = Modifier.fillMaxSize().padding(5.dp)) {
                    val stroke = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
                    drawArc(
                        color = Color.White.copy(alpha = 0.2f),
                        startAngle = 132f,
                        sweepAngle = 276f,
                        useCenter = false,
                        style = stroke,
                    )
                    drawArc(
                        color = OpenMapsColors.ElectricBlueFilled,
                        startAngle = 132f,
                        sweepAngle = 276f * arcFraction,
                        useCenter = false,
                        style = stroke,
                    )
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = speedValue,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Color.White,
                        maxLines = 1,
                    )
                    Text(
                        text = speedUnit,
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White.copy(alpha = 0.8f),
                        maxLines = 1,
                    )
                }
            }
        }
        if (limitValue != null) {
            Column(
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .padding(horizontal = 9.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = stringResource(R.string.live_speed_limit_label),
                    style = MaterialTheme.typography.labelSmall,
                    color = OpenMapsColors.Navy900,
                )
                Text(
                    text = limitValue,
                    style = MaterialTheme.typography.headlineSmall,
                    color = OpenMapsColors.Navy900,
                    maxLines = 1,
                )
            }
        }
    }
}

/** Pure, bounded dial geometry used by Live speed UI and JVM regression tests. */
internal fun speedArcFraction(speedMetersPerSecond: Float, measurementIsMph: Boolean): Float {
    if (!speedMetersPerSecond.isFinite()) return 0f
    val converted = speedMetersPerSecond.coerceAtLeast(0f) *
        (if (measurementIsMph) 2.2369363f else 3.6f)
    return (converted / if (measurementIsMph) 120f else 200f).coerceIn(0f, 1f)
}

/**
 * The reference screenshots' hazard/traffic pill: translucent dark-amber fill with a hairline amber
 * border and amber text — never the earlier solid-amber-fill/navy-text treatment, which reads as a
 * filled badge rather than the mockup's outlined warning chip.
 */
@Composable
private fun LiveAlertPill(text: String, iconRes: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(GlassShapes.Pill)
            .background(OpenMapsColors.Warning.copy(alpha = 0.16f))
            .border(1.dp, OpenMapsColors.Warning.copy(alpha = 0.55f), GlassShapes.Pill)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            tint = OpenMapsColors.Warning,
            modifier = Modifier.size(16.dp),
        )
        Text(
            text = text,
            style = MaterialTheme.typography.labelMedium,
            color = OpenMapsColors.Warning,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
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
    environmentProfile: EnvironmentNavigationProfile,
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

        // Phase 11 (Part I): environment tunes the arrow sampler's spacing/look-ahead/max-count/
        // turn-density only. `ARArrowSampler` is pure and stateless, so swapping it mid-trip is
        // safe; anchor creation/lifecycle (`RouteAnchorManager`, above) is completely untouched.
        LaunchedEffect(environmentProfile) {
            viewModel.arrowPipeline.updateArrowConfig(environmentProfile.arrowConfig())
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
        LaunchedEffect(places, spatialState, glView, environmentProfile) {
            viewModel.updateLivePlaces(
                places = places,
                spatialState = spatialState,
                viewportWidthPx = glView.width,
                viewportHeightPx = glView.height,
                maxVisible = environmentProfile.liveMaxPoiLabels,
            )
        }
        val labels by viewModel.livePlaceLabels.collectAsState()
        val showDiscovery = progress?.remainingDistanceMeters?.let { it > ARRIVAL_DISCOVERY_SUPPRESS_METERS } ?: !isNavigating
        LivePlaceLabels(
            labels = if (showDiscovery) labels else emptyList(),
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * Restrained contextual labels (Phase 9) — name, category, approximate distance, no giant cards.
 * Positioned at each label's projected screen point ([LivePlaceProjector.LivePlaceLabel.x]/`y`),
 * staggered vertically by rank so near-simultaneous labels do not stack exactly on top of each
 * other; this is a presentation offset only, not a precision claim.
 */
@Composable
private fun LivePlaceLabels(
    labels: List<LivePlaceProjector.LivePlaceLabel>,
    modifier: Modifier = Modifier,
) {
    val prioritized = remember(labels) {
        labels.filter { it.place.category in LIVE_DISCOVERY_CATEGORIES }
    }
    var startIndex by remember(prioritized) { mutableIntStateOf(0) }

    LaunchedEffect(prioritized) {
        startIndex = 0
        if (prioritized.size > LIVE_DISCOVERY_WINDOW) {
            while (true) {
                kotlinx.coroutines.delay(LIVE_DISCOVERY_ROTATION_MILLIS)
                startIndex = (startIndex + LIVE_DISCOVERY_WINDOW) % prioritized.size
            }
        }
    }

    val visible = if (prioritized.size <= LIVE_DISCOVERY_WINDOW) {
        prioritized
    } else {
        List(LIVE_DISCOVERY_WINDOW) { offset ->
            prioritized[(startIndex + offset) % prioritized.size]
        }
    }

    Box(modifier) {
        visible.forEachIndexed { index, label ->
            LivePlaceMarker(
                label = label,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            label.x.roundToInt(),
                            label.y.roundToInt() + index * LABEL_VERTICAL_STAGGER_PX,
                        )
                    }
                    .offset(y = -PLACE_MARKER_LIFT),
            )
        }
    }
}

private val LIVE_DISCOVERY_CATEGORIES = setOf(
    PlaceCategory.Gas,
    PlaceCategory.EvCharging,
    PlaceCategory.Medical,
    PlaceCategory.Parking,
    PlaceCategory.Restaurant,
    PlaceCategory.Coffee,
    PlaceCategory.Hotel,
    PlaceCategory.Pharmacy,
)
private const val LIVE_DISCOVERY_WINDOW = 2
private const val LIVE_DISCOVERY_ROTATION_MILLIS = 5_000L
private const val ARRIVAL_DISCOVERY_SUPPRESS_METERS = 500.0


/**
 * The reference screenshots' POI callout: a chip floating above the road with a thin leader stem
 * down to a small dot at the place's real projected point — never a chip sitting flush on top of
 * its own anchor, which is what [LivePlaceChip] alone drew before.
 *
 * [PLACE_MARKER_LIFT] estimates the chip's height rather than measuring it, so the dot lands within
 * a few dp of the true pixel rather than exactly on it — the same tolerance this file already
 * documents for [LABEL_VERTICAL_STAGGER_PX].
 */
@Composable
private fun LivePlaceMarker(label: LivePlaceProjector.LivePlaceLabel, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        LivePlaceChip(label)
        Box(
            Modifier
                .padding(start = PLACE_MARKER_STEM_INSET)
                .width(1.dp)
                .height(PLACE_MARKER_STEM_HEIGHT)
                .background(Color.White.copy(alpha = 0.38f)),
        )
        Box(
            Modifier
                .padding(start = PLACE_MARKER_STEM_INSET - PLACE_MARKER_DOT_SIZE / 2)
                .size(PLACE_MARKER_DOT_SIZE)
                .clip(CircleShape)
                .background(label.place.category.markerColor())
                .border(1.dp, Color.White.copy(alpha = 0.85f), CircleShape),
        )
    }
}

@Composable
private fun LivePlaceChip(label: LivePlaceProjector.LivePlaceLabel, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(OpenMapsColors.Navy950.copy(alpha = 0.72f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(
            painter = painterResource(label.place.category.iconRes()),
            contentDescription = null,
            tint = label.place.category.markerColor(),
            modifier = Modifier.size(16.dp),
        )
        Column {
            Text(
                text = label.place.name,
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${label.place.category.displayName()} · ${formatLiveDistance(label.distanceMeters)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = OpenMapsColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                // Independently real (same rule as speed/limit above): shown only when the provider
                // actually reported open/closed, never guessed — see Place.openNow's doc comment.
                label.place.openNow?.let { open ->
                    Text(
                        text = " · " + stringResource(if (open) R.string.place_open_now else R.string.place_closed_now),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (open) OpenMapsColors.Success else MaterialTheme.colorScheme.error,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

/**
 * Delta-spec section 8: current speed and the posted limit are independently real. Extracted as a
 * pure function (from `LiveRoadAlertHud`'s `SpeedLine`) so the decoupling is unit-testable without
 * a Compose harness — this codebase has none, so logic worth asserting on lives outside `@Composable`.
 */
internal fun speedLineText(
    currentSpeedMetersPerSecond: Float?,
    speedLimitAlert: RouteAlert.SpeedLimit?,
    measurementSystem: MeasurementSystem = MeasurementSystem.Auto,
    locale: Locale = Locale.getDefault(),
): String? {
    val unit = speedLimitAlert?.info?.unit ?: SpeedUnit.Mph
    return when {
        currentSpeedMetersPerSecond != null && speedLimitAlert != null ->
            "${RoadAlertPresentation.formatCurrentSpeed(currentSpeedMetersPerSecond, unit, measurementSystem, locale)} · " +
                RoadAlertPresentation.headline(speedLimitAlert, measurementSystem, locale)
        currentSpeedMetersPerSecond != null ->
            RoadAlertPresentation.formatCurrentSpeed(currentSpeedMetersPerSecond, unit, measurementSystem, locale)
        speedLimitAlert != null -> RoadAlertPresentation.headline(speedLimitAlert, measurementSystem, locale)
        else -> null
    }
}

private const val LABEL_VERTICAL_STAGGER_PX = 72
private val PLACE_MARKER_LIFT = 58.dp
private val PLACE_MARKER_STEM_HEIGHT = 14.dp
private val PLACE_MARKER_STEM_INSET = 14.dp
private val PLACE_MARKER_DOT_SIZE = 8.dp

/** Delta-spec section 13: long enough to read "You've arrived", short enough not to feel stuck. */
private const val ARRIVAL_AUTO_RETURN_DELAY_MILLIS = 6_000L

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
/**
 * Live's chrome.
 *
 * **Not navigating** — a full header row (wordmark + Map/Live toggle), because nothing else is on
 * screen and the camera alone is not guidance.
 *
 * **Navigating** — the mockup puts the maneuver card alone at the very top, with voice and the
 * mode controls down the right edge. Keeping the header row here instead would sit directly on top
 * of the maneuver card (they were overlapping in the first on-device render), which is exactly the
 * collision the mockup's layout avoids. So the header is dropped and [LiveSideControls] takes over.
 */
@Composable
private fun LiveTopBar(
    isNavigating: Boolean,
    onBackToMap: () -> Unit,
    onModeSelected: (MapMode) -> Unit,
    modifier: Modifier = Modifier,
    voiceEnabled: Boolean = true,
    onVoiceMuteToggled: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // onLight = false: this sits over the dark camera feed, not a light card — the default
        // dark-navy "Open" is otherwise unreadable here.
        OpenMapsWordmark(modifier = Modifier.padding(start = 4.dp), onLight = false)
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (!isNavigating) {
                VoiceToggle(voiceEnabled = voiceEnabled, onToggle = onVoiceMuteToggled)
                Spacer(Modifier.width(8.dp))
            }
            if (!isNavigating) {
                IconButton(onClick = onBackToMap) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.cd_back_to_map),
                        tint = OpenMapsColors.TextOnDark,
                    )
                }
            }
            MapModeToggle(selected = MapMode.Live, onSelected = onModeSelected, transparent = true)
        }
    }
}

/**
 * The reference screenshots' right-edge side controls during navigation: two floating circles —
 * voice and a one-tap way back to the map. Mirrors [LiveTopBar]'s own doc comment: the header row
 * is dropped while navigating specifically because this takes over its controls.
 *
 * The mic glyph matches the reference; it still drives the same real voice-guidance mute toggle
 * [LiveTopBar]'s [VoiceToggle] does elsewhere (there is no separate voice-command feature behind
 * it), so state is conveyed honestly through dimming rather than by claiming an unmapped "listening"
 * affordance.
 */
@Composable
private fun LiveSideControls(
    onBackToMap: () -> Unit,
    modifier: Modifier = Modifier,
    voiceEnabled: Boolean = true,
    onVoiceMuteToggled: () -> Unit = {},
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        IconButton(
            onClick = onVoiceMuteToggled,
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(OpenMapsColors.Navy950.copy(alpha = 0.55f))
                .border(1.dp, OpenMapsColors.ElectricBlue.copy(alpha = 0.35f), CircleShape),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_mic),
                contentDescription = stringResource(if (voiceEnabled) R.string.live_voice_on else R.string.live_voice_off),
                tint = if (voiceEnabled) OpenMapsColors.ElectricBlue else Color.White.copy(alpha = 0.45f),
                modifier = Modifier.size(22.dp),
            )
        }

        IconButton(
            onClick = onBackToMap,
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(OpenMapsColors.Navy950.copy(alpha = 0.55f))
                .border(1.dp, OpenMapsColors.Cyan.copy(alpha = 0.35f), CircleShape),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_map),
                contentDescription = stringResource(R.string.cd_back_to_map),
                tint = OpenMapsColors.Cyan,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Compact mute/unmute control.
 *
 * Uses real speaker glyphs authored in this project (`ic_volume` / `ic_volume_off`) rather than a
 * text label, matching the mockup's icon-only side control.
 */
@Composable
private fun VoiceToggle(
    voiceEnabled: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(OpenMapsColors.Navy950.copy(alpha = 0.50f)),
    ) {
        Icon(
            painter = painterResource(if (voiceEnabled) R.drawable.ic_volume else R.drawable.ic_volume_off),
            contentDescription = stringResource(if (voiceEnabled) R.string.live_voice_on else R.string.live_voice_off),
            tint = OpenMapsColors.TextOnDark,
        )
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
    // Original OpenMaps Live reference: large cobalt maneuver tile, dominant distance,
    // readable full instruction and a dark, translucent rounded container.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(Color(0xE60B1928))
            .padding(horizontal = 14.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(RoundedCornerShape(19.dp))
                .background(OpenMapsColors.ElectricBlueFilled),
            contentAlignment = Alignment.Center,
        ) {
            ManeuverGlyph(
                type = hud.type,
                tint = Color.White,
                modifier = Modifier.size(46.dp),
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                text = formatLiveDistance(hud.distanceToManeuverMeters),
                style = MaterialTheme.typography.headlineMedium,
                color = Color.White,
                maxLines = 1,
            )
            Text(
                text = hud.instruction,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.91f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * The mockup's separate "Then" card beneath the maneuver card.
 *
 * Split out of [LiveManeuverHud] rather than left as a third line inside it, matching the mockup:
 * the upcoming turn is its own compact pill so it never competes with the current instruction.
 * Mockup: "Then" label, a small white turn icon, a thin divider, then the instruction.
 */
@Composable
private fun LiveNextManeuverHud(
    nextInstruction: String,
    nextManeuverType: ManeuverType?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(OpenMapsColors.Navy950.copy(alpha = 0.50f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = stringResource(R.string.live_then_label),
            style = MaterialTheme.typography.labelLarge,
            color = OpenMapsColors.TextOnDarkSecondary,
        )
        if (nextManeuverType != null) {
            ManeuverGlyph(
                type = nextManeuverType,
                tint = Color.White,
                modifier = Modifier.size(18.dp),
            )
            Box(
                Modifier
                    .width(1.dp)
                    .height(16.dp)
                    .background(Color.White.copy(alpha = 0.3f)),
            )
        }
        Text(
            text = nextInstruction,
            style = MaterialTheme.typography.labelLarge,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Transient status (rerouting / off route). Camera keeps running underneath. */
@Composable
private fun LiveStatusHud(title: String, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(OpenMapsColors.Navy950.copy(alpha = 0.50f))
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

/**
 * Bottom white card: three columns (remaining distance / remaining time / arrival), matching the
 * mockup's white trip card over the camera feed — not a dark pill like the HUD elements above it.
 * No recomputation — HUD values only, and the arrival clock is always `now + remainingDurationSeconds`,
 * read at render time rather than stored, so it stays correct without becoming a second estimate.
 */
@Composable
private fun LiveDrivingActions(
    voiceEnabled: Boolean,
    driverReportStatus: ExternalActionStatus,
    tripShareStatus: ExternalActionStatus,
    onVoiceMuteToggled: () -> Unit,
    onReport: (DriverReportType) -> Unit,
    onShareTrip: () -> Unit,
    onDismissExternalStatus: () -> Unit,
    onReroute: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showVolume by remember { mutableStateOf(false) }
    var showReports by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }

    Column(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .clip(GlassShapes.Pill)
                .background(OpenMapsColors.Navy950.copy(alpha = 0.48f))
                .padding(horizontal = 10.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LiveDrivingAction(
                label = stringResource(R.string.nav_action_report),
                onClick = { showReports = true },
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = OpenMapsColors.Warning,
                    modifier = Modifier.size(18.dp),
                )
            }
            LiveDrivingAction(
                label = stringResource(R.string.nav_action_volume),
                onClick = { showVolume = !showVolume },
            ) {
                Icon(
                    painter = painterResource(if (voiceEnabled) R.drawable.ic_volume else R.drawable.ic_volume_off),
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
            }
            LiveDrivingAction(
                label = stringResource(R.string.nav_action_share),
                onClick = onShareTrip,
                enabled = tripShareStatus != ExternalActionStatus.Working,
            ) {
                Text("↗", style = MaterialTheme.typography.titleMedium, color = Color.White)
            }
            LiveDrivingAction(
                label = stringResource(R.string.nav_action_reroute),
                onClick = onReroute,
            ) {
                Text("↻", style = MaterialTheme.typography.titleLarge, color = Color.White)
            }
        }

        if (showVolume) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterHorizontally)
                    .padding(top = 6.dp)
                    .clip(GlassShapes.Pill)
                    .background(OpenMapsColors.Navy950.copy(alpha = 0.58f))
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        audioManager?.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_LOWER,
                            0,
                        )
                    },
                    modifier = Modifier.size(38.dp),
                ) {
                    Text("−", style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
                IconButton(
                    onClick = onVoiceMuteToggled,
                    modifier = Modifier.size(38.dp),
                ) {
                    Icon(
                        painter = painterResource(if (voiceEnabled) R.drawable.ic_volume else R.drawable.ic_volume_off),
                        contentDescription = stringResource(if (voiceEnabled) R.string.live_voice_on else R.string.live_voice_off),
                        tint = Color.White,
                    )
                }
                IconButton(
                    onClick = {
                        audioManager?.adjustStreamVolume(
                            AudioManager.STREAM_MUSIC,
                            AudioManager.ADJUST_RAISE,
                            0,
                        )
                    },
                    modifier = Modifier.size(38.dp),
                ) {
                    Text("+", style = MaterialTheme.typography.titleLarge, color = Color.White)
                }
            }
        }
    }

    if (showReports) {
        val reports = listOf(
            DriverReportType.Police to R.string.nav_report_police,
            DriverReportType.Traffic to R.string.nav_report_traffic,
            DriverReportType.Crash to R.string.nav_report_crash,
            DriverReportType.LaneClosed to R.string.nav_report_lane_closed,
            DriverReportType.Construction to R.string.nav_report_construction,
            DriverReportType.RoadHazard to R.string.nav_report_road_hazard,
            DriverReportType.Flooding to R.string.nav_report_flooding,
            DriverReportType.DisabledVehicle to R.string.nav_report_disabled_vehicle,
            DriverReportType.ObjectOnRoad to R.string.nav_report_object_road,
        )
        AlertDialog(
            onDismissRequest = { showReports = false },
            title = { Text(stringResource(R.string.nav_report_title)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (driverReportStatus == ExternalActionStatus.Unavailable) {
                        Text(
                            text = stringResource(R.string.nav_report_unavailable),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                    reports.forEach { (type, label) ->
                        Button(
                            onClick = {
                                onReport(type)
                                showReports = false
                            },
                            enabled = driverReportStatus != ExternalActionStatus.Working,
                            modifier = Modifier.fillMaxWidth(),
                            shape = GlassShapes.Pill,
                        ) {
                            Text(stringResource(label))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showReports = false }) {
                    Text(stringResource(R.string.cd_dismiss))
                }
            },
        )
    }

    if (tripShareStatus == ExternalActionStatus.Unavailable) {
        AlertDialog(
            onDismissRequest = onDismissExternalStatus,
            title = { Text(stringResource(R.string.nav_share_unavailable_title)) },
            text = { Text(stringResource(R.string.nav_share_unavailable_body)) },
            confirmButton = {
                TextButton(onClick = onDismissExternalStatus) {
                    Text(stringResource(R.string.cd_dismiss))
                }
            },
        )
    }
}

@Composable
private fun LiveDrivingAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        content()
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.42f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The reference screenshots' bottom trip card: a drag handle, three equal-weight stats (time /
 * distance / arrival clock) with no captions, and a single status line below — a live congestion
 * reading when one exists, otherwise the destination name. Exit is still one tap away, just as a
 * small corner glyph rather than a dominant red pill: the reference never shows a prominent exit
 * control here, but the feature has to stay reachable.
 */
@Composable
private fun LiveTripSummary(
    hud: LiveHudState.Maneuver,
    roadIntelligenceState: RoadIntelligenceState,
    onExitNavigation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var confirmExit by remember { mutableStateOf(false) }
    val measurementSystem = LocalMeasurementSystem.current
    val locale = LocalAppLocale.current
    val congestion = roadIntelligenceState.activeAlerts.filterIsInstance<RouteAlert.Congestion>().firstOrNull()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Color(0xE6101C2B))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Box(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .align(Alignment.Center)
                    .width(36.dp)
                    .height(4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(Color.White.copy(alpha = 0.3f)),
            )
            IconButton(
                onClick = { confirmExit = true },
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .size(48.dp),
            ) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.nav_exit),
                    tint = Color(0xFFFF6B6B),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            Text(
                text = formatLiveDuration(hud.remainingDurationSeconds),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Text(
                text = formatLiveDistance(hud.remainingDistanceMeters),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
            Text(
                text = ArrivalTimeFormatter.format(
                    hud.remainingDurationSeconds,
                    System.currentTimeMillis(),
                    locale = locale,
                ),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
            )
        }
        Spacer(Modifier.height(8.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(if (congestion != null) OpenMapsColors.Warning else OpenMapsColors.ElectricBlue),
            )
            Text(
                text = congestion?.let { RoadAlertPresentation.headline(it, measurementSystem, locale) }
                    ?: hud.destinationName
                    ?: stringResource(R.string.live_status_destination),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.75f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    if (confirmExit) {
        AlertDialog(
            onDismissRequest = { confirmExit = false },
            title = { Text(stringResource(R.string.nav_exit_confirm_title)) },
            text = { Text(stringResource(R.string.nav_exit_confirm_body)) },
            confirmButton = {
                Button(
                    onClick = {
                        confirmExit = false
                        onExitNavigation()
                    },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFC62828),
                        contentColor = Color.White,
                    ),
                    shape = GlassShapes.Pill,
                ) {
                    Text(stringResource(R.string.nav_exit_end))
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmExit = false }) {
                    Text(stringResource(R.string.nav_exit_continue))
                }
            },
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
                text = stringResource(R.string.live_arrived_title_new),
                style = MaterialTheme.typography.headlineSmall,
                color = Color.White,
            )
        }
        destinationName?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextOnDarkSecondary,
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

@Composable
private fun formatLiveDistance(meters: Double): String =
    NavigationUnits.formatDistance(
        meters,
        LocalMeasurementSystem.current,
        LocalAppLocale.current,
    )

@Composable
private fun formatLiveDuration(seconds: Double): String =
    NavigationUnits.formatDuration(seconds, LocalAppLocale.current)

private fun RouteAlert.communityReportId(): String? = when (this) {
    is RouteAlert.Hazard -> event.metadata["communityReportId"]
    is RouteAlert.Informational -> event.metadata["communityReportId"]
    else -> null
}
