package com.openmaps.app.feature.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.ui.res.painterResource
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.GlassShapes
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.designsystem.SheetDragHandle
import com.openmaps.app.core.designsystem.glass
import com.openmaps.app.core.designsystem.surfaceCard
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.UserLocation
import com.openmaps.app.core.localization.LocalAppLocale
import com.openmaps.app.core.localization.LocalMeasurementSystem
import com.openmaps.app.core.localization.NavigationUnits
import com.openmaps.app.core.places.Place
import com.openmaps.app.core.places.PlaceCategory
import com.openmaps.app.map.MapMode
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.roundToInt

/**
 * Map | Live toggle, shared by the Map and Live screens.
 *
 * Mockup: two **separate** pills with a gap between them, not one segmented capsule — the selected
 * pill is filled OpenMaps blue, the unselected pill is filled dark navy (the same [glass] treatment
 * as the category chips), both with white text.
 */
@Composable
fun MapModeToggle(
    selected: MapMode,
    onSelected: (MapMode) -> Unit,
    modifier: Modifier = Modifier,
    transparent: Boolean = false,
) {
    val container = if (transparent) {
        OpenMapsColors.Navy950.copy(alpha = 0.36f)
    } else {
        Color.White.copy(alpha = 0.92f)
    }
    Row(
        modifier = modifier
            .clip(GlassShapes.Pill)
            .background(container)
            .border(
                1.dp,
                if (transparent) Color.White.copy(alpha = 0.10f) else OpenMapsColors.SurfaceBorder,
                GlassShapes.Pill,
            )
            .padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Display order only (not MapMode's declaration order): the reference always shows
        // "Live | Map", in both Map and Live, regardless of which segment is selected.
        listOf(MapMode.Live, MapMode.Map).forEach { mode ->
            val isSelected = mode == selected
            Box(
                modifier = Modifier
                    .heightIn(min = 34.dp)
                    .clip(GlassShapes.Pill)
                    .background(
                        when {
                            isSelected -> OpenMapsColors.ElectricBlueFilled
                            transparent -> Color.Transparent
                            else -> Color.Transparent
                        },
                    )
                    .selectable(
                        selected = isSelected,
                        role = Role.RadioButton,
                        onClick = { onSelected(mode) },
                    )
                    .padding(horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = stringResource(if (mode == MapMode.Map) R.string.mode_map else R.string.mode_live),
                    style = MaterialTheme.typography.labelMedium,
                    color = when {
                        isSelected -> Color.White
                        transparent -> Color.White
                        else -> OpenMapsColors.TextSecondary
                    },
                )
            }
        }
    }
}

/**
 * "Where are you going?" entry point. Opens the search screen.
 *
 * White rounded field directly under the header, per the mockup. The microphone is drawn only when
 * [onVoiceSearchClick] is supplied — the home screen routes it into the real search screen's
 * recognizer, so it is never a control that does nothing. Mockup: search icon + hint + mic only, no
 * settings gear inside the field — settings now lives next to the Map/Live toggle (see
 * [MapSettingsButton]).
 */
@Composable
fun MapSearchBar(
    onSearchClick: () -> Unit,
    modifier: Modifier = Modifier,
    onVoiceSearchClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 50.dp)
            .surfaceCard(GlassShapes.Pill)
            .clickable(role = Role.Button, onClick = onSearchClick)
            .padding(start = 16.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Filled.Search, contentDescription = null, tint = OpenMapsColors.TextSecondary)
        Spacer(Modifier.width(12.dp))
        Text(
            text = stringResource(R.string.map_search_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = OpenMapsColors.TextSecondary,
            modifier = Modifier.weight(1f),
        )
        onVoiceSearchClick?.let { onVoice ->
            IconButton(onClick = onVoice) {
                Icon(
                    painter = painterResource(R.drawable.ic_mic),
                    contentDescription = stringResource(R.string.cd_voice_search),
                    tint = OpenMapsColors.TextSecondary,
                )
            }
        }
    }
}

