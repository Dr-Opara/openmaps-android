package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.ManeuverType
import org.junit.Assert.assertFalse
import org.junit.Test

class LaneGuidanceReleaseGateTest {
    @Test
    fun `unverified lanes must never be shown even with high provider confidence`() {
        val apparentlyTrusted = LaneGuidance(
            lanes = listOf(
                Lane(directions = listOf(LaneDirection.Left)),
                Lane(directions = listOf(LaneDirection.Straight), isRecommended = true),
                Lane(directions = listOf(LaneDirection.Right)),
            ),
            recommendedLaneIndexes = listOf(1),
            maneuverRelation = ManeuverType.Continue,
            source = "osm+mapbox",
            confidence = LaneGuidanceConfidence.High,
        )
        assertFalse(LaneGuidanceReleaseGate.FIELD_VALIDATED)
        assertFalse(LaneGuidanceReleaseGate.mayDisplay(apparentlyTrusted))
    }

    @Test
    fun `lane gate does not accept absent advice`() {
        assertFalse(LaneGuidanceReleaseGate.mayDisplay(null))
    }
}
