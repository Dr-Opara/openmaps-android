package com.openmaps.app.feature.map

import androidx.annotation.StringRes
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.GlassShapes
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.glass
import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.core.routing.RouteManeuver
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
        Icons.Filled.ArrowForward,
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
    val nextManeuver = progress.nextManeuver ?: progress.currentManeuver
    // Filled, saturated backgrounds: this sits on top of a map, so it must read at a glance.
    // Warning is a light amber, so it takes navy type rather than white — white on #FFB84D is
    // ~1.9:1 and effectively unreadable on a phone in daylight.
    val isWarning = navigationState is NavigationState.OffRoute || navigationState is NavigationState.Rerouting
    val background = if (isWarning) OpenMapsColors.Warning else OpenMapsColors.ElectricBlueFilled
    val foreground = if (isWarning) OpenMapsColors.Navy900 else Color.White

    Box(
        modifier = modifier
            .fillMaxWidth()
            .glass(GlassShapes.Card)
            .background(background)
            .heightIn(min = 64.dp)
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        when (navigationState) {
            is NavigationState.Rerouting -> ReroutingRow(foreground)
            is NavigationState.Arrived -> ArrivedRow(foreground)
            is NavigationState.OffRoute -> OffRouteRow(foreground)
            else -> ManeuverRow(
                maneuver = nextManeuver,
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
    val instruction = maneuver.instruction.ifBlank { maneuver.streetName.orEmpty() }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Box(
            modifier = Modifier.size(44.dp).semantics { contentDescription = maneuver.type.spokenLabel },
            contentAlignment = Alignment.Center,
        ) {
            ManeuverGlyph(maneuver.type, foreground)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                text = stringResource(R.string.nav_maneuver_in_format, formatNavigationDistance(distanceToManeuverMeters)),
                style = MaterialTheme.typography.labelLarge,
                color = foreground.copy(alpha = 0.85f),
            )
            Text(
                text = instruction.ifBlank { stringResource(R.string.nav_following) },
                style = MaterialTheme.typography.titleMedium,
                color = foreground,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val then = progress.nextManeuver?.instruction?.takeIf { it.isNotBlank() && it != instruction }
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
 * Start/End row shown beneath the route summary. While a route is merely loaded this is the
 * affordance that begins a trip; during one it ends it.
 */
@Composable
fun NavigationControls(
    canStart: Boolean,
    isNavigating: Boolean,
    navigationError: NavigationError?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val errorMessage = navigationError?.let { messageFor(it) }
    if (!isNavigating && !canStart && errorMessage == null) return
    val startLabel = stringResource(R.string.cd_start_navigation)
    val stopLabel = stringResource(R.string.cd_stop_navigation)

    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (errorMessage != null) {
            Text(
                text = errorMessage,
                style = MaterialTheme.typography.bodySmall,
                color = OpenMapsColors.Warning,
                modifier = Modifier.weight(1f),
            )
        }
        if (isNavigating) {
            // The visible label is "End"; spell out what that means for a screen reader, which
            // would otherwise announce a bare, ambiguous verb.
            TextButton(
                onClick = onStop,
                modifier = Modifier.semantics { contentDescription = stopLabel },
            ) {
                Text(
                    text = stringResource(R.string.nav_stop),
                    style = MaterialTheme.typography.labelLarge,
                    color = OpenMapsColors.TextSecondary,
                )
            }
        } else {
            TextButton(
                onClick = onStart,
                enabled = canStart,
                modifier = Modifier.semantics { contentDescription = startLabel },
            ) {
                Text(
                    text = stringResource(R.string.nav_start),
                    style = MaterialTheme.typography.labelLarge,
                    color = if (canStart) OpenMapsColors.ElectricBlue else OpenMapsColors.TextSecondary,
                )
            }
        }
    }
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
private fun formatNavigationDistance(meters: Double): String {
    val feet = meters * FEET_PER_METER
    val miles = meters / METERS_PER_MILE
    return when {
        // Under 1000 ft: whole feet. Rounding to "10s" would make a 340 ft turn read as 340.
        feet < 1000.0 -> "${feet.roundToInt()} ft"
        miles < 10 -> String.format(Locale.US, "%.1f mi", miles)
        else -> "${miles.roundToInt()} mi"
    }
}

private fun formatNavigationDuration(seconds: Double): String {
    val totalMinutes = (seconds / 60).roundToInt().coerceAtLeast(1)
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return if (hours > 0) "$hours hr $minutes min" else "$minutes min"
}

private const val FEET_PER_METER = 3.28084
private const val METERS_PER_MILE = 1609.344