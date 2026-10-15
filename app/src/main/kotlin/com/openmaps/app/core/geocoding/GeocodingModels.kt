package com.openmaps.app.core.geocoding

/** One geocoding match. Pure Kotlin: no Android types, no provider-specific fields leak past here. */
data class SearchResult(
    val id: String,
    val name: String,
    val displayName: String,
    val latitude: Double,
    val longitude: Double,
    val category: String? = null,
    val address: String? = null,
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
) {
    /** Compact "address line" for result cards and the destination summary: street, then city/state. */
    val subtitle: String
        get() {
            val cityState = listOfNotNull(city, state).joinToString(", ").ifBlank { null }
            return listOfNotNull(address, cityState).joinToString(", ").ifBlank { displayName }
        }

    /**
     * Which Search Results tab this result belongs to (the mockup's All / Places / Addresses /
     * Categories).
     *
     * Classified **from the data Nominatim actually returns**, never guessed: a result only counts
     * as [Places] when it carries a real `category`, and only as [Addresses] when it carries a real
     * street `address`. That keeps the tabs honest — a query in an area with no POIs shows an honest
     * empty Places tab instead of re-listing the same addresses under a label they do not match.
     */
    val resultKind: SearchResultKind
        get() = when {
            hasBusinessCategory() -> SearchResultKind.Place
            !address.isNullOrBlank() -> SearchResultKind.Address
            else -> SearchResultKind.Other
        }

    /**
     * True when Nominatim returned a `category`/`type` that names a point of interest, e.g.
     * "restaurant", "hotel", "tourist attraction". Street types ("residential", "secondary") are
     * road classifications rather than business categories and must not read as a place.
     */
    fun hasBusinessCategory(): Boolean {
        val raw = category?.trim()?.lowercase().orEmpty()
        if (raw.isEmpty()) return false
        if (raw in ROAD_TYPES) return false
        return true
    }

    private companion object {
        /** Nominatim `type` values that describe a road, not a business. */
        val ROAD_TYPES = setOf(
            "residential", "secondary", "tertiary", "primary", "trunk", "motorway", "unclassified",
            "living_street", "pedestrian", "service", "track", "path", "footway", "cycleway",
            "road", "motorway_link", "trunk_link", "primary_link", "secondary_link", "tertiary_link",
        )
    }
}

/**
 * The Search Results tabs. [All] is the unfiltered list; the other three partition it by what the
 * provider actually told us about each result.
 */
enum class SearchResultKind {
    /** Named businesses and POIs — Nominatim returned a real category for them. */
    Place,
    /** Street addresses — Nominatim returned a real street address. */
    Address,
    /** Anything else (localities, administrative areas, junctions). */
    Other,
}

/**
 * Why a geocoding search failed, classified so the UI layer never has to inspect raw
 * [java.io.IOException]/HTTP details. Mirrors [com.openmaps.app.map.MapError]'s shape.
 */
sealed class GeocodingException(message: String) : Exception(message) {
    object NoNetwork : GeocodingException("No network connection")
    object Timeout : GeocodingException("The request timed out")
    class Server(val code: Int) : GeocodingException("Server error ($code)")
    object RateLimited : GeocodingException("Too many requests")
    object Malformed : GeocodingException("Malformed response")
}
