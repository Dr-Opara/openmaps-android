package com.openmaps.app.feature.map

import com.openmaps.app.core.location.LocationPermissionState
import com.openmaps.app.core.location.LocationStatus
import com.openmaps.app.core.location.UserLocation
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
) {
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
}
