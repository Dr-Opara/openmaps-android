package com.openmaps.app.core.sharing

import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.UserLocation

data class TripShareRequest(
    val destination: SearchResult,
    val currentLocation: UserLocation,
    val expiresAtMillis: Long,
)

data class TripShareSession(
    val url: String,
    val expiresAtMillis: Long,
)

sealed class TripShareException(message: String) : Exception(message) {
    data object Unavailable : TripShareException("Live trip sharing provider is not configured")
}

/**
 * Provider-neutral temporary trip-sharing seam. Implementations must return an expiring session URL;
 * the navigation UI never owns tokens, persistence, or backend details.
 */
interface TripShareRepository {
    suspend fun createSession(request: TripShareRequest): Result<TripShareSession>
    suspend fun stopSession(session: TripShareSession): Result<Unit>
}

/** Safe default: no live location leaves the device until a secure backend is explicitly wired. */
class UnavailableTripShareRepository : TripShareRepository {
    override suspend fun createSession(request: TripShareRequest): Result<TripShareSession> =
        Result.failure(TripShareException.Unavailable)

    override suspend fun stopSession(session: TripShareSession): Result<Unit> =
        Result.failure(TripShareException.Unavailable)
}
