package com.openmaps.app.feature.map

import com.openmaps.app.core.routing.RouteResult

/**
 * The map's route lifecycle as one value, not scattered nullable fields: a [Ready] always
 * carries its [RouteResult], so "ready but no geometry" can't happen. Mirrors how
 * `LocationPrompt`/`SearchError` keep the UI-facing shape separate from provider exceptions.
 */
sealed interface RouteState {
    data object Idle : RouteState
    data object WaitingForLocation : RouteState
    data object Loading : RouteState
    data class Ready(val route: RouteResult) : RouteState
    data class Error(val reason: RouteError) : RouteState
}

/** User-facing routing failure categories. No raw HTTP/IO details reach the UI. */
enum class RouteError {
    NoNetwork,
    Timeout,
    ServerError,
    Malformed,
    NoRouteFound,
}
