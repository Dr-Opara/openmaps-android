package com.openmaps.app.core.roadintelligence

/**
 * Configurable activation distances (section 14 of the Phase 10 brief: "make thresholds
 * configurable"). Defaults are deliberately conservative/approach-oriented rather than tuned
 * against real driving data, since no device validation has happened yet — see
 * docs/ARCHITECTURE.md's Road intelligence section.
 */
data class RoadAlertActivationConfig(
    /** A severe closure is shown at low prominence until within this distance. */
    val closureProminenceMeters: Double = 3_000.0,
    /** Collision-class hazards (crash, debris, flooding, ice) activate at this approach distance. */
    val collisionClassApproachMeters: Double = 800.0,
    /** Construction/other lower-severity hazards activate at this approach distance. */
    val informationalApproachMeters: Double = 400.0,
    /** Lane guidance activates near the maneuver it applies to. */
    val laneGuidanceApproachMeters: Double = 300.0,
) {
    /** The approach distance for one hazard event, by type/severity band (section 14 examples). */
    fun approachMetersFor(event: RoadEvent): Double = when (event.type) {
        RoadEventType.Closure -> if (event.severity == RoadEventSeverity.Severe) {
            closureProminenceMeters
        } else {
            collisionClassApproachMeters
        }
        RoadEventType.Crash, RoadEventType.Debris, RoadEventType.Flooding, RoadEventType.Ice -> collisionClassApproachMeters
        else -> informationalApproachMeters
    }
}

/**
 * Turns matched [RoadEvent]s (already stamped with [RoadEvent.routeDistanceMeters] by
 * [RoadEventRouteMatcher]) into the subset that should actually be active right now, given where
 * the driver is on the route.
 *
 * Two rules, both from section 13/14 of the Phase 10 brief:
 *  - **Behind the driver is suppressed.** An event whose route distance is already behind
 *    [distanceTraveledMeters] is not upcoming; show it to nobody.
 *  - **Ahead, but only "active" inside its approach distance** ([RoadAlertActivationConfig]) — a
 *    closure 3 km out is known about but not yet prominent. Speed limit and weather are not
 *    distance-gated at all here: they are persistent/contextual by nature (current position, not a
 *    point on the road ahead), which is why only hazard/incident events go through this object.
 */
object RoadAlertActivation {

    /** Null when [event] has no route distance (unmatched) or sits behind the driver. */
    fun distanceAheadMeters(event: RoadEvent, distanceTraveledMeters: Double): Double? {
        val eventDistance = event.routeDistanceMeters ?: return null
        val ahead = eventDistance - distanceTraveledMeters
        return if (ahead < 0.0) null else ahead
    }

    /**
     * Hazard/incident events that are ahead of the driver and within their type's approach
     * distance, paired with that approach distance for the caller to build [RouteAlert.Hazard] or
     * [RouteAlert.Informational] from (severity decides which — see `MapViewModel`'s wiring).
     */
    fun activeHazards(
        events: List<RoadEvent>,
        distanceTraveledMeters: Double,
        config: RoadAlertActivationConfig = RoadAlertActivationConfig(),
    ): List<Pair<RoadEvent, Double>> =
        events.mapNotNull { event ->
            val ahead = distanceAheadMeters(event, distanceTraveledMeters) ?: return@mapNotNull null
            if (ahead > config.approachMetersFor(event)) return@mapNotNull null
            event to ahead
        }
}
