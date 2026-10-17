package com.openmaps.app.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Star
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.GlassShapes
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.glass
import com.openmaps.app.core.designsystem.surfaceCard
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
    /**
     * Which categories this row offers.
     *
     * The home screen passes [HOME_CHIP_CATEGORIES] (the mockup's four) so the visible strip stays
     * compact; the Explore sheet passes the full taxonomy. Both drive the same
     * `placeCategoryFilters` state, so narrowing the *visible* set never narrows what is filtered —
     * a category simply has no chip in that particular row.
     */
    categories: List<PlaceCategory> = PlaceCategory.entries,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        categories.forEach { category ->
            val isSelected = category in selected
            Row(
                // Mockup: dark pill chips with white type, compact. Always dark regardless of
                // selection so the row reads as one uniform control strip; the colored dot carries
                // the category and the chip's own tint shows which are on.
                modifier = Modifier
                    .glass(GlassShapes.Pill, alpha = if (isSelected) 0.92f else 0.45f)
                    .selectable(
                        selected = isSelected,
                        role = Role.Checkbox,
                        onClick = {
                            onSelectedChanged(if (isSelected) selected - category else selected + category)
                        },
                    )
                    .padding(horizontal = 12.dp, vertical = 7.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier
                        .size(16.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(category.iconRes()),
                        contentDescription = null,
                        tint = category.markerColor(),
                    )
                }
                Text(
                    text = category.chipLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = OpenMapsColors.TextOnDark,
                )
            }
        }
    }
}

/**
 * The category tiles inside the "Explore Nearby Places" panel (mockup: Food / Gas / Hotels /
 * Parking).
 *
 * A deliberately small, fixed set — the home screen must stay visually clean, so the full category
 * taxonomy lives in the Explore sheet rather than being dumped into the discovery panel. Tapping a
 * tile opens Explore with that category selected, reusing the existing filter state rather than
 * starting a second filter concept.
 */
@Composable
fun ExploreCategoryTiles(
    onCategoryClicked: (PlaceCategory) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        EXPLORE_TILE_CATEGORIES.forEach { category ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    // Mockup: dark navy tiles with white labels, matching the chip row above — not
                    // white cards.
                    .glass(GlassShapes.Row)
                    .clickable(role = Role.Button) { onCategoryClicked(category) }
                    .padding(vertical = 12.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    Modifier.size(26.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(category.iconRes()),
                        contentDescription = null,
                        tint = category.markerColor(),
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    text = category.tileLabel(),
                    style = MaterialTheme.typography.labelMedium,
                    color = OpenMapsColors.TextOnDark,
                    maxLines = 1,
                )
            }
        }
    }
}

/**
 * The mockup's four discovery tiles. `Restaurant` renders as "Food" to match the mockup's tile
 * label; the underlying category is unchanged, so tapping it still filters restaurants.
 */
private val EXPLORE_TILE_CATEGORIES = listOf(
    PlaceCategory.Restaurant,
    PlaceCategory.Gas,
    PlaceCategory.Hotel,
    PlaceCategory.Parking,
)

/**
 * The home screen's visible chip strip — the mockup's Restaurants / Gas / Hotels / Parking, and
 * nothing more.
 *
 * Deliberately four. The full taxonomy still works and is still reachable through the Explore
 * sheet; showing fourteen chips under the search bar would bury the search field and contradict the
 * brief's "keep the home mockup visually clean".
 */
internal val HOME_CHIP_CATEGORIES = listOf(
    PlaceCategory.Restaurant,
    PlaceCategory.Gas,
    PlaceCategory.Hotel,
    PlaceCategory.Parking,
)

/** Tile caption: the mockup's discovery tiles use the same plural wording as the home chip strip. */
internal fun PlaceCategory.tileLabel(): String = when (this) {
    PlaceCategory.Restaurant -> "Food"
    else -> chipLabel()
}

/**
 * Chip caption on the home strip, which the mockup writes in the plural ("Restaurants", "Hotels").
 * The Explore sheet uses the singular [displayName], so the two surfaces read naturally without
 * renaming the category itself.
 */
internal fun PlaceCategory.chipLabel(): String = when (this) {
    PlaceCategory.Restaurant -> "Restaurants"
    PlaceCategory.Hotel -> "Hotels"
    PlaceCategory.EvCharging -> "EV charging"
    else -> displayName()
}

/** The four Place Details tabs (delta-spec section 5). [Nearby] reuses [MapUiState.visiblePlaces]. */
enum class PlaceDetailsTab { Overview, Reviews, Photos, Nearby }

