package com.openmaps.app.core.reliability

enum class ReliabilityEventName(val wireName: String) {
    AppLaunch("app_launch"),
    PreviousCrash("previous_crash"),
    RouteRequest("route_request"),
    RouteReady("route_ready"),
    RouteFailed("route_failed"),
    NavigationStarted("navigation_started"),
    NavigationStartFailed("navigation_start_failed"),
    OffRouteConfirmed("off_route_confirmed"),
    RerouteTriggered("reroute_triggered"),
    RerouteSucceeded("reroute_succeeded"),
    RerouteFailed("reroute_failed"),
    Arrived("arrived"),
    NavigationEnded("navigation_ended"),
    ArAvailability("ar_availability"),
    ArReady("ar_ready"),
    ArFallback("ar_fallback"),
    CameraFailure("camera_failure"),
}

enum class ReliabilityOutcome(val wireName: String) {
    Info("info"),
    Success("success"),
    Error("error"),
    Degraded("degraded"),
}

data class ReliabilityEvent(
    val name: ReliabilityEventName,
    val outcome: ReliabilityOutcome = ReliabilityOutcome.Info,
    val reason: String? = null,
    val durationMillis: Long? = null,
    val metadata: Map<String, String> = emptyMap(),
)

fun interface ReliabilityReporter {
    fun record(event: ReliabilityEvent)
}

object NoopReliabilityReporter : ReliabilityReporter {
    override fun record(event: ReliabilityEvent) = Unit
}
