package com.openmaps.app.core.routing

/** Best-effort operational signal that the degraded route provider actually served a route. */
fun interface RoutingFallbackReporter {
    suspend fun reportFallbackUsed()
}

object NoopRoutingFallbackReporter : RoutingFallbackReporter {
    override suspend fun reportFallbackUsed() = Unit
}
