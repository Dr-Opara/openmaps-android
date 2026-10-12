package com.openmaps.app.core.places

import com.openmaps.app.core.routing.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PlaceCacheTest {

    private val center = Coordinate(40.0, -74.0)
    private val categories = setOf(PlaceCategory.Coffee)
    private val places = listOf(place("1", "Cafe", PlaceCategory.Coffee, 40.0, -74.0))

    @Test
    fun get_beforePut_isMiss() {
        val cache = PlaceCache()
        assertNull(cache.get(center, 1_000.0, categories))
    }

    @Test
    fun put_thenGet_withinTtl_isHit() {
        var now = 0L
        val cache = PlaceCache(ttlMillis = 1_000L, now = { now })
        cache.put(center, 1_000.0, categories, places)
        now = 999L
        assertEquals(places, cache.get(center, 1_000.0, categories))
    }

    @Test
    fun get_afterTtlExpires_isMiss() {
        var now = 0L
        val cache = PlaceCache(ttlMillis = 1_000L, now = { now })
        cache.put(center, 1_000.0, categories, places)
        now = 1_000L
        assertNull(cache.get(center, 1_000.0, categories))
    }

    @Test
    fun get_differentCategories_isMiss() {
        val cache = PlaceCache()
        cache.put(center, 1_000.0, categories, places)
        assertNull(cache.get(center, 1_000.0, setOf(PlaceCategory.Gas)))
    }

    @Test
    fun get_nearbyCenterWithinBucket_isHit() {
        val cache = PlaceCache(bucketDegrees = 0.01)
        cache.put(center, 1_000.0, categories, places)
        // A few meters away: same bucket.
        assertEquals(places, cache.get(Coordinate(40.00001, -74.00001), 1_000.0, categories))
    }

    @Test
    fun get_farCenter_isMiss() {
        val cache = PlaceCache(bucketDegrees = 0.005)
        cache.put(center, 1_000.0, categories, places)
        assertNull(cache.get(Coordinate(41.0, -74.0), 1_000.0, categories))
    }

    @Test
    fun clear_removesEveryEntry() {
        val cache = PlaceCache()
        cache.put(center, 1_000.0, categories, places)
        cache.clear()
        assertNull(cache.get(center, 1_000.0, categories))
    }
}

private fun place(id: String, name: String, category: PlaceCategory, lat: Double, lon: Double) =
    Place(id = id, name = name, category = category, latitude = lat, longitude = lon)
