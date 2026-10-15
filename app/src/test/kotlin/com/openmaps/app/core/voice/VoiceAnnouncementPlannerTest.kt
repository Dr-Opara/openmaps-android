package com.openmaps.app.core.voice

import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.core.routing.RouteManeuver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Section 16: staged, deduplicated maneuver announcements, plus the one-shot approach cue. */
class VoiceAnnouncementPlannerTest {

    private fun maneuver(
        type: ManeuverType = ManeuverType.TurnRight,
        streetName: String? = "Main Street",
        begin: Int = 10,
        end: Int = 12,
    ) = RouteManeuver(
        instruction = "Turn right",
        verbalInstruction = null,
        type = type,
        streetName = streetName,
        distanceMeters = 50.0,
        beginShapeIndex = begin,
        endShapeIndex = end,
    )

    private fun progress(
        distanceToNextManeuverMeters: Double,
        maneuver: RouteManeuver = maneuver(),
        remainingDistanceMeters: Double = 10_000.0,
    ) = RouteProgress(
        snappedShapeIndex = 0,
        lateralDistanceMeters = 0.0,
        distanceTraveledMeters = 0.0,
        remainingDistanceMeters = remainingDistanceMeters,
        remainingDurationSeconds = 600.0,
        progressFraction = 0.1,
        currentManeuver = maneuver,
        nextManeuver = null,
        distanceToNextManeuverMeters = distanceToNextManeuverMeters,
        isOffRoute = false,
    )

    @Test
    fun farThenNearThenImmediate_eachFiresExactlyOnce() {
        val planner = VoiceAnnouncementPlanner()

        val far = planner.planManeuver(progress(distanceToNextManeuverMeters = 800.0))
        assertEquals(AnnouncementStage.Far, far?.stage)

        // Still inside the Far band: must not re-fire.
        assertNull(planner.planManeuver(progress(distanceToNextManeuverMeters = 790.0)))

        val near = planner.planManeuver(progress(distanceToNextManeuverMeters = 150.0))
        assertEquals(AnnouncementStage.Near, near?.stage)
        assertNull(planner.planManeuver(progress(distanceToNextManeuverMeters = 140.0)))

        val immediate = planner.planManeuver(progress(distanceToNextManeuverMeters = 20.0))
        assertEquals(AnnouncementStage.Immediate, immediate?.stage)
        assertNull(planner.planManeuver(progress(distanceToNextManeuverMeters = 5.0)))
    }

    @Test
    fun gpsNoiseBouncingDistanceBackUp_neverReAnnouncesAnEarlierStage() {
        val planner = VoiceAnnouncementPlanner()
        val near = planner.planManeuver(progress(distanceToNextManeuverMeters = 150.0))
        assertEquals(AnnouncementStage.Near, near?.stage)

        // A noisy fix reports a larger distance, back inside the Far band — must not re-announce it.
        assertNull(
            "stage must never regress on noisy distance",
            planner.planManeuver(progress(distanceToNextManeuverMeters = 700.0)),
        )
    }

    @Test
    fun aNewManeuver_getsItsOwnFreshStageSequence() {
        val planner = VoiceAnnouncementPlanner()
        planner.planManeuver(progress(distanceToNextManeuverMeters = 20.0, maneuver = maneuver(begin = 10, end = 12)))

        // A different maneuver (different shape-index span) must be free to announce Far again.
        val next = planner.planManeuver(
            progress(distanceToNextManeuverMeters = 800.0, maneuver = maneuver(begin = 20, end = 22, type = ManeuverType.TurnLeft)),
        )
        assertEquals(AnnouncementStage.Far, next?.stage)
        assertEquals(ManeuverType.TurnLeft, next?.type)
    }

    @Test
    fun beyondTheFarThreshold_producesNoAnnouncement() {
        val planner = VoiceAnnouncementPlanner()
        assertNull(planner.planManeuver(progress(distanceToNextManeuverMeters = 5_000.0)))
    }

    @Test
    fun reset_clearsStageAndAllowsReAnnouncingTheSameManeuver() {
        val planner = VoiceAnnouncementPlanner()
        val m = maneuver()
        planner.planManeuver(progress(distanceToNextManeuverMeters = 20.0, maneuver = m))
        planner.reset()
        val again = planner.planManeuver(progress(distanceToNextManeuverMeters = 800.0, maneuver = m))
        assertEquals(AnnouncementStage.Far, again?.stage)
    }

    @Test
    fun destinationApproaching_firesExactlyOnce() {
        val planner = VoiceAnnouncementPlanner()
        assertNull(planner.planDestinationApproaching(progress(distanceToNextManeuverMeters = 50.0, remainingDistanceMeters = 1_000.0)))

        val fired = planner.planDestinationApproaching(progress(distanceToNextManeuverMeters = 50.0, remainingDistanceMeters = 300.0))
        assertEquals(VoiceEvent.DestinationApproaching, fired)

        assertNull(
            "must not repeat every tick",
            planner.planDestinationApproaching(progress(distanceToNextManeuverMeters = 50.0, remainingDistanceMeters = 100.0)),
        )
    }

    @Test
    fun reset_alsoClearsDestinationApproaching() {
        val planner = VoiceAnnouncementPlanner()
        planner.planDestinationApproaching(progress(distanceToNextManeuverMeters = 50.0, remainingDistanceMeters = 300.0))
        planner.reset()
        assertTrue(
            planner.planDestinationApproaching(progress(distanceToNextManeuverMeters = 50.0, remainingDistanceMeters = 300.0)) != null,
        )
    }
}
