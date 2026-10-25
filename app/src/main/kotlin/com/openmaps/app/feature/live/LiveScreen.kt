package com.openmaps.app.feature.live

import android.media.AudioManager

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.Canvas
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openmaps.app.BuildConfig
import com.openmaps.app.anchor.ArCoreAnchorBackend
import com.openmaps.app.arrow.ARArrowPipeline
import com.openmaps.app.core.anchor.RouteAnchorManager
import com.openmaps.app.core.arrow.ArPipelineDiagnostics
import com.openmaps.app.core.arrow.ArPipelineSnapshot
import com.openmaps.app.core.arrow.ArPreviewProvider
import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.RouteResult
import com.openmaps.app.core.spatial.GeoProjector
import com.openmaps.app.core.spatial.arcore.ArCoreSpatialLocalization
import com.openmaps.app.feature.map.NavigationState
import com.openmaps.app.R
import com.openmaps.app.camera.CameraEngineEvent
import com.openmaps.app.camera.CameraFailure
import com.openmaps.app.camera.CameraXRearEngine
import com.openmaps.app.camera.RearCameraHost
import com.openmaps.app.camera.RearCameraState
import com.openmaps.app.core.camera.CameraPermissionState
import com.openmaps.app.core.designsystem.GlassShapes
import com.openmaps.app.core.designsystem.ReferenceManeuverArrow
import com.openmaps.app.core.designsystem.NavigationVolumePopup
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
import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.core.spatial.LiveLocalizationStatus
import com.openmaps.app.core.spatial.LiveLocalizationStatusMapper
import com.openmaps.app.core.spatial.SpatialLocalizationState
import com.openmaps.app.feature.map.ArrivalTimeFormatter
import com.openmaps.app.feature.map.LaneGuidanceStrip
import com.openmaps.app.core.roadintelligence.LaneGuidanceSafetyGate
import com.openmaps.app.feature.map.MapModeToggle
import com.openmaps.app.feature.map.RoadAlertPresentation
import com.openmaps.app.feature.map.displayName
import com.openmaps.app.feature.map.iconRes
import com.openmaps.app.feature.map.markerColor
import com.openmaps.app.map.MapMode
import java.util.Locale
import kotlinx.coroutines.delay
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
    onCommunityReportConfirm: (String) -> Unit = {},
    onCommunityReportDismiss: (String) -> Unit = {},
    /** Ends the active trip, unlike the Map toggle which keeps navigation running. */
    onExitNavigation: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // Reactive: flips from Spatial to CameraX mid-session if AR fails, which is what guarantees a
    // camera is always available in Live.
    val previewProvider by viewModel.previewProvider.collectAsState()
    // The first five seconds explain route/localization context. Afterwards declutter
    // non-critical street and traffic overlays, but NEVER hide routing safety warnings.
    var showStartupContext by remember { mutableStateOf(true) }
    LaunchedEffect(isNavigating) {
        showStartupContext = true
        if (isNavigating) {
            delay(5_000)
            showStartupContext = false
        }
    }

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

        // One content-sized top stack: the main maneuver, its following instruction and
        // optional alert must never use independent absolute offsets. Those offsets caused
        // the "Then" pill to collide with the turn banner on real devices and large text.
        val spatialState by viewModel.spatialLocalization.state
            .collectAsState(initial = SpatialLocalizationState.Idle)
        val localizationStatus = remember(spatialState) { LiveLocalizationStatusMapper.of(spatialState) }
        // Debug status is useful during the initial positioning window but should never
        // persist over active guidance or ship as part of the production visual surface.
        if (BuildConfig.DEBUG && showStartupContext) {
            LiveArDiagnosticsOverlay(
                previewProvider = previewProvider,
                spatialState = spatialState,
                hasRoute = route != null,
                isNavigating = isNavigating,
                arrowPipeline = viewModel.arrowPipeline,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(start = 8.dp, top = 8.dp),
            )
        }
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 64.dp, start = 14.dp, end = 14.dp),
            horizontalAlignment = Alignment.Start,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            when (val hud = hudState) {
                is LiveHudState.Maneuver -> {
                    LiveManeuverHud(hud = hud, modifier = Modifier.fillMaxWidth())
                    hud.nextInstruction?.let { then ->
                        LiveNextManeuverHud(
                            nextInstruction = then,
                            nextManeuverType = hud.nextManeuverType,
                        )
                    }
                }
                LiveHudState.Rerouting -> LiveStatusHud(
                    title = stringResource(R.string.live_rerouting),
                    modifier = Modifier.fillMaxWidth(),
                )
                is LiveHudState.OffRoute -> LiveStatusHud(
                    title = stringResource(R.string.live_off_route),
                    modifier = Modifier.fillMaxWidth(),
                )
                // No active route yet: a neutral text prompt only, never a directional graphic. Real
                // AR guidance exists exclusively once navigation is active — see
                // ARArrowPipeline.update, which gates every arrow on isNavigating and a tracked pose.
                LiveHudState.Idle -> LiveArPreviewLabel(modifier = Modifier.fillMaxWidth())
                is LiveHudState.Arrived -> Unit
            }
            if (localizationStatus != LiveLocalizationStatus.None &&
                (showStartupContext || localizationStatus == LiveLocalizationStatus.Locating ||
                    localizationStatus == LiveLocalizationStatus.Improving)
            ) {
                // Keep accuracy warnings visible while AR arrows cannot safely be placed.
                LocalizationChip(status = localizationStatus)
            }
            if (isNavigating) {
                // Highway navigation must not lose lane recommendations when a hazard is
                // ranked above the lane alert. Show real lane guidance independently,
                // then show the highest non-lane hazard/traffic message if available.
                roadIntelligenceState.laneGuidance?.takeIf(LaneGuidanceSafetyGate::canShow)?.let { guidance ->
                    LaneGuidanceStrip(
                        guidance = guidance,
                        darkSurface = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                LiveRoadAlertHud(
                    roadState = roadIntelligenceState,
                    showInformationalTraffic = showStartupContext,
                    onCommunityConfirm = onCommunityReportConfirm,
                    onCommunityDismiss = onCommunityReportDismiss,
                )
            }
        }

        // The reference screenshots' green current-street-name tag. A street applies to the whole
        // road rather than one point, so this is a fixed screen position (unlike the POI/destination
        // markers, which are real projected points) — centred over the lower-middle of the camera
        // feed, roughly where the chevron path is passing in the reference. [currentStreetName] is
        // the routing provider's own street_names field (never derived from the instruction text),
        // so the tag is simply absent rather than guessed when the provider omits it.
        if (showStartupContext && hudState is LiveHudState.Maneuver) {
            hudState.currentStreetName?.let { streetName ->
                LiveStreetNameTag(
                    streetName = streetName,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .offset(y = (-24).dp),
                )
            }
        }

        if (hudState is LiveHudState.Arrived) {
            LiveArrivedHud(
                destinationName = hudState.destinationName,
                onBackToMap = { onModeSelected(MapMode.Map) },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp),
            )
        }

        // Keep speed, side controls, driving actions and trip summary in a single content-sized
        // bottom stack. The old independently bottom-aligned rows assumed a fixed trip-card
        // height, so action labels were obscured when Claude's redesigned summary grew.
        if (hudState is LiveHudState.Maneuver) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom,
                ) {
                    LiveSpeedBlock(
                        currentSpeedMetersPerSecond = roadIntelligenceState.currentSpeedMetersPerSecond,
                        speedLimitAlert = roadIntelligenceState.activeAlerts.filterIsInstance<RouteAlert.SpeedLimit>().firstOrNull(),
                    )
                    Spacer(Modifier.weight(1f))
                    if (isNavigating) {
                        LiveSideControls(
                            onBackToMap = { onModeSelected(MapMode.Map) },
                            voiceEnabled = voiceEnabled,
                            onVoiceMuteToggled = onVoiceMuteToggled,
                            onExitNavigation = onExitNavigation,
                        )
                    }
                }
                LiveTripSummary(
                    hud = hudState,
                    roadIntelligenceState = roadIntelligenceState,
                    showInformationalTraffic = showStartupContext,
                )
            }
        }
    }
}

