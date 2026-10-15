package com.openmaps.app.core.spatial

import com.openmaps.app.core.routing.Coordinate

/**
 * Provider-neutral spatial-localization types. Deliberately free of ARCore types, mirroring how
 * `camera/CameraModels.kt` keeps CameraX out and `map/MapModels.kt` keeps MapLibre out: navigation,
 * the HUD and the tests stay runnable on the JVM with no AR hardware.
 *
 * ARCore's `Session`, `Earth`, `Pose`, `TrackingState` and accuracy types never cross this boundary.
 */

/**
 * How much the user can trust the current fix. Computed from real accuracy values, not from ARCore's
 * coarse tracking states, because "TRACKING" says nothing about *whether the heading is usable*.
 */
enum class LocalizationQuality {
    /** Nothing usable yet (no fix, or accuracies too large to mean anything). */
    Unavailable,

    /** Fix exists but is still settling (cold start, or just regained sky view). */
    Acquiring,

    /** Usable for "you are here", too noisy to steer by. */
    Poor,

    /** Good enough for navigation guidance. */
    Fair,

    /** Heading and position are tight enough for AR anchoring in later phases. */
    Good,
}

/**
 * A single localization fix, provider-neutral.
 *
 * [headingDegrees] is a **device-facing** direction (0 = true north, clockwise), which is distinct
 * from the GPS movement bearing in `UserLocation.bearingDegrees` — the former is where the phone is
 * pointed, the latter is which way it is moving. Phase 7 needs the former for AR and the latter for
 * "continue on this road", and conflating them is a classic source of guidance that reads wrong at
 * every stop sign.
 */
data class SpatialPose(
    val latitude: Double,
    val longitude: Double,
    /** Metres above the WGS84 ellipsoid; null when ARCore does not report it. */
    val altitudeMeters: Double?,
    /** Device-facing heading in degrees clockwise from true north, [0, 360). */
    val headingDegrees: Double,
    /** Metres. Null when the provider does not report it. */
    val horizontalAccuracyMeters: Double?,
    val verticalAccuracyMeters: Double?,
    val headingAccuracyDegrees: Double?,
    val quality: LocalizationQuality,
) {
    val coordinate: Coordinate get() = Coordinate(latitude, longitude)

    /** True when quality is good enough to anchor AR content to a world point. */
    val isAnchorable: Boolean get() = quality == LocalizationQuality.Good
}

/**
 * The spatial-localization lifecycle. Ordered roughly by "how close to working".
 */
sealed interface SpatialLocalizationState {
    /** Not started (Live closed, or AR not requested yet). */
    data object Idle : SpatialLocalizationState

    /** This device cannot run ARCore at all. Not fixable by the user. */
    data object Unsupported : SpatialLocalizationState

    /** ARCore is supported but the app/service is missing or too old. Usually a Play Store prompt. */
    data class NotInstalled(val requestInstall: Boolean = true) : SpatialLocalizationState

    /** Camera permission for the AR session is not granted. */
    data object PermissionRequired : SpatialLocalizationState

    /** Session being created / Earth being initialized. */
    data object Initializing : SpatialLocalizationState

    /**
     * Producing fixes. [pose] may be null for a moment after this state is entered — ARCore reports
     * TRACKING before the first geospatial pose has arrived, and pretending otherwise would show a
     * confident "Ready" over a map position of (0, 0).
     */
    data class Tracking(val pose: SpatialPose?) : SpatialLocalizationState

    /** Tracking, but degraded: too few features, bad lighting, weak GPS. Still usually usable. */
    data class Limited(val reason: LimitedReason) : SpatialLocalizationState

    /** Failed unrecoverably for now; Live falls back. */
    data class Failed(val reason: SpatialFailure) : SpatialLocalizationState
}

/** Why tracking is degraded. Provider-neutral; mapped from ARCore's TrackingState + camera state. */
enum class LimitedReason {
    /** Running, but has not accumulated enough motion/features to be confident. */
    Initializing,

    /** Too few features in view (featureless wall, blank sky). */
    InsufficientFeatures,

    /** Too much motion / blur to localise. */
    ExcessiveMotion,

    /** Weak or absent GPS, so the Earth pose is poorly constrained. */
    PoorGeolocation,

    /** Camera is tracking but `session.earth` has not resolved yet (not the same as a poor fix). */
    WaitingForEarth,

    /** `Earth.getTrackingState()` is PAUSED — distinct from `EarthState`, which can read ENABLED
     * while the Earth pose itself is not currently being tracked (section A4). */
    EarthPaused,
}

/** Why localization failed. */
enum class SpatialFailure {
    /** The AR session could not be created at all. */
    SessionCreationFailed,

    /** ARCore reported an unrecoverable error for this session. */
    ArCoreError,

    /** Earth could not be resolved (`Earth.EarthState.ERROR_NOT_AUTHORIZED`). */
    EarthUnavailable,

    /** `Earth.EarthState.ERROR_GEOSPATIAL_MODE_DISABLED` — distinct from [EarthUnavailable] so the
     * debug overlay can name the exact ARCore condition instead of one generic bucket. */
    GeospatialDisabled,

    /** This device/ARCore version does not support Geospatial mode at all
     * (`Session.isGeospatialModeSupported` returned false) — checked before `configure()` so this
     * never falls through to the generic [SessionCreationFailed]. */
    GeospatialUnsupported,

    /** Camera permission was revoked or the camera is held by another app. */
    CameraUnavailable,
}

/**
 * Whether spatial localization can run **at all** on this device — answered by a camera-free probe
 * (`ArCoreApk.checkAvailability`), so it can be asked *before* any camera is opened.
 *
 * This exists to solve the one problem Phase 6's camera architecture creates: something must decide
 * whether ARCore or CameraX owns the camera, and that decision must be made without opening either.
 * Deciding from the engine's *type* (`providesPreview`) instead is what made an early revision show
 * a black screen whenever AR failed after being selected — the fallback camera had never been
 * composed, so there was nothing to fall back *to*.
 */
enum class SpatialAvailability {
    /** ARCore can run here; it may be used to localize and to render the preview. */
    Supported,

    /** This device cannot run ARCore. Not fixable by the user. */
    Unsupported,

    /** Supported in principle, but the ARCore service is missing or too old (install/update). */
    NotInstalled,

    /**
     * `ArCoreApk.checkAvailability()` returned one of the `UNKNOWN_*` values (`UNKNOWN_CHECKING`,
     * `UNKNOWN_TIMED_OUT`, `UNKNOWN_ERROR`) — Google's own guidance is that these are transient and
     * should be re-queried, **not** treated as a permanent answer. Collapsing this into [Unsupported]
     * (an earlier revision) is what let a single unlucky first probe permanently strand Live on the
     * CameraX fallback with AR never even attempted — see `LiveViewModel.chooseInitialProvider`.
     */
    Checking,
}