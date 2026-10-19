package com.openmaps.app.core.routing

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * Production routing client. Commercial provider credentials stay behind the OpenMaps backend;
 * the Android app receives only provider-neutral route geometry, maneuvers and traffic metadata.
 */
class BackendRoutingRepository(
    private val endpointUrl: String,
    private val publishableKey: String,
    private val clientId: String? = null,
    private val localeTagProvider: () -> String = { Locale.getDefault().toLanguageTag() },
) : RoutingRepository {

    override suspend fun route(origin: Coordinate, destination: Coordinate): Result<RouteResult> =
        withContext(Dispatchers.IO) {
            if (endpointUrl.isBlank() || publishableKey.isBlank()) {
                return@withContext Result.failure(RoutingException.Server(CONFIGURATION_ERROR_CODE))
            }
            try {
                Result.success(fetch(origin, destination))
            } catch (e: CancellationException) {
                throw e
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

        val body = JSONObject()
            .put("origin", origin.toJson())
            .put("destination", destination.toJson())
            .put("localeTag", localeTagProvider())
            .toString()

        try {
            connection.outputStream.use {
                it.write(body.toByteArray(StandardCharsets.UTF_8))
            }
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    BackendRoutingResponseParser.parse(response)
                }
                HttpURLConnection.HTTP_NOT_FOUND -> throw RoutingException.NoRouteFound
                429 -> throw RoutingException.Server(code)
                in 500..599 -> throw RoutingException.Server(code)
                else -> throw RoutingException.Server(code)
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun Coordinate.toJson(): JSONObject = JSONObject()
        .put("latitude", latitude)
        .put("longitude", longitude)

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 10_000
        const val CONFIGURATION_ERROR_CODE = 599
    }
}

internal object BackendRoutingResponseParser {
    fun parse(body: String): RouteResult {
        val root = JSONObject(body)
        val trafficAware = root.optBoolean("trafficAware", false)
        val route = root.getJSONObject("route")
        val geometryJson = route.getJSONArray("geometry")
        val geometry = buildList(geometryJson.length()) {
            for (index in 0 until geometryJson.length()) {
                val point = geometryJson.getJSONObject(index)
                add(Coordinate(point.getDouble("latitude"), point.getDouble("longitude")))
            }
        }
        if (geometry.isEmpty()) throw RoutingException.NoRouteFound

        val maneuversJson = route.optJSONArray("maneuvers")
        val maneuvers = if (maneuversJson == null) {
            emptyList()
        } else {
            buildList(maneuversJson.length()) {
                for (index in 0 until maneuversJson.length()) {
                    val item = maneuversJson.getJSONObject(index)
                    add(
                        RouteManeuver(
                            instruction = item.getString("instruction"),
                            verbalInstruction = item.optNullableString("verbalInstruction"),
                            type = item.optString("type").toManeuverType(),
                            streetName = item.optNullableString("streetName"),
                            distanceMeters = item.optDouble("distanceMeters", 0.0),
                            beginShapeIndex = item.optInt("beginShapeIndex", 0),
                            endShapeIndex = item.optInt("endShapeIndex", 0),
                            exitSign = item.optNullableString("exitSign"),
                            signpost = item.optNullableString("signpost"),
                        ),
                    )
                }
            }
        }

        val alternativesJson = root.optJSONArray("alternatives")
        val alternatives = if (alternativesJson == null) {
            emptyList()
        } else {
            buildList(alternativesJson.length()) {
                for (index in 0 until alternativesJson.length()) {
                    add(parseAlternative(alternativesJson.getJSONObject(index), trafficAware))
                }
            }
        }

        return RouteResult(
            id = route.optNullableString("id"),
            label = route.optNullableString("label"),
            geometry = geometry,
            distanceMeters = route.getDouble("distanceMeters"),
            durationSeconds = route.getDouble("durationSeconds"),
            bounds = geometry.bounds(),
            maneuvers = maneuvers,
            baseDurationSeconds = route.optNullableDouble("baseDurationSeconds"),
            typicalDurationSeconds = route.optNullableDouble("typicalDurationSeconds"),
            trafficDelaySeconds = route.optNullableDouble("trafficDelaySeconds"),
            isTrafficAware = trafficAware,
            alternatives = alternatives,
        )
    }

    private fun parseAlternative(route: JSONObject, trafficAware: Boolean): RouteAlternative {
        val geometryJson = route.getJSONArray("geometry")
        val geometry = buildList(geometryJson.length()) {
            for (index in 0 until geometryJson.length()) {
                val point = geometryJson.getJSONObject(index)
                add(Coordinate(point.getDouble("latitude"), point.getDouble("longitude")))
            }
        }

        val maneuversJson = route.optJSONArray("maneuvers")
        val maneuvers = if (maneuversJson == null) {
            emptyList()
        } else {
            buildList(maneuversJson.length()) {
                for (index in 0 until maneuversJson.length()) {
                    val item = maneuversJson.getJSONObject(index)
                    add(
                        RouteManeuver(
                            instruction = item.getString("instruction"),
                            verbalInstruction = item.optNullableString("verbalInstruction"),
                            type = item.optString("type").toManeuverType(),
                            streetName = item.optNullableString("streetName"),
                            distanceMeters = item.optDouble("distanceMeters", 0.0),
                            beginShapeIndex = item.optInt("beginShapeIndex", 0),
                            endShapeIndex = item.optInt("endShapeIndex", 0),
                            exitSign = item.optNullableString("exitSign"),
                            signpost = item.optNullableString("signpost"),
                        ),
                    )
                }
            }
        }

        return RouteAlternative(
            id = route.optNullableString("id"),
            label = route.optNullableString("label"),
            geometry = geometry,
            distanceMeters = route.getDouble("distanceMeters"),
            durationSeconds = route.getDouble("durationSeconds"),
            bounds = geometry.bounds(),
            maneuvers = maneuvers,
            baseDurationSeconds = route.optNullableDouble("baseDurationSeconds"),
            typicalDurationSeconds = route.optNullableDouble("typicalDurationSeconds"),
            trafficDelaySeconds = route.optNullableDouble("trafficDelaySeconds"),
            isTrafficAware = trafficAware,
        )
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

    private fun JSONObject.optNullableDouble(key: String): Double? =
        if (!has(key) || isNull(key)) null else getDouble(key)

    private fun String.toManeuverType(): ManeuverType =
        ManeuverType.entries.firstOrNull { it.name == this } ?: ManeuverType.Unknown

    private fun List<Coordinate>.bounds(): RouteBounds? {
        if (isEmpty()) return null
        return RouteBounds(
            south = minOf { it.latitude },
            west = minOf { it.longitude },
            north = maxOf { it.latitude },
            east = maxOf { it.longitude },
        )
    }
}
