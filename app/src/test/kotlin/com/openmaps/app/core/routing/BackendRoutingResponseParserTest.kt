package com.openmaps.app.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendRoutingResponseParserTest {

    @Test
    fun parsesTrafficAwareRoute() {
        val body = """
            {
              "provider": "here",
              "trafficAware": true,
              "route": {
                "geometry": [
                  {"latitude":29.70,"longitude":-95.80},
                  {"latitude":29.71,"longitude":-95.81}
                ],
                "distanceMeters": 2300.0,
                "durationSeconds": 240.0,
                "baseDurationSeconds": 180.0,
                "typicalDurationSeconds": 210.0,
                "trafficDelaySeconds": 60.0,
                "maneuvers": [
                  {
                    "instruction":"Turn right",
                    "verbalInstruction":"Turn right",
                    "type":"TurnRight",
                    "streetName":"Jordan Ranch Blvd",
                    "distanceMeters":300.0,
                    "beginShapeIndex":0,
                    "endShapeIndex":1
                  }
                ]
              }
            }
        """.trimIndent()

        val result = BackendRoutingResponseParser.parse(body)

        assertEquals(2300.0, result.distanceMeters, 0.0)
        assertEquals(240.0, result.durationSeconds, 0.0)
        assertEquals(180.0, result.baseDurationSeconds ?: 0.0, 0.0)
        assertEquals(60.0, result.trafficDelaySeconds ?: 0.0, 0.0)
        assertTrue(result.isTrafficAware)
        assertEquals(ManeuverType.TurnRight, result.maneuvers.single().type)
    }

    @Test
    fun parsesAlternativesAndExitGuidance() {
        val body = """
            {
              "provider": "here",
              "trafficAware": true,
              "route": {
                "id": "primary",
                "label": "via I-10",
                "geometry": [
                  {"latitude":29.70,"longitude":-95.80},
                  {"latitude":29.71,"longitude":-95.81}
                ],
                "distanceMeters": 2300.0,
                "durationSeconds": 240.0,
                "baseDurationSeconds": 180.0,
                "typicalDurationSeconds": 210.0,
                "trafficDelaySeconds": 60.0,
                "maneuvers": [
                  {
                    "instruction":"Take the exit",
                    "verbalInstruction":"Take the exit",
                    "type":"Ramp",
                    "streetName":"I-10",
                    "distanceMeters":300.0,
                    "beginShapeIndex":0,
                    "endShapeIndex":1,
                    "exitSign":"Exit 743B",
                    "signpost":"San Antonio"
                  }
                ]
              },
              "alternatives": [
                {
                  "id": "alternate",
                  "label": "via US-90",
                  "geometry": [
                    {"latitude":29.70,"longitude":-95.80},
                    {"latitude":29.72,"longitude":-95.82}
                  ],
                  "distanceMeters": 2600.0,
                  "durationSeconds": 260.0,
                  "baseDurationSeconds": 200.0,
                  "typicalDurationSeconds": 230.0,
                  "trafficDelaySeconds": 60.0,
                  "maneuvers": []
                }
              ]
            }
        """.trimIndent()

        val result = BackendRoutingResponseParser.parse(body)

        assertEquals("primary", result.id)
        assertEquals("via I-10", result.label)
        assertEquals("Exit 743B", result.maneuvers.single().exitSign)
        assertEquals("San Antonio", result.maneuvers.single().signpost)
        assertEquals(1, result.alternatives.size)
        assertEquals("via US-90", result.alternatives.single().label)
        assertEquals(260.0, result.alternatives.single().durationSeconds, 0.0)
    }
}
