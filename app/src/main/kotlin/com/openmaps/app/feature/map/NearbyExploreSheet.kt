package com.openmaps.app.feature.map

import androidx.compose.foundation.background
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
import com.openmaps.app.core.places.Place
import com.openmaps.app.core.places.PlaceCategory
import com.openmaps.app.core.places.PromotionType
import java.util.Locale
import kotlin.math.roundToInt

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
    hasCurrentLocation: Boolean,
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
            !hasCurrentLocation -> NearbyStatusRow(stringResource(R.string.nearby_waiting_for_location), showSpinner = true)
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
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(places, key = { it.id }) { place ->
                        NearbyPlaceCard(place = place, onDetails = { onDetails(place) }, onNavigate = { onNavigate(place) })
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
private fun loadingCopy(categories: Set<PlaceCategory>): String {
    val single = categories.singleOrNull()
    return if (single != null) "Finding nearby ${single.displayName().lowercase()}s…"
    else "Finding places nearby…"
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
            .width(220.dp)
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
            text = place.name,
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
                Text(stringResource(R.string.nearby_details_action), style = MaterialTheme.typography.labelMedium)
            }
            Button(
                onClick = onNavigate,
                colors = ButtonDefaults.buttonColors(containerColor = OpenMapsColors.ElectricBlueFilled, contentColor = Color.White),
                modifier = Modifier.weight(1f).heightIn(min = 40.dp),
            ) {
                Text(stringResource(R.string.place_navigate_action), style = MaterialTheme.typography.labelMedium, color = Color.White)
            }
        }
    }
}

/** "0.3 mi away" plus address, when available — never a route distance (section B8). */
private fun nearbyCardSummary(place: Place): String? {
    val distance = place.distanceMeters?.let { "${formatNearbyDistance(it)} away" }
    val address = place.address
    return listOfNotNull(distance, address).joinToString(" · ").ifBlank { null }
}

private fun formatNearbyDistance(meters: Double): String {
    val feet = meters * FEET_PER_METER
    val miles = meters / METERS_PER_MILE
    return when {
        feet < 1000.0 -> "${feet.roundToInt()} ft"
        miles < 10 -> String.format(Locale.US, "%.1f mi", miles)
        else -> "${miles.roundToInt()} mi"
    }
}

private const val FEET_PER_METER = 3.28084
private const val METERS_PER_MILE = 1609.344
