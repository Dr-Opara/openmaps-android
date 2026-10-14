package com.openmaps.app.core.environment

import com.openmaps.app.core.arrow.ARArrowConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EnvironmentNavigationProfileTest {

    @Test
    fun forEnvironment_dispatchesToTheMatchingProfile() {
        assertEquals(EnvironmentProfiles.city, EnvironmentProfiles.forEnvironment(DrivingEnvironment.City))
        assertEquals(EnvironmentProfiles.highway, EnvironmentProfiles.forEnvironment(DrivingEnvironment.Highway))
        assertEquals(EnvironmentProfiles.suburb, EnvironmentProfiles.forEnvironment(DrivingEnvironment.Suburb))
        assertEquals(EnvironmentProfiles.rural, EnvironmentProfiles.forEnvironment(DrivingEnvironment.Rural))
        assertEquals(EnvironmentProfiles.unknown, EnvironmentProfiles.forEnvironment(DrivingEnvironment.Unknown))
    }

    @Test
    fun unknownProfile_reproducesThePrePhase11ArArrowConfigDefaults() {
        val default = ARArrowConfig()
        val unknownArrowConfig = EnvironmentProfiles.unknown.arrowConfig()
        assertEquals(default.maxDistanceAheadMeters, unknownArrowConfig.maxDistanceAheadMeters, 1e-9)
        assertEquals(default.maxVisibleArrows, unknownArrowConfig.maxVisibleArrows)
        assertEquals(default.spacingFarMeters, unknownArrowConfig.spacingFarMeters, 1e-9)
        assertEquals(default.spacingNearMeters, unknownArrowConfig.spacingNearMeters, 1e-9)
        assertEquals(default.maneuverLookaheadMeters, unknownArrowConfig.maneuverLookaheadMeters, 1e-9)
    }

    @Test
    fun unknownProfile_disablesEveryEnvironmentOnlyBehavior() {
        val unknown = EnvironmentProfiles.unknown
        assertEquals(1.0, unknown.utilityPlaceWeightMultiplier, 1e-9)
        assertEquals(1.0, unknown.generalPlaceWeightMultiplier, 1e-9)
        assertEquals(false, unknown.laneGuidancePriorityBoost)
        assertEquals(false, unknown.speedEmphasis)
    }

    @Test
    fun arrowConfig_carriesTheProfilesSpacingAndLookAhead() {
        val config = EnvironmentProfiles.highway.arrowConfig()
        assertEquals(EnvironmentProfiles.highway.arrowLookAheadMeters, config.maxDistanceAheadMeters, 1e-9)
        assertEquals(EnvironmentProfiles.highway.maxArrowCount, config.maxVisibleArrows)
        assertEquals(EnvironmentProfiles.highway.arrowFarSpacingMeters, config.spacingFarMeters, 1e-9)
        assertEquals(EnvironmentProfiles.highway.arrowNearSpacingMeters, config.spacingNearMeters, 1e-9)
        assertEquals(EnvironmentProfiles.highway.maneuverLeadDistanceMeters, config.maneuverLookaheadMeters, 1e-9)
    }

    @Test
    fun everyProfilesArrowLookAhead_staysWithinTheExistingAnchorWindow() {
        // `RouteAnchorConfig.aheadWindowMeters` defaults to 160 m and Phase 11 deliberately does not
        // touch Phase 7/8's anchor lifecycle/window sizing — an arrow look-ahead wider than that
        // would silently fall back to degraded (non-anchored) placement beyond 160 m.
        val anchorWindowMeters = 160.0
        listOf(
            EnvironmentProfiles.unknown,
            EnvironmentProfiles.city,
            EnvironmentProfiles.highway,
            EnvironmentProfiles.suburb,
            EnvironmentProfiles.rural,
        ).forEach { profile ->
            assertTrue(
                "${profile.environment} look-ahead (${profile.arrowLookAheadMeters}) must stay within the anchor window",
                profile.arrowLookAheadMeters <= anchorWindowMeters,
            )
        }
    }

    @Test
    fun highwayProfile_minimizesPoiClutter() {
        val highway = EnvironmentProfiles.highway
        val city = EnvironmentProfiles.city
        assertTrue(highway.liveMaxPoiLabels < city.liveMaxPoiLabels)
        assertTrue(highway.poiLimitNavigating < city.poiLimitNavigating)
        assertTrue(highway.utilityPlaceWeightMultiplier > highway.generalPlaceWeightMultiplier)
    }

    @Test
    fun highwayProfile_elevatesLaneAndSpeedPriority() {
        assertTrue(EnvironmentProfiles.highway.laneGuidancePriorityBoost)
        assertTrue(EnvironmentProfiles.highway.speedEmphasis)
    }

    @Test
    fun highwayProfile_activatesHazardsEarlierThanCity() {
        val highway = EnvironmentProfiles.highway.hazardActivationConfig
        val city = EnvironmentProfiles.city.hazardActivationConfig
        assertTrue(highway.collisionClassApproachMeters > city.collisionClassApproachMeters)
    }

    @Test
    fun ruralProfile_hasFewerWiderSpacedArrowsThanCity() {
        val rural = EnvironmentProfiles.rural
        val city = EnvironmentProfiles.city
        assertTrue(rural.maxArrowCount < city.maxArrowCount)
        assertTrue(rural.arrowFarSpacingMeters > city.arrowFarSpacingMeters)
        assertTrue(rural.poiLimitNavigating < city.poiLimitNavigating)
    }

    @Test
    fun cityProfile_hasShorterArrowLookAheadThanSuburb() {
        assertTrue(EnvironmentProfiles.city.arrowLookAheadMeters < EnvironmentProfiles.suburb.arrowLookAheadMeters)
    }
}
