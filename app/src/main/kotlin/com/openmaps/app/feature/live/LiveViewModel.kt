package com.openmaps.app.feature.live

import androidx.lifecycle.ViewModel
import com.openmaps.app.arrow.ARArrowPipeline
import com.openmaps.app.camera.CameraEngineEvent
import com.openmaps.app.core.anchor.RouteAnchorManager
import com.openmaps.app.camera.CameraFailure
import com.openmaps.app.camera.RearCameraEngine
import com.openmaps.app.camera.RearCameraState
import com.openmaps.app.core.camera.CameraPermissionState
import com.openmaps.app.core.spatial.NoopSpatialLocalization
import com.openmaps.app.core.spatial.SpatialAvailability
import com.openmaps.app.core.spatial.SpatialLocalizationEngine
import com.openmaps.app.core.spatial.SpatialLocalizationState
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
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
    /**
     * Phase 6. Defaults to a no-op so Live (and every existing test) keeps working with no AR at
     * all; the real ARCore engine is supplied by [AppContainer] once support is confirmed.
     */
    private val spatialEngine: SpatialLocalizationEngine = NoopSpatialLocalization(),
) : ViewModel() {

    private val _uiState = MutableStateFlow(LiveUiState())
    val uiState: StateFlow<LiveUiState> = _uiState.asStateFlow()

    /** The active spatial-localization engine, so the screen knows whether to use its GL preview. */
    val spatialLocalization: SpatialLocalizationEngine get() = spatialEngine

    /**
     * Which engine owns the camera and renders Live's preview.
     *
     * This is **state, not a type check**, and that is the whole point. Selecting the provider from
     * `spatialEngine.providesPreview` alone (an earlier revision) meant that whenever AR was selected
     * and then failed, the CameraX fallback had never been composed — Live rendered the AR surface,
     * AR failed, and the user got a black screen with no camera at all. Deciding from a camera-free
     * [SpatialLocalizationEngine.probeAvailability] first, and re-deciding on failure, keeps Live
     * usable in every case.
     *
     * Exactly one engine is true here at any moment: ARCore and CameraX both want the rear camera,
     * and the Android camera stack allows only one owner.
     */
    private val _previewProvider = MutableStateFlow(chooseInitialProvider())
    val previewProvider: StateFlow<PreviewProvider> = _previewProvider.asStateFlow()

    /** True when the AR engine owns the camera and renders the preview itself. */
    val spatialProvidesPreview: Boolean get() = _previewProvider.value == PreviewProvider.Spatial

    /**
     * Camera-free probe, asked once at construction. Anything other than
     * [SpatialAvailability.Supported] means the CameraX fallback is used from the very first frame.
     */
    private fun chooseInitialProvider(): PreviewProvider =
        if (spatialEngine.providesPreview &&
            spatialEngine.probeAvailability() == SpatialAvailability.Supported
        ) {
            PreviewProvider.Spatial
        } else {
            PreviewProvider.CameraX
        }

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

    // region Spatial localization (Phase 6)

    /**
     * Starts AR localization when the camera is granted and AR can actually run. Safe to call
     * repeatedly; the engine no-ops when already started. When AR cannot run this is a no-op and
     * Live simply continues on the CameraX preview — localization is additive and never required.
     */
    fun startSpatialLocalization(lifecycleOwner: LifecycleOwner) {
        if (!canStartCamera) return
        spatialEngine.start(lifecycleOwner)
        // Watch for AR dying mid-session and hand the camera back to the CameraX fallback. Without
        // this, a session that fails *after* a successful probe leaves Live with no camera at all.
        if (spatialFallbackJob == null && _previewProvider.value == PreviewProvider.Spatial) {
            spatialFallbackJob = viewModelScope.launch {
                spatialEngine.state.collect { state ->
                    if (state is SpatialLocalizationState.Failed ||
                        state is SpatialLocalizationState.Unsupported ||
                        state is SpatialLocalizationState.NotInstalled
                    ) {
                        spatialEngine.stop()
                        _previewProvider.value = PreviewProvider.CameraX
                    }
                }
            }
        }
    }

    /** Stops and releases AR localization. Idempotent. */
    fun stopSpatialLocalization() {
        spatialFallbackJob?.cancel()
        spatialFallbackJob = null
        spatialEngine.stop()
        // Never leave anchors or arrows alive over a camera that is no longer being driven.
        routeAnchorManager?.clear()
        arrowPipeline.clear()
    }

    private var spatialFallbackJob: kotlinx.coroutines.Job? = null

    // endregion

    // region AR anchoring + chevrons (Phases 7/8)

    /**
     * The AR chevron pipeline. Additive and self-contained: if it yields nothing, Live is unchanged.
     *
     * Owned here rather than in the composable so it outlives Live composition and can be cleared
     * explicitly on the moments stale arrows would otherwise linger — arrival, navigation stop, and
     * route replacement.
     */
    val arrowPipeline: ARArrowPipeline = ARArrowPipeline()

    /**
     * Phase 7 spatial anchors: the world-space route control points arrows are placed against.
     * Null when no backend was supplied (non-AR devices, unit tests), in which case Phase 8 falls
     * back to per-frame geographic projection and reports it via `arrowPipeline.usingAnchors = false`.
     */
    var routeAnchorManager: RouteAnchorManager? = null

    /**
     * A reroute replaces the route that both the anchors and the arrows were derived from, so both
     * sets are dropped before the new one is produced. Deliberately does **not** restart the AR
     * session: the camera and the pose remain valid, and rebuilding mid-drive would flicker the
     * preview and risk a camera handoff.
     */
    fun onRouteReplaced() {
        routeAnchorManager?.clear()
        arrowPipeline.onRouteReplaced()
    }

    /** End of trip: release anchors and remove every arrow. */
    fun onNavigationEnded() {
        routeAnchorManager?.clear()
        arrowPipeline.clear()
    }

    // endregion

    /** True when the camera should be started at all. */
    val canStartCamera: Boolean
        get() = _uiState.value.cameraPermission == CameraPermissionState.Granted && cameraEngine != null

    override fun onCleared() {
        eventChannel.close()
        spatialEngine.stop()
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

/**
 * Which engine currently owns the rear camera and renders Live's preview. Exactly one at a time —
 * see [LiveViewModel.previewProvider] for why this cannot be derived from the engine type.
 */
enum class PreviewProvider {
    /** ARCore: owns the camera, renders its own preview, and localizes while it does. */
    Spatial,

    /** CameraX (Phase 5): plain preview, no AR. The fallback whenever AR cannot run. */
    CameraX,
}