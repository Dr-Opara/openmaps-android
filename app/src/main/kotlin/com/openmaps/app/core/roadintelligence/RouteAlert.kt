package com.openmaps.app.core.roadintelligence

/**
 * One thing Map or Live might surface to the driver right now: a hazard, a weather warning, lane
 * guidance for the upcoming maneuver, a speed-limit readout, or a congestion notice. A single sealed
 * type (rather than a handful of nullable fields on some larger state) so "what alerts are active"
 * is always a plain list a renderer can map over, and so [RoadAlertPriority] has one shared type to
 * rank — mirrors how [com.openmaps.app.feature.map.RouteState]/`NavigationState` are each one sealed
 * value instead of scattered booleans.
 */
sealed interface RouteAlert {
    /** Stable across ticks for the same real-world thing, so hysteresis/dedup can key on it. */
    val id: String

    data class Hazard(val event: RoadEvent, val distanceAheadMeters: Double?) : RouteAlert {
        override val id: String = "hazard:${event.id}"
    }

    data class Weather(val condition: WeatherCondition) : RouteAlert {
        override val id: String = "weather:${condition.type}"
    }

    data class Lane(val guidance: LaneGuidance) : RouteAlert {
        override val id: String = "lane:${guidance.maneuverRelation}"
    }

    data class SpeedLimit(val info: SpeedLimitInfo) : RouteAlert {
        override val id: String = "speed-limit"
    }

    data class Congestion(val condition: TrafficCondition) : RouteAlert {
        override val id: String = "congestion"
    }

    /** A hazard whose severity doesn't warrant prominent treatment (e.g. low-severity construction). */
    data class Informational(val event: RoadEvent) : RouteAlert {
        override val id: String = "info:${event.id}"
    }
}

/**
 * Deterministic alert ordering (section 10 of the Phase 10 brief). Lower [rank] sorts first —
 * closure/severe hazard, then collision-class hazards, then dangerous weather, then lane guidance,
 * then speed-limit changes, then congestion, then everything else. No ML, no learned weighting:
 * a fixed table so the order is explainable and unit-testable by example.
 */
object RoadAlertPriority {

    /**
     * Lower sorts first (more urgent). Ties within a band are broken by [RoadEvent.severity].
     *
     * @param laneGuidanceBand Phase 11 environment tuning (see `EnvironmentNavigationProfile.
     *   laneGuidancePriorityBoost`): overrides [RouteAlert.Lane]'s band, e.g. to rank it above
     *   collision-class hazards on a highway. Defaults to the fixed [BAND_LANE_GUIDANCE], reproducing
     *   the exact pre-Phase-11 Phase 10 ordering.
     */
    fun rank(alert: RouteAlert, laneGuidanceBand: Int? = null): Int = when (alert) {
        is RouteAlert.Hazard -> rankHazard(alert.event)
        is RouteAlert.Weather -> rankWeather(alert.condition)
        is RouteAlert.Lane -> laneGuidanceBand ?: BAND_LANE_GUIDANCE
        is RouteAlert.SpeedLimit -> BAND_SPEED_LIMIT
        is RouteAlert.Congestion -> BAND_CONGESTION
        is RouteAlert.Informational -> BAND_INFORMATIONAL
    }

    /** Stable sort: ties keep their relative order, so equal-priority alerts don't reshuffle per tick. */
    fun sorted(alerts: List<RouteAlert>, laneGuidanceBand: Int? = null): List<RouteAlert> =
        alerts.sortedBy { rank(it, laneGuidanceBand) }

    private fun rankHazard(event: RoadEvent): Int = when (event.type) {
        RoadEventType.Closure -> if (event.severity == RoadEventSeverity.Severe) BAND_CLOSURE_SEVERE else BAND_CLOSURE
        RoadEventType.Crash, RoadEventType.Debris -> BAND_COLLISION_CLASS
        RoadEventType.Flooding, RoadEventType.Ice -> BAND_COLLISION_CLASS
        RoadEventType.Construction -> if (event.severity >= RoadEventSeverity.High) BAND_COLLISION_CLASS else BAND_INFORMATIONAL
        RoadEventType.Congestion -> BAND_CONGESTION
        RoadEventType.Fog, RoadEventType.HeavyRain, RoadEventType.HighWind, RoadEventType.ReducedVisibility -> BAND_WEATHER
        RoadEventType.Other -> BAND_INFORMATIONAL
    }

    private fun rankWeather(condition: WeatherCondition): Int =
        if (condition.severity >= RoadEventSeverity.High) BAND_WEATHER else BAND_INFORMATIONAL

    // Bands are spaced out, not sequential 1..7, so a future severity tie-break can slot a hazard
    // between two existing bands without renumbering every constant below it.
    private const val BAND_CLOSURE_SEVERE = 0
    private const val BAND_CLOSURE = 10
    private const val BAND_COLLISION_CLASS = 20
    private const val BAND_WEATHER = 30
    private const val BAND_LANE_GUIDANCE = 40
    private const val BAND_SPEED_LIMIT = 50
    private const val BAND_CONGESTION = 60
    private const val BAND_INFORMATIONAL = 70
}
