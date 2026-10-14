package com.openmaps.app.core.roadintelligence

import org.junit.Assert.assertEquals
import org.junit.Test

class RoadAlertPriorityTest {

    @Test
    fun severeClosure_outranksEverything() {
        val closure = hazardAlert(RoadEventType.Closure, RoadEventSeverity.Severe)
        val crash = hazardAlert(RoadEventType.Crash, RoadEventSeverity.High)
        assertEquals(listOf(closure, crash), RoadAlertPriority.sorted(listOf(crash, closure)))
    }

    @Test
    fun collisionClassHazard_outranksWeather() {
        val crash = hazardAlert(RoadEventType.Crash, RoadEventSeverity.High)
        val weather = RouteAlert.Weather(weather(RoadEventSeverity.High))
        assertEquals(listOf(crash, weather), RoadAlertPriority.sorted(listOf(weather, crash)))
    }

    @Test
    fun dangerousWeather_outranksLaneGuidance() {
        val weather = RouteAlert.Weather(weather(RoadEventSeverity.High))
        val lane = RouteAlert.Lane(LaneGuidance(emptyList(), emptyList(), null))
        assertEquals(listOf(weather, lane), RoadAlertPriority.sorted(listOf(lane, weather)))
    }

    @Test
    fun laneGuidance_outranksSpeedLimit() {
        val lane = RouteAlert.Lane(LaneGuidance(emptyList(), emptyList(), null))
        val speed = RouteAlert.SpeedLimit(SpeedLimitInfo(45.0, SpeedUnit.Mph, "osm:maxspeed"))
        assertEquals(listOf(lane, speed), RoadAlertPriority.sorted(listOf(speed, lane)))
    }

    @Test
    fun speedLimit_outranksCongestion() {
        val speed = RouteAlert.SpeedLimit(SpeedLimitInfo(45.0, SpeedUnit.Mph, "osm:maxspeed"))
        val congestion = RouteAlert.Congestion(TrafficCondition(TrafficCongestionLevel.Heavy, 600.0))
        assertEquals(listOf(speed, congestion), RoadAlertPriority.sorted(listOf(congestion, speed)))
    }

    @Test
    fun congestion_outranksInformational() {
        val congestion = RouteAlert.Congestion(TrafficCondition(TrafficCongestionLevel.Heavy, 600.0))
        val info = hazardAlert(RoadEventType.Construction, RoadEventSeverity.Low)
        assertEquals(listOf(congestion, info), RoadAlertPriority.sorted(listOf(info, congestion)))
    }

    @Test
    fun lowSeverityConstruction_ranksAsInformational_highSeverityRanksAsCollisionClass() {
        val lowConstruction = hazardAlert(RoadEventType.Construction, RoadEventSeverity.Low)
        val highConstruction = hazardAlert(RoadEventType.Construction, RoadEventSeverity.High)
        val sorted = RoadAlertPriority.sorted(listOf(lowConstruction, highConstruction))
        assertEquals(listOf(highConstruction, lowConstruction), sorted)
    }

    @Test
    fun mildWeather_ranksAsInformationalNotDangerous() {
        val mild = RouteAlert.Weather(weather(RoadEventSeverity.Low))
        val congestion = RouteAlert.Congestion(TrafficCondition(TrafficCongestionLevel.Heavy, 600.0))
        // Mild weather must not outrank congestion the way "dangerous" weather does.
        assertEquals(listOf(congestion, mild), RoadAlertPriority.sorted(listOf(mild, congestion)))
    }

    @Test
    fun sortIsStable_tiesKeepRelativeOrder() {
        val a = hazardAlert(RoadEventType.Crash, RoadEventSeverity.High, id = "a")
        val b = hazardAlert(RoadEventType.Debris, RoadEventSeverity.High, id = "b")
        assertEquals(listOf(a, b), RoadAlertPriority.sorted(listOf(a, b)))
    }

    private fun hazardAlert(type: RoadEventType, severity: RoadEventSeverity, id: String = "h") = RouteAlert.Hazard(
        event = RoadEvent(id = id, type = type, latitude = 0.0, longitude = 0.0, severity = severity, title = "Event"),
        distanceAheadMeters = 100.0,
    )

    private fun weather(severity: RoadEventSeverity) = WeatherCondition(
        type = WeatherConditionType.Fog,
        severity = severity,
        description = "Fog",
        observedAtMillis = 0L,
    )
}
