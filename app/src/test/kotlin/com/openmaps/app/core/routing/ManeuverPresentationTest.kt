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
    fun typedTurnReplacesGenericProviderInstruction() {
        val left = RouteManeuver(
            instruction = "turn", verbalInstruction = null, type = ManeuverType.TurnLeft,
            streetName = "Tamarro Xing", distanceMeters = 320.0,
            beginShapeIndex = 0, endShapeIndex = 1,
        )
        val right = left.copy(type = ManeuverType.TurnRight)
        assertEquals("Turn left", left.displayInstruction())
        assertEquals("Turn right", right.displayInstruction())
    }

    @Test
    fun roundaboutApiTokensBecomeReadableInstructions() {
        val entry = RouteManeuver(
            instruction = "roundaboutEnter", verbalInstruction = null,
            type = ManeuverType.Roundabout, streetName = null,
            distanceMeters = 110.0, beginShapeIndex = 0, endShapeIndex = 1,
        )
        assertEquals("Enter the roundabout", entry.displayInstruction())
        assertEquals("Exit the roundabout", entry.copy(instruction = "roundaboutExit").displayInstruction())
    }

    @Test
    fun preservesSpecificProviderGuidanceWithoutInventingRoad() {
        val step = RouteManeuver(
            instruction = "Turn left onto Oak Ridge Drive", verbalInstruction = null,
            type = ManeuverType.TurnLeft, streetName = "Oak Ridge Drive",
            distanceMeters = 400.0, beginShapeIndex = 0, endShapeIndex = 1,
        )
        assertEquals("Turn left onto Oak Ridge Drive", step.displayInstruction())
    }

    @Test
    fun genericDepartUsesProviderStreetInsteadOfPlaceholder() {
        val maneuver = RouteManeuver(
            instruction = "depart", verbalInstruction = null,
            type = ManeuverType.Start, streetName = "East 81st Street",
            distanceMeters = 400.0, beginShapeIndex = 0, endShapeIndex = 1,
        )
        assertEquals("Depart on East 81st Street", maneuver.displayInstruction())
    }

    @Test
    fun genericDepartPrefersRichProviderSpokenInstructionWhenAvailable() {
        val maneuver = RouteManeuver(
            instruction = "depart",
            verbalInstruction = "Drive northwest on East 81st Street",
            type = ManeuverType.Start, streetName = "East 81st Street",
            distanceMeters = 400.0, beginShapeIndex = 0, endShapeIndex = 1,
        )
        assertEquals("Drive northwest on East 81st Street", maneuver.displayInstruction())
    }

    @Test
    fun noInventedBearingWhenProviderOmitsSpokenInstruction() {
        val maneuver = RouteManeuver(
            instruction = "turn", verbalInstruction = null,
            type = ManeuverType.TurnRight, streetName = "Oak Ridge Drive",
            distanceMeters = 110.0, beginShapeIndex = 0, endShapeIndex = 1,
        )
        assertEquals("Turn right", maneuver.displayInstruction())
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
