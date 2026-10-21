package com.openmaps.app.core.arrow

import com.openmaps.app.core.spatial.LimitedReason
import com.openmaps.app.core.spatial.LocalizationQuality
import com.openmaps.app.core.spatial.SpatialFailure
import com.openmaps.app.core.spatial.SpatialLocalizationState
import com.openmaps.app.core.spatial.SpatialPose
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The debug-only classifier that turns "arrows never appear" into one of nine concrete, actionable
 * pipeline stages. Each test locks down one named cause so a real report can be matched to exactly
 * one of these, never left as an undifferentiated "nothing happened".
 */
class ArPipelineDiagnosticsTest {

    private fun pose(quality: LocalizationQuality) = SpatialPose(
        latitude = 0.0,
        longitude = 0.0,
        altitudeMeters = null,
        headingDegrees = 0.0,
        horizontalAccuracyMeters = 3.0,
        verticalAccuracyMeters = null,
        headingAccuracyDegrees = 2.0,
        quality = quality,
    )

    private fun snapshot(
        previewProvider: ArPreviewProvider = ArPreviewProvider.Spatial,
        spatialState: SpatialLocalizationState = SpatialLocalizationState.Tracking(pose(LocalizationQuality.Good)),
        hasRoute: Boolean = true,
        isNavigating: Boolean = true,
        glReady: Boolean = true,
        sampledCount: Int = 1,
        arrowCount: Int = 1,
    ) = ArPipelineSnapshot(
        previewProvider = previewProvider,
        spatialState = spatialState,
        hasRoute = hasRoute,
        isNavigating = isNavigating,
        glReady = glReady,
        sampledCount = sampledCount,
        arrowCount = arrowCount,
    )

    @Test
    fun deviceUnsupported_isArCoreUnavailable() {
        assertEquals(
            ArPipelineDiagnostic.ArCoreUnavailable,
            ArPipelineDiagnostics.classify(snapshot(spatialState = SpatialLocalizationState.Unsupported)),
        )
    }

    @Test
    fun notInstalled_isArCoreUnavailable() {
        assertEquals(
            ArPipelineDiagnostic.ArCoreUnavailable,
            ArPipelineDiagnostics.classify(
                snapshot(spatialState = SpatialLocalizationState.NotInstalled(requestInstall = true)),
            ),
        )
    }

    @Test
    fun geospatialUnsupportedFailure_isArCoreUnavailable() {
        assertEquals(
            ArPipelineDiagnostic.ArCoreUnavailable,
            ArPipelineDiagnostics.classify(
                snapshot(
                    spatialState = SpatialLocalizationState.Failed(SpatialFailure.GeospatialUnsupported),
                ),
            ),
        )
    }

    @Test
    fun otherFailureReasons_areTrackingNotReady_notArCoreUnavailable() {
        // A transient failure (camera held by another app, a one-off ARCore error) is not the same
        // claim as "this device cannot run AR at all" — only GeospatialUnsupported means that.
        assertEquals(
            ArPipelineDiagnostic.TrackingNotReady,
            ArPipelineDiagnostics.classify(
                snapshot(spatialState = SpatialLocalizationState.Failed(SpatialFailure.CameraUnavailable)),
            ),
        )
    }

    @Test
    fun cameraXProvider_isCameraXFallback_evenWhenDeviceCouldRunAr() {
        assertEquals(
            ArPipelineDiagnostic.CameraXFallback,
            ArPipelineDiagnostics.classify(
                snapshot(
                    previewProvider = ArPreviewProvider.CameraX,
                    spatialState = SpatialLocalizationState.Tracking(pose(LocalizationQuality.Good)),
                ),
            ),
        )
    }

