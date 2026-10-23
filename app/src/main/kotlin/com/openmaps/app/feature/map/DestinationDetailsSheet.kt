package com.openmaps.app.feature.map

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
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
import com.openmaps.app.core.geocoding.LicensedDestinationPhoto
import com.openmaps.app.core.geocoding.WikimediaDestinationPhotos
import com.openmaps.app.core.location.UserLocation
import com.openmaps.app.core.localization.LocalAppLocale
import com.openmaps.app.core.localization.LocalMeasurementSystem
import com.openmaps.app.core.localization.NavigationUnits
import com.openmaps.app.core.places.Place
import com.openmaps.app.core.places.PlaceCategory
import com.openmaps.app.core.places.PlaceRepository
import com.openmaps.app.core.places.RouteCorridor
import com.openmaps.app.core.places.google.GooglePlaceDetailsCard
import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.ManeuverType
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
    routeState: RouteState = RouteState.Idle,
    nearbyPlaces: List<Place> = emptyList(),
    placesRepository: PlaceRepository,
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit,
    onSelectNearby: (Place) -> Unit,
    onDirections: () -> Unit,
    onStart: () -> Unit,
    onShare: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var tab by rememberSaveable(destination.id) { mutableStateOf(DestinationDetailsTab.Overview) }
    var photo by androidx.compose.runtime.remember(destination.id) { mutableStateOf<LicensedDestinationPhoto?>(null) }
    LaunchedEffect(destination.id) {
        photo = runCatching { WikimediaDestinationPhotos.forPlace(destination.name.ifBlank { destination.displayName }) }.getOrNull()
    }
    val context = LocalContext.current
    var destinationNearby by androidx.compose.runtime.remember(destination.id) { mutableStateOf<List<Place>>(emptyList()) }
    var nearbyStatus by androidx.compose.runtime.remember(destination.id) { mutableStateOf("loading") }
    // Search around the DESTINATION, not the user's device GPS. Refetch per destination.
    LaunchedEffect(destination.id, destination.latitude, destination.longitude, placesRepository) {
        nearbyStatus = "loading"
        val result = placesRepository.nearbyPlaces(
            Coordinate(destination.latitude, destination.longitude),
            3_000.0,
            PlaceCategory.entries.toSet(),
        )
        result.fold(
            onSuccess = {
                destinationNearby = it.places.filter { place ->
                    place.id != destination.id && place.name != destination.name
                }
                nearbyStatus = "ready"
            },
            onFailure = { nearbyStatus = "error" },
        )
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .surfaceCard(GlassShapes.Card),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(118.dp)
                .background(OpenMapsColors.SurfaceMuted),
        ) {
            if (photo != null) {
                Image(
                    bitmap = photo!!.bitmap.asImageBitmap(),
                    contentDescription = "Photo of ${destination.name}",
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.matchParentSize(),
                )
            } else {
                Icon(
                    Icons.Filled.Place,
                    contentDescription = null,
                    tint = OpenMapsColors.ElectricBlueFilled.copy(alpha = 0.32f),
                    modifier = Modifier.size(54.dp).align(Alignment.Center),
                )
            }
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
                style = MaterialTheme.typography.headlineSmall,
                color = OpenMapsColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            photo?.let { licensed ->
                Text(
                    "Photo: ${licensed.attribution} · ${licensed.license} · Wikimedia Commons",
                    modifier = Modifier.padding(top = 6.dp).clickable {
                        runCatching {
                            context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(licensed.commonsPage)))
                        }
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = OpenMapsColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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

            val readyRoute = (routeState as? RouteState.Ready)?.route
            if (readyRoute != null) {
                Spacer(Modifier.height(12.dp))
                DestinationTripSummary(
                    durationSeconds = readyRoute.durationSeconds,
                    distanceMeters = readyRoute.distanceMeters,
                )

                val parkingNearby = parkingNearDestination(destination, nearbyPlaces)
                Spacer(Modifier.height(10.dp))
                val routeComplexity = arrivalComplexityLabel(readyRoute.maneuvers.map { it.type })
                if (routeComplexity != null || parkingNearby.isNotEmpty()) {
                    ArrivalIntelligenceCard(
                        routeComplexity = routeComplexity,
                        parkingNearby = parkingNearby,
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
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
                    Spacer(Modifier.width(7.dp))
                    Text(
                        stringResource(R.string.destination_directions_action),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
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
                    Icon(Icons.Filled.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(7.dp))
                    Text(
                        stringResource(R.string.nav_start),
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                OutlinedButton(
                    onClick = onToggleFavorite,
                    shape = GlassShapes.Pill,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                ) {
                    Icon(painterResource(R.drawable.ic_bookmark), contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (isFavorite) "Saved" else stringResource(R.string.place_save_action),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
                OutlinedButton(
                    onClick = onShare,
                    shape = GlassShapes.Pill,
                    modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                ) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(17.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.place_share_action),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 1,
                    )
                }
            }

            Spacer(Modifier.height(10.dp))
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
                DestinationDetailsTab.Overview -> {
                    Text(
                        text = destination.subtitle,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OpenMapsColors.TextPrimary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                    )
                    val parkingNearby = parkingNearDestination(destination, nearbyPlaces)
                    if (parkingNearby.isNotEmpty()) {
                        Spacer(Modifier.height(12.dp))
                        Text(
                            text = stringResource(R.string.destination_parking_nearby),
                            style = MaterialTheme.typography.labelLarge,
                            color = OpenMapsColors.TextPrimary,
                        )
                        parkingNearby.take(3).forEach { place ->
                            Text(
                                text = parkingLine(place),
                                style = MaterialTheme.typography.bodySmall,
                                color = OpenMapsColors.TextSecondary,
                                modifier = Modifier.padding(top = 4.dp),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    // Phase 1, Issue #78: additional business details sourced from Google's Places
                    // UI Kit. Entirely optional — renders nothing when GOOGLE_PLACES_UI_KIT_CONFIGURED
                    // is false, so destinations keep today's Overview unchanged until a key exists.
                    Spacer(Modifier.height(12.dp))
                    GooglePlaceDetailsCard(
                        latitude = destination.latitude,
                        longitude = destination.longitude,
                    )
                }
                DestinationDetailsTab.Reviews -> Text(
                    stringResource(R.string.place_reviews_unavailable),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                )
                DestinationDetailsTab.Photos -> {
                    if (photo == null) {
                        Text("No verified, licensed photo available for this destination yet.", color = OpenMapsColors.TextSecondary)
                    } else {
                        Image(
                            bitmap = photo!!.bitmap.asImageBitmap(),
                            contentDescription = "Destination photo",
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxWidth().height(170.dp),
                        )
                        Text(
                            "View photo license and attribution on Wikimedia Commons",
                            modifier = Modifier.clickable {
                                runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(photo!!.commonsPage))) }
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = OpenMapsColors.ElectricBlueFilled,
                        )
                    }
                }
                DestinationDetailsTab.Nearby -> {
                    when (nearbyStatus) {
                        "loading" -> Text("Finding places near this destination…", color = OpenMapsColors.TextSecondary)
                        "error" -> Text("Nearby places are temporarily unavailable.", color = OpenMapsColors.TextSecondary)
                        else -> if (destinationNearby.isEmpty()) {
                            Text("No nearby places returned for this destination.", color = OpenMapsColors.TextSecondary)
                        } else {
                            destinationNearby.take(8).forEach { place ->
                                TextButton(onClick = { onSelectNearby(place) }, modifier = Modifier.fillMaxWidth()) {
                                    Column(Modifier.fillMaxWidth()) {
                                        Text(place.name, color = OpenMapsColors.TextPrimary, maxLines = 1)
                                        Text(
                                            place.category.name + (place.distanceMeters?.let { " · %.1f mi".format(Locale.US, it / 1609.344) } ?: ""),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = OpenMapsColors.TextSecondary,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DestinationTripSummary(durationSeconds: Double, distanceMeters: Double) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(OpenMapsColors.SurfaceMuted, GlassShapes.Card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = formatTripDuration(durationSeconds),
                style = MaterialTheme.typography.titleLarge,
                color = OpenMapsColors.TextPrimary,
            )
            Text(
                text = stringResource(R.string.destination_trip_time),
                style = MaterialTheme.typography.labelSmall,
                color = OpenMapsColors.TextSecondary,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = formatTripDistance(distanceMeters),
                style = MaterialTheme.typography.titleMedium,
                color = OpenMapsColors.TextPrimary,
            )
            Text(
                text = stringResource(R.string.destination_trip_distance),
                style = MaterialTheme.typography.labelSmall,
                color = OpenMapsColors.TextSecondary,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = ArrivalTimeFormatter.format(
                    durationSeconds,
                    System.currentTimeMillis(),
                    locale = LocalAppLocale.current,
                ),
                style = MaterialTheme.typography.titleMedium,
                color = OpenMapsColors.TextPrimary,
            )
            Text(
                text = stringResource(R.string.destination_trip_arrival),
                style = MaterialTheme.typography.labelSmall,
                color = OpenMapsColors.TextSecondary,
            )
        }
    }
}

@Composable
private fun ArrivalIntelligenceCard(
    routeComplexity: String?,
    parkingNearby: List<Place>,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(OpenMapsColors.SurfaceMuted, GlassShapes.Card)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(
            text = stringResource(R.string.destination_arrival_ease),
            style = MaterialTheme.typography.labelLarge,
            color = OpenMapsColors.TextPrimary,
        )
        routeComplexity?.let { complexity ->
            Text(
                text = complexity,
                style = MaterialTheme.typography.titleMedium,
                color = OpenMapsColors.ElectricBlueFilled,
                modifier = Modifier.padding(top = 2.dp),
            )
            Text(
                text = stringResource(R.string.destination_arrival_ease_basis),
                style = MaterialTheme.typography.bodySmall,
                color = OpenMapsColors.TextSecondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        if (parkingNearby.isNotEmpty()) {
            Text(
                text = stringResource(R.string.destination_parking_found, parkingNearby.size.coerceAtMost(3)),
                style = MaterialTheme.typography.bodySmall,
                color = OpenMapsColors.TextPrimary,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

@Composable
private fun arrivalComplexityLabel(types: List<ManeuverType>): String? {
    if (types.isEmpty()) return null
    val final = types.takeLast(6)
    val complex = final.count {
        it in setOf(
            ManeuverType.SharpLeft,
            ManeuverType.SharpRight,
            ManeuverType.UTurn,
            ManeuverType.Roundabout,
            ManeuverType.Ramp,
        )
    }
    val turns = final.count {
        it in setOf(
            ManeuverType.TurnLeft,
            ManeuverType.TurnRight,
            ManeuverType.SlightLeft,
            ManeuverType.SlightRight,
        )
    }
    return when {
        complex >= 2 -> stringResource(R.string.destination_approach_complex)
        complex == 1 || turns >= 3 -> stringResource(R.string.destination_approach_moderate)
        else -> stringResource(R.string.destination_approach_simple)
    }
}

private fun parkingNearDestination(destination: SearchResult, places: List<Place>): List<Place> =
    places
        .asSequence()
        .filter { it.category == PlaceCategory.Parking }
        .map { place ->
            val meters = RouteCorridor.metersBetween(
                Coordinate(destination.latitude, destination.longitude),
                Coordinate(place.latitude, place.longitude),
            )
            place to meters
        }
        .filter { (_, meters) -> meters <= DESTINATION_PARKING_RADIUS_METERS }
        .sortedBy { (_, meters) -> meters }
        .map { it.first }
        .take(3)
        .toList()

@Composable
private fun parkingLine(place: Place): String {
    val parts = mutableListOf<String>()
    parts += place.name.ifBlank { stringResource(R.string.parking_default_name) }
    place.metadata.parkingType
        ?.replace('_', ' ')
        ?.replaceFirstChar { it.uppercase() }
        ?.let(parts::add)
    place.metadata.isPublicAccess?.let {
        parts += stringResource(if (it) R.string.parking_public else R.string.parking_private)
    }
    place.metadata.feeApplies?.let {
        parts += stringResource(if (it) R.string.parking_fee else R.string.parking_no_fee)
    }
    return parts.joinToString(" · ")
}

@Composable
private fun formatTripDistance(meters: Double): String =
    NavigationUnits.formatDistance(meters, LocalMeasurementSystem.current, LocalAppLocale.current)

@Composable
private fun formatTripDuration(seconds: Double): String =
    NavigationUnits.formatDuration(seconds, LocalAppLocale.current)

@Composable
private fun approximateDistanceLabel(destination: SearchResult, currentLocation: UserLocation?): String? {
    val location = currentLocation ?: return null
    val meters = RouteCorridor.metersBetween(
        Coordinate(location.latitude, location.longitude),
        Coordinate(destination.latitude, destination.longitude),
    )
    val formatted = NavigationUnits.formatDistance(meters, LocalMeasurementSystem.current, LocalAppLocale.current)
    return stringResource(R.string.distance_away_format, formatted)
}

private const val DESTINATION_PARKING_RADIUS_METERS = 800.0
