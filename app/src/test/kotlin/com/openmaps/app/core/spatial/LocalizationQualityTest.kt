package com.openmaps.app.core.spatial

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Localization-quality thresholds, heading math and the Live status mapping — the entire
 * provider-neutral Phase 6 decision surface. No ARCore, no device: these are the rules that decide
 * whether OpenMaps claims a trustworthy fix, so they get tested hardest.
 */
class LocalizationQualityTest {

    private val mapper = LocalizationQualityMapper()

    // region Quality thresholds

    @Test
    fun excellentAccuracyOnBothAxesIsGood() {
        assertEquals(LocalizationQuality.Good, mapper.quality(3.0, 2.0))
    }

    @Test
    fun decentAccuracyIsFair() {
        // 15 m horizontal, 12 deg heading: past "excellent", within "good".
        assertEquals(LocalizationQuality.Fair, mapper.quality(15.0, 12.0))
    }

    @Test
    fun looseHeadingMakesItPoorEvenWithGoodPosition() {
        // A position you could stand on, but a heading you could not steer by.
        assertEquals(LocalizationQuality.Poor, mapper.quality(3.0, 35.0))
    }

    @Test
    fun loosePositionMakesItPoorEvenWithGoodHeading() {
        assertEquals(LocalizationQuality.Poor, mapper.quality(60.0, 2.0))
    }

    @Test
    fun missingAccuracyIsUnavailableNotPerfect() {
        // The most dangerous possible bug: treating "unknown" as "excellent" would show a confident
        // heading that is pure noise.
        assertEquals(LocalizationQuality.Unavailable, mapper.quality(null, 2.0))
        assertEquals(LocalizationQuality.Unavailable, mapper.quality(3.0, null))
        assertEquals(LocalizationQuality.Unavailable, mapper.quality(null, null))
    }

    @Test
    fun absurdAccuracyIsUnavailable() {
        // ~a city block out; not worth showing at all.
        assertEquals(LocalizationQuality.Unavailable, mapper.quality(500.0, 2.0))
        assertEquals(LocalizationQuality.Unavailable, mapper.quality(3.0, 120.0))
    }

    @Test
    fun unsettledFixIsAcquiringRegardlessOfAccuracy() {
        assertEquals(LocalizationQuality.Acquiring, mapper.quality(2.0, 1.0, isSettled = false))
    }

    @Test
    fun unusableAccuracyBeatsTheSettledFlag() {
        // An unsettled-but-usable fix is "Acquiring"; an unusable one is "Unavailable" no matter what.
        assertEquals(LocalizationQuality.Unavailable, mapper.quality(900.0, 2.0, isSettled = false))
    }

    @Test
    fun thresholdsAreConfigurable() {
        val strict = LocalizationQualityMapper(
            excellentHorizontalMeters = 1.0,
            goodHorizontalMeters = 2.0,
        )
        // 3 m horizontal is "Good" under the defaults but falls out of the "fair" band entirely
        // under the stricter map, since 3 m no longer counts as a good position.
        assertEquals(LocalizationQuality.Good, mapper.quality(3.0, 1.0))
        assertEquals(LocalizationQuality.Poor, strict.quality(3.0, 1.0))
    }

    @Test
    fun boundaryValuesLandOnTheBetterSide() {
        // Exactly at a threshold counts as acceptable, not rejected.
        assertEquals(LocalizationQuality.Fair, mapper.quality(LocalizationQualityMapper.GOOD_HORIZONTAL_METERS, 1.0))
        assertEquals(LocalizationQuality.Unavailable, mapper.quality(LocalizationQualityMapper.USABLE_HORIZONTAL_METERS + 0.1, 1.0))
    }

    // endregion

    // region Heading math

    @Test
    fun normalizeHeadingWrapsIntoZeroThreeSixty() {
        assertEquals(270.0, normalizeHeadingDegrees(-90.0), 1e-9)
        assertEquals(10.0, normalizeHeadingDegrees(370.0), 1e-9)
        assertEquals(0.0, normalizeHeadingDegrees(360.0), 1e-9)
        assertEquals(180.0, normalizeHeadingDegrees(-180.0), 1e-9)
    }

    @Test
    fun normalizeHeadingSurvivesNonFiniteInput() {
        assertEquals(0.0, normalizeHeadingDegrees(Double.NaN), 1e-9)
        assertEquals(0.0, normalizeHeadingDegrees(Double.POSITIVE_INFINITY), 1e-9)
    }

