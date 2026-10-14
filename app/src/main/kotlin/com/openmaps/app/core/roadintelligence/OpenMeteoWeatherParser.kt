package com.openmaps.app.core.roadintelligence

import org.json.JSONObject

/**
 * Parses an Open-Meteo `/v1/forecast?current=...` response body into a provider-neutral
 * [WeatherCondition]. Pure and Android-free (only `org.json`), mirroring every other response
 * parser in this codebase.
 *
 * Maps Open-Meteo's `current.weather_code` — the WMO weather interpretation code Open-Meteo
 * documents at https://open-meteo.com/en/docs (table "WMO Weather interpretation codes") — to the
 * driving-relevant [WeatherConditionType]/[RoadEventSeverity] pair. This is a real, documented,
 * finite code table, not an inference: every branch below is a direct reading of what the code
 * means, never a guess at conditions the API didn't report.
 *
 * **[WeatherCondition.visibilityMeters] is always null from this source.** Open-Meteo's `current`
 * block (unlike its `hourly` block) does not expose a visibility figure at all, and this parser only
 * ever reads `current` — see `DefaultRoadIntelligenceRepository` for why (one lightweight request,
 * not a full hourly payload for a single current-conditions read).
 */
internal object OpenMeteoWeatherParser {

    fun parse(body: String, nowMillis: Long): WeatherCondition? {
        val current = JSONObject(body).optJSONObject("current") ?: return null
        if (!current.has("weather_code")) return null
        val code = current.optInt("weather_code", -1)
        val (baseType, baseSeverity) = code.toTypeAndSeverity() ?: return null
        val windSpeedKph = current.optDoubleOrNull("wind_speed_10m")

        // Wind is a real measured value (wind_speed_10m), not invented — only the "is this strong
        // enough to call out" threshold is a judgment call. Only overrides when nothing more severe
        // (rain/snow/ice/storm) is already reported, so a windy thunderstorm still reads as a storm.
        val (type, severity) = if (windSpeedKph != null && windSpeedKph >= HIGH_WIND_KPH && baseSeverity <= RoadEventSeverity.Moderate) {
            WeatherConditionType.HighWind to if (windSpeedKph >= SEVERE_WIND_KPH) RoadEventSeverity.High else RoadEventSeverity.Moderate
        } else {
            baseType to baseSeverity
        }

        return WeatherCondition(
            type = type,
            severity = severity,
            temperatureCelsius = current.optDoubleOrNull("temperature_2m"),
            windSpeedKph = windSpeedKph,
            visibilityMeters = null,
            description = type.defaultDescription(),
            observedAtMillis = nowMillis,
        )
    }

    /** https://open-meteo.com/en/docs — "WMO Weather interpretation codes (WW)". */
    private fun Int.toTypeAndSeverity(): Pair<WeatherConditionType, RoadEventSeverity>? = when (this) {
        0, 1 -> WeatherConditionType.Clear to RoadEventSeverity.Low
        2, 3 -> WeatherConditionType.Clear to RoadEventSeverity.Low
        45, 48 -> WeatherConditionType.Fog to RoadEventSeverity.Moderate
        51, 53 -> WeatherConditionType.Rain to RoadEventSeverity.Low
        55 -> WeatherConditionType.Rain to RoadEventSeverity.Moderate
        56, 57 -> WeatherConditionType.Ice to RoadEventSeverity.High // freezing drizzle
        61, 63 -> WeatherConditionType.Rain to RoadEventSeverity.Moderate
        65 -> WeatherConditionType.HeavyRain to RoadEventSeverity.High
        66, 67 -> WeatherConditionType.Ice to RoadEventSeverity.Severe // freezing rain
        71, 73 -> WeatherConditionType.Snow to RoadEventSeverity.Moderate
        75, 77 -> WeatherConditionType.Snow to RoadEventSeverity.High
        80, 81 -> WeatherConditionType.Rain to RoadEventSeverity.Moderate
        82 -> WeatherConditionType.HeavyRain to RoadEventSeverity.High // violent rain showers
        85 -> WeatherConditionType.Snow to RoadEventSeverity.Moderate
        86 -> WeatherConditionType.Snow to RoadEventSeverity.High
        95 -> WeatherConditionType.Storm to RoadEventSeverity.High
        96, 99 -> WeatherConditionType.Storm to RoadEventSeverity.Severe // thunderstorm with hail
        else -> null // Unmapped/unknown WMO code: no condition reported rather than a guess.
    }

    private fun WeatherConditionType.defaultDescription(): String = when (this) {
        WeatherConditionType.Clear -> "Clear"
        WeatherConditionType.Rain -> "Rain"
        WeatherConditionType.HeavyRain -> "Heavy rain"
        WeatherConditionType.Snow -> "Snow"
        WeatherConditionType.Ice -> "Freezing precipitation"
        WeatherConditionType.Fog -> "Fog"
        WeatherConditionType.HighWind -> "High wind"
        WeatherConditionType.Storm -> "Thunderstorm"
        WeatherConditionType.ReducedVisibility -> "Reduced visibility"
        WeatherConditionType.Unknown -> "Unknown"
    }

    private fun JSONObject.optDoubleOrNull(name: String): Double? =
        if (has(name)) optDouble(name).takeIf { !it.isNaN() } else null

    /** ~31 mph — the US National Weather Service's common "wind advisory" starting point. */
    private const val HIGH_WIND_KPH = 50.0

    /** ~46 mph — in range of a "high wind warning." */
    private const val SEVERE_WIND_KPH = 75.0
}
