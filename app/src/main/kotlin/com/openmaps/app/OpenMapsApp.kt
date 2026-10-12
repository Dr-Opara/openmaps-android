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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.openmaps.app.feature.live.LiveScreen
import com.openmaps.app.feature.live.LiveUiEvent
import com.openmaps.app.feature.live.LiveViewModel
import com.openmaps.app.feature.live.liveViewModelFactory
import com.openmaps.app.feature.map.MapScreen
import com.openmaps.app.feature.map.MapUiEvent
import com.openmaps.app.feature.map.MapViewModel
import com.openmaps.app.feature.map.mapViewModelFactory
import com.openmaps.app.feature.search.SearchScreen
import com.openmaps.app.feature.search.SearchViewModel
import com.openmaps.app.feature.search.searchViewModelFactory
import com.openmaps.app.feature.settings.SettingsScreen
import com.openmaps.app.feature.splash.SplashScreen
import com.openmaps.app.map.MapMode

private enum class Overlay { None, Search, Settings }

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
    var overlay by rememberSaveable { mutableStateOf(Overlay.None) }

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

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                MapUiEvent.RequestLocationPermission -> {
                    prefs.markRequested()
                    permissionLauncher.launch(LocationPermissions.REQUEST)
                }
                MapUiEvent.OpenAppSettings -> context.openAppSettings()
                MapUiEvent.OpenLocationSettings -> context.startSafely(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
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
            onSearchClick = { overlay = Overlay.Search },
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
                    viewModel.onDestinationSelected(result)
                    overlay = Overlay.None
                },
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

private fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        // No handler on this device: nothing sensible to do, and we must not crash.
    }
}
