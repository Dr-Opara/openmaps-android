package com.openmaps.app.core.voice

import com.openmaps.app.core.navigation.RouteProgress

/**
 * Decides *when* a maneuver announcement should fire, and deduplicates so the same stage is never
 * spoken twice (delta-spec section 16). Pure and stateful, the same shape as `OffRouteDetector`/
 * `RoadAlertHysteresis`: policy lives here, not inside `MapViewModel`, so it is unit-testable
 * without a navigation session.
 *
 * **Stage only ever advances, never regresses**, keyed per maneuver (by its shape-index span, which
 * is stable for the life of that maneuver): a GPS-noise fluctuation that briefly reports a larger
 * `distanceToNextManeuverMeters` after [AnnouncementStage.Near] has already been announced must not
 * re-announce [AnnouncementStage.Far] — that is exactly the "do not repeat the exact same
 * announcement every GPS update" requirement.
 */
class VoiceAnnouncementPlanner(
    private val farThresholdMeters: Double = FAR_THRESHOLD_METERS,
    private val nearThresholdMeters: Double = NEAR_THRESHOLD_METERS,
    private val immediateThresholdMeters: Double = IMMEDIATE_THRESHOLD_METERS,
    private val destinationApproachingThresholdMeters: Double = DESTINATION_APPROACHING_THRESHOLD_METERS,
) {
    private var currentManeuverKey: Pair<Int, Int>? = null
    private var announcedStageOrdinal: Int = -1
    private var destinationApproachingAnnounced = false

    /** The next maneuver announcement to speak, or null if nothing new has crossed a stage. */
    fun planManeuver(progress: RouteProgress): VoiceEvent.Maneuver? {
        val maneuver = progress.currentManeuver
        val maneuverKey = maneuver.beginShapeIndex to maneuver.endShapeIndex
        if (maneuverKey != currentManeuverKey) {
            currentManeuverKey = maneuverKey
            announcedStageOrdinal = -1
        }
        val stage = stageFor(progress.distanceToNextManeuverMeters) ?: return null
        if (stage.ordinal <= announcedStageOrdinal) return null
        announcedStageOrdinal = stage.ordinal
        return VoiceEvent.Maneuver(maneuver.type, maneuver.streetName, stage)
    }

    /** Fires exactly once per trip, the first time remaining distance crosses the threshold. */
    fun planDestinationApproaching(progress: RouteProgress): VoiceEvent? {
        if (destinationApproachingAnnounced) return null
        if (progress.remainingDistanceMeters > destinationApproachingThresholdMeters) return null
        destinationApproachingAnnounced = true
        return VoiceEvent.DestinationApproaching
    }

    private fun stageFor(distanceMeters: Double): AnnouncementStage? = when {
        distanceMeters <= immediateThresholdMeters -> AnnouncementStage.Immediate
        distanceMeters <= nearThresholdMeters -> AnnouncementStage.Near
        distanceMeters <= farThresholdMeters -> AnnouncementStage.Far
        else -> null
    }

    /** New trip / reroute / stop: a fresh route means fresh maneuvers with nothing announced yet. */
    fun reset() {
        currentManeuverKey = null
        announcedStageOrdinal = -1
        destinationApproachingAnnounced = false
    }

    private companion object {
        const val FAR_THRESHOLD_METERS = 804.672 // 0.5 mi
        const val NEAR_THRESHOLD_METERS = 152.4 // 500 ft
        const val IMMEDIATE_THRESHOLD_METERS = 45.72 // 150 ft — "now"
        const val DESTINATION_APPROACHING_THRESHOLD_METERS = 402.336 // 0.25 mi
    }
}
