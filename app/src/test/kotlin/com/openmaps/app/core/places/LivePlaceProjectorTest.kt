package com.openmaps.app.core.places

import com.openmaps.app.core.spatial.LocalizationQuality
import com.openmaps.app.core.spatial.SpatialPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePlaceProjectorTest {

    private fun pose(lat: Double = 0.0, lon: Double = 0.0, headingDegrees: Double = 0.0) = SpatialPose(
        latitude = lat,
        longitude = lon,
        altitudeMeters = null,
        headingDegrees = headingDegrees,
        horizontalAccuracyMeters = 2.0,
        verticalAccuracyMeters = null,
        headingAccuracyDegrees = 2.0,
        quality = LocalizationQuality.Good,
    )

    private fun place(id: String, lat: Double, lon: Double) =
        Place(id = id, name = id, category = PlaceCategory.Coffee, latitude = lat, longitude = lon)

    @Test
    fun project_nullPose_returnsEmpty() {
        val result = LivePlaceProjector.project(
            places = listOf(place("a", 0.001, 0.0)),
            pose = null,
            viewportWidthPx = 1000,
            viewportHeightPx = 2000,
            maxVisible = 3,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun project_placeAhead_isOnScreen() {
        // North of the user, device facing north: dead ahead.
        val result = LivePlaceProjector.project(
            places = listOf(place("ahead", 0.001, 0.0)),
            pose = pose(headingDegrees = 0.0),
            viewportWidthPx = 1000,
            viewportHeightPx = 2000,
            maxVisible = 3,
        )
        assertEquals(1, result.size)
        assertEquals("ahead", result.single().place.id)
    }

    @Test
    fun project_placeBehindDevice_isExcluded() {
        // South of the user, device facing north: directly behind.
        val result = LivePlaceProjector.project(
            places = listOf(place("behind", -0.001, 0.0)),
            pose = pose(headingDegrees = 0.0),
            viewportWidthPx = 1000,
            viewportHeightPx = 2000,
            maxVisible = 3,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun project_capsAtMaxVisible_nearestFirst() {
        val places = (1..10).map { i -> place("p$i", 0.0001 * i, 0.0) }
        val result = LivePlaceProjector.project(
            places = places,
            pose = pose(headingDegrees = 0.0),
            viewportWidthPx = 1000,
            viewportHeightPx = 2000,
            maxVisible = 3,
        )
        assertEquals(3, result.size)
        assertEquals(listOf("p1", "p2", "p3"), result.map { it.place.id })
    }

    @Test
    fun project_zeroMaxVisible_returnsEmpty() {
        val result = LivePlaceProjector.project(
            places = listOf(place("a", 0.001, 0.0)),
            pose = pose(),
            viewportWidthPx = 1000,
            viewportHeightPx = 2000,
            maxVisible = 0,
        )
        assertTrue(result.isEmpty())
    }
}
