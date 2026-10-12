package com.openmaps.app.core.spatial

import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [SpatialLocalizationEngine] that cannot localize.
 *
 * This exists so "AR is unavailable" is a *value* the rest of the app can handle normally rather
 * than a null it has to special-case everywhere — the same reason `RouteState.Error` and
 * `CameraState.Unavailable` are modeled instead of thrown. It is what Live uses when ARCore is
 * unsupported, missing, or fails, and what the JVM unit tests use instead of an ARCore device.
 *
 * [state] is fixed and never changes, so a caller can branch on it once and forget about it.
 */
class NoopSpatialLocalization(
    private val reason: SpatialLocalizationState = SpatialLocalizationState.Unsupported,
) : SpatialLocalizationEngine {

    private val _state = MutableStateFlow(reason)

    override val state: StateFlow<SpatialLocalizationState> = _state.asStateFlow()

    /** No camera, so Live must keep using the CameraX preview. */
    override val providesPreview: Boolean = false

    /**
     * Never available. Camera-free by construction: this engine never touches a camera, so Live's
     * provider choice always lands on the CameraX fallback when one is in use.
     */
    override fun probeAvailability(): SpatialAvailability = when (reason) {
        is SpatialLocalizationState.NotInstalled -> SpatialAvailability.NotInstalled
        else -> SpatialAvailability.Unsupported
    }

    override fun start(lifecycleOwner: LifecycleOwner) = Unit

    override fun stop() = Unit
}