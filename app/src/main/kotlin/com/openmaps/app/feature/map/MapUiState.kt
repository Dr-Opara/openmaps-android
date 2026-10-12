package com.openmaps.app.feature.map

import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.LocationPermissionState
import com.openmaps.app.core.location.LocationStatus
import com.openmaps.app.core.location.UserLocation
import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.map.CameraState
import com.openmaps.app.map.MapError
import com.openmaps.app.map.MapMode

/**
 * Everything the Map screen renders. Deliberately free of MapLibre types.
 *
 * High-frequency camera data (the live bearing during a rotate gesture) is NOT in here;
 * it is exposed separately as `MapViewModel.compassBearing` so a rotating map does not
 * recompose the whole screen every frame.
 */
data class MapUiState(
    val currentLocation: UserLocation? = null,
    val locationPermission: LocationPermissionState = LocationPermissionState.Unknown,
    val locationStatus: LocationStatus = LocationStatus.Idle,
    /** Last settled camera (updated when the camera goes idle). Used to restore the view. */
    val cameraState: CameraState = CameraState.UnitedStates,
    val selectedMapMode: MapMode = MapMode.Map,
    val isMapReady: Boolean = false,
    val error: MapError? = null,
    /** The prompt the user dismissed, if any. Re-shows automatically if the situation changes. */
    val dismissedPrompt: LocationPrompt? = null,
    /** The destination marker shown on the map, or null when none is selected. */
    val selectedDestination: SearchResult? = null,
    /** The route to [selectedDestination], as one lifecycle value (see [RouteState]). */
    val routeState: RouteState = RouteState.Idle,
    /** Turn-by-turn navigation over [routeState]'s route (see [NavigationState]). */
    val navigationState: NavigationState = NavigationState.Idle,
    /**
     * True while the user has panned the camera away during a trip, so the banner can offer
     * recenter. Derived from engine events rather than stored separately, so it can never drift
     * out of sync with what actually happened.
     */
    val navigationFollowSuspended: Boolean = false,
) {
    /** The progress snapshot to render, or null when navigation isn't reporting one. */
    val navigationProgress: RouteProgress?
        get() = when (val state = navigationState) {
            is NavigationState.Active -> state.progress
            is NavigationState.OffRoute -> state.progress
            is NavigationState.Arrived -> state.progress
            NavigationState.Idle,
            NavigationState.Ready,
            NavigationState.Rerouting,
            is NavigationState.Error,
            -> null
        }

    /** True while a trip is underway: following, off route, rerouting, or arrived. */
    val isNavigating: Boolean
        get() = when (navigationState) {
            NavigationState.Idle, NavigationState.Ready, is NavigationState.Error -> false
            is NavigationState.Active, is NavigationState.OffRoute, NavigationState.Rerouting, is NavigationState.Arrived -> true
        }

    /** "Start" is offered once a route and a location fix both exist, and not mid-trip. */
    val canStartNavigation: Boolean
        get() = routeState is RouteState.Ready && currentLocation != null && navigationState is NavigationState.Idle

    val isLocationLoading: Boolean
        get() = locationStatus == LocationStatus.Loading

    /** The location card to show, or null when nothing needs the user's attention. */
    val locationPrompt: LocationPrompt?
        get() {
            val prompt = when (locationPermission) {
                LocationPermissionState.Unknown -> null
                LocationPermissionState.NotRequested -> LocationPrompt.AllowLocation
                LocationPermissionState.Denied -> LocationPrompt.Denied
                LocationPermissionState.PermanentlyDenied -> LocationPrompt.Blocked
                is LocationPermissionState.Granted -> when (locationStatus) {
                    LocationStatus.Loading -> LocationPrompt.Loading
                    LocationStatus.ServicesDisabled -> LocationPrompt.ServicesOff
                    LocationStatus.Unavailable -> LocationPrompt.Unavailable
                    LocationStatus.Idle, LocationStatus.Available -> null
                }
            }
            return if (prompt != null && prompt == dismissedPrompt && prompt != LocationPrompt.Loading) null else prompt
        }
}

/** One user-facing location situation. The UI maps each to copy and a primary action. */
enum class LocationPrompt {
    Loading,
    AllowLocation,
    Denied,
    Blocked,
    ServicesOff,
    Unavailable,
}

/** One-shot effects the UI must perform (they need an Activity/Context). */
sealed interface MapUiEvent {
    data object RequestLocationPermission : MapUiEvent
    data object OpenAppSettings : MapUiEvent
    data object OpenLocationSettings : MapUiEvent

    /**
     * A reroute attempted during navigation failed. The old route is still active and the user is
     * free to keep driving or correct course; this is a transient "we couldn't fix the route
     * right now" notice, not a dead end.
     */
    data object RerouteFailed : MapUiEvent
}
