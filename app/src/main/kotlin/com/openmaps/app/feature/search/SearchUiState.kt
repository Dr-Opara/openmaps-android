package com.openmaps.app.feature.search

import com.openmaps.app.core.geocoding.SearchResult

/** Everything the Search screen renders. Pure Kotlin, same philosophy as `MapUiState`. */
data class SearchUiState(
    val query: String = "",
    val results: List<SearchResult> = emptyList(),
    val isSearching: Boolean = false,
    val error: SearchError? = null,
    val recentSearches: List<SearchResult> = emptyList(),
) {
    /** True once a query was long enough to search and the (successful) result list is empty. */
    val hasNoResults: Boolean
        get() = !isSearching && error == null && results.isEmpty() && query.trim().length >= SearchViewModel.MIN_QUERY_LENGTH
}

/** User-facing geocoding failure categories. No raw HTTP/IO details reach the UI. */
enum class SearchError {
    NoNetwork,
    Timeout,
    RateLimited,
    ServerError,
    Malformed,
}
