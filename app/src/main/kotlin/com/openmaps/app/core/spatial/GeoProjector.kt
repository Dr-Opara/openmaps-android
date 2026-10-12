package com.openmaps.app.core.spatial

import com.openmaps.app.core.routing.Coordinate
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * The **projection seam** between a geographic world point and the Live camera's screen — the
 * pipeline Phase 7+ needs for road-anchored arrows:
 *
 * ```
 * geographic route point -> world offset (E/N relative to user) -> camera view -> screen (x, y)
 * ```
 *
 * Phase 5 deliberately declined to add this "to avoid speculative API"; Phase 6 adds it because it
 * now has a real, measured input: an ARCore pose with a known heading and accuracy. The seam is
 * therefore not speculative — every value it needs exists and is asserted in tests.
 *
 * **Why not use ARCore's own projection here.** ARCore can project via `Frame.camera.getViewMatrix()`
 * / `getProjectionMatrix()` on the GL thread, which is the correct choice once there is a GL scene
 * to render into. That is Phase 7's concern. Phase 6 needs a *CPU-side, testable* transform that
 * says "a point 20 m ahead, 3 m to the left, with the device facing north, lands here" — which is
 * exactly what this computes, with no GL and no ARCore dependency.
 *
 * All inputs are plain doubles and the whole thing is pure, so the projection can be unit-tested
 * exhaustively on the JVM — the part of AR that is hardest to debug on a real device.
 */
object GeoProjector {

    /**
     * Result of projecting a world point into the camera's view. [visible] is false when the point
     * is behind the device; a caller must not draw an arrow for something off-screen behind you.
     */
    data class ScreenPoint(
        val x: Float,
        val y: Float,
        val distanceMeters: Double,
        val bearingDegrees: Double,
        val visible: Boolean,
        /** True when the point projects inside the viewport. */
        val onScreen: Boolean,
    )

    /**
     * @param world the geographic point to project (a route point, in Phase 7).
     * @param userPose the device's current AR pose (position + device-facing heading).
     * @param viewportWidthPx / heightPx output surface size.
     * @param horizontalFovDegrees the camera's horizontal field of view. 60 is a reasonable rear
     *   phone-camera default and is a parameter (not a hidden constant) because real devices vary
     *   and Phase 7 will read it from ARCore's projection matrix instead.
     */
    fun project(
        world: Coordinate,
        userPose: SpatialPose,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        horizontalFovDegrees: Double = DEFAULT_HORIZONTAL_FOV_DEGREES,
    ): ScreenPoint? {
        val offset = enuOffsetMeters(world, userPose.latitude, userPose.longitude)
        val east = offset.first
        val north = offset.second
        val distance = sqrt(east * east + north * north)

        // Bearing from the device to the point, clockwise from north.
        val bearing = normalizeHeadingDegrees(Math.toDegrees(atan2(east, north)))
        // Rotate the world into the device's frame: how far off-axis, relative to where it points.
        val relative = headingDeltaDegrees(userPose.headingDegrees, bearing)

        val visible = abs(relative) <= HALF_HFOV_DEGREES
        if (!visible) {
            return ScreenPoint(x = 0f, y = 0f, distanceMeters = distance, bearingDegrees = bearing, visible = false, onScreen = false)
        }

        // Normalized device coords: x to the right (positive = right of heading), z forward.
        val focalLengthPx = if (viewportWidthPx > 0) {
            (viewportWidthPx / 2.0) / tan(horizontalFovDegrees * PI / 360.0)
        } else {
            return null
        }
        val forward = distance * cos(relative * PI / 180.0)
        val right = distance * sin(relative * PI / 180.0)

        if (forward <= MIN_FORWARD_METERS) {
            // At or behind the camera plane; cannot be rendered meaningfully.
            return ScreenPoint(0f, 0f, distance, bearing, visible = false, onScreen = false)
        }

        val x = viewportWidthPx / 2.0 + (right / forward) * focalLengthPx
        val y = viewportHeightPx / 2.0
        val onScreen = x >= 0 && x <= viewportWidthPx

        return ScreenPoint(
            x = x.toFloat(),
            y = y.toFloat(),
            distanceMeters = distance,
            bearingDegrees = bearing,
            visible = true,
            onScreen = onScreen,
        )
    }

    /**
     * East/north offset in metres from the user's position to [world].
     *
     * Uses a local flat-earth (equirectangular) approximation about the user, the same projection
     * `RouteProgressTracker` and `NavigationCamera` already use, so all three agree to well under a
     * metre at navigation ranges and a route point never disagrees with itself depending on which
     * layer asked.
     */
    fun enuOffsetMeters(
        world: Coordinate,
        userLatitude: Double,
        userLongitude: Double,
    ): Pair<Double, Double> { // (east, north) metres
        val metersPerDegreeLat = METERS_PER_DEGREE
        val metersPerDegreeLon = metersPerDegreeLat * cos(Math.toRadians(userLatitude))
        val north = (world.latitude - userLatitude) * metersPerDegreeLat
        val east = (world.longitude - userLongitude) * metersPerDegreeLon
        return east to north
    }

    const val DEFAULT_HORIZONTAL_FOV_DEGREES = 60.0
    private const val HALF_HFOV_DEGREES = 90.0
    private const val MIN_FORWARD_METERS = 0.5
    private const val METERS_PER_DEGREE = 111_320.0
}