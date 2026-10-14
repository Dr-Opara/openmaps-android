package com.openmaps.app.core.roadintelligence

import com.openmaps.app.core.routing.ManeuverType
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests [OsrmLaneGuidanceMapper] against OSRM's real, documented `intersections[].lanes` shape
 * (http://project-osrm.org/docs/v5.24.0/api/#route-leg-object) — not against live Valhalla output,
 * which this app's `/route` request does not receive (see the mapper's doc comment).
 */
class OsrmLaneGuidanceMapperTest {

    @Test
    fun nullLanesArray_returnsNull() {
        assertNull(OsrmLaneGuidanceMapper.fromLanesJson(null, ManeuverType.TurnRight))
    }

    @Test
    fun emptyLanesArray_returnsNull() {
        assertNull(OsrmLaneGuidanceMapper.fromLanesJson(JSONArray("[]"), ManeuverType.TurnRight))
    }

    @Test
    fun singleValidStraightLane_mapsCorrectly() {
        val json = JSONArray("""[{"valid":true,"indications":["straight"]}]""")
        val guidance = OsrmLaneGuidanceMapper.fromLanesJson(json, ManeuverType.Continue)!!
        assertEquals(1, guidance.lanes.size)
        assertEquals(listOf(LaneDirection.Straight), guidance.lanes.single().directions)
        assertTrue(guidance.lanes.single().isRecommended)
        assertEquals(listOf(0), guidance.recommendedLaneIndexes)
        assertEquals(ManeuverType.Continue, guidance.maneuverRelation)
    }

    @Test
    fun multiLaneIntersection_onlyValidLanesAreRecommended() {
        val json = JSONArray(
            """[
                {"valid":false,"indications":["left"]},
                {"valid":true,"indications":["straight","right"]},
                {"valid":false,"indications":["right"]}
            ]""",
        )
        val guidance = OsrmLaneGuidanceMapper.fromLanesJson(json, ManeuverType.TurnRight)!!
        assertEquals(3, guidance.lanes.size)
        assertEquals(listOf(1), guidance.recommendedLaneIndexes)
        assertEquals(listOf(LaneDirection.Straight, LaneDirection.Right), guidance.lanes[1].directions)
        assertFalseRecommended(guidance, 0)
        assertFalseRecommended(guidance, 2)
    }

    @Test
    fun everyDocumentedIndicationValue_mapsToASensibleDirection() {
        val json = JSONArray(
            """[{"valid":true,"indications":
                ["left","slight left","straight","slight right","right","sharp left","sharp right",
                 "uturn","merge to left","merge to right","none"]}]""",
        )
        val guidance = OsrmLaneGuidanceMapper.fromLanesJson(json, null)!!
        assertEquals(
            listOf(
                LaneDirection.Left, LaneDirection.SlightLeft, LaneDirection.Straight, LaneDirection.SlightRight,
                LaneDirection.Right, LaneDirection.Left, LaneDirection.Right, LaneDirection.UTurn,
                LaneDirection.SlightLeft, LaneDirection.SlightRight, LaneDirection.Unknown,
            ),
            guidance.lanes.single().directions,
        )
    }

    @Test
    fun unrecognizedIndicationValue_mapsToUnknownRatherThanThrowing() {
        val json = JSONArray("""[{"valid":true,"indications":["some-future-value"]}]""")
        val guidance = OsrmLaneGuidanceMapper.fromLanesJson(json, null)!!
        assertEquals(listOf(LaneDirection.Unknown), guidance.lanes.single().directions)
    }

    @Test
    fun laneWithNoIndications_defaultsToUnknown() {
        val json = JSONArray("""[{"valid":true,"indications":[]}]""")
        val guidance = OsrmLaneGuidanceMapper.fromLanesJson(json, null)!!
        assertEquals(listOf(LaneDirection.Unknown), guidance.lanes.single().directions)
    }

    private fun assertFalseRecommended(guidance: LaneGuidance, index: Int) {
        assertTrue(index !in guidance.recommendedLaneIndexes)
        assertEquals(false, guidance.lanes[index].isRecommended)
    }
}