/**
 * Rich-but-honest place-details presentation (delta-spec sections 4-6): a hero area, identity,
 * Directions/Start as the primary actions, and a tab structure (Overview/Reviews/Photos/Nearby)
 * architected so a future provider can fill Reviews/Photos in without rewriting this screen —
 * today neither OSM/Overpass nor any other wired provider supplies either, so both tabs show an
 * honest "not available" state rather than inventing content (section 5's data rule).
 *
 * Deliberately not a Yelp/Google-style profile clone: no fabricated ratings, reviews, photos or
 * hours — every field shown is either present on [Place] or omitted.
 */
@Composable
fun PlaceDetailsSheet(
    place: Place,
    onDirections: () -> Unit,
    onStart: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    /** The same ranked/capped list already driving the map markers (`MapUiState.visiblePlaces`) —
     * reused for the Nearby tab rather than a second fetch/ranking concept. */
    nearbyPlaces: List<Place> = emptyList(),
    onSelectNearbyPlace: (Place) -> Unit = {},
) {
    var tab by rememberSaveable(place.id) { mutableStateOf(PlaceDetailsTab.Overview) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .surfaceCard(GlassShapes.Card),
    ) {
        // Hero image, or a clean category placeholder when no real image exists. Never a fabricated
        // photo: no wired provider (Overpass/OSM) supplies imagery today. Mockup: full-bleed, flush
        // with the card's top corners, not inset.
        PlaceHeroImage(place = place, onDismiss = onDismiss)
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text = place.name,
                style = MaterialTheme.typography.headlineSmall,
                color = OpenMapsColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(4.dp))
            // Mockup: category (+ accessibility icon when known) on its own line, open/closed status
            // on the line below it, rather than one combined row.
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
            place.openNow?.let { open ->
                Text(
                    text = stringResource(if (open) R.string.place_open_now else R.string.place_closed_now),
                    style = MaterialTheme.typography.labelMedium,
                    color = if (open) OpenMapsColors.Success else MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            Spacer(Modifier.height(14.dp))
            // Mockup action row: Directions (solid blue) / Start / Save (both a lighter blue fill,
            // not outlined). Save is rendered but permanently disabled — there is no account system
            // yet, so the visual action is preserved while never pretending to persist anything.
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onDirections,
                    shape = GlassShapes.Pill,
                    modifier = Modifier.weight(1.25f).heightIn(min = 46.dp),
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp).graphicsLayer { rotationZ = -90f },
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.destination_directions_action),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = onStart,
                    shape = GlassShapes.Pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OpenMapsColors.ElectricBlueFilled.copy(alpha = 0.14f),
                        contentColor = OpenMapsColors.ElectricBlueFilled,
                    ),
                    modifier = Modifier.weight(0.95f).heightIn(min = 46.dp),
                ) {
                    Icon(
                        Icons.Filled.PlayArrow,
                        contentDescription = null,
                        tint = OpenMapsColors.ElectricBlueFilled,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.nav_start),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Button(
                    onClick = {},
                    enabled = false,
                    shape = GlassShapes.Pill,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = OpenMapsColors.ElectricBlueFilled.copy(alpha = 0.14f),
                        contentColor = OpenMapsColors.ElectricBlueFilled,
                        disabledContainerColor = OpenMapsColors.ElectricBlueFilled.copy(alpha = 0.14f),
                        disabledContentColor = OpenMapsColors.ElectricBlueFilled,
                    ),
                    modifier = Modifier.weight(0.9f).heightIn(min = 46.dp),
                ) {
                    Icon(Icons.Filled.Star, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(
                        stringResource(R.string.place_save_action),
                        style = MaterialTheme.typography.labelSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            PlaceDetailsTabRow(selected = tab, onSelected = { tab = it })
            Spacer(Modifier.height(10.dp))
            when (tab) {
                PlaceDetailsTab.Overview -> OverviewTabContent(place)
                PlaceDetailsTab.Reviews -> UnavailableTabContent(stringResource(R.string.place_reviews_unavailable))
                PlaceDetailsTab.Photos -> UnavailableTabContent(stringResource(R.string.place_photos_unavailable))
                PlaceDetailsTab.Nearby -> NearbyTabContent(
                    place = place,
                    nearbyPlaces = nearbyPlaces,
                    onSelect = onSelectNearbyPlace,
                )
            }
        }
    }
}

