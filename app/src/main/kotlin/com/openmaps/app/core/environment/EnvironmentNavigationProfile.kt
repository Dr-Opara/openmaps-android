package com.openmaps.app.core.environment

import com.openmaps.app.core.arrow.ARArrowConfig
import com.openmaps.app.core.roadintelligence.RoadAlertActivationConfig

/**
 * Centralized per-environment tuning (Phase 11, section D: "do not scatter `if (city)`/`if
 * (highway)` throughout the codebase"). One profile is the single source of truth for every
 * environment-dependent number; consumers (the AR arrow pipeline, the navigation camera, Phase 9
 * places, Phase 10 road intelligence, the Live HUD) read fields off whichever profile is active and
 * never branch on [DrivingEnvironment] themselves.
 *
 * [EnvironmentProfiles.unknown] is deliberately set to the exact pre-Phase-11 constants
 * ([ARArrowConfig]'s and `MapViewModel`'s own previous hardcoded defaults): when classification
 * cannot resolve, behavior must be identical to what Phases 1-10 already shipped, never a new,
 * untested combination — section B6's "never fail navigation because classification is uncertain."
 */
data class EnvironmentNavigationProfile(
    val environment: DrivingEnvironment,

    // Arrow density (Part I: ARArrowSampler/ARArrowPipeline tuning only — anchor lifecycle untouched).
    val maneuverLeadDistanceMeters: Double,
    val arrowNearSpacingMeters: Double,
    val arrowFarSpacingMeters: Double,
    val arrowLookAheadMeters: Double,
    val maxArrowCount: Int,

    // Places (Part L).
    val poiLimitBrowsing: Int,
    val poiLimitNavigating: Int,
    val liveMaxPoiLabels: Int,
    /** Multiplies the Phase 9 utility (parking/gas/EV) ranking weight; 1.0 = unchanged Phase 9 behavior. */
    val utilityPlaceWeightMultiplier: Double,
    /** Multiplies the Phase 9 general-category ranking weight; 1.0 = unchanged Phase 9 behavior. */
    val generalPlaceWeightMultiplier: Double,

    // Road intelligence (Part K): presentation thresholds only — provider truth is never changed.
    val hazardActivationConfig: RoadAlertActivationConfig,
    /** When true, provider-backed lane guidance ranks above lower-priority road context on highways. */
    val laneGuidancePriorityBoost: Boolean,
    /** When true, Live's speed line renders above the hazard/weather line instead of below it. */
    val speedEmphasis: Boolean,

    // Map camera (Part J). Manual pan is untouched — these only affect the follow target.
    val navigationCameraZoom: Double,
    val navigationCameraLookAheadMeters: Double,
) {
    /** [ARArrowConfig] for this environment — only the fields section I calls out are overridden. */
    fun arrowConfig(): ARArrowConfig = ARArrowConfig(
        maxDistanceAheadMeters = arrowLookAheadMeters,
        maxVisibleArrows = maxArrowCount,
        spacingFarMeters = arrowFarSpacingMeters,
        spacingNearMeters = arrowNearSpacingMeters,
        spacingNearManeuverMeters = minOf(arrowNearSpacingMeters, DEFAULT_SPACING_NEAR_MANEUVER_METERS),
        maneuverLookaheadMeters = maneuverLeadDistanceMeters,
    )

    private companion object {
        const val DEFAULT_SPACING_NEAR_MANEUVER_METERS = 4.0
    }
}

/**
 * The five concrete profiles (section D-H). Values are directional/documented judgment calls (no
 * field tuning data exists yet — same caveat the base [ARArrowConfig] defaults already carry),
 * expressed strictly as multiples/variants of the pre-existing Phase 1-10 defaults so every number
 * has a traceable baseline.
 */
object EnvironmentProfiles {

    /** Pre-Phase-11 behavior, unchanged. The safe fallback for [DrivingEnvironment.Unknown]. */
    val unknown = EnvironmentNavigationProfile(
        environment = DrivingEnvironment.Unknown,
        maneuverLeadDistanceMeters = 25.0,
        arrowNearSpacingMeters = 6.0,
        arrowFarSpacingMeters = 14.0,
        arrowLookAheadMeters = 120.0,
        maxArrowCount = 12,
        poiLimitBrowsing = 40,
        poiLimitNavigating = 20,
        liveMaxPoiLabels = 3,
        utilityPlaceWeightMultiplier = 1.0,
        generalPlaceWeightMultiplier = 1.0,
        hazardActivationConfig = RoadAlertActivationConfig(),
        laneGuidancePriorityBoost = false,
        speedEmphasis = false,
        navigationCameraZoom = 16.5,
        navigationCameraLookAheadMeters = 110.0,
    )

