package com.openmaps.app.core.sharing

import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.UserLocation

data class TripShareRequest(
    val destination: SearchResult,
    val currentLocation: UserLocation,
    val expiresAtMillis: Long,
    val remainingDistanceMeters: Double? = null,
    val remainingDurationSeconds: Double? = null,
)

data class TripShareSession(
    val sessionId: String,
    val ownerToken: String,
    val url: String,
    val expiresAtMillis: Long,
)

data class TripShareUpdate(
    val currentLocation: UserLocation,
    val remainingDistanceMeters: Double? = null,
    val remainingDurationSeconds: Double? = null,
    val arrived: Boolean = false,
)

sealed class TripShareException(message: String) : Exception(message) {
    data object Unavailable : TripShareException("Live trip sharing is unavailable")
    data object Expired : TripShareException("Live trip sharing session expired")
    data object Closed : TripShareException("Live trip sharing session is closed")
    data object Malformed : TripShareException("Malformed live trip sharing response")
}

/**
 * Provider-neutral temporary trip-sharing seam.
 *
 * The public share token exists only in [TripShareSession.url]. The private owner token is held
 * in-memory by the navigation session and authorizes update/stop operations; it is never embedded
 * in the public URL.
 */
interface TripShareRepository {
    suspend fun createSession(request: TripShareRequest): Result<TripShareSession>
    suspend fun updateSession(session: TripShareSession, update: TripShareUpdate): Result<Unit>
    suspend fun stopSession(session: TripShareSession): Result<Unit>
}

/** Safe default: no live location leaves the device until a secure backend is explicitly wired. */
class UnavailableTripShareRepository : TripShareRepository {
    override suspend fun createSession(request: TripShareRequest): Result<TripShareSession> =
        Result.failure(TripShareException.Unavailable)

    override suspend fun updateSession(session: TripShareSession, update: TripShareUpdate): Result<Unit> =
        Result.failure(TripShareException.Unavailable)

    override suspend fun stopSession(session: TripShareSession): Result<Unit> =
        Result.failure(TripShareException.Unavailable)
}
