package com.openmaps.app.core.reporting

import com.openmaps.app.core.roadintelligence.RoadEvent
import com.openmaps.app.core.roadintelligence.RoadEventRouteMatcher
import com.openmaps.app.core.roadintelligence.RoadEventSeverity
import com.openmaps.app.core.roadintelligence.RoadEventType
import com.openmaps.app.core.roadintelligence.RoadIntelligenceException
import com.openmaps.app.core.roadintelligence.RoadIntelligenceRepository
import com.openmaps.app.core.roadintelligence.SpeedLimitInfo
import com.openmaps.app.core.roadintelligence.WeatherCondition
import com.openmaps.app.core.routing.Coordinate
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

class BackendCommunityReportsRepository(
    private val endpointUrl: String,
    private val publishableKey: String,
) : DriverReportRepository, RoadIntelligenceRepository {

    override suspend fun submit(report: DriverReport): Result<Unit> = requestResult {
        post(
            JSONObject()
                .put("action", "submit")
                .put("type", report.type.name)
                .put(
                    "location",
                    JSONObject()
                        .put("latitude", report.location.latitude)
                        .put("longitude", report.location.longitude),
                ),
        )
        Unit
    }

    override suspend fun confirm(reportId: String): Result<Unit> = vote(reportId, "confirm")

    override suspend fun dismiss(reportId: String): Result<Unit> = vote(reportId, "dismiss")

    override suspend fun roadEventsAlongRoute(
        routeGeometry: List<Coordinate>,
        corridorMeters: Double,
    ): Result<List<RoadEvent>> = requestResult {
        val geometry = JSONArray().apply {
            routeGeometry.forEach { point ->
                put(JSONObject().put("latitude", point.latitude).put("longitude", point.longitude))
            }
        }
        val response = post(
            JSONObject()
                .put("action", "query")
                .put("routeGeometry", geometry)
                .put("corridorMeters", corridorMeters),
        )
        val reports = response.optJSONArray("reports") ?: JSONArray()
        val raw = buildList {
            for (i in 0 until reports.length()) {
                parseEvent(reports.getJSONObject(i))?.let(::add)
            }
        }
        RoadEventRouteMatcher.matchToRoute(raw, routeGeometry, corridorMeters)
    }

    override suspend fun speedLimitNear(location: Coordinate): Result<SpeedLimitInfo?> =
        Result.success(null)

    override suspend fun weatherNear(location: Coordinate): Result<WeatherCondition?> =
        Result.success(null)

    private suspend fun vote(reportId: String, vote: String): Result<Unit> = requestResult {
        post(
            JSONObject()
                .put("action", "vote")
                .put("reportId", reportId)
                .put("vote", vote),
        )
        Unit
    }

    private suspend fun <T> requestResult(block: () -> T): Result<T> = withContext(Dispatchers.IO) {
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            Result.failure(DriverReportException.Unavailable)
        } catch (e: JSONException) {
            Result.failure(RoadIntelligenceException.Malformed)
        } catch (e: IOException) {
            Result.failure(DriverReportException.Unavailable)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun post(body: JSONObject): JSONObject {
        if (endpointUrl.isBlank() || publishableKey.isBlank()) throw DriverReportException.Unavailable
        val connection = URL(endpointUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("apikey", publishableKey)
        try {
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = connection.responseCode
            if (code !in 200..299) throw DriverReportException.Unavailable
            return JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun parseEvent(json: JSONObject): RoadEvent? {
        val reportType = runCatching { DriverReportType.valueOf(json.getString("type")) }.getOrNull() ?: return null
        val confidence = json.optDouble("confidence", 0.35).coerceIn(0.0, 1.0)
        val confirmations = json.optInt("confirmations", 0)
        val dismissals = json.optInt("dismissals", 0)
        val (type, severity, title) = reportPresentation(reportType)
        return RoadEvent(
            id = "community:" + json.getString("id"),
            type = type,
            latitude = json.getDouble("latitude"),
            longitude = json.getDouble("longitude"),
            severity = severity,
            title = title,
            description = "Community report",
            startTimeMillis = parseTimeMillis(json.optString("createdAt")),
            endTimeMillis = parseTimeMillis(json.optString("expiresAt")),
            metadata = mapOf(
                "source" to "community",
                "communityReportId" to json.getString("id"),
                "confidence" to String.format(java.util.Locale.US, "%.2f", confidence),
                "confirmations" to confirmations.toString(),
                "dismissals" to dismissals.toString(),
            ),
        )
    }

    private fun reportPresentation(type: DriverReportType): Triple<RoadEventType, RoadEventSeverity, String> = when (type) {
        DriverReportType.Police -> Triple(RoadEventType.Other, RoadEventSeverity.Moderate, "Police reported ahead")
        DriverReportType.Traffic -> Triple(RoadEventType.Congestion, RoadEventSeverity.Moderate, "Traffic reported ahead")
        DriverReportType.Crash -> Triple(RoadEventType.Crash, RoadEventSeverity.High, "Crash reported ahead")
        DriverReportType.LaneClosed -> Triple(RoadEventType.Closure, RoadEventSeverity.High, "Lane closure reported ahead")
        DriverReportType.Construction -> Triple(RoadEventType.Construction, RoadEventSeverity.Moderate, "Construction reported ahead")
        DriverReportType.RoadHazard -> Triple(RoadEventType.Other, RoadEventSeverity.High, "Road hazard reported ahead")
        DriverReportType.Flooding -> Triple(RoadEventType.Flooding, RoadEventSeverity.High, "Flooding reported ahead")
        DriverReportType.DisabledVehicle -> Triple(RoadEventType.Other, RoadEventSeverity.Moderate, "Disabled vehicle reported ahead")
        DriverReportType.ObjectOnRoad -> Triple(RoadEventType.Debris, RoadEventSeverity.High, "Object on road reported ahead")
    }

    private fun parseTimeMillis(value: String): Long? =
        runCatching { java.time.Instant.parse(value).toEpochMilli() }.getOrNull()

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 8_000
    }
}
