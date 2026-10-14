package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.ManeuverType
import org.json.JSONArray

/**
 * Maps the OSRM-family `intersections[].lanes` wire format — documented at
 * http://project-osrm.org/docs/v5.24.0/api/#route-leg-object, `{"valid":bool,
 * "indications":["left","straight",...],"valid_indication":"straight"}` — into the provider-neutral
 * [LaneGuidance]/[Lane] models.
 *
 * **Not wired into [com.openmaps.app.core.routing.ValhallaResponseParser] today.** Per section 9 of
 * the Phase 10 brief ("inspect Valhalla response first"): the native Valhalla `/route` response this
 * app requests (`ValhallaRoutingRepository`, `costing=auto`, no `format` override) was inspected —
 * including the fixture captured from a live call in `ValhallaResponseParserTest` — and its
 * `trip.legs[].maneuvers[]` objects carry no lane-level field under any name. Valhalla *can* emit
 * OSRM-compatible output (and that format does carry `intersections[].lanes`), but only via a
 * `format=osrm` request parameter this app does not pass, and switching the live request format is a
 * routing-provider change well beyond Phase 10's scope ("do NOT replace existing navigation...
 * architecture"). This mapper exists, and is unit-tested against the real documented OSRM shape, so
 * that wiring it up later (Phase 11 prerequisite — see ROADMAP.md) is a parser call, not new design.
 */
object OsrmLaneGuidanceMapper {

    /** Maps one `intersections[n].lanes` JSON array. Null when [lanesJson] is null or empty. */
    fun fromLanesJson(lanesJson: JSONArray?, maneuverRelation: ManeuverType?): LaneGuidance? {
        if (lanesJson == null || lanesJson.length() == 0) return null
        val lanes = mutableListOf<Lane>()
        val recommended = mutableListOf<Int>()
        for (i in 0 until lanesJson.length()) {
            val laneJson = lanesJson.optJSONObject(i) ?: continue
            val directions = laneJson.optJSONArray("indications").toLaneDirections()
            val isValid = laneJson.optBoolean("valid", false)
            lanes += Lane(directions = directions, isRecommended = isValid)
            if (isValid) recommended += i
        }
        if (lanes.isEmpty()) return null
        return LaneGuidance(lanes = lanes, recommendedLaneIndexes = recommended, maneuverRelation = maneuverRelation)
    }

    private fun JSONArray?.toLaneDirections(): List<LaneDirection> {
        if (this == null) return listOf(LaneDirection.Unknown)
        val directions = (0 until length()).map { index -> optString(index).toLaneDirection() }
        return directions.ifEmpty { listOf(LaneDirection.Unknown) }
    }

    private fun String.toLaneDirection(): LaneDirection = when (this) {
        "left" -> LaneDirection.Left
        "slight left" -> LaneDirection.SlightLeft
        "straight" -> LaneDirection.Straight
        "slight right" -> LaneDirection.SlightRight
        "right" -> LaneDirection.Right
        "uturn" -> LaneDirection.UTurn
        // "sharp left"/"sharp right"/"merge to left"/"merge to right" are real OSRM values this
        // provider-neutral model has no corresponding case for yet — collapse to the nearest side
        // rather than drop the lane entirely.
        "sharp left" -> LaneDirection.Left
        "sharp right" -> LaneDirection.Right
        "merge to left" -> LaneDirection.SlightLeft
        "merge to right" -> LaneDirection.SlightRight
        "none" -> LaneDirection.Unknown
        else -> LaneDirection.Unknown
    }
}
