package com.openmaps.app.core.navigation

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.RouteManeuver

/**
 * A snapshot of where a user is along a route, computed by [RouteProgressTracker]. Wrapped
 * directly by [com.openmaps.app.feature.map.NavigationState] cases, mirroring how
 * [com.openmaps.app.feature.map.RouteState.Ready] wraps `RouteResult` directly rather than
 * re-flattening it into a second near-identical type.
 */
enum class MapMatchConfidence {
    High,
    Medium,
    Low,
    OffRoute,
}

data class RouteProgress(
    val snappedShapeIndex: Int,
    /** Nearest point on the matched road segment, not the raw GPS sample. */
    val snappedCoordinate: Coordinate? = null,
    /** Confidence derived from lateral distance and heading agreement. */
    val mapMatchConfidence: MapMatchConfidence = MapMatchConfidence.Low,
    val lateralDistanceMeters: Double,
    val distanceTraveledMeters: Double,
    val remainingDistanceMeters: Double,
    val remainingDurationSeconds: Double,
    val progressFraction: Double,
    val currentManeuver: RouteManeuver,
    val nextManeuver: RouteManeuver?,
    val distanceToNextManeuverMeters: Double,
    val isOffRoute: Boolean,
) {
    /**
     * True once the driver has effectively reached the destination.
     *
     * Requires *both* a near-zero remaining distance and a nearly-complete progress fraction
     * rather than either alone: the tracker clamps progress to never regress, so a stale or
     * badly-matched segment can momentarily report ~0 remaining distance while the user is still
     * far from the end. Requiring both means arriving only happens where the two independent
     * measures agree.
     */
    val hasArrived: Boolean
        get() = remainingDistanceMeters <= ARRIVAL_DISTANCE_METERS &&
            progressFraction >= ARRIVAL_PROGRESS_FRACTION

    private companion object {
        /**
         * ~10 m of the route left. Tight enough that the arrival card does not appear while the
         * driver is still visibly driving toward the destination.
         */
        const val ARRIVAL_DISTANCE_METERS = 10.0

        /**
         * 99% of the route driven. Slightly stricter than the distance check on purpose: for a long
         * route, "99% of total distance" is hundreds of metres, so this is the binding constraint
         * there, and it keeps arrival from firing on a short route where the distance threshold alone
         * would be satisfied far too early in the trip.
         */
        const val ARRIVAL_PROGRESS_FRACTION = 0.99
    }
}
