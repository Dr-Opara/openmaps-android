package com.openmaps.app

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.openmaps.app.core.camera.CameraPermissions
import com.openmaps.app.core.designsystem.OpenMapsColors
import com.openmaps.app.core.location.LocationPermissions
import com.openmaps.app.feature.live.LiveHud
import com.openmaps.app.feature.live.LiveReadinessMapper
import com.openmaps.app.feature.live.LiveScreen
import com.openmaps.app.feature.live.LiveUiEvent
import com.openmaps.app.feature.live.LiveViewModel
import com.openmaps.app.feature.live.liveViewModelFactory
import com.openmaps.app.feature.map.MapScreen
import com.openmaps.app.feature.map.MapUiEvent
import com.openmaps.app.feature.map.RouteState
import com.openmaps.app.feature.map.MapViewModel
import com.openmaps.app.feature.map.mapViewModelFactory
import com.openmaps.app.feature.search.SearchScreen
import com.openmaps.app.feature.search.SearchViewModel
import com.openmaps.app.feature.search.searchViewModelFactory
import com.openmaps.app.feature.settings.SettingsScreen
import com.openmaps.app.feature.splash.SplashScreen
import com.openmaps.app.map.MapMode

private enum class Overlay { None, Search, Settings }
private enum class SearchPurpose { NewDestination, RouteOrigin, RouteDestination }

/**
 * App root: splash, then one persistent shell.
 *
 * Search/Settings/Live are layered over an always-composed Map instead of being separate
 * navigation destinations. That keeps the MapLibre view alive (no style reload, camera and
 * dot preserved) when the user opens a screen and comes back. Add a navigation library when
 * the screen count justifies it.
 */
@Composable
fun OpenMapsApp(container: AppContainer) {
    var showSplash by rememberSaveable { mutableStateOf(true) }
    if (showSplash) {
        SplashScreen(onFinished = { showSplash = false })
    } else {
        AppShell(container)
    }
}

