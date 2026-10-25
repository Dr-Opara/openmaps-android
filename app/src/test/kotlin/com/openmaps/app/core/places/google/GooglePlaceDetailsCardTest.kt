package com.openmaps.app.core.places.google

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers only [significantNameOverlapRatio], the one piece of the business-identity check that
 * is a pure function. [placeLikelyMatches] itself also depends on distance and on Google's
 * abstract [com.google.android.libraries.places.api.model.Place], which this module has no
 * Robolectric/mocking setup to construct — that half needs a real device load to verify (see
 * docs/SETUP.md "Google Places UI Kit provisioning").
 */
class GooglePlaceDetailsCardTest {

    @Test
    fun exactNameIsFullOverlap() {
        assertEquals(1f, significantNameOverlapRatio("Chipotle Mexican Grill", "Chipotle Mexican Grill"))
    }

    @Test
    fun punctuationAndCaseDoNotPreventAMatch() {
        assertEquals(
            1f,
            significantNameOverlapRatio("Trader Joe's", "TRADER JOE'S"),
        )
    }

    @Test
    fun stopwordsDoNotCountAsOverlapOnTheirOwn() {
        // Only "the" and "cafe" are shared; "the" is a stopword, so the only significant overlap
        // is "cafe" out of 2 significant words on the shorter side -> 0.5, not a false full match.
        val ratio = significantNameOverlapRatio("The Daily Grind Cafe", "The Other Cafe")
        assertTrue("expected a partial, not full, match but got $ratio", ratio < 1f)
    }

    @Test
    fun differentChainBranchesAtSimilarNamesStillOverlapOnBrand() {
        // Same brand, different location qualifier — the brand name itself is real overlap.
        // (Distance is the other half of the real check in placeLikelyMatches; not exercised here.)
        val ratio = significantNameOverlapRatio("Starbucks - Main St", "Starbucks - 5th Ave")
        assertTrue("expected shared-brand overlap but got $ratio", ratio > 0f)
    }

    @Test
    fun unrelatedBusinessesAtTheSameAddressDoNotOverlap() {
        assertEquals(0f, significantNameOverlapRatio("Golden Dragon Restaurant", "SuperCuts Hair Salon"))
    }

    @Test
    fun blankGoogleNameNeverMatches() {
        assertEquals(0f, significantNameOverlapRatio("Any Business", ""))
    }
}