    /** Dense grid, frequent turns: tighter arrows, shorter look-ahead, turns unmistakable, closer camera. */
    val city = EnvironmentNavigationProfile(
        environment = DrivingEnvironment.City,
        maneuverLeadDistanceMeters = 35.0,
        arrowNearSpacingMeters = 4.0,
        arrowFarSpacingMeters = 10.0,
        arrowLookAheadMeters = 90.0,
        maxArrowCount = 14,
        poiLimitBrowsing = 40,
        poiLimitNavigating = 24,
        liveMaxPoiLabels = 3,
        utilityPlaceWeightMultiplier = 1.1,
        generalPlaceWeightMultiplier = 1.1,
        hazardActivationConfig = RoadAlertActivationConfig(
            collisionClassApproachMeters = 600.0,
            informationalApproachMeters = 300.0,
            closureProminenceMeters = 2_000.0,
        ),
        laneGuidancePriorityBoost = false,
        speedEmphasis = false,
        navigationCameraZoom = 17.0,
        navigationCameraLookAheadMeters = 80.0,
    )

    /** Speed and early decisions: longer look-ahead/lead, lane and speed elevated, POIs cut hard. */
    val highway = EnvironmentNavigationProfile(
        environment = DrivingEnvironment.Highway,
        maneuverLeadDistanceMeters = 60.0,
        arrowNearSpacingMeters = 10.0,
        arrowFarSpacingMeters = 20.0,
        arrowLookAheadMeters = 150.0,
        maxArrowCount = 10,
        poiLimitBrowsing = 20,
        poiLimitNavigating = 6,
        liveMaxPoiLabels = 0,
        utilityPlaceWeightMultiplier = 1.6,
        generalPlaceWeightMultiplier = 0.3,
        hazardActivationConfig = RoadAlertActivationConfig(
            collisionClassApproachMeters = 1_200.0,
            informationalApproachMeters = 700.0,
            closureProminenceMeters = 5_000.0,
        ),
        laneGuidancePriorityBoost = true,
        speedEmphasis = true,
        navigationCameraZoom = 15.5,
        navigationCameraLookAheadMeters = 180.0,
    )

    /** The balanced middle: moderate everything, closest to the pre-Phase-11 [unknown] baseline. */
    val suburb = EnvironmentNavigationProfile(
        environment = DrivingEnvironment.Suburb,
        maneuverLeadDistanceMeters = 30.0,
        arrowNearSpacingMeters = 6.0,
        arrowFarSpacingMeters = 14.0,
        arrowLookAheadMeters = 120.0,
        maxArrowCount = 12,
        poiLimitBrowsing = 35,
        poiLimitNavigating = 18,
        liveMaxPoiLabels = 2,
        utilityPlaceWeightMultiplier = 1.0,
        generalPlaceWeightMultiplier = 1.0,
        hazardActivationConfig = RoadAlertActivationConfig(),
        laneGuidancePriorityBoost = false,
        speedEmphasis = false,
        navigationCameraZoom = 16.5,
        navigationCameraLookAheadMeters = 110.0,
    )

    /** Sparse and spread out: wider arrow spacing, fewer of them, earlier warnings, utility-first POIs. */
    val rural = EnvironmentNavigationProfile(
        environment = DrivingEnvironment.Rural,
        maneuverLeadDistanceMeters = 50.0,
        arrowNearSpacingMeters = 8.0,
        arrowFarSpacingMeters = 18.0,
        arrowLookAheadMeters = 140.0,
        maxArrowCount = 8,
        poiLimitBrowsing = 15,
        poiLimitNavigating = 8,
        liveMaxPoiLabels = 1,
        utilityPlaceWeightMultiplier = 1.4,
        generalPlaceWeightMultiplier = 0.6,
        hazardActivationConfig = RoadAlertActivationConfig(
            collisionClassApproachMeters = 1_000.0,
            informationalApproachMeters = 600.0,
            closureProminenceMeters = 4_000.0,
        ),
        laneGuidancePriorityBoost = false,
        speedEmphasis = false,
        navigationCameraZoom = 16.0,
        navigationCameraLookAheadMeters = 140.0,
    )

    fun forEnvironment(environment: DrivingEnvironment): EnvironmentNavigationProfile = when (environment) {
        DrivingEnvironment.City -> city
        DrivingEnvironment.Highway -> highway
        DrivingEnvironment.Suburb -> suburb
        DrivingEnvironment.Rural -> rural
        DrivingEnvironment.Unknown -> unknown
    }
}
