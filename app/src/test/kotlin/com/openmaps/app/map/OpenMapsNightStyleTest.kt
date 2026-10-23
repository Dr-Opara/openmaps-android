package com.openmaps.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMapsNightStyleTest {
    private val day = MapStyleSource.Url(
        url = "https://tiles.stadiamaps.com/styles/alidade_smooth.json?api_key=EXAMPLE",
        name = "Stadia Maps", isDevelopmentOnly = false,
    )
    @Test fun dayToNightRetainsKeyAndProviderAttribution() {
        val dark = OpenMapsNightStyle.resolve(day, night = true) as MapStyleSource.Url
        assertEquals("https://tiles.stadiamaps.com/styles/alidade_smooth_dark.json?api_key=EXAMPLE", dark.url)
        assertEquals(day.attribution, dark.attribution)
        assertFalse(dark.isDevelopmentOnly)
    }
    @Test fun nightBackToDayRestoresBaseStyle() {
        val night = OpenMapsNightStyle.resolve(day, night = true)
        val roundTrip = OpenMapsNightStyle.resolve(night, night = false) as MapStyleSource.Url
        assertEquals(day.url, roundTrip.url)
    }
    @Test fun unknownProviderDoesNotGetAnInventedEndpoint() {
        val custom = MapStyleSource.Url("https://tiles.openfreemap.org/styles/liberty", "OpenFreeMap", true)
        assertFalse(OpenMapsNightStyle.isSupported(custom))
        assertEquals(custom, OpenMapsNightStyle.resolve(custom, true))
    }
    @Test fun stadiaSmoothIsSupported() {
        assertTrue(OpenMapsNightStyle.isSupported(day))
    }
}
