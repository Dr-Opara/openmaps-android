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
 * OpenMaps lane gateway receives real provider lane evidence and can identify at least one
 * maneuver-compatible lane. The production gateway can combine OSM turn-lane tags with Mapbox
 * Directions lane arrays. Conflicting evidence is rejected rather than guessed.
 */
enum class LaneGuidanceConfidence { High, Medium, Unknown }

data class LaneGuidance(
    val lanes: List<Lane>,
    val recommendedLaneIndexes: List<Int>,
    val maneuverRelation: ManeuverType?,
    val source: String? = null,
    val confidence: LaneGuidanceConfidence = LaneGuidanceConfidence.Unknown,
)
