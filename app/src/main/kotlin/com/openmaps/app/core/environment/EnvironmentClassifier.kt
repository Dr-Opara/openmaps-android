package com.openmaps.app.core.environment

/**
 * Thresholds [EnvironmentClassifier] compares [EnvironmentSignals] against. Configurable, not
 * hardcoded into the classifier — section B of the Phase 11 brief calls for tunable, testable
 * thresholds, the same reasoning `RoadAlertActivationConfig` already applies to Phase 10.
 */
data class EnvironmentClassifierConfig(
    /** At or below this maneuver spacing, the route reads as a dense, City-style intersection grid. */
    val cityManeuverSpacingMeters: Double = 300.0,
    /** At or above this maneuver spacing, the route reads as long, uninterrupted Rural segments. */
    val ruralManeuverSpacingMeters: Double = 2_000.0,
    /** At or above this many nearby places, the area reads as City-dense. */
    val cityPlaceDensityMin: Int = 6,
    /** At or below this many nearby places, the area reads as Rural-sparse. */
    val ruralPlaceDensityMax: Int = 1,
)

/**
 * Deterministic, explainable environment classification (Phase 11) — no ML, by design, mirroring
 * `PlaceRanking`'s and `RoadAlertPriority`'s "no ML" precedent. Pure and stateless: the *debounced*
 * mode over time is [EnvironmentHysteresis]'s job, not this object's — this only ever answers "what
 * does this one snapshot of signals look like."
 */
object EnvironmentClassifier {

    fun classify(
        signals: EnvironmentSignals,
        config: EnvironmentClassifierConfig = EnvironmentClassifierConfig(),
    ): DrivingEnvironment {
        // Highway is decided by road classification alone — never by speed. A driver crawling in
        // motorway traffic is still on a motorway; a driver speeding on a rural road is not on one.
        val roadClass = signals.roadClass
        if (roadClass != null && roadClass.isControlledAccess) return DrivingEnvironment.Highway

        val spacing = signals.averageManeuverSpacingMeters
        val density = signals.nearbyPlaceCount
        val hasAnySignal = roadClass != null || spacing != null || density > 0 || signals.speedLimitKmh != null
        if (!hasAnySignal) return DrivingEnvironment.Unknown

        val ruralEvidence = spacing != null &&
            spacing >= config.ruralManeuverSpacingMeters &&
            density <= config.ruralPlaceDensityMax
        if (ruralEvidence) return DrivingEnvironment.Rural

        val cityEvidence = (spacing != null && spacing <= config.cityManeuverSpacingMeters) ||
            density >= config.cityPlaceDensityMin ||
            roadClass == RoadClass.Residential
        if (cityEvidence) return DrivingEnvironment.City

        // Evidence exists but is neither clearly City nor clearly Rural (typically a primary/
        // secondary/tertiary road with moderate density) — Suburb is the balanced middle mode.
        return DrivingEnvironment.Suburb
    }
}
