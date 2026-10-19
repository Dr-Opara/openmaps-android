package com.openmaps.app.core.routing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CachedRoutingRepositoryTest {

    @Test
    fun networkFailureUsesMatchingFreshCachedRouteWithoutStaleTraffic() = runTest {
        var now = 1_000_000L
        val store = MemoryStore()
        val liveRoute = RouteResult(
            geometry = listOf(Coordinate(29.7604, -95.3698), Coordinate(29.7704, -95.3598)),
            distanceMeters = 2_000.0,
            durationSeconds = 240.0,
            baseDurationSeconds = 180.0,
            trafficDelaySeconds = 60.0,
            isTrafficAware = true,
        )
        val origin = Coordinate(29.7604, -95.3698)
        val destination = Coordinate(29.7704, -95.3598)

        val repository = CachedRoutingRepository(
            store = store,
            upstream = SequenceRepository(
                mutableListOf(
                    Result.success(liveRoute),
                    Result.failure(RoutingException.NoNetwork),
                ),
            ),
            nowMillis = { now },
        )

        repository.route(origin, destination).getOrThrow()
        now += 60_000
        val cached = repository.route(origin, destination).getOrThrow()

        assertTrue(cached.isOfflineCached)
        assertFalse(cached.isTrafficAware)
        assertNull(cached.baseDurationSeconds)
        assertNull(cached.trafficDelaySeconds)
    }

    @Test
    fun cacheDoesNotServeDifferentDestination() = runTest {
        val store = MemoryStore()
        val origin = Coordinate(29.7604, -95.3698)
        val destination = Coordinate(29.7704, -95.3598)
        val liveRoute = RouteResult(
            geometry = listOf(origin, destination),
            distanceMeters = 2_000.0,
            durationSeconds = 240.0,
        )
        val repository = CachedRoutingRepository(
            store = store,
            upstream = SequenceRepository(
                mutableListOf(
                    Result.success(liveRoute),
                    Result.failure(RoutingException.NoNetwork),
                ),
            ),
            nowMillis = { 1_000_000L },
        )

        repository.route(origin, destination).getOrThrow()
        val result = repository.route(origin, Coordinate(30.2672, -97.7431))

        assertTrue(result.isFailure)
    }

    @Test
    fun expiredCacheIsNotServed() = runTest {
        var now = 1_000_000L
        val store = MemoryStore()
        val origin = Coordinate(29.7604, -95.3698)
        val destination = Coordinate(29.7704, -95.3598)
        val route = RouteResult(
            geometry = listOf(origin, destination),
            distanceMeters = 2_000.0,
            durationSeconds = 240.0,
        )
        val repository = CachedRoutingRepository(
            store = store,
            upstream = SequenceRepository(
                mutableListOf(
                    Result.success(route),
                    Result.failure(RoutingException.NoNetwork),
                ),
            ),
            maxAgeMillis = 1_000L,
            nowMillis = { now },
        )

        repository.route(origin, destination).getOrThrow()
        now += 2_000L
        val result = repository.route(origin, destination)

        assertTrue(result.isFailure)
    }

    private class MemoryStore : RouteCacheStore {
        private var value: String? = null
        override fun get(): String? = value
        override fun put(value: String) { this.value = value }
        override fun clear() { value = null }
    }

    private class SequenceRepository(
        private val responses: MutableList<Result<RouteResult>>,
    ) : RoutingRepository {
        override suspend fun route(origin: Coordinate, destination: Coordinate): Result<RouteResult> =
            responses.removeAt(0)
    }
}
