package com.openmaps.app.map

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.RouteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineCorridorPolicyTest {

    @Test
    fun shortRouteUsesDetailedZoomAndPadding() {
        val route = RouteResult(
            geometry = listOf(
                Coordinate(29.75, -95.38),
                Coordinate(29.80, -95.30),
            ),
            distanceMeters = 12_000.0,
            durationSeconds = 900.0,
        )

        val plan = OfflineCorridorPolicy.plan(route)

        assertNotNull(plan)
        assertEquals(15.0, plan!!.maxZoom, 0.0)
        assertTrue(plan.bounds.south < 29.75)
        assertTrue(plan.bounds.north > 29.80)
        assertTrue(plan.bounds.west < -95.38)
        assertTrue(plan.bounds.east > -95.30)
    }

    @Test
    fun longRouteReducesMaximumZoom() {
        val route = RouteResult(
            geometry = listOf(
                Coordinate(29.76, -95.37),
                Coordinate(32.78, -96.80),
            ),
            distanceMeters = 420_000.0,
            durationSeconds = 15_000.0,
        )

        val plan = OfflineCorridorPolicy.plan(route)

        assertNotNull(plan)
        assertEquals(12.0, plan!!.maxZoom, 0.0)
        assertEquals(6.0, plan.minZoom, 0.0)
    }

    @Test
    fun emptyRouteCannotCreateOfflineRegion() {
        val route = RouteResult(
            geometry = emptyList(),
            distanceMeters = 0.0,
            durationSeconds = 0.0,
        )

        assertEquals(null, OfflineCorridorPolicy.plan(route))
    }
}
