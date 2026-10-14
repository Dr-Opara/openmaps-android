package com.openmaps.app.core.roadintelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMeteoWeatherParserTest {

    @Test
    fun clearSkyCode_parsesAsClearLowSeverity() {
        val body = """{"current":{"weather_code":0,"temperature_2m":22.0,"wind_speed_10m":5.0}}"""
        val weather = OpenMeteoWeatherParser.parse(body, nowMillis = 1_000L)!!
        assertEquals(WeatherConditionType.Clear, weather.type)
        assertEquals(RoadEventSeverity.Low, weather.severity)
        assertEquals(22.0, weather.temperatureCelsius!!, 1e-9)
        assertEquals(1_000L, weather.observedAtMillis)
        assertNull("Open-Meteo's current block has no visibility figure", weather.visibilityMeters)
    }

    @Test
    fun heavyRainCode_parsesAsHeavyRainHighSeverity() {
        val body = """{"current":{"weather_code":65,"wind_speed_10m":10.0}}"""
        val weather = OpenMeteoWeatherParser.parse(body, nowMillis = 0L)!!
        assertEquals(WeatherConditionType.HeavyRain, weather.type)
        assertEquals(RoadEventSeverity.High, weather.severity)
    }

    @Test
    fun freezingRainCode_parsesAsIceSevereSeverity() {
        val body = """{"current":{"weather_code":67,"wind_speed_10m":5.0}}"""
        val weather = OpenMeteoWeatherParser.parse(body, nowMillis = 0L)!!
        assertEquals(WeatherConditionType.Ice, weather.type)
        assertEquals(RoadEventSeverity.Severe, weather.severity)
    }

    @Test
    fun thunderstormWithHailCode_parsesAsStormSevereSeverity() {
        val body = """{"current":{"weather_code":99,"wind_speed_10m":20.0}}"""
        val weather = OpenMeteoWeatherParser.parse(body, nowMillis = 0L)!!
        assertEquals(WeatherConditionType.Storm, weather.type)
        assertEquals(RoadEventSeverity.Severe, weather.severity)
    }

    @Test
    fun unmappedWeatherCode_returnsNullRatherThanGuessing() {
        val body = """{"current":{"weather_code":12345,"wind_speed_10m":5.0}}"""
        assertNull(OpenMeteoWeatherParser.parse(body, nowMillis = 0L))
    }

    @Test
    fun missingWeatherCode_returnsNull() {
        assertNull(OpenMeteoWeatherParser.parse("""{"current":{"temperature_2m":10.0}}""", nowMillis = 0L))
    }

    @Test
    fun missingCurrentBlock_returnsNull() {
        assertNull(OpenMeteoWeatherParser.parse("""{}""", nowMillis = 0L))
    }

    @Test
    fun highWind_overridesClearCodeWithHighWindCondition() {
        // Code 1 ("mainly clear") would normally map to Clear/Low, but a measured wind speed past
        // the advisory threshold is real data too and must not be silently dropped.
        val body = """{"current":{"weather_code":1,"wind_speed_10m":60.0}}"""
        val weather = OpenMeteoWeatherParser.parse(body, nowMillis = 0L)!!
        assertEquals(WeatherConditionType.HighWind, weather.type)
        assertEquals(RoadEventSeverity.Moderate, weather.severity)
    }

    @Test
    fun severeWind_isHighSeverityNotModerate() {
        val body = """{"current":{"weather_code":1,"wind_speed_10m":80.0}}"""
        val weather = OpenMeteoWeatherParser.parse(body, nowMillis = 0L)!!
        assertEquals(WeatherConditionType.HighWind, weather.type)
        assertEquals(RoadEventSeverity.High, weather.severity)
    }

    @Test
    fun highWind_doesNotOverrideAnAlreadyMoreSevereCondition() {
        // A windy heavy-rain reading must still read as heavy rain, not get reclassified as wind.
        val body = """{"current":{"weather_code":65,"wind_speed_10m":80.0}}"""
        val weather = OpenMeteoWeatherParser.parse(body, nowMillis = 0L)!!
        assertEquals(WeatherConditionType.HeavyRain, weather.type)
    }

    @Test
    fun lowWind_doesNotTriggerHighWindOverride() {
        val body = """{"current":{"weather_code":0,"wind_speed_10m":10.0}}"""
        val weather = OpenMeteoWeatherParser.parse(body, nowMillis = 0L)!!
        assertEquals(WeatherConditionType.Clear, weather.type)
    }

    @Test
    fun malformedJson_throwsRatherThanSwallowingSilently() {
        var threw = false
        try {
            OpenMeteoWeatherParser.parse("not json", nowMillis = 0L)
        } catch (e: org.json.JSONException) {
            threw = true
        }
        assertTrue("callers (DefaultRoadIntelligenceRepository) map this to RoadIntelligenceException.Malformed", threw)
    }
}
