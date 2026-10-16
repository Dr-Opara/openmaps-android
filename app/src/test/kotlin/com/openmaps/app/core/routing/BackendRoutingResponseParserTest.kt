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
}
