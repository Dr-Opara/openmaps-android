package com.openmaps.app.core.places

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
 * [PlaceRepository] backed by the public Overpass API (https://overpass-api.de/api/interpreter),
 * which queries OpenStreetMap data directly — free, open, no API key, the same OSM lineage already
 * used for the basemap (OpenFreeMap), geocoding (Nominatim) and routing (Valhalla); see
 * ARCHITECTURE.md "Place data provider".
 *
 * **Overture Maps was considered and rejected for this phase**: Overture distributes places as
 * cloud-hosted Parquet (S3/Azure blob storage), not a queryable nearby-search HTTP API, so using it
 * from an Android client at request time would mean standing up and operating a server-side tile
 * index — real infrastructure, not a Phase 9 decision. Overpass already is a request/response
 * nearby-search API over the same open dataset the rest of the app depends on.
 *
 * **Development-only constraints** (same category as the tile source, Nominatim and Valhalla — see
 * ARCHITECTURE.md): the public instance has no SLA, a documented soft rate limit, and asks for a
 * descriptive `User-Agent` (set below). **Do not ship to the public on it.** Before production,
 * move to a self-hosted Overpass instance (or a contracted places provider) — only this class needs
 * to change; [PlaceRepository] stays the same.
 */
class OverpassPlaceRepository(
    private val userAgent: String,
    private val baseUrl: String = "https://overpass-api.de/api/interpreter",
    private val cache: PlaceCache = PlaceCache(),
) : PlaceRepository {

    override suspend fun nearbyPlaces(
        center: Coordinate,
        radiusMeters: Double,
        categories: Set<PlaceCategory>,
    ): Result<List<Place>> = fetchCached(center, radiusMeters.coerceAtMost(MAX_NEARBY_RADIUS_METERS), categories)

    /**
     * Queries once over the route's bounding box (plus [corridorMeters] of margin, capped at
     * [MAX_ROUTE_RADIUS_METERS]) and filters client-side to the actual corridor via
     * [RouteCorridor.filterToCorridor] — a corridor **approximation**, not per-segment detour
     * routing, which ROADMAP.md explicitly accepts for Phase 9.
     */
    override suspend fun placesAlongRoute(
        routeGeometry: List<Coordinate>,
        corridorMeters: Double,
        categories: Set<PlaceCategory>,
    ): Result<List<Place>> {
        if (routeGeometry.size < 2) return Result.success(emptyList())
        val bounds = RouteCorridor.boundingQuery(routeGeometry, corridorMeters, MAX_ROUTE_RADIUS_METERS)
        return fetchCached(bounds.center, bounds.radiusMeters, categories).map { places ->
            RouteCorridor.filterToCorridor(places, routeGeometry, corridorMeters)
                .sortedBy { it.routeDetourMeters ?: Double.MAX_VALUE }
                .take(MAX_RESULTS)
        }
    }

    private suspend fun fetchCached(
        center: Coordinate,
        radiusMeters: Double,
        categories: Set<PlaceCategory>,
    ): Result<List<Place>> = withContext(Dispatchers.IO) {
        if (categories.isEmpty()) return@withContext Result.success(emptyList())
        cache.get(center, radiusMeters, categories)?.let { return@withContext Result.success(it) }
        try {
            val places = fetch(center, radiusMeters, categories)
            cache.put(center, radiusMeters, categories, places)
            Result.success(places)
        } catch (e: CancellationException) {
            throw e // Never swallow cancellation: a superseded fetch must drop cleanly.
        } catch (e: PlaceException) {
            Result.failure(e)
        } catch (e: SocketTimeoutException) {
            Result.failure(PlaceException.Timeout)
        } catch (e: JSONException) {
            Result.failure(PlaceException.Malformed)
        } catch (e: IOException) {
            Result.failure(PlaceException.NoNetwork)
        }
    }

    private fun fetch(center: Coordinate, radiusMeters: Double, categories: Set<PlaceCategory>): List<Place> {
        val connection = URL(baseUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = TIMEOUT_MILLIS
        connection.readTimeout = TIMEOUT_MILLIS
        connection.doOutput = true
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            val body = "data=" + URLEncoder.encode(buildQuery(center, radiusMeters, categories), "UTF-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                    withDistances(OverpassResponseParser.parse(responseBody), center)
                }
                429, 504 -> throw PlaceException.RateLimited
                else -> throw PlaceException.Server(code)
            }
        } finally {
            connection.disconnect()
        }
    }

    /** Stamps straight-line [Place.distanceMeters] from [center] and caps the result count. */
    private fun withDistances(places: List<Place>, center: Coordinate): List<Place> =
        places
            .map { it.copy(distanceMeters = RouteCorridor.metersBetween(center, Coordinate(it.latitude, it.longitude))) }
            .sortedBy { it.distanceMeters }
            .take(MAX_RESULTS)

    /**
     * Builds an Overpass QL query for every tag selector [categories] maps to, over both nodes and
     * ways (a way is returned with its centroid via `out center`, since a parking lot or hotel is
     * often mapped as a way, not a point). `out body center $MAX_ELEMENTS` is the request-side
     * bound on how many POIs can come back — Phase 9 explicitly must not fetch an unbounded set.
     */
    private fun buildQuery(center: Coordinate, radiusMeters: Double, categories: Set<PlaceCategory>): String {
        val radius = radiusMeters.toInt().coerceAtLeast(1)
        val selectors = PlaceCategoryMapper.overpassSelectors(categories)
        val clauses = selectors.joinToString("\n") { selector ->
            "  node$selector(around:$radius,${center.latitude},${center.longitude});\n" +
                "  way$selector(around:$radius,${center.latitude},${center.longitude});"
        }
        return "[out:json][timeout:$TIMEOUT_SECONDS];\n(\n$clauses\n);\nout body center $MAX_ELEMENTS;"
    }

    private companion object {
        const val TIMEOUT_MILLIS = 10_000
        const val TIMEOUT_SECONDS = 15
        const val MAX_ELEMENTS = 60
        const val MAX_RESULTS = 60
        const val MAX_NEARBY_RADIUS_METERS = 3_000.0
        const val MAX_ROUTE_RADIUS_METERS = 8_000.0
    }
}
