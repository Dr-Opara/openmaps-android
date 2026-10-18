package com.openmaps.app.core.localization

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class NavigationUnitsTest {

    @Test
    fun auto_usesUsImperialInUnitedStates() {
        assertEquals(
            "328 ft",
            NavigationUnits.formatDistance(100.0, MeasurementSystem.Auto, Locale.US),
        )
    }

    @Test
    fun auto_usesMetricInFrance() {
        assertEquals(
            "1,5 km",
            NavigationUnits.formatDistance(1500.0, MeasurementSystem.Auto, Locale.FRANCE),
        )
    }

    @Test
    fun auto_usesYardsInUnitedKingdom() {
        assertEquals(
            "109 yd",
            NavigationUnits.formatDistance(100.0, MeasurementSystem.Auto, Locale.UK),
        )
    }

    @Test
    fun explicitMetric_overridesUsLocale() {
        assertEquals(
            "1.5 km",
            NavigationUnits.formatDistance(1500.0, MeasurementSystem.Metric, Locale.US),
        )
    }

    @Test
    fun speed_convertsToPreferredUnits() {
        assertEquals(
            "100 km/h",
            NavigationUnits.formatPostedSpeed(62.1371, sourceIsMph = true, MeasurementSystem.Metric, Locale.US),
        )
        assertEquals(
            "62 mph",
            NavigationUnits.formatPostedSpeed(100.0, sourceIsMph = false, MeasurementSystem.Imperial, Locale.US),
        )
    }

    @Test
    fun arabicDuration_usesLocalizedDigitsAndUnits() {
        val value = NavigationUnits.formatDuration(3900.0, Locale.forLanguageTag("ar"))
        assertEquals("١ س ٥ د", value)
    }

    @Test
    fun duration_usesLocaleNumberFormatting() {
        assertEquals("1 h 5 min", NavigationUnits.formatDuration(3900.0, Locale.US))
    }
}
