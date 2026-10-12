package com.openmaps.app.map

import com.openmaps.app.core.routing.RouteBounds

/**
 * Engine-neutral map types. Feature UI and the ViewModel talk in these;
 * only `MapLibreMapEngine` knows about MapLibre classes.
 */

/** Which top-level experience is showing. */
enum class MapMode { Map, Live }

/** Full camera description: center, zoom, bearing (deg, 0 = north) and pitch (deg, 0 = top-down). */
data class CameraState(
    val latitude: Double,
    val longitude: Double,
    val zoom: Double,
    val bearing: Double = 0.0,
    val pitch: Double = 0.0,
) {
    companion object {
        /**
         * Fallback when no location is available: the whole contiguous United States.
         * (Geographic center of the lower 48; a public landmark, not a personal address.)
         */
        val UnitedStates = CameraState(latitude = 39.8283, longitude = -98.5795, zoom = 3.2)
    }
}

/** Commands from the ViewModel to the map engine. */
sealed interface MapCommand {
    data class MoveCamera(
        val target: CameraState,
        val animated: Boolean = true,
        val durationMillis: Int = 600,
    ) : MapCommand

    /** Reload the style after a failure ("Map unavailable" -> Retry). */
    data object RetryStyle : MapCommand

    /** One-time camera fit to [bounds] (Phase 3: fit the route once it loads). Not repeated after. */
    data class FitBounds(
        val bounds: RouteBounds,
        val paddingPx: Int = 64,
        val durationMillis: Int = 700,
    ) : MapCommand
}

/** Events from the map engine to the ViewModel. */
sealed interface MapEvent {
    /** The style finished loading; the map is drawable. */
    data object StyleLoaded : MapEvent

    /** The style could not be loaded (offline, bad URL, server error...). */
    data class StyleFailed(val message: String?) : MapEvent

    /** The *user* started moving the camera (pan/zoom/rotate/tilt gesture), not code. */
    data object UserGestureStarted : MapEvent

    /** Fired while the camera moves, but only when the bearing changed noticeably. */
    data class BearingChanged(val bearingDegrees: Double) : MapEvent

    /** The camera stopped moving. */
    data class CameraIdle(val camera: CameraState) : MapEvent
}

/** User-visible map failures. */
sealed interface MapError {
    data class Unavailable(val message: String?) : MapError
}
