package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoadEventRouteMatcherTest {

    /** A straight east-bound route at the equator: 1000 points ~111.32 m apart is overkill; keep it short. */
    private val geometry = listOf(
        Coordinate(0.0, 0.0),
        Coordinate(0.0, 0.001), // ~111.32 m
        Coordinate(0.0, 0.002), // ~222.64 m
        Coordinate(0.0, 0.003), // ~333.96 m
    )

    @Test
    fun eventOnTheLine_isStampedWithCumulativeRouteDistance() {
        val event = hazard(id = "h1", latitude = 0.0, longitude = 0.002)
        val matched = RoadEventRouteMatcher.matchToRoute(listOf(event), geometry, corridorMeters = 50.0)

        assertEquals(1, matched.size)
        assertEquals(222.64, matched.single().routeDistanceMeters!!, 1.0)
    }

    @Test
    fun eventTooFarFromTheRoute_isDropped() {
        // ~1.1 km off the line (0.01 deg lat) — far outside a 50 m corridor.
        val event = hazard(id = "h1", latitude = 0.01, longitude = 0.002)
        val matched = RoadEventRouteMatcher.matchToRoute(listOf(event), geometry, corridorMeters = 50.0)
        assertTrue(matched.isEmpty())
    }

    @Test
    fun eventWithinCorridor_isKept() {
        // ~11 m off the line — inside a 50 m corridor.
        val event = hazard(id = "h1", latitude = 0.0001, longitude = 0.002)
        val matched = RoadEventRouteMatcher.matchToRoute(listOf(event), geometry, corridorMeters = 50.0)
        assertEquals(1, matched.size)
    }

    @Test
    fun multipleEvents_areSortedByRouteDistanceAscending() {
        val far = hazard(id = "far", latitude = 0.0, longitude = 0.003)
        val near = hazard(id = "near", latitude = 0.0, longitude = 0.001)
        val matched = RoadEventRouteMatcher.matchToRoute(listOf(far, near), geometry, corridorMeters = 50.0)
        assertEquals(listOf("near", "far"), matched.map { it.id })
    }

    @Test
    fun degenerateRoute_returnsEmpty() {
        val event = hazard(id = "h1", latitude = 0.0, longitude = 0.001)
        assertTrue(RoadEventRouteMatcher.matchToRoute(listOf(event), listOf(Coordinate(0.0, 0.0)), 50.0).isEmpty())
    }

    @Test
    fun eventLaterallyOffset_usesProjectedDistanceNotChordDistance() {
        // ~33 m north of the line, closest to the (0.001, 0.002) segment at t=0.1 along it.
        // A naive "chord from the segment start to the point" distance (111.32 + ~35.2 = ~146.5 m)
        // would overcount by the lateral offset; the correct projected distance is ~122.45 m
        // (111.32 m prefix to the segment, plus 11.13 m actually along it).
        val event = hazard(id = "h1", latitude = 0.0003, longitude = 0.0011)
        val matched = RoadEventRouteMatcher.matchToRoute(listOf(event), geometry, corridorMeters = 50.0)
        assertEquals(1, matched.size)
        assertEquals(122.45, matched.single().routeDistanceMeters!!, 1.0)
    }

    private fun hazard(id: String, latitude: Double, longitude: Double) = RoadEvent(
        id = id,
        type = RoadEventType.Construction,
        latitude = latitude,
        longitude = longitude,
        severity = RoadEventSeverity.Moderate,
        title = "Construction",
    )
}
