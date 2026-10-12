package com.openmaps.app.core.location

import kotlinx.coroutines.flow.Flow

/** Events produced by the location layer while [LocationRepository.updates] is collected. */
sealed interface LocationUpdate {
    data class Fix(val location: UserLocation) : LocationUpdate

    /** Device location services are off. Emitted first if they are off at subscribe time. */
    data object ServicesDisabled : LocationUpdate

    /** Device location services are on; a fix may still be pending. */
    data object ServicesEnabled : LocationUpdate

    /** The provider failed (for example the permission was revoked while collecting). */
    data class Failure(val cause: Throwable? = null) : LocationUpdate
}

/**
 * Phase 0 location layer. Implementations must:
 *  - start listening only while the returned flow is collected, and stop on cancel;
 *  - never throw for missing permission/provider; report [LocationUpdate.Failure] instead.
 * The caller (MapViewModel) only collects while permission is granted and the app is visible.
 */
interface LocationRepository {
    fun updates(): Flow<LocationUpdate>
}
