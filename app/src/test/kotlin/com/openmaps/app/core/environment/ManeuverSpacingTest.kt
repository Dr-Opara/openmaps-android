package com.openmaps.app.core.environment

import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.core.routing.RouteManeuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ManeuverSpacingTest {

    @Test
    fun emptyList_returnsNull() {
        assertNull(ManeuverSpacing.averageSpacingMeters(emptyList()))
    }

    @Test
    fun allZeroLengthManeuvers_returnsNull() {
        val maneuvers = listOf(maneuver(0.0), maneuver(0.0))
        assertNull(ManeuverSpacing.averageSpacingMeters(maneuvers))
    }

    @Test
    fun averagesOnlyThePositiveLengthManeuvers() {
        // A typical route: a zero-length "Start" followed by real segments and a zero-length arrival.
        val maneuvers = listOf(maneuver(0.0), maneuver(100.0), maneuver(300.0), maneuver(0.0))
        assertEquals(200.0, ManeuverSpacing.averageSpacingMeters(maneuvers)!!, 1e-9)
    }

    @Test
    fun singleUsableManeuver_returnsItsOwnLength() {
        assertEquals(450.0, ManeuverSpacing.averageSpacingMeters(listOf(maneuver(450.0)))!!, 1e-9)
    }

    private fun maneuver(distanceMeters: Double) = RouteManeuver(
        instruction = "Continue",
        verbalInstruction = null,
        type = ManeuverType.Continue,
        streetName = null,
        distanceMeters = distanceMeters,
        beginShapeIndex = 0,
        endShapeIndex = 1,
    )
}
