package com.openmaps.app.core.environment

import com.openmaps.app.core.routing.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassRoadClassParserTest {

    @Test
    fun motorwayTag_parsesAsMotorway() {
        val body = """
            {"elements":[
              {"type":"way","id":1,"center":{"lat":0.0,"lon":0.0},"tags":{"highway":"motorway"}}
            ]}
        """.trimIndent()
        assertEquals(RoadClass.Motorway, OverpassRoadClassParser.parseNearestRoadClass(body, Coordinate(0.0, 0.0)))
    }

    @Test
    fun everyDocumentedHighwayValue_mapsToASensibleClass() {
        val mapping = mapOf(
            "motorway" to RoadClass.Motorway,
            "motorway_link" to RoadClass.MotorwayLink,
            "trunk" to RoadClass.Trunk,
            "trunk_link" to RoadClass.TrunkLink,
            "primary" to RoadClass.Primary,
            "secondary" to RoadClass.Secondary,
            "tertiary" to RoadClass.Tertiary,
            "residential" to RoadClass.Residential,
            "living_street" to RoadClass.Residential,
            "service" to RoadClass.Service,
            "unclassified" to RoadClass.Unclassified,
            "footway" to RoadClass.Other,
        )
        mapping.forEach { (tag, expected) ->
            val body = """
                {"elements":[{"type":"way","id":1,"center":{"lat":0.0,"lon":0.0},"tags":{"highway":"$tag"}}]}
            """.trimIndent()
            assertEquals(tag, expected, OverpassRoadClassParser.parseNearestRoadClass(body, Coordinate(0.0, 0.0)))
        }
    }

    @Test
    fun multipleWays_picksTheNearestOne() {
        val body = """
            {"elements":[
              {"type":"way","id":1,"center":{"lat":0.01,"lon":0.0},"tags":{"highway":"motorway"}},
              {"type":"way","id":2,"center":{"lat":0.0001,"lon":0.0},"tags":{"highway":"residential"}}
            ]}
        """.trimIndent()
        assertEquals(RoadClass.Residential, OverpassRoadClassParser.parseNearestRoadClass(body, Coordinate(0.0, 0.0)))
    }

    @Test
    fun elementWithNoHighwayTag_isSkipped() {
        val body = """{"elements":[{"type":"way","id":1,"center":{"lat":0.0,"lon":0.0},"tags":{"name":"Foo"}}]}"""
        assertNull(OverpassRoadClassParser.parseNearestRoadClass(body, Coordinate(0.0, 0.0)))
    }

    @Test
    fun emptyElements_returnsNull() {
        assertNull(OverpassRoadClassParser.parseNearestRoadClass("""{"elements":[]}""", Coordinate(0.0, 0.0)))
    }

    @Test
    fun missingElementsKey_returnsNullRatherThanThrowing() {
        assertNull(OverpassRoadClassParser.parseNearestRoadClass("""{}""", Coordinate(0.0, 0.0)))
    }

    @Test
    fun elementWithNoResolvableCoordinates_isSkippedNotThrown() {
        val body = """{"elements":[{"type":"way","id":1,"tags":{"highway":"motorway"}}]}"""
        assertNull(OverpassRoadClassParser.parseNearestRoadClass(body, Coordinate(0.0, 0.0)))
    }

    @Test
    fun isControlledAccess_isTrueOnlyForMotorwayAndTrunkFamilies() {
        assertTrue(RoadClass.Motorway.isControlledAccess)
        assertTrue(RoadClass.MotorwayLink.isControlledAccess)
        assertTrue(RoadClass.Trunk.isControlledAccess)
        assertTrue(RoadClass.TrunkLink.isControlledAccess)
        assertEquals(false, RoadClass.Primary.isControlledAccess)
        assertEquals(false, RoadClass.Residential.isControlledAccess)
    }
}
