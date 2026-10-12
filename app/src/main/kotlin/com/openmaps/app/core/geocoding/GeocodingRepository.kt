package com.openmaps.app.core.geocoding

/**
 * Converts free-text queries into candidate locations. Implementations must:
 *  - never throw: failures come back as `Result.failure` carrying a [GeocodingException];
 *  - return `Result.success(emptyList())` for "no matches", not a failure;
 *  - treat [query] as caller-normalized (trimming, minimum length, debouncing are the
 *    caller's job, not the repository's).
 *
 * Replaceable: nothing outside this package and its single implementation knows which
 * provider is behind it.
 */
interface GeocodingRepository {
    suspend fun search(query: String): Result<List<SearchResult>>
}
