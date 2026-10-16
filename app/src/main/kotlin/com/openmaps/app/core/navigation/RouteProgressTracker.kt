package com.openmaps.app.core.navigation

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.RouteResult
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Projects a live location onto a [RouteResult]'s decoded geometry and reports progress: how far
 * along the route the user is, which maneuver is current/next, and how far off the route line
 * they are. Stateful (remembers the last matched segment) so repeated calls on a moving user are
 * cheap: each call searches only a window around the last match instead of the full route, with
 * a full-scan fallback only if that window's best match is implausibly far away.
 *
 * Uses a local equirectangular (flat-earth) projection for distance and nearest-point-on-segment
 * math. This is standard for route-matching at these scales (route lengths up to a few hundred
 * km, matching tolerances of tens of meters): well under 1% error, far cheaper than geodesic math,
 * and not trying to be a research-grade map matcher.
 *
 * `distanceTraveledMeters`/`snappedShapeIndex`/the resolved maneuver index are all clamped to
 * never regress tick-over-tick, so GPS jitter cannot bounce progress or the maneuver banner
 * backward.
 */
class RouteProgressTracker(private val route: RouteResult) {

    private val geometry = route.geometry
    private val cumulativeDistances: DoubleArray = buildCumulativeDistances(geometry)
    private val totalDistanceMeters = cumulativeDistances.lastOrNull() ?: 0.0

    private var lastMatchedSegment = 0
    private var lastManeuverIndex = 0
    private var lastDistanceTraveledMeters = 0.0

