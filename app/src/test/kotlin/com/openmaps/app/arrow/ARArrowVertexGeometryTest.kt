package com.openmaps.app.arrow

import com.openmaps.app.core.arrow.ARArrowNode
import com.openmaps.app.core.arrow.ScreenArrow
import com.openmaps.app.core.routing.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Test

class ARArrowVertexGeometryTest {
    private fun arrow(x: Float, y: Float, r: Float = 10f) = ScreenArrow(
        node = ARArrowNode(
            id = 1, worldPosition = Coordinate(0.0, 0.0), headingRadians = 0.0,
            distanceAheadMeters = 10.0, visibility = true, scale = r,
            isApproachingManeuver = false,
        ),
        x = x, y = y, radiusPx = r, onScreen = true,
    )

    @Test fun portraitViewportMapsEdgesToClipSpace() {
        assertEquals(-1f, ARArrowVertexGeometry.pixelToNdcX(0f, 1080), 0.00001f)
        assertEquals(1f, ARArrowVertexGeometry.pixelToNdcX(1080f, 1080), 0.00001f)
        assertEquals(0f, ARArrowVertexGeometry.pixelToNdcX(540f, 1080), 0.00001f)
        assertEquals(1f, ARArrowVertexGeometry.pixelToNdcY(0f, 2400), 0.00001f)
        assertEquals(-1f, ARArrowVertexGeometry.pixelToNdcY(2400f, 2400), 0.00001f)
        assertEquals(0f, ARArrowVertexGeometry.pixelToNdcY(1200f, 2400), 0.00001f)
    }

    @Test fun arrowPacksExactlyTwoFloatsPerVertex() {
        val buffer = FloatArray(ARArrowVertexGeometry.MAX_FLOATS)
        val written = ARArrowVertexGeometry.write(listOf(arrow(540f, 1200f)), 1080, 2400, buffer)
        assertEquals(ARArrowVertexGeometry.FLOATS_PER_ARROW, written)
        // Every vertex lies around the correctly projected screen center, not at raw pixels.
        for (i in 0 until written step 2) {
            org.junit.Assert.assertTrue(buffer[i] in -1f..1f)
            org.junit.Assert.assertTrue(buffer[i + 1] in -1f..1f)
        }
        // Two thick diagonal arms, not a filled three-vertex triangle.
        assertEquals(12, ARArrowVertexGeometry.VERTICES_PER_ARROW)
    }

    @Test fun invalidViewportAndOffscreenArrowDoNotDraw() {
        val buffer = FloatArray(ARArrowVertexGeometry.MAX_FLOATS)
        assertEquals(0, ARArrowVertexGeometry.write(listOf(arrow(540f, 1200f)), 0, 2400, buffer))
        assertEquals(0, ARArrowVertexGeometry.write(listOf(arrow(540f, 1200f)), 1080, -1, buffer))
        assertEquals(0, ARArrowVertexGeometry.write(listOf(arrow(Float.NaN, 1200f)), 1080, 2400, buffer))
    }

    @Test fun bufferLimitsChevronsToHardCap() {
        val buffer = FloatArray(ARArrowVertexGeometry.MAX_FLOATS)
        assertEquals(ARArrowVertexGeometry.MAX_FLOATS,
            ARArrowVertexGeometry.write(List(40) { arrow(540f, 1200f) }, 1080, 2400, buffer))
    }
}
