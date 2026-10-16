package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.ManeuverType

/** Which way one lane permits travel. A lane can carry more than one (e.g. "through or right"). */
enum class LaneDirection {
    Left,
    SlightLeft,
    Straight,
    SlightRight,
    Right,
    UTurn,
    Unknown,
}

/**
 * One physical lane at an upcoming maneuver, left-to-right as the provider lists it.
 * [isRecommended] mirrors [LaneGuidance.recommendedLaneIndexes] at the per-lane call site so a
 * renderer can read either the index list or the flag, whichever is more convenient.
 */
data class Lane(
    val directions: List<LaneDirection>,
    val isRecommended: Boolean = false,
)

/**
 * Provider-neutral lane guidance for one upcoming maneuver (Phase 10). [lanes] is left-to-right;
 * [recommendedLaneIndexes] indexes into it. [maneuverRelation] is the maneuver type this guidance
 * applies to, so a renderer can place it next to the matching banner without a second lookup.
 *
 * **Never built from route geometry alone.** Production LaneLock is populated only when the
 * OpenMaps lane gateway receives real lane attributes from HERE Route Matching and can identify at
 * least one maneuver-compatible lane. Otherwise this model stays absent. The older
 * [OsrmLaneGuidanceMapper] remains as a provider-neutral fallback mapper, not as evidence that a
 * lane exists.
 */
data class LaneGuidance(
    val lanes: List<Lane>,
    val recommendedLaneIndexes: List<Int>,
    val maneuverRelation: ManeuverType?,
)