    /** Returns null if the route has no usable geometry/maneuvers to report progress against. */
    fun locate(position: Coordinate, headingDegrees: Double? = null): RouteProgress? {
        if (geometry.size < 2 || route.maneuvers.isEmpty()) return null

        val match = findNearestSegment(position, headingDegrees)
        lastMatchedSegment = match.segmentIndex

        val distanceTraveled = max(
            cumulativeDistances[match.segmentIndex] + match.distanceAlongSegmentMeters,
            lastDistanceTraveledMeters,
        )
        lastDistanceTraveledMeters = distanceTraveled

        val remainingDistance = (totalDistanceMeters - distanceTraveled).coerceAtLeast(0.0)
        val progressFraction = if (totalDistanceMeters > 0) {
            (distanceTraveled / totalDistanceMeters).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val remainingDuration = if (totalDistanceMeters > 0) {
            route.durationSeconds * (remainingDistance / totalDistanceMeters)
        } else {
            0.0
        }

        val maneuverIndex = max(resolveManeuverIndex(match.segmentIndex), lastManeuverIndex)
        lastManeuverIndex = maneuverIndex
        val current = route.maneuvers[maneuverIndex]
        val next = route.maneuvers.getOrNull(maneuverIndex + 1)
        val endIndex = current.endShapeIndex.coerceIn(0, cumulativeDistances.lastIndex)
        val distanceToNext = (cumulativeDistances[endIndex] - distanceTraveled).coerceAtLeast(0.0)

        return RouteProgress(
            snappedShapeIndex = match.segmentIndex,
            snappedCoordinate = match.projectedCoordinate,
            mapMatchConfidence = confidenceFor(match),
            lateralDistanceMeters = match.lateralDistanceMeters,
            distanceTraveledMeters = distanceTraveled,
            remainingDistanceMeters = remainingDistance,
            remainingDurationSeconds = remainingDuration,
            progressFraction = progressFraction,
            currentManeuver = current,
            nextManeuver = next,
            distanceToNextManeuverMeters = distanceToNext,
            isOffRoute = false, // the caller (OffRouteDetector) decides this; the tracker only reports geometry
        )
    }

    private fun resolveManeuverIndex(shapeIndex: Int): Int {
        var index = 0
        for (i in route.maneuvers.indices) {
            if (route.maneuvers[i].beginShapeIndex <= shapeIndex) index = i else break
        }
        return index
    }

    private data class SegmentMatch(
        val segmentIndex: Int,
        val distanceAlongSegmentMeters: Double,
        val lateralDistanceMeters: Double,
        val projectedCoordinate: Coordinate,
        val headingDifferenceDegrees: Double?,
    )

    private fun findNearestSegment(position: Coordinate, headingDegrees: Double?): SegmentMatch {
        val windowStart = max(0, lastMatchedSegment - BACKWARD_WINDOW)
        val windowEnd = min(geometry.size - 2, lastMatchedSegment + FORWARD_WINDOW)
        var best = projectOntoSegment(position, windowStart, headingDegrees)
        for (i in windowStart + 1..windowEnd) {
            val candidate = projectOntoSegment(position, i, headingDegrees)
            if (isBetterMatch(candidate, best)) best = candidate
        }
        // Fallback: if the windowed search missed badly (e.g. a reroute just replaced the route
        // and lastMatchedSegment is stale), do one full scan rather than report a bogus match.
        if (best.lateralDistanceMeters > FALLBACK_THRESHOLD_METERS) {
            for (i in 0 until geometry.size - 1) {
                if (i in windowStart..windowEnd) continue
                val candidate = projectOntoSegment(position, i, headingDegrees)
                if (isBetterMatch(candidate, best)) best = candidate
            }
        }
        return best
    }

    /**
     * True when [candidate] should replace [best] as the snapped position.
     *
     * The lateral-distance comparison alone is not enough. A fix sitting on or very near a route
     * vertex is equidistant (often exactly 0.0) from the segments on *both* sides of it, and a
     * strict "strictly closer wins" test keeps the earlier one — snapping the driver to the
     * segment they have already left and pulling the follow camera behind them. So ties (and
     * near-ties within [TIE_TOLERANCE_METERS]) resolve forward, which is also the only direction
     * a moving driver can legitimately have come from.
     */
    private fun isBetterMatch(candidate: SegmentMatch, best: SegmentMatch): Boolean {
        val candidateScore = matchScore(candidate)
        val bestScore = matchScore(best)
        return when {
            candidateScore < bestScore - TIE_TOLERANCE_METERS -> true
            candidateScore > bestScore + TIE_TOLERANCE_METERS -> false
            else -> candidate.segmentIndex > best.segmentIndex
        }
    }

    /**
     * Distance is always the primary signal. When Android supplies a real movement bearing, large
     * heading disagreement adds a bounded penalty so parallel service roads do not steal the snap.
     */
    private fun matchScore(match: SegmentMatch): Double {
        val headingPenalty = match.headingDifferenceDegrees
            ?.let { (it.coerceAtMost(90.0) / 90.0) * MAX_HEADING_PENALTY_METERS }
            ?: 0.0
        return match.lateralDistanceMeters + headingPenalty
    }

    private fun confidenceFor(match: SegmentMatch): MapMatchConfidence = when {
        match.lateralDistanceMeters > NavigationSession.OFF_ROUTE_THRESHOLD_METERS -> MapMatchConfidence.OffRoute
        match.lateralDistanceMeters <= 15.0 && (match.headingDifferenceDegrees == null || match.headingDifferenceDegrees <= 35.0) ->
            MapMatchConfidence.High
        match.lateralDistanceMeters <= 35.0 && (match.headingDifferenceDegrees == null || match.headingDifferenceDegrees <= 60.0) ->
            MapMatchConfidence.Medium
        else -> MapMatchConfidence.Low
    }

    private fun projectOntoSegment(
        position: Coordinate,
        segmentIndex: Int,
        headingDegrees: Double?,
    ): SegmentMatch {
        val start = geometry[segmentIndex]
        val end = geometry[segmentIndex + 1]
        val metersPerDegreeLon = METERS_PER_DEGREE_LATITUDE * cos(Math.toRadians(start.latitude))

        val endX = (end.longitude - start.longitude) * metersPerDegreeLon
        val endY = (end.latitude - start.latitude) * METERS_PER_DEGREE_LATITUDE
        val pointX = (position.longitude - start.longitude) * metersPerDegreeLon
        val pointY = (position.latitude - start.latitude) * METERS_PER_DEGREE_LATITUDE

        val segmentLengthSquared = endX * endX + endY * endY
        val t = if (segmentLengthSquared > 0) {
            (((pointX * endX) + (pointY * endY)) / segmentLengthSquared).coerceIn(0.0, 1.0)
        } else {
            0.0
        }
        val projectedX = t * endX
        val projectedY = t * endY
        val dx = pointX - projectedX
        val dy = pointY - projectedY

        val projectedCoordinate = Coordinate(
            latitude = start.latitude + (end.latitude - start.latitude) * t,
            longitude = start.longitude + (end.longitude - start.longitude) * t,
        )
        val segmentBearing = if (segmentLengthSquared > 0) bearingBetween(start, end) else null
        val headingDifference = if (headingDegrees != null && headingDegrees.isFinite() && segmentBearing != null) {
            angularDifference(headingDegrees, segmentBearing)
        } else {
            null
        }

        return SegmentMatch(
            segmentIndex = segmentIndex,
            distanceAlongSegmentMeters = t * sqrt(segmentLengthSquared),
            lateralDistanceMeters = sqrt(dx * dx + dy * dy),
            projectedCoordinate = projectedCoordinate,
            headingDifferenceDegrees = headingDifference,
        )
    }

    companion object {
        private const val BACKWARD_WINDOW = 5
        private const val FORWARD_WINDOW = 80
        private const val FALLBACK_THRESHOLD_METERS = 500.0

        /**
         * Lateral slack for deciding two segments are equidistant. Comfortably above consumer GPS
         * noise so a real movement still snaps forward, and far below the ~75 m off-route
         * threshold so it can never mask genuine route choice at a junction.
         */
        private const val TIE_TOLERANCE_METERS = 5.0
        private const val MAX_HEADING_PENALTY_METERS = 25.0

        private const val METERS_PER_DEGREE_LATITUDE = 111_320.0

        private fun buildCumulativeDistances(geometry: List<Coordinate>): DoubleArray {
            if (geometry.isEmpty()) return DoubleArray(0)
            val distances = DoubleArray(geometry.size)
            for (i in 1 until geometry.size) {
                distances[i] = distances[i - 1] + metersBetween(geometry[i - 1], geometry[i])
            }
            return distances
        }

        private fun metersBetween(a: Coordinate, b: Coordinate): Double {
            val midLatRadians = Math.toRadians((a.latitude + b.latitude) / 2.0)
            val dLat = (b.latitude - a.latitude) * METERS_PER_DEGREE_LATITUDE
            val dLon = (b.longitude - a.longitude) * METERS_PER_DEGREE_LATITUDE * cos(midLatRadians)
            return sqrt(dLat * dLat + dLon * dLon)
        }

        private fun bearingBetween(a: Coordinate, b: Coordinate): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val y = kotlin.math.sin(dLon) * kotlin.math.cos(lat2)
            val x = kotlin.math.cos(lat1) * kotlin.math.sin(lat2) -
                kotlin.math.sin(lat1) * kotlin.math.cos(lat2) * kotlin.math.cos(dLon)
            return ((Math.toDegrees(atan2(y, x)) % 360.0) + 360.0) % 360.0
        }

        private fun angularDifference(a: Double, b: Double): Double {
            val raw = abs((((a - b) % 360.0) + 360.0) % 360.0)
            return minOf(raw, 360.0 - raw)
        }
    }
}
