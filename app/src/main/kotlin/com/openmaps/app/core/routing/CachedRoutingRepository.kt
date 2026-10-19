package com.openmaps.app.core.routing

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.*

/**
 * Local degraded-operation cache for the most recently successful route.
 *
 * Traffic fields are deliberately cleared on restore: cached geometry/maneuvers are useful offline,
 * but old traffic/ETA must never be presented as live. The cache is only eligible when both origin
 * and destination still match within conservative distance gates and the entry is fresh.
 */
class CachedRoutingRepository(
    context: Context,
    private val upstream: RoutingRepository,
    private val maxAgeMillis: Long = DEFAULT_MAX_AGE_MILLIS,
) : RoutingRepository {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override suspend fun route(origin: Coordinate, destination: Coordinate): Result<RouteResult> {
        val live = upstream.route(origin, destination)
        if (live.isSuccess) {
            live.getOrNull()?.let { save(origin, destination, it) }
            return live
        }

        val failure = live.exceptionOrNull()
        val cacheEligible = failure is RoutingException.NoNetwork ||
            failure is RoutingException.Timeout ||
            failure is RoutingException.Server

        if (!cacheEligible) return live

        val cached = load(origin, destination) ?: return live
        return Result.success(cached)
    }

    private fun save(origin: Coordinate, destination: Coordinate, route: RouteResult) {
        val json = JSONObject()
            .put("savedAt", System.currentTimeMillis())
            .put("origin", origin.toJson())
            .put("destination", destination.toJson())
            .put("route", route.toCacheJson())
            .toString()
        prefs.edit().putString(KEY_ROUTE, json).apply()
    }

    private fun load(origin: Coordinate, destination: Coordinate): RouteResult? {
        val raw = prefs.getString(KEY_ROUTE, null) ?: return null
        return runCatching {
            val root = JSONObject(raw)
            val savedAt = root.getLong("savedAt")
            if (System.currentTimeMillis() - savedAt > maxAgeMillis) return null

            val savedOrigin = root.getJSONObject("origin").toCoordinate()
            val savedDestination = root.getJSONObject("destination").toCoordinate()
            if (distanceMeters(origin, savedOrigin) > ORIGIN_MATCH_METERS) return null
            if (distanceMeters(destination, savedDestination) > DESTINATION_MATCH_METERS) return null

            root.getJSONObject("route").toRouteResult().copy(
                baseDurationSeconds = null,
                typicalDurationSeconds = null,
                trafficDelaySeconds = null,
                isTrafficAware = false,
                alternatives = emptyList(),
                isOfflineCached = true,
            )
        }.getOrNull()
    }

    private fun RouteResult.toCacheJson(): JSONObject = JSONObject()
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

    private fun distanceMeters(a: Coordinate, b: Coordinate): Double {
        val earthRadius = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val lat1 = Math.toRadians(a.latitude)
        val lat2 = Math.toRadians(b.latitude)
        val h = sin(dLat / 2).pow(2) + cos(lat1) * cos(lat2) * sin(dLon / 2).pow(2)
        return 2 * earthRadius * asin(sqrt(h))
    }

    private companion object {
        const val PREFS_NAME = "openmaps_route_cache"
        const val KEY_ROUTE = "last_route"
        const val DEFAULT_MAX_AGE_MILLIS = 6 * 60 * 60 * 1000L
        const val ORIGIN_MATCH_METERS = 2_000.0
        const val DESTINATION_MATCH_METERS = 300.0
    }
}
