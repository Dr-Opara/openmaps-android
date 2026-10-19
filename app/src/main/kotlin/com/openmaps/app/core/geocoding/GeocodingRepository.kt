package com.openmaps.app.core.geocoding

/**
 * Context that helps a geocoder rank results without restricting search coverage.
 *
 * [latitude]/[longitude] are a proximity bias only: a valid address anywhere in the world must
 * remain searchable. This is what lets "coffee" prefer nearby businesses while an explicit address
 * in another country still resolves normally.
 */
data class GeocodingSearchContext(
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** BCP-47 language tag preferred for labels/results, e.g. "en-US", "es-ES". */
    val localeTag: String? = null,
)

/**
 * Converts free-text queries into candidate locations. Implementations must:
 *  - never throw: failures come back as `Result.failure` carrying a [GeocodingException];
 *  - return `Result.success(emptyList())` for "no matches", not a failure;
 *  - treat [query] as caller-normalized (trimming, minimum length, debouncing are the
 *    caller's job, not the repository's);
 *  - treat [context] as ranking/localization context, never as a hard geographic restriction.
 *
 * Replaceable: nothing outside this package knows which provider is behind it.
 */
interface GeocodingRepository {
    suspend fun search(
        query: String,
        context: GeocodingSearchContext = GeocodingSearchContext(),
    ): Result<List<SearchResult>>
}
