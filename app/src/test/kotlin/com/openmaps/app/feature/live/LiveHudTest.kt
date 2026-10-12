package com.openmaps.app.feature.live

import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.core.routing.RouteManeuver
import com.openmaps.app.feature.map.NavigationError
import com.openmaps.app.feature.map.NavigationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The HUD mapping is the whole Phase 5 testable surface: it is a pure function of navigation state,
 * so the camera never has to be present (or even modelled) to prove the guidance shown over it is
 * correct.
 */
class LiveHudTest {

    private fun maneuver(
        instruction: String = "Turn left onto 7th Avenue.",
        type: ManeuverType = ManeuverType.TurnLeft,
        distance: Double = 150.0,
        remainingDistance: Double = 8000.0,
        remainingDuration: Double = 780.0,
    ) = RouteManeuver(
        instruction = instruction,
        verbalInstruction = null,
        type = type,
        streetName = null,
        distanceMeters = 150.0,
        beginShapeIndex = 0,
        endShapeIndex = 5,
    )

    private fun progress(
        current: RouteManeuver = maneuver(),
        next: RouteManeuver? = null,
        distanceToNextManeuverMeters: Double = 150.0,
        remainingDistanceMeters: Double = 8000.0,
        remainingDurationSeconds: Double = 780.0,
    ) = RouteProgress(
        snappedShapeIndex = 3,
        lateralDistanceMeters = 4.0,
        distanceTraveledMeters = 100.0,
        remainingDistanceMeters = remainingDistanceMeters,
        remainingDurationSeconds = remainingDurationSeconds,
        progressFraction = 0.2,
        currentManeuver = current,
        nextManeuver = next,
        distanceToNextManeuverMeters = distanceToNextManeuverMeters,
        isOffRoute = false,
    )

    @Test
    fun idleWhenNotNavigating() {
        assertEquals(LiveHudState.Idle, LiveHud.state(NavigationState.Idle, "Central Park"))
        assertEquals(LiveHudState.Idle, LiveHud.state(NavigationState.Ready, "Central Park"))
        assertEquals(
            LiveHudState.Idle,
            LiveHud.state(NavigationState.Error(NavigationError.RouteUnavailable), "Central Park"),
        )
    }

    @Test
    fun activeMapsCurrentManeuverAndDistance() {
        val hud = LiveHud.state(NavigationState.Active(progress()), "Central Park")

        assertTrue(hud is LiveHudState.Maneuver)
        hud as LiveHudState.Maneuver
        assertEquals(ManeuverType.TurnLeft, hud.type)
        assertEquals("Turn left onto 7th Avenue.", hud.instruction)
        assertEquals(150.0, hud.distanceToManeuverMeters, 1e-9)
        assertEquals(8000.0, hud.remainingDistanceMeters, 1e-9)
        assertEquals(780.0, hud.remainingDurationSeconds, 1e-9)
        assertEquals("Central Park", hud.destinationName)
    }

    @Test
    fun active_pairsCurrentManeuverWithTheDistanceToIt_notTheNextOne() {
        // `distanceToNextManeuverMeters` measures to the CURRENT maneuver's end index, so pairing it
        // with the following maneuver's instruction would mislabel every turn.
        val current = maneuver(instruction = "Turn left onto 7th Avenue.", type = ManeuverType.TurnLeft)
        val next = maneuver(instruction = "Turn right onto 5th Avenue.", type = ManeuverType.TurnRight)

        val hud = LiveHud.state(NavigationState.Active(progress(current = current, next = next)), null) as LiveHudState.Maneuver

        assertEquals("primary line is the current maneuver", "Turn left onto 7th Avenue.", hud.instruction)
        assertEquals(ManeuverType.TurnLeft, hud.type)
        assertEquals("following maneuver is secondary", "Turn right onto 5th Avenue.", hud.nextInstruction)
    }

    @Test
    fun active_omitsNextInstructionWhenItDuplicatesTheCurrentOne() {
        // A "continue" repeated on the same street is noise, not a second line.
        val same = maneuver()
        val hud = LiveHud.state(NavigationState.Active(progress(current = same, next = same)), null) as LiveHudState.Maneuver
        assertNull(hud.nextInstruction)
    }

    @Test
    fun active_omitsNextInstructionWhenBlank() {
        val next = maneuver(instruction = "   ")
        val hud = LiveHud.state(NavigationState.Active(progress(next = next)), null) as LiveHudState.Maneuver
        assertNull(hud.nextInstruction)
    }

    @Test
    fun active_fallsBackToStreetNameWhenInstructionIsBlank() {
        val current = RouteManeuver(
            instruction = "",
            verbalInstruction = null,
            type = ManeuverType.Continue,
            streetName = "Broadway",
            distanceMeters = 100.0,
            beginShapeIndex = 0,
            endShapeIndex = 5,
        )
        val hud = LiveHud.state(NavigationState.Active(progress(current = current)), null) as LiveHudState.Maneuver
        assertEquals("Broadway", hud.instruction)
    }

    @Test
    fun offRouteKeepsDestinationAndDropsTripFigures() {
        // Off route the remaining numbers are stale, so the HUD must not present them as current.
        val hud = LiveHud.state(NavigationState.OffRoute(progress()), "Central Park")
        assertEquals(LiveHudState.OffRoute("Central Park"), hud)
    }

    @Test
    fun reroutingIsItsOwnStateAndCarriesNoStaleFigures() {
        assertEquals(LiveHudState.Rerouting, LiveHud.state(NavigationState.Rerouting, "Central Park"))
    }

    @Test
    fun arrivedKeepsDestinationForTheSummary() {
        assertEquals(
            LiveHudState.Arrived("Central Park"),
            LiveHud.state(NavigationState.Arrived(progress(remainingDistanceMeters = 0.0)), "Central Park"),
        )
    }

    @Test
    fun destinationNameIsOptional() {
        val hud = LiveHud.state(NavigationState.Active(progress()), null) as LiveHudState.Maneuver
        assertNull(hud.destinationName)
    }

    @Test
    fun everyNavigatingStateMapsToSomethingRenderable() {
        // Guards the `when` from gaining a state the HUD forgets: no branch may return null or throw.
        val states = listOf(
            NavigationState.Idle,
            NavigationState.Ready,
            NavigationState.Active(progress()),
            NavigationState.OffRoute(progress()),
            NavigationState.Rerouting,
            NavigationState.Arrived(progress()),
            NavigationState.Error(NavigationError.LocationUnavailable),
        )
        states.forEach { navigationState ->
            assertTrue(
                "unmapped state: $navigationState",
                LiveHud.state(navigationState, "Central Park") !is LiveHudState.Idle || navigationState is NavigationState.Idle ||
                    navigationState is NavigationState.Ready || navigationState is NavigationState.Error,
            )
        }
    }
}