package com.openmaps.app.core.arrow

import com.openmaps.app.core.spatial.LocalizationQuality
import com.openmaps.app.core.spatial.SpatialFailure
import com.openmaps.app.core.spatial.SpatialLocalizationState

/**
 * One named reason the AR arrow pipeline is (or is not) drawing right now.
 *
 * Exists because "arrows never appear" was, until now, a single undifferentiated symptom spanning at
 * least nine different real causes scattered across `LiveViewModel`, `ArCoreSpatialLocalization` and
 * `ARArrowPipeline` — camera permission, device support, tracking state, Geospatial accuracy, route
 * availability, sampling, projection and GL readiness. A field report of "no arrows" is not
 * actionable; "stuck at [GeospatialAccuracyInsufficient]" is. Debug-build only — see
 * `LiveArDiagnosticsOverlay` for the production-safe rule that this never reaches a release build.
 */
enum class ArPipelineDiagnostic {
    /** This device/ARCore cannot run spatial localization at all (not fixable by waiting). */
    ArCoreUnavailable,

    /** AR is possible here, but Live is currently on the plain CameraX preview instead. */
    CameraXFallback,

    /** The engine is running but has not reached a tracked camera fix yet. */
    TrackingNotReady,

    /** Camera is tracking, but the Geospatial fix itself is not accurate enough to place anything. */
    GeospatialAccuracyInsufficient,

    /** Localization is fine; there is simply no active route to draw arrows for. */
    RouteNotAvailable,

    /** Everything upstream is ready, but the sampler produced zero candidate nodes this update. */
    NoArrowSamples,

    /** Nodes were sampled, but none of them projected onto the visible viewport. */
    SamplesOutsideViewport,

    /** Nodes exist and projected, but the GL renderer has not been initialized yet. */
    RendererNotInitialized,

    /** The pipeline is drawing arrows right now. */
    ArrowsDrawn,
}

/** Mirrors `LiveViewModel.PreviewProvider` without this (JVM-only) file depending on it. */
enum class ArPreviewProvider { Spatial, CameraX }

/**
 * Everything the classifier needs, gathered from across the pipeline into one snapshot per update so
 * the precedence logic itself stays a pure, independently testable function.
 */
data class ArPipelineSnapshot(
    val previewProvider: ArPreviewProvider,
    val spatialState: SpatialLocalizationState,
    val hasRoute: Boolean,
    val isNavigating: Boolean,
    val glReady: Boolean,
    val sampledCount: Int,
    val arrowCount: Int,
)

object ArPipelineDiagnostics {

    /**
     * Classifies [snapshot] into exactly one [ArPipelineDiagnostic], in strict precedence order: the
     * first upstream stage blocking arrows wins, so two simultaneously-true symptoms (e.g. no route
     * *and* poor accuracy) always resolve to the one that is actually load-bearing.
     */
    fun classify(snapshot: ArPipelineSnapshot): ArPipelineDiagnostic {
        if (isDeviceIncapable(snapshot.spatialState)) return ArPipelineDiagnostic.ArCoreUnavailable
        if (snapshot.previewProvider != ArPreviewProvider.Spatial) return ArPipelineDiagnostic.CameraXFallback

        trackingDiagnosticOrNull(snapshot.spatialState)?.let { return it }

        if (!snapshot.isNavigating || !snapshot.hasRoute) return ArPipelineDiagnostic.RouteNotAvailable
        if (!snapshot.glReady) return ArPipelineDiagnostic.RendererNotInitialized
        if (snapshot.sampledCount == 0) return ArPipelineDiagnostic.NoArrowSamples
        if (snapshot.arrowCount == 0) return ArPipelineDiagnostic.SamplesOutsideViewport
        return ArPipelineDiagnostic.ArrowsDrawn
    }

    private fun isDeviceIncapable(state: SpatialLocalizationState): Boolean = when (state) {
        SpatialLocalizationState.Unsupported -> true
        is SpatialLocalizationState.NotInstalled -> true
        // The one `Failed` reason that means "this device/ARCore combination cannot do this", not
        // "could not do it just now" — see SpatialFailure.GeospatialUnsupported's own doc.
        is SpatialLocalizationState.Failed -> state.reason == SpatialFailure.GeospatialUnsupported
        else -> false
    }

    /** Null means "tracking is fine, keep evaluating" — anything else is where classification stops. */
    private fun trackingDiagnosticOrNull(state: SpatialLocalizationState): ArPipelineDiagnostic? =
        when (state) {
            SpatialLocalizationState.Idle,
            SpatialLocalizationState.Initializing,
            SpatialLocalizationState.PermissionRequired,
            is SpatialLocalizationState.Limited,
            is SpatialLocalizationState.Failed,
            -> ArPipelineDiagnostic.TrackingNotReady

            // Unreachable in practice (isDeviceIncapable already returns for both), kept only so this
            // `when` stays exhaustive over the sealed state rather than relying on an `else`.
            SpatialLocalizationState.Unsupported, is SpatialLocalizationState.NotInstalled ->
                ArPipelineDiagnostic.ArCoreUnavailable

            is SpatialLocalizationState.Tracking -> {
                val quality = state.pose?.quality
                // Mirrors ARArrowConfig.minimumQuality's default floor: below this there is no usable
                // fix at all, as opposed to merely an imprecise one (see that property's doc for why
                // Poor itself is excluded here).
                if (quality == null || quality.ordinal <= LocalizationQuality.Acquiring.ordinal) {
                    ArPipelineDiagnostic.GeospatialAccuracyInsufficient
                } else {
                    null
                }
            }
        }
}
