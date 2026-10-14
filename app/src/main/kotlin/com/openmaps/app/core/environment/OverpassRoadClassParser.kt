package com.openmaps.app.core.environment

import com.openmaps.app.core.routing.Coordinate
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Parses an Overpass API `[out:json]` response (`way["highway"](around:...)`) into the nearest
 * way's real OSM `highway=*` classification. Pure and Android-free (only `org.json`), mirroring
 * `OverpassRoadIntelligenceParser`'s shape.
 */
internal object OverpassRoadClassParser {

    fun parseNearestRoadClass(body: String, near: Coordinate): RoadClass? {
        val root = JSONObject(body)
        val elements = root.optJSONArray("elements") ?: return null
        var best: Pair<Double, RoadClass>? = null
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            val tags = element.optJSONObject("tags") ?: continue
            val highway = tags.optStringOrNull("highway")?.toRoadClass() ?: continue
            val (lat, lon) = coordinatesOf(element) ?: continue
            val distance = haversineMeters(near.latitude, near.longitude, lat, lon)
            if (best == null || distance < best.first) best = distance to highway
        }
        return best?.second
    }

    private fun String.toRoadClass(): RoadClass = when (this) {
        "motorway" -> RoadClass.Motorway
        "motorway_link" -> RoadClass.MotorwayLink
        "trunk" -> RoadClass.Trunk
        "trunk_link" -> RoadClass.TrunkLink
        "primary", "primary_link" -> RoadClass.Primary
        "secondary", "secondary_link" -> RoadClass.Secondary
        "tertiary", "tertiary_link" -> RoadClass.Tertiary
        "residential", "living_street" -> RoadClass.Residential
        "service" -> RoadClass.Service
        "unclassified" -> RoadClass.Unclassified
        else -> RoadClass.Other
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

    private fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val earthRadiusMeters = 6_371_000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).let { it * it } +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).let { it * it }
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return earthRadiusMeters * c
    }
}
