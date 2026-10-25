package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.ManeuverType
import org.junit.Assert.assertFalse
import org.junit.Test

/** Regression for #87: never advise physical lanes before same-route validation exists. */
class LaneGuidanceSafetyGateTest {
    @Test
    fun rejectsRequestsUntilFieldVerification() {
        assertFalse(LaneGuidanceSafetyGate.canRequest())
    }

    @Test
    fun rejectsNullAndUnverifiedGuidance() {
        assertFalse(LaneGuidanceSafetyGate.canShow(null))
        val convincingButUnverified = LaneGuidance(
            lanes = listOf(
                Lane(listOf(LaneDirection.Straight), isRecommended = false),
                Lane(listOf(LaneDirection.Right), isRecommended = true),
            ),
            recommendedLaneIndexes = listOf(1),
            maneuverRelation = ManeuverType.TurnRight,
            source = "osm+mapbox",
            confidence = LaneGuidanceConfidence.High,
        )
        // Even high provider confidence is NOT route-/maneuver-alignment evidence.
        assertFalse(LaneGuidanceSafetyGate.canShow(convincingButUnverified))
    }

    @Test
    fun rejectsUnknownOrMalformedRecommendations() {
        val malformed = LaneGuidance(
            lanes = listOf(Lane(listOf(LaneDirection.Unknown), isRecommended = true)),
            recommendedLaneIndexes = listOf(3),
            maneuverRelation = null,
            source = "osm",
            confidence = LaneGuidanceConfidence.Unknown,
        )
        assertFalse(LaneGuidanceSafetyGate.canShow(malformed))
    }
}
