package com.openmaps.app.core.navigation

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.RouteResult
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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

        val plan = assertNotNull(ArrivalPreviewPlan.from(route, targetMeters = 300.0))

        assertTrue(plan.geometry.size >= 2)
        assertTrue(plan.geometry.last() == geometry.last())
        assertTrue(plan.distanceMeters >= 300.0 || plan.geometry == geometry)
    }
}
