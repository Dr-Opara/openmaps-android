package com.openmaps.app.core.geocoding

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositeGeocodingRepositoryTest {

    @Test
    fun firstNonEmptyProviderWins() = runTest {
        val primary = FakeRepository(Result.success(listOf(result("primary"))))
        val fallback = FakeRepository(Result.success(listOf(result("fallback"))))
        val repository = CompositeGeocodingRepository(listOf(primary, fallback))

        val results = repository.search("school").getOrThrow()

        assertEquals(listOf("primary"), results.map { it.id })
        assertEquals(1, primary.calls)
        assertEquals(0, fallback.calls)
    }

    @Test
    fun emptyPrimaryFallsBack() = runTest {
        val primary = FakeRepository(Result.success(emptyList()))
        val fallback = FakeRepository(Result.success(listOf(result("fallback"))))
        val repository = CompositeGeocodingRepository(listOf(primary, fallback))

        val results = repository.search("2880 Jordan Ranch Blvd").getOrThrow()

        assertEquals(listOf("fallback"), results.map { it.id })
        assertEquals(1, fallback.calls)
    }

    @Test
    fun failedPrimaryFallsBack() = runTest {
        val primary = FakeRepository(Result.failure(GeocodingException.Timeout))
        val fallback = FakeRepository(Result.success(listOf(result("fallback"))))
        val repository = CompositeGeocodingRepository(listOf(primary, fallback))

        assertEquals("fallback", repository.search("Melton Elementary").getOrThrow().single().id)
    }

    @Test
    fun allFailedReturnsFailure() = runTest {
        val repository = CompositeGeocodingRepository(
            listOf(
                FakeRepository(Result.failure(GeocodingException.Timeout)),
                FakeRepository(Result.failure(GeocodingException.NoNetwork)),
            ),
        )

        assertTrue(repository.search("test").isFailure)
    }

    @Test
    fun successfulEmptyFromAnyProviderBeatsFailure() = runTest {
        val repository = CompositeGeocodingRepository(
            listOf(
                FakeRepository(Result.failure(GeocodingException.Timeout)),
                FakeRepository(Result.success(emptyList())),
            ),
        )

        assertTrue(repository.search("not-a-place").getOrThrow().isEmpty())
    }

    private fun result(id: String) = SearchResult(
        id = id,
        name = "Melton Elementary School",
        displayName = "Melton Elementary School, Brookshire, Texas",
        latitude = 29.75,
        longitude = -95.89,
        address = "2880 Jordan Ranch Blvd",
        city = "Brookshire",
        state = "Texas",
        country = "United States",
    )

    private class FakeRepository(
        private val response: Result<List<SearchResult>>,
    ) : GeocodingRepository {
        var calls = 0
        override suspend fun search(
            query: String,
            context: GeocodingSearchContext,
        ): Result<List<SearchResult>> {
            calls++
            return response
        }
    }
}
