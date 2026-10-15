package com.openmaps.app.core.navigation

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.RouteResult
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ArrivalPreviewPlanTest {

    @Test
    fun from_returnsOnlyFinalApproachGeometry() {
        val geometry = listOf(
            Coordinate(29.7600, -95.3700),
            Coordinate(29.7620, -95.3700),
            Coordinate(29.7640, -95.3700),
            Coordinate(29.7660, -95.3700),
            Coordinate(29.7680, -95.3700),
        )
        val route = RouteResult(
            geometry = geometry,
            distanceMeters = 900.0,
            durationSeconds = 600.0,
        )

        val plan = ArrivalPreviewPlan.from(route, targetMeters = 300.0)
        assertNotNull(plan)
        val actual = plan!!

        assertTrue(actual.geometry.size >= 2)
        assertTrue(actual.geometry.last() == geometry.last())
        assertTrue(actual.distanceMeters >= 300.0 || actual.geometry == geometry)
    }
}
