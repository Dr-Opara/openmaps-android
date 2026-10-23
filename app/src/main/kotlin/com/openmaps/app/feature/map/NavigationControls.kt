package com.openmaps.app.feature.map

import android.media.AudioManager
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.GlassShapes
import com.openmaps.app.core.designsystem.ManeuverIconTile
import com.openmaps.app.feature.live.LiveSpeedRow
import com.openmaps.app.feature.live.speedArcFraction
import com.openmaps.app.core.roadintelligence.RouteAlert
import com.openmaps.app.core.roadintelligence.SpeedUnit
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.surfaceCard
import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.core.localization.LocalAppLocale
import com.openmaps.app.core.localization.LocalMeasurementSystem
import com.openmaps.app.core.localization.NavigationUnits
import com.openmaps.app.core.reporting.DriverReportType
import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.core.routing.RouteManeuver
import com.openmaps.app.core.routing.displayInstruction
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The navigation surfaces for an active trip. Kept out of [MapControls] because they only exist
 * while navigating, and out of the ViewModel because they are presentation only.
 *
 * Two pieces: a persistent [NavigationBanner] at the top (the next maneuver, its distance, and
 * live remaining time/distance) and an inline [NavigationControls] row (start/end plus the follow
 * state). [ArrivalCard] replaces both once the trip is done.
 */

/**
 * Maneuver arrow glyph. Rotation encodes the turn; the label carries the meaning for a11y.
 *
 * Uses [ArrowForward] (pointing east) rather than a dedicated navigation arrow: only
 * `material-icons-core` is on the classpath (50 icons, no `Navigation`/`ArrowUpward`), and pulling
 * in `material-icons-extended` for one glyph would add a large dependency to the APK. Rotating an
 * arrow that already exists is the same visual result.
 */
@Composable
private fun ManeuverGlyph(type: ManeuverType, tint: Color, modifier: Modifier = Modifier) {
    Icon(
        Icons.AutoMirrored.Filled.ArrowForward,
        contentDescription = null, // decorative; the instruction text beside it carries the meaning
        tint = tint,
        modifier = modifier.graphicsLayer { rotationZ = type.arrowRotationDegrees },
    )
}

/**
 * Screen-reader text for a maneuver. An arrow icon alone is meaningless to a screen reader, so the
 * spoken description is derived from the type rather than reused from the provider's prose (which
 * reads as a full sentence and would be redundant next to the visible instruction).
 */
private val ManeuverType.spokenLabel: String
    get() = when (this) {
        ManeuverType.Start -> "Starting route"
        ManeuverType.Continue -> "Continue"
        ManeuverType.SlightLeft -> "Bear left"
        ManeuverType.SlightRight -> "Bear right"
        ManeuverType.TurnLeft -> "Turn left"
        ManeuverType.TurnRight -> "Turn right"
        ManeuverType.SharpLeft -> "Sharp left"
        ManeuverType.SharpRight -> "Sharp right"
        ManeuverType.UTurn -> "Make a U-turn"
        ManeuverType.Merge -> "Merge"
        ManeuverType.Ramp -> "Take the ramp"
        ManeuverType.Roundabout -> "Enter the roundabout"
        ManeuverType.Destination -> "Arrive at your destination"
        ManeuverType.Unknown -> "Continue"
    }

/**
 * Clockwise rotation, in degrees, for the arrow. Expressed as "which way is the arrow pointing
 * relative to straight ahead" and then converted: the glyph points east at 0°, so straight ahead
 * (north) is -90° and each 45° step is a quarter turn.
 */
private val ManeuverType.arrowRotationDegrees: Float
    get() = (pointingDegrees - EAST_DEGREES).toFloat()

