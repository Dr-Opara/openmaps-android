package com.openmaps.app.map

import com.openmaps.app.core.routing.RouteBounds
import com.openmaps.app.core.routing.RouteResult
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

data class OfflineCorridorPlan(
    val bounds: RouteBounds,
    val minZoom: Double,
    val maxZoom: Double,
)

/**
 * Conservative route-corridor policy for the single active offline map region.
 *
 * Stadia's mobile terms cap cached map tiles at 100 MB/device. OpenMaps therefore uses one active
 * route region, a narrow geographic pad, distance-sensitive max zoom, and a runtime 90 MiB stop
 * threshold in the Android downloader.
 */
object OfflineCorridorPolicy {
    private const val CORRIDOR_PAD_METERS = 1_500.0
    private const val METERS_PER_LAT_DEGREE = 111_320.0

    fun plan(route: RouteResult): OfflineCorridorPlan? {
        val raw = route.bounds ?: route.geometry.toBounds() ?: return null
        val centerLat = (raw.north + raw.south) / 2.0
        val latPad = CORRIDOR_PAD_METERS / METERS_PER_LAT_DEGREE
        val lonScale = max(0.15, cos(Math.toRadians(centerLat)))
        val lonPad = CORRIDOR_PAD_METERS / (METERS_PER_LAT_DEGREE * lonScale)

        val padded = RouteBounds(
            south = max(-85.0, raw.south - latPad),
            west = max(-180.0, raw.west - lonPad),
            north = min(85.0, raw.north + latPad),
            east = min(180.0, raw.east + lonPad),
        )
        val maxZoom = when {
            route.distanceMeters <= 30_000.0 -> 15.0
            route.distanceMeters <= 100_000.0 -> 14.0
            route.distanceMeters <= 300_000.0 -> 13.0
            else -> 12.0
        }
        return OfflineCorridorPlan(
            bounds = padded,
            minZoom = 6.0,
            maxZoom = maxZoom,
        )
    }

    private fun List<com.openmaps.app.core.routing.Coordinate>.toBounds(): RouteBounds? {
        if (isEmpty()) return null
        return RouteBounds(
            south = minOf { it.latitude },
            west = minOf { it.longitude },
            north = maxOf { it.latitude },
            east = maxOf { it.longitude },
        )
    }
}
