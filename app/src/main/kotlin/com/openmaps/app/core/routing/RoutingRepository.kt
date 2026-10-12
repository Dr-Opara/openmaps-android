package com.openmaps.app.core.routing

/**
 * Converts an origin/destination pair into a route. Implementations must:
 *  - never throw: failures come back as `Result.failure` carrying a [RoutingException];
 *  - return `Result.failure(RoutingException.NoRouteFound)` when the provider found no path,
 *    not an empty success.
 *
 * Replaceable: nothing outside this package and its single implementation knows which
 * provider is behind it.
 */
interface RoutingRepository {
    suspend fun route(origin: Coordinate, destination: Coordinate): Result<RouteResult>
}
