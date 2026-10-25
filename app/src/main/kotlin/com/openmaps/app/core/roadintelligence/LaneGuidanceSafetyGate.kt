package com.openmaps.app.core.roadintelligence

/**
 * Release-safety gate for lane-level directions.
 *
 * Physical-device road testing found LaneLock recommendations that were late or contradicted
 * the observed lane geometry. Our main route uses HERE while lane evidence comes from a separate
 * OSM/Mapbox matching query, which has not been proven to refer to the *same* upcoming maneuver.
 * A confidence string alone cannot make that correlation safe.
 *
 * Fail closed until route/maneuver matching, freshness and physical-drive acceptance in #87 pass.
 * This gate is shared by retrieval and both UI surfaces, including cached/hysteresis alerts.
 * Do NOT flip it to true solely to make the lane strip visible in a mockup.
 */
object LaneGuidanceSafetyGate {
    private const val FIELD_VERIFIED = false

    fun canRequest(): Boolean = FIELD_VERIFIED

    fun canShow(guidance: LaneGuidance?): Boolean =
        FIELD_VERIFIED &&
            guidance != null &&
            guidance.confidence == LaneGuidanceConfidence.High &&
            guidance.maneuverRelation != null &&
            guidance.lanes.isNotEmpty() &&
            guidance.recommendedLaneIndexes.isNotEmpty() &&
            guidance.recommendedLaneIndexes.all { it in guidance.lanes.indices }
}