/** Compass bearing the arrow should indicate, in degrees clockwise from north. */
private val ManeuverType.pointingDegrees: Int
    get() = when (this) {
        ManeuverType.Start -> NORTH_DEGREES
        ManeuverType.Continue -> NORTH_DEGREES
        ManeuverType.SlightLeft -> NORTH_DEGREES - 45
        ManeuverType.SlightRight -> NORTH_DEGREES + 45
        ManeuverType.TurnLeft -> NORTH_DEGREES - 90
        ManeuverType.TurnRight -> NORTH_DEGREES + 90
        ManeuverType.SharpLeft -> NORTH_DEGREES - 135
        ManeuverType.SharpRight -> NORTH_DEGREES + 135
        ManeuverType.UTurn -> SOUTH_DEGREES
        // Merging reads as "get onto the road ahead", so it stays straight.
        ManeuverType.Merge -> NORTH_DEGREES
        ManeuverType.Ramp -> NORTH_DEGREES + 45
        ManeuverType.Roundabout -> NORTH_DEGREES + 90
        ManeuverType.Destination -> NORTH_DEGREES
        ManeuverType.Unknown -> NORTH_DEGREES
    }

private const val NORTH_DEGREES = 0
private const val EAST_DEGREES = 90
private const val SOUTH_DEGREES = 180

/**
 * The top banner during a trip. Renders one of four things, in priority order:
 * rerouting (spinner), arrived (check), off route (warning), otherwise the next maneuver.
 */
@Composable
fun NavigationBanner(
    navigationState: NavigationState,
    followSuspended: Boolean,
    modifier: Modifier = Modifier,
) {
    val progress = navigationState.progressOrNull() ?: return
    // Primary row pairs the CURRENT maneuver with the distance travelled to reach it.
    // `distanceToNextManeuverMeters` is measured to `currentManeuver.endShapeIndex`, and a
    // Valhalla maneuver's instruction is the action taken at the END of its own segment — so
    // showing `nextManeuver` here (as this did previously) put the *following* turn's instruction
    // next to the *current* turn's distance.
    val currentManeuver = progress.currentManeuver
    // Filled, saturated backgrounds: this sits on top of a map, so it must read at a glance.
    // Warning is a light amber, so it takes navy type rather than white — white on #FFB84D is
    // ~1.9:1 and effectively unreadable on a phone in daylight.
    val isWarning = navigationState is NavigationState.OffRoute || navigationState is NavigationState.Rerouting
    // Map guidance matches Live: dark readable glass with cobalt maneuver tile.
    // Critical off-route/reroute states use a dark red-amber alert surface.
    val background = if (isWarning) Color(0xEF4A3022) else Color(0xE60B1928)
    val foreground = Color.White

    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(GlassShapes.Card)
            .background(background)
            .heightIn(min = 86.dp)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        when (navigationState) {
            is NavigationState.Rerouting -> ReroutingRow(foreground)
            is NavigationState.Arrived -> ArrivedRow(foreground)
            is NavigationState.OffRoute -> OffRouteRow(foreground)
            else -> ManeuverRow(
                maneuver = currentManeuver,
                distanceToManeuverMeters = progress.distanceToNextManeuverMeters,
                progress = progress,
                followSuspended = followSuspended,
                foreground = foreground,
            )
        }
    }
}

