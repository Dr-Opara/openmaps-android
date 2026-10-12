package com.openmaps.app.core.routing

/**
 * Decodes Google/Valhalla-style encoded polylines. Valhalla's `/route` endpoint encodes
 * `shape` at precision 6 (not the more common precision 5), confirmed against a live response:
 * the first decoded point lands on the requested origin only at precision 6.
 */
internal object PolylineDecoder {

    fun decode(encoded: String, precision: Int = 6): List<Coordinate> {
        val factor = Math.pow(10.0, precision.toDouble())
        val points = mutableListOf<Coordinate>()
        var index = 0
        var lat = 0
        var lon = 0

        while (index < encoded.length) {
            val (deltaLat, afterLat) = readDelta(encoded, index)
            index = afterLat
            lat += deltaLat

            val (deltaLon, afterLon) = readDelta(encoded, index)
            index = afterLon
            lon += deltaLon

            points.add(Coordinate(lat / factor, lon / factor))
        }
        return points
    }

    /** Reads one varint-encoded, zigzag-signed delta starting at [start]; returns (value, nextIndex). */
    private fun readDelta(encoded: String, start: Int): Pair<Int, Int> {
        var index = start
        var shift = 0
        var result = 0
        var byte: Int
        do {
            byte = encoded[index].code - 63
            index++
            result = result or ((byte and 0x1f) shl shift)
            shift += 5
        } while (byte >= 0x20)
        val delta = if (result and 1 != 0) (result shr 1).inv() else result shr 1
        return delta to index
    }
}
