package com.openmaps.app.feature.map

import com.openmaps.app.core.geocoding.SearchResult
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MapOverlayVisibilityTest {
    private val airport = SearchResult(
        id = "iah",
        name = "IAH",
        displayName = "IAH",
        latitude = 29.99,
        longitude = -95.34,
    )

    @Test fun floatingMapActions_areAvailableOnHome() {
        assertTrue(shouldShowFloatingMapActions(MapUiState()))
    }

    @Test fun floatingMapActions_hideWhenDestinationCardIsOpen() {
        assertFalse(shouldShowFloatingMapActions(MapUiState(selectedDestination = airport)))
    }

    @Test fun floatingMapActions_hideWhenExploreSheetIsOpen() {
        assertFalse(shouldShowFloatingMapActions(MapUiState(isExploreOpen = true)))
    }

    @Test fun floatingMapActions_hideForSteps() {
        assertFalse(shouldShowFloatingMapActions(MapUiState(isShowingSteps = true)))
    }
}
