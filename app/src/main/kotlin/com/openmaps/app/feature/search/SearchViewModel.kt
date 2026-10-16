package com.openmaps.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmaps.app.core.geocoding.GeocodingException
import com.openmaps.app.core.geocoding.GeocodingSearchContext
import com.openmaps.app.core.geocoding.GeocodingRepository
import com.openmaps.app.core.geocoding.RecentSearchStore
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.UserLocation
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Owns the search session: the live query, debounced remote lookups and the recent-search
 * list. Destination *selection* (camera move, marker) is [com.openmaps.app.feature.map.MapViewModel]'s
 * job; this ViewModel only reports that a result was picked via [onResultSelected] bookkeeping
 * (clearing the query, recording the recent) — the caller is responsible for also telling the
 * map. Contains no Android framework and no provider-specific types, same as `MapViewModel`.
 */
class SearchViewModel(
    private val geocodingRepository: GeocodingRepository,
    private val recentSearchStore: RecentSearchStore,
    private val debounceMillis: Long = DEFAULT_DEBOUNCE_MILLIS,
    initialLocaleTag: String? = null,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState(recentSearches = recentSearchStore.load()))
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var searchContext = GeocodingSearchContext(localeTag = initialLocaleTag)
    private var lastSubmittedQuery: String? = null
    private var searchGeneration: Long = 0L

    /**
     * Keeps search globally available while biasing ambiguous queries toward the driver's current
     * position. A missing fix simply means unbiased worldwide search.
     */
    fun onLocationBiasChanged(location: UserLocation?) {
        searchContext = searchContext.copy(
            latitude = location?.latitude,
            longitude = location?.longitude,
        )
    }

    fun onQueryChanged(query: String) {
        // A voice search (or any programmatic text change) lands here, so the one search path stays
        // the one path — no separate "search this spoken phrase" branch that could diverge.
        _uiState.update { it.copy(query = query, pendingVoiceQuery = null) }
        searchJob?.cancel()
        searchGeneration++
        val generation = searchGeneration

        val trimmed = query.trim()
        val normalized = trimmed.lowercase().replace(Regex("\\s+"), " ")
        if (trimmed.length < MIN_QUERY_LENGTH) {
            lastSubmittedQuery = null
            _uiState.update { it.copy(results = emptyList(), isSearching = false, error = null) }
            return
        }

        // Re-entering the same normalized query (extra whitespace/casing only) must not spend
        // another commercial-provider request.
        if (normalized == lastSubmittedQuery) {
            _uiState.update { it.copy(isSearching = false, error = null) }
            return
        }

        _uiState.update { it.copy(isSearching = true, error = null) }
        searchJob = viewModelScope.launch {
            delay(adaptiveDebounceMillis(trimmed))
            if (generation != searchGeneration) return@launch

            lastSubmittedQuery = normalized
            val requestContext = searchContext
            geocodingRepository.search(trimmed, requestContext)
                .onSuccess { results ->
                    if (generation != searchGeneration) return@onSuccess
                    _uiState.update { it.copy(results = results, isSearching = false, error = null) }
                }
                .onFailure { cause ->
                    if (generation != searchGeneration) return@onFailure
                    _uiState.update {
                        it.copy(results = emptyList(), isSearching = false, error = cause.toSearchError())
                    }
                }
        }
    }

    /** Switches the visible result bucket. Never re-queries — it partitions the same result list. */
    fun onTabSelected(tab: SearchTab) {
        _uiState.update { it.copy(selectedTab = tab) }
    }

    /** The recognizer started listening. */
    fun onVoiceSearchStarted() {
        _uiState.update { it.copy(voiceState = VoiceSearchState.Listening) }
    }

    /** No recognizer on the device, or it refused to start. Surfaced, never silently ignored. */
    fun onVoiceSearchUnavailable() {
        _uiState.update { it.copy(voiceState = VoiceSearchState.Unavailable) }
    }

    fun onVoiceSearchFailed(message: String) {
        _uiState.update { it.copy(voiceState = VoiceSearchState.Failed(message)) }
    }

    /** The user dismissed the recognizer without saying anything. */
    fun onVoiceSearchCancelled() {
        _uiState.update { it.copy(voiceState = VoiceSearchState.Idle) }
    }

    /** Clears any voice error banner once it has been seen. */
    fun onVoiceSearchMessageShown() {
        _uiState.update { it.copy(voiceState = VoiceSearchState.Idle) }
    }

    /**
     * The recognizer returned [text]. Runs the normal debounced search and leaves the phrase in the
     * field so the user can see and correct what was heard — nothing is hidden.
     */
    fun onVoiceSearchResult(text: String) {
        _uiState.update { it.copy(voiceState = VoiceSearchState.Idle) }
        onQueryChanged(text)
    }

    /** Bookkeeping for a selection (fresh result or a re-tapped recent): records it and resets the session. */
    fun onResultSelected(result: SearchResult) {
        searchJob?.cancel()
        searchGeneration++
        lastSubmittedQuery = null
        recentSearchStore.record(result)
        _uiState.update {
            it.copy(
                query = "",
                results = emptyList(),
                isSearching = false,
                error = null,
                recentSearches = recentSearchStore.load(),
                // Reset to All so reopening search never shows a stale, now-empty filtered tab.
                selectedTab = SearchTab.All,
                voiceState = VoiceSearchState.Idle,
            )
        }
    }

    override fun onCleared() {
        searchJob?.cancel()
    }

    private fun adaptiveDebounceMillis(query: String): Long = when {
        debounceMillis != DEFAULT_DEBOUNCE_MILLIS -> debounceMillis
        query.length <= 4 -> 650L
        query.length <= 8 -> 500L
        else -> 400L
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 350L
        // Three characters avoids firing expensive global search on one/two-character noise while
        // still preserving normal type-ahead behavior for POIs, streets and cities.
        const val MIN_QUERY_LENGTH = 3
    }
}

private fun Throwable.toSearchError(): SearchError = when (this) {
    is GeocodingException.NoNetwork -> SearchError.NoNetwork
    is GeocodingException.Timeout -> SearchError.Timeout
    is GeocodingException.RateLimited -> SearchError.RateLimited
    is GeocodingException.Server -> SearchError.ServerError
    is GeocodingException.Malformed -> SearchError.Malformed
    else -> SearchError.Malformed
}
