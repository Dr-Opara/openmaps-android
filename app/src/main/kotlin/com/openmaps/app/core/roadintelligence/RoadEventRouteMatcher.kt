package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.places.RouteCorridor
import com.openmaps.app.core.routing.Coordinate
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Matches raw [RoadEvent]s to a route: drops events too far off the line, and stamps the survivors
 * with [RoadEvent.routeDistanceMeters] — the cumulative distance *along the route* to each event's
 * nearest point, not a straight-line one. That is what lets [RoadAlertActivation] later compute
 * "distance ahead" as a cheap subtraction against `RouteProgress.distanceTraveledMeters` rather than
 * re-walking the route geometry on every GPS tick (section 13/19 of the Phase 10 brief).
 *
 * Reuses [RouteCorridor]'s nearest-segment math (the same local flat-earth projection
 * `RouteProgressTracker` uses), so a hazard's distance to the route never disagrees with how the
 * tracker measures the driver's own progress along it — exactly the reasoning
 * `RouteCorridor.filterToCorridor` already documents for places.
 */
object RoadEventRouteMatcher {

    /**
     * [events] within [corridorMeters] of [geometry], each with [RoadEvent.routeDistanceMeters] set,
     * sorted nearest-along-route first. Events with no resolvable segment (an empty/degenerate
     * route) are dropped rather than kept with a fabricated distance.
     */
    fun matchToRoute(events: List<RoadEvent>, geometry: List<Coordinate>, corridorMeters: Double): List<RoadEvent> {
        if (geometry.size < 2) return emptyList()
        val prefixMeters = cumulativePrefixMeters(geometry)
        return events
            .mapNotNull { event ->
                val point = Coordinate(event.latitude, event.longitude)
                val nearest = RouteCorridor.nearestSegment(point, geometry) ?: return@mapNotNull null
                if (nearest.distanceMeters > corridorMeters) return@mapNotNull null
                val alongSegment = projectedDistanceAlongSegment(
                    point = point,
                    start = geometry[nearest.segmentIndex],
                    end = geometry[nearest.segmentIndex + 1],
                )
                event.copy(routeDistanceMeters = prefixMeters[nearest.segmentIndex] + alongSegment)
            }
            .sortedBy { it.routeDistanceMeters }
    }

    /**
     * `prefix[i]` is the route distance from the start up to `geometry[i]`. Computed once per route
     * (O(n)) and shared across every event matched against it, rather than re-walking the geometry
     * per event.
     */
    private fun cumulativePrefixMeters(geometry: List<Coordinate>): DoubleArray {
        val prefix = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            prefix[i] = prefix[i - 1] + RouteCorridor.metersBetween(geometry[i - 1], geometry[i])
        }
        return prefix
    }

    /**
     * How far along the [start]-[end] segment [point]'s perpendicular projection falls, in metres
     * from [start]. Using [RouteCorridor.metersBetween] directly (the straight-line chord to
     * [point]) would overcount by the event's lateral offset from the route — fine for a point
     * right on the line, wrong by up to [corridorMeters]-scale error for one sitting near the
     * corridor's edge, which is exactly the "distance along route, not straight-line" distinction
     * section 13 of the Phase 10 brief calls for. [RouteCorridor] keeps the equivalent projection
     * private, so the same small equirectangular math is duplicated here rather than widening that
     * type's API for this one caller.
     */
    private fun projectedDistanceAlongSegment(point: Coordinate, start: Coordinate, end: Coordinate): Double {
        val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(Math.toRadians(start.latitude))
        val endX = (end.longitude - start.longitude) * metersPerDegreeLon
        val endY = (end.latitude - start.latitude) * METERS_PER_DEGREE_LAT
        val pointX = (point.longitude - start.longitude) * metersPerDegreeLon
        val pointY = (point.latitude - start.latitude) * METERS_PER_DEGREE_LAT

        val segmentLengthSquared = endX * endX + endY * endY
        if (segmentLengthSquared <= 0.0) return 0.0
        val t = (((pointX * endX) + (pointY * endY)) / segmentLengthSquared).coerceIn(0.0, 1.0)
        return t * sqrt(segmentLengthSquared)
    }

    private const val METERS_PER_DEGREE_LAT = 111_320.0
}
