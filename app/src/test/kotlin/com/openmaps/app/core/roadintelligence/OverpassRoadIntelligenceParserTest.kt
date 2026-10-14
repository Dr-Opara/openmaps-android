package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassRoadIntelligenceParserTest {

    // region Hazards

    @Test
    fun highwayConstruction_parsesAsConstructionHazard() {
        val body = """
            {"elements":[
              {"type":"way","id":1,"center":{"lat":40.1,"lon":-74.1},"tags":{"highway":"construction"}}
            ]}
        """.trimIndent()
        val hazards = OverpassRoadIntelligenceParser.parseHazards(body)
        assertEquals(1, hazards.size)
        assertEquals(RoadEventType.Construction, hazards.single().type)
        assertEquals("osm:way/1", hazards.single().id)
        assertEquals(40.1, hazards.single().latitude, 1e-9)
    }

    @Test
    fun constructionTagOnAnOtherwiseOpenRoad_alsoParsesAsConstruction() {
        val body = """
            {"elements":[
              {"type":"way","id":2,"center":{"lat":40.2,"lon":-74.2},
               "tags":{"highway":"residential","construction":"minor"}}
            ]}
        """.trimIndent()
        val hazards = OverpassRoadIntelligenceParser.parseHazards(body)
        assertEquals(RoadEventType.Construction, hazards.single().type)
    }

    @Test
    fun hazardTag_parsesAsOtherWithRawValueCarriedVerbatim() {
        val body = """
            {"elements":[
              {"type":"node","id":3,"lat":40.3,"lon":-74.3,"tags":{"hazard":"animal_crossing"}}
            ]}
        """.trimIndent()
        val hazards = OverpassRoadIntelligenceParser.parseHazards(body)
        assertEquals(RoadEventType.Other, hazards.single().type)
        assertEquals("animal_crossing", hazards.single().description)
    }

    @Test
    fun elementWithNeitherTag_isSkipped() {
        val body = """{"elements":[{"type":"way","id":4,"center":{"lat":1.0,"lon":1.0},"tags":{"highway":"primary"}}]}"""
        assertTrue(OverpassRoadIntelligenceParser.parseHazards(body).isEmpty())
    }

    @Test
    fun emptyElements_returnsEmptyList() {
        assertTrue(OverpassRoadIntelligenceParser.parseHazards("""{"elements":[]}""").isEmpty())
    }

    @Test
    fun missingElementsKey_returnsEmptyListRatherThanThrowing() {
        assertTrue(OverpassRoadIntelligenceParser.parseHazards("""{}""").isEmpty())
    }

    @Test
    fun elementWithNoResolvableCoordinates_isSkippedNotThrown() {
        val body = """{"elements":[{"type":"way","id":5,"tags":{"highway":"construction"}}]}"""
        assertTrue(OverpassRoadIntelligenceParser.parseHazards(body).isEmpty())
    }

    // endregion

    // region Speed limits

    @Test
    fun maxspeedPlainNumber_parsesAsKmh() {
        val body = """
            {"elements":[
              {"type":"way","id":10,"center":{"lat":0.0,"lon":0.0},"tags":{"highway":"primary","maxspeed":"50"}}
            ]}
        """.trimIndent()
        val info = OverpassRoadIntelligenceParser.parseNearestSpeedLimit(body, Coordinate(0.0, 0.0))
        assertEquals(50.0, info!!.value, 1e-9)
        assertEquals(SpeedUnit.Kmh, info.unit)
    }

    @Test
    fun maxspeedWithMphSuffix_parsesAsMph() {
        val body = """
            {"elements":[
              {"type":"way","id":11,"center":{"lat":0.0,"lon":0.0},"tags":{"highway":"primary","maxspeed":"45 mph"}}
            ]}
        """.trimIndent()
        val info = OverpassRoadIntelligenceParser.parseNearestSpeedLimit(body, Coordinate(0.0, 0.0))
        assertEquals(45.0, info!!.value, 1e-9)
        assertEquals(SpeedUnit.Mph, info.unit)
    }

    @Test
    fun maxspeedNonNumericValue_isNeverInvented() {
        val body = """
            {"elements":[
              {"type":"way","id":12,"center":{"lat":0.0,"lon":0.0},"tags":{"highway":"living_street","maxspeed":"walk"}}
            ]}
        """.trimIndent()
        assertNull(OverpassRoadIntelligenceParser.parseNearestSpeedLimit(body, Coordinate(0.0, 0.0)))
    }

    @Test
    fun wayWithNoMaxspeedTag_isIgnored() {
        val body = """{"elements":[{"type":"way","id":13,"center":{"lat":0.0,"lon":0.0},"tags":{"highway":"primary"}}]}"""
        assertNull(OverpassRoadIntelligenceParser.parseNearestSpeedLimit(body, Coordinate(0.0, 0.0)))
    }

    @Test
    fun multipleWays_picksTheNearestOne() {
        val body = """
            {"elements":[
              {"type":"way","id":20,"center":{"lat":0.01,"lon":0.0},"tags":{"highway":"primary","maxspeed":"65 mph"}},
              {"type":"way","id":21,"center":{"lat":0.0001,"lon":0.0},"tags":{"highway":"primary","maxspeed":"25 mph"}}
            ]}
        """.trimIndent()
        val info = OverpassRoadIntelligenceParser.parseNearestSpeedLimit(body, Coordinate(0.0, 0.0))
        assertEquals(25.0, info!!.value, 1e-9)
    }

    @Test
    fun emptySpeedLimitResponse_returnsNull() {
        assertNull(OverpassRoadIntelligenceParser.parseNearestSpeedLimit("""{"elements":[]}""", Coordinate(0.0, 0.0)))
    }

    // endregion
}
