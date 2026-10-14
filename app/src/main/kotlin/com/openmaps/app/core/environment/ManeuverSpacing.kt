package com.openmaps.app.core.environment

import com.openmaps.app.core.routing.RouteManeuver

/**
 * The average distance between consecutive maneuvers on a route — one of the real signals
 * [EnvironmentClassifier] uses (section B1: "maneuver spacing"). [RouteManeuver.distanceMeters] is
 * already "how far was travelled before this maneuver's instruction fires" (see
 * `ValhallaResponseParser`), so the mean of that field across a route's maneuvers *is* the average
 * maneuver spacing — no route-geometry walk needed, and nothing invented.
 */
object ManeuverSpacing {
    /** Null when there is no usable maneuver with a real (non-zero) segment length. */
    fun averageSpacingMeters(maneuvers: List<RouteManeuver>): Double? {
        val usable = maneuvers.filter { it.distanceMeters > 0.0 }
        if (usable.isEmpty()) return null
        return usable.sumOf { it.distanceMeters } / usable.size
    }
}
