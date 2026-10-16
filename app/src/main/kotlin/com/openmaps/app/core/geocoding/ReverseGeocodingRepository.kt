package com.openmaps.app.core.geocoding

/**
 * Converts a coordinate into the best available human-readable location.
 *
 * Reverse geocoding is intentionally separate from forward search so callers can resolve "Where am
 * I?", incident locations, trip-share positions and arrival context without constructing fake text
 * queries.
 */
interface ReverseGeocodingRepository {
    suspend fun reverse(
        latitude: Double,
        longitude: Double,
        localeTag: String? = null,
    ): Result<SearchResult?>
}
