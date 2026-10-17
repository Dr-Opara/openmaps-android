package com.openmaps.app.core.places

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendPlaceResponseParserTest {

    @Test
    fun parsesProviderNeutralPlaces() {
        val body = """
            {
              "provider":"here+osm",
              "providers":["here","osm"],
              "capability":"available",
              "places":[
                {
                  "id":"here:school-1",
                  "name":"OpenMaps Academy",
                  "category":"Education",
                  "latitude":29.7604,
                  "longitude":-95.3698,
                  "address":"100 Main St",
                  "distanceMeters":125.0,
                  "openNow":null,
                  "metadata":{
                    "parkingType":null,
                    "isPublicAccess":null,
                    "feeApplies":null,
                    "brand":null,
                    "connectors":[]
                  }
                }
              ]
            }
        """.trimIndent()

        val places = BackendPlaceResponseParser.parse(body)

        assertEquals(1, places.size)
        assertEquals(PlaceCategory.Education, places.single().category)
        assertEquals("OpenMaps Academy", places.single().name)
        assertEquals(125.0, places.single().distanceMeters!!, 0.001)
        assertNull(places.single().openNow)
    }

    @Test
    fun skipsUnknownCategoriesInsteadOfGuessing() {
        val body = """
            {"places":[{"id":"x","name":"Unknown","category":"Other","latitude":1.0,"longitude":2.0}]}
        """.trimIndent()

        assertTrue(BackendPlaceResponseParser.parse(body).isEmpty())
    }
}
