package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.Coordinate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.util.Locale

/**
 * Production HERE Traffic incident client through the OpenMaps backend.
 *
 * Only live incidents/closures are supplied here. Speed limits and weather intentionally return
 * null so the composite repository can continue to use their existing providers.
 */
class HereTrafficRoadIntelligenceRepository(
    private val endpointUrl: String,
    private val publishableKey: String,
    private val localeTagProvider: () -> String = { Locale.getDefault().toLanguageTag() },
) : RoadIntelligenceRepository {

    override suspend fun roadEventsAlongRoute(
        routeGeometry: List<Coordinate>,
        corridorMeters: Double,
    ): Result<List<RoadEvent>> = withContext(Dispatchers.IO) {
        if (endpointUrl.isBlank() || publishableKey.isBlank()) {
            return@withContext Result.failure(RoadIntelligenceException.Server(599))
        }
        if (routeGeometry.size < 2) return@withContext Result.success(emptyList())

        try {
            val raw = fetch(routeGeometry, corridorMeters)
            Result.success(
                RoadEventRouteMatcher.matchToRoute(
                    RoadEventDeduplicator.dedupe(raw),
                    routeGeometry,
                    corridorMeters,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: RoadIntelligenceException) {
            Result.failure(e)
        } catch (e: SocketTimeoutException) {
            Result.failure(RoadIntelligenceException.Timeout)
        } catch (e: JSONException) {
            Result.failure(RoadIntelligenceException.Malformed)
        } catch (e: IOException) {
            Result.failure(RoadIntelligenceException.NoNetwork)
        }
    }

    override suspend fun speedLimitNear(location: Coordinate): Result<SpeedLimitInfo?> =
        Result.success(null)

    override suspend fun weatherNear(location: Coordinate): Result<WeatherCondition?> =
        Result.success(null)

    private fun fetch(routeGeometry: List<Coordinate>, corridorMeters: Double): List<RoadEvent> {
        val connection = URL(endpointUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("apikey", publishableKey)

        val body = JSONObject()
            .put(
                "routeGeometry",
                org.json.JSONArray().apply {
                    routeGeometry.forEach { point ->
                        put(
                            JSONObject()
                                .put("latitude", point.latitude)
                                .put("longitude", point.longitude),
                        )
                    }
                },
            )
            .put("corridorMeters", corridorMeters)
            .put("localeTag", localeTagProvider())
            .toString()

        try {
            connection.outputStream.use { output ->
                output.write(body.toByteArray(Charsets.UTF_8))
            }
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    HereTrafficIncidentResponseParser.parse(response)
                }
                else -> throw RoadIntelligenceException.Server(code)
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 9_000
    }
}

internal object HereTrafficIncidentResponseParser {
    fun parse(body: String): List<RoadEvent> {
        val root = JSONObject(body)
        val items = root.optJSONArray("incidents") ?: return emptyList()
        return buildList(items.length()) {
            for (index in 0 until items.length()) {
                val item = items.getJSONObject(index)
                add(
                    RoadEvent(
                        id = item.getString("id"),
                        type = item.getString("type").toRoadEventType(),
                        latitude = item.getDouble("latitude"),
                        longitude = item.getDouble("longitude"),
                        severity = item.getString("severity").toRoadEventSeverity(),
                        title = item.getString("title"),
                        description = item.optNullableString("description"),
                        startTimeMillis = item.optNullableLong("startTimeMillis"),
                        endTimeMillis = item.optNullableLong("endTimeMillis"),
                        metadata = item.optJSONObject("metadata")?.let { metadata ->
                            buildMap {
                                metadata.keys().forEach { key -> put(key, metadata.optString(key)) }
                            }
                        }.orEmpty(),
                    ),
                )
            }
        }
    }

    private fun String.toRoadEventType(): RoadEventType =
        RoadEventType.entries.firstOrNull { it.name == this } ?: RoadEventType.Other

    private fun String.toRoadEventSeverity(): RoadEventSeverity =
        RoadEventSeverity.entries.firstOrNull { it.name == this } ?: RoadEventSeverity.Low

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

    private fun JSONObject.optNullableLong(key: String): Long? =
        if (!has(key) || isNull(key)) null else getLong(key)
}
