package com.openmaps.app.core.environment

import com.openmaps.app.core.routing.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoadClassCacheTest {

    private val center = Coordinate(40.0, -74.0)

    @Test
    fun missBeforePut() {
        assertNull(RoadClassCache().get(center))
    }

    @Test
    fun hitWithinTtl_missAfterExpiry() {
        var now = 0L
        val cache = RoadClassCache(now = { now })
        cache.put(center, RoadClass.Residential)

        now = 30 * 60 * 1000L - 1
        assertEquals(RoadClass.Residential, cache.get(center))

        now = 30 * 60 * 1000L
        assertNull(cache.get(center))
    }

    @Test
    fun nearbyCenterWithinBucket_isHit_farCenterIsMiss() {
        val cache = RoadClassCache()
        cache.put(center, RoadClass.Motorway)
        assertEquals(RoadClass.Motorway, cache.get(Coordinate(40.00001, -74.00001)))
        assertNull(cache.get(Coordinate(41.0, -74.0)))
    }

    @Test
    fun clear_removesEveryEntry() {
        val cache = RoadClassCache()
        cache.put(center, RoadClass.Tertiary)
        cache.clear()
        assertNull(cache.get(center))
    }
}
