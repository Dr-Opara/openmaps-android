package com.openmaps.app.core.places

import com.openmaps.app.core.routing.Coordinate
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Route-corridor approximation (Phase 9): which places sit near enough to the active route to be
 * worth surfacing, and whether they are ahead of or behind the driver.
 *
 * Deliberately **not** a detour-routing engine — see ARCHITECTURE.md "Route-aware filtering" and
 * ROADMAP.md, which both call a corridor approximation sufficient for this phase. It uses the same
 * local flat-earth (equirectangular) projection as [com.openmaps.app.core.navigation.RouteProgressTracker],
 * so a place's distance to the route never disagrees with how the tracker itself measures distance
 * along it.
 */
object RouteCorridor {

    /** A query center/radius covering a route's geometry plus a corridor margin. */
    data class BoundingQuery(val center: Coordinate, val radiusMeters: Double)

    /**
     * The smallest center + radius that covers all of [geometry] plus [corridorMeters] of margin,
     * capped at [maxRadiusMeters] so a very long route can never trigger an unbounded-radius query.
     */
    fun boundingQuery(geometry: List<Coordinate>, corridorMeters: Double, maxRadiusMeters: Double): BoundingQuery {
        require(geometry.isNotEmpty()) { "geometry must not be empty" }
        var minLat = geometry[0].latitude
        var maxLat = geometry[0].latitude
        var minLon = geometry[0].longitude
        var maxLon = geometry[0].longitude
        for (point in geometry) {
            if (point.latitude < minLat) minLat = point.latitude
            if (point.latitude > maxLat) maxLat = point.latitude
            if (point.longitude < minLon) minLon = point.longitude
            if (point.longitude > maxLon) maxLon = point.longitude
        }
        val center = Coordinate((minLat + maxLat) / 2.0, (minLon + maxLon) / 2.0)
        val corner = Coordinate(maxLat, maxLon)
        val radius = (metersBetween(center, corner) + corridorMeters).coerceAtMost(maxRadiusMeters)
        return BoundingQuery(center, radius)
    }

    /** The nearest route segment to a point, and the lateral distance to it. */
    data class NearestSegment(val segmentIndex: Int, val distanceMeters: Double)

    fun nearestSegment(point: Coordinate, geometry: List<Coordinate>): NearestSegment? {
        if (geometry.size < 2) return null
        var best: NearestSegment? = null
        for (i in 0 until geometry.size - 1) {
            val distance = distanceToSegmentMeters(point, geometry[i], geometry[i + 1])
            if (best == null || distance < best.distanceMeters) best = NearestSegment(i, distance)
        }
        return best
    }

    /**
     * Keeps only the places within [corridorMeters] of [geometry], stamping each survivor's
     * [Place.routeDetourMeters] with the lateral distance that was measured. Places with no usable
     * geometry to compare against (an empty/degenerate route) are dropped rather than kept with a
     * fabricated distance.
     */
    fun filterToCorridor(places: List<Place>, geometry: List<Coordinate>, corridorMeters: Double): List<Place> =
        places.mapNotNull { place ->
            val nearest = nearestSegment(Coordinate(place.latitude, place.longitude), geometry) ?: return@mapNotNull null
            if (nearest.distanceMeters > corridorMeters) null else place.copy(routeDetourMeters = nearest.distanceMeters)
        }

    /**
     * True when [place]'s nearest point on [geometry] has not yet been passed relative to
     * [snappedShapeIndex] (the driver's current position on the route, from `RouteProgress`).
     * Places with no resolvable segment (e.g. an empty route) are treated as ahead rather than
     * silently dropped — callers that care about "ahead only" already filter on [filterToCorridor]
     * returning non-null first.
     */
    fun isAhead(place: Place, geometry: List<Coordinate>, snappedShapeIndex: Int): Boolean {
        val nearest = nearestSegment(Coordinate(place.latitude, place.longitude), geometry) ?: return true
        return nearest.segmentIndex >= snappedShapeIndex
    }

    fun metersBetween(a: Coordinate, b: Coordinate): Double {
        val midLat = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val dLat = (b.latitude - a.latitude) * METERS_PER_DEGREE_LAT
        val dLon = (b.longitude - a.longitude) * METERS_PER_DEGREE_LAT * cos(midLat)
        return sqrt(dLat * dLat + dLon * dLon)
    }

    private fun distanceToSegmentMeters(point: Coordinate, start: Coordinate, end: Coordinate): Double {
        val metersPerDegreeLon = METERS_PER_DEGREE_LAT * cos(Math.toRadians(start.latitude))
        val endX = (end.longitude - start.longitude) * metersPerDegreeLon
        val endY = (end.latitude - start.latitude) * METERS_PER_DEGREE_LAT
        val pointX = (point.longitude - start.longitude) * metersPerDegreeLon
        val pointY = (point.latitude - start.latitude) * METERS_PER_DEGREE_LAT

        val segmentLengthSquared = endX * endX + endY * endY
        val t = if (segmentLengthSquared > 0) {
            (((pointX * endX) + (pointY * endY)) / segmentLengthSquared).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val dx = pointX - t * endX
        val dy = pointY - t * endY
        return sqrt(dx * dx + dy * dy)
    }

    private const val METERS_PER_DEGREE_LAT = 111_320.0
}
