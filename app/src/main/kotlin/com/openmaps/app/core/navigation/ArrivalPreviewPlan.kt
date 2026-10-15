package com.openmaps.app.core.navigation

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.RouteResult
import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Provider-neutral final-approach slice for the future Arrival Preview in Live experience.
 *
 * This does not fabricate imagery or AR. It only identifies the final route geometry that a real
 * camera/street-imagery provider can later render. Until such a provider exists, the UI should not
 * present a fake "preview".
 */
data class ArrivalPreviewPlan(
    val geometry: List<Coordinate>,
    val distanceMeters: Double,
) {
    companion object {
        fun from(route: RouteResult, targetMeters: Double = DEFAULT_PREVIEW_METERS): ArrivalPreviewPlan? {
            if (route.geometry.size < 2) return null

            val reversed = ArrayList<Coordinate>()
            reversed += route.geometry.last()
            var accumulated = 0.0

            for (index in route.geometry.lastIndex downTo 1) {
                val end = route.geometry[index]
                val start = route.geometry[index - 1]
                val segment = metersBetween(start, end)
                accumulated += segment
                reversed += start
                if (accumulated >= targetMeters) break
            }

            val geometry = reversed.asReversed()
            if (geometry.size < 2) return null
            return ArrivalPreviewPlan(
                geometry = geometry,
                distanceMeters = accumulated.coerceAtMost(route.distanceMeters),
            )
        }

        private fun metersBetween(a: Coordinate, b: Coordinate): Double {
            val lat1 = a.latitude * PI / 180.0
            val lat2 = b.latitude * PI / 180.0
            val dLat = (b.latitude - a.latitude) * PI / 180.0
            val dLon = (b.longitude - a.longitude) * PI / 180.0

            val h = sin(dLat / 2) * sin(dLat / 2) +
                cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
            return 2.0 * EARTH_RADIUS_METERS * asin(sqrt(h.coerceIn(0.0, 1.0)))
        }

        const val DEFAULT_PREVIEW_METERS = 500.0
        private const val EARTH_RADIUS_METERS = 6_371_000.0
    }
}
