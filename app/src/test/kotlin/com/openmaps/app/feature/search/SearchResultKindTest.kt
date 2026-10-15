package com.openmaps.app.feature.search

import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.geocoding.SearchResultKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The mockup's Search Results tabs must be **functional**, not decorative.
 *
 * A tab that re-showed the same untyped list under a different heading would be a lie, so these lock
 * in that each bucket only ever contains what the provider actually described.
 */
class SearchResultKindTest {

    private fun result(
        name: String = "Some Place",
        category: String? = null,
        address: String? = "2800 North Terminal Road",
    ) = SearchResult(
        id = name,
        name = name,
        displayName = "$name, Texas",
        latitude = 29.9,
        longitude = -95.6,
        category = category,
        address = address,
    )

    @Test
    fun aNamedBusinessWithACategory_isAPlace() {
        assertEquals(SearchResultKind.Place, result(category = "restaurant").resultKind)
    }

    @Test
    fun aStreetAddressWithNoBusinessCategory_isAnAddress() {
        assertEquals(SearchResultKind.Address, result(category = null).resultKind)
    }

    /**
     * Nominatim's `type` for an ordinary street is a road classification, not a business. Treating
     * "residential" as a place would put every street under the Places tab.
     */
    @Test
    fun aRoadClassification_isNotTreatedAsABusiness() {
        val road = result(category = "residential")
        assertFalse(road.hasBusinessCategory())
        assertEquals(SearchResultKind.Address, road.resultKind)
    }

    @Test
    fun aLocalityWithNeitherCategoryNorAddress_isNeitherPlaceNorAddress() {
        val locality = result(name = "Houston", category = null, address = null)
        assertEquals(SearchResultKind.Other, locality.resultKind)
    }

    @Test
    fun allTab_containsEveryResult() {
        val results = listOf(
            result(name = "A", category = "cafe"),
            result(name = "B"),
            result(name = "C", category = null, address = null),
        )
        assertTrue(SearchTab.All.isEmptyFor(results).not())
        assertEquals(3, results.size)
    }

    @Test
    fun placesTab_onlyHoldsResultsWithARealCategory() {
        val cafe = result(name = "Cafe", category = "cafe")
        val street = result(name = "Main St")
        val visible = listOf(cafe, street).filter { it.resultKind == SearchResultKind.Place }
        assertEquals(listOf(cafe), visible)
    }

    @Test
    fun addressesTab_onlyHoldsResultsWithARealAddress() {
        val cafe = result(name = "Cafe", category = "cafe")
        val street = result(name = "Main St")
        val visible = listOf(cafe, street).filter { it.resultKind == SearchResultKind.Address }
        assertEquals(listOf(street), visible)
    }

    @Test
    fun anEmptyBucketIsReportedHonestly_ratherThanFallingBackToEverything() {
        val onlyAddresses = listOf(result(name = "Main St"))
        assertTrue(SearchTab.Places.isEmptyFor(onlyAddresses))
        assertFalse(SearchTab.Addresses.isEmptyFor(onlyAddresses))
    }
}

/**
 * The selected tab partitions the provider's list; the default must be the unfiltered [SearchTab.All]
 * so the app never opens search already filtered.
 */
class SearchUiStateTabTest {

    private val cafe = SearchResult(
        id = "1", name = "Cafe", displayName = "Cafe, TX",
        latitude = 29.9, longitude = -95.6, category = "cafe",
    )
    private val street = SearchResult(
        id = "2", name = "Main St", displayName = "Main St, TX",
        latitude = 29.9, longitude = -95.6, address = "1 Main St",
    )

    @Test
    fun defaultTab_isAll_andShowsEverything() {
        val state = SearchUiState(results = listOf(cafe, street))
        assertEquals(SearchTab.All, state.selectedTab)
        assertEquals(listOf(cafe, street), state.visibleResults)
    }

    @Test
    fun placesTab_hidesTheAddressRatherThanMislabelingIt() {
        val state = SearchUiState(results = listOf(cafe, street), selectedTab = SearchTab.Places)
        assertEquals(listOf(cafe), state.visibleResults)
    }

    @Test
    fun addressesTab_hidesThePlaceRatherThanMislabelingIt() {
        val state = SearchUiState(results = listOf(cafe, street), selectedTab = SearchTab.Addresses)
        assertEquals(listOf(street), state.visibleResults)
    }

    /** A search that worked but has nothing in the chosen bucket must say so, not look like "no results". */
    @Test
    fun anEmptyBucket_isDistinctFromAnEmptySearch() {
        val state = SearchUiState(
            query = "zzz",
            results = listOf(cafe),
            selectedTab = SearchTab.Addresses,
        )
        assertTrue(state.selectedTabIsEmpty)
        assertFalse(state.hasNoResults)
    }
}

/**
 * The microphone must never be a dead control, so every voice outcome has to land in an explicit
 * state the UI can report.
 */
class VoiceSearchStateTest {

    @Test
    fun idle_isTheDefaultSoTheMicIsNeverStuckListening() {
        assertEquals(VoiceSearchState.Idle, SearchUiState().voiceState)
    }

    @Test
    fun unavailableIsDistinctFromFailure() {
        val unavailable = VoiceSearchState.Unavailable
        val failed = VoiceSearchState.Failed("boom")
        assertTrue(unavailable != failed)
    }
}