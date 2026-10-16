package com.openmaps.app.feature.search

import com.openmaps.app.core.geocoding.GeocodingException
import com.openmaps.app.core.geocoding.GeocodingRepository
import com.openmaps.app.core.geocoding.GeocodingSearchContext
import com.openmaps.app.core.geocoding.RecentSearchList
import com.openmaps.app.core.geocoding.RecentSearchStore
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.UserLocation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SearchViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = FakeGeocodingRepository()
    private val recentStore = FakeRecentSearchStore()
    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = SearchViewModel(repository, recentStore, debounceMillis = DEBOUNCE)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun blankQuery_neverSearches() = runTest(dispatcher) {
        viewModel.onQueryChanged("")
        dispatcher.scheduler.advanceTimeBy(DEBOUNCE + 1)
        assertTrue(repository.queriesReceived.isEmpty())
        assertTrue(viewModel.uiState.value.results.isEmpty())
    }

    @Test
    fun tooShortQuery_neverSearches() = runTest(dispatcher) {
        viewModel.onQueryChanged("ab") // below MIN_QUERY_LENGTH (3)
        dispatcher.scheduler.advanceTimeBy(DEBOUNCE + 1)
        assertTrue(repository.queriesReceived.isEmpty())
    }

    @Test
    fun typingQuickly_onlySearchesTheFinalQuery() = runTest(dispatcher) {
        viewModel.onQueryChanged("ca")
        viewModel.onQueryChanged("caf")
        viewModel.onQueryChanged("cafe")
        dispatcher.scheduler.advanceTimeBy(DEBOUNCE + 1)
        assertEquals(listOf("cafe"), repository.queriesReceived)
    }

    @Test
    fun sameNormalizedQuery_doesNotSpendAnotherProviderRequest() = runTest(dispatcher) {
        viewModel.onQueryChanged("Melton Elementary")
        dispatcher.scheduler.advanceTimeBy(DEBOUNCE + 1)
        viewModel.onQueryChanged("  melton   elementary  ")
        dispatcher.scheduler.advanceTimeBy(DEBOUNCE + 1)

        assertEquals(listOf("Melton Elementary"), repository.queriesReceived)
    }

    @Test
    fun twoCharacterNoise_neverCallsProvider() = runTest(dispatcher) {
        viewModel.onQueryChanged("me")
        dispatcher.scheduler.advanceTimeBy(DEBOUNCE + 1)

        assertTrue(repository.queriesReceived.isEmpty())
    }

    @Test
    fun searchUsesLatestLocationBias() = runTest(dispatcher) {
        viewModel.onLocationBiasChanged(
            UserLocation(
                latitude = 29.75,
                longitude = -95.75,
                accuracyMeters = 5f,
                bearingDegrees = null,
                timestampMillis = 1L,
                speedMetersPerSecond = null,
            ),
        )
        viewModel.onQueryChanged("coffee")
        dispatcher.scheduler.advanceTimeBy(DEBOUNCE + 1)

        assertEquals(29.75, repository.contextsReceived.single().latitude ?: 0.0, 0.0)
        assertEquals(-95.75, repository.contextsReceived.single().longitude ?: 0.0, 0.0)
    }

    @Test
    fun successfulSearch_populatesResults() = runTest(dispatcher) {
        repository.result = Result.success(listOf(place("1"), place("2")))
        viewModel.onQueryChanged("coffee")
        dispatcher.scheduler.advanceTimeBy(DEBOUNCE + 1)

        assertEquals(2, viewModel.uiState.value.results.size)
        assertFalse(viewModel.uiState.value.isSearching)
        assertNull(viewModel.uiState.value.error)
    }

    @Test
    fun emptySuccessfulResult_isNotAnError() = runTest(dispatcher) {
        repository.result = Result.success(emptyList())
        viewModel.onQueryChanged("asdfghjkl")
        dispatcher.scheduler.advanceTimeBy(DEBOUNCE + 1)

        assertTrue(viewModel.uiState.value.results.isEmpty())
        assertNull(viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.hasNoResults)
    }

    @Test
    fun providerFailure_mapsToTypedSearchError() = runTest(dispatcher) {
        repository.result = Result.failure(GeocodingException.NoNetwork)
        viewModel.onQueryChanged("coffee")
        dispatcher.scheduler.advanceTimeBy(DEBOUNCE + 1)

        assertEquals(SearchError.NoNetwork, viewModel.uiState.value.error)
        assertTrue(viewModel.uiState.value.results.isEmpty())
        assertFalse(viewModel.uiState.value.isSearching)
    }

    @Test
    fun rateLimited_mapsToRateLimitedError() = runTest(dispatcher) {
        repository.result = Result.failure(GeocodingException.RateLimited)
        viewModel.onQueryChanged("coffee")
        dispatcher.scheduler.advanceTimeBy(DEBOUNCE + 1)
        assertEquals(SearchError.RateLimited, viewModel.uiState.value.error)
    }

    @Test
    fun resultSelected_recordsRecent_andResetsQuery() = runTest(dispatcher) {
        val result = place("1")
        viewModel.onQueryChanged("statue")
        dispatcher.scheduler.advanceTimeBy(DEBOUNCE + 1)

        viewModel.onResultSelected(result)

        assertEquals("", viewModel.uiState.value.query)
        assertTrue(viewModel.uiState.value.results.isEmpty())
        assertEquals(listOf(result), viewModel.uiState.value.recentSearches)
    }

    @Test
    fun recentSearches_loadFromStoreOnCreation() = runTest(dispatcher) {
        recentStore.record(place("existing"))
        val freshViewModel = SearchViewModel(repository, recentStore, debounceMillis = DEBOUNCE)
        assertEquals(listOf("existing"), freshViewModel.uiState.value.recentSearches.map { it.id })
    }

    private fun place(id: String) = SearchResult(
        id = id,
        name = "Place $id",
        displayName = "Place $id, USA",
        latitude = 1.0,
        longitude = 2.0,
    )

    private class FakeGeocodingRepository : GeocodingRepository {
        val queriesReceived = mutableListOf<String>()
        val contextsReceived = mutableListOf<GeocodingSearchContext>()
        var result: Result<List<SearchResult>> = Result.success(emptyList())
        override suspend fun search(
            query: String,
            context: GeocodingSearchContext,
        ): Result<List<SearchResult>> {
            queriesReceived += query
            contextsReceived += context
            return result
        }
    }

    private class FakeRecentSearchStore : RecentSearchStore {
        private var items = emptyList<SearchResult>()
        override fun load(): List<SearchResult> = items
        override fun record(result: SearchResult) {
            items = RecentSearchList.withRecorded(items, result)
        }
    }

    private companion object {
        const val DEBOUNCE = 100L
    }
}
