package com.openmaps.app.core.anchor

import com.openmaps.app.core.routing.Coordinate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Chooses which route vertices become anchor control points. **Pure** — no ARCore — so the sampling
 * rules can be unit-tested, which matters because getting them wrong is invisible on a desktop and
 * obvious in a car.
 *
 * ## Why anchors are keyed by an absolute grid, not by a vertex index
 *
 * The first version of this class keyed anchors on the route vertex index and spaced them out from
 * the driver's current position. That looks right and is quietly broken: as the driver moves, the
 * whole ladder of sample positions slides forward, so every existing key stops being wanted and the
 * entire anchor set is destroyed and recreated. The visible result is a full arrow-set reset — the
 * exact anchor churn that makes anchored content shimmer instead of sitting still.
 *
 * So sample positions are **absolute**: anchor *n* always lies at `n * grid` metres along the route,
 * no matter where the car is. Driving forward then *adds* anchors at the far end of the window and
 * *retires* them at the near end — a conveyor belt, not a rebuild. An anchor that exists is an
 * anchor whose position cannot change, which is the property that makes world-locked arrows stable.
 *
 * Density still adapts to the driver (tighter close in, tighter approaching a maneuver) by deciding
 * which grid points to *keep*, never where they are.
 */
class RouteAnchorSampler(
    private val config: RouteAnchorConfig = RouteAnchorConfig(),
) {

    /**
     * A control point to anchor.
     *
     * [key] is derived from [absoluteDistanceMeters] alone, so it is stable for the life of the
     * route and is what the manager uses to decide create-versus-reuse.
     */
    data class AnchorPlan(
        val key: Long,
        val absoluteDistanceMeters: Double,
        val latitude: Double,
        val longitude: Double,
        val headingRadians: Double,
    )

    /**
     * Samples [geometry] into anchor plans on the absolute grid.
     *
     * @param startIndex the driver's current progress; only used to find the window position.
     * @param distanceToNextManeuverMeters used to tighten density approaching a turn.
     * @param routeDistanceAtIndex cumulative route distance for each vertex, or null to recompute.
     */
    fun plan(
        geometry: List<Coordinate>,
        startIndex: Int,
        distanceToNextManeuverMeters: Double,
        routeDistanceAtIndex: DoubleArray? = null,
    ): List<AnchorPlan> {
        if (geometry.size < 2) return emptyList()

        val cumulative = routeDistanceAtIndex ?: cumulativeDistances(geometry)
        val routeLength = cumulative.last()
        if (routeLength <= RouteAnchorConfig.DEGENERATE_SEGMENT_METERS) return emptyList()

        val start = startIndex.coerceIn(0, geometry.lastIndex)
        val baseDistance = cumulative.getOrElse(start) { 0.0 }

        val grid = config.anchorGridMeters
        val plans = ArrayList<AnchorPlan>()

        // Density is decided in ABSOLUTE route distance, never relative to the driver.
        //
        // An earlier version thinned anchors by distance-ahead, which looks natural but re-phases the
        // whole ladder of control points every time the car moves: the same road yields different
        // anchors at different moments, so every location update destroys and recreates the set.
        // Because anchors are world anchors, that is exactly the wrong property — an anchor's
        // position must be a fact about the road, not about when you happened to ask.
        //
        // So: uniform spacing over the whole route, tightened only around the *next maneuver's
        // absolute position*. As the driver advances, `baseDistance` and the distance-to-maneuver
        // increase together, so that absolute position does not move and the tightened band stays
        // put. Density that genuinely should vary with the driver (arrow spacing near/far, turn
        // density) belongs to Phase 8's sampler, which already does it per arrow.
        val maneuverAbsolute = if (distanceToNextManeuverMeters.isFinite() && distanceToNextManeuverMeters >= 0.0) {
            baseDistance + distanceToNextManeuverMeters
        } else {
            Double.MAX_VALUE
        }

        // Density is a **stateless modular rule on the grid index**, not a forward walk that keeps
        // track of the previous anchor. A forward walk looks equivalent but is not: it has to be
        // seeded from somewhere, so the moment the window moves the seed moves too, the pattern
        // re-phases, and every anchor in the window is destroyed and recreated. The modular rule has
        // no state at all, so "which grid points are anchors" is a pure function of the road.
        val straightStep = gridSteps(config.spacingStraightMeters, grid)
        val maneuverStep = gridSteps(config.spacingNearManeuverMeters, grid)

        val nearIndex = ((baseDistance - config.behindWindowMeters) / grid).toLong().coerceAtLeast(0)
        val farIndex = ((baseDistance + config.aheadWindowMeters) / grid).toLong()

        var index = nearIndex
        while (index <= farIndex) {
            val gridIndex = index
            val absolute = gridIndex * grid
            index++

            // Never create an anchor for road that does not exist.
            if (absolute > routeLength) break
            val distanceAhead = absolute - baseDistance
            if (distanceAhead > config.aheadWindowMeters) break

            val step = if (inManeuverBand(absolute, maneuverAbsolute)) maneuverStep else straightStep
            if (gridIndex % step != 0L) continue

            val position = positionAt(geometry, cumulative, absolute) ?: break
            val segmentIndex = segmentAt(cumulative, absolute)
            plans += AnchorPlan(
                key = absolute.roundToLong(),
                absoluteDistanceMeters = absolute,
                latitude = position.latitude,
                longitude = position.longitude,
                headingRadians = headingAt(geometry, segmentIndex),
            )
        }

        // The destination is always a control point when it is inside the window: it is where the
        // trip ends, and Phase 8's arrival behaviour depends on the endpoint being defined.
        // Outside the window it is deliberately *not* added — an anchor 600 m ahead describes a
        // stretch of road nothing is drawing, and costs a real ARCore object.
        val destinationAhead = routeLength - baseDistance
        if (destinationAhead in 0.0..config.aheadWindowMeters) {
            val destination = geometry.last()
            val endPlan = AnchorPlan(
                key = routeLength.roundToLong(),
                absoluteDistanceMeters = routeLength,
                latitude = destination.latitude,
                longitude = destination.longitude,
                headingRadians = headingAt(geometry, geometry.lastIndex - 1),
            )
            val alreadyCovered = plans.lastOrNull()?.let { routeLength - it.absoluteDistanceMeters < MIN_DESTINATION_GAP_METERS } ?: false
            if (!alreadyCovered) plans += endPlan
        }

        return plans
    }

    /**
     * How many grid steps [spacingMeters] corresponds to, at least 1.
     *
     * The configuration documents that both spacings must be whole multiples of
     * [RouteAnchorConfig.anchorGridMeters]; this rounds rather than trusting callers, because a
     * fractional step would silently produce a spacing that is not the one configured.
     */
    internal fun gridSteps(spacingMeters: Double, grid: Double): Long =
        Math.round(spacingMeters / grid).toLong().coerceAtLeast(1L)

    /**
     * True when [absoluteMeters] lies in the tightened band around a maneuver.
     *
     * Measured in absolute route distance, so the band is a property of the road. The band reaches
     * back from the maneuver by [RouteAnchorConfig.maneuverApproachBandMeters] — enough control
     * points on the approach for the corner to be drawn as a corner rather than a straight line.
     */
    internal fun inManeuverBand(absoluteMeters: Double, maneuverAbsoluteMeters: Double): Boolean {
        if (!maneuverAbsoluteMeters.isFinite()) return false
        val delta = maneuverAbsoluteMeters - absoluteMeters
        return delta in 0.0..config.maneuverApproachBandMeters
    }

    /** The route point [absoluteMeters] along the geometry, or null if out of range. */
    internal fun positionAt(
        geometry: List<Coordinate>,
        cumulative: DoubleArray,
        absoluteMeters: Double,
    ): Coordinate? {
        val clamped = absoluteMeters.coerceIn(0.0, cumulative.last())
        val segment = segmentAt(cumulative, clamped)
        val a = geometry.getOrNull(segment) ?: return null
        val b = geometry.getOrNull(segment + 1) ?: return a
        val leg = cumulative[segment + 1] - cumulative[segment]
        if (leg <= RouteAnchorConfig.DEGENERATE_SEGMENT_METERS) return a
        val t = ((clamped - cumulative[segment]) / leg).coerceIn(0.0, 1.0)
        return Coordinate(
            latitude = a.latitude + (b.latitude - a.latitude) * t,
            longitude = a.longitude + (b.longitude - a.longitude) * t,
        )
    }

    /** Index of the segment containing [absoluteMeters], by binary search over cumulative distance. */
    internal fun segmentAt(cumulative: DoubleArray, absoluteMeters: Double): Int {
        var low = 0
        var high = cumulative.lastIndex - 1
        while (low <= high) {
            val mid = (low + high) / 2
            if (absoluteMeters < cumulative[mid]) {
                high = mid - 1
            } else if (absoluteMeters > cumulative[mid + 1]) {
                low = mid + 1
            } else {
                return mid
            }
        }
        return low.coerceIn(0, max(0, cumulative.lastIndex - 1))
    }

    /**
     * Route direction at a segment, in radians clockwise from north — derived purely from geometry,
     * never from the device heading.
     *
     * Degenerate (duplicate) points fall back to the nearest previous usable segment rather than
     * defaulting to north, which would silently point an arrow the wrong way.
     */
    internal fun headingAt(geometry: List<Coordinate>, segmentIndex: Int): Double {
        var idx = segmentIndex.coerceIn(0, max(0, geometry.lastIndex - 1))
        var a: Coordinate? = geometry.getOrNull(idx)
        var b: Coordinate? = geometry.getOrNull(idx + 1)
        if (a == null || b == null) return 0.0

        if (metersBetween(a, b) <= RouteAnchorConfig.DEGENERATE_SEGMENT_METERS) {
            // Walk backwards for the last segment that actually has a direction.
            for (back in idx - 1 downTo 0) {
                val prev = geometry[back]
                val cur = geometry[back + 1]
                if (metersBetween(prev, cur) > RouteAnchorConfig.DEGENERATE_SEGMENT_METERS) {
                    a = prev
                    b = cur
                    break
                }
            }
        }
        // A degenerate pair means the polyline has no usable direction here at all; reporting north
        // would be a guess that can point an arrow the wrong way.
        val from = a ?: return 0.0
        val to = b ?: return 0.0
        if (metersBetween(from, to) <= RouteAnchorConfig.DEGENERATE_SEGMENT_METERS) return 0.0

        val dEast = metersPerDegreeLon(from.latitude) * (to.longitude - from.longitude)
        val dNorth = RouteAnchorConfig.METERS_PER_DEGREE * (to.latitude - from.latitude)
        return Math.toDegrees(atan2(dEast, dNorth)) * DEG_TO_RAD
    }

    internal fun cumulativeDistances(geometry: List<Coordinate>): DoubleArray {
        val out = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) out[i] = out[i - 1] + metersBetween(geometry[i - 1], geometry[i])
        return out
    }

    private fun metersPerDegreeLon(lat: Double) =
        RouteAnchorConfig.METERS_PER_DEGREE * cos(Math.toRadians(lat))

    private fun metersBetween(a: Coordinate, b: Coordinate): Double {
        val mid = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val dLat = (b.latitude - a.latitude) * RouteAnchorConfig.METERS_PER_DEGREE
        val dLon = (b.longitude - a.longitude) * RouteAnchorConfig.METERS_PER_DEGREE * cos(mid)
        return hypot(dLat, dLon)
    }

    private companion object {
        const val DEG_TO_RAD = Math.PI / 180.0

        /** A destination within this of the last grid point needs no separate anchor. */
        const val MIN_DESTINATION_GAP_METERS = 1.0
    }
}
