package com.openmaps.app.core.scout

import com.openmaps.app.core.places.PlaceCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class ScoutIntentParserTest {
    private val parser = RuleBasedScoutIntentParser()

    @Test fun navigateTo_extractsDestination() {
        assertEquals(ScoutIntent.NavigateTo("NRG Stadium"), parser.parse("Navigate to NRG Stadium"))
    }

    @Test fun arrivalPlan_extractsDestination() {
        assertEquals(
            ScoutIntent.ArrivalPlan("George Bush Intercontinental Airport"),
            parser.parse("Plan my arrival to George Bush Intercontinental Airport"),
        )
    }

    @Test fun nearbyCategory_isTyped() {
        assertEquals(ScoutIntent.FindNearby(PlaceCategory.Coffee), parser.parse("Find coffee nearby"))
    }

    @Test fun parking_isSpecialArrivalCapability() {
        assertEquals(ScoutIntent.FindParking, parser.parse("Where can I park?"))
    }

    @Test fun unknown_neverPretendsToUnderstand() {
        assertEquals(ScoutIntent.Unknown, parser.parse("make this drive magical"))
    }
}
