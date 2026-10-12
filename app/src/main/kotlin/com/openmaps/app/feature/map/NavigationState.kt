package com.openmaps.app.feature.map

import com.openmaps.app.core.navigation.RouteProgress

/**
 * Turn-by-turn navigation lifecycle for the active route, mirroring [RouteState]'s
 * one-field-not-five approach: a single sealed value instead of scattered nullable flags.
 *
 *  - [Idle]: no route to navigate, or navigation was stopped.
 *  - [Ready]: a route exists and a location fix is available, so "Start Navigation" is offered.
 *  - [Active]: navigating normally, snapped to the route.
 *  - [OffRoute]: confirmed (multi-sample) deviation from the route; no reroute in flight yet.
 *  - [Rerouting]: a replacement route has been requested after persistent deviation.
 *  - [Arrived]: the destination maneuver has been reached.
 *  - [Error]: navigation hit a terminal condition it cannot recover from on its own (distinct
 *    from [RouteState.Error], which is about the initial route request, not an in-progress trip).
 *
 * A *failed* reroute attempt deliberately does **not** produce [Error]: the old route stays
 * active (navigationState reverts to [OffRoute]) and a one-time [MapUiEvent.RerouteFailed] is
 * sent instead, so a transient network blip during navigation doesn't dead-end the trip.
 */
sealed interface NavigationState {
    data object Idle : NavigationState
    data object Ready : NavigationState
    data class Active(val progress: RouteProgress) : NavigationState
    data class OffRoute(val progress: RouteProgress) : NavigationState
    data object Rerouting : NavigationState
    data class Arrived(val progress: RouteProgress) : NavigationState
    data class Error(val reason: NavigationError) : NavigationState
}

enum class NavigationError {
    LocationUnavailable,
    RouteUnavailable,
}
