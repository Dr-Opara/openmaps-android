package com.openmaps.app.core.navigation

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.RouteResult
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Where the camera should sit while navigating. Deliberately *not* `map.CameraState`: this is
 * pure route math with no dependency on the map package, so it stays unit-testable without the
 * renderer (the same reasoning that keeps [RouteProgressTracker] renderer-free).
 */
data class NavigationCameraTarget(
    val latitude: Double,
    val longitude: Double,
    val bearingDegrees: Double,
    val zoom: Double,
    val pitch: Double,
)

/**
 * Computes the follow-camera target for one progress update against a fixed [route].
 *
 * A navigation camera is deliberately *not* simply "the user's position": that pins the user to
 * the bottom of the screen with no road visible ahead. Instead the target is a point up the
 * route with the bearing pointing along it, so the next decision point is on screen before the
 * driver reaches it.
 *
 * Bearing resolution, in order of trust:
 *  1. the device's reported heading — most accurate, and the only one that still makes sense when
 *     the driver is off the route but oriented along a nearby road;
 *  2. the route's own direction at the snapped point — a good fallback when the device reports no
 *     heading, which is common indoors and in cars without a magnetometer;
 *  3. [fallbackBearing] — whatever the camera already shows, so one bad sample never spins the view.
 */
class NavigationCamera(
    private val route: RouteResult,
    private val lookAheadMeters: Double = DEFAULT_LOOK_AHEAD_METERS,
) {

    fun targetFor(
        progress: RouteProgress,
        headingDegrees: Double?,
        fallbackBearing: Double = 0.0,
        zoom: Double = DEFAULT_NAVIGATION_ZOOM,
        pitch: Double = DEFAULT_NAVIGATION_PITCH,
    ): NavigationCameraTarget? {
        val geometry = route.geometry
        if (geometry.isEmpty()) return null

        val startIndex = progress.snappedShapeIndex.coerceIn(0, geometry.lastIndex)
        val start = geometry[startIndex]
        val ahead = pointAhead(startIndex, lookAheadMeters)

        val bearing = when {
            headingDegrees != null && headingDegrees.isFinite() -> headingDegrees
            // Far from the route (a pre-reroute excursion) the route's direction says nothing
            // useful, so hold the current bearing instead of swinging the view at the route line.
            progress.lateralDistanceMeters > OFF_ROUTE_CAMERA_BLEND_METERS -> fallbackBearing
            else -> bearingBetween(start, ahead)
        }

        return NavigationCameraTarget(
            latitude = ahead.latitude,
            longitude = ahead.longitude,
            bearingDegrees = normalizeDegrees(bearing),
            zoom = zoom,
            pitch = pitch,
        )
    }

    /**
     * Walks the geometry forward from [startIndex] accumulating up to [distanceMeters], capped at
     * MIN_LOOK_AHEAD_METERS from the end of the route. Without that cap the camera would overshoot
     * the destination and end up pointing past it.
     */
    private fun pointAhead(startIndex: Int, distanceMeters: Double): Coordinate {
        val geometry = route.geometry
        val remainingOnRoute = metersAlongRoute(startIndex)
        val target = minOf(distanceMeters, maxOf(MIN_LOOK_AHEAD_METERS, remainingOnRoute))
        var remaining = target
        var index = startIndex
        while (index < geometry.lastIndex) {
            val leg = metersBetween(geometry[index], geometry[index + 1])
            if (leg >= remaining) {
                val t = if (leg > 0) remaining / leg else 0.0
                return interpolate(geometry[index], geometry[index + 1], t)
            }
            remaining -= leg
            index++
        }
        return geometry.last()
    }

    /** Distance from [startIndex] to the end of the route. */
    private fun metersAlongRoute(startIndex: Int): Double {
        val geometry = route.geometry
        var total = 0.0
        for (i in startIndex until geometry.lastIndex) total += metersBetween(geometry[i], geometry[i + 1])
        return total
    }

    private companion object {
        /**
         * How far up the route to look. Long enough to show the next turn before it is missed on a
         * city street, short enough that a hairpin does not drag the camera around the block.
         */
        const val DEFAULT_LOOK_AHEAD_METERS = 110.0

        /**
         * Floor on the look-ahead when the route is nearly finished, so a stopped driver still
         * sees where they arrived instead of a camera pinned to the destination pin.
         */
        const val MIN_LOOK_AHEAD_METERS = 8.0

        /** Beyond this lateral distance the route's direction says nothing useful about the camera. */
        const val OFF_ROUTE_CAMERA_BLEND_METERS = 250.0

        const val DEFAULT_NAVIGATION_ZOOM = 16.5
        const val DEFAULT_NAVIGATION_PITCH = 55.0
        const val METERS_PER_DEGREE_LATITUDE = 111_320.0

        fun metersBetween(a: Coordinate, b: Coordinate): Double {
            val midLat = Math.toRadians((a.latitude + b.latitude) / 2.0)
            val dLat = (b.latitude - a.latitude) * METERS_PER_DEGREE_LATITUDE
            val dLon = (b.longitude - a.longitude) * METERS_PER_DEGREE_LATITUDE * cos(midLat)
            return sqrt(dLat * dLat + dLon * dLon)
        }

        fun interpolate(a: Coordinate, b: Coordinate, t: Double): Coordinate =
            Coordinate(
                latitude = a.latitude + (b.latitude - a.latitude) * t,
                longitude = a.longitude + (b.longitude - a.longitude) * t,
            )

        /** Initial bearing from [a] to [b], degrees clockwise from north. */
        fun bearingBetween(a: Coordinate, b: Coordinate): Double {
            val lat1 = Math.toRadians(a.latitude)
            val lat2 = Math.toRadians(b.latitude)
            val dLon = Math.toRadians(b.longitude - a.longitude)
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            return Math.toDegrees(atan2(y, x))
        }

        fun normalizeDegrees(degrees: Double): Double = ((degrees % 360.0) + 360.0) % 360.0
    }
}