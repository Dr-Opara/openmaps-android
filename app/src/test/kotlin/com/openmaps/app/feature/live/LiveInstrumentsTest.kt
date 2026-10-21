package com.openmaps.app.feature.live

import org.junit.Assert.assertEquals
import org.junit.Test

class LiveInstrumentsTest {
    @Test fun stoppedSpeedHasEmptyArc() {
        assertEquals(0f, speedArcFraction(0f, true), 0.0001f)
    }

    @Test fun sixtyMphIsHalfTheMphDial() {
        assertEquals(0.5f, speedArcFraction(26.8224f, true), 0.001f)
    }

    @Test fun oneHundredKmhIsHalfTheMetricDial() {
        assertEquals(0.5f, speedArcFraction(27.77778f, false), 0.001f)
    }

    @Test fun speedBeyondScaleClampsToFullArc() {
        assertEquals(1f, speedArcFraction(100f, true), 0.0001f)
    }

    @Test fun negativeOrUnavailableSpeedDoesNotFakeMotion() {
        assertEquals(0f, speedArcFraction(-1f, true), 0.0001f)
        assertEquals(0f, speedArcFraction(Float.NaN, false), 0.0001f)
    }
}
