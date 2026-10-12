package com.openmaps.app.core.geocoding

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * [RecentSearchStore] backed by `SharedPreferences` as a small JSON array (same lightweight
 * persistence pattern as `LocationPermissionPrefs`; no database for one short list). Ordering,
 * dedup and the cap are [RecentSearchList]'s job, not this class's.
 */
class SharedPreferencesRecentSearchStore(
    context: Context,
    private val limit: Int = RecentSearchList.DEFAULT_LIMIT,
) : RecentSearchStore {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    override fun load(): List<SearchResult> {
        val raw = prefs.getString(KEY_RECENTS, null) ?: return emptyList()
        return runCatching { JSONArray(raw).toSearchResults() }.getOrDefault(emptyList())
    }

    override fun record(result: SearchResult) {
        val next = RecentSearchList.withRecorded(load(), result, limit)
        prefs.edit().putString(KEY_RECENTS, next.toJsonArray().toString()).apply()
    }

    private fun JSONArray.toSearchResults(): List<SearchResult> = buildList(length()) {
        for (i in 0 until length()) {
            val o = getJSONObject(i)
            add(
                SearchResult(
                    id = o.getString("id"),
                    name = o.getString("name"),
                    displayName = o.getString("displayName"),
                    latitude = o.getDouble("latitude"),
                    longitude = o.getDouble("longitude"),
                    category = o.optString("category").ifBlank { null },
                    address = o.optString("address").ifBlank { null },
                    city = o.optString("city").ifBlank { null },
                    state = o.optString("state").ifBlank { null },
                    country = o.optString("country").ifBlank { null },
                ),
            )
        }
    }

    private fun List<SearchResult>.toJsonArray(): JSONArray = JSONArray().apply {
        forEach { r ->
            put(
                JSONObject().apply {
                    put("id", r.id)
                    put("name", r.name)
                    put("displayName", r.displayName)
                    put("latitude", r.latitude)
                    put("longitude", r.longitude)
                    r.category?.let { put("category", it) }
                    r.address?.let { put("address", it) }
                    r.city?.let { put("city", it) }
                    r.state?.let { put("state", it) }
                    r.country?.let { put("country", it) }
                },
            )
        }
    }

    private companion object {
        const val FILE = "openmaps_prefs"
        const val KEY_RECENTS = "recent_searches"
    }
}
