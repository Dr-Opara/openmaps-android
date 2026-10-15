package com.openmaps.app.feature.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openmaps.app.core.designsystem.OpenMapsWordmark
import com.openmaps.app.map.MapEngine
import com.openmaps.app.map.MapLibreHost
import com.openmaps.app.map.MapLibreMapEngine
import com.openmaps.app.map.MapStyleProvider
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * The Map experience. State and policy live in [MapViewModel]; this composable only lays out
 * the map and overlays and forwards user intent.
 *
 * The map itself lives in [MapLibreHost] and receives data through flows, so state changes
 * here (prompts, mode, errors) never recreate or re-update the map view.
 */
@Composable
fun MapScreen(
    viewModel: MapViewModel,
    styleProvider: MapStyleProvider,
    onSearchClick: () -> Unit,
    onRouteOriginClick: () -> Unit = {},
    onRouteDestinationClick: () -> Unit = {},
    /** Opens the search screen straight into voice mode. Null hides the mic rather than showing a dead icon. */
    onVoiceSearchClick: (() -> Unit)? = null,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    // One engine per composition of this screen. The initial camera is the ViewModel's last
    // known camera, so a rotation/recreation restores the view the user left.
    val engine: MapEngine = remember(viewModel, styleProvider) {
        MapLibreMapEngine(styleProvider = styleProvider, initialCamera = viewModel.uiState.value.cameraState)
    }
    val userLocationFlow = remember(viewModel) {
        viewModel.uiState.map { it.currentLocation }.distinctUntilChanged()
    }
    val destinationFlow = remember(viewModel) {
        viewModel.uiState.map { it.selectedDestination }.distinctUntilChanged()
    }
    val routeFlow = remember(viewModel) {
        viewModel.uiState.map { (it.routeState as? RouteState.Ready)?.route }.distinctUntilChanged()
    }
    val placesFlow = remember(viewModel) {
        viewModel.uiState.map { it.visiblePlaces }.distinctUntilChanged()
    }
    val roadEventsFlow = remember(viewModel) {
        viewModel.uiState.map { it.roadIntelligenceState.events }.distinctUntilChanged()
    }
    // Its own StateFlow rather than derived from `commands`: that flow is a Channel, so a second
    // collector would compete for messages with the one MapLibreHost already uses and silently
    // swallow camera/style commands.
    // Already a StateFlow, so it de-duplicates on its own; distinctUntilChanged would be a no-op.
    val routeProgressFlow = remember(viewModel) { viewModel.routeProgressView }
    var bottomInsetPx by remember { mutableIntStateOf(0) }
    LaunchedEffect(engine, bottomInsetPx) { engine.setBottomInset(bottomInsetPx) }

    Box(modifier.fillMaxSize()) {
        MapLibreHost(
            engine = engine,
            commands = viewModel.commands,
            userLocation = userLocationFlow,
            destination = destinationFlow,
            route = routeFlow,
            onEvent = viewModel::onMapEvent,
            modifier = Modifier.fillMaxSize(),
            routeProgress = routeProgressFlow,
            places = placesFlow,
            roadEvents = roadEventsFlow,
        )

        // Failure state sits above the map but below the controls, so search and settings
        // stay reachable while the map is unavailable.
        if (state.error != null) {
            MapUnavailableOverlay(onRetry = viewModel::onRetryMap, modifier = Modifier.fillMaxSize())
        }

        // Mockup Search/Explore: branding left, Map/Live toggle right, then the search field and
        // the dark category chips directly beneath it.
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                OpenMapsWordmark(Modifier.padding(start = 4.dp))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    MapModeToggle(selected = state.selectedMapMode, onSelected = viewModel::onModeSelected)
                    MapSettingsButton(onClick = onSettingsClick)
                }
            }
            // Mockup Route Preview (screen 4): the origin/destination fields and travel-mode row
            // live at the TOP, replacing the generic search bar — the bottom card then holds only
            // the summary and Start/Steps. True exactly when BottomPanel's own "preview" branch is
            // what is rendering at the bottom (not Destination Details/Steps/Explore).
            val isRoutePreview = state.selectedDestination != null &&
                !state.isShowingAnyDestinationDetails &&
                !state.isShowingSteps &&
                !state.isExploreOpen &&
                state.selectedPlace == null
            // Navigation replaces the search affordance for the duration of a trip: the banner is
            // the driver's primary instruction, and search is a trip-ending action anyway. The
            // category toggles go with it — Live/active-trip chrome should not grow, not shrink.
            if (!state.isNavigating) {
                if (isRoutePreview) {
                    RouteEndpointFields(
                        destination = state.selectedDestination,
                        origin = state.routeOriginOverride,
                        onOriginClick = onRouteOriginClick,
                        onDestinationClick = onRouteDestinationClick,
                    )
                    RouteModeRow(routeState = state.routeState)
                } else {
                    MapSearchBar(
                        onSearchClick = onSearchClick,
                        onVoiceSearchClick = onVoiceSearchClick,
                    )
                    PlaceCategoryFilterRow(
                        selected = state.placeCategoryFilters,
                        onSelectedChanged = viewModel::onCategoryFilterChanged,
                        // Mockup's compact four-chip strip; the full taxonomy lives in Explore.
                        categories = HOME_CHIP_CATEGORIES,
                    )
                }
            } else {
                NavigationBanner(
                    navigationState = state.navigationState,
                    followSuspended = state.navigationFollowSuspended,
                )
            }
            RoadAlertBanner(roadState = state.roadIntelligenceState)
            // Part A7: a concise, dismissible reason when Start's automatic switch to Live had to
            // fall back to Map because the camera turned out to be unusable. Null (and this renders
            // nothing) once dismissed or once Live is next entered successfully.
            state.liveFallbackMessage?.let { message ->
                LiveFallbackBanner(message = message, onDismiss = viewModel::onLiveFallbackMessageDismissed)
            }
        }

        // Bottom: controls, then the location card, then the collapsed panel.
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .onSizeChanged { bottomInsetPx = it.height }
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.align(Alignment.End),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                CompassButton(bearing = viewModel.compassBearing, onClick = viewModel::onCompassClicked)
                // Mockup stacks the two circular map controls: white recenter above the filled blue
                // navigation action.
                RecenterButton(hasLocation = state.currentLocation != null, onClick = viewModel::onRecenterClicked)
                NavigationActionButton(
                    onClick = viewModel::onNavigationActionClicked,
                    modifier = Modifier.align(Alignment.End),
                )
            }
            state.locationPrompt?.let { prompt ->
                LocationPromptCard(
                    prompt = prompt,
                    onAction = viewModel::onLocationPromptAction,
                    onDismiss = viewModel::onDismissLocationPrompt,
                    modifier = Modifier.align(Alignment.Start),
                )
            }
            val selectedPlace = state.selectedPlace
            when {
                // Mockup Route Preview: the "Steps" maneuver list takes over the panel region,
                // layered above the route summary it belongs to.
                state.isShowingSteps && state.stepsRouteSummary != null -> RouteStepsSheet(
                    maneuvers = state.stepsRouteSummary?.maneuvers.orEmpty(),
                    onDismiss = viewModel::onStepsDismissed,
                )

                // Place details take over the panel region rather than stacking beside it — a
                // compact presentation, not a second destination summary competing for attention.
                selectedPlace != null -> PlaceDetailsSheet(
                    place = selectedPlace,
                    nearbyPlaces = state.visiblePlaces,
                    onDirections = { viewModel.onDirectionsRequestedForPlace(selectedPlace) },
                    onStart = { viewModel.onStartFromPlace(selectedPlace) },
                    onSelectNearbyPlace = viewModel::onPlaceSelected,
                    onDismiss = viewModel::onPlaceDetailsDismissed,
                )

                // Part A2/A3: a destination was picked but Directions has not been tapped yet.
                // Phase 12A, section D: or the user tapped "Directions" again from Route Preview to
                // review the destination (isReviewingDirections) — same sheet, but dismissing it
                // must return to Route Preview rather than clearing the destination/route.
                state.isShowingAnyDestinationDetails -> state.selectedDestination?.let { destination ->
                    DestinationDetailsSheet(
                        destination = destination,
                        currentLocation = state.currentLocation,
                        onDirections = viewModel::onDirectionsRequested,
                        // Early Start never skips Route Preview: with no route yet it computes one
                        // and lands there, where the real Start lives.
                        onStart = { viewModel.onStartRequestedFromDestination(destination) },
                        onDismiss = if (state.isReviewingDirections) {
                            viewModel::onDirectionsReviewDismissed
                        } else {
                            viewModel::onDestinationCleared
                        },
                    )
                }

                // Phase 12A, section B5: the Explore CTA's bottom sheet. Lower priority than place
                // details/destination details above, so drilling into a card (Details/Navigate)
                // naturally takes over this same region instead of fighting it for space.
                state.isExploreOpen -> NearbyExploreSheet(
                    placesState = state.placesState,
                    places = state.visiblePlaces,
                    selectedCategories = state.placeCategoryFilters,
                    onCategoriesChanged = viewModel::onCategoryFilterChanged,
                    hasCurrentLocation = state.currentLocation != null,
                    onDetails = viewModel::onPlaceSelected,
                    onNavigate = viewModel::onNavigateToPlace,
                    onDismiss = viewModel::onExploreDismissed,
                    onRetry = viewModel::onExploreRetryRequested,
                )

                else -> {
                    BottomPanel(
                        destination = state.selectedDestination,
                        routeState = state.routeState,
                        onClearDestination = viewModel::onDestinationCleared,
                        onExploreClicked = viewModel::onExploreRequested,
                        onExploreCategoryClicked = viewModel::onExploreCategoryClicked,
                        onSteps = viewModel::onStepsRequested,
                        canStart = state.canStartNavigation,
                        isNavigating = state.isNavigating,
                        navigationError = (state.navigationState as? NavigationState.Error)?.reason,
                        onStart = viewModel::startNavigation,
                        onStop = viewModel::stopNavigation,
                    )
                }
            }
        }
    }
}
