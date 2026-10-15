package com.openmaps.app.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
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

private enum class DestinationDetailsTab { Overview, Reviews, Photos, Nearby }

/**
 * Full mockup-aligned Destination Details shell for geocoded search results.
 * Unsupported rich fields remain honest rather than fabricated.
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
    var tab by rememberSaveable(destination.id) { mutableStateOf(DestinationDetailsTab.Overview) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .surfaceCard(GlassShapes.Card),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .background(OpenMapsColors.SurfaceMuted),
        ) {
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                tint = OpenMapsColors.ElectricBlueFilled.copy(alpha = 0.32f),
                modifier = Modifier.size(54.dp).align(Alignment.Center),
            )
            IconButton(
                onClick = onDismiss,
                modifier = Modifier.align(Alignment.TopStart).padding(6.dp)
                    .clip(CircleShape).background(Color.White.copy(alpha = 0.90f)),
            ) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
            }
            Box(
                modifier = Modifier.align(Alignment.TopEnd).padding(12.dp)
                    .size(36.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.90f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.MoreVert, contentDescription = null, tint = OpenMapsColors.TextSecondary)
            }
        }

        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = destination.name.ifBlank { destination.displayName },
                style = MaterialTheme.typography.titleLarge,
                color = OpenMapsColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            destination.category?.takeIf { it.isNotBlank() }?.let {
                Text(
                    text = it.replace('_', ' '),
                    style = MaterialTheme.typography.labelMedium,
                    color = OpenMapsColors.TextSecondary,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }

            approximateDistanceLabel(destination, currentLocation)?.let { label ->
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = OpenMapsColors.ElectricBlueFilled,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
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
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.destination_directions_action))
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
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(5.dp))
                    Text(stringResource(R.string.nav_start))
                }
                OutlinedButton(
                    onClick = {},
                    enabled = false,
                    shape = GlassShapes.Pill,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_bookmark), contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(R.string.place_save_action))
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                DestinationDetailsTab.entries.forEach { item ->
                    val selected = item == tab
                    Column(
                        modifier = Modifier.clickable { tab = item }.padding(horizontal = 4.dp, vertical = 6.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            text = when (item) {
                                DestinationDetailsTab.Overview -> stringResource(R.string.place_tab_overview)
                                DestinationDetailsTab.Reviews -> stringResource(R.string.place_tab_reviews)
                                DestinationDetailsTab.Photos -> stringResource(R.string.place_tab_photos)
                                DestinationDetailsTab.Nearby -> stringResource(R.string.place_tab_nearby)
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = if (selected) OpenMapsColors.ElectricBlueFilled else OpenMapsColors.TextSecondary,
                        )
                        Spacer(Modifier.height(4.dp))
                        Box(
                            Modifier.height(2.dp).width(if (selected) 28.dp else 0.dp)
                                .background(OpenMapsColors.ElectricBlueFilled),
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            when (tab) {
                DestinationDetailsTab.Overview -> Text(
                    text = destination.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                DestinationDetailsTab.Reviews -> Text(
                    stringResource(R.string.place_reviews_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                )
                DestinationDetailsTab.Photos -> Text(
                    stringResource(R.string.place_photos_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                )
                DestinationDetailsTab.Nearby -> Text(
                    stringResource(R.string.place_nearby_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                )
            }
        }
    }
}

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
