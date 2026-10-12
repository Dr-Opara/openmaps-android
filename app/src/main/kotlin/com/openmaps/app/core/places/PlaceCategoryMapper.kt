package com.openmaps.app.core.places

/**
 * Maps OSM/Overpass tags to [PlaceCategory] and back to the Overpass QL selectors needed to query
 * for a requested category set. The only place OSM tag names appear outside `OverpassResponseParser`
 * — every other layer talks in [PlaceCategory].
 *
 * One tag pair per table row, deliberately not a 1:1 OSM-tag-to-category mapping: OSM models the
 * same real-world category under more than one `amenity`/`shop`/`tourism` value (a cafe and a
 * restaurant are both "food", but OSM keeps them as separate `amenity` values), so each
 * [PlaceCategory] maps from a small *set* of tag pairs.
 */
object PlaceCategoryMapper {

    /** One OSM tag pair ("amenity" to "cafe", etc.) and the category it maps to. */
    private data class TagMapping(val key: String, val value: String, val category: PlaceCategory)

    private val mappings = listOf(
        TagMapping("amenity", "restaurant", PlaceCategory.Restaurant),
        TagMapping("amenity", "fast_food", PlaceCategory.Restaurant),
        TagMapping("amenity", "cafe", PlaceCategory.Coffee),
        TagMapping("tourism", "hotel", PlaceCategory.Hotel),
        TagMapping("tourism", "motel", PlaceCategory.Hotel),
        TagMapping("tourism", "guest_house", PlaceCategory.Hotel),
        TagMapping("shop", "supermarket", PlaceCategory.Grocery),
        TagMapping("shop", "convenience", PlaceCategory.Grocery),
        TagMapping("shop", "grocery", PlaceCategory.Grocery),
        TagMapping("amenity", "pharmacy", PlaceCategory.Pharmacy),
        TagMapping("amenity", "parking", PlaceCategory.Parking),
        TagMapping("amenity", "fuel", PlaceCategory.Gas),
        TagMapping("amenity", "charging_station", PlaceCategory.EvCharging),
    )

    /**
     * The category for a raw tag map, or null when nothing in [tags] matches a known mapping — an
     * **unsupported category**, which callers must skip rather than invent a fallback for.
     */
    fun categoryFor(tags: Map<String, String>): PlaceCategory? =
        mappings.firstOrNull { tags[it.key] == it.value }?.category

    /**
     * The Overpass QL selector fragments (e.g. `["amenity"="cafe"]`) needed to query for
     * [categories]. Deduplicated and in a stable order so the generated query is deterministic
     * (and therefore cache-friendly — see [PlaceCache]).
     */
    fun overpassSelectors(categories: Set<PlaceCategory>): List<String> =
        mappings
            .filter { it.category in categories }
            .distinct()
            .map { "[\"${it.key}\"=\"${it.value}\"]" }

    /** Every category this mapper can produce from OSM data. */
    val supportedCategories: Set<PlaceCategory> = mappings.map { it.category }.toSet()
}
