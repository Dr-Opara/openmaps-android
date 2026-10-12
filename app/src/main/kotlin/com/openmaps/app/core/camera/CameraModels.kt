package com.openmaps.app.core.camera

/**
 * Runtime camera permission as *the app* understands it.
 *
 * Deliberately the same shape as [com.openmaps.app.core.location.LocationPermissionState] rather
 * than something camera-specific, so the Live screen's permission UI and the Map screen's
 * location UI read the same way and `LiveViewModel` mirrors `MapViewModel`'s structure.
 */
sealed interface CameraPermissionState {
    /** The UI has not reported the permission yet (first frames after opening Live). */
    data object Unknown : CameraPermissionState

    /** The OS dialog has never been shown. */
    data object NotRequested : CameraPermissionState

    data object Granted : CameraPermissionState

    /** Denied, but the system will still show the dialog again. */
    data object Denied : CameraPermissionState

    /** Denied with "don't ask again" (or denied twice). Only system settings can fix it. */
    data object PermanentlyDenied : CameraPermissionState
}