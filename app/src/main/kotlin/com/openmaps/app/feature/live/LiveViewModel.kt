package com.openmaps.app.feature.live

import androidx.lifecycle.ViewModel
import com.openmaps.app.camera.CameraEngineEvent
import com.openmaps.app.camera.CameraFailure
import com.openmaps.app.camera.RearCameraEngine
import com.openmaps.app.camera.RearCameraState
import com.openmaps.app.core.camera.CameraPermissionState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow

/**
 * Owns Live-screen state: camera permission and camera health.
 *
 * **It does not own navigation.** Live has no `NavigationState` of its own — the app has one
 * `MapViewModel` and one `MapUiState.navigationState`, and Live reads it (see `LiveScreen`). That is
 * what makes Map -> Live -> Map free: there is nothing to copy, start, stop or tear down, because
 * Live never owned the trip in the first place. A second navigation session here would have been
 * the single easiest way to break navigation continuity, which is the whole point of this phase.
 *
 * Deliberately imports **no CameraX types** — only the `camera` package's engine-neutral
 * [RearCameraEngine]/[RearCameraState] — so this class is unit-testable on the JVM like
 * `MapViewModel`.
 */
class LiveViewModel(
    private val cameraEngine: RearCameraEngine? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveUiState())
    val uiState: StateFlow<LiveUiState> = _uiState.asStateFlow()

    private val eventChannel = Channel<LiveUiEvent>(Channel.BUFFERED)
    val events: Flow<LiveUiEvent> = eventChannel.receiveAsFlow()

    // region Inputs from the UI layer

    /** Called with the app's current view of the permission (on resume and after the dialog). */
    fun onCameraPermissionChanged(state: CameraPermissionState) {
        _uiState.update { it.copy(cameraPermission = state) }
    }

    /**
     * Forward camera-engine events. Unknown/failed states are collapsed into a single
     * [RearCameraState.Unavailable] carrying a classified [CameraFailure]; the UI never sees a raw
     * exception.
     */
    fun onCameraEvent(event: CameraEngineEvent) {
        val state = (event as? CameraEngineEvent.StateChanged)?.state ?: return
        _uiState.update { it.copy(cameraState = state) }
    }

    /**
     * Asks for camera permission. No-ops when already granted so the OS dialog is never
     * re-triggered from a screen the user already answered.
     */
    fun onRequestCameraPermission() {
        val current = _uiState.value.cameraPermission
        if (current == CameraPermissionState.Granted) return
        if (current == CameraPermissionState.PermanentlyDenied) {
            // The system dialog will not show again; only Settings can change this now.
            eventChannel.trySend(LiveUiEvent.OpenAppSettings)
            return
        }
        eventChannel.trySend(LiveUiEvent.RequestCameraPermission)
    }

    // endregion

    /** True when the camera should be started at all. */
    val canStartCamera: Boolean
        get() = _uiState.value.cameraPermission == CameraPermissionState.Granted && cameraEngine != null

    override fun onCleared() {
        eventChannel.close()
        cameraEngine?.release()
    }

    private inline fun MutableStateFlow<LiveUiState>.update(transform: (LiveUiState) -> LiveUiState) {
        value = transform(value)
    }
}

/** Everything the Live screen renders. Mirrors `MapUiState`'s "one state value" approach. */
data class LiveUiState(
    val cameraPermission: CameraPermissionState = CameraPermissionState.Unknown,
    val cameraState: RearCameraState = RearCameraState.Idle,
) {
    /** Permission granted but the camera could not start: show the failure, not a spinner. */
    val cameraFailure: CameraFailure?
        get() = (cameraState as? RearCameraState.Unavailable)?.failure

    /** The camera is doing its job (or has not been asked for yet). */
    val isCameraUsable: Boolean
        get() = cameraPermission == CameraPermissionState.Granted && cameraFailure == null
}

/** One-shot effects the Live screen must perform (they need an Activity/Context). */
sealed interface LiveUiEvent {
    data object RequestCameraPermission : LiveUiEvent
    data object OpenAppSettings : LiveUiEvent
}