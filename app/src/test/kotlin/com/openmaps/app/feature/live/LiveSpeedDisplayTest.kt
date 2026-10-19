package com.openmaps.app.feature.live

import com.openmaps.app.core.localization.MeasurementSystem
import com.openmaps.app.core.roadintelligence.RouteAlert
import com.openmaps.app.core.roadintelligence.SpeedLimitInfo
import com.openmaps.app.core.roadintelligence.SpeedUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.util.Locale
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Delta-spec section 8: current speed and the posted limit must be independently real — neither
 * ever implies the other exists.
 */
class LiveSpeedDisplayTest {

    private fun limit(value: Double = 55.0, unit: SpeedUnit = SpeedUnit.Mph) =
        RouteAlert.SpeedLimit(SpeedLimitInfo(value, unit, source = "test"))

    @Test
    fun bothPresent_showsBothFigures() {
        val text = speedLineText(
            currentSpeedMetersPerSecond = 20.1168f,
            speedLimitAlert = limit(55.0),
            measurementSystem = MeasurementSystem.Auto,
            locale = Locale.US,
        )
        assertTrue("expected both current speed and the limit: $text", text!!.contains("mph") && text.contains("55"))
    }

    @Test
    fun onlyCurrentSpeed_showsCurrentSpeed_neverAFabricatedLimit() {
        val text = speedLineText(
            currentSpeedMetersPerSecond = 13.4112f,
            speedLimitAlert = null,
            measurementSystem = MeasurementSystem.Auto,
            locale = Locale.US,
        )
        assertTrue(text!!.contains("mph"))
        assertTrue("must never show a limit that was never real", !text.contains("Speed limit"))
    }

    @Test
    fun onlyPostedLimit_showsLimit_withoutFabricatingACurrentSpeedReading() {
        val text = speedLineText(
            currentSpeedMetersPerSecond = null,
            speedLimitAlert = limit(45.0),
            measurementSystem = MeasurementSystem.Auto,
            locale = Locale.US,
        )
        assertEquals("Speed limit 45 mph", text)
    }

    @Test
    fun metricLocale_convertsCurrentAndPostedSpeedToKmh() {
        val text = speedLineText(
            currentSpeedMetersPerSecond = 20.1168f,
            speedLimitAlert = limit(55.0),
            measurementSystem = MeasurementSystem.Auto,
            locale = Locale.FRANCE,
        )
        assertTrue("expected metric current speed and converted limit: $text", text!!.contains("km/h"))
        assertTrue(text.contains("89"))
    }

    @Test
    fun neitherPresent_showsNothing() {
        assertNull(speedLineText(currentSpeedMetersPerSecond = null, speedLimitAlert = null))
    }

    @Test
    fun noPostedLimitUnitYet_defaultsToMph_usFirst() {
        // 0 m/s current speed with no limit at all is still a real reading (stopped at a light).
        val text = speedLineText(
            currentSpeedMetersPerSecond = 0f,
            speedLimitAlert = null,
            measurementSystem = MeasurementSystem.Auto,
            locale = Locale.US,
        )
        assertTrue(text!!.endsWith("mph"))
    }
}
