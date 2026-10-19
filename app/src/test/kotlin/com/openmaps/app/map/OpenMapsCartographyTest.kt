package com.openmaps.app.map

import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OpenMapsCartographyTest {

    @Test
    fun navigationReducesPoiDensity() {
        assertTrue(OpenMapsCartography.NavigationDay.poiOpacity < OpenMapsCartography.BrowseDay.poiOpacity)
        assertTrue(OpenMapsCartography.NavigationNight.poiOpacity < OpenMapsCartography.BrowseNight.poiOpacity)
    }

    @Test
    fun dayAndNightPalettesAreDistinct() {
        assertNotEquals(OpenMapsCartography.BrowseDay.background, OpenMapsCartography.BrowseNight.background)
        assertNotEquals(OpenMapsCartography.BrowseDay.label, OpenMapsCartography.BrowseNight.label)
        assertNotEquals(OpenMapsCartography.NavigationDay.route, OpenMapsCartography.NavigationNight.route)
    }

    @Test
    fun routeRemainsHighContrastAcrossModes() {
        CartographyMode.entries.forEach { mode ->
            val palette = OpenMapsCartography.palette(mode)
            assertNotEquals(palette.route, palette.routeCasing)
            assertNotEquals(palette.route, palette.traveledRoute)
        }
    }
}
