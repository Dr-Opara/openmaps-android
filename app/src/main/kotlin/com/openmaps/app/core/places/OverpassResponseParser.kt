package com.openmaps.app.core.places

import org.json.JSONObject

/**
 * Parses an Overpass API `[out:json]` response body into provider-neutral [Place]s. Pure and
 * Android-free (only `org.json`), mirroring `NominatimResponseParser`/`ValhallaResponseParser`, so
 * it is unit-testable without a real HTTP call.
 *
 * **Per-element failures never fail the whole parse.** An element with no tags, no resolvable
 * coordinates, or a category [PlaceCategoryMapper] does not recognize is simply skipped — this is
 * the "unsupported category" / "partial category failure" handling the Phase 9 plan calls for.
 * Only a response that is not valid JSON at all throws (via [org.json.JSONException]), which the
 * repository maps to [PlaceException.Malformed].
 */
internal object OverpassResponseParser {

    fun parse(body: String): List<Place> {
        val root = JSONObject(body)
        val elements = root.optJSONArray("elements") ?: return emptyList()
        val results = mutableListOf<Place>()
        for (i in 0 until elements.length()) {
            val element = elements.optJSONObject(i) ?: continue
            toPlace(element)?.let { results.add(it) }
        }
        return results
    }

    private fun toPlace(element: JSONObject): Place? {
        val tagsJson = element.optJSONObject("tags") ?: return null
        val tags = tagsJson.toStringMap()
        val category = PlaceCategoryMapper.categoryFor(tags) ?: return null
        val (latitude, longitude) = coordinatesOf(element) ?: return null
        val type = element.optString("type", "node")
        val id = "$type/${element.optLong("id")}"
        val name = tags["name"]?.takeIf { it.isNotBlank() } ?: category.defaultName()

        return Place(
            id = id,
            name = name,
            category = category,
            latitude = latitude,
            longitude = longitude,
            address = streetAddress(tags),
            // Overpass/OSM has no live open/closed computation available here; never invented.
            openNow = null,
            metadata = metadataFor(category, tags),
        )
    }

    /** A node carries `lat`/`lon` directly; a way/relation queried with `out center` carries them under `center`. */
    private fun coordinatesOf(element: JSONObject): Pair<Double, Double>? {
        if (element.has("lat") && element.has("lon")) {
            return validLatLon(element.optDouble("lat"), element.optDouble("lon"))
        }
        val center = element.optJSONObject("center") ?: return null
        return validLatLon(center.optDouble("lat"), center.optDouble("lon"))
    }

    private fun validLatLon(lat: Double, lon: Double): Pair<Double, Double>? =
        if (lat.isNaN() || lon.isNaN()) null else lat to lon

    private fun streetAddress(tags: Map<String, String>): String? {
        val house = tags["addr:housenumber"]?.takeIf { it.isNotBlank() }
        val street = tags["addr:street"]?.takeIf { it.isNotBlank() }
        return when {
            house != null && street != null -> "$house $street"
            street != null -> street
            else -> null
        }
    }

    /** Category-specific fields (Phase 9 parking/gas/EV support). Never invents a value the tags don't state. */
    private fun metadataFor(category: PlaceCategory, tags: Map<String, String>): PlaceMetadata = when (category) {
        PlaceCategory.Parking -> PlaceMetadata(
            parkingType = tags["parking"]?.takeIf { it.isNotBlank() },
            isPublicAccess = accessFlag(tags["access"]),
            feeApplies = yesNoFlag(tags["fee"]),
        )

        PlaceCategory.Gas -> PlaceMetadata(
            brand = (tags["brand"] ?: tags["operator"])?.takeIf { it.isNotBlank() },
        )

        PlaceCategory.EvCharging -> PlaceMetadata(
            brand = (tags["operator"] ?: tags["network"])?.takeIf { it.isNotBlank() },
            connectors = tags.keys
                .filter { it.startsWith("socket:") }
                .map { it.removePrefix("socket:") }
                .sorted(),
        )

        PlaceCategory.Restaurant, PlaceCategory.Coffee, PlaceCategory.Hotel,
        PlaceCategory.Grocery, PlaceCategory.Pharmacy, PlaceCategory.Bank, PlaceCategory.Atm,
        PlaceCategory.Convenience, PlaceCategory.Medical, PlaceCategory.Entertainment,
        PlaceCategory.Shopping,
        -> PlaceMetadata()
    }

    private fun accessFlag(value: String?): Boolean? = when (value) {
        "yes", "public", "permissive" -> true
        "private", "no" -> false
        else -> null
    }

    private fun yesNoFlag(value: String?): Boolean? = when (value) {
        "yes" -> true
        "no" -> false
        else -> null
    }

    private fun PlaceCategory.defaultName(): String = when (this) {
        PlaceCategory.Restaurant -> "Restaurant"
        PlaceCategory.Coffee -> "Coffee"
        PlaceCategory.Hotel -> "Hotel"
        PlaceCategory.Grocery -> "Grocery"
        PlaceCategory.Pharmacy -> "Pharmacy"
        PlaceCategory.Parking -> "Parking"
        PlaceCategory.Gas -> "Gas station"
        PlaceCategory.EvCharging -> "EV charging"
        PlaceCategory.Bank -> "Bank"
        PlaceCategory.Atm -> "ATM"
        PlaceCategory.Convenience -> "Convenience store"
        PlaceCategory.Medical -> "Medical"
        PlaceCategory.Entertainment -> "Entertainment"
        PlaceCategory.Shopping -> "Shopping"
    }

    private fun JSONObject.toStringMap(): Map<String, String> {
        val map = mutableMapOf<String, String>()
        val keys = keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = optString(key)
        }
        return map
    }
}
