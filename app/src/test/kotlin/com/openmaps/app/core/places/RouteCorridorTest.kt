package com.openmaps.app.core.places

import com.openmaps.app.core.routing.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteCorridorTest {

    /** A straight ~1.1 km east-running route near the equator, so 0.01 deg lon ~= 1.1 km. */
    private val geometry = listOf(
        Coordinate(0.0, 0.0),
        Coordinate(0.0, 0.01),
        Coordinate(0.0, 0.02),
    )

    @Test
    fun nearestSegment_pointOnLine_zeroDistance() {
        val nearest = RouteCorridor.nearestSegment(Coordinate(0.0, 0.005), geometry)!!
        assertEquals(0, nearest.segmentIndex)
        assertTrue(nearest.distanceMeters < 1.0)
    }

    @Test
    fun nearestSegment_pointOffLine_measuresLateralDistance() {
        // ~0.001 deg north of the line is roughly 111 m.
        val nearest = RouteCorridor.nearestSegment(Coordinate(0.001, 0.005), geometry)!!
        assertEquals(0, nearest.segmentIndex)
        assertTrue(nearest.distanceMeters in 90.0..130.0)
    }

    @Test
    fun nearestSegment_tooFewPoints_returnsNull() {
        assertNull(RouteCorridor.nearestSegment(Coordinate(0.0, 0.0), listOf(Coordinate(0.0, 0.0))))
    }

    @Test
    fun filterToCorridor_dropsPlacesBeyondCorridor_keepsThoseInside() {
        val near = place("near", "Near", PlaceCategory.Gas, 0.0001, 0.005)
        val far = place("far", "Far", PlaceCategory.Gas, 1.0, 1.0)
        val result = RouteCorridor.filterToCorridor(listOf(near, far), geometry, corridorMeters = 50.0)
        assertEquals(listOf("near"), result.map { it.id })
    }

    @Test
    fun filterToCorridor_stampsRouteDetourMeters() {
        val p = place("p", "P", PlaceCategory.Gas, 0.001, 0.005)
        val result = RouteCorridor.filterToCorridor(listOf(p), geometry, corridorMeters = 200.0)
        assertEquals(1, result.size)
        assertTrue(result.single().routeDetourMeters!! > 0.0)
    }

    @Test
    fun isAhead_segmentAtOrAfterSnapped_isAhead() {
        val p = place("p", "P", PlaceCategory.Gas, 0.0, 0.015) // nearest to segment 1
        assertTrue(RouteCorridor.isAhead(p, geometry, snappedShapeIndex = 1))
        assertTrue(RouteCorridor.isAhead(p, geometry, snappedShapeIndex = 0))
    }

    @Test
    fun isAhead_segmentBeforeSnapped_isBehind() {
        val p = place("p", "P", PlaceCategory.Gas, 0.0, 0.002) // nearest to segment 0
        assertFalseIsAhead(p, snappedShapeIndex = 1)
    }

    private fun assertFalseIsAhead(place: Place, snappedShapeIndex: Int) {
        assertEquals(false, RouteCorridor.isAhead(place, geometry, snappedShapeIndex))
    }

    @Test
    fun boundingQuery_coversEveryPointPlusCorridorMargin() {
        val query = RouteCorridor.boundingQuery(geometry, corridorMeters = 100.0, maxRadiusMeters = 10_000.0)
        for (point in geometry) {
            assertTrue(RouteCorridor.metersBetween(query.center, point) <= query.radiusMeters)
        }
    }

    @Test
    fun boundingQuery_radiusNeverExceedsCap() {
        val longGeometry = listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 10.0))
        val query = RouteCorridor.boundingQuery(longGeometry, corridorMeters = 100.0, maxRadiusMeters = 5_000.0)
        assertEquals(5_000.0, query.radiusMeters, 0.001)
    }
}

private fun place(id: String, name: String, category: PlaceCategory, lat: Double, lon: Double) =
    Place(id = id, name = name, category = category, latitude = lat, longitude = lon)
