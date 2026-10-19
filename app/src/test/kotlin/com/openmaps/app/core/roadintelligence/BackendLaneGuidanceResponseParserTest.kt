package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.ManeuverType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendLaneGuidanceResponseParserTest {

    @Test
    fun parsesProviderBackedRecommendedLanes() {
        val body = """
            {
              "provider":"osm+mapbox",
              "capability":"available",
              "guidance":{
                "lanes":[
                  {"laneNumber":1,"directions":["Straight"],"recommended":false},
                  {"laneNumber":2,"directions":["Right"],"recommended":true}
                ],
                "recommendedLaneIndexes":[1],
                "maneuverRelation":"TurnRight",
                "source":"osm+mapbox","confidence":"high"
              }
            }
        """.trimIndent()

        val guidance = BackendLaneGuidanceResponseParser.parse(body)!!

        assertEquals(ManeuverType.TurnRight, guidance.maneuverRelation)
        assertEquals(listOf(1), guidance.recommendedLaneIndexes)
        assertEquals(LaneDirection.Right, guidance.lanes[1].directions.single())
        assertTrue(guidance.lanes[1].isRecommended)
        assertEquals("osm+mapbox", guidance.source)
        assertEquals(LaneGuidanceConfidence.High, guidance.confidence)
    }

    @Test
    fun unavailableCapabilityStaysNull() {
        assertNull(
            BackendLaneGuidanceResponseParser.parse(
                """{"provider":"osm+mapbox","capability":"no_trustworthy_lane_guidance","guidance":null}""",
            ),
        )
    }
}
