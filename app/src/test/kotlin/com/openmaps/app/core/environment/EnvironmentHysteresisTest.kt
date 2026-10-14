package com.openmaps.app.core.environment

import org.junit.Assert.assertEquals
import org.junit.Test

class EnvironmentHysteresisTest {

    @Test
    fun startsAtUnknown() {
        assertEquals(DrivingEnvironment.Unknown, EnvironmentHysteresis().currentMode())
    }

    @Test
    fun singleDifferingSample_doesNotSwitch() {
        val hysteresis = EnvironmentHysteresis(confirmTicks = 3)
        val result = hysteresis.update(DrivingEnvironment.Highway)
        assertEquals(DrivingEnvironment.Unknown, result)
    }

    @Test
    fun threeConsecutiveSamples_switchesMode() {
        val hysteresis = EnvironmentHysteresis(confirmTicks = 3)
        hysteresis.update(DrivingEnvironment.Highway)
        hysteresis.update(DrivingEnvironment.Highway)
        val result = hysteresis.update(DrivingEnvironment.Highway)
        assertEquals(DrivingEnvironment.Highway, result)
    }

    @Test
    fun cityToHighway_transitionsAfterConfirmTicks() {
        val hysteresis = EnvironmentHysteresis(confirmTicks = 2)
        repeat(2) { hysteresis.update(DrivingEnvironment.City) }
        assertEquals(DrivingEnvironment.City, hysteresis.currentMode())

        hysteresis.update(DrivingEnvironment.Highway)
        assertEquals("one sample must not switch yet", DrivingEnvironment.City, hysteresis.currentMode())
        hysteresis.update(DrivingEnvironment.Highway)
        assertEquals(DrivingEnvironment.Highway, hysteresis.currentMode())
    }

    @Test
    fun highwayToSuburb_transitionsAfterConfirmTicks() {
        val hysteresis = EnvironmentHysteresis(confirmTicks = 2)
        repeat(2) { hysteresis.update(DrivingEnvironment.Highway) }
        repeat(2) { hysteresis.update(DrivingEnvironment.Suburb) }
        assertEquals(DrivingEnvironment.Suburb, hysteresis.currentMode())
    }

    @Test
    fun suburbToRural_transitionsAfterConfirmTicks() {
        val hysteresis = EnvironmentHysteresis(confirmTicks = 2)
        repeat(2) { hysteresis.update(DrivingEnvironment.Suburb) }
        repeat(2) { hysteresis.update(DrivingEnvironment.Rural) }
        assertEquals(DrivingEnvironment.Rural, hysteresis.currentMode())
    }

    @Test
    fun ruralToCity_transitionsAfterConfirmTicks() {
        val hysteresis = EnvironmentHysteresis(confirmTicks = 2)
        repeat(2) { hysteresis.update(DrivingEnvironment.Rural) }
        repeat(2) { hysteresis.update(DrivingEnvironment.City) }
        assertEquals(DrivingEnvironment.City, hysteresis.currentMode())
    }

    @Test
    fun briefClassificationNoise_isSuppressed() {
        val hysteresis = EnvironmentHysteresis(confirmTicks = 3)
        repeat(3) { hysteresis.update(DrivingEnvironment.Suburb) }
        assertEquals(DrivingEnvironment.Suburb, hysteresis.currentMode())

        // A single noisy tick classifying City must not switch the confirmed mode.
        val result = hysteresis.update(DrivingEnvironment.City)
        assertEquals(DrivingEnvironment.Suburb, result)
        // And the very next tick returning to Suburb must not have "half-confirmed" City either.
        hysteresis.update(DrivingEnvironment.Suburb)
        assertEquals(DrivingEnvironment.Suburb, hysteresis.currentMode())
    }

    @Test
    fun alternatingCandidates_neverAccumulateAcrossDifferentValues() {
        val hysteresis = EnvironmentHysteresis(confirmTicks = 2)
        hysteresis.update(DrivingEnvironment.Highway) // streak(Highway) = 1
        hysteresis.update(DrivingEnvironment.Rural) // candidate switches to Rural, streak = 1
        hysteresis.update(DrivingEnvironment.Highway) // candidate switches back, streak = 1
        // Never two consecutive identical candidates, so nothing should have confirmed.
        assertEquals(DrivingEnvironment.Unknown, hysteresis.currentMode())
    }

    @Test
    fun matchingCurrentMode_resetsCandidateStreak() {
        val hysteresis = EnvironmentHysteresis(confirmTicks = 2)
        repeat(2) { hysteresis.update(DrivingEnvironment.City) }
        hysteresis.update(DrivingEnvironment.Highway) // candidate streak = 1
        hysteresis.update(DrivingEnvironment.City) // matches current mode; candidate cleared
        val result = hysteresis.update(DrivingEnvironment.Highway) // candidate streak restarts at 1
        assertEquals("the earlier partial Highway streak must not have carried over", DrivingEnvironment.City, result)
    }

    @Test
    fun reset_returnsToUnknownAndClearsCandidateState() {
        val hysteresis = EnvironmentHysteresis(confirmTicks = 2)
        repeat(2) { hysteresis.update(DrivingEnvironment.Rural) }
        hysteresis.reset()
        assertEquals(DrivingEnvironment.Unknown, hysteresis.currentMode())
        // Needs a fresh confirm streak after reset, same as a brand-new instance would.
        val result = hysteresis.update(DrivingEnvironment.Rural)
        assertEquals(DrivingEnvironment.Unknown, result)
    }
}