    @Test
    fun notYetTracking_isTrackingNotReady() {
        for (state in listOf(
            SpatialLocalizationState.Idle,
            SpatialLocalizationState.Initializing,
            SpatialLocalizationState.PermissionRequired,
            SpatialLocalizationState.Limited(LimitedReason.InsufficientFeatures),
        )) {
            assertEquals(
                "state=$state",
                ArPipelineDiagnostic.TrackingNotReady,
                ArPipelineDiagnostics.classify(snapshot(spatialState = state)),
            )
        }
    }

    @Test
    fun trackingWithNoPoseYet_isGeospatialAccuracyInsufficient() {
        assertEquals(
            ArPipelineDiagnostic.GeospatialAccuracyInsufficient,
            ArPipelineDiagnostics.classify(snapshot(spatialState = SpatialLocalizationState.Tracking(null))),
        )
    }

    @Test
    fun trackingWithAcquiringOrUnavailableQuality_isGeospatialAccuracyInsufficient() {
        for (quality in listOf(LocalizationQuality.Unavailable, LocalizationQuality.Acquiring)) {
            assertEquals(
                "quality=$quality",
                ArPipelineDiagnostic.GeospatialAccuracyInsufficient,
                ArPipelineDiagnostics.classify(
                    snapshot(spatialState = SpatialLocalizationState.Tracking(pose(quality))),
                ),
            )
        }
    }

    @Test
    fun trackingWithPoorQualityOrBetter_passesTheAccuracyStage() {
        // Poor is a usable fix (see ARArrowConfig.minimumQuality's doc) — it must not be classified
        // the same as "no fix at all".
        for (quality in listOf(LocalizationQuality.Poor, LocalizationQuality.Fair, LocalizationQuality.Good)) {
            val diagnostic = ArPipelineDiagnostics.classify(
                snapshot(spatialState = SpatialLocalizationState.Tracking(pose(quality))),
            )
            assertEquals("quality=$quality", ArPipelineDiagnostic.ArrowsDrawn, diagnostic)
        }
    }

    @Test
    fun noRoute_isRouteNotAvailable() {
        assertEquals(
            ArPipelineDiagnostic.RouteNotAvailable,
            ArPipelineDiagnostics.classify(snapshot(hasRoute = false)),
        )
    }

    @Test
    fun notNavigating_isRouteNotAvailable() {
        assertEquals(
            ArPipelineDiagnostic.RouteNotAvailable,
            ArPipelineDiagnostics.classify(snapshot(isNavigating = false)),
        )
    }

    @Test
    fun glNotReady_isRendererNotInitialized() {
        assertEquals(
            ArPipelineDiagnostic.RendererNotInitialized,
            ArPipelineDiagnostics.classify(snapshot(glReady = false)),
        )
    }

    @Test
    fun zeroSampledNodes_isNoArrowSamples() {
        assertEquals(
            ArPipelineDiagnostic.NoArrowSamples,
            ArPipelineDiagnostics.classify(snapshot(sampledCount = 0, arrowCount = 0)),
        )
    }

    @Test
    fun sampledButNoneOnScreen_isSamplesOutsideViewport() {
        assertEquals(
            ArPipelineDiagnostic.SamplesOutsideViewport,
            ArPipelineDiagnostics.classify(snapshot(sampledCount = 4, arrowCount = 0)),
        )
    }

    @Test
    fun everythingReady_isArrowsDrawn() {
        assertEquals(
            ArPipelineDiagnostic.ArrowsDrawn,
            ArPipelineDiagnostics.classify(snapshot(sampledCount = 4, arrowCount = 3)),
        )
    }

    @Test
    fun precedence_deviceIncapableBeatsEverythingElse() {
        // Even with every later stage individually "fine", device incapability must still win.
        assertEquals(
            ArPipelineDiagnostic.ArCoreUnavailable,
            ArPipelineDiagnostics.classify(
                snapshot(
                    spatialState = SpatialLocalizationState.Unsupported,
                    hasRoute = true,
                    isNavigating = true,
                    glReady = true,
                    sampledCount = 4,
                    arrowCount = 3,
                ),
            ),
        )
    }
}
