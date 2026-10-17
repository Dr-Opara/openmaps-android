package com.openmaps.app.core.places

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

/**
 * Production places provider backed by the OpenMaps Edge gateway.
 *
 * Provider secrets never ship in the APK. This repository only speaks the provider-neutral
 * [PlaceRepository] contract and falls back through [CompositePlaceRepository] when the gateway or
 * its commercial provider is unavailable.
 */
class BackendPlaceRepository(
    private val endpointUrl: String,
    private val publishableKey: String,
) : PlaceRepository {

    override suspend fun nearbyPlaces(
        center: Coordinate,
        radiusMeters: Double,
        categories: Set<PlaceCategory>,
    ): Result<PlaceQueryResult> = withContext(Dispatchers.IO) {
        if (categories.isEmpty()) {
            return@withContext Result.success(PlaceQueryResult(emptyList(), PlaceSource.Empty))
        }
        if (endpointUrl.isBlank() || publishableKey.isBlank()) {
            return@withContext Result.failure(PlaceException.NoNetwork)
        }

        try {
            Result.success(
                PlaceQueryResult(
                    places = fetch(center, radiusMeters, categories),
                    source = PlaceSource.Network,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: SocketTimeoutException) {
            Result.failure(PlaceException.Timeout)
        } catch (e: JSONException) {
            Result.failure(PlaceException.Malformed)
        } catch (e: PlaceException) {
            Result.failure(e)
        } catch (e: IOException) {
            Result.failure(PlaceException.NoNetwork)
        }
    }

    override suspend fun placesAlongRoute(
        routeGeometry: List<Coordinate>,
        corridorMeters: Double,
        categories: Set<PlaceCategory>,
    ): Result<List<Place>> {
        if (routeGeometry.size < 2) return Result.success(emptyList())
        val bounds = RouteCorridor.boundingQuery(routeGeometry, corridorMeters, MAX_ROUTE_RADIUS_METERS)
        return nearbyPlaces(bounds.center, bounds.radiusMeters, categories).map { result ->
            RouteCorridor.filterToCorridor(result.places, routeGeometry, corridorMeters)
                .sortedBy { it.routeDetourMeters ?: Double.MAX_VALUE }
                .take(MAX_RESULTS)
        }
    }

    private fun fetch(
        center: Coordinate,
        radiusMeters: Double,
        categories: Set<PlaceCategory>,
    ): List<Place> {
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
                "center",
                JSONObject()
                    .put("latitude", center.latitude)
                    .put("longitude", center.longitude),
            )
            .put("radiusMeters", radiusMeters.coerceIn(MIN_RADIUS_METERS, MAX_ROUTE_RADIUS_METERS))
            .put(
                "categories",
                JSONArray().apply { categories.forEach { put(it.name) } },
            )
            .toString()

        try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val response = connection.inputStream.bufferedReader().use { it.readText() }
                    BackendPlaceResponseParser.parse(response)
                }
                429 -> throw PlaceException.RateLimited
                in 500..599 -> throw PlaceException.Server(code)
                else -> throw PlaceException.Server(code)
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 5_000
        const val READ_TIMEOUT_MILLIS = 9_000
        const val MIN_RADIUS_METERS = 250.0
        const val MAX_ROUTE_RADIUS_METERS = 8_000.0
        const val MAX_RESULTS = 60
    }
}

internal object BackendPlaceResponseParser {

    fun parse(body: String): List<Place> {
        val root = JSONObject(body)
        val places = root.optJSONArray("places") ?: return emptyList()
        return buildList {
            for (index in 0 until places.length()) {
                val item = places.optJSONObject(index) ?: continue
                parsePlace(item)?.let(::add)
            }
        }
    }

    private fun parsePlace(item: JSONObject): Place? {
        val category = item.optString("category")
            .takeIf { it.isNotBlank() }
            ?.let { raw -> PlaceCategory.entries.firstOrNull { it.name == raw } }
            ?: return null

        val latitude = item.optDouble("latitude", Double.NaN)
        val longitude = item.optDouble("longitude", Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite()) return null

        val metadata = item.optJSONObject("metadata")
        val connectors = buildList {
            val values = metadata?.optJSONArray("connectors") ?: JSONArray()
            for (index in 0 until values.length()) {
                values.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            }
        }

        return Place(
            id = item.optString("id").takeIf { it.isNotBlank() } ?: return null,
            name = item.optString("name").takeIf { it.isNotBlank() } ?: category.name,
            category = category,
            latitude = latitude,
            longitude = longitude,
            address = item.optString("address").takeIf { it.isNotBlank() },
            distanceMeters = item.optDouble("distanceMeters", Double.NaN).takeIf { it.isFinite() },
            openNow = if (item.has("openNow") && !item.isNull("openNow")) item.optBoolean("openNow") else null,
            metadata = PlaceMetadata(
                parkingType = metadata?.optString("parkingType")?.takeIf { it.isNotBlank() },
                isPublicAccess = metadata?.takeIf { it.has("isPublicAccess") && !it.isNull("isPublicAccess") }
                    ?.optBoolean("isPublicAccess"),
                feeApplies = metadata?.takeIf { it.has("feeApplies") && !it.isNull("feeApplies") }
                    ?.optBoolean("feeApplies"),
                brand = metadata?.optString("brand")?.takeIf { it.isNotBlank() },
                connectors = connectors,
            ),
        )
    }
}