@Composable
private fun ManeuverRow(
    maneuver: RouteManeuver,
    distanceToManeuverMeters: Double,
    progress: RouteProgress,
    followSuspended: Boolean,
    foreground: Color,
) {
    val instruction = maneuver.displayInstruction()
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.semantics { contentDescription = maneuver.type.spokenLabel },
            contentAlignment = Alignment.Center,
        ) {
            ManeuverIconTile(type = maneuver.type, size = 62.dp)
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.nav_maneuver_in_format, formatNavigationDistance(distanceToManeuverMeters)),
                style = MaterialTheme.typography.titleMedium,
                color = foreground.copy(alpha = 0.90f),
            )
            Text(
                text = instruction.ifBlank { stringResource(R.string.nav_following) },
                style = MaterialTheme.typography.titleLarge,
                color = foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val then = progress.nextManeuver
                ?.displayInstruction()
                ?.takeIf { it.isNotBlank() && it != instruction }
            if (then != null) {
                Text(
                    text = stringResource(R.string.nav_next_maneuver, then),
                    style = MaterialTheme.typography.bodySmall,
                    color = foreground.copy(alpha = 0.8f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (followSuspended) {
                Text(
                    text = stringResource(R.string.nav_follow_suspended),
                    style = MaterialTheme.typography.labelSmall,
                    color = foreground.copy(alpha = 0.9f),
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        // Trip summary: "12 mi · 18 min left". Announces as one phrase rather than two loose
        // numbers that read ambiguously out of context.
        Text(
            text = stringResource(
                R.string.nav_remaining_format,
                formatNavigationDistance(progress.remainingDistanceMeters),
                formatNavigationDuration(progress.remainingDurationSeconds),
            ),
            style = MaterialTheme.typography.labelLarge,
            color = foreground,
        )
    }
}

@Composable
private fun ReroutingRow(foreground: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp, color = foreground)
        Text(
            text = stringResource(R.string.nav_rerouting),
            style = MaterialTheme.typography.titleMedium,
            color = foreground,
        )
    }
}

@Composable
private fun OffRouteRow(foreground: Color) {
    Column {
        Text(
            text = stringResource(R.string.nav_off_route),
            style = MaterialTheme.typography.titleMedium,
            color = foreground,
        )
        Text(
            text = stringResource(R.string.nav_off_route_hint),
            style = MaterialTheme.typography.bodySmall,
            color = foreground.copy(alpha = 0.85f),
        )
    }
}

@Composable
private fun ArrivedRow(foreground: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = foreground, modifier = Modifier.size(24.dp))
        Column {
            Text(
                text = stringResource(R.string.nav_arrived_title),
                style = MaterialTheme.typography.titleMedium,
                color = foreground,
            )
            Text(
                text = stringResource(R.string.nav_arrived_body),
                style = MaterialTheme.typography.bodySmall,
                color = foreground.copy(alpha = 0.85f),
            )
        }
    }
}

/**
 * Route Preview's action block (Phase 12A, section D). Called only once a destination exists
 * (see `BottomPanel`), so it always renders something rather than the previous "early return to
 * nothing" — Start is visible, just disabled, from the moment a destination exists, which is the
 * fix for "Start is too hard to find/see": it no longer appears only once a route has already
 * loaded, and it is no longer a small right-aligned text link.
 *
 *  - Navigating: just "End" (unchanged behaviour/placement).
 *  - Otherwise: a single visible two-button row — secondary "Directions" (re-opens Destination
 *    Details to review, without discarding the computed route — [onDirectionsReview]) and a
 *    primary, filled, high-contrast "Start" (D2: OpenMaps blue, white text, full-width-sharing,
 *    minimum 48dp touch target).
 */
@Composable
fun NavigationControls(
    canStart: Boolean,
    isNavigating: Boolean,
    navigationError: NavigationError?,
    selectedNavigationMode: NavigationMode,
    onNavigationModeSelected: (NavigationMode) -> Unit,
    onSteps: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorMessage = navigationError?.let { messageFor(it) }
    val startLabel = stringResource(R.string.cd_start_navigation)
    val stopLabel = stringResource(R.string.cd_stop_navigation)
    val stepsLabel = stringResource(R.string.cd_steps)

    Column(modifier = modifier.fillMaxWidth()) {
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 8.dp),
            )
        }
        if (isNavigating) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                // The visible label is "End"; spell out what that means for a screen reader, which
                // would otherwise announce a bare, ambiguous verb.
                TextButton(
                    onClick = onStop,
                    modifier = Modifier.semantics { contentDescription = stopLabel },
                ) {
                    Text(
                        text = stringResource(R.string.nav_stop),
                        style = MaterialTheme.typography.labelLarge,
                        color = Color.White,
                    )
                }
            }
        } else {
            Text(
                text = stringResource(R.string.nav_choose_experience),
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                modifier = Modifier.padding(bottom = 2.dp),
            )
            Text(
                text = stringResource(R.string.nav_choose_experience_hint),
                style = MaterialTheme.typography.bodySmall,
                color = OpenMapsColors.TextOnDarkSecondary,
                modifier = Modifier.padding(bottom = 10.dp),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                NavigationViewOptionCard(
                    title = stringResource(R.string.nav_mode_map_title),
                    subtitle = stringResource(R.string.nav_mode_map_body),
                    selected = selectedNavigationMode == NavigationMode.Map,
                    onClick = { onNavigationModeSelected(NavigationMode.Map) },
                    icon = painterResource(R.drawable.ic_map),
                    modifier = Modifier.weight(1f),
                )
                NavigationViewOptionCard(
                    title = stringResource(R.string.nav_mode_live_title),
                    subtitle = stringResource(R.string.nav_mode_live_body),
                    selected = selectedNavigationMode == NavigationMode.Live,
                    onClick = { onNavigationModeSelected(NavigationMode.Live) },
                    icon = painterResource(R.drawable.ic_camera_outline),
                    modifier = Modifier.weight(1f),
                )
            }
            RoutePreviewCtaRow(
                startLabel = if (selectedNavigationMode == NavigationMode.Live) {
                    stringResource(R.string.nav_start_live)
                } else {
                    stringResource(R.string.nav_start_map)
                },
                onStart = onStart,
                stepsLabel = stringResource(R.string.nav_steps),
                onSteps = onSteps,
                startEnabled = canStart,
            )
        }
    }
}

