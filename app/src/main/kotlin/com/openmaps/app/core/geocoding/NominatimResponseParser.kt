package com.openmaps.app.core.geocoding

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses a Nominatim `format=jsonv2` search response body. Pure and Android-free (uses only
 * `org.json`, which exists both on-device and, for tests, via the `org.json:json` test
 * dependency) so it is unit-testable without a real HTTP call.
 *
 * Throws [org.json.JSONException] on anything that is not the expected JSON array shape;
 * callers translate that into [GeocodingException.Malformed].
 */
internal object NominatimResponseParser {

    fun parse(body: String): List<SearchResult> {
        val array = JSONArray(body)
        return buildList(array.length()) {
            for (i in 0 until array.length()) {
                add(array.getJSONObject(i).toSearchResult())
            }
        }
    }

    private fun JSONObject.toSearchResult(): SearchResult {
        val address = optJSONObject("address")
        val displayName = optString("display_name")
        val name = optString("name").ifBlank { displayName.substringBefore(',') }
        return SearchResult(
            id = optString("place_id").ifBlank { optString("osm_id") },
            name = name,
            displayName = displayName,
            latitude = getString("lat").toDouble(),
            longitude = getString("lon").toDouble(),
            category = optString("type").ifBlank { null } ?: optString("category").ifBlank { null },
            address = address?.streetAddress(),
            city = address?.firstNonBlank("city", "town", "village", "hamlet"),
            state = address?.optString("state")?.ifBlank { null },
            country = address?.optString("country")?.ifBlank { null },
        )
    }

    private fun JSONObject.streetAddress(): String? {
        val house = optString("house_number").ifBlank { null }
        val road = optString("road").ifBlank { null }
        return when {
            house != null && road != null -> "$house $road"
            road != null -> road
            else -> null
        }
    }

    private fun JSONObject.firstNonBlank(vararg keys: String): String? =
        keys.map { optString(it) }.firstOrNull { it.isNotBlank() }
}
