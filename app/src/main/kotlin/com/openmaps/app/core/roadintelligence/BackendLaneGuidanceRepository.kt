package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.ManeuverType
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class BackendLaneGuidanceRepository(
    private val endpointUrl: String,
    private val publishableKey: String,
) : LaneGuidanceRepository {

    override suspend fun guidance(
        trace: List<Coordinate>,
        maneuverType: ManeuverType,
    ): Result<LaneGuidance?> = withContext(Dispatchers.IO) {
        if (endpointUrl.isBlank() || publishableKey.isBlank()) {
            return@withContext Result.success(null)
        }
        if (trace.size < 2) return@withContext Result.success(null)

        try {
            Result.success(fetch(trace, maneuverType))
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            Result.failure(RoadIntelligenceException.Timeout)
        } catch (e: JSONException) {
            Result.failure(RoadIntelligenceException.Malformed)
        } catch (e: IOException) {
            Result.failure(RoadIntelligenceException.NoNetwork)
        }
    }

    private fun fetch(trace: List<Coordinate>, maneuverType: ManeuverType): LaneGuidance? {
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
                "trace",
                org.json.JSONArray().apply {
                    trace.forEach { point ->
                        put(
                            JSONObject()
                                .put("latitude", point.latitude)
                                .put("longitude", point.longitude),
                        )
                    }
                },
            )
            .put("maneuverType", maneuverType.name)
            .toString()

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    BackendLaneGuidanceResponseParser.parse(response)
                }
                429 -> throw RoadIntelligenceException.Server(code)
                in 500..599 -> throw RoadIntelligenceException.Server(code)
                else -> null
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

internal object BackendLaneGuidanceResponseParser {
    fun parse(body: String): LaneGuidance? {
        val root = JSONObject(body)
        if (!root.has("guidance") || root.isNull("guidance")) return null
        val guidance = root.getJSONObject("guidance")
        val laneJson = guidance.getJSONArray("lanes")
        val recommendedJson = guidance.getJSONArray("recommendedLaneIndexes")
        val relation = guidance.optString("maneuverRelation")
            .takeIf { it.isNotBlank() }
            ?.let { raw -> ManeuverType.entries.firstOrNull { it.name == raw } }

        val recommended = buildList {
            for (index in 0 until recommendedJson.length()) add(recommendedJson.getInt(index))
        }

        val lanes = buildList {
            for (index in 0 until laneJson.length()) {
                val lane = laneJson.getJSONObject(index)
                val directionsJson = lane.getJSONArray("directions")
                val directions = buildList {
                    for (directionIndex in 0 until directionsJson.length()) {
                        val raw = directionsJson.getString(directionIndex)
                        add(LaneDirection.entries.firstOrNull { it.name == raw } ?: LaneDirection.Unknown)
                    }
                }
                add(
                    Lane(
                        directions = directions.ifEmpty { listOf(LaneDirection.Unknown) },
                        isRecommended = lane.optBoolean("recommended", index in recommended),
                    ),
                )
            }
        }

        if (lanes.isEmpty() || recommended.isEmpty()) return null
        val source = guidance.optString("source").takeIf { it.isNotBlank() }
        val confidence = when (guidance.optString("confidence").lowercase()) {
            "high" -> LaneGuidanceConfidence.High
            "medium" -> LaneGuidanceConfidence.Medium
            else -> LaneGuidanceConfidence.Unknown
        }

        return LaneGuidance(
            lanes = lanes,
            recommendedLaneIndexes = recommended.filter { it in lanes.indices },
            maneuverRelation = relation,
            source = source,
            confidence = confidence,
        )
    }
}
