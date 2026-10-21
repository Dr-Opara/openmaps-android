package com.openmaps.app.core.places

import com.openmaps.app.core.routing.Coordinate
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 12B: making Nearby Places usable against a provider that is measurably unreliable.
 *
 * The device finding was precise — "Nearby places unavailable right now" everywhere — and these lock
 * in the three properties that fix it: an empty result is not a failure, a failure falls through the
 * provider chain to stale cache, and a single-category request never becomes an all-category one.
 */
class CompositePlaceRepositoryTest {

    private val center = Coordinate(29.74469, -95.89488)

    private fun cachedPlace(id: String) = Place(
        id = id,
        name = "Place $id",
        category = PlaceCategory.Gas,
        latitude = center.latitude,
        longitude = center.longitude,
    )

    private class StubProvider(
        var result: Result<PlaceQueryResult>,
    ) : PlaceRepository {
        var calls = 0
        override suspend fun nearbyPlaces(
            center: Coordinate,
            radiusMeters: Double,
            categories: Set<PlaceCategory>,
        ): Result<PlaceQueryResult> {
            calls++
            return result
        }

        override suspend fun placesAlongRoute(
            routeGeometry: List<Coordinate>,
            corridorMeters: Double,
            categories: Set<PlaceCategory>,
        ): Result<List<Place>> = Result.success(emptyList())
    }

    @Test
    fun wideDiscovery_backendFailureNeverFallsBackTo3KmOverpass() = runTest {
        val backend = StubProvider(Result.failure(PlaceException.NoNetwork))
        val overpass = StubProvider(Result.success(PlaceQueryResult(listOf(cachedPlace("wrong-radius")), PlaceSource.Network)))
        val repo = CompositePlaceRepository(listOf(backend, overpass))

        val result = repo.nearbyPlaces(center, 32_187.0, setOf(PlaceCategory.Gas))

        assertTrue("wide failure must be honest", result.isFailure)
        assertEquals(1, backend.calls)
        assertEquals("the clamping Overpass provider must not report false 20-mile coverage", 0, overpass.calls)
    }

    @Test
    fun normalNearbyFailureStillFallsBackToOverpass() = runTest {
        val backend = StubProvider(Result.failure(PlaceException.NoNetwork))
        val overpass = StubProvider(Result.success(PlaceQueryResult(listOf(cachedPlace("normal")), PlaceSource.Network)))
        val repo = CompositePlaceRepository(listOf(backend, overpass))

        val result = repo.nearbyPlaces(center, 3_000.0, setOf(PlaceCategory.Gas)).getOrThrow()

        assertEquals(listOf("normal"), result.places.map { it.id })
        assertEquals(1, overpass.calls)
    }

    @Test
    fun primarySuccess_isReturnedAsNetworkData() = runTest {
        val primary = StubProvider(Result.success(PlaceQueryResult(listOf(cachedPlace("a")), PlaceSource.Network)))
        val repo = CompositePlaceRepository(listOf(primary))

        val result = repo.nearbyPlaces(center, 1_000.0, setOf(PlaceCategory.Gas)).getOrThrow()

        assertEquals(listOf("a"), result.places.map { it.id })
        assertEquals(PlaceSource.Network, result.source)
        assertFalse(result.isCached)
    }

    /** The bug that made the app look permanently broken: a dead primary hid a working secondary. */
    @Test
    fun primaryFailure_fallsThroughToTheSecondaryProvider() = runTest {
        val primary = StubProvider(Result.failure(PlaceException.RateLimited))
        val secondary = StubProvider(Result.success(PlaceQueryResult(listOf(cachedPlace("b")), PlaceSource.Network)))
        val repo = CompositePlaceRepository(listOf(primary, secondary))

        val result = repo.nearbyPlaces(center, 1_000.0, setOf(PlaceCategory.Gas)).getOrThrow()

        assertEquals(1, primary.calls)
        assertEquals(1, secondary.calls)
        assertEquals(listOf("b"), result.places.map { it.id })
    }

    /** "No results" and "could not reach the provider" are different facts and must never merge. */
    @Test
    fun anEmptyResultIsASuccess_notAFailure() = runTest {
        val provider = StubProvider(Result.success(PlaceQueryResult(emptyList(), PlaceSource.Network)))
        val repo = CompositePlaceRepository(listOf(provider))

        val result = repo.nearbyPlaces(center, 1_000.0, setOf(PlaceCategory.Gas))

        assertTrue("an empty area is not a provider failure", result.isSuccess)
        assertTrue(result.getOrThrow().places.isEmpty())
    }

    @Test
    fun everyProviderFailing_withNoCachedData_returnsTheRealFailure() = runTest {
        val repo = CompositePlaceRepository(
            listOf(
                StubProvider(Result.failure(PlaceException.Timeout)),
                StubProvider(Result.failure(PlaceException.NoNetwork)),
            ),
        )

        val result = repo.nearbyPlaces(center, 1_000.0, setOf(PlaceCategory.Gas))

        assertTrue(result.isFailure)
        assertEquals(PlaceException.NoNetwork, result.exceptionOrNull())
    }

