package com.openmaps.app.core.roadintelligence

/**
 * Driver-safety release gate for LaneLock.
 *
 * Physical Westpark Tollway field testing showed lane advice associated with the wrong or
 * already-passed road geometry. The current provider response does not carry enough route-step,
 * carriageway, intersection and freshness identity to prove that advice is safe to display.
 *
 * Leave this OFF until Issue #88 has route-matched, maneuver-timed evidence and separate
 * on-device acceptance. This flag suppresses provider fetches as well as Map/Live display;
 * it does not disable conventional navigation, turn alerts or the AR camera.
 */
internal object LaneGuidanceReleaseGate {
    const val FIELD_VALIDATED: Boolean = false

    fun mayDisplay(guidance: LaneGuidance?): Boolean =
        FIELD_VALIDATED && guidance != null &&
            guidance.lanes.isNotEmpty() &&
            guidance.recommendedLaneIndexes.isNotEmpty()
}
