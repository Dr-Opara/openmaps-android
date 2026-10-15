package com.openmaps.app.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.GlassShapes
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.surfaceCard
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.UserLocation
import com.openmaps.app.core.places.RouteCorridor
import com.openmaps.app.core.routing.Coordinate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Destination Details (Phase 11, section A3; restyled to the mockup): the state between picking a
 * search result and requesting a route. Name, subtitle/address, an approximate straight-line
 * distance when a location fix is known, and the mockup's two-action row.
 *
 * "Start" here does **not** bypass Route Preview: with no route yet it computes one and lands the
 * user in Route Preview, where the real Start lives — so the flow stays Details -> Route Preview ->
 * Start and pressing Start early never skips a step. It is genuinely useful here because it saves the
 * Directions tap when the user has already decided to drive.
 */
@Composable
fun DestinationDetailsSheet(
    destination: SearchResult,
    currentLocation: UserLocation?,
    onDirections: () -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .surfaceCard(GlassShapes.Card)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = destination.name.ifBlank { destination.displayName },
                    style = MaterialTheme.typography.titleLarge,
                    color = OpenMapsColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = destination.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                approximateDistanceLabel(destination, currentLocation)?.let { label ->
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelLarge,
                        color = OpenMapsColors.ElectricBlueFilled,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_dismiss),
                    tint = OpenMapsColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(14.dp))
        // Mockup's primary action row: filled blue "Directions" as the primary action, with Start
        // beside it. Start routes into Route Preview when no route exists yet, so the two are not
        // redundant (see `MapViewModel.onStartFromPlace`).
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(
                onClick = onDirections,
                shape = GlassShapes.Pill,
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp).graphicsLayer { rotationZ = -90f },
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.destination_directions_action),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
            }
            Button(
                onClick = onStart,
                shape = GlassShapes.Pill,
                colors = ButtonDefaults.buttonColors(
                    containerColor = OpenMapsColors.ElectricBlueFilled.copy(alpha = 0.14f),
                    contentColor = OpenMapsColors.ElectricBlueFilled,
                ),
                modifier = Modifier.weight(1f).heightIn(min = 48.dp),
            ) {
                Icon(
                    Icons.Filled.PlayArrow,
                    contentDescription = null,
                    tint = OpenMapsColors.ElectricBlueFilled,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.nav_start),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
    }
}

/**
 * A straight-line "0.8 mi away" — the "approximate distance if available" section A3 calls for.
 * Deliberately the straight-line figure, not a route distance: no route exists yet at this stage
 * (that is exactly what "Directions" is about to compute), so this is never presented as the trip
 * distance, only as rough context for which result was picked.
 */
private fun approximateDistanceLabel(destination: SearchResult, currentLocation: UserLocation?): String? {
    val location = currentLocation ?: return null
    val meters = RouteCorridor.metersBetween(
        Coordinate(location.latitude, location.longitude),
        Coordinate(destination.latitude, destination.longitude),
    )
    val feet = meters * FEET_PER_METER
    val miles = meters / METERS_PER_MILE
    val formatted = when {
        feet < 1000.0 -> "${feet.roundToInt()} ft"
        miles < 10 -> String.format(Locale.US, "%.1f mi", miles)
        else -> "${miles.roundToInt()} mi"
    }
    return "$formatted away"
}

private const val FEET_PER_METER = 3.28084
private const val METERS_PER_MILE = 1609.344
