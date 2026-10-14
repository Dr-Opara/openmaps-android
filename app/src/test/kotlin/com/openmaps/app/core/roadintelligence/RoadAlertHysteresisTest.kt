package com.openmaps.app.core.roadintelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadAlertHysteresisTest {

    @Test
    fun singleTickCandidate_isNotYetActive() {
        val hysteresis = RoadAlertHysteresis(confirmSamples = 2)
        val result = hysteresis.update(listOf(hazardAlert("h1")))
        assertTrue("one sample must not be enough to confirm", result.isEmpty())
    }

    @Test
    fun twoConsecutiveTicks_confirmsTheAlert() {
        val hysteresis = RoadAlertHysteresis(confirmSamples = 2)
        hysteresis.update(listOf(hazardAlert("h1")))
        val result = hysteresis.update(listOf(hazardAlert("h1")))
        assertEquals(listOf("hazard:h1"), result.map { it.id })
    }

    @Test
    fun oneTickDropout_doesNotDeactivate() {
        val hysteresis = RoadAlertHysteresis(confirmSamples = 2)
        hysteresis.update(listOf(hazardAlert("h1")))
        hysteresis.update(listOf(hazardAlert("h1"))) // confirmed active

        val droppedOnce = hysteresis.update(emptyList())
        assertEquals(
            "a single borderline sample must not blank out an already-active alert",
            listOf("hazard:h1"),
            droppedOnce.map { it.id },
        )
    }

    @Test
    fun twoConsecutiveDropouts_deactivatesTheAlert() {
        val hysteresis = RoadAlertHysteresis(confirmSamples = 2)
        hysteresis.update(listOf(hazardAlert("h1")))
        hysteresis.update(listOf(hazardAlert("h1"))) // confirmed active

        hysteresis.update(emptyList())
        val result = hysteresis.update(emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun flickeringCandidate_everyOtherTick_neverConfirms() {
        val hysteresis = RoadAlertHysteresis(confirmSamples = 2)
        repeat(6) { tick ->
            val candidates = if (tick % 2 == 0) listOf(hazardAlert("h1")) else emptyList()
            val result = hysteresis.update(candidates)
            assertTrue("alternating presence must never accumulate two consecutive confirms", result.isEmpty())
        }
    }

    @Test
    fun confirmedAlert_refreshesItsPayloadWhileStillActive() {
        val hysteresis = RoadAlertHysteresis(confirmSamples = 2)
        hysteresis.update(listOf(hazardAlert("h1", distanceAheadMeters = 500.0)))
        hysteresis.update(listOf(hazardAlert("h1", distanceAheadMeters = 500.0)))
        val result = hysteresis.update(listOf(hazardAlert("h1", distanceAheadMeters = 300.0)))
        val hazard = result.single() as RouteAlert.Hazard
        assertEquals(300.0, hazard.distanceAheadMeters!!, 1e-6)
    }

    @Test
    fun output_isPriorityOrdered() {
        val hysteresis = RoadAlertHysteresis(confirmSamples = 1)
        val result = hysteresis.update(
            listOf(
                RouteAlert.SpeedLimit(SpeedLimitInfo(45.0, SpeedUnit.Mph, "osm:maxspeed")),
                hazardAlert("severe-closure", severity = RoadEventSeverity.Severe, type = RoadEventType.Closure),
            ),
        )
        assertEquals(listOf("hazard:severe-closure", "speed-limit"), result.map { it.id })
    }

    @Test
    fun reset_clearsAllState_soAConfirmedAlertNeedsTwoTicksAgain() {
        val hysteresis = RoadAlertHysteresis(confirmSamples = 2)
        hysteresis.update(listOf(hazardAlert("h1")))
        hysteresis.update(listOf(hazardAlert("h1"))) // confirmed

        hysteresis.reset()
        val result = hysteresis.update(listOf(hazardAlert("h1")))
        assertTrue("state must not survive a reset", result.isEmpty())
    }

    @Test
    fun duplicateIdsAcrossDistinctAlertKinds_areIndependent() {
        val hysteresis = RoadAlertHysteresis(confirmSamples = 1)
        val result = hysteresis.update(
            listOf(
                RouteAlert.Weather(weatherCondition()),
                RouteAlert.Congestion(TrafficCondition(TrafficCongestionLevel.Heavy, baseDurationSeconds = 600.0)),
            ),
        )
        assertEquals(setOf("weather:Fog", "congestion"), result.map { it.id }.toSet())
    }

    private fun hazardAlert(
        id: String,
        distanceAheadMeters: Double = 100.0,
        severity: RoadEventSeverity = RoadEventSeverity.High,
        type: RoadEventType = RoadEventType.Crash,
    ) = RouteAlert.Hazard(
        event = RoadEvent(id = id, type = type, latitude = 0.0, longitude = 0.0, severity = severity, title = "Event"),
        distanceAheadMeters = distanceAheadMeters,
    )

    private fun weatherCondition() = WeatherCondition(
        type = WeatherConditionType.Fog,
        severity = RoadEventSeverity.Moderate,
        description = "Fog",
        observedAtMillis = 0L,
    )
}
