package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.Coordinate
import org.json.JSONObject

/**
 * Parses Overpass API `[out:json]` responses into Phase 10's two Overpass-backed data types:
 * static road hazards (construction/hazard tags) and OSM `maxspeed` speed limits. Pure and
 * Android-free (only `org.json`), mirroring `OverpassResponseParser` for places.
 *
 * **What this can and cannot honestly claim.** OSM is a static map database, not a live incident
 * feed: it has a real, standard `highway=construction`/`construction=*` tag pair and a real,
 * standard `hazard=*` tag (https://wiki.openstreetmap.org/wiki/Key:hazard), but nothing resembling a
 * crash, a stalled vehicle, debris, or a temporary closure — those only exist as *events*, which a
 * static database cannot represent. This parser therefore only ever produces
 * [RoadEventType.Construction] and [RoadEventType.Other] (for whatever `hazard=*` value OSM states,
 * carried verbatim in [RoadEvent.description] rather than guessed at) — see ARCHITECTURE.md's Road
 * intelligence section for the full reasoning and what a crash/incident feed would need instead.
 */
internal object OverpassRoadIntelligenceParser {

    fun parseHazards(body: String): List<RoadEvent> {
        val root = JSONObject(body)
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val results = mutableListOf<RoadEvent>()
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            toHazard(element)?.let { results.add(it) }
        }
        return results
    }

    private fun toHazard(element: JSONObject): RoadEvent? {
        val tags = element.optJSONObject("tags") ?: return null
        val (latitude, longitude) = coordinatesOf(element) ?: return null
        val type = element.optString("type", "node")
        val id = "osm:$type/${element.optLong("id")}"

        val highway = tags.optStringOrNull("highway")
        val construction = tags.optStringOrNull("construction")
        val hazard = tags.optStringOrNull("hazard")

        return when {
            highway == "construction" || construction != null -> RoadEvent(
                id = id,
                type = RoadEventType.Construction,
                latitude = latitude,
                longitude = longitude,
                severity = RoadEventSeverity.Moderate,
                title = "Construction",
                description = construction?.takeIf { it.isNotBlank() },
                metadata = tags.toStringMap(),
            )

            hazard != null -> RoadEvent(
                id = id,
                type = RoadEventType.Other,
                latitude = latitude,
                longitude = longitude,
                severity = RoadEventSeverity.Low,
                title = "Road hazard",
                // Carried verbatim (e.g. "animal_crossing", "ice") — OSM states it, we don't
                // interpret it into a more specific RoadEventType we can't actually confirm.
                description = hazard,
                metadata = tags.toStringMap(),
            )

            else -> null
        }
    }

    /**
     * The `maxspeed` tag of the nearest `highway=*` way to [near], within whatever radius the query
     * was built with, or null when none carries one. Ties (equal distance) resolve to the first
     * element Overpass returned — deterministic given a fixed query, not meaningful real-world
     * tie-breaking, since that case does not arise from live data at the small radius this is
     * queried with.
     */
    fun parseNearestSpeedLimit(body: String, near: Coordinate): SpeedLimitInfo? {
        val root = JSONObject(body)
        val elements = root.optJSONArray("elements") ?: return null
        var best: Pair<Double, SpeedLimitInfo>? = null
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val tags = element.optJSONObject("tags") ?: continue
            if (tags.optStringOrNull("highway") == null) continue
            val maxspeed = tags.optStringOrNull("maxspeed")?.toSpeedLimitOrNull() ?: continue
            val (lat, lon) = coordinatesOf(element) ?: continue
            val distance = haversineMeters(near.latitude, near.longitude, lat, lon)
            if (best == null || distance < best.first) best = distance to maxspeed
        }
        return best?.second
    }

    /**
     * OSM's `maxspeed` tag is free text: a bare number (km/h unless the country default is
     * elsewhere), `"<n> mph"`, or a non-numeric special value (`"none"`, `"walk"`, a zone name).
     * Only the numeric forms are a real speed limit; anything else is **not** translated into a
     * guess — see section 7 of the Phase 10 brief ("do NOT infer or invent speed limits").
     */
    private fun String.toSpeedLimitOrNull(): SpeedLimitInfo? {
        val trimmed = trim().lowercase()
        val mphMatch = Regex("""^(\d+(?:\.\d+)?)\s*mph$""").find(trimmed)
        if (mphMatch != null) {
            val value = mphMatch.groupValues[1].toDoubleOrNull() ?: return null
            return SpeedLimitInfo(value = value, unit = SpeedUnit.Mph, source = "osm:maxspeed")
        }
        val kmhMatch = Regex("""^(\d+(?:\.\d+)?)\s*(?:km/h|kmh)?$""").find(trimmed)
        if (kmhMatch != null) {
            val value = kmhMatch.groupValues[1].toDoubleOrNull() ?: return null
            return SpeedLimitInfo(value = value, unit = SpeedUnit.Kmh, source = "osm:maxspeed")
        }
        return null // "none", "walk", "RU:rural", a zone reference, etc. — not a number, not invented.
    }

    private fun coordinatesOf(element: JSONObject): Pair<Double, Double>? {
        if (element.has("lat") && element.has("lon")) {
            return validLatLon(element.optDouble("lat"), element.optDouble("lon"))
        }
        val center = element.optJSONObject("center") ?: return null
        return validLatLon(center.optDouble("lat"), center.optDouble("lon"))
    }

    private fun validLatLon(lat: Double, lon: Double): Pair<Double, Double>? =
        if (lat.isNaN() || lon.isNaN()) null else lat to lon

    private fun JSONObject.optStringOrNull(name: String): String? =
        if (has(name)) getString(name).takeIf { it.isNotBlank() } else null

    private fun JSONObject.toStringMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = optString(key)
        }
        return map
    }

    /** Great-circle distance in metres. Only used to rank a handful of nearby candidates, not for route math. */
    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = kotlin.math.sin(dLat / 2).let { it * it } +
            kotlin.math.cos(Math.toRadians(lat1)) * kotlin.math.cos(Math.toRadians(lat2)) *
            kotlin.math.sin(dLon / 2).let { it * it }
        val c = 2 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        return earthRadiusMeters * c
    }
}