/**
 * Settings entry point, not present in the mockup's search field. The app still needs one reachable
 * place to change it (Settings > Navigation > Voice Language), so it sits as a small, muted icon
 * next to the Map/Live toggle instead of inside the pixel-matched search bar.
 */
@Composable
fun MapSettingsButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    IconButton(onClick = onClick, modifier = modifier.size(36.dp)) {
        Icon(
            Icons.Filled.Settings,
            contentDescription = stringResource(R.string.cd_settings),
            tint = OpenMapsColors.TextSecondary,
        )
    }
}

/**
 * Floating recenter control: a white circular button on the map, as in the mockup. White rather than
 * navy so it matches the light map and the white cards above it.
 */
@Composable
fun RecenterButton(
    hasLocation: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(52.dp)
            .surfaceCard(GlassShapes.Circle),
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_my_location),
            contentDescription = stringResource(R.string.cd_recenter),
            tint = if (hasLocation) OpenMapsColors.ElectricBlueFilled else OpenMapsColors.TextSecondary,
        )
    }
}

/**
 * The blue circular navigation/location action above the recenter control (mockup, bottom-right).
 *
 * Distinct from [RecenterButton]: recenter only re-points the camera at the user, whereas this is
 * the primary "take me there / go" action and is filled with OpenMaps blue to read as the stronger
 * of the two. Deliberately routes through the same `onNavigateAction` callback the caller wires to
 * recenter-and-act, so it never becomes a second, divergent navigation entry point.
 */
@Composable
fun NavigationActionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(OpenMapsColors.ElectricBlueFilled)
            .clip(CircleShape),
    ) {
        // `Icons.Filled.Navigation` is not in `material-icons-core` (only 49 icons ship there, and
        // this project deliberately avoids pulling in the extended set). Reusing the arrow glyph
        // rotated to point ahead is the same convention `ManeuverGlyph` already documents. Mockup:
        // a diagonal "send/compass" arrow (~45 degrees), not a straight-up one.
        Icon(
            Icons.AutoMirrored.Filled.ArrowForward,
            contentDescription = stringResource(R.string.cd_navigate_action),
            tint = Color.White,
            modifier = Modifier.graphicsLayer { rotationZ = -45f },
        )
    }
}

/**
 * Compass, visible only while the map is rotated. Collects the live bearing itself so only
 * this small composable recomposes while the user rotates the map.
 */
@Composable
fun CompassButton(
    bearing: StateFlow<Float>,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val degrees by bearing.collectAsStateWithLifecycle()
    AnimatedVisibility(
        visible = abs(degrees) > COMPASS_VISIBLE_THRESHOLD_DEGREES,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier,
    ) {
        IconButton(
            onClick = onClick,
            modifier = Modifier
                .size(48.dp)
                .glass(GlassShapes.Circle),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_compass),
                contentDescription = stringResource(R.string.cd_compass),
                tint = Color.Unspecified,
                modifier = Modifier.graphicsLayer { rotationZ = -degrees },
            )
        }
    }
}

private const val COMPASS_VISIBLE_THRESHOLD_DEGREES = 1f

/**
 * Route Preview's origin/destination header: "Your location" above the chosen destination, with a
 * dotted connector between the two outlined fields (mockup's visual link between them). The
 * mockup's swap affordance is deliberately not reproduced: OpenMaps routes from the device fix, it
 * has no second origin to swap to, and a control that does nothing would be a lie.
 */
