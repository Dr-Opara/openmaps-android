package com.openmaps.app.core.routing

import org.junit.Assert.assertEquals
import org.junit.Test

class PolylineDecoderTest {

    @Test
    fun decode_singlePoint_matchesHandEncodedValue() {
        // "S" encodes delta-lat=10, "g@" encodes delta-lon=20 (verified by hand against the
        // standard varint/zigzag polyline algorithm), so at precision 6 this is (0.00001, 0.00002).
        val points = PolylineDecoder.decode("Sg@", precision = 6)
        assertEquals(1, points.size)
        assertEquals(0.00001, points[0].latitude, 1e-9)
        assertEquals(0.00002, points[0].longitude, 1e-9)
    }

    @Test
    fun decode_realValhallaShape_firstPointMatchesRequestedOrigin() {
        // Captured from a live Valhalla response for locations=[(40.7128,-74.0060), (40.7580,-73.9855)].
        val shape = "wr{slAbn~clCbDaItGeQh@eA`@i@^Sd@QfDkA`@`CbCdP^rCLfAR|AbKfo@p@rEbA|FxCnQTvBHpBApB"
        val points = PolylineDecoder.decode(shape, precision = 6)
        assertEquals(40.712508, points.first().latitude, 1e-6)
        assertEquals(-74.006258, points.first().longitude, 1e-6)
    }

    @Test
    fun decode_emptyString_returnsEmptyList() {
        assertEquals(emptyList<Coordinate>(), PolylineDecoder.decode(""))
    }

    @Test
    fun decode_multiplePoints_accumulatesDeltas() {
        // Two points: (0.00001, 0.00002) then back to origin (delta -10, -20).
        val points = PolylineDecoder.decode("Sg@" + "Rf@" /* -10, -20 */, precision = 6)
        assertEquals(2, points.size)
        assertEquals(0.00001, points[0].latitude, 1e-9)
        assertEquals(0.0, points[1].latitude, 1e-9)
        assertEquals(0.0, points[1].longitude, 1e-9)
    }
}
