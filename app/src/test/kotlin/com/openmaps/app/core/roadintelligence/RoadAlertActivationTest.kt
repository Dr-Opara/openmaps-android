package com.openmaps.app.core.roadintelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadAlertActivationTest {

    private val config = RoadAlertActivationConfig()

    @Test
    fun distanceAheadMeters_eventAheadOfDriver_isPositive() {
        val event = hazard(routeDistanceMeters = 500.0)
        assertEquals(300.0, RoadAlertActivation.distanceAheadMeters(event, distanceTraveledMeters = 200.0)!!, 1e-6)
    }

    @Test
    fun distanceAheadMeters_eventBehindDriver_isNull() {
        val event = hazard(routeDistanceMeters = 100.0)
        assertNull(RoadAlertActivation.distanceAheadMeters(event, distanceTraveledMeters = 200.0))
    }

    @Test
    fun distanceAheadMeters_eventExactlyAtDriver_isZeroNotNull() {
        val event = hazard(routeDistanceMeters = 200.0)
        assertEquals(0.0, RoadAlertActivation.distanceAheadMeters(event, distanceTraveledMeters = 200.0)!!, 1e-6)
    }

    @Test
    fun distanceAheadMeters_unmatchedEvent_isNull() {
        val event = hazard(routeDistanceMeters = null)
        assertNull(RoadAlertActivation.distanceAheadMeters(event, distanceTraveledMeters = 0.0))
    }

    @Test
    fun activeHazards_suppressesEventsBehindTheDriver() {
        val behind = hazard(routeDistanceMeters = 50.0, id = "behind")
        val ahead = hazard(routeDistanceMeters = 500.0, id = "ahead")
        val active = RoadAlertActivation.activeHazards(listOf(behind, ahead), distanceTraveledMeters = 200.0, config = config)
        assertEquals(listOf("ahead"), active.map { it.first.id })
    }

    @Test
    fun activeHazards_collisionClassEvent_activatesWithinApproachDistance() {
        val crash = hazard(routeDistanceMeters = 700.0, type = RoadEventType.Crash)
        val active = RoadAlertActivation.activeHazards(listOf(crash), distanceTraveledMeters = 0.0, config = config)
        // 700 m ahead, within the default 800 m collision-class approach distance.
        assertEquals(1, active.size)
        assertEquals(700.0, active.single().second, 1e-6)
    }

    @Test
    fun activeHazards_collisionClassEvent_notYetActiveBeyondApproachDistance() {
        val crash = hazard(routeDistanceMeters = 900.0, type = RoadEventType.Crash)
        val active = RoadAlertActivation.activeHazards(listOf(crash), distanceTraveledMeters = 0.0, config = config)
        assertTrue(active.isEmpty())
    }

    @Test
    fun activeHazards_severeClosure_usesTheWiderProminenceDistance() {
        val closure = hazard(routeDistanceMeters = 2_500.0, type = RoadEventType.Closure, severity = RoadEventSeverity.Severe)
        val active = RoadAlertActivation.activeHazards(listOf(closure), distanceTraveledMeters = 0.0, config = config)
        // 2.5 km ahead: past the 800 m collision-class distance but within the 3 km closure
        // prominence distance — a severe closure is known about from farther away.
        assertEquals(1, active.size)
    }

    @Test
    fun activeHazards_nonSevereClosure_usesTheNarrowerCollisionClassDistance() {
        val closure = hazard(routeDistanceMeters = 2_500.0, type = RoadEventType.Closure, severity = RoadEventSeverity.Moderate)
        val active = RoadAlertActivation.activeHazards(listOf(closure), distanceTraveledMeters = 0.0, config = config)
        assertTrue(active.isEmpty())
    }

    @Test
    fun activeHazards_informationalEvent_usesTheNarrowestApproachDistance() {
        val construction = hazard(routeDistanceMeters = 500.0, type = RoadEventType.Construction)
        val tooFar = RoadAlertActivation.activeHazards(listOf(construction), distanceTraveledMeters = 0.0, config = config)
        assertTrue("500 m exceeds construction's 400 m default approach distance", tooFar.isEmpty())

        val close = hazard(routeDistanceMeters = 300.0, type = RoadEventType.Construction)
        val active = RoadAlertActivation.activeHazards(listOf(close), distanceTraveledMeters = 0.0, config = config)
        assertEquals(1, active.size)
    }

    private fun hazard(
        routeDistanceMeters: Double?,
        id: String = "h",
        type: RoadEventType = RoadEventType.Crash,
        severity: RoadEventSeverity = RoadEventSeverity.High,
    ) = RoadEvent(
        id = id,
        type = type,
        latitude = 0.0,
        longitude = 0.0,
        routeDistanceMeters = routeDistanceMeters,
        severity = severity,
        title = "Event",
    )
}
