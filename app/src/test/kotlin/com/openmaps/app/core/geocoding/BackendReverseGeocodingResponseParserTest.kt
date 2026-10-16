package com.openmaps.app.core.geocoding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BackendReverseGeocodingResponseParserTest {

    @Test
    fun parsesLocation() {
        val body = """
            {
              "provider": "nominatim",
              "result": {
                "id": "osm:1",
                "name": "2880 Jordan Ranch Blvd",
                "displayName": "2880 Jordan Ranch Blvd, Brookshire, Texas, United States",
                "latitude": 29.75,
                "longitude": -95.89,
                "category": "house",
                "address": "2880 Jordan Ranch Blvd",
                "city": "Brookshire",
                "state": "Texas",
                "country": "United States"
              }
            }
        """.trimIndent()

        val result = BackendReverseGeocodingResponseParser.parse(body)

        assertEquals("2880 Jordan Ranch Blvd", result?.address)
        assertEquals("Brookshire", result?.city)
    }

    @Test
    fun nullResultParsesAsNull() {
        assertNull(BackendReverseGeocodingResponseParser.parse("""{"result":null}"""))
    }
}
