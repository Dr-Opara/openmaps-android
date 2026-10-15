package com.openmaps.app.core.places

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OverpassResponseParserTest {

    @Test
    fun parse_node_withLatLonDirect() {
        val body = """
            {"elements":[
              {"type":"node","id":1,"lat":37.7749,"lon":-122.4194,
               "tags":{"amenity":"cafe","name":"Blue Bottle Coffee"}}
            ]}
        """.trimIndent()
        val places = OverpassResponseParser.parse(body)
        assertEquals(1, places.size)
        val place = places.single()
        assertEquals("node/1", place.id)
        assertEquals("Blue Bottle Coffee", place.name)
        assertEquals(PlaceCategory.Coffee, place.category)
        assertEquals(37.7749, place.latitude, 0.0001)
        assertEquals(-122.4194, place.longitude, 0.0001)
    }

    @Test
    fun parse_way_usesCenterForCoordinates() {
        val body = """
            {"elements":[
              {"type":"way","id":2,"center":{"lat":40.0,"lon":-74.0},
               "tags":{"amenity":"parking","parking":"surface","access":"yes","fee":"no"}}
            ]}
        """.trimIndent()
        val places = OverpassResponseParser.parse(body)
        val place = places.single()
        assertEquals("way/2", place.id)
        assertEquals(PlaceCategory.Parking, place.category)
        assertEquals(40.0, place.latitude, 0.0001)
        assertEquals(-74.0, place.longitude, 0.0001)
        assertEquals("surface", place.metadata.parkingType)
        assertEquals(true, place.metadata.isPublicAccess)
        assertEquals(false, place.metadata.feeApplies)
    }

    @Test
    fun parse_missingName_fallsBackToCategoryDefault_neverBlank() {
        val body = """
            {"elements":[
              {"type":"node","id":3,"lat":1.0,"lon":2.0,"tags":{"amenity":"parking"}}
            ]}
        """.trimIndent()
        val place = OverpassResponseParser.parse(body).single()
        assertEquals("Parking", place.name)
    }

    @Test
    fun parse_unsupportedCategory_isSkipped_notFatal() {
        val body = """
            {"elements":[
              {"type":"node","id":4,"lat":1.0,"lon":2.0,"tags":{"amenity":"bench"}},
              {"type":"node","id":5,"lat":1.0,"lon":2.0,"tags":{"amenity":"cafe","name":"Good Cafe"}}
            ]}
        """.trimIndent()
        val places = OverpassResponseParser.parse(body)
        assertEquals(1, places.size)
        assertEquals("Good Cafe", places.single().name)
    }

    @Test
    fun parse_elementMissingTags_isSkipped() {
        val body = """{"elements":[{"type":"node","id":6,"lat":1.0,"lon":2.0}]}"""
        assertTrue(OverpassResponseParser.parse(body).isEmpty())
    }

    @Test
    fun parse_elementMissingCoordinates_isSkipped() {
        val body = """
            {"elements":[
              {"type":"way","id":7,"tags":{"amenity":"cafe","name":"No Coordinates"}}
            ]}
        """.trimIndent()
        assertTrue(OverpassResponseParser.parse(body).isEmpty())
    }

    @Test
    fun parse_partialCategoryFailure_keepsTheGoodElementsOnly() {
        val body = """
            {"elements":[
              {"type":"node","id":8,"lat":1.0,"lon":2.0,"tags":{"amenity":"restaurant","name":"Good Food"}},
              {"type":"node","id":9,"tags":{"amenity":"cafe","name":"No Coords"}},
              {"type":"node","id":10,"lat":1.0,"lon":2.0,"tags":{"shop":"bakery"}},
              {"type":"node","id":11,"lat":3.0,"lon":4.0,"tags":{"amenity":"fuel","name":"Good Gas"}}
            ]}
        """.trimIndent()
        val places = OverpassResponseParser.parse(body)
        assertEquals(setOf("Good Food", "Good Gas"), places.map { it.name }.toSet())
    }

    @Test
    fun parse_emptyElements_returnsEmptyList_notFailure() {
        assertTrue(OverpassResponseParser.parse("""{"elements":[]}""").isEmpty())
    }

    @Test
    fun parse_missingElementsKey_returnsEmptyList() {
        assertTrue(OverpassResponseParser.parse("""{"version":0.6}""").isEmpty())
    }

    @Test(expected = JSONException::class)
    fun parse_notJson_throwsJsonException() {
        OverpassResponseParser.parse("this is not json")
    }

    @Test
    fun parse_evCharging_readsOperatorAndConnectors() {
        val body = """
            {"elements":[
              {"type":"node","id":12,"lat":1.0,"lon":2.0,
               "tags":{"amenity":"charging_station","name":"Fast Charge","operator":"ChargeCo",
                       "socket:type2":"2","socket:ccs":"1"}}
            ]}
        """.trimIndent()
        val place = OverpassResponseParser.parse(body).single()
        assertEquals("ChargeCo", place.metadata.brand)
        assertEquals(listOf("ccs", "type2"), place.metadata.connectors)
    }

    @Test
    fun parse_gas_readsBrand() {
        val body = """
            {"elements":[
              {"type":"node","id":13,"lat":1.0,"lon":2.0,"tags":{"amenity":"fuel","name":"Fuel Stop","brand":"Shell"}}
            ]}
        """.trimIndent()
        assertEquals("Shell", OverpassResponseParser.parse(body).single().metadata.brand)
    }

    @Test
    fun parse_neverInventsOpenNow() {
        val body = """
            {"elements":[
              {"type":"node","id":14,"lat":1.0,"lon":2.0,"tags":{"amenity":"restaurant","name":"Diner"}}
            ]}
        """.trimIndent()
        assertNull(OverpassResponseParser.parse(body).single().openNow)
    }

    @Test
    fun parse_newPhase12ACategories_mapCorrectlyAndNeverInventMetadata() {
        val body = """
            {"elements":[
              {"type":"node","id":20,"lat":1.0,"lon":2.0,"tags":{"amenity":"bank","name":"First Bank"}},
              {"type":"node","id":21,"lat":1.0,"lon":2.0,"tags":{"amenity":"atm"}},
              {"type":"node","id":22,"lat":1.0,"lon":2.0,"tags":{"shop":"convenience","name":"Quick Stop"}},
              {"type":"node","id":23,"lat":1.0,"lon":2.0,"tags":{"amenity":"hospital","name":"General Hospital"}},
              {"type":"node","id":24,"lat":1.0,"lon":2.0,"tags":{"amenity":"cinema","name":"Grand Cinema"}},
              {"type":"node","id":25,"lat":1.0,"lon":2.0,"tags":{"shop":"mall","name":"Big Mall"}}
            ]}
        """.trimIndent()
        val places = OverpassResponseParser.parse(body).associateBy { it.id }

        assertEquals(PlaceCategory.Bank, places["node/20"]!!.category)
        assertEquals(PlaceCategory.Atm, places["node/21"]!!.category)
        assertEquals("ATM", places["node/21"]!!.name) // missing name falls back to the category default
        assertEquals(PlaceCategory.Convenience, places["node/22"]!!.category)
        assertEquals(PlaceCategory.Medical, places["node/23"]!!.category)
        assertEquals(PlaceCategory.Entertainment, places["node/24"]!!.category)
        assertEquals(PlaceCategory.Shopping, places["node/25"]!!.category)
        // None of these categories have category-specific metadata (section B2) — never invented.
        places.values.forEach { assertEquals(PlaceMetadata(), it.metadata) }
    }

    @Test
    fun parse_streetAddress_combinesHouseNumberAndStreet() {
        val body = """
            {"elements":[
              {"type":"node","id":15,"lat":1.0,"lon":2.0,
               "tags":{"amenity":"pharmacy","name":"Corner Drug","addr:housenumber":"221B","addr:street":"Baker St"}}
            ]}
        """.trimIndent()
        assertEquals("221B Baker St", OverpassResponseParser.parse(body).single().address)
    }
}
