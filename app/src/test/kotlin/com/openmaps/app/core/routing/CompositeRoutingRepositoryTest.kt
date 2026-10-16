package com.openmaps.app.core.routing

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class CompositeRoutingRepositoryTest {

    @Test
    fun failedPrimaryUsesFallback() = runTest {
        val primary = FakeRoutingRepository(Result.failure(RoutingException.Server(503)))
        val fallbackRoute = RouteResult(
            geometry = listOf(Coordinate(1.0, 2.0), Coordinate(1.1, 2.1)),
            distanceMeters = 1000.0,
            durationSeconds = 100.0,
        )
        val fallback = FakeRoutingRepository(Result.success(fallbackRoute))

        val result = CompositeRoutingRepository(listOf(primary, fallback))
            .route(Coordinate(1.0, 2.0), Coordinate(1.1, 2.1))
            .getOrThrow()

        assertEquals(1000.0, result.distanceMeters, 0.0)
        assertEquals(1, primary.calls)
        assertEquals(1, fallback.calls)
    }

    private class FakeRoutingRepository(
        private val response: Result<RouteResult>,
    ) : RoutingRepository {
        var calls = 0
        override suspend fun route(origin: Coordinate, destination: Coordinate): Result<RouteResult> {
            calls++
            return response
        }
    }
}
