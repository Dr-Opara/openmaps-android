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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.openmaps.app.core.designsystem.ReferenceManeuverArrow
import com.openmaps.app.core.localization.LocalMeasurementSystem
import com.openmaps.app.core.localization.LocalAppLocale
import com.openmaps.app.core.localization.NavigationUnits
import com.openmaps.app.core.routing.displayInstruction
import kotlin.math.roundToInt
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
    totalDistanceMeters: Double? = null,
    totalDurationSeconds: Double? = null,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(GlassShapes.Card)
            .background(OpenMapsColors.Navy900)
            .padding(horizontal = 18.dp, vertical = 14.dp),
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
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_dismiss),
                    tint = Color.White,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Per-maneuver time is NOT provided by the route model: only show an overall ETA.
        if (totalDistanceMeters != null || totalDurationSeconds != null) {
            Row(
                modifier = Modifier.fillMaxWidth()
                    .background(OpenMapsColors.Navy800, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                totalDistanceMeters?.takeIf { it.isFinite() && it >= 0 }?.let { distance ->
                    Column(Modifier.weight(1f)) {
                        Text("TOTAL DISTANCE", style = MaterialTheme.typography.labelSmall,
                            color = OpenMapsColors.TextOnDarkSecondary)
                        Text(
                            NavigationUnits.formatDistance(
                                distance, LocalMeasurementSystem.current, LocalAppLocale.current,
                            ),
                            style = MaterialTheme.typography.titleMedium, color = Color.White,
                        )
                    }
                }
                totalDurationSeconds?.takeIf { it.isFinite() && it >= 0 }?.let { seconds ->
                    val minutes = (seconds / 60.0).roundToInt()
                    val hours = minutes / 60
                    val rest = minutes % 60
                    val duration = if (hours > 0) {
                        hours.toString() + " hr " + rest.toString() + " min"
                    } else minutes.toString() + " min"
                    Column(Modifier.weight(1f)) {
                        Text("TRIP TIME", style = MaterialTheme.typography.labelSmall,
                            color = OpenMapsColors.TextOnDarkSecondary)
                        Text(duration, style = MaterialTheme.typography.titleMedium, color = Color.White)
                    }
                }
            }
        }

        if (maneuvers.isEmpty()) {
            Text(
                text = stringResource(R.string.nav_steps_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextOnDarkSecondary,
                modifier = Modifier.padding(vertical = 12.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.heightIn(max = 410.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(maneuvers, key = { index, _ -> index }) { index, maneuver ->
                    RouteStepRow(index + 1, maneuver)
                }
            }
        }
    }
}

@Composable
private fun RouteStepRow(stepNumber: Int, maneuver: RouteManeuver) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OpenMapsColors.Navy800, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier.size(58.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(OpenMapsColors.ElectricBlueFilled),
            contentAlignment = Alignment.Center,
        ) {
            // Same real hook/roundabout/U-turn pictogram family as Live guidance.
            ReferenceManeuverArrow(
                type = maneuver.type,
                size = 38.dp,
                color = Color.White,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = "STEP $stepNumber",
                style = MaterialTheme.typography.labelLarge,
                color = OpenMapsColors.TextOnDarkSecondary,
            )
            val instruction = maneuver.displayInstruction()
                .ifBlank { maneuver.streetName.orEmpty() }
                .ifBlank { maneuver.type.stepSpokenLabel() }
            Text(
                text = instruction,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
            maneuver.streetName
                ?.takeIf { it.isNotBlank() && !instruction.contains(it, ignoreCase = true) }
                ?.let { street ->
                    Text(street, style = MaterialTheme.typography.bodyMedium,
                        color = OpenMapsColors.TextOnDarkSecondary, maxLines = 2)
                }
            if (maneuver.distanceMeters.isFinite() && maneuver.distanceMeters >= 0.0) {
                Text(
                    text = NavigationUnits.formatDistance(
                        maneuver.distanceMeters,
                        LocalMeasurementSystem.current,
                        LocalAppLocale.current,
                    ) + " along this segment",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFB9D3FF),
                )
            }
        }
    }
}

/** Provider type labels also serve accessibility; unknown types are not fabricated as turns. */
private fun com.openmaps.app.core.routing.ManeuverType.stepSpokenLabel(): String = when (this) {
    com.openmaps.app.core.routing.ManeuverType.Start -> "Depart"
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
    com.openmaps.app.core.routing.ManeuverType.Roundabout -> "Enter roundabout"
    com.openmaps.app.core.routing.ManeuverType.Destination -> "Arrive at your destination"
    com.openmaps.app.core.routing.ManeuverType.Unknown -> "Continue"
}

private val MAX_STEPS_SHEET_HEIGHT = 410.dp