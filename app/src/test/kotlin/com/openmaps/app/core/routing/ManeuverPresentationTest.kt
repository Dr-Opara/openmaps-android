package com.openmaps.app.core.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class ManeuverPresentationTest {

    @Test
    fun appendsProviderBackedExitAndSignpost() {
        val maneuver = RouteManeuver(
            instruction = "Take the ramp",
            verbalInstruction = null,
            type = ManeuverType.Ramp,
            streetName = "I-10",
            distanceMeters = 500.0,
            beginShapeIndex = 0,
            endShapeIndex = 1,
            exitSign = "Exit 743B",
            signpost = "San Antonio",
        )

        assertEquals(
            "Take the ramp · Exit 743B · San Antonio",
            maneuver.displayInstruction(),
        )
    }

    @Test
    fun doesNotDuplicateExitAlreadyInInstruction() {
        val maneuver = RouteManeuver(
            instruction = "Take Exit 743B toward San Antonio",
            verbalInstruction = null,
            type = ManeuverType.Ramp,
            streetName = "I-10",
            distanceMeters = 500.0,
            beginShapeIndex = 0,
            endShapeIndex = 1,
            exitSign = "Exit 743B",
            signpost = "San Antonio",
        )

        assertEquals("Take Exit 743B toward San Antonio", maneuver.displayInstruction())
    }

    @Test
    fun fallsBackToStreetName() {
        val maneuver = RouteManeuver(
            instruction = "",
            verbalInstruction = null,
            type = ManeuverType.Continue,
            streetName = "Broadway",
            distanceMeters = 100.0,
            beginShapeIndex = 0,
            endShapeIndex = 1,
        )

        assertEquals("Broadway", maneuver.displayInstruction())
    }
}