/**
 * The mockup's top hero: a full-bleed image area with a back control over it, falling back to a
 * clean category-tinted placeholder. No wired provider supplies real imagery, so the placeholder is
 * what renders today — deliberately not a stock or generated "photo".
 *
 * The mockup also shows an overflow ("more options") button top-right. It is deliberately not
 * reproduced here: there is no real menu behind it yet (no share/report action is wired), and this
 * codebase's rule is that a control never renders unless it does something real.
 */
@Composable
private fun PlaceHeroImage(place: Place, onDismiss: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(128.dp)
            .clip(RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
            .background(place.category.markerColor().copy(alpha = 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(place.category.iconRes()),
            contentDescription = null,
            tint = place.category.markerColor(),
            modifier = Modifier.size(44.dp),
        )
        IconButton(
            onClick = onDismiss,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(10.dp)
                .clip(CircleShape)
                .background(OpenMapsColors.Navy900.copy(alpha = 0.55f)),
        ) {
            Icon(
                Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_dismiss),
                tint = Color.White,
            )
        }
    }
}

/**
 * The mockup's photo strip under the action row.
 *
 * **Placeholders, never photos.** No wired provider (Overpass/OSM) supplies imagery, so these are
 * tinted category tiles. They preserve the mockup's structure without inventing content — the
 * Photos tab says so outright.
 */
