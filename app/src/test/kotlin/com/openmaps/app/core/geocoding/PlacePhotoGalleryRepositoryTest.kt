package com.openmaps.app.core.geocoding

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertEquals
import org.junit.Test

/** Pure matching/license tests: no network and no image download during unit tests. */
class PlacePhotoGalleryRepositoryTest {
    @Test fun airportPhotoCanMatchWithoutCityInFilename() {
        assertTrue(PlacePhotoGalleryRepository.matchesCommonsTitle(
            "William P. Hobby Airport", "File:Inside William P. Hobby Airport.jpg", "Houston",
        ))
    }

    @Test fun unrelatedAirportPhotoMustNotMatch() {
        assertFalse(PlacePhotoGalleryRepository.matchesCommonsTitle(
            "William P. Hobby Airport", "File:George Bush Intercontinental Airport.jpg", "Houston",
        ))
    }

    @Test fun sameNamedChainStoreInAnotherCityIsRejected() {
        assertFalse(PlacePhotoGalleryRepository.matchesCommonsTitle(
            "Starbucks", "File:Starbucks Seattle.jpg", "Houston",
        ))
        assertFalse(PlacePhotoGalleryRepository.matchesCommonsTitle(
            "Starbucks", "File:Starbucks store.jpg", null,
        ))
    }

    @Test fun coordinatesAloneDoNotProveAnImageBelongsToTheVenue() {
        assertFalse(PlacePhotoGalleryRepository.matchesCommonsTitle(
            "William P. Hobby Airport", "File:Houston skyline.jpg", "Houston",
        ))
    }

    @Test fun licenseFilterRejectsNonCommercialOrUnclearLicenses() {
        assertTrue(PlacePhotoGalleryRepository.allowedCommonsLicense("CC BY 4.0"))
        assertTrue(PlacePhotoGalleryRepository.allowedCommonsLicense("CC BY-SA 4.0"))
        assertTrue(PlacePhotoGalleryRepository.allowedCommonsLicense("CC0"))
        assertFalse(PlacePhotoGalleryRepository.allowedCommonsLicense("CC BY-NC 4.0"))
        assertFalse(PlacePhotoGalleryRepository.allowedCommonsLicense(""))
    }

    @Test fun localityFromAddressRequiresACityLikeSegment() {
        assertEquals("Houston", PlacePhotoGalleryRepository.localityFromAddress("123 Main St, Houston, TX"))
        assertEquals(null, PlacePhotoGalleryRepository.localityFromAddress("Houston"))
    }
}
