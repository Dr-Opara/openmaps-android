package com.openmaps.app.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.GlassShapes
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.glass
import com.openmaps.app.core.places.Place
import com.openmaps.app.core.places.PlaceCategory
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Lightweight category toggles (Phase 9 "User controls"). One chip per [PlaceCategory] rather
 * than a grouped taxonomy ("Food", "Places", ...) not to overbuild a layers panel — toggling a
 * chip is purely a client-side display filter (see `MapViewModel.onCategoryFilterChanged`); it
 * never triggers a new network request.
 */
@Composable
fun PlaceCategoryFilterRow(
    selected: Set<PlaceCategory>,
    onSelectedChanged: (Set<PlaceCategory>) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PlaceCategory.entries.forEach { category ->
            val isSelected = category in selected
            Row(
                modifier = Modifier
                    .glass(GlassShapes.Pill, alpha = if (isSelected) 0.9f else 0.55f)
                    .selectable(
                        selected = isSelected,
                        role = Role.Checkbox,
                        onClick = {
                            onSelectedChanged(if (isSelected) selected - category else selected + category)
                        },
                    )
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(category.markerColor()),
                )
                Text(
                    text = category.displayName(),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isSelected) OpenMapsColors.TextPrimary else OpenMapsColors.TextSecondary,
                )
            }
        }
    }
}

/**
 * Compact place-details presentation (Phase 9 "Place details") — deliberately not a Yelp-style
 * profile page: name, category, distance, address, and whatever category-specific metadata the
 * provider actually supplied (never invented — see `OverpassResponseParser`), plus "Navigate".
 */
@Composable
fun PlaceDetailsSheet(
    place: Place,
    onNavigate: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glass(GlassShapes.Card)
            .padding(horizontal = 20.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(
                        Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(place.category.markerColor()),
                    )
                    Text(
                        text = place.category.displayName(),
                        style = MaterialTheme.typography.labelMedium,
                        color = OpenMapsColors.TextSecondary,
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = place.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = OpenMapsColors.TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                placeSummaryLine(place)?.let { summary ->
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OpenMapsColors.TextSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                placeMetadataLine(place)?.let { metadata ->
                    Text(
                        text = metadata,
                        style = MaterialTheme.typography.labelMedium,
                        color = OpenMapsColors.Cyan,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
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
        Button(onClick = onNavigate, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.place_navigate_action))
        }
    }
}

/** "0.3 mi away" / "150 ft off route" — whichever distance figure the place actually carries. */
private fun placeSummaryLine(place: Place): String? {
    val detour = place.routeDetourMeters
    val straight = place.distanceMeters
    return when {
        detour != null -> "${formatPlaceDistance(detour)} off route"
        straight != null -> "${formatPlaceDistance(straight)} away"
        else -> place.address
    }
}

/** Category-specific metadata, only when the provider actually supplied it — never invented. */
private fun placeMetadataLine(place: Place): String? {
    val metadata = place.metadata
    return when (place.category) {
        PlaceCategory.Parking -> listOfNotNull(
            metadata.parkingType?.replace('_', ' ')?.replaceFirstChar { it.uppercase() },
            metadata.isPublicAccess?.let { if (it) "Public" else "Private" },
            metadata.feeApplies?.let { if (it) "Fee applies" else "Free" },
        ).joinToString(" · ").ifBlank { null }

        PlaceCategory.Gas -> metadata.brand

        PlaceCategory.EvCharging -> listOfNotNull(
            metadata.brand,
            metadata.connectors.takeIf { it.isNotEmpty() }?.joinToString(", "),
        ).joinToString(" · ").ifBlank { null }

        PlaceCategory.Restaurant, PlaceCategory.Coffee, PlaceCategory.Hotel,
        PlaceCategory.Grocery, PlaceCategory.Pharmacy,
        -> null
    }
}

private fun formatPlaceDistance(meters: Double): String {
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

/**
 * Screen-reader/UI label for a category. Hardcoded rather than `stringResource`, matching the
 * established pattern for short derived labels in this codebase (see `ManeuverType.spokenLabel`).
 */
internal fun PlaceCategory.displayName(): String = when (this) {
    PlaceCategory.Restaurant -> "Restaurant"
    PlaceCategory.Coffee -> "Coffee"
    PlaceCategory.Hotel -> "Hotel"
    PlaceCategory.Grocery -> "Grocery"
    PlaceCategory.Pharmacy -> "Pharmacy"
    PlaceCategory.Parking -> "Parking"
    PlaceCategory.Gas -> "Gas"
    PlaceCategory.EvCharging -> "EV charging"
}

/**
 * Marker color per category — kept visually identical to [com.openmaps.app.map.MapLibreMapEngine]'s
 * marker colors (duplicated rather than shared: one side is a Compose `Color`, the other an
 * Android `Color` int for a MapLibre style expression, and this project already keeps each
 * surface's small color/format tables local rather than introducing a shared conversion layer).
 */
internal fun PlaceCategory.markerColor(): Color = when (this) {
    PlaceCategory.Restaurant -> Color(0xFFFF8A3D)
    PlaceCategory.Coffee -> Color(0xFFA9713A)
    PlaceCategory.Hotel -> Color(0xFF9B6BFF)
    PlaceCategory.Grocery -> Color(0xFF3DBA6B)
    PlaceCategory.Pharmacy -> Color(0xFFFF5C7A)
    PlaceCategory.Parking -> Color(0xFF5C7A99)
    PlaceCategory.Gas -> Color(0xFFE8C340)
    PlaceCategory.EvCharging -> Color(0xFF3DD6C6)
}
