package com.openmaps.app.core.geocoding

/**
 * Pure list policy for recent searches: most-recent-first, deduplicated by [SearchResult.id],
 * capped at [limit]. Separated from [RecentSearchStore] so this logic is unit-testable without
 * `SharedPreferences`.
 */
internal object RecentSearchList {
    const val DEFAULT_LIMIT = 8

    fun withRecorded(current: List<SearchResult>, result: SearchResult, limit: Int = DEFAULT_LIMIT): List<SearchResult> =
        (listOf(result) + current.filterNot { it.id == result.id }).take(limit)
}
