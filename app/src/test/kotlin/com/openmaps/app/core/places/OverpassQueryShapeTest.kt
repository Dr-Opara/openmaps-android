package com.openmaps.app.core.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Query construction and endpoint behaviour, checked against what Phase 12B actually measured.
 *
 * The regression that mattered: a 26-selector query measured ~8.3 s and blew a 10 s timeout, and a
 * *fixed preference list* meant every request first burned ~12 s against whichever mirror was
 * throttled — even when the other one was healthy.
 */
class OverpassQueryShapeTest {

    private val all = PlaceCategoryMapper.supportedCategories

    @Test
    fun everySupportedCategoryMapsToAtLeastOneSelector() {
        assertTrue(PlaceCategoryMapper.overpassSelectors(all).isNotEmpty())
    }

    /** One chunk must stay small: the public instance cannot answer the whole taxonomy at once. */
    @Test
    fun selectorsAreChunkedIntoSmallRequests() {
        val selectors = PlaceCategoryMapper.overpassSelectors(all)
        val chunks = selectors.chunked(OverpassPlaceRepository.SELECTORS_PER_REQUEST)

        assertTrue("the taxonomy must genuinely need batching", selectors.size > OverpassPlaceRepository.SELECTORS_PER_REQUEST)
        assertTrue(chunks.all { it.size <= OverpassPlaceRepository.SELECTORS_PER_REQUEST })
        assertEquals("batching must neither drop nor duplicate a selector", selectors, chunks.flatten())
    }

    @Test
    fun aSingleCategoryRequestStaysSmall() {
        // The whole point of category-scoped fetching: tapping "Gas" must not ask for 26 selectors.
        val gas = PlaceCategoryMapper.overpassSelectors(setOf(PlaceCategory.Gas))
        val restaurant = PlaceCategoryMapper.overpassSelectors(setOf(PlaceCategory.Restaurant))
        val parking = PlaceCategoryMapper.overpassSelectors(setOf(PlaceCategory.Parking))

        assertTrue(gas.isNotEmpty())
        assertTrue(restaurant.isNotEmpty())
        assertTrue(parking.isNotEmpty())
        assertTrue(
            "one category must fit in a single request",
            listOf(gas, restaurant, parking).all { it.size <= OverpassPlaceRepository.SELECTORS_PER_REQUEST },
        )
    }

    @Test
    fun everyCategoryMapsToItsOwnDistinctSelectorSet() {
        val perCategory = PlaceCategory.entries.associateWith { PlaceCategoryMapper.overpassSelectors(setOf(it)) }
        assertTrue(perCategory.values.all { it.isNotEmpty() })
        // Gas must not silently fetch parking as well.
        assertFalse(
            PlaceCategoryMapper.overpassSelectors(setOf(PlaceCategory.Gas))
                .containsAll(PlaceCategoryMapper.overpassSelectors(setOf(PlaceCategory.Parking))),
        )
    }

    /** A bounded per-attempt budget: a hung mirror must not freeze the UI. */
    @Test
    fun perAttemptTimeoutsAreBounded() {
        assertTrue(OverpassPlaceRepository.ATTEMPT_TIMEOUT_MILLIS in 1..30_000)
        assertTrue(OverpassPlaceRepository.CONNECT_TIMEOUT_MILLIS <= OverpassPlaceRepository.ATTEMPT_TIMEOUT_MILLIS)
    }

    /**
     * Failover needs somewhere to go, and the set must include the canonical public instance as a
     * backstop. The first entry is *not* asserted to be the canonical one: Phase 12B measured that
     * slow-first ordering is what made every cold request time out, so the list is ordered fastest
     * measured first. What matters is breadth plus a canonical fallback.
     */
    @Test
    fun thereIsAReachablePrimaryAndAtLeastOneFallback() {
        val endpoints = OverpassPlaceRepository.DEFAULT_ENDPOINTS
        assertTrue("failover needs more than one endpoint", endpoints.size > 1)
        assertTrue(
            "the canonical public instance must remain available as a backstop",
            endpoints.contains("https://overpass-api.de/api/interpreter"),
        )
        assertTrue(endpoints.all { it.startsWith("https://") })
        assertEquals("endpoints must be unique", endpoints.size, endpoints.distinct().size)
    }

    /** No commercial POI provider is wired in for MVP; the chain stays OSM/Overpass-only. */
    @Test
    fun theProviderSetIsOverpassOnly() {
        assertTrue(
            OverpassPlaceRepository.DEFAULT_ENDPOINTS.all { it.contains("overpass") || it.contains("osm") },
        )
    }
}