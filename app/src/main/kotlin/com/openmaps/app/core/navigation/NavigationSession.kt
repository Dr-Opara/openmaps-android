package com.openmaps.app.core.navigation

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.RouteResult

/**
 * All the state that belongs to one navigation trip: the geometry matcher, the off-route
 * hysteresis, and the camera policy for the route being driven.
 *
 * Kept as one object so the Map feature holds a single nullable field instead of three that must
 * be reset together. Replacing the route (a reroute, or picking a new destination) means building a
 * new session — the tracker's remembered segment belongs to the old geometry.
 */
class NavigationSession(
    val route: RouteResult,
    val offRouteDetector: OffRouteDetector = OffRouteDetector(),
) {
    val camera: NavigationCamera = NavigationCamera(route)
    private val tracker: RouteProgressTracker = RouteProgressTracker(route)

    fun locate(position: Coordinate): RouteProgress? = tracker.locate(position)

    /** Feeds one sample through the hysteresis and returns what it implies for this tick. */
    fun updateOffRoute(progress: RouteProgress): OffRouteDecision =
        offRouteDetector.update(progress.lateralDistanceMeters > OFF_ROUTE_THRESHOLD_METERS)

    companion object {
        /**
         * How far off the line counts as off-route. Deliberately conservative: consumer GPS on a
         * phone routinely sits 10-30 m from the true position in urban canyons and under tree cover,
         * so a tighter threshold (the ~10 m a naive implementation might use) would reroute on
         * ordinary noise and cost a real network round trip every time.
         *
         * The sample count in [OffRouteDetector] adds a second, independent guard: a single stray
         * reading can never trigger a reroute no matter how far off it lands.
         */
        const val OFF_ROUTE_THRESHOLD_METERS = 75.0
    }
}