@Composable
fun RouteEndpointFields(
    destination: SearchResult?,
    modifier: Modifier = Modifier,
    origin: SearchResult? = null,
    onOriginClick: () -> Unit = {},
    onDestinationClick: () -> Unit = {},
) {
    val connectorX = 14.dp + 9.dp // icon column padding + half the 18dp icon
    Column(
        modifier = modifier
            .fillMaxWidth()
            .drawBehind {
                val x = connectorX.toPx()
                drawLine(
                    color = OpenMapsColors.SurfaceBorder,
                    start = androidx.compose.ui.geometry.Offset(x, 46.dp.toPx()),
                    end = androidx.compose.ui.geometry.Offset(x, size.height - 46.dp.toPx()),
                    strokeWidth = 2.dp.toPx(),
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())),
                )
            },
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OpenMapsColors.Surface, GlassShapes.Row)
                .border(1.dp, OpenMapsColors.SurfaceBorder, GlassShapes.Row)
                .clickable(role = Role.Button, onClick = onOriginClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_my_location),
                contentDescription = null,
                tint = OpenMapsColors.ElectricBlueFilled,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = origin?.name?.ifBlank { origin.displayName } ?: stringResource(R.string.route_origin_your_location),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextPrimary,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(OpenMapsColors.Surface, GlassShapes.Row)
                .border(1.dp, OpenMapsColors.SurfaceBorder, GlassShapes.Row)
                .clickable(role = Role.Button, onClick = onDestinationClick)
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                Icons.Filled.Place,
                contentDescription = null,
                tint = Color(0xFFE03131),
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = destination?.name?.ifBlank { destination.displayName }
                    ?: stringResource(R.string.route_destination_placeholder),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

/**
 * The mockup's route-mode row: Driving / Transit / Walking.
 *
 * **Only Driving is real.** OpenMaps computes a production traffic-aware driving profile through
 * its routing gateway (with Valhalla as a fallback), so Transit and Walking are rendered but
 * permanently disabled rather than shown with invented travel times. They
 * are `enabled = false` buttons, not clickable-and-ignored ones, so the control can never be a lie.
 */
@Composable
fun RouteModeRow(
    routeState: RouteState,
    modifier: Modifier = Modifier,
    onModeSelected: (RouteModeOption) -> Unit = {},
) {
    val ready = routeState as? RouteState.Ready
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RouteModeOption.entries.forEach { mode ->
            val isDriving = mode == RouteModeOption.Driving
            val enabled = isDriving
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(GlassShapes.Pill)
                    // Mockup: only the selected (Driving) mode gets a filled pill; Transit/Walking
                    // sit directly on the card with no chip background.
                    .then(if (isDriving) Modifier.background(OpenMapsColors.ElectricBlueFilled) else Modifier)
                    .padding(horizontal = 6.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    painter = painterResource(mode.iconRes()),
                    contentDescription = null,
                    tint = when {
                        isDriving -> Color.White
                        enabled -> OpenMapsColors.ElectricBlueFilled
                        else -> OpenMapsColors.TextSecondary
                    },
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    text = when {
                        isDriving && ready != null -> "${stringResource(mode.labelRes())} · ${formatDuration(ready.route.durationSeconds)}"
                        else -> stringResource(mode.labelRes())
                    },
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = when {
                        isDriving -> Color.White
                        else -> OpenMapsColors.TextSecondary
                    },
                )
            }
        }
    }
}

/** The three travel modes the mockup shows. Only [Driving] is routable today. */
enum class RouteModeOption(val isSupported: Boolean) {
    Driving(true),
    Transit(false),
    Walking(false),
}

private fun RouteModeOption.iconRes(): Int = when (this) {
    RouteModeOption.Driving -> R.drawable.ic_car
    RouteModeOption.Transit -> R.drawable.ic_transit
    RouteModeOption.Walking -> R.drawable.ic_walk
}

private fun RouteModeOption.labelRes(): Int = when (this) {
    RouteModeOption.Driving -> R.string.route_mode_driving
    RouteModeOption.Transit -> R.string.route_mode_transit
    RouteModeOption.Walking -> R.string.route_mode_walking
}

/**
 * Collapsed bottom panel. Shows a compact destination summary (plus, once Phase 3's route
 * loads, distance/ETA) once a destination is selected, otherwise the Phase 1 placeholder.
 */
