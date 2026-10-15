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
 * [PlaceRepository] backed by the public Overpass API, querying OpenStreetMap directly.
 *
 * **Development-only.** The public instances have no SLA, a documented soft rate limit, and ask for a
 * descriptive `User-Agent`. Before production this is replaced by a contracted/self-hosted provider —
 * `PlaceRepository` is the seam, so only this class changes. See `CompositePlaceRepository` for how
 * a second provider slots in.
 *
 * ### Why it is built this way (Phase 12B, from measured failures)
 *
 * The first version asked for all 26 OSM selectors in one query. Measured on the public instance
 * that took **8.3 s**, against a 10 s read timeout, so a phone regularly timed out; under repeated
 * load the instance began answering **504** and **429** for even small queries. Three changes follow
 * from those measurements:
 *
 *  1. **`nwr` shorthand, and small selector sets.** One clause per selector instead of a `node` plus
 *     a `way` clause halves the work Overpass has to plan.
 *  2. **Sticky endpoint health.** Endpoints are not a fixed preference list: whichever one last
 *     answered successfully is tried first. Measured during this phase, `overpass-api.de` was
 *     returning 504 while `maps.mail.ru` was returning HTTP 200 with real data — a fixed-order list
 *     burns ~12 s failing over on *every* request, which is what made the app look permanently
 *     broken.
 *  3. **Bounded attempts.** At most one pass over the endpoint list per request. No retry loops, so
 *     a bad provider is never hammered.
 *
 * **No cache here on purpose.** Caching belongs to [CompositePlaceRepository], which owns the cache
 * and reports provenance. A second cache inside this class would make "did this come from the
 * network?" unanswerable, which is exactly the honesty problem Phase 12B set out to fix.
 */
