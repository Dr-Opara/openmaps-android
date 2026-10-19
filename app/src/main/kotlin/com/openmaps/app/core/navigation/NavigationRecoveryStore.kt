package com.openmaps.app.core.navigation

import android.content.Context
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.core.routing.RouteBounds
import com.openmaps.app.core.routing.RouteManeuver
import com.openmaps.app.core.routing.RouteResult
import org.json.JSONArray
import org.json.JSONObject

data class NavigationRecoverySnapshot(
    val route: RouteResult,
    val destination: SearchResult,
    val savedAtMillis: Long,
)

interface NavigationRecoveryStore {
    fun load(nowMillis: Long = System.currentTimeMillis()): NavigationRecoverySnapshot?
    fun save(route: RouteResult, destination: SearchResult, nowMillis: Long = System.currentTimeMillis())
    fun clear()
}

object NoopNavigationRecoveryStore : NavigationRecoveryStore {
    override fun load(nowMillis: Long): NavigationRecoverySnapshot? = null
    override fun save(route: RouteResult, destination: SearchResult, nowMillis: Long) = Unit
    override fun clear() = Unit
}

class SharedPreferencesNavigationRecoveryStore(
    context: Context,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
) : NavigationRecoveryStore {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(nowMillis: Long): NavigationRecoverySnapshot? {
        val raw = prefs.getString(KEY_ACTIVE_NAVIGATION, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val savedAt = root.getLong("savedAt")
            if (nowMillis - savedAt !in 0..maxAgeMillis) {
                clear()
                return null
            }
            NavigationRecoverySnapshot(
                route = root.getJSONObject("route").toRouteResult(),
                destination = root.getJSONObject("destination").toSearchResult(),
                savedAtMillis = savedAt,
            )
        }.getOrElse {
            clear()
            null
        }
    }

    override fun save(route: RouteResult, destination: SearchResult, nowMillis: Long) {
        val root = JSONObject()
            .put("savedAt", nowMillis)
            .put("route", route.toJson())
            .put("destination", destination.toJson())
        prefs.edit().putString(KEY_ACTIVE_NAVIGATION, root.toString()).apply()
    }

    override fun clear() {
        prefs.edit().remove(KEY_ACTIVE_NAVIGATION).apply()
    }

    private fun RouteResult.toJson(): JSONObject = JSONObject()
        .put("geometry", JSONArray().apply { geometry.forEach { put(it.toJson()) } })
        .put("distanceMeters", distanceMeters)
        .put("durationSeconds", durationSeconds)
        .put("maneuvers", JSONArray().apply { maneuvers.forEach { put(it.toJson()) } })
        .put("id", id)
        .put("label", label)

    private fun JSONObject.toRouteResult(): RouteResult {
        val geometryArray = getJSONArray("geometry")
        val geometry = buildList(geometryArray.length()) {
            for (index in 0 until geometryArray.length()) add(geometryArray.getJSONObject(index).toCoordinate())
        }
        val maneuverArray = optJSONArray("maneuvers")
        val maneuvers = buildList {
            if (maneuverArray != null) {
                for (index in 0 until maneuverArray.length()) add(maneuverArray.getJSONObject(index).toManeuver())
            }
        }
        return RouteResult(
            geometry = geometry,
            distanceMeters = getDouble("distanceMeters"),
            durationSeconds = getDouble("durationSeconds"),
            bounds = geometry.bounds(),
            maneuvers = maneuvers,
            id = optString("id").ifBlank { null },
            label = optString("label").ifBlank { null },
            isOfflineCached = true,
        )
    }

    private fun RouteManeuver.toJson(): JSONObject = JSONObject()
        .put("instruction", instruction)
        .put("verbalInstruction", verbalInstruction)
        .put("type", type.name)
        .put("streetName", streetName)
        .put("distanceMeters", distanceMeters)
        .put("beginShapeIndex", beginShapeIndex)
        .put("endShapeIndex", endShapeIndex)
        .put("exitSign", exitSign)
        .put("signpost", signpost)

    private fun JSONObject.toManeuver(): RouteManeuver = RouteManeuver(
        instruction = getString("instruction"),
        verbalInstruction = optNullableString("verbalInstruction"),
        type = ManeuverType.entries.firstOrNull { it.name == optString("type") } ?: ManeuverType.Unknown,
        streetName = optNullableString("streetName"),
        distanceMeters = getDouble("distanceMeters"),
        beginShapeIndex = getInt("beginShapeIndex"),
        endShapeIndex = getInt("endShapeIndex"),
        exitSign = optNullableString("exitSign"),
        signpost = optNullableString("signpost"),
    )

    private fun SearchResult.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("displayName", displayName)
        .put("latitude", latitude)
        .put("longitude", longitude)
        .put("category", category)
        .put("address", address)
        .put("city", city)
        .put("state", state)
        .put("country", country)

    private fun JSONObject.toSearchResult(): SearchResult = SearchResult(
        id = getString("id"),
        name = getString("name"),
        displayName = getString("displayName"),
        latitude = getDouble("latitude"),
        longitude = getDouble("longitude"),
        category = optNullableString("category"),
        address = optNullableString("address"),
        city = optNullableString("city"),
        state = optNullableString("state"),
        country = optNullableString("country"),
    )

    private fun Coordinate.toJson(): JSONObject =
        JSONObject().put("latitude", latitude).put("longitude", longitude)

    private fun JSONObject.toCoordinate(): Coordinate =
        Coordinate(getDouble("latitude"), getDouble("longitude"))

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else optString(key).ifBlank { null }

    private fun List<Coordinate>.bounds(): RouteBounds? {
        if (isEmpty()) return null
        return RouteBounds(
            south = minOf { it.latitude },
            west = minOf { it.longitude },
            north = maxOf { it.latitude },
            east = maxOf { it.longitude },
        )
    }

    private companion object {
        const val PREFS_NAME = "openmaps_navigation_recovery"
        const val KEY_ACTIVE_NAVIGATION = "active_navigation"
        const val DEFAULT_MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L
    }
}
