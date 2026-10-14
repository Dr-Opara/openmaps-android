package com.openmaps.app.feature.live

import com.openmaps.app.camera.CameraFailure
import com.openmaps.app.camera.RearCameraState
import com.openmaps.app.core.camera.CameraPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test

class LiveReadinessMapperTest {

    @Test
    fun unknownPermission_isPending() {
        val (readiness, reason) = LiveReadinessMapper.of(LiveUiState(cameraPermission = CameraPermissionState.Unknown))
        assertEquals(LiveReadiness.Pending, readiness)
        assertNull(reason)
    }

    @Test
    fun notRequestedPermission_isPending_notUnusable() {
        // Critical: must not be treated as a failure, or the normal permission-request flow would
        // never get a chance to run before Start's fallback bounced back to Map.
        val (readiness, _) = LiveReadinessMapper.of(LiveUiState(cameraPermission = CameraPermissionState.NotRequested))
        assertEquals(LiveReadiness.Pending, readiness)
    }

    @Test
    fun deniedPermission_isUnusableWithAReason() {
        val (readiness, reason) = LiveReadinessMapper.of(LiveUiState(cameraPermission = CameraPermissionState.Denied))
        assertEquals(LiveReadiness.Unusable, readiness)
        assertNotNull(reason)
    }

    @Test
    fun permanentlyDeniedPermission_isUnusableWithAReason() {
        val (readiness, reason) = LiveReadinessMapper.of(LiveUiState(cameraPermission = CameraPermissionState.PermanentlyDenied))
        assertEquals(LiveReadiness.Unusable, readiness)
        assertNotNull(reason)
    }

    @Test
    fun grantedWithNoFailure_isReady() {
        val (readiness, reason) = LiveReadinessMapper.of(
            LiveUiState(cameraPermission = CameraPermissionState.Granted, cameraState = RearCameraState.Ready),
        )
        assertEquals(LiveReadiness.Ready, readiness)
        assertNull(reason)
    }

    @Test
    fun grantedButCameraFailed_isUnusableWithAReason() {
        val (readiness, reason) = LiveReadinessMapper.of(
            LiveUiState(
                cameraPermission = CameraPermissionState.Granted,
                cameraState = RearCameraState.Unavailable(CameraFailure.InUse),
            ),
        )
        assertEquals(LiveReadiness.Unusable, readiness)
        assertNotNull(reason)
    }

    @Test
    fun grantedWhileStarting_isReady() {
        // `isCameraUsable`-style gating treats "starting, no failure yet" as usable; the readiness
        // mapper must agree, so Start's fallback does not bounce back during the normal open delay.
        val (readiness, _) = LiveReadinessMapper.of(
            LiveUiState(cameraPermission = CameraPermissionState.Granted, cameraState = RearCameraState.Starting),
        )
        assertEquals(LiveReadiness.Ready, readiness)
    }
}
