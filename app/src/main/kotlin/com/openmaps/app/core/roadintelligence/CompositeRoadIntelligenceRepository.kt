package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.Coordinate

/**
 * Merges live/commercial and fallback road-intelligence providers without making navigation depend
 * on any one feed. Live incidents can coexist with static OSM construction/hazard data.
 */
class CompositeRoadIntelligenceRepository(
    private val providers: List<RoadIntelligenceRepository>,
) : RoadIntelligenceRepository {

    init {
        require(providers.isNotEmpty()) { "At least one road-intelligence provider is required" }
    }

    override suspend fun roadEventsAlongRoute(
        routeGeometry: List<Coordinate>,
        corridorMeters: Double,
    ): Result<List<RoadEvent>> {
        val merged = mutableListOf<RoadEvent>()
        var lastFailure: Throwable? = null
        var anySuccess = false

        providers.forEach { provider ->
            provider.roadEventsAlongRoute(routeGeometry, corridorMeters).fold(
                onSuccess = {
                    anySuccess = true
                    merged += it
                },
                onFailure = { lastFailure = it },
            )
        }

        return when {
            anySuccess -> Result.success(RoadEventDeduplicator.dedupe(merged).sortedBy { it.routeDistanceMeters ?: Double.MAX_VALUE })
            lastFailure != null -> Result.failure(lastFailure!!)
            else -> Result.success(emptyList())
        }
    }

    override suspend fun speedLimitNear(location: Coordinate): Result<SpeedLimitInfo?> =
        firstNonNull { it.speedLimitNear(location) }

    override suspend fun weatherNear(location: Coordinate): Result<WeatherCondition?> =
        firstNonNull { it.weatherNear(location) }

    private suspend fun <T> firstNonNull(
        call: suspend (RoadIntelligenceRepository) -> Result<T?>,
    ): Result<T?> {
        var lastFailure: Throwable? = null
        var hadSuccess = false
        providers.forEach { provider ->
            call(provider).fold(
                onSuccess = { value ->
                    hadSuccess = true
                    if (value != null) return Result.success(value)
                },
                onFailure = { lastFailure = it },
            )
        }
        return when {
            hadSuccess -> Result.success(null)
            lastFailure != null -> Result.failure(lastFailure!!)
            else -> Result.success(null)
        }
    }
}