class OverpassPlaceRepository(
    private val userAgent: String,
    endpoints: List<String> = DEFAULT_ENDPOINTS,
    private val attemptTimeoutMillis: Int = ATTEMPT_TIMEOUT_MILLIS,
) : PlaceRepository {

    /** Endpoints in initial preference order. */
    private val endpoints: List<String> = endpoints.ifEmpty { DEFAULT_ENDPOINTS }

    /**
     * The endpoint that last answered successfully, or null. Persisted for the repository's lifetime
     * (per app process) so a session learns which mirror is currently healthy.
     */
    @Volatile
    private var preferredEndpoint: String? = null

    override suspend fun nearbyPlaces(
        center: Coordinate,
        radiusMeters: Double,
        categories: Set<PlaceCategory>,
    ): Result<PlaceQueryResult> = withContext(Dispatchers.IO) {
        // Empty request is a real, successful empty answer — never a failure.
        if (categories.isEmpty()) {
            return@withContext Result.success(PlaceQueryResult(emptyList(), PlaceSource.Empty))
        }
        val selectors = PlaceCategoryMapper.overpassSelectors(categories)
        if (selectors.isEmpty()) {
            return@withContext Result.success(PlaceQueryResult(emptyList(), PlaceSource.Empty))
        }
        val radius = radiusMeters.coerceIn(MIN_NEARBY_RADIUS_METERS, MAX_NEARBY_RADIUS_METERS)

        // One small request per chunk. Small selector sets are what the public instance can actually
        // answer; a failed chunk fails the request rather than silently returning a partial list that
        // would read as "there is nothing nearby".
        val collected = ArrayList<Place>()
        for (chunk in selectors.chunked(SELECTORS_PER_REQUEST)) {
            val batch = fetchChunk(center, radius, chunk)
            batch.getOrElse { return@withContext Result.failure(it) }
            collected += batch.getOrThrow()
        }
        // An empty result here is Overpass's genuine answer, not a failure: the area was searched
        // and had nothing of the requested kind.
        Result.success(PlaceQueryResult(withDistances(collected.distinctBy { it.id }, center), PlaceSource.Network))
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

    /**
     * One chunk against the endpoint list, best-first, at most one pass.
     *
     * Only availability failures (timeout / 5xx / rate limit) advance to the next endpoint — a
     * malformed body will be malformed everywhere, so retrying it only burns the user's time.
     */
    private fun fetchChunk(
        center: Coordinate,
        radiusMeters: Double,
        selectors: List<String>,
    ): Result<List<Place>> {
        var last: PlaceException = PlaceException.NoNetwork
        for (endpoint in endpointOrder()) {
            val attempt = runCatching { fetchOnce(endpoint, center, radiusMeters, selectors) }
            attempt.fold(
                onSuccess = { places ->
                    preferredEndpoint = endpoint
                    return Result.success(places)
                },
                onFailure = { error ->
                    last = when (error) {
                        is PlaceException -> error
                        is SocketTimeoutException -> PlaceException.Timeout
                        is JSONException -> PlaceException.Malformed
                        else -> PlaceException.NoNetwork
                    }
                    // Endpoint-independent: stop rather than repeating a bad parse on every mirror.
                    if (last === PlaceException.Malformed) return Result.failure(last)
                },
            )
        }
        return Result.failure(last)
    }

    /** Sticky-healthy endpoint first, then the rest in configured order, without duplicates. */
    private fun endpointOrder(): List<String> {
        val preferred = preferredEndpoint ?: return endpoints
        return listOf(preferred) + endpoints.filter { it != preferred }
    }

    private fun fetchOnce(
        endpoint: String,
        center: Coordinate,
        radiusMeters: Double,
        selectors: List<String>,
    ): List<Place> {
        val connection = URL(endpoint).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = attemptTimeoutMillis
        connection.doOutput = true
        connection.setRequestProperty("User-Agent", userAgent)
        connection.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        try {
            val body = "data=" + URLEncoder.encode(buildQuery(center, radiusMeters, selectors), "UTF-8")
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return when (val code = connection.responseCode) {
                HttpURLConnection.HTTP_OK -> {
                    val responseBody = connection.inputStream.bufferedReader().use { it.readText() }
                    OverpassResponseParser.parse(responseBody)
                }
                // 429 = rate limited, 504 = gateway timeout. Both mean "this mirror is busy right now",
                // which is exactly the case another mirror is there for.
                429, 504 -> throw PlaceException.RateLimited
                else -> throw PlaceException.Server(code)
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Builds one Overpass QL query.
     *
     * `nwr` matches nodes, ways and relations in a **single** clause per selector, where the previous
     * `node` + `way` pair doubled the clause count for no extra coverage. `out body center` bounds
     * the response and supplies a centroid for ways/areas (a car park or hotel is often mapped as a
     * polygon, not a point).
     */
    private fun buildQuery(center: Coordinate, radiusMeters: Double, selectors: List<String>): String {
        val radius = radiusMeters.toInt().coerceAtLeast(1)
        val clauses = selectors.joinToString("\n") { selector ->
            "  nwr$selector(around:$radius,${center.latitude},${center.longitude});"
        }
        return "[out:json][timeout:$OVERPASS_TIMEOUT_SECONDS];\n(\n$clauses\n);\nout body center $MAX_ELEMENTS;"
    }

    /** Stamps straight-line distance from [center] and caps the result count. */
    private fun withDistances(places: List<Place>, center: Coordinate): List<Place> =
        places
            .map { it.copy(distanceMeters = RouteCorridor.metersBetween(center, Coordinate(it.latitude, it.longitude))) }
            .sortedBy { it.distanceMeters }
            .take(MAX_RESULTS)

    companion object {
        /**
         * Initial preference order, fastest-measured first — and **global-coverage only**.
         *
         * Measured during Phase 12B against the real device location in Texas: `overpass.openstreetmap.fr`
         * is a full-planet instance that answered in **~0.5–1.4 s** with real named data (Love's Travel
         * Stop, Starbucks, Pizza Bella). `overpass-api.de` is the canonical global instance but slower
         * (**~10 s**); `maps.mail.ru` is global and healthy but wildly variable (10 s, >30 s, 17 s).
         *
         * **`overpass.osm.ch` is deliberately absent.** It is fast (~840 ms) but serves a *regional*
         * Swiss extract: for a Texas coordinate it answers `HTTP 200` with an *empty* element list.
         * Because an empty result is a real answer here (see [nearbyPlaces]), a regional mirror would
         * silently end the chain with a false "nothing nearby" — the exact dishonesty this phase is
         * about. Every endpoint listed must serve the whole planet.
         *
         * The *sticky* health memory — not this order — decides after the first success, because which
         * mirror is degraded changes day to day.
         */
        val DEFAULT_ENDPOINTS = listOf(
            "https://overpass.openstreetmap.fr/api/interpreter",
            "https://overpass-api.de/api/interpreter",
            "https://maps.mail.ru/osm/tools/overpass/api/interpreter",
        )

        const val CONNECT_TIMEOUT_MILLIS = 10_000

        /**
         * Per-attempt read budget. Deliberately *shorter* than the previous 25 s: a slow mirror is
         * worse than no mirror, because it blocks the UI. Failing over promptly and letting the
         * composite layer fall back to cache beats waiting.
         */
        const val ATTEMPT_TIMEOUT_MILLIS = 12_000

        const val OVERPASS_TIMEOUT_SECONDS = 20

        /** Selector-set size per request. Small enough for a busy public instance to answer. */
        const val SELECTORS_PER_REQUEST = 4

        const val MIN_NEARBY_RADIUS_METERS = 250.0
        const val MAX_NEARBY_RADIUS_METERS = 3_000.0
        const val MAX_ROUTE_RADIUS_METERS = 8_000.0

        private const val MAX_ELEMENTS = 60
        private const val MAX_RESULTS = 60
    }
}