package com.openmaps.app.core.environment

/**
 * Provider-neutral driving environment (Phase 11). Four real modes plus a safe fallback — see
 * [EnvironmentClassifier] for how a trip resolves to one of these, and [EnvironmentHysteresis] for
 * why the resolved value does not flicker tick to tick.
 */
enum class DrivingEnvironment {
    City,
    Highway,
    Suburb,
    Rural,
    /** No usable signal yet (trip just started, or every input is absent). A safe, neutral default. */
    Unknown,
}

/**
 * The real OSM `highway=*` classification of the nearest way to a point. A genuine map-data signal
 * (see `OverpassRoadClassRepository`), never inferred from speed or behavior — section B2 of the
 * Phase 11 brief is explicit that Highway mode must come from road classification, not from "going
 * fast."
 */
enum class RoadClass {
    Motorway,
    MotorwayLink,
    Trunk,
    TrunkLink,
    Primary,
    Secondary,
    Tertiary,
    Residential,
    Service,
    Unclassified,
    Other,
}

/** True for the classes that make [EnvironmentClassifier] resolve [DrivingEnvironment.Highway]. */
val RoadClass.isControlledAccess: Boolean
    get() = this == RoadClass.Motorway || this == RoadClass.MotorwayLink ||
        this == RoadClass.Trunk || this == RoadClass.TrunkLink

/**
 * Every real, available signal [EnvironmentClassifier] is allowed to use — nothing here is
 * inferred or guessed; each field is either absent (null / zero) or came from a real source already
 * in this codebase (Overpass road class, Phase 10 speed limit/current speed, the active route's own
 * maneuver spacing, Phase 9's nearby-place count).
 */
data class EnvironmentSignals(
    /** Nearest way's OSM highway classification, from Overpass — see `OverpassRoadClassRepository`. */
    val roadClass: RoadClass? = null,
    /** Posted speed limit, km/h — from Phase 10's `SpeedLimitInfo`, normalized. */
    val speedLimitKmh: Double? = null,
    /** Current speed, km/h — from Phase 10's direct `Location.getSpeed()` reading, normalized. */
    val currentSpeedKmh: Double? = null,
    /** Mean distance between consecutive maneuvers on the active route, metres. Null with no route. */
    val averageManeuverSpacingMeters: Double? = null,
    /** How many contextual places (Phase 9) are currently visible/ranked nearby. */
    val nearbyPlaceCount: Int = 0,
)
