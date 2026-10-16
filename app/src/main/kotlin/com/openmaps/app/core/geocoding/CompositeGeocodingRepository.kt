package com.openmaps.app.core.geocoding

/**
 * Production failover chain for location search.
 *
 * Providers are tried in priority order. The first non-empty successful result wins. Empty results
 * continue to the next provider because one index missing a real address must not become a false
 * "not found". Provider failures are also skipped. A failure is returned only when every provider
 * fails; if at least one provider answered successfully with an empty list, the final answer is an
 * honest empty list.
 *
 * This strategy intentionally avoids sending the same query to every provider simultaneously. It
 * controls cost, avoids duplicate upstream traffic and makes provider priority deterministic.
 */
class CompositeGeocodingRepository(
    private val providers: List<GeocodingRepository>,
) : GeocodingRepository {

    init {
        require(providers.isNotEmpty()) { "At least one geocoding provider is required" }
    }

    override suspend fun search(
        query: String,
        context: GeocodingSearchContext,
    ): Result<List<SearchResult>> {
        var lastFailure: Throwable? = null
        var hadSuccessfulEmptyResponse = false

        for (provider in providers) {
            val result = provider.search(query, context)
            result.fold(
                onSuccess = { results ->
                    if (results.isNotEmpty()) {
                        return Result.success(SearchResultDeduplicator.deduplicate(results))
                    }
                    hadSuccessfulEmptyResponse = true
                },
                onFailure = { error ->
                    lastFailure = error
                },
            )
        }

        return when {
            hadSuccessfulEmptyResponse -> Result.success(emptyList())
            lastFailure != null -> Result.failure(lastFailure!!)
            else -> Result.success(emptyList())
        }
    }
}

/**
 * Defensive result cleanup at the provider boundary. Backends may aggregate multiple indexes, so
 * equivalent hits can arrive with different provider IDs.
 */
internal object SearchResultDeduplicator {
    fun deduplicate(results: List<SearchResult>): List<SearchResult> {
        val kept = ArrayList<SearchResult>(results.size)
        for (candidate in results) {
            val duplicate = kept.any { existing -> samePlace(existing, candidate) }
            if (!duplicate) kept += candidate
        }
        return kept
    }

    private fun samePlace(a: SearchResult, b: SearchResult): Boolean {
        val sameLabel = normalize(a.name) == normalize(b.name) ||
            normalize(a.displayName) == normalize(b.displayName)
        if (!sameLabel) return false

        val latNear = kotlin.math.abs(a.latitude - b.latitude) <= COORDINATE_EPSILON
        val lonNear = kotlin.math.abs(a.longitude - b.longitude) <= COORDINATE_EPSILON
        if (latNear && lonNear) return true

        val aAddress = normalize(a.address.orEmpty())
        val bAddress = normalize(b.address.orEmpty())
        return aAddress.isNotBlank() && aAddress == bAddress &&
            normalize(a.city.orEmpty()) == normalize(b.city.orEmpty())
    }

    private fun normalize(value: String): String =
        value.lowercase()
            .filter { it.isLetterOrDigit() || it.isWhitespace() }
            .replace(Regex("\\s+"), " ")
            .trim()

    private const val COORDINATE_EPSILON = 0.0005
}
