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
    fun parse_maneuvers_mapsFieldsAndType() {
        val body = """
            {
              "trip": {
                "status": 0,
                "legs": [
                  {
                    "shape": "Sg@Rf@",
                    "summary": {},
                    "maneuvers": [
                      {
                        "type": 3,
                        "instruction": "Drive northeast on Broadway.",
                        "verbal_pre_transition_instruction": "Drive northeast",
                        "street_names": ["Broadway"],
                        "length": 0.5,
                        "begin_shape_index": 0,
                        "end_shape_index": 0
                      },
                      {
                        "type": 15,
                        "instruction": "Turn left onto Reade St.",
                        "length": 0.25,
                        "begin_shape_index": 1,
                        "end_shape_index": 1
                      }
                    ]
                  }
                ],
                "summary": { "time": 60.0, "length": 1.0 }
              }
            }
        """.trimIndent()

        val result = ValhallaResponseParser.parse(body)

        assertEquals(2, result.maneuvers.size)
        val first = result.maneuvers[0]
        assertEquals(ManeuverType.Start, first.type)
        assertEquals("Drive northeast on Broadway.", first.instruction)
        assertEquals("Drive northeast", first.verbalInstruction)
        assertEquals("Broadway", first.streetName)
        assertEquals(500.0, first.distanceMeters, 1e-6)
        assertEquals(0, first.beginShapeIndex)
        assertEquals(ManeuverType.TurnLeft, result.maneuvers[1].type)
        assertNull("absent verbal instruction must be null, not empty string", result.maneuvers[1].verbalInstruction)
        assertNull("absent street_names must be null", result.maneuvers[1].streetName)
    }

    @Test
    fun parse_multipleLegs_offsetsManeuverIndexesByPriorLegPointCount() {
        // Leg 1's shape decodes to 2 points, so leg 2's begin_shape_index of 0 must become 2 once
        // the geometry is concatenated. Without the offset, every maneuver after the first leg would
        // point at the wrong place on the route (and index past the end of the geometry).
        val legOne = """
            {
              "trip": {
                "status": 0,
                "legs": [
                  {
                    "shape": "Sg@Rf@",
                    "summary": {},
                    "maneuvers": [
                      { "type": 2, "instruction": "Start.", "length": 0.0, "begin_shape_index": 0, "end_shape_index": 1 }
                    ]
                  },
                  {
                    "shape": "Rg@Qf@",
                    "summary": {},
                    "maneuvers": [
                      { "type": 15, "instruction": "Turn left.", "length": 0.0, "begin_shape_index": 0, "end_shape_index": 1 }
                    ]
                  }
                ],
                "summary": { "time": 10.0, "length": 1.0 }
              }
            }
        """.trimIndent()

        val result = ValhallaResponseParser.parse(legOne)

        assertEquals(4, result.geometry.size)
        assertEquals(0, result.maneuvers[0].beginShapeIndex)
        assertEquals(2, result.maneuvers[1].beginShapeIndex)
        assertEquals(3, result.maneuvers[1].endShapeIndex)
        // Every maneuver must still address a real point in the concatenated geometry.
        result.maneuvers.forEach { maneuver ->
            assertTrue(
                "maneuver indexes ${maneuver.beginShapeIndex}..${maneuver.endShapeIndex} out of range",
                maneuver.endShapeIndex in 0 until result.geometry.size,
            )
        }
    }

    @Test
    fun parse_legWithoutManeuvers_stillProducesARoute() {
        // Maneuvers are additive: a route with none is still a valid Phase 3 route.
        val body = """
            {"trip": {"status": 0, "legs": [{"shape": "Sg@", "summary": {}}], "summary": {"time": 1.0, "length": 1.0}}}
        """.trimIndent()

        assertTrue(ValhallaResponseParser.parse(body).maneuvers.isEmpty())
    }

    @Test
    fun maneuverTypeMapping_coversRepresentativeValhallaEnumValues() {
        // Spot-checks against DirectionsLeg_Maneuver_Type (see the parser's mapping table).
        val cases = mapOf(
            2 to ManeuverType.Start,
            4 to ManeuverType.Destination,
            7 to ManeuverType.Continue,
            10 to ManeuverType.TurnRight,
            11 to ManeuverType.SharpRight,
            12 to ManeuverType.UTurn,
            15 to ManeuverType.TurnLeft,
            16 to ManeuverType.SlightLeft,
            18 to ManeuverType.Ramp,
            26 to ManeuverType.Roundabout,
            37 to ManeuverType.Merge,
            45 to ManeuverType.Destination,
            99 to ManeuverType.Unknown,
        )
        val body = buildString {
            append("""{"trip":{"status":0,"legs":[{"shape":"Sg@Rf@","summary":{},"maneuvers":[""")
            append(
                cases.entries.joinToString(",") { (code, _) ->
                    """{"type":$code,"instruction":"x","length":0.0,"begin_shape_index":0,"end_shape_index":0}"""
                },
            )
            append("""]}],"summary":{"time":1.0,"length":1.0}}}""")
        }

        val result = ValhallaResponseParser.parse(body)

        assertEquals(cases.size, result.maneuvers.size)
        cases.entries.forEachIndexed { index, (code, expected) ->
            assertEquals("type code $code", expected, result.maneuvers[index].type)
        }
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
