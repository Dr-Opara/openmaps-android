package com.openmaps.app.core.sharing

import com.openmaps.app.core.location.UserLocation
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/**
 * Production live-trip sharing client.
 *
 * The backend owns persistence and public share tokens. Android receives a separate private owner
 * capability for update/stop calls and keeps it only in memory for the active navigation session.
 */
class BackendTripShareRepository(
    private val endpointUrl: String,
    private val publishableKey: String,
    private val clientId: String? = null,
) : TripShareRepository {

    override suspend fun createSession(request: TripShareRequest): Result<TripShareSession> =
        ioResult {
            val body = JSONObject()
                .put("action", "create")
                .put(
                    "destination",
                    JSONObject()
                        .put("name", request.destination.name)
                        .put("latitude", request.destination.latitude)
                        .put("longitude", request.destination.longitude),
                )
                .put("currentLocation", request.currentLocation.toJson())
                .put("expiresAtMillis", request.expiresAtMillis)
                .putNullable("remainingDistanceMeters", request.remainingDistanceMeters)
                .putNullable("remainingDurationSeconds", request.remainingDurationSeconds)

            val response = post(body)
            TripShareSession(
                sessionId = response.getString("sessionId"),
                ownerToken = response.getString("ownerToken"),
                url = response.getString("url"),
                expiresAtMillis = response.getLong("expiresAtMillis"),
            )
        }

    override suspend fun updateSession(
        session: TripShareSession,
        update: TripShareUpdate,
    ): Result<Unit> = ioResult {
        val body = JSONObject()
            .put("action", "update")
            .put("sessionId", session.sessionId)
            .put("ownerToken", session.ownerToken)
            .put("currentLocation", update.currentLocation.toJson())
            .putNullable("remainingDistanceMeters", update.remainingDistanceMeters)
            .putNullable("remainingDurationSeconds", update.remainingDurationSeconds)
            .put("arrived", update.arrived)
        post(body)
        Unit
    }

    override suspend fun stopSession(session: TripShareSession): Result<Unit> = ioResult {
        post(
            JSONObject()
                .put("action", "stop")
                .put("sessionId", session.sessionId)
                .put("ownerToken", session.ownerToken),
        )
        Unit
    }

    private suspend fun <T> ioResult(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            Result.failure(TripShareException.Unavailable)
        } catch (e: JSONException) {
            Result.failure(TripShareException.Malformed)
        } catch (e: TripShareException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(TripShareException.Unavailable)
        }
    }

    private fun post(body: JSONObject): JSONObject {
        if (endpointUrl.isBlank() || publishableKey.isBlank()) throw TripShareException.Unavailable
        val connection = URL(endpointUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("apikey", publishableKey)
        clientId?.takeIf { it.isNotBlank() }?.let {
            connection.setRequestProperty("x-openmaps-client-id", it)
        }

        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code in 200..299) {
                val text = connection.inputStream.bufferedReader().use { it.readText() }
                return JSONObject(text)
            }
            when (code) {
                409 -> throw TripShareException.Closed
                410 -> throw TripShareException.Expired
                else -> throw TripShareException.Unavailable
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun UserLocation.toJson(): JSONObject = JSONObject()
        .put("latitude", latitude)
        .put("longitude", longitude)
        .putNullable("headingDegrees", bearingDegrees?.toDouble())
        .putNullable("speedMetersPerSecond", speedMetersPerSecond?.toDouble())

    private fun JSONObject.putNullable(name: String, value: Double?): JSONObject =
        put(name, value ?: JSONObject.NULL)

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 8_000
    }
}
