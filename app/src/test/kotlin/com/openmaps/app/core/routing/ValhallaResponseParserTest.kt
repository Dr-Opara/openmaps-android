package com.openmaps.app.core.routing

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ValhallaResponseParserTest {

    @Test
    fun parse_successfulResponse_mapsDistanceDurationGeometryAndBounds() {
        val body = """
            {
              "trip": {
                "status": 0,
                "status_message": "Found route between points",
                "legs": [
                  { "shape": "Sg@Rf@", "summary": {} }
                ],
                "summary": {
                  "time": 737.39,
                  "length": 8.001,
                  "min_lat": 40.711667, "min_lon": -74.013421,
                  "max_lat": 40.764243, "max_lon": -73.98508
                }
              }
            }
        """.trimIndent()

        val result = ValhallaResponseParser.parse(body)

        assertEquals(8001.0, result.distanceMeters, 1e-6)
        assertEquals(737.39, result.durationSeconds, 1e-6)
        assertEquals(2, result.geometry.size)
        assertEquals(0.00001, result.geometry[0].latitude, 1e-9)
        assertEquals(0.0, result.geometry[1].latitude, 1e-9)
        assertEquals(RouteBounds(south = 40.711667, west = -74.013421, north = 40.764243, east = -73.98508), result.bounds)
    }

    @Test
    fun parse_multipleLegs_concatenatesGeometry() {
        val body = """
            {
              "trip": {
                "status": 0,
                "legs": [
                  { "shape": "Sg@", "summary": {} },
                  { "shape": "Rf@", "summary": {} }
                ],
                "summary": { "time": 100.0, "length": 1.0 }
              }
            }
        """.trimIndent()

        val result = ValhallaResponseParser.parse(body)

        assertEquals(2, result.geometry.size)
    }

    @Test
    fun parse_missingBounds_returnsNullBounds() {
        val body = """
            {"trip": {"status": 0, "legs": [{"shape": "Sg@", "summary": {}}], "summary": {"time": 1.0, "length": 1.0}}}
        """.trimIndent()

        assertNull(ValhallaResponseParser.parse(body).bounds)
    }

    @Test
    fun parse_nonZeroStatus_throwsNoRouteFound() {
        val body = """
            {"trip": {"status": 1, "status_message": "no path", "legs": [], "summary": {"time": 0, "length": 0}}}
        """.trimIndent()

        assertThrows(RoutingException.NoRouteFound::class.java) { ValhallaResponseParser.parse(body) }
    }

    @Test
    fun parse_emptyLegs_throwsNoRouteFound() {
        val body = """{"trip": {"status": 0, "legs": [], "summary": {"time": 0, "length": 0}}}"""
        assertThrows(RoutingException.NoRouteFound::class.java) { ValhallaResponseParser.parse(body) }
    }

    @Test
    fun parse_malformedJson_throwsJsonException() {
        assertThrows(JSONException::class.java) { ValhallaResponseParser.parse("not json") }
    }

    @Test
    fun parse_missingTripKey_throwsJsonException() {
        assertThrows(JSONException::class.java) { ValhallaResponseParser.parse("""{"foo": 123}""") }
    }

    @Test
    fun parse_realCapturedResponse_parsesWithoutError() {
        // Captured from a live request to valhalla1.openstreetmap.de for
        // locations=[(40.7128,-74.0060), (40.7580,-73.9855)], costing=auto, units=kilometers.
        val shape = "wr{slAbn~clCbDaItGeQh@eA`@i@^Sd@QfDkA`@`CbCdP^rCLfAR|AbKfo@p@rEbA|FxCnQTvBHpBApB"
        val body = """
            {
              "trip": {
                "status": 0,
                "status_message": "Found route between points",
                "legs": [ { "shape": "$shape", "summary": {} } ],
                "summary": {
                  "time": 737.39, "length": 8.001,
                  "min_lat": 40.711667, "min_lon": -74.013421,
                  "max_lat": 40.764243, "max_lon": -73.98508
                }
              }
            }
        """.trimIndent()

        val result = ValhallaResponseParser.parse(body)

        assertTrue(result.geometry.isNotEmpty())
        assertEquals(40.712508, result.geometry.first().latitude, 1e-6)
        assertEquals(8001.0, result.distanceMeters, 1e-6)
    }
}
