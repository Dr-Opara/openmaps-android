package com.openmaps.app.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.GlassShapes
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.surfaceCard
import com.openmaps.app.core.location.UserLocation
import com.openmaps.app.core.localization.LocalMeasurementSystem
import com.openmaps.app.core.localization.NavigationUnits
import com.openmaps.app.core.places.Place
import com.openmaps.app.core.places.PlaceCategory
import com.openmaps.app.core.places.PromotionType
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The Nearby/Explore bottom sheet (Phase 12A, sections B5-B9): opened by tapping "Explore what's
 * around you" ([BottomPanel]). Deliberately reuses the existing category-filter state and the
 * already-ranked/capped [places] list ([MapUiState.visiblePlaces]/[PlaceCategoryFilterRow]) rather
 * than inventing a second filter/list concept — toggling a category here and toggling it on the
 * map chips are the same action (section B4/B7 "map/card sync").
 *
 * Presented as an inline glass card anchored to the bottom, the same non-modal idiom
 * [DestinationDetailsSheet]/[PlaceDetailsSheet] already use, rather than introducing a first
 * `ModalBottomSheet` into this codebase.
 */
@Composable
fun NearbyExploreSheet(
    placesState: PlacesState,
    places: List<Place>,
    selectedCategories: Set<PlaceCategory>,
    onCategoriesChanged: (Set<PlaceCategory>) -> Unit,
    currentLocation: UserLocation?,
    hasRoute: Boolean = false,
    onDetails: (Place) -> Unit,
    onNavigate: (Place) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** Re-runs the fetch after a provider failure — the public Overpass instance is often transient. */
    onRetry: () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .surfaceCard(GlassShapes.Card)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.panel_title),
                style = MaterialTheme.typography.titleMedium,
                color = OpenMapsColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_close_explore),
                    tint = OpenMapsColors.TextSecondary,
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        PlaceCategoryFilterRow(selected = selectedCategories, onSelectedChanged = onCategoriesChanged)
        Spacer(Modifier.height(12.dp))

        when {
            currentLocation == null -> NearbyStatusRow(stringResource(R.string.nearby_waiting_for_location), showSpinner = true)
            placesState is PlacesState.Loading -> NearbyStatusRow(
                text = loadingCopy(placesState.categories),
                showSpinner = true,
            )
            placesState is PlacesState.Error -> NearbyErrorState(onRetry = onRetry)
            places.isEmpty() -> NearbyStatusRow(
                text = if (selectedCategories.size == 1) {
                    stringResource(R.string.nearby_empty_category_format, selectedCategories.first().displayName())
                } else {
                    stringResource(R.string.nearby_empty_generic)
                },
                showSpinner = false,
            )
            else -> {
                // Provenance is stated when results are older than the fresh window. Never present a
                // stale list as if it were live.
                if (placesState is PlacesState.Ready && placesState.stale) {
                    NearbyStatusRow(
                        text = stringResource(R.string.nearby_showing_cached),
                        showSpinner = false,
                    )
                }

                Text(
                    text = stringResource(R.string.nearby_near_you_now),
                    style = MaterialTheme.typography.labelLarge,
                    color = OpenMapsColors.TextPrimary,
                )
                Spacer(Modifier.height(8.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(places.take(8), key = { it.id }) { place ->
                        NearbyPlaceCard(place = place, onDetails = { onDetails(place) }, onNavigate = { onNavigate(place) })
                    }
                }

                val ahead = currentLocation
                    ?.bearingDegrees
                    ?.let { bearing -> placesAhead(currentLocation, bearing, places) }
                    .orEmpty()
                if (ahead.isNotEmpty()) {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        text = stringResource(R.string.nearby_whats_ahead),
                        style = MaterialTheme.typography.labelLarge,
                        color = OpenMapsColors.TextPrimary,
                    )
                    Text(
                        text = stringResource(R.string.nearby_whats_ahead_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = OpenMapsColors.TextSecondary,
                        modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        items(ahead, key = { it.id }) { place ->
                            AheadPlaceChip(place = place, onClick = { onDetails(place) })
                        }
                    }
                }

                if (hasRoute) {
                    val suggestedStops = places.filter { it.category in SUGGESTED_STOP_CATEGORIES }.take(4)
                    if (suggestedStops.isNotEmpty()) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            text = stringResource(R.string.nearby_suggested_stops),
                            style = MaterialTheme.typography.labelLarge,
                            color = OpenMapsColors.TextPrimary,
                        )
                        Text(
                            text = stringResource(R.string.nearby_suggested_stops_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = OpenMapsColors.TextSecondary,
                            modifier = Modifier.padding(top = 2.dp, bottom = 8.dp),
                        )
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            items(suggestedStops, key = { "stop:" + it.id }) { place ->
                                AheadPlaceChip(place = place, onClick = { onDetails(place) })
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * "Finding nearby restaurants…" — names what is actually being searched for.
 *
 * A single selected category is named in the singular; a broad or multi-category fetch falls back to
 * the generic wording rather than producing an awkward list.
 */
@Composable
private fun loadingCopy(categories: Set<PlaceCategory>): String {
    val single = categories.singleOrNull()
    return if (single != null) {
        stringResource(R.string.nearby_finding_category, single.displayName().lowercase())
    } else {
        stringResource(R.string.nearby_finding)
    }
}

/**
 * The provider-failure state.
 *
 * Distinct from the empty state on purpose: "the provider did not answer" and "there is nothing
 * here" are different facts. A retry is offered because the failure is usually transient.
 */
@Composable
private fun NearbyErrorState(onRetry: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_warning),
            contentDescription = null,
            tint = OpenMapsColors.Warning,
            modifier = Modifier.size(22.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.nearby_error),
            style = MaterialTheme.typography.bodyMedium,
            color = OpenMapsColors.TextSecondary,
        )
        Spacer(Modifier.height(8.dp))
        TextButton(onClick = onRetry) {
            Text(
                text = stringResource(R.string.action_retry),
                style = MaterialTheme.typography.labelLarge,
                color = OpenMapsColors.ElectricBlueFilled,
            )
        }
    }
}

@Composable
private fun NearbyStatusRow(text: String, showSpinner: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (showSpinner) {
            CircularProgressIndicator(modifier = Modifier.height(16.dp).width(16.dp), strokeWidth = 2.dp, color = OpenMapsColors.ElectricBlue)
            Spacer(Modifier.width(10.dp))
        }
        Text(text = text, style = MaterialTheme.typography.bodyMedium, color = OpenMapsColors.TextSecondary)
    }
}

@Composable
private fun NearbyPlaceCard(place: Place, onDetails: () -> Unit, onNavigate: () -> Unit) {
    Column(
        modifier = Modifier
            .width(300.dp)
            .background(OpenMapsColors.SurfaceMuted, GlassShapes.Card)
            .padding(14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Box(
                Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(place.category.markerColor()),
            )
            Text(
                text = place.category.displayName(),
                style = MaterialTheme.typography.labelSmall,
                color = place.category.markerColor(),
            )
            if (place.promotionType == PromotionType.Sponsored) {
                Text(
                    text = stringResource(R.string.nearby_sponsored),
                    style = MaterialTheme.typography.labelSmall,
                    color = OpenMapsColors.Warning,
                )
            }
        }
        Text(
            text = nearbyDisplayName(place),
            style = MaterialTheme.typography.titleMedium,
            color = OpenMapsColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 2.dp),
        )
        nearbyCardSummary(place)?.let { summary ->
            Text(
                text = summary,
                style = MaterialTheme.typography.bodySmall,
                color = OpenMapsColors.TextSecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        place.openNow?.let { open ->
            Text(
                text = stringResource(if (open) R.string.place_open_now else R.string.place_closed_now),
                style = MaterialTheme.typography.labelSmall,
                color = if (open) OpenMapsColors.ElectricBlue else OpenMapsColors.Warning,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = onDetails, modifier = Modifier.weight(1f).heightIn(min = 40.dp)) {
                Text(
                    stringResource(R.string.nearby_details_action),
                    style = MaterialTheme.typography.labelSmall,
                    maxLines = 1,
                )
            }
            Button(
                onClick = onNavigate,
                colors = ButtonDefaults.buttonColors(containerColor = OpenMapsColors.ElectricBlueFilled, contentColor = Color.White),
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
            ) {
                Text(
                    stringResource(R.string.place_navigate_action),
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun nearbyDisplayName(place: Place): String {
    val raw = place.name.trim()
    val generic = place.category.displayName()
    if (raw.isNotBlank() && !raw.equals(generic, ignoreCase = true)) return raw
    return when (place.category) {
        PlaceCategory.Parking -> stringResource(R.string.nearby_public_parking)
        PlaceCategory.Gas -> stringResource(R.string.nearby_gas_station)
        PlaceCategory.EvCharging -> stringResource(R.string.category_ev_charging)
        PlaceCategory.Atm -> stringResource(R.string.category_atm)
        else -> stringResource(R.string.nearby_generic_name, generic)
    }
}

/** "0.3 mi away" plus address, when available — never a route distance (section B8). */
@Composable
private fun nearbyCardSummary(place: Place): String? {
    val distance = place.distanceMeters?.let {
        stringResource(R.string.distance_away_format, formatNearbyDistance(it))
    }
    val address = place.address
    return listOfNotNull(distance, address).joinToString(" · ").ifBlank { null }
}

@Composable
private fun AheadPlaceChip(place: Place, onClick: () -> Unit) {
    val distance = if (place.distanceMeters != null) {
        formatNearbyDistance(place.distanceMeters)
    } else {
        null
    }
    Row(
        modifier = Modifier
            .background(OpenMapsColors.SurfaceMuted, GlassShapes.Pill)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Box(
            Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(place.category.markerColor()),
        )
        Text(
            text = nearbyDisplayName(place),
            style = MaterialTheme.typography.labelMedium,
            color = OpenMapsColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (distance != null) {
            Text(
                text = distance,
                style = MaterialTheme.typography.labelSmall,
                color = OpenMapsColors.TextSecondary,
            )
        }
    }
}

/**
 * "What's Ahead" is shown only when the location provider has a real movement bearing.
 * No compass/bearing means no directional claim is made.
 */
private fun placesAhead(
    location: UserLocation,
    movementBearingDegrees: Float,
    places: List<Place>,
): List<Place> =
    places
        .asSequence()
        .filter { it.distanceMeters != null }
        .map { place ->
            place to angularDifferenceDegrees(
                movementBearingDegrees.toDouble(),
                initialBearingDegrees(
                    location.latitude,
                    location.longitude,
                    place.latitude,
                    place.longitude,
                ),
            )
        }
        .filter { (_, difference) -> difference <= AHEAD_HALF_ANGLE_DEGREES }
        .sortedBy { (place, _) -> place.distanceMeters }
        .map { it.first }
        .take(MAX_AHEAD_PLACES)
        .toList()

private fun initialBearingDegrees(
    fromLatitude: Double,
    fromLongitude: Double,
    toLatitude: Double,
    toLongitude: Double,
): Double {
    val fromLat = fromLatitude * PI / 180.0
    val toLat = toLatitude * PI / 180.0
    val deltaLon = (toLongitude - fromLongitude) * PI / 180.0
    val y = sin(deltaLon) * cos(toLat)
    val x = cos(fromLat) * sin(toLat) - sin(fromLat) * cos(toLat) * cos(deltaLon)
    return ((atan2(y, x) * 180.0 / PI) + 360.0) % 360.0
}

private fun angularDifferenceDegrees(a: Double, b: Double): Double {
    val raw = abs(a - b) % 360.0
    return if (raw > 180.0) 360.0 - raw else raw
}

@Composable
private fun formatNearbyDistance(meters: Double): String =
    NavigationUnits.formatDistance(meters, LocalMeasurementSystem.current)
private const val AHEAD_HALF_ANGLE_DEGREES = 55.0
private const val MAX_AHEAD_PLACES = 4
private val SUGGESTED_STOP_CATEGORIES = setOf(
    PlaceCategory.Gas,
    PlaceCategory.EvCharging,
    PlaceCategory.Restaurant,
    PlaceCategory.Coffee,
    PlaceCategory.Parking,
    PlaceCategory.Pharmacy,
)
