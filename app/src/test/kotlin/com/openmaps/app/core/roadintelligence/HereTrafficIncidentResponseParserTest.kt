package com.openmaps.app.core.roadintelligence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HereTrafficIncidentResponseParserTest {

    @Test
    fun parsesLiveClosure() {
        val body = """
            {
              "provider": "here",
              "incidents": [
                {
                  "id": "abc",
                  "type": "Closure",
                  "latitude": 29.75,
                  "longitude": -95.75,
                  "severity": "Severe",
                  "title": "Road closed",
                  "description": "Crash blocking all lanes",
                  "startTimeMillis": 1000,
                  "endTimeMillis": 2000,
                  "metadata": {
                    "source": "HERE Traffic",
                    "roadClosed": "true"
                  }
                }
              ]
            }
        """.trimIndent()

        val event = HereTrafficIncidentResponseParser.parse(body).single()

        assertEquals(RoadEventType.Closure, event.type)
        assertEquals(RoadEventSeverity.Severe, event.severity)
        assertEquals("HERE Traffic", event.metadata["source"])
        assertTrue(event.description!!.contains("Crash"))
    }
}