@Composable
private fun AppShell(container: AppContainer) {
    val viewModel: MapViewModel = viewModel(factory = mapViewModelFactory(container))
    val searchViewModel: SearchViewModel = viewModel(factory = searchViewModelFactory(container))
    val liveViewModel: LiveViewModel = viewModel(factory = liveViewModelFactory(container))
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val liveState by liveViewModel.uiState.collectAsStateWithLifecycle()
    val searchState by searchViewModel.uiState.collectAsStateWithLifecycle()

    // Production search: continuously keep the geocoder's ranking bias aligned with the latest
    // device location. This never restricts worldwide search; it only helps ambiguous/local queries
    // prefer nearby addresses and POIs.
    LaunchedEffect(state.currentLocation?.latitude, state.currentLocation?.longitude) {
        searchViewModel.onLocationBiasChanged(state.currentLocation)
    }

    var overlay by rememberSaveable { mutableStateOf(Overlay.None) }
    var searchPurpose by rememberSaveable { mutableStateOf(SearchPurpose.NewDestination) }
    // Bumped to ask the search screen to start voice capture as soon as it appears. An incrementing
    // counter rather than a boolean so tapping the home mic twice still re-arms the recognizer.
    var voiceSearchRequest by rememberSaveable { mutableIntStateOf(0) }

    // Part A7: reactive (never timer-based) fallback from Live back to Map. Recomputed whenever
    // Live's camera permission/failure state changes; `MapViewModel.onLiveReadinessChanged` itself
    // guards this to only act in the short window right after `startNavigation()` auto-switched to
    // Live, so this is safe to call unconditionally here rather than gating on the current mode.
    LaunchedEffect(liveState) {
        val (readiness, reason) = LiveReadinessMapper.of(liveState)
        viewModel.onLiveReadinessChanged(readiness, reason)
    }

    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val prefs = container.locationPermissionPrefs
    val cameraPrefs = container.cameraPermissionPrefs

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.onPermissionStateChanged(LocationPermissions.evaluate(activity, prefs))
    }

    // Camera permission is requested only when the user actually opens Live, never on launch.
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        liveViewModel.onCameraPermissionChanged(CameraPermissions.evaluate(activity, cameraPrefs))
    }

    // Visibility gates GPS usage; permission is re-read on every resume (the user may have
    // changed it in system settings).
    LifecycleEventEffect(Lifecycle.Event.ON_START) { viewModel.onAppVisibilityChanged(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_STOP) { viewModel.onAppVisibilityChanged(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        viewModel.onPermissionStateChanged(LocationPermissions.evaluate(activity, prefs))
        // Re-read on resume: the user may have granted or revoked camera access in Settings while
        // the app was backgrounded.
        liveViewModel.onCameraPermissionChanged(CameraPermissions.evaluate(activity, cameraPrefs))
    }

    // Phase 7/8 lifecycle bridge: the Map feature owns the trip, so it is the only thing that knows
    // when the route is replaced or the trip ends. Live's anchors and arrows are derived from that
    // route, so they are told through these two callbacks rather than by watching MapUiState — a
    // state-driven rebuild would still leave stale anchors for one composition pass, which shows up
    // as a ghost arrow over the road just driven.
    //
    // Assigned once per ViewModel pair and cleared on dispose, so a recomposition cannot stack
    // duplicate callbacks and clear the arrows repeatedly.
    DisposableEffect(viewModel, liveViewModel) {
        viewModel.onRouteReplaced = { liveViewModel.onRouteReplaced() }
        viewModel.onNavigationEnded = { liveViewModel.onNavigationEnded() }
        onDispose {
            viewModel.onRouteReplaced = {}
            viewModel.onNavigationEnded = {}
        }
    }

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                MapUiEvent.RequestLocationPermission -> {
                    prefs.markRequested()
                    permissionLauncher.launch(LocationPermissions.REQUEST)
                }
                MapUiEvent.OpenAppSettings -> context.openAppSettings()
                MapUiEvent.OpenLocationSettings -> context.startSafely(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                is MapUiEvent.ShareTripUrl -> context.shareText(
                    title = "OpenMaps trip",
                    text = event.url,
                )
                // Purely informational: the old route is still active and usable, so there is
                // nothing to launch and nothing to recover from here. Deliberately a no-op — the
                // visible cue is the OffRoute state on the banner itself.
                MapUiEvent.RerouteFailed -> Unit
            }
        }
    }

    LaunchedEffect(liveViewModel) {
        liveViewModel.events.collect { event ->
            when (event) {
                LiveUiEvent.RequestCameraPermission -> {
                    cameraPrefs.markRequested()
                    cameraPermissionLauncher.launch(CameraPermissions.REQUEST)
                }
                LiveUiEvent.OpenAppSettings -> context.openAppSettings()
            }
        }
    }

    BackHandler(enabled = overlay != Overlay.None || state.selectedMapMode == MapMode.Live) {
        if (overlay != Overlay.None) overlay = Overlay.None else viewModel.onModeSelected(MapMode.Map)
    }

    val styleSource = remember(container) { container.mapStyleProvider.resolve() }

    Box(Modifier.fillMaxSize().background(OpenMapsColors.Navy900)) {
        MapScreen(
            viewModel = viewModel,
            styleProvider = container.mapStyleProvider,
            recentDestinations = searchState.recentSearches,
            onShareDestination = { destination ->
                context.shareText(
                    title = destination.name.ifBlank { "OpenMaps destination" },
                    text = buildString {
                        append(destination.name.ifBlank { "OpenMaps destination" })
                        destination.subtitle.takeIf { it.isNotBlank() }?.let {
                            append("\n")
                            append(it)
                        }
                    },
                )
            },
            onSearchClick = {
                searchPurpose = SearchPurpose.NewDestination
                overlay = Overlay.Search
            },
            onRouteOriginClick = {
                searchPurpose = SearchPurpose.RouteOrigin
                overlay = Overlay.Search
            },
            onRouteDestinationClick = {
                searchPurpose = SearchPurpose.RouteDestination
                overlay = Overlay.Search
            },
            // Same real launcher as inside the search screen: the home mic and the search-screen mic
            // are one feature, not two.
            onVoiceSearchClick = {
                searchPurpose = SearchPurpose.NewDestination
                voiceSearchRequest++
                overlay = Overlay.Search
            },
            onSettingsClick = { overlay = Overlay.Settings },
        )

        AnimatedVisibility(
            visible = state.selectedMapMode == MapMode.Live,
            enter = fadeIn(),
            exit = fadeOut(),
        ) {
            // Live only *reads* navigation: hudState is derived from the one MapViewModel, so
            // Map <-> Live never starts, stops or copies a trip.
            LiveScreen(
                viewModel = liveViewModel,
                hudState = LiveHud.state(state.navigationState, state.selectedDestination?.name),
                isNavigating = state.isNavigating,
                onModeSelected = viewModel::onModeSelected,
                // Phases 7/8 need the *same* route and navigation instances the map is showing, so
                // a reroute reaches the anchors and arrows through exactly one state update.
                route = (state.routeState as? RouteState.Ready)?.route,
                navigationState = state.navigationState,
                // Phase 9: the same ranked/capped list Map mode renders markers for.
                places = state.visiblePlaces,
                // Phase 10: the same road-intelligence state Map mode reads.
                roadIntelligenceState = state.roadIntelligenceState,
                // Phase 11: the same active environment profile Map mode's camera/places/alerts read.
                environmentProfile = state.activeProfile,
                voiceEnabled = state.voiceEnabled,
                onVoiceMuteToggled = viewModel::onVoiceMuteToggled,
                driverReportStatus = state.driverReportStatus,
                tripShareStatus = state.tripShareStatus,
                onReport = viewModel::onDriverReportRequested,
                onShareTrip = viewModel::onTripShareRequested,
                onDismissExternalStatus = viewModel::onExternalActionStatusDismissed,
                onReroute = viewModel::onManualRerouteRequested,
                onExitNavigation = viewModel::endNavigationToHome,
            )
        }

        AnimatedVisibility(
            visible = overlay == Overlay.Search,
            enter = fadeIn() + slideInVertically { it / 12 },
            exit = fadeOut() + slideOutVertically { it / 12 },
        ) {
            SearchScreen(
                viewModel = searchViewModel,
                onBack = { overlay = Overlay.None },
                onResultSelected = { result ->
                    when (searchPurpose) {
                        SearchPurpose.NewDestination -> viewModel.onDestinationSelected(result)
                        SearchPurpose.RouteOrigin -> viewModel.onRouteOriginSelected(result)
                        SearchPurpose.RouteDestination -> viewModel.onRouteDestinationSelected(result)
                    }
                    searchPurpose = SearchPurpose.NewDestination
                    overlay = Overlay.None
                },
                onCategorySelected = { category ->
                    // Categories are a discovery action, not a fabricated geocoding result.
                    // Reuse the existing Nearby pipeline with the selected category.
                    searchPurpose = SearchPurpose.NewDestination
                    overlay = Overlay.None
                    viewModel.onExploreCategoryClicked(category)
                },
                autoStartVoiceSearch = voiceSearchRequest > 0,
            )
        }

        AnimatedVisibility(
            visible = overlay == Overlay.Settings,
            enter = fadeIn() + slideInVertically { it / 12 },
            exit = fadeOut() + slideOutVertically { it / 12 },
        ) {
            SettingsScreen(
                permission = state.locationPermission,
                styleSource = styleSource,
                onBack = { overlay = Overlay.None },
                onOpenAppSettings = { context.openAppSettings() },
                voiceLanguage = state.voiceLanguage,
                onVoiceLanguageChanged = viewModel::onVoiceLanguageChanged,
            )
        }
    }
}

private tailrec fun Context.findActivity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> error("No Activity found in the Context chain")
}

private fun Context.openAppSettings() {
    startSafely(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
    )
}

private fun Context.shareText(title: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    startSafely(Intent.createChooser(send, title))
}

private fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // No handler on this device: nothing sensible to do, and we must not crash.
    }
}
