package com.openmaps.app.feature.search

import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.geocoding.SearchResultKind

/** Everything the Search screen renders. Pure Kotlin, same philosophy as `MapUiState`. */
data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val error: SearchError? = null,
    val recentSearches: List<SearchResult> = emptyList(),
    /** The mockup's result filter. [SearchTab.All] is the unfiltered provider list. */
    val selectedTab: SearchTab = SearchTab.All,
    /** Voice-search lifecycle, so the microphone can show an honest state instead of a dead icon. */
    val voiceState: VoiceSearchState = VoiceSearchState.Idle,
    /** One-shot text handed back by a voice search, surfaced to the UI exactly once. */
    val pendingVoiceQuery: String? = null,
) {
    /**
     * The results the selected tab shows.
     *
     * A pure partition of the **same** provider list — the tabs never re-query, so switching tabs is
     * instant and can never change what the provider said. An empty tab is reported honestly through
     * [SearchTab.isEmptyFor] rather than by silently falling back to showing everything.
     */
    val visibleResults: List<SearchResult>
        get() = when (selectedTab) {
            SearchTab.All -> results
            SearchTab.Places -> results.filter { it.resultKind == SearchResultKind.Place }
            SearchTab.Addresses -> results.filter { it.resultKind == SearchResultKind.Address }
            SearchTab.Categories -> results.filter { it.hasBusinessCategory() && it.name.isNotBlank() }
        }

    /** True once a query was long enough to search and the (successful) result list is empty. */
    val hasNoResults: Boolean
        get() = !isSearching && error == null && results.isEmpty() && query.trim().length >= SearchViewModel.MIN_QUERY_LENGTH

    /** A search succeeded but the selected tab genuinely has nothing in it. */
    val selectedTabIsEmpty: Boolean
        get() = !isSearching && error == null && results.isNotEmpty() && visibleResults.isEmpty()
}

/**
 * The mockup's Search Results tabs.
 *
 * [Places], [Addresses] and [Categories] partition [SearchResultKind] — a result classified by the
 * provider as an address never appears under Places, and vice versa.
 */
enum class SearchTab {
    All,
    Places,
    Addresses,
    Categories;

    /** True when the provider returned nothing in this bucket for the current query. */
    fun isEmptyFor(results: List<SearchResult>): Boolean = results.none { predicate(it) }

    private fun predicate(result: SearchResult): Boolean = when (this) {
        All -> true
        Places -> result.resultKind == SearchResultKind.Place
        Addresses -> result.resultKind == SearchResultKind.Address
        Categories -> result.hasBusinessCategory() && result.name.isNotBlank()
    }
}

/**
 * Voice-search state.
 *
 * Modelled explicitly so the microphone is never a dead control: every terminal outcome — including
 * "no recognizer on this device" and the user cancelling — has a state the UI can report.
 */
sealed interface VoiceSearchState {
    /** Nothing in progress. */
    data object Idle : VoiceSearchState
    /** Waiting for the user to speak. */
    data object Listening : VoiceSearchState
    /** No speech recognizer is installed/enabled, or it refused. Reported, never swallowed. */
    data object Unavailable : VoiceSearchState
    /** Recognizer or permission error. */
    data class Failed(val message: String) : VoiceSearchState
}

/** User-facing geocoding failure categories. No raw HTTP/IO details reach the UI. */
enum class SearchError {
    NoNetwork,
    Timeout,
    RateLimited,
    ServerError,
    Malformed,
}
