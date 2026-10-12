package com.openmaps.app.core.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaceCategoryMapperTest {

    @Test
    fun categoryFor_mapsKnownTagsToCategories() {
        assertEquals(PlaceCategory.Restaurant, PlaceCategoryMapper.categoryFor(mapOf("amenity" to "restaurant")))
        assertEquals(PlaceCategory.Restaurant, PlaceCategoryMapper.categoryFor(mapOf("amenity" to "fast_food")))
        assertEquals(PlaceCategory.Coffee, PlaceCategoryMapper.categoryFor(mapOf("amenity" to "cafe")))
        assertEquals(PlaceCategory.Hotel, PlaceCategoryMapper.categoryFor(mapOf("tourism" to "hotel")))
        assertEquals(PlaceCategory.Grocery, PlaceCategoryMapper.categoryFor(mapOf("shop" to "supermarket")))
        assertEquals(PlaceCategory.Pharmacy, PlaceCategoryMapper.categoryFor(mapOf("amenity" to "pharmacy")))
        assertEquals(PlaceCategory.Parking, PlaceCategoryMapper.categoryFor(mapOf("amenity" to "parking")))
        assertEquals(PlaceCategory.Gas, PlaceCategoryMapper.categoryFor(mapOf("amenity" to "fuel")))
        assertEquals(PlaceCategory.EvCharging, PlaceCategoryMapper.categoryFor(mapOf("amenity" to "charging_station")))
    }

    @Test
    fun categoryFor_unsupportedTags_returnsNull() {
        assertNull(PlaceCategoryMapper.categoryFor(mapOf("amenity" to "bench")))
        assertNull(PlaceCategoryMapper.categoryFor(emptyMap()))
    }

    @Test
    fun categoryFor_extraTagsDoNotAffectMatch() {
        val tags = mapOf("amenity" to "cafe", "name" to "Blue Bottle", "opening_hours" to "Mo-Su 07:00-19:00")
        assertEquals(PlaceCategory.Coffee, PlaceCategoryMapper.categoryFor(tags))
    }

    @Test
    fun overpassSelectors_returnsOnlyRequestedCategories() {
        val selectors = PlaceCategoryMapper.overpassSelectors(setOf(PlaceCategory.Coffee))
        assertEquals(listOf("[\"amenity\"=\"cafe\"]"), selectors)
    }

    @Test
    fun overpassSelectors_multipleTagsPerCategory_allIncluded() {
        val selectors = PlaceCategoryMapper.overpassSelectors(setOf(PlaceCategory.Restaurant))
        assertEquals(2, selectors.size)
        assertTrue(selectors.contains("[\"amenity\"=\"restaurant\"]"))
        assertTrue(selectors.contains("[\"amenity\"=\"fast_food\"]"))
    }

    @Test
    fun overpassSelectors_emptyCategories_returnsEmpty() {
        assertTrue(PlaceCategoryMapper.overpassSelectors(emptySet()).isEmpty())
    }

    @Test
    fun supportedCategories_coversEveryDeclaredCategory() {
        assertEquals(PlaceCategory.entries.toSet(), PlaceCategoryMapper.supportedCategories)
    }
}
