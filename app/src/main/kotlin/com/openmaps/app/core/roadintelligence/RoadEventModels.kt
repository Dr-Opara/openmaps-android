package com.openmaps.app.core.roadintelligence

/**
 * Provider-neutral road-event type (Phase 10). Mirrors how [com.openmaps.app.core.places.PlaceCategory]
 * is the only vocabulary anything outside the provider implementation sees — no OSM tag, incident-feed
 * code or vendor enum ever leaks past this.
 */
enum class RoadEventType {
    Congestion,
    Crash,
    Construction,
    Closure,
    Debris,
    Flooding,
    Ice,
    Fog,
    HeavyRain,
    HighWind,
    ReducedVisibility,
    Other,
}

/**
 * How severe a [RoadEvent] is, ordered least to most severe so comparisons (`severity >= Moderate`)
 * read naturally and [RoadAlertPriority] can rank on it directly.
 */
enum class RoadEventSeverity {
    Low,
    Moderate,
    High,
    Severe,
}

/**
 * One provider-neutral road event: a hazard, incident, congestion report or closure somewhere near
 * (or on) a route. No provider JSON (Overpass, a traffic feed, a 511 feed, ...) ever crosses this —
 * see `OverpassRoadIntelligenceRepository`.
 *
 * [routeDistanceMeters] is the cumulative distance **along the route** (from its start) to this
 * event's nearest point, stamped once by [RoadEventRouteMatcher] when the event is matched to the
 * active route. It is deliberately a route-walked distance, not a straight-line one, so "distance
 * ahead" (routeDistanceMeters minus [com.openmaps.app.core.navigation.RouteProgress.distanceTraveledMeters])
 * agrees with what the driver actually has left to drive — see section 13 of the Phase 10 brief.
 * Null means the event has not been (or cannot be) matched to a route, e.g. nearby-browsing context
 * with no active route.
 */
data class RoadEvent(
    val id: String,
    val type: RoadEventType,
    val latitude: Double,
    val longitude: Double,
    val routeDistanceMeters: Double? = null,
    val severity: RoadEventSeverity,
    val title: String,
    val description: String? = null,
    val startTimeMillis: Long? = null,
    val endTimeMillis: Long? = null,
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Why a road-intelligence request failed, classified so the UI/ViewModel layer never inspects raw
 * IO/HTTP details. Mirrors [com.openmaps.app.core.places.PlaceException]'s shape.
 *
 * Deliberately unused for control flow anywhere navigation-critical: per the Phase 10 brief, no road
 * intelligence source may become a navigation dependency, so every caller treats a `Result.failure`
 * here exactly like [com.openmaps.app.core.places.PlaceException] — additive context that quietly
 * stays at its last-known value, never a trip blocker.
 */
sealed class RoadIntelligenceException(message: String) : Exception(message) {
    object NoNetwork : RoadIntelligenceException("No network connection")
    object Timeout : RoadIntelligenceException("The request timed out")
    class Server(val code: Int) : RoadIntelligenceException("Server error ($code)")
    object Malformed : RoadIntelligenceException("Malformed response")
}
