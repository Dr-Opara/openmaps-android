package com.openmaps.app.core.routing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets

/**
 * [RoutingRepository] backed by the public Valhalla demo instance
 * (https://valhalla1.openstreetmap.de) — free, open, no API key, run by FOSSGIS e.V. on
 * OpenStreetMap data, the same lineage as the OpenFreeMap tile source and Nominatim geocoding
 * already used elsewhere in this app.
 *
 * Development-only constraints: this is a shared community demo server with no SLA, no
 * documented rate limit (be conservative — this app only calls it once per destination
 * selection, never on a timer or per-frame) and no guarantee of availability. Before shipping,
 * move to a self-hosted Valhalla instance or a contracted routing provider — the same decision
 * already flagged for the tile source and the geocoding provider.
 *
 * Costing is hardcoded to `"auto"` (driving): Phase 3 has no mode picker.
 */
class ValhallaRoutingRepository(
    private val userAgent: String,
    private val baseUrl: String = "https://valhalla1.openstreetmap.de/route",
) : RoutingRepository {

    override suspend fun route(origin: Coordinate, destination: Coordinate): Result<RouteResult> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(fetch(origin, destination))
            } catch (e: CancellationException) {
                throw e // Never swallow cancellation: the caller relies on it to drop stale requests.
            } catch (e: RoutingException) {
                Result.failure(e)
            } catch (e: SocketTimeoutException) {
                Result.failure(RoutingException.Timeout)
            } catch (e: JSONException) {
                Result.failure(RoutingException.Malformed)
            } catch (e: IOException) {
                Result.failure(RoutingException.NoNetwork)
            }
        }

    private fun fetch(origin: Coordinate, destination: Coordinate): RouteResult {
        val connection = URL(baseUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Content-Type", "application/json")
        connection.setRequestProperty("Accept", "application/json")
        try {
            connection.outputStream.use { it.write(requestBody(origin, destination).toByteArray(StandardCharsets.UTF_8)) }
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    ValhallaResponseParser.parse(body)
                }
                else -> throw mapErrorResponse(code, connection)
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Valhalla reports an unroutable pair (no road near a point, no path between them) as a
     * 4xx with a JSON body shaped like `{"error_code":171,"error":"...","status_code":400}` —
     * no `"trip"` key, so [ValhallaResponseParser] never sees it. Confirmed against a live
     * request for two points with no nearby roads. Anything else is a generic server error.
     */
    private fun mapErrorResponse(code: Int, connection: HttpURLConnection): RoutingException {
        if (code in 400..499) {
            val errorBody = connection.errorStream?.bufferedReader()?.use { it.readText() }
            val looksLikeNoRoute = errorBody != null && runCatching { JSONObject(errorBody).has("error") }.getOrDefault(false)
            if (looksLikeNoRoute) return RoutingException.NoRouteFound
        }
        return RoutingException.Server(code)
    }

    private fun requestBody(origin: Coordinate, destination: Coordinate): String = JSONObject().apply {
        put(
            "locations",
            JSONArray().apply {
                put(origin.toJson())
                put(destination.toJson())
            },
        )
        put("costing", "auto")
        put("units", "kilometers")
    }.toString()

    private fun Coordinate.toJson(): JSONObject = JSONObject().apply {
        put("lat", latitude)
        put("lon", longitude)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000
    }
}