@Composable
fun BottomPanel(
    destination: SearchResult?,
    routeState: RouteState,
    currentLocation: UserLocation? = null,
    nearbyPlaces: List<Place> = emptyList(),
    recentDestinations: List<SearchResult> = emptyList(),
    favoriteDestinations: List<SearchResult> = emptyList(),
    onRecentSelected: (SearchResult) -> Unit = {},
    onPlaceSelected: (Place) -> Unit = {},
    onClearDestination: () -> Unit,
    /** Phase 12A, section B5: opens the Nearby/Explore sheet; only reachable with no destination. */
    onExploreClicked: () -> Unit,
    /** A discovery tile was tapped: open Explore narrowed to that category. */
    onExploreCategoryClicked: (PlaceCategory) -> Unit,
    /** Opens the step list for the computed route (mockup's "Steps" button). */
    onSteps: () -> Unit,
    onAlternativeSelected: (Int) -> Unit = {},
    canStart: Boolean,
    isNavigating: Boolean,
    navigationError: NavigationError?,
    selectedNavigationMode: NavigationMode,
    onNavigationModeSelected: (NavigationMode) -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    var discoverExpanded by remember { mutableStateOf(false) }
    var dragDistance by remember { mutableFloatStateOf(0f) }
    // Route Preview has its own dark surface. The home/discovery sheet keeps its light treatment.
    // Do not re-theme the shared surfaceCard() modifier used across Map and Search.
    val panelSurface = if (destination != null) {
        modifier.fillMaxWidth()
            .background(OpenMapsColors.Navy900, GlassShapes.Card)
            .clip(GlassShapes.Card)
    } else {
        modifier.fillMaxWidth().surfaceCard(GlassShapes.Card)
    }
    Column(
        modifier = panelSurface.padding(horizontal = 18.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        // Drag only from the handle so category taps and scrolling do not fight sheet gestures.
        Box(
            Modifier
                .fillMaxWidth()
                .height(24.dp)
                .pointerInput(destination) {
                    detectVerticalDragGestures(
                        onDragStart = { dragDistance = 0f },
                        onVerticalDrag = { change, amount ->
                            change.consume()
                            dragDistance += amount
                        },
                        onDragEnd = {
                            if (dragDistance < -35f) discoverExpanded = true
                            if (dragDistance > 35f) discoverExpanded = false
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            SheetDragHandle(
                tint = if (destination != null) Color.White.copy(alpha = 0.35f) else OpenMapsColors.SurfaceBorder,
            )
        }
        if (destination == null) {
            Box(
                modifier = if (discoverExpanded) Modifier.height(screenHeight * 0.68f)
                    else Modifier.heightIn(max = screenHeight * 0.40f),
            ) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
            HomeDiscoveryPanel(
                currentLocation = currentLocation,
                nearbyPlaces = nearbyPlaces,
                recentDestinations = recentDestinations,
                favoriteDestinations = favoriteDestinations,
                onExploreClicked = onExploreClicked,
                onExploreCategoryClicked = onExploreCategoryClicked,
                onRecentSelected = onRecentSelected,
                onPlaceSelected = onPlaceSelected,
            )
            }
            }
        } else {
            Text(
                text = stringResource(R.string.route_preview_title),
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                modifier = Modifier.align(Alignment.Start),
            )
            Text(
                text = stringResource(R.string.route_preview_subtitle),
                style = MaterialTheme.typography.bodySmall,
                color = OpenMapsColors.TextOnDarkSecondary,
                modifier = Modifier.align(Alignment.Start).padding(top = 2.dp),
            )
            Spacer(Modifier.height(12.dp))
            RoutePreviewSummary(routeState, onAlternativeSelected)
            Spacer(Modifier.height(14.dp))
            NavigationControls(
                canStart = canStart,
                isNavigating = isNavigating,
                navigationError = navigationError,
                selectedNavigationMode = selectedNavigationMode,
                onNavigationModeSelected = onNavigationModeSelected,
                onSteps = onSteps,
                onStart = onStart,
                onStop = onStop,
            )
        }
    }
}

@Composable
private fun HomeDiscoveryPanel(
    currentLocation: UserLocation?,
    nearbyPlaces: List<Place>,
    recentDestinations: List<SearchResult>,
    favoriteDestinations: List<SearchResult>,
    onExploreClicked: () -> Unit,
    onExploreCategoryClicked: (PlaceCategory) -> Unit,
    onRecentSelected: (SearchResult) -> Unit,
    onPlaceSelected: (Place) -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.Start,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(role = Role.Button, onClick = onExploreClicked)
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.home_explore_title),
                    style = MaterialTheme.typography.titleLarge,
                    color = OpenMapsColors.TextPrimary,
                )
                Text(
                    text = stringResource(R.string.home_explore_subtitle),
                    style = MaterialTheme.typography.bodySmall,
                    color = OpenMapsColors.TextSecondary,
                )
            }
            Text(
                text = stringResource(R.string.home_see_all),
                style = MaterialTheme.typography.labelLarge,
                color = OpenMapsColors.ElectricBlueFilled,
            )
        }

        Spacer(Modifier.height(12.dp))
        ExploreCategoryTiles(onCategoryClicked = onExploreCategoryClicked)

        val nearYou = nearbyPlaces.take(4)
        if (nearYou.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HomeSectionHeader(
                title = stringResource(R.string.home_near_you_now),
                subtitle = stringResource(R.string.home_near_you_now_subtitle),
            )
            Spacer(Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                nearYou.take(2).forEach { place ->
                    HomePlaceCard(
                        place = place,
                        onClick = { onPlaceSelected(place) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }

        val ahead = currentLocation
            ?.bearingDegrees
            ?.let { bearing -> homePlacesAhead(currentLocation, bearing, nearbyPlaces) }
            .orEmpty()
        if (ahead.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HomeSectionHeader(
                title = stringResource(R.string.home_whats_ahead),
                subtitle = stringResource(R.string.home_whats_ahead_subtitle),
            )
            Spacer(Modifier.height(6.dp))
            ahead.take(3).forEach { place ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onPlaceSelected(place) }
                        .padding(vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(place.category.iconRes()),
                        contentDescription = null,
                        tint = place.category.markerColor(),
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = place.name.ifBlank { place.category.displayName() },
                        style = MaterialTheme.typography.bodyMedium,
                        color = OpenMapsColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    place.distanceMeters?.let { meters ->
                        Text(
                            text = homeDistanceLabel(meters),
                            style = MaterialTheme.typography.labelMedium,
                            color = OpenMapsColors.TextSecondary,
                        )
                    }
                }
            }
        }

        if (favoriteDestinations.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HomeSectionHeader(
                title = "Saved places",
                subtitle = "Your favorites are stored on this device",
            )
            favoriteDestinations.take(5).forEach { favorite ->
                Row(
                    modifier = Modifier.fillMaxWidth()
                        .clickable { onRecentSelected(favorite) }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_bookmark),
                        contentDescription = null,
                        tint = OpenMapsColors.ElectricBlueFilled,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Text(
                        text = favorite.name.ifBlank { favorite.displayName },
                        style = MaterialTheme.typography.bodyMedium,
                        color = OpenMapsColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (recentDestinations.isNotEmpty()) {
            Spacer(Modifier.height(16.dp))
            HomeSectionHeader(
                title = stringResource(R.string.home_recent_destinations),
                subtitle = stringResource(R.string.home_recent_destinations_subtitle),
            )
            recentDestinations.take(2).forEach { recent ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onRecentSelected(recent) }
                        .padding(vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Filled.Place,
                        contentDescription = null,
                        tint = OpenMapsColors.ElectricBlueFilled,
                        modifier = Modifier.size(17.dp),
                    )
                    Spacer(Modifier.width(9.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = recent.name.ifBlank { recent.displayName },
                            style = MaterialTheme.typography.bodyMedium,
                            color = OpenMapsColors.TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        recent.subtitle.takeIf { it.isNotBlank() }?.let {
                            Text(
                                text = it,
                                style = MaterialTheme.typography.bodySmall,
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
}

@Composable
private fun HomeSectionHeader(title: String, subtitle: String) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = OpenMapsColors.TextPrimary,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodySmall,
            color = OpenMapsColors.TextSecondary,
        )
    }
}

@Composable
private fun HomePlaceCard(
    place: Place,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(OpenMapsColors.SurfaceMuted, GlassShapes.Card)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                painter = painterResource(place.category.iconRes()),
                contentDescription = null,
                tint = place.category.markerColor(),
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = place.category.displayName(),
                style = MaterialTheme.typography.labelSmall,
                color = OpenMapsColors.TextSecondary,
            )
        }
        Text(
            text = place.name.ifBlank { place.category.displayName() },
            style = MaterialTheme.typography.titleSmall,
            color = OpenMapsColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 5.dp),
        )
        place.distanceMeters?.let { meters ->
            Text(
                text = homeDistanceLabel(meters),
                style = MaterialTheme.typography.labelMedium,
                color = OpenMapsColors.ElectricBlueFilled,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

private fun homePlacesAhead(
    location: UserLocation,
    movementBearingDegrees: Float,
    places: List<Place>,
): List<Place> =
    places.asSequence()
        .filter { it.distanceMeters != null }
        .map { place ->
            place to homeAngularDifference(
                movementBearingDegrees.toDouble(),
                homeInitialBearing(
                    location.latitude,
                    location.longitude,
                    place.latitude,
                    place.longitude,
                ),
            )
        }
        .filter { (_, diff) -> diff <= HOME_AHEAD_HALF_ANGLE_DEGREES }
        .sortedBy { (place, _) -> place.distanceMeters }
        .map { it.first }
        .take(3)
        .toList()

private fun homeInitialBearing(
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

private fun homeAngularDifference(a: Double, b: Double): Double {
    val raw = abs(a - b) % 360.0
    return if (raw > 180.0) 360.0 - raw else raw
}

@Composable
private fun homeDistanceLabel(meters: Double): String =
    NavigationUnits.formatDistance(meters, LocalMeasurementSystem.current, LocalAppLocale.current)

private const val HOME_AHEAD_HALF_ANGLE_DEGREES = 55.0

@Composable
private fun RoutePreviewSummary(
    routeState: RouteState,
    onAlternativeSelected: (Int) -> Unit = {},
) {
    when (routeState) {
        RouteState.Idle -> Unit
        RouteState.WaitingForLocation -> Text(
            text = stringResource(R.string.route_waiting_for_location),
            style = MaterialTheme.typography.bodySmall,
            color = OpenMapsColors.TextOnDarkSecondary,
        )
        RouteState.Loading -> RouteLoadingRow(color = OpenMapsColors.TextOnDarkSecondary)
        is RouteState.Error -> Text(
            text = stringResource(routeState.reason.toMessageRes()),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        is RouteState.Ready -> {
            val trafficStatus = routeState.route.trafficDelaySeconds
                ?.takeIf { routeState.route.isTrafficAware && it >= 60.0 }
                ?.let { delay ->
                    stringResource(R.string.route_traffic_delay_format, (delay / 60.0).roundToInt())
                }
            RoutePreviewDarkMetricsRow(
                durationText = formatDuration(routeState.route.durationSeconds),
                distanceText = formatDistance(routeState.route.distanceMeters),
                arrivalText = ArrivalTimeFormatter.format(
                    routeState.route.durationSeconds,
                    System.currentTimeMillis(),
                    locale = LocalAppLocale.current,
                ),
                statusText = trafficStatus,
            )

            if (routeState.route.alternatives.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    text = stringResource(R.string.route_other_routes),
                    style = MaterialTheme.typography.labelLarge,
                    color = Color.White,
                )
                Spacer(Modifier.height(6.dp))
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    routeState.route.alternatives.take(2).forEachIndexed { index, alternative ->
                        RoutePreviewDarkAlternateRow(
                            label = alternative.label ?: stringResource(R.string.route_alternate_fallback),
                            detail = "${formatDuration(alternative.durationSeconds)} · ${formatDistance(alternative.distanceMeters)}",
                            onUseClick = { onAlternativeSelected(index) },
                        )
                    }
                }
            }

            if (routeState.route.maneuvers.isNotEmpty()) {
                Text(
                    text = stringResource(
                        R.string.route_preview_turn_count,
                        routeState.route.maneuvers.count {
                            it.type != com.openmaps.app.core.routing.ManeuverType.Start &&
                                it.type != com.openmaps.app.core.routing.ManeuverType.Continue &&
                                it.type != com.openmaps.app.core.routing.ManeuverType.Destination
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = OpenMapsColors.TextOnDarkSecondary,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }
}

@Composable
private fun RouteMetricCard(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .background(OpenMapsColors.SurfaceMuted, GlassShapes.Card)
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            color = OpenMapsColors.TextPrimary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = OpenMapsColors.TextSecondary,
            maxLines = 1,
        )
    }
}

/**
 * One compact line under the destination subtitle: loading, error, or "12 mi · 18 min · Arrive
 * 3:45 PM" — Route Preview's confirmation figures (section A5: distance, duration, "expected
 * arrival time if calculable"). The arrival clock time is read at render time ([System.currentTimeMillis])
 * rather than computed once and stored, so it stays correct across recomposition without this
 * becoming a second, independently-fallible estimate — it is always `now + durationSeconds`.
 */
@Composable
private fun RouteSummaryLine(routeState: RouteState) {
    when (routeState) {
        RouteState.Idle -> Unit
        RouteState.WaitingForLocation -> RouteStatusText(stringResource(R.string.route_waiting_for_location))
        RouteState.Loading -> RouteLoadingRow()
        // Mockup Route Preview: a large duration/distance headline, then the arrival line beneath.
        // Traffic delay is shown in RoutePreviewSummary only when the production provider supplied
        // real traffic-aware timing; this compact summary never invents a second estimate.
        is RouteState.Ready -> {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = formatDuration(routeState.route.durationSeconds),
                    style = MaterialTheme.typography.displaySmall,
                    color = OpenMapsColors.TextPrimary,
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = formatDistance(routeState.route.distanceMeters),
                    style = MaterialTheme.typography.titleMedium,
                    color = OpenMapsColors.TextSecondary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            RouteStatusText(
                text = stringResource(
                    R.string.route_arrive_format,
                    ArrivalTimeFormatter.format(
                        routeState.route.durationSeconds,
                        System.currentTimeMillis(),
                        locale = LocalAppLocale.current,
                    ),
                ),
                color = OpenMapsColors.TextSecondary,
            )
        }
        is RouteState.Error -> RouteStatusText(stringResource(routeState.reason.toMessageRes()))
    }
}

@Composable
private fun RouteStatusText(text: String, color: Color = OpenMapsColors.TextSecondary) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = color,
        modifier = Modifier.padding(top = 2.dp),
    )
}

@Composable
private fun RouteLoadingRow(color: Color = OpenMapsColors.TextSecondary) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = OpenMapsColors.ElectricBlue)
        Spacer(Modifier.width(6.dp))
        Text(
            text = stringResource(R.string.route_loading),
            style = MaterialTheme.typography.labelLarge,
            color = color,
        )
    }
}

private fun RouteError.toMessageRes(): Int = when (this) {
    RouteError.NoNetwork -> R.string.route_error_no_network
    RouteError.Timeout -> R.string.route_error_timeout
    RouteError.ServerError -> R.string.route_error_server
    RouteError.Malformed -> R.string.route_error_malformed
    RouteError.NoRouteFound -> R.string.route_error_no_route
}

@Composable
private fun formatDistance(meters: Double): String =
    NavigationUnits.formatDistance(meters, LocalMeasurementSystem.current)

@Composable
private fun formatDuration(seconds: Double): String =
    NavigationUnits.formatDuration(seconds, LocalAppLocale.current)

/** One card for every location situation; copy and primary action depend on [prompt]. */
@Composable
fun LocationPromptCard(
    prompt: LocationPrompt,
    onAction: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (prompt == LocationPrompt.Loading) {
        Row(
            modifier = modifier
                .glass(GlassShapes.Pill)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp,
                color = OpenMapsColors.ElectricBlue,
            )
            Text(
                text = stringResource(R.string.location_loading),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextPrimary,
            )
        }
        return
    }

    val copy = prompt.copy()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .surfaceCard(GlassShapes.Card)
            .padding(start = 18.dp, top = 14.dp, end = 6.dp, bottom = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(
                    text = stringResource(copy.title),
                    style = MaterialTheme.typography.titleMedium,
                    color = OpenMapsColors.TextPrimary,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(copy.body),
                    style = MaterialTheme.typography.bodyMedium,
                    color = OpenMapsColors.TextSecondary,
                )
            }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = stringResource(R.string.cd_dismiss),
                    tint = OpenMapsColors.TextSecondary,
                )
            }
        }
        TextButton(onClick = onAction, modifier = Modifier.align(Alignment.Start)) {
            Text(
                text = stringResource(copy.action),
                style = MaterialTheme.typography.labelLarge,
                color = OpenMapsColors.ElectricBlue,
            )
        }
    }
}

