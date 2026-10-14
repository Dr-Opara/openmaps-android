package com.openmaps.app.core.roadintelligence

/** How congested the route (or the road the driver is on) currently is. */
enum class TrafficCongestionLevel {
    Unknown,
    Light,
    Moderate,
    Heavy,
    Severe,
}

/**
 * Route-aware traffic state (Phase 10). Kept separate from [RoadEvent] because congestion is a
 * continuous condition over a stretch of road, not a point-like incident, even though a severe jam
 * is often also *reported* as one or more [RoadEvent]s with [RoadEventType.Congestion] — both can be
 * true at once, and neither is derived from the other.
 *
 * [baseDurationSeconds] and [trafficAdjustedDurationSeconds] are kept **distinct** on purpose (see
 * section 17 of the Phase 10 brief): a provider that cannot supply a traffic-aware estimate leaves
 * [trafficAdjustedDurationSeconds] null rather than this type inventing one from [congestionLevel].
 *
 * [isLive] is an honesty flag, not a display toggle: it is false whenever [congestionLevel] was
 * derived from static/historical data rather than a real-time feed, so a caller can choose not to
 * say "current traffic" about data that is not current. See `DefaultRoadIntelligenceRepository`,
 * which has no live-traffic source and therefore never produces a non-null [TrafficCondition] with
 * [isLive] true today.
 */
data class TrafficCondition(
    val congestionLevel: TrafficCongestionLevel,
    val baseDurationSeconds: Double,
    val trafficAdjustedDurationSeconds: Double? = null,
    val isLive: Boolean = false,
)
