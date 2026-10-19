package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.places.RouteCorridor
import com.openmaps.app.core.routing.Coordinate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONException
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder

/**
 * [RoadIntelligenceRepository] backed by two public, free, no-API-key data sources — the same
 * "development-only, document the limits" category as every other provider in this codebase (see
 * docs/ARCHITECTURE.md's Geocoding/Routing/Place data provider sections):
 *
 *  - **Overpass API** (`overpass-api.de/api/interpreter`, the same host `OverpassPlaceRepository`
 *    already calls) for [roadEventsAlongRoute] (static `highway=construction`/`hazard=*` tags — see
 *    [OverpassRoadIntelligenceParser]'s doc comment for exactly what that can and cannot claim) and
 *    [speedLimitNear] (the nearest way's `maxspeed` tag).
 *  - **Open-Meteo** (`api.open-meteo.com`, free, no API key, no account) for [weatherNear].
 *
 * **This fallback repository does not provide live traffic.** Production OpenMaps wraps it in
 * [CompositeRoadIntelligenceRepository] with [HereTrafficRoadIntelligenceRepository] first, so
 * live incidents/closures and traffic-aware route timing come from the commercial backend while
 * this class remains the static OSM/weather fallback. Missing live data is never fabricated.
 *
 * **Do not ship to the public on either of these.** Same category of open production decision as
 * every other provider here — see "Decisions to revisit" in ARCHITECTURE.md.
 */
class DefaultRoadIntelligenceRepository(
    private val userAgent: String,
    private val overpassBaseUrl: String = "https://overpass-api.de/api/interpreter",
    private val openMeteoBaseUrl: String = "https://api.open-meteo.com/v1/forecast",
    private val cache: RoadIntelligenceCache = RoadIntelligenceCache(),
    private val now: () -> Long = System::currentTimeMillis,
) : RoadIntelligenceRepository {

    override suspend fun roadEventsAlongRoute(
        routeGeometry: List<Coordinate>,
        corridorMeters: Double,
    ): Result<List<RoadEvent>> = withContext(Dispatchers.IO) {
        if (routeGeometry.size < 2) return@withContext Result.success(emptyList())
        runCatchingProviderErrors {
            val bounds = RouteCorridor.boundingQuery(routeGeometry, corridorMeters, MAX_HAZARD_RADIUS_METERS)
            val cached = cache.getHazards(bounds.center)
            val raw = cached ?: fetchHazards(bounds.center, bounds.radiusMeters).also {
                cache.putHazards(bounds.center, it)
            }
            val matched = RoadEventRouteMatcher.matchToRoute(RoadEventDeduplicator.dedupe(raw), routeGeometry, corridorMeters)
            matched.take(MAX_RESULTS)
        }
    }

    override suspend fun speedLimitNear(location: Coordinate): Result<SpeedLimitInfo?> =
        withContext(Dispatchers.IO) {
            runCatchingProviderErrors {
                cache.getSpeedLimit(location) ?: fetchSpeedLimit(location).also { cache.putSpeedLimit(location, it) }
            }
        }

    override suspend fun weatherNear(location: Coordinate): Result<WeatherCondition?> =
        withContext(Dispatchers.IO) {
            runCatchingProviderErrors {
                cache.getWeather(location) ?: fetchWeather(location).also { cache.putWeather(location, it) }
            }
        }

    // region Overpass (hazards + speed limit)

    private fun fetchHazards(center: Coordinate, radiusMeters: Double): List<RoadEvent> {
        val query = buildHazardQuery(center, radiusMeters)
        val body = postOverpass(query)
        return OverpassRoadIntelligenceParser.parseHazards(body)
    }

    private fun fetchSpeedLimit(location: Coordinate): SpeedLimitInfo? {
        val query = buildSpeedLimitQuery(location)
        val body = postOverpass(query)
        return OverpassRoadIntelligenceParser.parseNearestSpeedLimit(body, location)
    }

    private fun buildHazardQuery(center: Coordinate, radiusMeters: Double): String {
        val radius = radiusMeters.toInt().coerceAtLeast(1)
        val around = "around:$radius,${center.latitude},${center.longitude}"
        return """
            [out:json][timeout:$TIMEOUT_SECONDS];
            (
              way["highway"="construction"]($around);
              way["construction"]($around);
              node["hazard"]($around);
              way["hazard"]($around);
            );
            out body center $MAX_ELEMENTS;
        """.trimIndent()
    }

    private fun buildSpeedLimitQuery(location: Coordinate): String {
        val around = "around:$SPEED_LIMIT_RADIUS_METERS,${location.latitude},${location.longitude}"
        return """
            [out:json][timeout:$TIMEOUT_SECONDS];
            (
              way["highway"]["maxspeed"]($around);
            );
            out body center $MAX_SPEED_LIMIT_ELEMENTS;
        """.trimIndent()
    }

    private fun postOverpass(query: String): String {
        val connection = URL(overpassBaseUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.doOutput = true
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            val body = "data=" + URLEncoder.encode(query, "UTF-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> connection.inputStream.bufferedReader().use { it.readText() }
                else -> throw RoadIntelligenceException.Server(code) // includes 429/504 (Overpass "too busy")
            }
        } finally {
            connection.disconnect()
        }
    }

    // endregion

    // region Open-Meteo (weather)

    private fun fetchWeather(location: Coordinate): WeatherCondition? {
        val url = "$openMeteoBaseUrl?latitude=${location.latitude}&longitude=${location.longitude}" +
            "&current=temperature_2m,wind_speed_10m,weather_code" +
            "&temperature_unit=celsius&wind_speed_unit=kmh&timezone=auto"
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.requestMethod = "GET"
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.setRequestProperty("User-Agent", userAgent)
        try {
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val body = connection.inputStream.bufferedReader().use { it.readText() }
                    OpenMeteoWeatherParser.parse(body, now())
                }
                else -> throw RoadIntelligenceException.Server(code)
            }
        } finally {
            connection.disconnect()
        }
    }

    // endregion

    private inline fun <T> runCatchingProviderErrors(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e // Never swallow cancellation: a superseded fetch must drop cleanly.
    } catch (e: RoadIntelligenceException) {
        Result.failure(e)
    } catch (e: SocketTimeoutException) {
        Result.failure(RoadIntelligenceException.Timeout)
    } catch (e: JSONException) {
        Result.failure(RoadIntelligenceException.Malformed)
    } catch (e: IOException) {
        Result.failure(RoadIntelligenceException.NoNetwork)
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000
        const val TIMEOUT_SECONDS = 15
        const val MAX_ELEMENTS = 60
        const val MAX_RESULTS = 60
        const val MAX_HAZARD_RADIUS_METERS = 8_000.0
        const val SPEED_LIMIT_RADIUS_METERS = 60
        const val MAX_SPEED_LIMIT_ELEMENTS = 10
    }
}
