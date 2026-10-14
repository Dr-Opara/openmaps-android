package com.openmaps.app.core.roadintelligence

/**
 * Everything Map/Live road intelligence renders, as one value — mirrors how
 * [com.openmaps.app.feature.map.MapUiState] bundles `placesState`/`visiblePlaces` rather than
 * scattering nullable fields. Held by `MapUiState.roadIntelligenceState` and read by both Map and
 * Live, exactly like `visiblePlaces` already is (see `LivePlaceProjector`'s precedent).
 *
 * Every field here is additive context: a navigation-critical code path must never branch on
 * whether any of these are present (section 18 of the Phase 10 brief).
 */
data class RoadIntelligenceState(
    /** Hazards/incidents matched to the active route, nearest first. Empty when none or no route. */
    val events: List<RoadEvent> = emptyList(),
    val traffic: TrafficCondition? = null,
    val weather: WeatherCondition? = null,
    /** Always null today — see [LaneGuidance]'s doc comment for why. Kept for the Phase 11 contract. */
    val laneGuidance: LaneGuidance? = null,
    val speedLimit: SpeedLimitInfo? = null,
    val currentSpeedMetersPerSecond: Float? = null,
    /**
     * Deterministically prioritized ([RoadAlertPriority]), distance-filtered and
     * hysteresis-stabilized ([RoadAlertActivation], [RoadAlertHysteresis]) — the list a renderer
     * should actually show, already capped. Never recomputed per-frame; see `MapViewModel`'s
     * "Road intelligence" region for when it refreshes.
     */
    val activeAlerts: List<RouteAlert> = emptyList(),
)
