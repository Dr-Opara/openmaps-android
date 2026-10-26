package com.openmaps.app.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowCameraMotionTest {
    private val here = CameraState(29.7, -95.7, 17.0)
    private fun north(meters: Double) = here.copy(latitude = here.latitude + meters / 111_320.0)

    @Test fun firstStepSnaps() = assertFalse(FollowCameraMotion.plan(null, here, null).animated)

    @Test fun normalFixGlidesLinearlyForOneInterval() {
        val m = FollowCameraMotion.plan(here, north(15.0), 1000)
        assertTrue(m.animated && m.linear)
        assertEquals(1000, m.durationMillis)
    }

    @Test fun fastFixesAreClampedSoTheCameraDoesNotStrobe() =
        assertEquals(FollowCameraMotion.MIN_GLIDE_MILLIS.toInt(), FollowCameraMotion.plan(here, north(2.0), 50).durationMillis)

    @Test fun slowFixesAreClampedSoTheCameraDoesNotLagBehind() =
        assertEquals(FollowCameraMotion.MAX_GLIDE_MILLIS.toInt(), FollowCameraMotion.plan(here, north(40.0), 3500).durationMillis)

    @Test fun longGapSnaps() = assertFalse(FollowCameraMotion.plan(here, north(80.0), 9000).animated)

    @Test fun teleportSnapsInsteadOfFlyingAcrossTheMap() = assertFalse(FollowCameraMotion.plan(here, north(600.0), 1000).animated)

    @Test fun bigZoomChangeSnaps() = assertFalse(FollowCameraMotion.plan(here, here.copy(zoom = 14.0), 1000).animated)

    @Test fun clockGoingBackwardsSnaps() = assertFalse(FollowCameraMotion.plan(here, north(5.0), -10).animated)
}
