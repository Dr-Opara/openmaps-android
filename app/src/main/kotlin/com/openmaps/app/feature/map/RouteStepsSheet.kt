package com.openmaps.app.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.GlassShapes
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.surfaceCard
import com.openmaps.app.core.routing.RouteManeuver

/**
 * Route Preview's "Steps" list (mockup Route Preview).
 *
 * Shows the maneuvers of the **already-computed** route. It never requests a route itself, so
 * opening it cannot race with or replace the route the user is about to drive — see
 * `MapViewModel.onStepsRequested`.
 *
 * Read-only by design: tapping a row does not "navigate to that step". Mid-trip step scrubbing is a
 * navigation behaviour, not a Route Preview one, and a control that silently did nothing here would
 * be worse than no control. The rows are therefore informational.
 */
@Composable
fun RouteStepsSheet(
    maneuvers: List<RouteManeuver>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .surfaceCard(GlassShapes.Card)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Icon(
                Icons.AutoMirrored.Filled.List,
                contentDescription = null,
                tint = OpenMapsColors.ElectricBlueFilled,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.nav_steps),
                style = MaterialTheme.typography.titleMedium,
                color = OpenMapsColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_dismiss),
                    tint = OpenMapsColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        if (maneuvers.isEmpty()) {
            Text(
                text = stringResource(R.string.nav_steps_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextSecondary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.heightIn(max = MAX_STEPS_SHEET_HEIGHT)) {
                items(maneuvers, key = { "${it.type}-${it.endShapeIndex}" }) { maneuver ->
                    RouteStepRow(maneuver)
                }
            }
        }
    }
}

@Composable
private fun RouteStepRow(maneuver: RouteManeuver) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(OpenMapsColors.SurfaceMuted, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = if (maneuver.type == com.openmaps.app.core.routing.ManeuverType.Destination) {
                    Icons.Filled.Place
                } else {
                    Icons.AutoMirrored.Filled.ArrowForward
                },
                contentDescription = null,
                tint = OpenMapsColors.ElectricBlueFilled,
                modifier = Modifier
                    .size(16.dp)
                    .graphicsLayer { rotationZ = maneuver.type.stepArrowRotationDegrees() },
            )
        }
        Spacer(Modifier.width(12.dp))
        Text(
            text = maneuver.instruction.ifBlank { maneuver.streetName.orEmpty() },
            style = MaterialTheme.typography.bodyMedium,
            color = OpenMapsColors.TextPrimary,
            modifier = Modifier
                .weight(1f)
                .semantics { contentDescription = maneuver.type.stepSpokenLabel() },
        )
    }
}

/** Rotation for a step's arrow, matching [NavigationControls]' maneuver-arrow convention. */
private fun com.openmaps.app.core.routing.ManeuverType.stepArrowRotationDegrees(): Float {
    val pointing = when (this) {
        com.openmaps.app.core.routing.ManeuverType.SlightLeft -> -45
        com.openmaps.app.core.routing.ManeuverType.TurnLeft -> -90
        com.openmaps.app.core.routing.ManeuverType.SharpLeft -> -135
        com.openmaps.app.core.routing.ManeuverType.UTurn -> 180
        com.openmaps.app.core.routing.ManeuverType.SlightRight -> 45
        com.openmaps.app.core.routing.ManeuverType.TurnRight -> 90
        com.openmaps.app.core.routing.ManeuverType.SharpRight -> 135
        com.openmaps.app.core.routing.ManeuverType.Roundabout -> 90
        com.openmaps.app.core.routing.ManeuverType.Ramp -> 45
        else -> 0
    }
    // The glyph points east at 0deg, so straight ahead is -90deg.
    return pointing - 90f
}

/** Screen-reader text for a step; an arrow glyph alone carries no meaning. */
private fun com.openmaps.app.core.routing.ManeuverType.stepSpokenLabel(): String = when (this) {
    com.openmaps.app.core.routing.ManeuverType.Start -> "Starting route"
    com.openmaps.app.core.routing.ManeuverType.Continue -> "Continue"
    com.openmaps.app.core.routing.ManeuverType.SlightLeft -> "Bear left"
    com.openmaps.app.core.routing.ManeuverType.SlightRight -> "Bear right"
    com.openmaps.app.core.routing.ManeuverType.TurnLeft -> "Turn left"
    com.openmaps.app.core.routing.ManeuverType.TurnRight -> "Turn right"
    com.openmaps.app.core.routing.ManeuverType.SharpLeft -> "Sharp left"
    com.openmaps.app.core.routing.ManeuverType.SharpRight -> "Sharp right"
    com.openmaps.app.core.routing.ManeuverType.UTurn -> "Make a U-turn"
    com.openmaps.app.core.routing.ManeuverType.Merge -> "Merge"
    com.openmaps.app.core.routing.ManeuverType.Ramp -> "Take the ramp"
    com.openmaps.app.core.routing.ManeuverType.Roundabout -> "Enter the roundabout"
    com.openmaps.app.core.routing.ManeuverType.Destination -> "Arrive at your destination"
    com.openmaps.app.core.routing.ManeuverType.Unknown -> "Continue"
}

private val MAX_STEPS_SHEET_HEIGHT = 320.dp