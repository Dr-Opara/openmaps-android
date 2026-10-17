package com.openmaps.app.core.places

/**
 * Provider-neutral place category (Phase 9). Mapped from OSM/Overpass tags by
 * [PlaceCategoryMapper] — no raw tag ever leaks past that mapper.
 */
enum class PlaceCategory {
    Restaurant,
    Coffee,
    Hotel,
    Grocery,
    Pharmacy,
    Parking,
    Gas,
    EvCharging,
    Bank,
    Atm,
    /** Convenience stores (`shop=convenience`) — split out from [Grocery] in Phase 12A. */
    Convenience,
    /** Clinics, hospitals and doctors' offices. */
    Medical,
    /** Cinemas, theatres and tourist attractions. */
    Entertainment,
    /** A curated `shop=*` subset (malls, department stores, clothing/shoes/electronics). */
    Shopping,
    /** Schools, colleges, universities and kindergartens. */
    Education,
}

/**
 * Provider-neutral seam for a future sponsored-listing feature (section C of the Phase 12A brief).
 * No ranking, payment, or advertiser code reads this yet — [OverpassResponseParser] only ever
 * produces [Organic], since OSM has no concept of a sponsored listing. It exists so that if/when a
 * sponsored-listing provider is added, every nearby-place surface already has one labeled field to
 * check rather than needing to retrofit one later.
 */
enum class PromotionType {
    Organic,
    Sponsored,
}

/**
 * Category-specific fields that only some providers/places actually supply. Deliberately a single
 * holder of nullable/empty-default fields rather than a per-category subtype hierarchy: a `Place`
 * is one provider-neutral shape regardless of category (mirrors [com.openmaps.app.core.geocoding.SearchResult]
 * staying one flat shape rather than branching per result type), and most fields are absent most of
 * the time. Nothing here is ever invented — see [PlaceCategoryMapper] and `OverpassResponseParser`.
 */
data class PlaceMetadata(
    /** Parking only: e.g. "surface", "underground", "multi-storey", "street_side". */
    val parkingType: String? = null,
    /** Parking only: true/false only when the provider states it explicitly; null means unknown. */
    val isPublicAccess: Boolean? = null,
    /** Parking only: true/false only when the provider states a fee applies; null means unknown. */
    val feeApplies: Boolean? = null,
    /** Gas/EV only: the brand or network operator name, if the provider supplies one. */
    val brand: String? = null,
    /** EV charging only: connector types reported by the provider (e.g. "type2", "ccs"). Never invented. */
    val connectors: List<String> = emptyList(),
    /** Provider-supplied public phone number, when available. */
    val phone: String? = null,
    /** Provider-supplied public website, when available. */
    val website: String? = null,
    /** Provider-supplied opening-hours text. This is descriptive; OpenMaps never infers hours. */
    val openingHoursText: String? = null,
    /** Preferred access/entrance point when the provider supplies one. */
    val accessLatitude: Double? = null,
    val accessLongitude: Double? = null,
)

/**
 * One contextual place, independent of any provider's response shape. No Overpass/OSM JSON leaks
 * past this — see `OverpassResponseParser`. Mirrors [com.openmaps.app.core.geocoding.SearchResult]'s
 * "one flat provider-neutral shape" convention.
 */
data class Place(
    val id: String,
    val name: String,
    val category: PlaceCategory,
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    /** Straight-line distance from the point the query was centered on, when known. */
    val distanceMeters: Double? = null,
    /** How far this place sits off the active route, when it was resolved via [PlaceRepository.placesAlongRoute]. */
    val routeDetourMeters: Double? = null,
    /** Only set when the provider actually reports opening-hours state; never guessed. */
    val openNow: Boolean? = null,
    val metadata: PlaceMetadata = PlaceMetadata(),
    /** Always [PromotionType.Organic] today — see [PromotionType]'s doc comment. */
    val promotionType: PromotionType = PromotionType.Organic,
)

/**
 * Why a places request failed, classified so the UI layer never inspects raw IO/HTTP details.
 * Mirrors [com.openmaps.app.core.geocoding.GeocodingException]'s shape.
 */
sealed class PlaceException(message: String) : Exception(message) {
    object NoNetwork : PlaceException("No network connection")
    object Timeout : PlaceException("The request timed out")
    class Server(val code: Int) : PlaceException("Server error ($code)")
    object RateLimited : PlaceException("Too many requests")
    object Malformed : PlaceException("Malformed response")
}