private data class PromptCopy(val title: Int, val body: Int, val action: Int)

private fun LocationPrompt.copy(): PromptCopy = when (this) {
    LocationPrompt.AllowLocation -> PromptCopy(
        R.string.location_allow_title, R.string.location_allow_body, R.string.location_allow_action,
    )
    LocationPrompt.Denied -> PromptCopy(
        R.string.location_denied_title, R.string.location_denied_body, R.string.location_allow_action,
    )
    LocationPrompt.Blocked -> PromptCopy(
        R.string.location_blocked_title, R.string.location_blocked_body, R.string.location_blocked_action,
    )
    LocationPrompt.ServicesOff -> PromptCopy(
        R.string.location_services_off_title, R.string.location_services_off_body, R.string.location_services_off_action,
    )
    LocationPrompt.Unavailable -> PromptCopy(
        R.string.location_unavailable_title, R.string.location_unavailable_body, R.string.location_unavailable_action,
    )
    // Loading is rendered as a pill above and never reaches here.
    LocationPrompt.Loading -> PromptCopy(
        R.string.location_loading, R.string.location_loading, R.string.location_loading,
    )
}

/**
 * Part A7: the concise, dismissible reason shown after Start's automatic switch to Live fell back
 * to Map because the camera turned out to be unusable. A single compact row, not a full card — this
 * is informational, not a decision the user needs to make (unlike [LocationPromptCard], it has no
 * primary action: by the time it shows, navigation is already continuing fine in Map).
 */
