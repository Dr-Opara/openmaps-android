package com.openmaps.app.core.location

/** A single position fix. Pure Kotlin: no Android types leak past the location layer. */
data class UserLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float? = null,
    val bearingDegrees: Float? = null,
    val timestampMillis: Long = 0L,
    /**
     * A direct speed reading from the location provider (Phase 10's "current speed"; see
     * `AndroidLocationRepository.toUserLocation`), in metres/second. Null when the provider has not
     * reported one — never derived from consecutive fixes here: a distance/time estimate from two
     * noisy GPS points is a materially different (and materially worse) number than a direct reading,
     * and section 8 of the Phase 10 brief explicitly asks for the real reading when one exists rather
     * than fabricating one from unreliable samples.
     */
    val speedMetersPerSecond: Float? = null,
)

/**
 * Runtime location permission as the *app* understands it.
 *
 * [NotRequested] vs [PermanentlyDenied] cannot be told apart from the OS alone
 * (both report "no grant, no rationale"), so the UI layer combines the OS state
 * with a persisted "we already asked" flag. See `LocationPermissions`.
 */
sealed interface LocationPermissionState {
    /** The UI has not reported the permission yet (first frames after launch). */
    data object Unknown : LocationPermissionState
    data object NotRequested : LocationPermissionState
    data class Granted(val precise: Boolean) : LocationPermissionState
    /** Denied, but the system will still show the dialog again. */
    data object Denied : LocationPermissionState
    /** Denied with "don't ask again" (or denied twice). Only system settings can fix it. */
    data object PermanentlyDenied : LocationPermissionState
}

val LocationPermissionState.isGranted: Boolean
    get() = this is LocationPermissionState.Granted

/** What the location pipeline is doing once permission is granted. */
enum class LocationStatus {
    /** Not collecting (no permission, or app not visible). */
    Idle,
    /** Collecting, waiting for the first fix. */
    Loading,
    /** A usable fix is available. */
    Available,
    /** Device location services (GPS/network location) are switched off. */
    ServicesDisabled,
    /** Services are on but no fix arrived in time, or the provider failed. */
    Unavailable,
}
