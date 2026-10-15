package com.openmaps.app.core.places

import com.openmaps.app.core.routing.Coordinate

/**
 * The places chain: **cache → providers in order → stale cache → explicit failure**.
 *
 * This is the composition the feature layer talks to; the individual providers stay unaware of each
 * other. It exists because Phase 12B measured the failure mode precisely: the canonical public
 * Overpass instance was returning 504 while a mirror was returning real data, and the app showed
 * "Nearby places unavailable right now" instead of the data it already could have had.
 *
 * Behaviour, in order:
 *  1. **Fresh cache** — returned with no network call at all.
 *  2. **Providers, in order** — the first non-failure wins. Currently a single
 *     [OverpassPlaceRepository] (which itself fails over across its mirrors), so adding a production
 *     provider later is one more entry and zero UI changes.
 *  3. **Stale cache** — if every provider fails but we once had an answer for this area, that answer
 *     is returned **flagged stale** rather than showing an error over usable data.
 *  4. **Failure** — only when there is genuinely nothing to show, and it carries the real reason so
 *     the UI can say "unavailable" instead of pretending the neighbourhood is empty.
 */
class CompositePlaceRepository(
    /** Tried in order. One Overpass repository today; a production provider slots in beside it. */
    private val providers: List<PlaceRepository>,
    private val cache: PlaceCache = PlaceCache(),
) : PlaceRepository {

    override suspend fun nearbyPlaces(
        center: Coordinate,
        radiusMeters: Double,
        categories: Set<PlaceCategory>,
    ): Result<PlaceQueryResult> {
        // Nothing to search for is a successful empty answer, never a failure.
        if (categories.isEmpty()) return Result.success(PlaceQueryResult(emptyList(), PlaceSource.Empty))
        if (providers.isEmpty()) return Result.failure(PlaceException.NoNetwork)

        // 1. Fresh cache — no network call at all.
        cache.get(center, radiusMeters, categories)?.let {
            return Result.success(PlaceQueryResult(it, PlaceSource.Cache))
        }

        // 2. Providers, in order. The first success wins; an empty success is a real answer and ends
        //    the chain rather than falling through to a second provider for the same area.
        var lastFailure: PlaceException = PlaceException.NoNetwork
        for (provider in providers) {
            val attempt = runCatching { provider.nearbyPlaces(center, radiusMeters, categories) }
            val value = attempt.getOrNull()
            if (value != null && value.isSuccess) {
                val result = value.getOrThrow()
                // This layer owns the cache, so it is also the only thing that writes it. An empty
                // result is cached too: "this area has no gas stations" is a real, reusable answer.
                cache.put(center, radiusMeters, categories, result.places)
                return Result.success(result)
            }
            lastFailure = value?.exceptionOrNull()?.asPlaceException()
                ?: attempt.exceptionOrNull()?.asPlaceException()
                ?: lastFailure
        }

        // 3. Stale cache. Recent data beats an error screen, provided we label it stale.
        cache.getStale(center, radiusMeters, categories)?.let {
            return Result.success(PlaceQueryResult(it, PlaceSource.StaleCache))
        }
        // 4. Honest failure with the real reason.
        return Result.failure(lastFailure)
    }

    override suspend fun placesAlongRoute(
        routeGeometry: List<Coordinate>,
        corridorMeters: Double,
        categories: Set<PlaceCategory>,
    ): Result<List<Place>> {
        if (routeGeometry.size < 2) return Result.success(emptyList())
        val bounds = RouteCorridor.boundingQuery(routeGeometry, corridorMeters, MAX_ROUTE_RADIUS_METERS)
        return nearbyPlaces(bounds.center, bounds.radiusMeters, categories).map { it.places }
    }

    private fun Throwable.asPlaceException(): PlaceException = when (this) {
        is PlaceException -> this
        is java.net.SocketTimeoutException -> PlaceException.Timeout
        is org.json.JSONException -> PlaceException.Malformed
        is java.io.IOException -> PlaceException.NoNetwork
        else -> PlaceException.NoNetwork
    }

    private companion object {
        const val MAX_ROUTE_RADIUS_METERS = 8_000.0
    }
}

/**
 * A places answer plus **where it came from**, so the UI can be honest about freshness.
 *
 * The distinction matters: a stale-cache list must not be presented as live, and an empty live
 * result must never be dressed up as one.
 */
data class PlaceQueryResult(
    val places: List<Place>,
    val source: PlaceSource,
) {
    /** True when the answer came from cache, fresh or stale. */
    val isCached: Boolean get() = source != PlaceSource.Network
}

/** Provenance of a places answer. */
enum class PlaceSource {
    /** Straight from a provider. */
    Network,

    /** From cache and still within its fresh window. */
    Cache,

    /** From cache past its fresh window, used only because every provider failed. */
    StaleCache,

    /** Nothing to search for (no categories selected). */
    Empty,
}