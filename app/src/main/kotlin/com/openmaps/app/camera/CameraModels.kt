package com.openmaps.app.camera

/**
 * Engine-neutral rear-camera types. Deliberately free of CameraX types, mirroring how
 * `map/MapModels.kt` keeps MapLibre out of everything except `MapLibreMapEngine`.
 *
 * The split exists for the same reason it does for the map: navigation logic must be able to ask
 * "is the camera working?" without importing CameraX, and so the HUD, the ViewModel and the tests
 * stay runnable on the JVM with no camera hardware and no Android framework.
 */

/** Why the rear camera is not currently showing an image. */
enum class CameraFailure {
    /** The device reports no back-facing camera at all. */
    NoRearCamera,

    /** CameraX could not bind a use case (permission revoked mid-bind, hardware busy, ...). */
    BindingFailed,

    /** Another app or the OS holds the camera. Common when returning from another camera app. */
    InUse,

    /** Anything else CameraX reported that we have not classified. Never a crash. */
    Unknown,
}

/**
 * The rear camera's current condition. [Ready] means frames are being delivered.
 *
 * This is deliberately a plain value the ViewModel mirrors into state, rather than something the
 * UI infers from "the PreviewView exists": the two disagree exactly when things are broken, and
 * that disagreement is what the user needs to be told about.
 */
sealed interface RearCameraState {
    /** No camera requested (Map mode, or Live not composed). */
    data object Idle : RearCameraState

    /** Binding in progress. */
    data object Starting : RearCameraState

    /** Frames are flowing. */
    data object Ready : RearCameraState

    data class Unavailable(val failure: CameraFailure) : RearCameraState
}

/** Events from the camera engine to the ViewModel. */
sealed interface CameraEngineEvent {
    data class StateChanged(val state: RearCameraState) : CameraEngineEvent
}

/**
 * The seam between OpenMaps and the camera, deliberately the same shape as `map.MapEngine`: the app
 * depends on this interface and on engine-neutral types; only `CameraXRearEngine` imports CameraX.
 *
 * Scope is **preview only**. There is no `ImageAnalysis` case here on purpose — Phase 6 adds AR
 * localisation, and adding an analyzer now would mean every preview frame ran through an analyzer
 * for nothing. [release] must be safe to call repeatedly and when nothing is bound.
 */
interface RearCameraEngine {
    /** Engine -> app events. */
    val events: kotlinx.coroutines.flow.Flow<CameraEngineEvent>

    /** Creates the preview view exactly once per engine instance. Safe to hand to AndroidView. */
    fun createView(context: android.content.Context): android.view.View

    /** Bind the rear camera to [lifecycleOwner]. A no-op when already bound. */
    fun bind(lifecycleOwner: androidx.lifecycle.LifecycleOwner)

    /** Unbind and release the camera. Idempotent; safe to call when never bound. */
    fun release()
}