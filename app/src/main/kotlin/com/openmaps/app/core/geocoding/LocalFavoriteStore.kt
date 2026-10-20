package com.openmaps.app.core.geocoding

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Guest favorites: stored on-device, never sent to an account or third party. */
class LocalFavoriteStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("openmaps_favorites", Context.MODE_PRIVATE)

    fun load(): List<SearchResult> = runCatching {
        val items = JSONArray(prefs.getString("saved_places", "[]"))
        buildList {
            for (i in 0 until items.length()) {
                val v = items.getJSONObject(i)
                add(SearchResult(
                    id = v.getString("id"),
                    name = v.getString("name"),
                    displayName = v.getString("displayName"),
                    latitude = v.getDouble("latitude"),
                    longitude = v.getDouble("longitude"),
                    category = v.optString("category").ifBlank { null },
                    address = v.optString("address").ifBlank { null },
                    city = v.optString("city").ifBlank { null },
                    state = v.optString("state").ifBlank { null },
                    country = v.optString("country").ifBlank { null },
                ))
            }
        }
    }.getOrDefault(emptyList())

    /** Toggle bookmark and return the refreshed list for immediate UI updates. */
    fun toggle(place: SearchResult): List<SearchResult> {
        val current = load()
        val updated = if (current.any { it.id == place.id }) {
            current.filterNot { it.id == place.id }
        } else {
            (listOf(place) + current).take(MAX_FAVORITES)
        }
        val array = JSONArray()
        updated.forEach { result ->
            array.put(JSONObject().apply {
                put("id", result.id)
                put("name", result.name)
                put("displayName", result.displayName)
                put("latitude", result.latitude)
                put("longitude", result.longitude)
                result.category?.let { put("category", it) }
                result.address?.let { put("address", it) }
                result.city?.let { put("city", it) }
                result.state?.let { put("state", it) }
                result.country?.let { put("country", it) }
            })
        }
        prefs.edit().putString("saved_places", array.toString()).apply()
        return updated
    }

    companion object { const val MAX_FAVORITES = 100 }
}
