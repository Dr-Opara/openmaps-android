package com.openmaps.app.core.geocoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackendGeocodingResponseParserTest {

    @Test
    fun parsesProviderNeutralResponse() {
        val body = """
            {
              "results": [
                {
                  "id": "123",
                  "name": "Melton Elementary School",
                  "displayName": "Melton Elementary School, 2880 Jordan Ranch Blvd, Brookshire, TX",
                  "latitude": 29.75,
                  "longitude": -95.89,
                  "category": "school",
                  "address": "2880 Jordan Ranch Blvd",
                  "city": "Brookshire",
                  "state": "Texas",
                  "country": "United States"
                }
              ]
            }
        """.trimIndent()

        val result = BackendGeocodingResponseParser.parse(body).single()

        assertEquals("123", result.id)
        assertEquals("Melton Elementary School", result.name)
        assertEquals("2880 Jordan Ranch Blvd", result.address)
        assertEquals("Brookshire", result.city)
    }

    @Test
    fun optionalNullFieldsRemainNull() {
        val body = """
            {
              "results": [
                {
                  "id": "1",
                  "name": "Point",
                  "displayName": "Point",
                  "latitude": 1.0,
                  "longitude": 2.0,
                  "category": null,
                  "address": null,
                  "city": null,
                  "state": null,
                  "country": null
                }
              ]
            }
        """.trimIndent()

        val result = BackendGeocodingResponseParser.parse(body).single()
        assertNull(result.category)
        assertNull(result.address)
    }
}
