package com.openmaps.app.core.geocoding

/** Persists recently-selected destinations, most-recent-first. */
interface RecentSearchStore {
    fun load(): List<SearchResult>
    fun record(result: SearchResult)
}
