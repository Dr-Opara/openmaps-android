package com.openmaps.app.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.GlassShapes
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.glass
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.UserLocation
import com.openmaps.app.core.places.RouteCorridor
import com.openmaps.app.core.routing.Coordinate
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Destination Details (Phase 11, section A3): the state between picking a search result and
 * requesting a route. Deliberately minimal — name, subtitle/address, an approximate straight-line
 * distance when a location fix is known, and a single "Directions" action — not a cloned Google
 * Maps place page; OpenMaps' design language (the same glass card [BottomPanel]/[PlaceDetailsSheet]
 * already use).
 *
 * "Start" is intentionally **not** offered here: section A5's flow is Details -> Directions ->
 * Route Preview -> Start, and Route Preview (the existing [BottomPanel] + [NavigationControls], once
 * [RouteState] leaves [RouteState.Idle]) is where Start already lives — showing it twice would be the
 * kind of redundant chrome the brief's "do not clone Google Maps visually" warns against.
 */
@Composable
fun DestinationDetailsSheet(
    destination: SearchResult,
    currentLocation: UserLocation?,
    onDirections: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass(GlassShapes.Card)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = destination.name.ifBlank { destination.displayName },
                    style = MaterialTheme.typography.titleMedium,
                    color = OpenMapsColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
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
                        color = OpenMapsColors.ElectricBlue,
                        modifier = Modifier.padding(top = 2.dp),
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
        Spacer(Modifier.height(8.dp))
        Button(onClick = onDirections, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.destination_directions_action))
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
