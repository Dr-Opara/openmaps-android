package com.openmaps.app.map

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Decides how the navigation follow camera should travel from where it is to the next target.
 *
 * Pure and JVM-testable. The follow target arrives once per GPS fix, and fixes are neither evenly
 * spaced nor always sensible, so a fixed animation length is wrong in both directions: too short
 * and the camera stops and starts (stutter), too long and it lags behind the vehicle. The policy:
 *
 *  - **Glide linearly for about one fix interval**, measured from the previous fix and clamped to
 *    [MIN_GLIDE_MILLIS]..[MAX_GLIDE_MILLIS]. A fix that arrives early cancels and replaces the
 *    running glide, which MapLibre starts from the camera's *current* position, so there is no jump.
 *  - **Snap, never glide, across a discontinuity**: the first step, a long gap with no fixes
 *    (tunnel, app backgrounded), a target farther than [TELEPORT_METERS] (reroute, GPS jump), or a
 *    large zoom change. Gliding 400 m in one second would read as a violent fly-over.
 */
object FollowCameraMotion {
    data class Motion(val animated: Boolean, val durationMillis: Int, val linear: Boolean)

    fun plan(previous: CameraState?, target: CameraState, millisSinceLastStep: Long?): Motion {
        if (previous == null || millisSinceLastStep == null) return SNAP
        if (millisSinceLastStep > GAP_SNAP_MILLIS || millisSinceLastStep < 0) return SNAP
        if (distanceMeters(previous, target) > TELEPORT_METERS) return SNAP
        if (abs(previous.zoom - target.zoom) > MAX_GLIDE_ZOOM_DELTA) return SNAP
        val duration = millisSinceLastStep.coerceIn(MIN_GLIDE_MILLIS, MAX_GLIDE_MILLIS).toInt()
        return Motion(animated = true, durationMillis = duration, linear = true)
    }

    val SNAP = Motion(animated = false, durationMillis = 0, linear = false)

    const val MIN_GLIDE_MILLIS = 400L
    const val MAX_GLIDE_MILLIS = 1500L
    const val GAP_SNAP_MILLIS = 4000L
    const val TELEPORT_METERS = 250.0
    const val MAX_GLIDE_ZOOM_DELTA = 1.5

    private fun distanceMeters(a: CameraState, b: CameraState): Double {
        val r = 6_371_000.0
        val dLat = Math.toRadians(b.latitude - a.latitude)
        val dLon = Math.toRadians(b.longitude - a.longitude)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.latitude)) * cos(Math.toRadians(b.latitude)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * r * atan2(sqrt(h), sqrt(1 - h))
    }
}
