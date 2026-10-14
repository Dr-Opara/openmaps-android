package com.openmaps.app.feature.live

import com.openmaps.app.core.camera.CameraPermissionState

/**
 * Whether Live's camera has resolved to usable, resolved to unusable, or is still waiting on a
 * decision (the permission dialog, most commonly) — section A7 of the Phase 11 brief. A three-way
 * signal rather than [LiveUiState.isCameraUsable]'s plain boolean on purpose: that property treats
 * "permission not yet asked" the same as "permission denied" (both read as "not usable *yet*"),
 * which is correct for gating the HUD but wrong for deciding whether to bounce back to Map — a
 * fresh `NotRequested`/`Unknown` permission must be given the normal request flow's chance to
 * resolve, not be treated as an immediate failure.
 */
enum class LiveReadiness {
    /** Still waiting on a decision (permission dialog in flight, or not yet evaluated). */
    Pending,
    /** Confirmed usable: permission granted, no camera failure. */
    Ready,
    /** A terminal negative: permission denied/blocked, or the camera itself failed. */
    Unusable,
}

/**
 * Maps [LiveUiState] to a [LiveReadiness] plus a short, concrete, user-facing reason for
 * [LiveReadiness.Unusable] — pure and testable without Compose or a real camera.
 */
object LiveReadinessMapper {
    fun of(state: LiveUiState): Pair<LiveReadiness, String?> = when {
        state.cameraPermission == CameraPermissionState.Denied ->
            LiveReadiness.Unusable to "Camera access denied — continuing in Map"
        state.cameraPermission == CameraPermissionState.PermanentlyDenied ->
            LiveReadiness.Unusable to "Camera access is blocked — continuing in Map"
        state.cameraFailure != null ->
            LiveReadiness.Unusable to "Camera unavailable — continuing in Map"
        state.cameraPermission == CameraPermissionState.Granted ->
            LiveReadiness.Ready to null
        else ->
            // Unknown / NotRequested: the normal permission-request flow is still in flight.
            LiveReadiness.Pending to null
    }
}