@Composable
fun LiveFallbackBanner(message: String, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .surfaceCard(GlassShapes.Pill)
            .padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelMedium,
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
}


/**
 * Persistent degraded-service notice for a route restored from the local cache. No dismiss action:
 * the notice disappears only after a live route replaces the cached one.
 */
@Composable
fun DegradedRouteBanner(modifier: Modifier = Modifier) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .surfaceCard(GlassShapes.Pill)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = stringResource(R.string.route_degraded_cached),
            style = MaterialTheme.typography.labelMedium,
            color = OpenMapsColors.Warning,
        )
    }
}

/** Full-screen failure state: "Map unavailable" + retry. Browsing is blocked only by this. */
@Composable
fun MapUnavailableOverlay(
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .drawBehind { drawRect(OpenMapsColors.Navy900) }
            // Swallow touches so gestures do not reach the (failed) map underneath.
            .pointerInput(Unit) { detectTapGestures { } },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.map_unavailable_title),
                style = MaterialTheme.typography.titleLarge,
                color = OpenMapsColors.TextPrimary,
            )
            Text(
                text = stringResource(R.string.map_unavailable_body),
                style = MaterialTheme.typography.bodyMedium,
                color = OpenMapsColors.TextSecondary,
            )
            Button(onClick = onRetry) {
                Text(stringResource(R.string.action_retry))
            }
        }
    }
}
