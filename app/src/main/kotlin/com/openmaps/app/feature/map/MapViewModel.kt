package com.openmaps.app.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.LocationPermissionState
import com.openmaps.app.core.location.LocationRepository
import com.openmaps.app.core.location.LocationStatus
import com.openmaps.app.core.location.LocationUpdate
import com.openmaps.app.core.location.UserLocation
import com.openmaps.app.core.location.isGranted
import com.openmaps.app.map.CameraState
import com.openmaps.app.map.MapCommand
import com.openmaps.app.map.MapError
import com.openmaps.app.map.MapEvent
import com.openmaps.app.map.MapMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Owns Map-screen state and all camera/location policy. Contains no Android framework
 * and no MapLibre types: the engine is driven through [commands] and reports back through
 * [onMapEvent].
 *
 * Camera policy (Phase 1):
 *  - The first location fix centers the map once, unless the user already moved it.
 *  - Later fixes only move the blue dot, never the camera.
 *  - [onRecenterClicked] is the only way the camera returns to the user afterwards.
 */
class MapViewModel(
    private val locationRepository: LocationRepository,
    private val fixTimeoutMillis: Long = DEFAULT_FIX_TIMEOUT_MILLIS,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    /** Live bearing in degrees [-180, 180); separate from [uiState] to avoid per-frame recomposition. */
    private val _compassBearing = MutableStateFlow(0f)
    val compassBearing: StateFlow<Float> = _compassBearing.asStateFlow()

    private val commandChannel = Channel<MapCommand>(Channel.BUFFERED)
    val commands: Flow<MapCommand> = commandChannel.receiveAsFlow()

    private val eventChannel = Channel<MapUiEvent>(Channel.BUFFERED)
    val events: Flow<MapUiEvent> = eventChannel.receiveAsFlow()

    private var locationJob: Job? = null
    private var timeoutJob: Job? = null
    private var isAppVisible = false
    private var autoCenterHandled = false
    private var userHasMovedCamera = false
    private var autoPromptEmitted = false

    // region Inputs from the UI layer

    /** Called with the app's current view of the permission (on resume and after the dialog). */
    fun onPermissionStateChanged(state: LocationPermissionState) {
        if (_uiState.value.locationPermission == state) return
        _uiState.update {
            val next = it.copy(locationPermission = state)
            if (state.isGranted) next else next.copy(currentLocation = null, locationStatus = LocationStatus.Idle)
        }
        if (state == LocationPermissionState.NotRequested && !autoPromptEmitted) {
            autoPromptEmitted = true
            eventChannel.trySend(MapUiEvent.RequestLocationPermission)
        }
        syncLocationCollection()
    }

    /** GPS/network listeners run only while the app is visible (battery and privacy). */
    fun onAppVisibilityChanged(visible: Boolean) {
        isAppVisible = visible
        syncLocationCollection()
    }

    fun onMapEvent(event: MapEvent) {
        when (event) {
            MapEvent.StyleLoaded -> _uiState.update { it.copy(isMapReady = true, error = null) }
            is MapEvent.StyleFailed -> _uiState.update {
                it.copy(isMapReady = false, error = MapError.Unavailable(event.message))
            }
            MapEvent.UserGestureStarted -> userHasMovedCamera = true
            is MapEvent.BearingChanged -> _compassBearing.value = normalizeBearing(event.bearingDegrees)
            is MapEvent.CameraIdle -> {
                _compassBearing.value = normalizeBearing(event.camera.bearing)
                _uiState.update { if (it.cameraState == event.camera) it else it.copy(cameraState = event.camera) }
            }
        }
    }

    fun onModeSelected(mode: MapMode) {
        _uiState.update { it.copy(selectedMapMode = mode) }
    }

    fun onRecenterClicked() {
        val state = _uiState.value
        val location = state.currentLocation
        if (location == null) {
            // No fix to fly to: fix whatever is blocking one (permission, GPS off, retry).
            onLocationPromptAction()
            return
        }
        val camera = state.cameraState
        moveCamera(
            CameraState(
                latitude = location.latitude,
                longitude = location.longitude,
                zoom = max(camera.zoom, RECENTER_MIN_ZOOM),
                bearing = camera.bearing,
                pitch = camera.pitch,
            ),
            durationMillis = 700,
        )
    }

    fun onCompassClicked() {
        moveCamera(_uiState.value.cameraState.copy(bearing = 0.0), durationMillis = 400)
    }

    fun onRetryMap() {
        _uiState.update { it.copy(error = null, isMapReady = false) }
        commandChannel.trySend(MapCommand.RetryStyle)
    }

    /** The primary button of the location card, or the recenter button when there is no fix. */
    fun onLocationPromptAction() {
        val state = _uiState.value
        when (state.locationPermission) {
            LocationPermissionState.Unknown -> Unit
            LocationPermissionState.NotRequested,
            LocationPermissionState.Denied -> eventChannel.trySend(MapUiEvent.RequestLocationPermission)
            LocationPermissionState.PermanentlyDenied -> eventChannel.trySend(MapUiEvent.OpenAppSettings)
            is LocationPermissionState.Granted -> {
                when (state.locationStatus) {
                    LocationStatus.ServicesDisabled -> eventChannel.trySend(MapUiEvent.OpenLocationSettings)
                    LocationStatus.Unavailable,
                    LocationStatus.Loading,
                    LocationStatus.Idle -> restartLocationCollection()
                    LocationStatus.Available -> Unit
                }
            }
        }
    }

    fun onDismissLocationPrompt() {
        _uiState.update { it.copy(dismissedPrompt = it.locationPrompt) }
    }

    /** A search result (fresh or recent) was picked: show its marker and fly the camera to it. */
    fun onDestinationSelected(result: SearchResult) {
        _uiState.update { it.copy(selectedDestination = result) }
        moveCamera(CameraState(result.latitude, result.longitude, DESTINATION_ZOOM), durationMillis = 900)
    }

    /** Clears the destination marker and summary. */
    fun onDestinationCleared() {
        _uiState.update { it.copy(selectedDestination = null) }
    }

    // endregion

    // region Location pipeline

    private fun syncLocationCollection() {
        if (_uiState.value.locationPermission.isGranted && isAppVisible) {
            if (locationJob?.isActive != true) startLocationCollection()
        } else {
            stopLocationCollection()
        }
    }

    private fun restartLocationCollection() {
        locationJob?.cancel()
        locationJob = null
        syncLocationCollection()
    }

    private fun startLocationCollection() {
        _uiState.update {
            it.copy(locationStatus = if (it.currentLocation != null) LocationStatus.Available else LocationStatus.Loading)
        }
        if (_uiState.value.locationStatus == LocationStatus.Loading) armFixTimeout()
        locationJob = viewModelScope.launch {
            locationRepository.updates()
                .catch { onLocationUpdate(LocationUpdate.Failure(it)) }
                .collect { onLocationUpdate(it) }
        }
    }

    private fun stopLocationCollection() {
        locationJob?.cancel()
        locationJob = null
        timeoutJob?.cancel()
        timeoutJob = null
        _uiState.update {
            it.copy(locationStatus = if (it.currentLocation != null) LocationStatus.Available else LocationStatus.Idle)
        }
    }

    private fun armFixTimeout() {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(fixTimeoutMillis)
            _uiState.update {
                if (it.locationStatus == LocationStatus.Loading) it.copy(locationStatus = LocationStatus.Unavailable) else it
            }
        }
    }

    private fun onLocationUpdate(update: LocationUpdate) {
        when (update) {
            is LocationUpdate.Fix -> {
                timeoutJob?.cancel()
                _uiState.update { it.copy(currentLocation = update.location, locationStatus = LocationStatus.Available) }
                centerOnFirstFix(update.location)
            }
            LocationUpdate.ServicesDisabled -> {
                timeoutJob?.cancel()
                _uiState.update { it.copy(currentLocation = null, locationStatus = LocationStatus.ServicesDisabled) }
            }
            LocationUpdate.ServicesEnabled -> {
                if (_uiState.value.locationStatus != LocationStatus.Available) {
                    _uiState.update { it.copy(locationStatus = LocationStatus.Loading) }
                    armFixTimeout()
                }
            }
            is LocationUpdate.Failure -> {
                timeoutJob?.cancel()
                _uiState.update { it.copy(locationStatus = LocationStatus.Unavailable) }
            }
        }
    }

    private fun centerOnFirstFix(location: UserLocation) {
        if (autoCenterHandled) return
        autoCenterHandled = true
        // Respect a user who already started exploring before the first fix arrived.
        if (userHasMovedCamera) return
        moveCamera(
            CameraState(location.latitude, location.longitude, INITIAL_USER_ZOOM),
            durationMillis = 1400,
        )
    }

    // endregion

    private fun moveCamera(target: CameraState, durationMillis: Int) {
        // Optimistic update so a configuration change mid-animation restores the destination.
        _uiState.update { it.copy(cameraState = target) }
        commandChannel.trySend(MapCommand.MoveCamera(target, animated = true, durationMillis = durationMillis))
    }

    override fun onCleared() {
        commandChannel.close()
        eventChannel.close()
    }

    companion object {
        const val DEFAULT_FIX_TIMEOUT_MILLIS = 20_000L
        const val INITIAL_USER_ZOOM = 15.0
        const val RECENTER_MIN_ZOOM = 14.0
        const val DESTINATION_ZOOM = 15.0

        /** Maps any bearing to [-180, 180) so "rotated" checks and the compass needle are symmetric. */
        fun normalizeBearing(degrees: Double): Float = (((degrees % 360.0) + 540.0) % 360.0 - 180.0).toFloat()
    }
}