@Composable
private fun PlacePhotoStrip(place: Place) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        repeat(PLACEHOLDER_PHOTO_COUNT) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(56.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(place.category.markerColor().copy(alpha = 0.14f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = painterResource(place.category.iconRes()),
                    contentDescription = null,
                    tint = place.category.markerColor().copy(alpha = 0.55f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private const val PLACEHOLDER_PHOTO_COUNT = 3

@Composable
private fun PlaceDetailsTabRow(selected: PlaceDetailsTab, onSelected: (PlaceDetailsTab) -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().selectableGroup(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            PlaceDetailsTab.entries.forEach { entry ->
                val isSelected = entry == selected
                Column(
                    modifier = Modifier
                        .selectable(selected = isSelected, role = Role.Tab, onClick = { onSelected(entry) })
                        .padding(vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        text = stringResource(entry.labelRes()),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isSelected) OpenMapsColors.ElectricBlueFilled else OpenMapsColors.TextSecondary,
                    )
                    Spacer(Modifier.height(4.dp))
                    // Mockup: a short blue underline beneath only the selected tab.
                    Box(
                        Modifier
                            .height(2.dp)
                            .width(if (isSelected) 24.dp else 0.dp)
                            .background(OpenMapsColors.ElectricBlueFilled),
                    )
                }
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(OpenMapsColors.SurfaceBorder))
    }
}

private fun PlaceDetailsTab.labelRes(): Int = when (this) {
    PlaceDetailsTab.Overview -> R.string.place_tab_overview
    PlaceDetailsTab.Reviews -> R.string.place_tab_reviews
    PlaceDetailsTab.Photos -> R.string.place_tab_photos
    PlaceDetailsTab.Nearby -> R.string.place_tab_nearby
}

@Composable
private fun OverviewTabContent(place: Place) {
    Column {
        // Open/closed already renders above the action row (always visible, not tab-gated) — not
        // repeated here. Distance/detour only: the address itself gets its own dedicated row below,
        // so placeSummaryLine's address fallback is skipped here to avoid showing it twice.
        if (place.distanceMeters != null || place.routeDetourMeters != null) {
            placeSummaryLine(place)?.let { summary ->
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
        // Mockup: a dedicated pin + address row at the bottom of Overview. Only when the provider
        // actually supplied one — never a fabricated address.
        place.address?.takeIf { it.isNotBlank() }?.let { address ->
            Row(
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 10.dp),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_place),
                    contentDescription = null,
                    tint = OpenMapsColors.ElectricBlueFilled,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = address,
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextPrimary,
                )
            }
        }
    }
}

/** Honest "not available" state — never a fabricated review/photo (section 5's data rule). */
@Composable
private fun UnavailableTabContent(message: String) {
    Text(
        text = message,
        style = MaterialTheme.typography.bodyMedium,
        color = OpenMapsColors.TextSecondary,
        modifier = Modifier.padding(vertical = 12.dp),
    )
}

@Composable
private fun NearbyTabContent(place: Place, nearbyPlaces: List<Place>, onSelect: (Place) -> Unit) {
    val others = nearbyPlaces.filter { it.id != place.id }.take(MAX_NEARBY_TAB_RESULTS)
    if (others.isEmpty()) {
        UnavailableTabContent(stringResource(R.string.place_nearby_unavailable))
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        others.forEach { nearby ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(role = Role.Button) { onSelect(nearby) }
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(nearby.category.markerColor()),
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        text = nearby.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = OpenMapsColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    placeSummaryLine(nearby)?.let { summary ->
                        Text(
                            text = summary,
                            style = MaterialTheme.typography.labelSmall,
                            color = OpenMapsColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_NEARBY_TAB_RESULTS = 8

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
        PlaceCategory.Grocery, PlaceCategory.Pharmacy, PlaceCategory.Bank, PlaceCategory.Atm,
        PlaceCategory.Convenience, PlaceCategory.Medical, PlaceCategory.Entertainment,
        PlaceCategory.Shopping, PlaceCategory.Education,
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
    PlaceCategory.Bank -> "Bank"
    PlaceCategory.Atm -> "ATM"
    PlaceCategory.Convenience -> "Convenience"
    PlaceCategory.Medical -> "Medical"
    PlaceCategory.Entertainment -> "Entertainment"
    PlaceCategory.Shopping -> "Shopping"
    PlaceCategory.Education -> "Education"
}

/**
 * The category's semantic icon.
 *
 * Real vector drawables rather than a coloured dot or a substituted material glyph: the mockup gives
 * every category a recognisable pictogram (fork-and-knife, fuel pump, bed, "P"), and a dot is not
 * one. Every drawable here was authored in this project because `material-icons-core` ships only 49
 * icons and contains none of these concepts.
 */
internal fun PlaceCategory.iconRes(): Int = when (this) {
    PlaceCategory.Restaurant -> R.drawable.ic_restaurant
    PlaceCategory.Coffee -> R.drawable.ic_coffee
    PlaceCategory.Hotel -> R.drawable.ic_hotel
    PlaceCategory.Grocery -> R.drawable.ic_grocery
    PlaceCategory.Pharmacy -> R.drawable.ic_pharmacy
    PlaceCategory.Parking -> R.drawable.ic_parking
    PlaceCategory.Gas -> R.drawable.ic_gas
    PlaceCategory.EvCharging -> R.drawable.ic_ev
    PlaceCategory.Bank -> R.drawable.ic_bank
    PlaceCategory.Atm -> R.drawable.ic_atm
    PlaceCategory.Convenience -> R.drawable.ic_convenience
    PlaceCategory.Medical -> R.drawable.ic_medical
    PlaceCategory.Entertainment -> R.drawable.ic_entertainment
    PlaceCategory.Shopping -> R.drawable.ic_shopping
    PlaceCategory.Education -> R.drawable.ic_place
}

/**
 * Marker color per category — kept visually identical to [com.openmaps.app.map.MapLibreMapEngine]'s
 * marker colors (duplicated rather than shared: one side is a Compose `Color`, the other an
 * Android `Color` int for a MapLibre style expression, and this project already keeps each
 * surface's small color/format tables local rather than introducing a shared conversion layer).
 */
internal fun PlaceCategory.markerColor(): Color = when (this) {
    // Mockup frame 1's discovery tiles (section "SCREEN 1 EXACTNESS": "exact semantic icons"):
    // Food = gold, Gas = red, Hotels = magenta, Parking = vivid blue.
    PlaceCategory.Restaurant -> Color(0xFFFFC107)
    PlaceCategory.Coffee -> Color(0xFFA9713A)
    PlaceCategory.Hotel -> Color(0xFFE91E8C)
    PlaceCategory.Grocery -> Color(0xFF3DBA6B)
    PlaceCategory.Pharmacy -> Color(0xFFFF5C7A)
    PlaceCategory.Parking -> Color(0xFF2F7BFF)
    PlaceCategory.Gas -> Color(0xFFE03131)
    PlaceCategory.EvCharging -> Color(0xFF3DD6C6)
    PlaceCategory.Bank -> Color(0xFF4A7FE8)
    PlaceCategory.Atm -> Color(0xFF6B8FE0)
    PlaceCategory.Convenience -> Color(0xFF8ED16F)
    PlaceCategory.Medical -> Color(0xFFE05C5C)
    PlaceCategory.Entertainment -> Color(0xFFD16FD1)
    PlaceCategory.Shopping -> Color(0xFFE89B3D)
    PlaceCategory.Education -> Color(0xFF6F8FD1)
}
