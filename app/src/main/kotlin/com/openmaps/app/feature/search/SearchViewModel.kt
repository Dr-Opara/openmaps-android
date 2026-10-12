package com.openmaps.app.feature.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmaps.app.core.geocoding.GeocodingException
import com.openmaps.app.core.geocoding.GeocodingRepository
import com.openmaps.app.core.geocoding.RecentSearchStore
import com.openmaps.app.core.geocoding.SearchResult
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
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState(recentSearches = recentSearchStore.load()))
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null

    fun onQueryChanged(query: String) {
        _uiState.update { it.copy(query = query) }
        searchJob?.cancel()

        val trimmed = query.trim()
        if (trimmed.length < MIN_QUERY_LENGTH) {
            _uiState.update { it.copy(results = emptyList(), isSearching = false, error = null) }
            return
        }

        _uiState.update { it.copy(isSearching = true, error = null) }
        searchJob = viewModelScope.launch {
            delay(debounceMillis)
            geocodingRepository.search(trimmed)
                .onSuccess { results ->
                    _uiState.update { it.copy(results = results, isSearching = false, error = null) }
                }
                .onFailure { cause ->
                    _uiState.update {
                        it.copy(results = emptyList(), isSearching = false, error = cause.toSearchError())
                    }
                }
        }
    }

    /** Bookkeeping for a selection (fresh result or a re-tapped recent): records it and resets the session. */
    fun onResultSelected(result: SearchResult) {
        searchJob?.cancel()
        recentSearchStore.record(result)
        _uiState.update {
            it.copy(query = "", results = emptyList(), isSearching = false, error = null, recentSearches = recentSearchStore.load())
        }
    }

    override fun onCleared() {
        searchJob?.cancel()
    }

    companion object {
        const val DEFAULT_DEBOUNCE_MILLIS = 350L
        const val MIN_QUERY_LENGTH = 2
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
