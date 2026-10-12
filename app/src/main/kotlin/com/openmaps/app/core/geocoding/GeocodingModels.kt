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
