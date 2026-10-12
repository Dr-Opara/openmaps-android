package com.openmaps.app.core.geocoding

import org.json.JSONException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class NominatimResponseParserTest {

    @Test
    fun parse_successfulResponse_mapsAllFields() {
        val body = """
            [
              {
                "place_id": 12345,
                "name": "Empire State Building",
                "display_name": "Empire State Building, 350, 5th Avenue, Manhattan, New York, 10118, United States",
                "lat": "40.7484405",
                "lon": "-73.9856644",
                "type": "attraction",
                "category": "tourism",
                "address": {
                  "house_number": "350",
                  "road": "5th Avenue",
                  "city": "New York",
                  "state": "New York",
                  "country": "United States"
                }
              }
            ]
        """.trimIndent()

        val results = NominatimResponseParser.parse(body)

        assertEquals(1, results.size)
        val result = results.single()
        assertEquals("12345", result.id)
        assertEquals("Empire State Building", result.name)
        assertEquals(40.7484405, result.latitude, 1e-9)
        assertEquals(-73.9856644, result.longitude, 1e-9)
        assertEquals("attraction", result.category)
        assertEquals("350 5th Avenue", result.address)
        assertEquals("New York", result.city)
        assertEquals("New York", result.state)
        assertEquals("United States", result.country)
    }

    @Test
    fun parse_emptyArray_returnsEmptyList() {
        assertTrue(NominatimResponseParser.parse("[]").isEmpty())
    }

    @Test
    fun parse_missingOptionalFields_fillsNulls() {
        val body = """[{"place_id": 1, "display_name": "Somewhere", "lat": "1.0", "lon": "2.0"}]"""
        val result = NominatimResponseParser.parse(body).single()
        assertEquals("Somewhere", result.name) // falls back to display_name's first segment
        assertNull(result.category)
        assertNull(result.address)
        assertNull(result.city)
    }

    @Test
    fun parse_malformedJson_throwsJsonException() {
        assertThrows(JSONException::class.java) { NominatimResponseParser.parse("not json") }
    }

    @Test
    fun parse_notAnArray_throwsJsonException() {
        assertThrows(JSONException::class.java) { NominatimResponseParser.parse("""{"error": "oops"}""") }
    }

    @Test
    fun parse_missingLatLon_throwsJsonException() {
        val body = """[{"place_id": 1, "display_name": "Nowhere"}]"""
        assertThrows(JSONException::class.java) { NominatimResponseParser.parse(body) }
    }
}
