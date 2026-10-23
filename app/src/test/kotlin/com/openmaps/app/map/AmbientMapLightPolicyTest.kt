package com.openmaps.app.map

import org.junit.Assert.assertEquals
import org.junit.Test

class AmbientMapLightPolicyTest {
    @Test fun lowLightChoosesNight() {
        assertEquals(AmbientLightDecision.Night, AmbientMapLightPolicy.classify(0f))
        assertEquals(AmbientLightDecision.Night, AmbientMapLightPolicy.classify(12f))
    }
    @Test fun brightLightChoosesDay() {
        assertEquals(AmbientLightDecision.Day, AmbientMapLightPolicy.classify(85f))
        assertEquals(AmbientLightDecision.Day, AmbientMapLightPolicy.classify(12000f))
    }
    @Test fun twilightBandHoldsLastStableMode() {
        assertEquals(AmbientLightDecision.Hold, AmbientMapLightPolicy.classify(15f))
        assertEquals(AmbientLightDecision.Hold, AmbientMapLightPolicy.classify(60f))
    }
    @Test fun invalidSensorValuesNeverTriggerSwitch() {
        assertEquals(AmbientLightDecision.Hold, AmbientMapLightPolicy.classify(null))
        assertEquals(AmbientLightDecision.Hold, AmbientMapLightPolicy.classify(Float.NaN))
        assertEquals(AmbientLightDecision.Hold, AmbientMapLightPolicy.classify(-1f))
        assertEquals(AmbientLightDecision.Hold, AmbientMapLightPolicy.classify(Float.POSITIVE_INFINITY))
    }
    @Test fun settlingPeriodIsLongEnoughForTransientShadows() {
        assertEquals(15_000L, AmbientMapLightPolicy.SETTLE_MILLIS)
    }
}