/**
 * Debug-build-only read of [ArPipelineDiagnostics]: names the one pipeline stage currently blocking
 * (or not blocking) AR arrows, instead of the single undifferentiated "no arrows" symptom.
 *
 * **Never reaches a release build.** Gated on [BuildConfig.DEBUG] rather than any runtime/user
 * setting, so there is no code path — flag, build variant, or otherwise — by which this diagnostic
 * text could render in front of a real driver. Production messaging stays exactly what
 * [LocalizationChip] already shows.
 */
@Composable
private fun LiveArDiagnosticsOverlay(
    previewProvider: PreviewProvider,
    spatialState: SpatialLocalizationState,
    hasRoute: Boolean,
    isNavigating: Boolean,
    arrowPipeline: ARArrowPipeline,
    modifier: Modifier = Modifier,
) {
    if (!BuildConfig.DEBUG) return
    val snapshot = ArPipelineSnapshot(
        previewProvider = if (previewProvider == PreviewProvider.Spatial) {
            ArPreviewProvider.Spatial
        } else {
            ArPreviewProvider.CameraX
        },
        spatialState = spatialState,
        hasRoute = hasRoute,
        isNavigating = isNavigating,
        glReady = arrowPipeline.isGlReady,
        sampledCount = arrowPipeline.sampledCount(),
        arrowCount = arrowPipeline.arrowCount(),
    )
    val diagnostic = remember(snapshot) { ArPipelineDiagnostics.classify(snapshot) }
    val detail = when {
        arrowPipeline.usingAnchors -> "anchored"
        arrowPipeline.degradedReason != null -> arrowPipeline.degradedReason
        else -> null
    }
    Text(
        text = buildString {
            append("AR: ")
            append(diagnostic.name)
            append(" · sampled ")
            append(snapshot.sampledCount)
            append(" · drawn ")
            append(snapshot.arrowCount)
            detail?.let { append(" · "); append(it) }
        },
        style = MaterialTheme.typography.labelSmall,
        color = Color.White,
        modifier = modifier
            .clip(MaterialTheme.shapes.small)
            .background(OpenMapsColors.Navy950.copy(alpha = 0.7f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
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
    showInformationalTraffic: Boolean = true,
    onCommunityConfirm: (String) -> Unit = {},
    onCommunityDismiss: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Hazards only. Speed moved to its own lower-left block ([LiveSpeedBlock]) because the mockup
    // puts the speedometer there, in the driver's natural eyeline next to the trip card — not in a
    // queue with warnings under the maneuver card.
    // Lane recommendations have their own dedicated panel above, so they stay visible
    // even when severe congestion or an incident is ranked first.
    val topAlert = roadState.activeAlerts.firstOrNull {
        it !is RouteAlert.SpeedLimit &&
            it !is RouteAlert.Lane &&
            // Static/historical congestion is not a current traffic condition.
            (it !is RouteAlert.Congestion ||
                (showInformationalTraffic && it.condition.isLive))
    } ?: return

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
    val unit = NavigationUnits.speedUnitLabel(measurementSystem, locale)
    // Unknown is NOT zero. Keep the speed instrument present for consistency with
    // the mockup, showing a dash until the device delivers an actual speed fix.
    val speed = currentSpeedMetersPerSecond?.let {
        NavigationUnits.formatCurrentSpeed(it, measurementSystem, locale).substringBeforeLast(" ")
    } ?: "—"
    val posted = speedLimitAlert?.let {
        NavigationUnits.formatPostedSpeed(
            value = it.info.value,
            sourceIsMph = it.info.unit == SpeedUnit.Mph,
            preference = measurementSystem,
            locale = locale,
        ).substringBeforeLast(" ")
    }
    LiveSpeedRow(
        speedValue = speed,
        speedUnit = unit,
        arcFraction = speedArcFraction(
            currentSpeedMetersPerSecond ?: 0f,
            measurementIsMph = unit.contains("mph", ignoreCase = true),
        ),
        limitValue = posted,
        modifier = modifier,
    )
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
 * The reference screenshots' green current-street-name tag — styled like a physical street-sign
 * pill, which is why this is the one Live element with its own distinct (green, not navy) fill.
 */
@Composable
private fun LiveStreetNameTag(streetName: String, modifier: Modifier = Modifier) {
    Text(
        text = streetName,
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(GlassShapes.Pill)
            .background(OpenMapsColors.Success)
            .padding(horizontal = 14.dp, vertical = 7.dp),
    )
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

        // Phase 1 guidance gate: chevrons and anchors only for valid ACTIVE navigation.
        // MapUiState.isNavigating is deliberately broader — it stays true for OffRoute,
        // Rerouting and Arrived so the HUD can keep describing the trip — and must not gate the
        // AR guidance layer, or stale-route arrows and anchors survive those transitions. The
        // HUD's own uses of isNavigating below are untouched by this gate.
        val isGuidanceActive = navigationState is NavigationState.Active

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
                    isNavigating = isGuidanceActive,
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
                isNavigating = isGuidanceActive,
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

        // The reference screenshots' "Destination" pin: the route's own final geometry point
        // projected through the same real [GeoProjector] seam place labels use — never a guessed
        // screen position. Pure math on values already held (route, pose, viewport size), so no
        // LaunchedEffect/extra state is needed; it simply recomputes along with everything else in
        // this composable when the pose updates.
        val destinationPoint = remember(route, spatialState, glView.width, glView.height) {
            val destinationPose = (spatialState as? SpatialLocalizationState.Tracking)?.pose
            val destination = route?.geometry?.lastOrNull()
            if (destinationPose == null || destination == null || glView.width <= 0 || glView.height <= 0) {
                null
            } else {
                GeoProjector.project(
                    world = destination,
                    userPose = destinationPose,
                    viewportWidthPx = glView.width,
                    viewportHeightPx = glView.height,
                )?.takeIf { it.visible && it.onScreen }
            }
        }
        if (destinationPoint != null && isNavigating) {
            LiveDestinationMarker(
                modifier = Modifier
                    .offset { IntOffset(destinationPoint.x.roundToInt(), destinationPoint.y.roundToInt()) }
                    .offset(y = -PLACE_MARKER_LIFT),
            )
        }
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

/**
 * The reference screenshots' "Destination" pin: a filled blue marker with a white ring, labeled
 * pill above it, same leader-stem-and-dot structure [LivePlaceMarker] uses for POIs so it reads as
 * one consistent marker language rather than a bespoke one-off shape.
 */
@Composable
private fun LiveDestinationMarker(modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.Start) {
        Text(
            text = stringResource(R.string.live_status_destination),
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            maxLines = 1,
            modifier = Modifier
                .clip(MaterialTheme.shapes.large)
                .background(OpenMapsColors.ElectricBlueFilled)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        )
        Box(
            Modifier
                .padding(start = PLACE_MARKER_STEM_INSET)
                .width(1.dp)
                .height(PLACE_MARKER_STEM_HEIGHT)
                .background(Color.White.copy(alpha = 0.5f)),
        )
        Box(
            Modifier
                .padding(start = PLACE_MARKER_STEM_INSET - DESTINATION_MARKER_DOT_SIZE / 2)
                .size(DESTINATION_MARKER_DOT_SIZE)
                .clip(CircleShape)
                .background(OpenMapsColors.ElectricBlueFilled)
                .border(2.dp, Color.White, CircleShape),
        )
    }
}

private val DESTINATION_MARKER_DOT_SIZE = 14.dp

/**
 * The reference screenshots' POI chip: one line, no icon — "{category} · {detail}" (e.g.
 * "Hotel · 0.3 mi", "Coffee · Open"). [detail] prefers whichever *real* provider field is most
 * useful and falls through in that order:
 *  1. The provider's own hours text ([com.openmaps.app.core.places.PlaceMetadata.openingHoursText]),
 *     when it supplied one — this is the only path that can produce something like the reference's
 *     "Open until 10 PM", and only because that exact string came from the provider.
 *  2. Real-time open/closed ([Place.openNow]), when the provider reports it explicitly.
 *  3. For parking only, the provider's parking type ([PlaceMetadata.parkingType]).
 *  4. Plain distance — always available, so the chip always has *something* true to say.
 *
 * Deliberately never a rating or a price: [Place] carries neither field, and the reference's
 * "4.7 ⭐" / "$12 4 spaces" / "$3.19" would have to be invented to reproduce verbatim, which this
 * codebase's own "nothing here is ever invented" rule (see [com.openmaps.app.core.places.PlaceMetadata])
 * forbids. A provider that starts supplying ratings/prices can extend [detail]'s fallthrough.
 */
@Composable
private fun LivePlaceChip(label: LivePlaceProjector.LivePlaceLabel, modifier: Modifier = Modifier) {
    val place = label.place
    val detail = when {
        !place.metadata.openingHoursText.isNullOrBlank() -> place.metadata.openingHoursText
        place.openNow != null -> stringResource(
            if (place.openNow) R.string.place_open_now else R.string.place_closed_now,
        )
        place.category == PlaceCategory.Parking && !place.metadata.parkingType.isNullOrBlank() ->
            place.metadata.parkingType!!.replaceFirstChar { it.uppercase() }
        else -> formatLiveDistance(label.distanceMeters)
    }
    Text(
        text = "${place.category.displayName()} · $detail",
        style = MaterialTheme.typography.labelMedium,
        color = Color.White,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(OpenMapsColors.Navy950.copy(alpha = 0.72f))
            .padding(horizontal = 12.dp, vertical = 7.dp),
    )
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
    onModeSelected: (MapMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    // Approved Live reference has two header elements only: OpenMaps wordmark and Live/Map.
    // Removing old speaker and close shortcuts also restores a readable wordmark on small phones.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        OpenMapsWordmark(
            modifier = Modifier.padding(start = 4.dp),
            onLight = false,
            large = true,
        )
        MapModeToggle(selected = MapMode.Live, onSelected = onModeSelected, transparent = true)
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
    onExitNavigation: () -> Unit = {},
) {
    var confirmExit by remember { mutableStateOf(false) }
    var showVolume by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Box {
            IconButton(
                onClick = { showVolume = true },
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(OpenMapsColors.Navy950.copy(alpha = 0.72f))
                    .border(1.dp, OpenMapsColors.ElectricBlue.copy(alpha = 0.45f), CircleShape),
            ) {
                Icon(
                    painter = painterResource(
                        if (voiceEnabled) R.drawable.ic_volume else R.drawable.ic_volume_off,
                    ),
                    contentDescription = "Navigation volume",
                    tint = if (voiceEnabled) OpenMapsColors.ElectricBlue else Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.size(22.dp),
                )
            }
            // +/- and mute only appear when volume is tapped; never add persistent controls
            // over the camera feed. Navigation voice setting remains the shared MapViewModel state.
            NavigationVolumePopup(
                expanded = showVolume,
                onDismiss = { showVolume = false },
                guidanceEnabled = voiceEnabled,
                onLower = {
                    audioManager?.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0,
                    )
                },
                onRaise = {
                    audioManager?.adjustStreamVolume(
                        AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0,
                    )
                },
                onMuteToggle = onVoiceMuteToggled,
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

        // A Map switch retains the trip. This distinct End control terminates navigation
        // only after confirmation, preventing accidental route dismissal.
        IconButton(
            onClick = { confirmExit = true },
            modifier = Modifier
                .size(50.dp)
                .clip(CircleShape)
                .background(OpenMapsColors.Navy950.copy(alpha = 0.72f))
                .border(1.dp, Color(0xFFC62828).copy(alpha = 0.90f), CircleShape),
        ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = stringResource(R.string.nav_exit),
                tint = Color(0xFFFF6B6B),
                modifier = Modifier.size(23.dp),
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

/**
 * The maneuver row: arrow, "in X", the instruction, and the following maneuver as a secondary line.
 *
 * Fixed min height so the row does not reflow every time the distance text changes width, which
 * would be distracting at speed.
 */
@Composable
private fun LiveManeuverHud(hud: LiveHudState.Maneuver, modifier: Modifier = Modifier) {
    // Approved navigation-flow image (screen 5): a LARGE WHITE standalone arrow with
    // a solid head directly on translucent navy. No blue square behind the icon.
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Color(0xEB0B1A30))
            .heightIn(min = 104.dp)
            .padding(horizontal = 14.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        ReferenceManeuverArrow(
            type = hud.type,
            size = 58.dp,
            modifier = Modifier.semantics {
                contentDescription = "Navigation maneuver: ${hud.instruction}"
            },
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                text = "in " + formatLiveDistance(hud.distanceToManeuverMeters),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.98f),
                maxLines = 1,
            )
            Text(
                text = hud.instruction.ifBlank { "Continue following route" },
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** The reference's small separate "Then ↱" pill, not another text-heavy banner. */
@Composable
private fun LiveNextManeuverHud(
    nextInstruction: String,
    nextManeuverType: ManeuverType?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(13.dp))
            .background(Color(0xDE0B1928))
            .semantics(mergeDescendants = true) {
                contentDescription = "Then " + nextInstruction
            }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.live_then_label),
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
        )
        if (nextManeuverType != null) {
            ReferenceManeuverArrow(type = nextManeuverType, size = 26.dp)
        } else {
            Text(nextInstruction, style = MaterialTheme.typography.bodyMedium,
                color = Color.White, maxLines = 1)
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
 * No-route idle state (Live opened without a destination, or one chosen but navigation not yet
 * started): a neutral text prompt only. Deliberately has no accompanying graphic of any kind —
 * a decorative arrow with no tie to a route, device pose, or ARCore tracking would be
 * indistinguishable from real guidance on a live camera feed. Real AR chevrons
 * ([ARArrowPipeline]) exist exclusively once navigation is active: [ARArrowPipeline.update] bails
 * and clears immediately unless `isNavigating` is true, a route and tracked pose both exist, and
 * real sample nodes come out of the route geometry.
 */
@Composable
private fun LiveArPreviewLabel(modifier: Modifier = Modifier) {
    Text(
        text = stringResource(R.string.live_ar_preview_label),
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        textAlign = TextAlign.Center,
        modifier = modifier
            .clip(MaterialTheme.shapes.large)
            .background(OpenMapsColors.Navy950.copy(alpha = 0.62f))
            .padding(horizontal = 16.dp, vertical = 10.dp),
    )
}

/**
 * The reference screenshots' bottom trip card: a drag handle, three equal-weight stats (time /
 * distance / arrival clock) with no captions, and a single status line below — a live congestion
 * reading when one exists.
 *
 * Deliberately has no exit/end-trip control: the reference never shows one, and Live itself never
 * owned trip lifecycle in the first place (see this file's own top doc comment — "Nothing here
 * starts, stops, copies or mutates a trip"). Ending a trip stays reachable from Map mode.
 */
@Composable
private fun LiveTripSummary(
    hud: LiveHudState.Maneuver,
    roadIntelligenceState: RoadIntelligenceState,
    modifier: Modifier = Modifier,
    showInformationalTraffic: Boolean = true,
) {
    val measurementSystem = LocalMeasurementSystem.current
    val locale = LocalAppLocale.current
    // Only real provider-backed, live congestion may be called "traffic" on the camera.
    val liveCongestion = roadIntelligenceState.activeAlerts
        .filterIsInstance<RouteAlert.Congestion>()
        .firstOrNull { it.condition.isLive }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(24.dp))
            .background(Color(0xE6101C2B))
            .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(24.dp))
            .padding(horizontal = 12.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            listOf(
                formatLiveDuration(hud.remainingDurationSeconds),
                formatLiveDistance(hud.remainingDistanceMeters),
                ArrivalTimeFormatter.format(
                    hud.remainingDurationSeconds,
                    System.currentTimeMillis(),
                    locale = locale,
                ),
            ).forEach { label ->
                Text(
                    text = label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (showInformationalTraffic) liveCongestion?.let { congestion ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(OpenMapsColors.Warning),
                )
                Text(
                    text = RoadAlertPresentation.headline(congestion, measurementSystem, locale),
                    style = MaterialTheme.typography.labelMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
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