    @Test
    fun aFreshCacheHit_avoidsTheNetworkEntirely() = runTest {
        var now = 1_000L
        val cache = PlaceCache(ttlMillis = 10_000, staleTtlMillis = 60_000, now = { now })
        val provider = StubProvider(Result.success(PlaceQueryResult(listOf(cachedPlace("cached")), PlaceSource.Network)))
        val repo = CompositePlaceRepository(listOf(provider), cache)

        val first = repo.nearbyPlaces(center, 1_000.0, setOf(PlaceCategory.Gas)).getOrThrow()
        assertEquals(PlaceSource.Network, first.source)

        val second = repo.nearbyPlaces(center, 1_000.0, setOf(PlaceCategory.Gas)).getOrThrow()

        assertEquals("the second identical request must not hit the network", 1, provider.calls)
        assertEquals(PlaceSource.Cache, second.source)
        assertEquals(listOf("cached"), second.places.map { it.id })
    }

    /** Same category, barely moved: the location bucket must absorb it. */
    @Test
    fun sameCategoryAndNearbyLocation_servesFromCache() = runTest {
        val provider = StubProvider(Result.success(PlaceQueryResult(listOf(cachedPlace("x")), PlaceSource.Network)))
        val repo = CompositePlaceRepository(listOf(provider))

        repo.nearbyPlaces(center, 1_000.0, setOf(PlaceCategory.Gas)).getOrThrow()
        // ~50 m north — inside the same 0.005° bucket.
        repo.nearbyPlaces(Coordinate(center.latitude + 0.0004, center.longitude), 1_000.0, setOf(PlaceCategory.Gas))
            .getOrThrow()

        assertEquals("a small move in the same bucket must not refetch", 1, provider.calls)
    }

    @Test
    fun aDifferentCategory_isACacheMiss() = runTest {
        val provider = StubProvider(Result.success(PlaceQueryResult(listOf(cachedPlace("x")), PlaceSource.Network)))
        val repo = CompositePlaceRepository(listOf(provider))

        repo.nearbyPlaces(center, 1_000.0, setOf(PlaceCategory.Gas)).getOrThrow()
        repo.nearbyPlaces(center, 1_000.0, setOf(PlaceCategory.Restaurant)).getOrThrow()

        assertEquals("the cache key includes the category", 2, provider.calls)
    }

    @Test
    fun aDifferentRadius_isACacheMiss() = runTest {
        val provider = StubProvider(Result.success(PlaceQueryResult(listOf(cachedPlace("x")), PlaceSource.Network)))
        val repo = CompositePlaceRepository(listOf(provider))

        repo.nearbyPlaces(center, 1_000.0, setOf(PlaceCategory.Gas)).getOrThrow()
        repo.nearbyPlaces(center, 3_000.0, setOf(PlaceCategory.Gas)).getOrThrow()

        assertEquals("the cache key includes the radius", 2, provider.calls)
    }

    /** A total outage should degrade to saved data — clearly labelled — not to an error screen. */
    @Test
    fun everyProviderFailing_fallsBackToStaleCacheAndFlagsIt() = runTest {
        var now = 1_000L
        val cache = PlaceCache(ttlMillis = 1_000, staleTtlMillis = 60_000, now = { now })
        var healthy = true
        val provider = object : PlaceRepository {
            var calls = 0
            override suspend fun nearbyPlaces(
                center: Coordinate,
                radiusMeters: Double,
                categories: Set<PlaceCategory>,
            ): Result<PlaceQueryResult> {
                calls++
                return if (healthy) {
                    Result.success(PlaceQueryResult(listOf(cachedPlace("saved")), PlaceSource.Network))
                } else {
                    Result.failure(PlaceException.Timeout)
                }
            }

            override suspend fun placesAlongRoute(
                routeGeometry: List<Coordinate>,
                corridorMeters: Double,
                categories: Set<PlaceCategory>,
            ): Result<List<Place>> = Result.success(emptyList())
        }
        val repo = CompositePlaceRepository(listOf(provider), cache)

        repo.nearbyPlaces(center, 1_000.0, setOf(PlaceCategory.Gas)).getOrThrow()
        now += 5_000 // past the fresh window, inside the stale window
        healthy = false

        val result = repo.nearbyPlaces(center, 1_000.0, setOf(PlaceCategory.Gas)).getOrThrow()

        assertEquals(PlaceSource.StaleCache, result.source)
        assertTrue("stale data must be flagged, never presented as live", result.isCached)
        assertEquals(listOf("saved"), result.places.map { it.id })
    }

