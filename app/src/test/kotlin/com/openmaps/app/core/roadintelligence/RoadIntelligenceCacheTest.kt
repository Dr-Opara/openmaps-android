package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoadIntelligenceCacheTest {

    private val center = Coordinate(40.0, -74.0)

    @Test
    fun hazards_missBeforePut() {
        assertNull(RoadIntelligenceCache().getHazards(center))
    }

    @Test
    fun hazards_hitWithinTtl_missAfterExpiry() {
        var now = 0L
        val cache = RoadIntelligenceCache(now = { now })
        val events = listOf(hazard("h1"))
        cache.putHazards(center, events)

        now = 3 * 60 * 1000L - 1 // just under the 3-minute hazard TTL
        assertEquals(events, cache.getHazards(center))

        now = 3 * 60 * 1000L
        assertNull(cache.getHazards(center))
    }

    @Test
    fun weather_hitWithinTtl_missAfterExpiry() {
        var now = 0L
        val cache = RoadIntelligenceCache(now = { now })
        val condition = weather()
        cache.putWeather(center, condition)

        now = 20 * 60 * 1000L - 1 // just under the 20-minute weather TTL
        assertEquals(condition, cache.getWeather(center))

        now = 20 * 60 * 1000L
        assertNull(cache.getWeather(center))
    }

    @Test
    fun speedLimit_hitWithinTtl_missAfterExpiry() {
        var now = 0L
        val cache = RoadIntelligenceCache(now = { now })
        val info = SpeedLimitInfo(45.0, SpeedUnit.Mph, "osm:maxspeed")
        cache.putSpeedLimit(center, info)

        now = 30 * 60 * 1000L - 1 // just under the 30-minute speed-limit TTL
        assertEquals(info, cache.getSpeedLimit(center))

        now = 30 * 60 * 1000L
        assertNull(cache.getSpeedLimit(center))
    }

    @Test
    fun nearbyCenterWithinBucket_isHit_farCenterIsMiss() {
        val cache = RoadIntelligenceCache()
        val info = SpeedLimitInfo(35.0, SpeedUnit.Mph, "osm:maxspeed")
        cache.putSpeedLimit(center, info)

        // A few meters away, well inside the ~165 m speed-limit bucket.
        assertEquals(info, cache.getSpeedLimit(Coordinate(40.00001, -74.00001)))
        // Far enough away to land in a different bucket.
        assertNull(cache.getSpeedLimit(Coordinate(41.0, -74.0)))
    }

    @Test
    fun threeCachesAreIndependent() {
        val cache = RoadIntelligenceCache()
        cache.putHazards(center, listOf(hazard("h1")))
        assertNull("putting hazards must not populate the weather cache", cache.getWeather(center))
        assertNull("putting hazards must not populate the speed-limit cache", cache.getSpeedLimit(center))
    }

    @Test
    fun clear_removesEveryEntryAcrossAllThreeCaches() {
        val cache = RoadIntelligenceCache()
        cache.putHazards(center, listOf(hazard("h1")))
        cache.putWeather(center, weather())
        cache.putSpeedLimit(center, SpeedLimitInfo(25.0, SpeedUnit.Mph, "osm:maxspeed"))

        cache.clear()

        assertNull(cache.getHazards(center))
        assertNull(cache.getWeather(center))
        assertNull(cache.getSpeedLimit(center))
    }

    private fun hazard(id: String) = RoadEvent(
        id = id,
        type = RoadEventType.Construction,
        latitude = 0.0,
        longitude = 0.0,
        severity = RoadEventSeverity.Moderate,
        title = "Construction",
    )

    private fun weather() = WeatherCondition(
        type = WeatherConditionType.Fog,
        severity = RoadEventSeverity.Moderate,
        description = "Fog",
        observedAtMillis = 0L,
    )
}
