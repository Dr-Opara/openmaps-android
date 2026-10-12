package com.openmaps.app.core.spatial

import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.Flow

/**
 * The seam between OpenMaps and spatial localization — same shape as `camera.RearCameraEngine` and
 * `map.MapEngine`. The app depends on this interface and on the provider-neutral types in
 * `SpatialModels.kt`; only the implementation under `spatial/arcore` imports `com.google.ar.core`.
 *
 * **Camera ownership.** This is the important architectural point. AR localization and the Phase 5
 * CameraX preview both want the rear camera, and the Android camera stack allows exactly one owner
 * at a time. The two engines therefore are *not* meant to run together: [ArCoreSpatialLocalization]
 * owns the camera whenever it is Tracking/Limited, and the CameraX preview engine is used only when
 * localization reports `Unsupported`/`NotInstalled`/`Failed` (see `LiveScreen`'s provider choice).
 * An [Implementation] advertises whether it renders its own preview so the UI never needs two
 * `RearCameraEngine`s bound at once.
 */
interface SpatialLocalizationEngine {

    /** Engine -> app state changes. */
    val state: Flow<SpatialLocalizationState>

    /**
     * Whether this implementation *can* provide the camera preview Live should display (an ARCore
     * engine renders its own camera image; a no-op engine never can).
     *
     * This is a **capability**, not a decision. The decision is [probeAvailability] plus the live
     * [state]: Live must not compose the AR surface merely because the engine *could* supply one —
     * doing so produced a black screen whenever AR then failed, because the CameraX fallback had
     * never been composed and there was nothing to fall back to.
     */
    val providesPreview: Boolean

    /**
     * Camera-free support probe. Must not open the camera or create a session, so Live can ask it
     * before choosing which engine owns the camera.
     */
    fun probeAvailability(): SpatialAvailability

    /**
     * Start localization against [lifecycleOwner]. Safe to call when already started (no-op).
     * Implementations MUST tie the underlying session/camera to the owner's lifecycle so background
     * the app closes the camera.
     */
    fun start(lifecycleOwner: LifecycleOwner)

    /** Stop and release everything. Idempotent; safe when never started. */
    fun stop()
}