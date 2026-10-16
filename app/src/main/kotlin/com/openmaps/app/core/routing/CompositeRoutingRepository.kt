package com.openmaps.app.core.routing

/**
 * Production route provider failover.
 *
 * The OpenMaps backend/commercial provider is first. The existing Valhalla route engine remains a
 * last-resort fallback until OpenMaps owns or contracts a second production-grade provider.
 */
class CompositeRoutingRepository(
    private val providers: List<RoutingRepository>,
) : RoutingRepository {

    init {
        require(providers.isNotEmpty()) { "At least one routing provider is required" }
    }

    override suspend fun route(origin: Coordinate, destination: Coordinate): Result<RouteResult> {
        var lastFailure: Throwable = RoutingException.NoRouteFound
        for (provider in providers) {
            val result = provider.route(origin, destination)
            if (result.isSuccess) return result
            lastFailure = result.exceptionOrNull() ?: lastFailure
        }
        return Result.failure(lastFailure)
    }
}