    @Test
    fun aCacheEntryOlderThanTheStaleWindow_doesNotMaskAFailure() = runTest {
        var now = 1_000L
        val cache = PlaceCache(ttlMillis = 100, staleTtlMillis = 500, now = { now })
        var healthy = true
        val provider = object : PlaceRepository {
            override suspend fun nearbyPlaces(
                center: Coordinate,
                radiusMeters: Double,
                categories: Set<PlaceCategory>,
            ): Result<PlaceQueryResult> =
                if (healthy) Result.success(PlaceQueryResult(listOf(cachedPlace("old")), PlaceSource.Network))
                else Result.failure(PlaceException.Timeout)

            override suspend fun placesAlongRoute(
                routeGeometry: List<Coordinate>,
                corridorMeters: Double,
                categories: Set<PlaceCategory>,
            ): Result<List<Place>> = Result.success(emptyList())
        }
        val repo = CompositePlaceRepository(listOf(provider), cache)

        repo.nearbyPlaces(center, 1_000.0, setOf(PlaceCategory.Gas)).getOrThrow()
        now += 10_000 // beyond even the stale window
        healthy = false

        assertTrue(repo.nearbyPlaces(center, 1_000.0, setOf(PlaceCategory.Gas)).isFailure)
    }

    @Test
    fun noCategories_isASuccessfulEmptyAnswer() = runTest {
        val provider = StubProvider(Result.failure(PlaceException.NoNetwork))
        val repo = CompositePlaceRepository(listOf(provider))

        val result = repo.nearbyPlaces(center, 1_000.0, emptySet())

        assertTrue(result.isSuccess)
        assertEquals(0, provider.calls)
    }

    @Test
    fun noProvidersConfigured_reportsUnavailableRatherThanEmpty() = runTest {
        val repo = CompositePlaceRepository(emptyList())

        assertTrue(
            "no provider must be an explicit failure, never a false 'nothing nearby'",
            repo.nearbyPlaces(center, 1_000.0, setOf(PlaceCategory.Gas)).isFailure,
        )
    }
}

/** The cache itself: bucketing, TTL and the stale window, on a controllable clock. */
class PlaceCacheStaleWindowTest {

    private val center = Coordinate(29.74469, -95.89488)
    private val categories = setOf(PlaceCategory.Gas)

    private fun cachedPlace(id: String) = Place(
        id = id, name = "P$id", category = PlaceCategory.Gas,
        latitude = center.latitude, longitude = center.longitude,
    )

    @Test
    fun missOnAnEmptyCache() {
        val cache = PlaceCache()
        assertEquals(null, cache.get(center, 1_000.0, categories))
    }

    @Test
    fun putThenGetWithinTtl_isAHit() {
        val cache = PlaceCache(ttlMillis = 10_000, now = { 0L })
        cache.put(center, 1_000.0, categories, listOf(cachedPlace("a")))
        assertEquals(listOf("a"), cache.get(center, 1_000.0, categories)?.map { it.id })
    }

    @Test
    fun expiredEntry_isAMiss() {
        var now = 0L
        val cache = PlaceCache(ttlMillis = 100, staleTtlMillis = 10_000, now = { now })
        cache.put(center, 1_000.0, categories, listOf(cachedPlace("a")))
        now += 200

        assertEquals(null, cache.get(center, 1_000.0, categories))
        assertEquals("past TTL but inside the stale window is still readable", listOf("a"), cache.getStale(center, 1_000.0, categories)?.map { it.id })
    }

    @Test
    fun beyondTheStaleWindow_nothingIsReturned() {
        var now = 0L
        val cache = PlaceCache(ttlMillis = 100, staleTtlMillis = 200, now = { now })
        cache.put(center, 1_000.0, categories, listOf(cachedPlace("a")))
        now += 500

        assertEquals(null, cache.getStale(center, 1_000.0, categories))
    }

    @Test
    fun theLocationBucket_absorbsSmallMovement() {
        val cache = PlaceCache()
        cache.put(center, 1_000.0, categories, listOf(cachedPlace("a")))
        val drifted = Coordinate(center.latitude + 0.0002, center.longitude + 0.0002)
        assertEquals(listOf("a"), cache.get(drifted, 1_000.0, categories)?.map { it.id })
    }

    @Test
    fun realMovement_fallsOutsideTheBucket() {
        val cache = PlaceCache()
        cache.put(center, 1_000.0, categories, listOf(cachedPlace("a")))
        val far = Coordinate(center.latitude + 0.05, center.longitude + 0.05)
        assertEquals(null, cache.get(far, 1_000.0, categories))
    }

    @Test
    fun differentCategories_doNotShareAnEntry() {
        val cache = PlaceCache()
        cache.put(center, 1_000.0, setOf(PlaceCategory.Gas), listOf(cachedPlace("a")))
        assertEquals(null, cache.get(center, 1_000.0, setOf(PlaceCategory.Restaurant)))
    }
}