@Composable
private fun NavigationModeSelector(
    selected: NavigationMode,
    onSelected: (NavigationMode) -> Unit,
) {
    Text(
        text = stringResource(R.string.nav_choose_experience),
        style = MaterialTheme.typography.titleMedium,
        color = OpenMapsColors.TextPrimary,
        modifier = Modifier.padding(bottom = 2.dp),
    )
    Text(
        text = stringResource(R.string.nav_choose_experience_hint),
        style = MaterialTheme.typography.bodySmall,
        color = OpenMapsColors.TextSecondary,
        modifier = Modifier.padding(bottom = 10.dp),
    )
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NavigationModeCard(
            title = stringResource(R.string.nav_mode_map_title),
            subtitle = stringResource(R.string.nav_mode_map_body),
            selected = selected == NavigationMode.Map,
            onClick = { onSelected(NavigationMode.Map) },
            modifier = Modifier.weight(1f),
        )
        NavigationModeCard(
            title = stringResource(R.string.nav_mode_live_title),
            subtitle = stringResource(R.string.nav_mode_live_body),
            selected = selected == NavigationMode.Live,
            onClick = { onSelected(NavigationMode.Live) },
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun NavigationModeCard(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val background = if (selected) OpenMapsColors.ElectricBlueFilled else OpenMapsColors.SurfaceMuted
    val foreground = if (selected) Color.White else OpenMapsColors.TextPrimary
    Column(
        modifier = modifier
            .heightIn(min = 104.dp)
            .background(background, GlassShapes.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 12.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = foreground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = foreground.copy(alpha = if (selected) 0.86f else 0.70f),
            modifier = Modifier.padding(top = 4.dp),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * Conventional Map Navigation bottom experience.
 *
 * The compact action strip is deliberately smaller than the trip summary. The trip figures and
 * red Exit are the persistent, highest-priority controls at the bottom of the driving screen.
 */
@Composable
fun ActiveMapNavigationPanel(
    progress: RouteProgress,
    voiceEnabled: Boolean,
    driverReportStatus: ExternalActionStatus,
    tripShareStatus: ExternalActionStatus,
    onVoiceMuteToggled: () -> Unit,
    onReport: (DriverReportType) -> Unit,
    onShareTrip: () -> Unit,
    onDismissExternalStatus: () -> Unit,
    onReroute: () -> Unit,
    onExitNavigation: () -> Unit,
    modifier: Modifier = Modifier,
    currentSpeedMetersPerSecond: Float? = null,
    speedLimitAlert: RouteAlert.SpeedLimit? = null,
) {
    var showVolume by remember { mutableStateOf(false) }
    var showMoreActions by remember { mutableStateOf(false) }
    var showReports by remember { mutableStateOf(false) }
    var confirmExit by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val audioManager = remember(context) { context.getSystemService(AudioManager::class.java) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // Mockup-consistent current driving speed and *provider-sourced* posted speed limit.
        // An unknown limit is omitted, not guessed. The same instrument is used in Live.
        val measurementSystem = LocalMeasurementSystem.current
        val locale = LocalAppLocale.current
        val speedUnit = NavigationUnits.speedUnitLabel(measurementSystem, locale)
        val speedValue = currentSpeedMetersPerSecond?.let {
            NavigationUnits.formatCurrentSpeed(it, measurementSystem, locale).substringBeforeLast(" ")
        }
        val postedLimit = speedLimitAlert?.let {
            NavigationUnits.formatPostedSpeed(
                value = it.info.value,
                sourceIsMph = it.info.unit == SpeedUnit.Mph,
                preference = measurementSystem,
                locale = locale,
            ).substringBeforeLast(" ")
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.Bottom,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (speedValue != null || postedLimit != null) {
                LiveSpeedRow(
                    speedValue = speedValue,
                    speedUnit = speedUnit,
                    arcFraction = speedArcFraction(
                        currentSpeedMetersPerSecond ?: 0f,
                        measurementIsMph = speedUnit.contains("mph", ignoreCase = true),
                    ),
                    limitValue = postedLimit,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    "Speed unavailable",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.weight(1f)
                        .background(OpenMapsColors.Navy900.copy(alpha = 0.85f), GlassShapes.Pill)
                        .padding(12.dp),
                )
            }
            Box {
                DrivingQuickAction(
                    label = stringResource(R.string.nav_action_volume),
                    onClick = { showVolume = true },
                ) {
                    Icon(
                        painter = painterResource(
                            if (voiceEnabled) R.drawable.ic_volume else R.drawable.ic_volume_off,
                        ),
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(24.dp),
                    )
                }
                // +/−/mute stay hidden until the user taps the volume control.
                DropdownMenu(expanded = showVolume, onDismissRequest = { showVolume = false }) {
                    DropdownMenuItem(
                        text = { Text("Volume down") },
                        onClick = {
                            audioManager?.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, 0,
                            )
                        },
                    )
                    DropdownMenuItem(
                        text = { Text(if (voiceEnabled) "Mute guidance" else "Unmute guidance") },
                        onClick = { onVoiceMuteToggled() },
                    )
                    DropdownMenuItem(
                        text = { Text("Volume up") },
                        onClick = {
                            audioManager?.adjustStreamVolume(
                                AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, 0,
                            )
                        },
                    )
                }
            }
            Box {
                DrivingQuickAction(label = "More", onClick = { showMoreActions = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = null,
                        tint = Color.White, modifier = Modifier.size(24.dp))
                }
                DropdownMenu(
                    expanded = showMoreActions,
                    onDismissRequest = { showMoreActions = false },
                ) {
                    DropdownMenuItem(text = { Text("Report hazard") }, onClick = {
                        showMoreActions = false
                        showReports = true
                    })
                    DropdownMenuItem(text = { Text("Share trip") }, onClick = {
                        showMoreActions = false
                        onShareTrip()
                    }, enabled = tripShareStatus != ExternalActionStatus.Working)
                    DropdownMenuItem(text = { Text("Reroute") }, onClick = {
                        showMoreActions = false
                        onReroute()
                    })
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(GlassShapes.Card)
                .background(Color(0xE60B1928))
                .padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TripMetric(
                value = formatNavigationDuration(progress.remainingDurationSeconds),
                label = stringResource(R.string.live_stat_to_arrive),
                modifier = Modifier.weight(1f),
            )
            TripMetric(
                value = formatNavigationDistance(progress.remainingDistanceMeters),
                label = stringResource(R.string.live_stat_remaining),
                modifier = Modifier.weight(1f),
            )
            TripMetric(
                value = ArrivalTimeFormatter.format(
                    progress.remainingDurationSeconds,
                    System.currentTimeMillis(),
                    locale = LocalAppLocale.current,
                ),
                label = stringResource(R.string.live_stat_arrival),
                modifier = Modifier.weight(1f),
            )
            Button(
                onClick = { confirmExit = true },
                shape = GlassShapes.Pill,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFFC62828),
                    contentColor = Color.White,
                ),
                modifier = Modifier.heightIn(min = 44.dp),
            ) {
                Text(
                    text = stringResource(R.string.nav_exit),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
            }
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

    if (showReports) {
        DriverReportDialog(
            status = driverReportStatus,
            onReport = {
                onReport(it)
                showReports = false
            },
            onDismiss = { showReports = false },
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
private fun DrivingQuickAction(
    label: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
    icon: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .heightIn(min = 44.dp)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        icon()
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = if (enabled) Color.White else Color.White.copy(alpha = 0.45f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun TripMetric(value: String, label: String, modifier: Modifier = Modifier) {
    Column(modifier = modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = Color.White,
            maxLines = 1,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.75f),
            maxLines = 1,
        )
    }
}

@Composable
private fun DriverReportDialog(
    status: ExternalActionStatus,
    onReport: (DriverReportType) -> Unit,
    onDismiss: () -> Unit,
) {
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
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.nav_report_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (status == ExternalActionStatus.Unavailable) {
                    Text(
                        text = stringResource(R.string.nav_report_unavailable),
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenMapsColors.TextSecondary,
                    )
                }
                reports.chunked(2).forEach { row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEach { (type, label) ->
                            Button(
                                onClick = { onReport(type) },
                                enabled = status != ExternalActionStatus.Working,
                                modifier = Modifier.weight(1f),
                                shape = GlassShapes.Pill,
                            ) {
                                Text(
                                    text = stringResource(label),
                                    style = MaterialTheme.typography.labelSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cd_dismiss))
            }
        },
    )
}

/** Mandatory acknowledgement shown before any camera-based driving session begins. */
@Composable
fun LiveSafetyWarningDialog(
    onUseMapNavigation: () -> Unit,
    onConfirmLive: () -> Unit,
    onDismiss: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.live_safety_title),
                style = MaterialTheme.typography.titleLarge,
                color = OpenMapsColors.TextPrimary,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.live_safety_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextPrimary,
                )
                Text(
                    text = stringResource(R.string.live_safety_eyes),
                    style = MaterialTheme.typography.titleMedium,
                    color = OpenMapsColors.TextPrimary,
                )
                Text(
                    text = stringResource(R.string.live_safety_body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                )
                Text(
                    text = stringResource(R.string.live_arcore_disclosure),
                    style = MaterialTheme.typography.bodySmall,
                    color = OpenMapsColors.TextSecondary,
                )
                Text(
                    text = stringResource(R.string.live_geospatial_disclosure),
                    style = MaterialTheme.typography.bodySmall,
                    color = OpenMapsColors.TextSecondary,
                )
                TextButton(
                    onClick = {
                        uriHandler.openUri(
                            "https://support.google.com/ar?p=how-google-play-services-for-ar-handles-your-data",
                        )
                    },
                ) {
                    Text(stringResource(R.string.live_geospatial_learn_more))
                }
            }
        },
        confirmButton = {
            Button(onClick = onConfirmLive, shape = GlassShapes.Pill) {
                Text(stringResource(R.string.live_safety_confirm), maxLines = 1)
            }
        },
        dismissButton = {
            TextButton(onClick = onUseMapNavigation) {
                Text(stringResource(R.string.live_safety_use_map))
            }
        },
    )
}

/** `@Composable` because `stringResource` is: error copy resolves per configuration. */
@Composable
private fun messageFor(error: NavigationError): String = when (error) {
    NavigationError.LocationUnavailable -> stringResource(R.string.nav_error_no_location)
    NavigationError.RouteUnavailable -> stringResource(R.string.nav_error_no_route)
}

/** The progress any rendering state can display, or null for states that carry none. */
private fun NavigationState.progressOrNull(): RouteProgress? = when (this) {
    is NavigationState.Active -> progress
    is NavigationState.OffRoute -> progress
    is NavigationState.Arrived -> progress
    NavigationState.Idle, NavigationState.Ready, NavigationState.Rerouting, is NavigationState.Error -> null
}

/**
 * Navigation distances are short-range and shown next to an arrow, so they switch to feet far
 * earlier than the trip summary's miles — "in 0.2 mi" is useless when the turn is 300 ft away.
 */
@Composable
private fun formatNavigationDistance(meters: Double): String =
    NavigationUnits.formatDistance(meters, LocalMeasurementSystem.current, LocalAppLocale.current)

@Composable
private fun formatNavigationDuration(seconds: Double): String =
    NavigationUnits.formatDuration(seconds, LocalAppLocale.current)