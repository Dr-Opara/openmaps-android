package com.openmaps.app.core.roadintelligence

/**
 * Driving-relevant weather condition (Phase 10). Deliberately a small, driving-focused vocabulary,
 * not a general-purpose weather-app model — see section 6 of the Phase 10 brief ("do NOT build a
 * general weather app").
 */
enum class WeatherConditionType {
    Clear,
    Rain,
    HeavyRain,
    Snow,
    Ice,
    Fog,
    HighWind,
    Storm,
    ReducedVisibility,
    Unknown,
}

/**
 * Route/current-location weather context. One snapshot, not a forecast timeline — Live and Map both
 * want "is there something relevant right now," not an hourly breakdown.
 *
 * [severity] reuses [RoadEventSeverity] rather than a parallel scale: both describe "how much should
 * this change what I show the driver," and the alert-priority model (`RoadAlertPriority`) needs to
 * compare a weather severity against a hazard severity on one shared scale.
 */
data class WeatherCondition(
    val type: WeatherConditionType,
    val severity: RoadEventSeverity,
    val temperatureCelsius: Double? = null,
    val windSpeedKph: Double? = null,
    val visibilityMeters: Double? = null,
    val description: String,
    val observedAtMillis: Long,
)