    @Test
    fun headingDeltaIsSignedAndShortest() {
        assertEquals(90.0, headingDeltaDegrees(0.0, 90.0), 1e-9)
        assertEquals(-90.0, headingDeltaDegrees(90.0, 0.0), 1e-9)
        // Across north must be a small negative, not +270.
        assertEquals(-10.0, headingDeltaDegrees(5.0, 355.0), 1e-9)
        assertEquals(10.0, headingDeltaDegrees(355.0, 5.0), 1e-9)
    }

    @Test
    fun headingsAgreeWithinTolerance() {
        assertTrue(headingsAgree(10.0, 20.0, 15.0))
        assertFalse(headingsAgree(10.0, 40.0, 15.0))
        // Across the 0/360 seam.
        assertTrue(headingsAgree(359.0, 1.0, 5.0))
    }

    // endregion

    // region Live status mapping

    @Test
    fun liveStatusIsNoneWhenLocalizationIsNotApplicable() {
        // AR is an enhancement; nothing should be shown while it is irrelevant or broken.
        assertEquals(LiveLocalizationStatus.None, LiveLocalizationStatusMapper.of(SpatialLocalizationState.Idle))
        assertEquals(LiveLocalizationStatus.None, LiveLocalizationStatusMapper.of(SpatialLocalizationState.Unsupported))
        assertEquals(LiveLocalizationStatus.None, LiveLocalizationStatusMapper.of(SpatialLocalizationState.PermissionRequired))
        assertEquals(LiveLocalizationStatus.None, LiveLocalizationStatusMapper.of(SpatialLocalizationState.NotInstalled()))
        assertEquals(
            LiveLocalizationStatus.None,
            LiveLocalizationStatusMapper.of(SpatialLocalizationState.Failed(SpatialFailure.ArCoreError)),
        )
    }

    @Test
    fun liveStatusIsLocatingWhileInitializingOrLimited() {
        assertEquals(LiveLocalizationStatus.Locating, LiveLocalizationStatusMapper.of(SpatialLocalizationState.Initializing))
        assertEquals(
            LiveLocalizationStatus.Locating,
            LiveLocalizationStatusMapper.of(SpatialLocalizationState.Limited(LimitedReason.InsufficientFeatures)),
        )
    }

    @Test
    fun liveStatusIsLocatingWhileTrackingWithoutAPoseYet() {
        // ARCore reports TRACKING before the first geospatial pose arrives; claiming "Ready" then
        // would be a lie.
        assertEquals(LiveLocalizationStatus.Locating, LiveLocalizationStatusMapper.of(SpatialLocalizationState.Tracking(null)))
    }

    @Test
    fun liveStatusFollowsPoseQuality() {
        assertEquals(LiveLocalizationStatus.Locating, LiveLocalizationStatusMapper.of(SpatialLocalizationState.Tracking(pose(LocalizationQuality.Unavailable))))
        assertEquals(LiveLocalizationStatus.Locating, LiveLocalizationStatusMapper.of(SpatialLocalizationState.Tracking(pose(LocalizationQuality.Acquiring))))
        assertEquals(LiveLocalizationStatus.Improving, LiveLocalizationStatusMapper.of(SpatialLocalizationState.Tracking(pose(LocalizationQuality.Poor))))
        assertEquals(LiveLocalizationStatus.Ready, LiveLocalizationStatusMapper.of(SpatialLocalizationState.Tracking(pose(LocalizationQuality.Fair))))
        assertEquals(LiveLocalizationStatus.Ready, LiveLocalizationStatusMapper.of(SpatialLocalizationState.Tracking(pose(LocalizationQuality.Good))))
    }

    @Test
    fun limitedRecoversToReadyOnceTrackingResumes() {
        val limited = LiveLocalizationStatusMapper.of(SpatialLocalizationState.Limited(LimitedReason.PoorGeolocation))
        val recovered = LiveLocalizationStatusMapper.of(SpatialLocalizationState.Tracking(pose(LocalizationQuality.Good)))
        assertEquals(LiveLocalizationStatus.Locating, limited)
        assertEquals(LiveLocalizationStatus.Ready, recovered)
    }

    @Test
    fun anchorableOnlyAtGoodQuality() {
        assertTrue(pose(LocalizationQuality.Good).isAnchorable)
        assertFalse(pose(LocalizationQuality.Fair).isAnchorable)
        assertFalse(pose(LocalizationQuality.Poor).isAnchorable)
    }

    // endregion

    private fun pose(quality: LocalizationQuality) = SpatialPose(
        latitude = 40.0,
        longitude = -74.0,
        altitudeMeters = 10.0,
        headingDegrees = 90.0,
        horizontalAccuracyMeters = 3.0,
        verticalAccuracyMeters = 5.0,
        headingAccuracyDegrees = 2.0,
        quality = quality,
    )
}