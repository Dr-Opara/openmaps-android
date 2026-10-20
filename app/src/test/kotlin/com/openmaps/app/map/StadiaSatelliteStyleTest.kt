package com.openmaps.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StadiaSatelliteStyleTest {
    @Test fun preservesConfiguredApiKeyAndChangesStyleOnly() {
        val standard = MapStyleSource.Url(
            "https://tiles.stadiamaps.com/styles/alidade_smooth.json?api_key=test-key",
            "Stadia", false,
        )
        val satellite = StadiaSatelliteStyle.fromStandard(standard)!!
        assertEquals(
            "https://tiles.stadiamaps.com/styles/alidade_satellite.json?api_key=test-key",
            satellite.url,
        )
        assertTrue(satellite.attribution.contains("Stadia Maps"))
    }

    @Test fun unsupportedAndDeveloperProvidersDoNotExposeSatellite() {
        assertNull(StadiaSatelliteStyle.fromStandard(
            MapStyleSource.Url("https://tiles.openfreemap.org/styles/liberty", "Dev", true),
        ))
        assertNull(StadiaSatelliteStyle.fromStandard(
            MapStyleSource.InlineJson("{}", "Custom", false),
        ))
        assertNull(StadiaSatelliteStyle.fromStandard(
            MapStyleSource.Url("https://tiles.stadiamaps.com/styles/alidade_smooth.json", "Development", true),
        ))
    }
}
