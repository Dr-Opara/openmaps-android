package com.openmaps.app.core.vision

/** What Live may draw this frame. */
sealed interface GuidanceDecision {
    /** Road-anchored chevrons may be drawn. Carries the evidence that justified it. */
    data class RoadAnchored(val alignmentResidualMeters: Double, val confidence: Double) : GuidanceDecision

    /**
     * Standard turn-by-turn only (maneuver banner, distance, street, ETA). This is a complete,
     * correct way to navigate, not an error state.
     */
    data class ConventionalOnly(val reasons: Set<GateReason>) : GuidanceDecision
}

enum class GateReason {
    /** No intrinsics for this camera. */
    CameraUncalibrated,

    /** Intrinsics are derived from sensor geometry and have not been verified on this phone. */
    CalibrationUnverified,

    /** No calibration profile for this phone, camera and orientation, or no lens height entered. */
    MountUnmeasured,

    /** The phone's angle has changed since the mount angle was captured; recalibration is required. */
    MountMoved,

    /** Device attitude is missing or older than [GuidanceConfidenceGate.MAX_ATTITUDE_AGE_NANOS]. */
    AttitudeStale,

    /** No road observation: the perception module does not exist yet, or it sees no road. */
    NoRoadObservation,
    RoadConfidenceLow,

    /** Camera heading relative to the route is unknown or too uncertain. */
    HeadingUncertain,

    /** The route has not been matched against the observed road, or the fit is poor. */
    RouteNotAligned,

    /** The map matcher is not confident the vehicle is on the route. */
    MapMatchWeak,
}

/** Everything the gate looks at. Every field defaults to "no evidence". */
data class GateEvidence(
    val intrinsics: CameraIntrinsics? = null,
    /** The profile that applies to the phone, camera and orientation in use; null when there is none. */
    val profile: CalibrationProfile? = null,
    val mountMoved: Boolean = false,
    val attitudeAgeNanos: Long? = null,
    val roadConfidence: Double? = null,
    val yawSigmaRadians: Double? = null,
    val alignmentResidualMeters: Double? = null,
    val alignmentConfidence: Double? = null,
    val mapMatchConfident: Boolean = false,
)

/**
 * Decides whether road-anchored arrows may be shown. **Fails closed:** a missing input is a reason
 * to refuse, never a default to allow, and every reason is reported so a refusal can be diagnosed.
 *
 * The numeric thresholds are provisional engineering starting points. They are not validated and
 * must be set from labelled recordings (see the acceptance criteria in the architecture document)
 * before any road-anchored rendering ships. With Stage 1 code there is no road observation at all,
 * so the result is always [GuidanceDecision.ConventionalOnly].
 */
object GuidanceConfidenceGate {
    const val MAX_ATTITUDE_AGE_NANOS = 200_000_000L
    const val MIN_ROAD_CONFIDENCE = 0.8
    const val MAX_YAW_SIGMA_RADIANS = 0.035 // ~2 degrees: ~0.7 m lateral error at 20 m
    const val MAX_ALIGNMENT_RESIDUAL_METERS = 0.75
    const val MIN_ALIGNMENT_CONFIDENCE = 0.8

    fun decide(evidence: GateEvidence): GuidanceDecision {
        val reasons = linkedSetOf<GateReason>()
        if (evidence.intrinsics == null) {
            reasons += GateReason.CameraUncalibrated
        } else if (evidence.intrinsics.source != IntrinsicsSource.Measured && evidence.profile?.verifiedOnDevice != true) {
            reasons += GateReason.CalibrationUnverified
        }
        val profile = evidence.profile
        if (profile == null || !profile.hasLensHeight || !profile.hasMountAngle) reasons += GateReason.MountUnmeasured
        if (evidence.mountMoved) reasons += GateReason.MountMoved
        val attitudeAge = evidence.attitudeAgeNanos
        if (attitudeAge == null || attitudeAge < 0 || attitudeAge > MAX_ATTITUDE_AGE_NANOS) reasons += GateReason.AttitudeStale
        val road = evidence.roadConfidence
        when {
            road == null || road.isNaN() -> reasons += GateReason.NoRoadObservation
            road < MIN_ROAD_CONFIDENCE -> reasons += GateReason.RoadConfidenceLow
        }
        val yawSigma = evidence.yawSigmaRadians
        if (yawSigma == null || yawSigma.isNaN() || yawSigma > MAX_YAW_SIGMA_RADIANS) reasons += GateReason.HeadingUncertain
        val residual = evidence.alignmentResidualMeters
        val alignmentConfidence = evidence.alignmentConfidence
        if (residual == null || residual.isNaN() || residual > MAX_ALIGNMENT_RESIDUAL_METERS ||
            alignmentConfidence == null || alignmentConfidence.isNaN() || alignmentConfidence < MIN_ALIGNMENT_CONFIDENCE
        ) {
            reasons += GateReason.RouteNotAligned
        }
        if (!evidence.mapMatchConfident) reasons += GateReason.MapMatchWeak

        return if (reasons.isEmpty()) {
            GuidanceDecision.RoadAnchored(alignmentResidualMeters = residual!!, confidence = alignmentConfidence!!)
        } else {
            GuidanceDecision.ConventionalOnly(reasons)
        }
    }
}
