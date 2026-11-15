package com.openmaps.app.core.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuidanceConfidenceGateTest {
    private val measuredIntrinsics = CameraIntrinsics(500.0, 500.0, 180.0, 320.0, 360, 640, IntrinsicsSource.Measured)
    private val derivedIntrinsics = measuredIntrinsics.copy(source = IntrinsicsSource.SensorGeometry)
    private val profile = CalibrationProfile(
        deviceModel = DeviceTargets.PRIMARY_MODEL,
        cameraId = "0",
        displayRotationDegrees = 0,
        lensHeightMeters = 1.3,
        referencePitchDownDegrees = 4.0,
        referenceRollDegrees = 0.0,
    )

    private val allGood = GateEvidence(
        intrinsics = measuredIntrinsics,
        profile = profile,
        attitudeAgeNanos = 20_000_000,
        roadConfidence = 0.95,
        yawSigmaRadians = 0.01,
        alignmentResidualMeters = 0.3,
        alignmentConfidence = 0.9,
        mapMatchConfident = true,
    )

    private fun reasons(e: GateEvidence) = (GuidanceConfidenceGate.decide(e) as GuidanceDecision.ConventionalOnly).reasons

    @Test
    fun noEvidenceMeansStandardGuidanceWithEveryReasonNamed() {
        assertEquals(
            setOf(
                GateReason.CameraUncalibrated,
                GateReason.MountUnmeasured,
                GateReason.AttitudeStale,
                GateReason.NoRoadObservation,
                GateReason.HeadingUncertain,
                GateReason.RouteNotAligned,
                GateReason.MapMatchWeak,
            ),
            reasons(GateEvidence()),
        )
    }

    @Test
    fun stageOneEvidenceCanNeverOpenTheGate() {
        // Everything Stage 1 can possibly supply: derived intrinsics, fresh attitude, a good map match.
        val stageOne = GateEvidence(intrinsics = derivedIntrinsics, attitudeAgeNanos = 5_000_000, mapMatchConfident = true)
        val r = reasons(stageOne)
        assertTrue(GateReason.NoRoadObservation in r)
        assertTrue(GateReason.RouteNotAligned in r)
        assertTrue(GateReason.HeadingUncertain in r)
        assertTrue(GateReason.CalibrationUnverified in r)
        assertTrue(GateReason.MountUnmeasured in r)
    }

    @Test
    fun completeEvidenceOpensTheGateAndCarriesItsJustification() {
        val decision = GuidanceConfidenceGate.decide(allGood) as GuidanceDecision.RoadAnchored
        assertEquals(0.3, decision.alignmentResidualMeters, 1e-9)
        assertEquals(0.9, decision.confidence, 1e-9)
    }

    @Test
    fun derivedIntrinsicsNeedHumanVerification() {
        assertEquals(setOf(GateReason.CalibrationUnverified), reasons(allGood.copy(intrinsics = derivedIntrinsics)))
        // Manufacturer-reported intrinsics are still not a measurement of this phone.
        assertEquals(
            setOf(GateReason.CalibrationUnverified),
            reasons(allGood.copy(intrinsics = measuredIntrinsics.copy(source = IntrinsicsSource.DeviceReported))),
        )
        assertTrue(
            GuidanceConfidenceGate.decide(
                allGood.copy(intrinsics = derivedIntrinsics, profile = profile.copy(verifiedOnDevice = true)),
            )
                is GuidanceDecision.RoadAnchored,
        )
    }

    @Test
    fun eachSingleWeaknessClosesTheGateOnItsOwn() {
        assertEquals(setOf(GateReason.CameraUncalibrated), reasons(allGood.copy(intrinsics = null)))
        assertEquals(setOf(GateReason.MountUnmeasured), reasons(allGood.copy(profile = null)))
        assertEquals(setOf(GateReason.MountUnmeasured), reasons(allGood.copy(profile = profile.copy(lensHeightMeters = null))))
        assertEquals(
            setOf(GateReason.MountUnmeasured),
            reasons(allGood.copy(profile = profile.copy(referencePitchDownDegrees = null))),
        )
        assertEquals(setOf(GateReason.MountMoved), reasons(allGood.copy(mountMoved = true)))
        assertEquals(setOf(GateReason.AttitudeStale), reasons(allGood.copy(attitudeAgeNanos = 900_000_000)))
        assertEquals(setOf(GateReason.AttitudeStale), reasons(allGood.copy(attitudeAgeNanos = null)))
        assertEquals(setOf(GateReason.NoRoadObservation), reasons(allGood.copy(roadConfidence = null)))
        assertEquals(setOf(GateReason.RoadConfidenceLow), reasons(allGood.copy(roadConfidence = 0.5)))
        assertEquals(setOf(GateReason.HeadingUncertain), reasons(allGood.copy(yawSigmaRadians = 0.2)))
        assertEquals(setOf(GateReason.HeadingUncertain), reasons(allGood.copy(yawSigmaRadians = null)))
        assertEquals(setOf(GateReason.RouteNotAligned), reasons(allGood.copy(alignmentResidualMeters = 2.0)))
        assertEquals(setOf(GateReason.RouteNotAligned), reasons(allGood.copy(alignmentConfidence = 0.4)))
        assertEquals(setOf(GateReason.RouteNotAligned), reasons(allGood.copy(alignmentResidualMeters = null)))
        assertEquals(setOf(GateReason.MapMatchWeak), reasons(allGood.copy(mapMatchConfident = false)))
    }

    @Test
    fun notANumberIsNeverGoodEnough() {
        assertTrue(GateReason.NoRoadObservation in reasons(allGood.copy(roadConfidence = Double.NaN)))
        assertTrue(GateReason.HeadingUncertain in reasons(allGood.copy(yawSigmaRadians = Double.NaN)))
        assertTrue(GateReason.RouteNotAligned in reasons(allGood.copy(alignmentResidualMeters = Double.NaN)))
        assertTrue(GateReason.RouteNotAligned in reasons(allGood.copy(alignmentConfidence = Double.NaN)))
    }

    @Test
    fun thresholdsAreInclusiveOnTheSafeSide() {
        assertTrue(
            GuidanceConfidenceGate.decide(
                allGood.copy(
                    roadConfidence = GuidanceConfidenceGate.MIN_ROAD_CONFIDENCE,
                    yawSigmaRadians = GuidanceConfidenceGate.MAX_YAW_SIGMA_RADIANS,
                    alignmentResidualMeters = GuidanceConfidenceGate.MAX_ALIGNMENT_RESIDUAL_METERS,
                    alignmentConfidence = GuidanceConfidenceGate.MIN_ALIGNMENT_CONFIDENCE,
                    attitudeAgeNanos = GuidanceConfidenceGate.MAX_ATTITUDE_AGE_NANOS,
                ),
            ) is GuidanceDecision.RoadAnchored,
        )
    }
}
