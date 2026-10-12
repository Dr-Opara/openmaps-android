package com.openmaps.app.core.arrow

import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.spatial.LocalizationQuality
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Turns a route into the chevrons to draw. **Pure**: no ARCore, no GL, no Android — so every rule
 * that decides where an arrow sits and which way it points is unit-testable on the JVM, which is the
 * only practical way to debug spatial behaviour that would otherwise need a phone in a car.
 *
 * Sampling walks the decoded route geometry forward from the driver's current progress, emitting an
 * arrow every [ARArrowConfig] metres. Spacing is **non-uniform by design** (see [spacingAt]) so the
 * path reads as a direction the driver is following rather than a dotted line of identical marks.
 */
class ARArrowSampler(
    private val config: ARArrowConfig = ARArrowConfig(),
) {

    /**
     * @param geometry the route's decoded points, in travel order.
     * @param progress where the driver is now (null when not navigating: no arrows at all).
     * @param quality localization quality; gates density and visibility.
     * @param isArrived true once arrival is detected: the path has served its purpose.
     * @param remainingDistanceMeters trip distance remaining, used to thin arrows near the destination.
     */
    fun sample(
        geometry: List<Coordinate>,
        progress: RouteProgress?,
        quality: LocalizationQuality,
        isArrived: Boolean = false,
        remainingDistanceMeters: Double = Double.MAX_VALUE,
    ): List<ARArrowNode> {
        // Arrival ends the path; Poor or worse means we cannot promise the arrows are in the right
        // place, so drawing them would be worse than drawing none. `<=` because
        // `minimumQuality` is documented as "at or below this, draw nothing" — a `<` here silently
        // let Poor quality through.
        if (isArrived || progress == null) return emptyList()
        if (quality.ordinal <= config.minimumQuality.ordinal) return emptyList()
        if (geometry.size < 2) return emptyList()

        val reduced = quality.ordinal < config.fullDensityQuality.ordinal
        val cap = if (reduced) {
            max(1, (config.maxVisibleArrows * config.reducedDensityFactor).toInt())
        } else {
            config.maxVisibleArrows
        }

        val startIndex = progress.snappedShapeIndex.coerceIn(0, geometry.lastIndex)
        /**
         * Absolute distance-along-route (from [startIndex]) at which the next arrow is due.
         * Starting past the near cut-off so an arrow never renders under the driver's chin.
         */
        var nextArrowAt = config.nearCutoffMeters
        var travelled = 0.0
        var id = 0
        val nodes = ArrayList<ARArrowNode>(cap)

        for (i in startIndex until geometry.lastIndex) {
            val a = geometry[i]
            val b = geometry[i + 1]
            val legLength = metersBetween(a, b)

            // Degenerate segment (duplicate or near-duplicate points are common in real polyline
            // data): accumulate distance but never place an arrow or derive a tangent from it.
            if (legLength <= DEGENERATE_SEGMENT_METERS) continue

            if (travelled + legLength >= nextArrowAt) {
                // Never emit past the visibility window. Checked *before* placing, because the
                // spacing budget can overshoot `maxDistanceAheadMeters` within a long segment.
                if (nextArrowAt > config.maxDistanceAheadMeters) return nodes

                // `nextArrowAt` is absolute from `startIndex`, and `travelled` is the distance
                // already walked, so the fraction along THIS segment is the difference. (Adding
                // `travelled` to `nextArrowAt` again — as an earlier revision did — silently
                // doubled every spacing after the first arrow.)
                val t = ((nextArrowAt - travelled) / legLength).coerceIn(0.0, 1.0)
                val position = interpolate(a, b, t)
                val headingRadians = tangentHeading(geometry, i, i + 1)
                val distanceAhead = nextArrowAt
                val approachingManeuver =
                    progress.distanceToNextManeuverMeters in 0.0..config.maneuverLookaheadMeters

                nodes += ARArrowNode(
                    id = id++,
                    worldPosition = position,
                    headingRadians = headingRadians,
                    distanceAheadMeters = distanceAhead,
                    // Always true here: an arrow is never emitted inside the near cut-off.
                    visibility = true,
                    scale = scaleAt(distanceAhead),
                    isApproachingManeuver = approachingManeuver,
                )
                if (nodes.size >= cap) return nodes

                // Advance to the next due arrow, spacing evaluated at the arrow just placed so the
                // ramp from near to far spacing is continuous rather than snapping.
                nextArrowAt += spacingAt(
                    distanceAheadMeters = distanceAhead,
                    approachingManeuver = approachingManeuver,
                    nearDestination = remainingDistanceMeters <= config.arrivalThinningDistanceMeters,
                )
            }
            travelled += legLength
            if (travelled > config.maxDistanceAheadMeters) break
        }
        return nodes
    }

    /**
     * Spacing at a given distance ahead.
     *
     * Blends linearly from [ARArrowConfig.spacingNearMeters] up close to
     * [ARArrowConfig.spacingFarMeters] at the far end of the window, then tightens to
     * [ARArrowConfig.spacingNearManeuverMeters] when a maneuver is imminent and widens again
     * approaching the destination.
     */
    internal fun spacingAt(
        distanceAheadMeters: Double,
        approachingManeuver: Boolean,
        nearDestination: Boolean,
    ): Double {
        // Blend factor 0 near -> 1 far across the sampling window.
        val far = (distanceAheadMeters / config.maxDistanceAheadMeters).coerceIn(0.0, 1.0)
        var spacing = config.spacingNearMeters + (config.spacingFarMeters - config.spacingNearMeters) * far
        if (approachingManeuver) spacing = min(spacing, config.spacingNearManeuverMeters)
        if (nearDestination) spacing = max(spacing, config.spacingFarMeters)
        return spacing
    }

    /**
     * Direction of travel along the route at a segment, in radians clockwise from north.
     *
     * Derived from the **route geometry**, never from the device heading: the arrow must point the
     * way the car is going to go, which is not the way the phone is currently pointed.
     *
     * Uses the segment itself and deliberately does *not* average it with neighbouring points. An
     * earlier revision widened the tangent one point either side to smooth jitter, which quietly
     * halved a 90-degree turn to a 45-degree diagonal — the worst possible place to be imprecise,
     * since a turn is the only moment the arrow's direction is load-bearing. Polyline vertices are
     * dense enough that the segment alone is already stable.
     *
     * Degenerate (duplicate) points carry the *previous* usable segment's bearing rather than
     * defaulting to north, so a duplicated vertex cannot spin an arrow to the wrong way.
     */
    internal fun tangentHeading(geometry: List<Coordinate>, fromIndex: Int, toIndex: Int): Double {
        if (geometry.isEmpty()) return 0.0
        // Clamp into range first: the caller may hand us indexes from a stale progress value.
        var segFrom = fromIndex.coerceIn(0, geometry.lastIndex)
        var segTo = toIndex.coerceIn(0, geometry.lastIndex)
        // Walk back to the nearest segment with real length if this one is degenerate.
        while (segFrom > 0 && lengthOf(geometry[segFrom], geometry[segTo]) <= DEGENERATE_SEGMENT_METERS) {
            segFrom -= 1
            segTo -= 1
        }
        val a = geometry[segFrom]
        val b = geometry[segTo]

        val dEast = metersPerDegreeLon(a.latitude) * (b.longitude - a.longitude)
        val dNorth = METERS_PER_DEGREE * (b.latitude - a.latitude)
        if (hypot(dEast, dNorth) <= DEGENERATE_SEGMENT_METERS) return 0.0
        return Math.toDegrees(Math.atan2(dEast, dNorth)) * ARArrowConfig.DEG_TO_RAD
    }

    private fun lengthOf(a: Coordinate, b: Coordinate): Double = hypot(
        metersPerDegreeLon(a.latitude) * (b.longitude - a.longitude),
        METERS_PER_DEGREE * (b.latitude - a.latitude),
    )

    /** Distance-aware scale: readable up close, tapering with range but never vanishing. */
    internal fun scaleAt(distanceAheadMeters: Double): Float {
        val raw = (config.baseRadiusPx * (config.scaleReferenceDistanceMeters / max(distanceAheadMeters, 1.0))).toFloat()
        return raw.coerceIn(config.minRadiusPx, config.maxRadiusPx)
    }

    private fun interpolate(a: Coordinate, b: Coordinate, t: Double) =
        Coordinate(
            latitude = a.latitude + (b.latitude - a.latitude) * t,
            longitude = a.longitude + (b.longitude - a.longitude) * t,
        )

    private fun metersPerDegreeLon(latitude: Double) = METERS_PER_DEGREE * Math.cos(Math.toRadians(latitude))

    private fun metersBetween(a: Coordinate, b: Coordinate): Double {
        val midLat = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val dLat = (b.latitude - a.latitude) * METERS_PER_DEGREE
        val dLon = (b.longitude - a.longitude) * METERS_PER_DEGREE * Math.cos(midLat)
        return hypot(dLat, dLon)
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0

        /**
         * Segments shorter than this are treated as degenerate. Real polyline data contains repeated
         * or near-repeated points; without this they would produce a zero-length tangent and an
         * arrow pointing at an arbitrary angle.
         */
        const val DEGENERATE_SEGMENT_METERS = 0.05
    }
}