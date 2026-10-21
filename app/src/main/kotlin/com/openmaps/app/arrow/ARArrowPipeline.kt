package com.openmaps.app.arrow

import com.openmaps.app.core.anchor.AnchoredRoutePoint
import com.openmaps.app.core.anchor.WorldTransform
import com.openmaps.app.core.arrow.ARArrowConfig
import com.openmaps.app.core.arrow.AnchoredArrowProjector
import com.openmaps.app.core.arrow.ARArrowProjector
import com.openmaps.app.core.arrow.ARArrowQualityGate
import com.openmaps.app.core.arrow.ARArrowSampler
import com.openmaps.app.core.arrow.ScreenArrow
import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.core.routing.RouteResult
import com.openmaps.app.core.spatial.SpatialPose
import com.openmaps.app.core.spatial.SpatialLocalizationState

/**
 * Drives the AR chevrons: owns the sampler, the quality gate and the current projected set, and
 * exposes a plain [ArCoreSpatialLocalization.ArOverlayRenderer] for the AR engine to call on the GL
 * thread.
 *
 * Kept out of the ViewModel and out of the AR engine on purpose. Neither should know about arrows:
 * the ViewModel's job is navigation and camera *policy*, and the AR engine's is owning the ARCore
 * session and the camera. This sits between them and can be replaced or disabled wholesale.
 *
 * **It is additive.** If anything here fails — no pose, poor quality, no route, GL not ready — the
 * arrow set is simply empty and Live carries on with the camera and HUD exactly as in Phase 6.
 */
class ARArrowPipeline(
    private val config: ARArrowConfig = ARArrowConfig(),
) : com.openmaps.app.core.spatial.arcore.ArCoreSpatialLocalization.ArOverlayRenderer {

    private var sampler = ARArrowSampler(config)
    private val gate = ARArrowQualityGate(config)
    private val renderer = ARArrowRenderer()

    /**
     * Swaps the sampler's spacing/look-ahead/max-count/turn-density config (Phase 11 environment
     * tuning — see `EnvironmentNavigationProfile.arrowConfig`). [ARArrowSampler] is pure and
     * stateless, so replacing it is safe mid-trip; this deliberately does **not** touch [gate] (the
     * quality-hysteresis fields are not part of what environment tunes) or anything in
     * `RouteAnchorManager` — anchor lifecycle stays exactly as Phase 7/8 left it.
     */
    fun updateArrowConfig(newConfig: ARArrowConfig) {
        sampler = ARArrowSampler(newConfig)
    }

    /**
     * The projected arrows for the most recent frame. Volatile because it is written on the main
     * thread (route/propose changes) and read on the GL thread (draw).
     */
    @Volatile
    private var current: List<ScreenArrow> = emptyList()

    /**
     * True when the current arrow set came from real ARCore world anchors, false when it came from
     * the per-frame geographic projection fallback. Surfaced in the debug overlay so degraded mode is
     * never mistaken for working spatial anchoring.
     */
    @Volatile
    var usingAnchors: Boolean = false
        private set

    /**
     * Why the anchored path is not in use, or null when it is. Diagnostic only — the reason exists
     * so "arrows are showing but they slide" is explainable rather than mysterious, and so a field
     * report of degraded mode carries its cause.
     */
    @Volatile
    var degradedReason: String? = null
        private set

    /** Nodes [ARArrowSampler] produced before projection/culling — debug overlay only (section A8). */
    @Volatile
    private var lastSampledCount: Int = 0

    /** True once [onSurfaceCreated] has run; before that there is no GL context to draw with. */
    private var glReady = false

    @Volatile private var viewportWidthPx: Int = 0
    @Volatile private var viewportHeightPx: Int = 0

    // region Inputs (main thread)

    /**
     * Recomputes the arrow set. Called when the route, the driver's progress, or localization
     * materially changes — deliberately *not* per frame. Sampling walks the route, so doing it every
     * frame would be the single most expensive thing in the AR loop for no visual benefit.
     *
     * **Sampling always runs.** Spacing, turn density, the near cut-off, the visible cap and the
     * tangent heading all come from [ARArrowSampler] and are identical on both paths, so the two
     * paths never disagree about *which* arrows exist or *which way* they point — only about where
     * in space those arrows are pinned. That is what makes the fallback a degraded placement, not a
     * different navigation feature.
     *
     * Placement path, in strict priority order:
     *  1. **Anchored** (preferred, see [usingAnchors]): real ARCore world poses from
     *     [anchoredPoints], interpolated between control points and projected through the camera's
     *     world transform by [AnchoredArrowProjector]. Spatially stable — the road stays put when
     *     the phone moves.
     *  2. **Projection fallback** (degraded, see [degradedReason]): per-frame lat/lng via
     *     [ARArrowProjector], recomputed against the current pose. Used *only* when anchoring cannot
     *     be used. It is never silently preferred while anchors are healthy: if a tracked anchor set
     *     is supplied and produces arrows, that set is what is drawn.
     */
    fun update(
        route: RouteResult?,
        progress: RouteProgress?,
        pose: SpatialPose?,
        spatialState: SpatialLocalizationState,
        viewportWidthPx: Int,
        viewportHeightPx: Int,
        isNavigating: Boolean,
        anchoredPoints: List<AnchoredRoutePoint> = emptyList(),
        cameraTransform: WorldTransform? = null,
    ) {
        this.viewportWidthPx = viewportWidthPx
        this.viewportHeightPx = viewportHeightPx
        // Nothing to draw without a usable pose *and* a route. Bail early and clear, so a stale set
        // can never linger after the trip ends or the route is replaced.
        if (!isNavigating || route == null || pose == null || spatialState !is SpatialLocalizationState.Tracking) {
            clear()
            return
        }

        val rawQuality = spatialState.pose?.quality
        if (rawQuality == null) {
            clear()
            return
        }
        val quality = gate.observe(rawQuality)

        // Phase 8 sampling: unchanged, and shared by both placement paths.
        val remaining = progress?.remainingDistanceMeters ?: Double.MAX_VALUE
        val nodes = sampler.sample(
            geometry = route.geometry,
            progress = progress,
            quality = quality,
            isArrived = false,
            remainingDistanceMeters = remaining,
        )
        lastSampledCount = nodes.size
        if (nodes.isEmpty()) {
            current = emptyList()
            usingAnchors = false
            return
        }

        // --- Preferred path: true ARCore world anchors ---
        val anchoredUsable = AnchoredArrowProjector.isUsable(anchoredPoints) && cameraTransform != null
        if (anchoredUsable) {
            val projected = AnchoredArrowProjector.projectAll(
                nodes = nodes,
                anchors = anchoredPoints,
                camera = cameraTransform!!,
                viewportWidthPx = viewportWidthPx,
                viewportHeightPx = viewportHeightPx,
            )
            // Only fall through if anchoring genuinely produced nothing. Note this also covers the
            // case where the anchor window does not yet reach as far as the arrows do: those arrows
            // are dropped rather than extrapolated, and the ones inside the window are still drawn
            // from real world positions.
            if (projected.isNotEmpty()) {
                usingAnchors = true
                degradedReason = null
                current = projected
                return
            }
        }

        // --- Degraded path: per-frame geographic projection ---
        usingAnchors = false
        degradedReason = when {
            anchoredPoints.isEmpty() -> "no anchors created yet"
            !AnchoredArrowProjector.isUsable(anchoredPoints) -> "anchors not yet tracked"
            cameraTransform == null -> "no camera world transform"
            else -> "anchors did not cover the arrow window"
        }
        current = ARArrowProjector.project(nodes, pose, viewportWidthPx, viewportHeightPx)
    }

    /**
     * Drops every arrow immediately. Called on arrival, on navigation stop, and on route
     * replacement — the three moments where keeping the previous set would show stale arrows for the
     * road just driven, or for a route no longer being driven.
     */
    fun clear() {
        current = emptyList()
        gate.reset()
        usingAnchors = false
        degradedReason = null
        lastSampledCount = 0
    }

    /**
     * Called when the route is replaced (reroute). Resets the gate so quality does not carry over
     * from the old route, but leaves the GL resources alone — rebuilding the renderer here would
     * churn GPU memory for no reason.
     *
     * Note the caller is responsible for dropping the Phase 7 anchors too (see
     * `LiveViewModel.onRouteReplaced`): the arrows and the anchors are two halves of the same
     * derived state and neither is meaningful on its own.
     */
    fun onRouteReplaced() {
        gate.reset()
        current = emptyList()
        usingAnchors = false
        degradedReason = null
        lastSampledCount = 0
    }

    // endregion

    // region Overlay callbacks (GL thread)

    override fun onSurfaceCreated() {
        renderer.onSurfaceCreated()
        glReady = true
    }

    override fun hasDrawableContent(): Boolean =
        glReady && current.isNotEmpty() && viewportWidthPx > 0 && viewportHeightPx > 0

    override fun drawFrame(alpha: Float) {
        if (!hasDrawableContent()) return
        renderer.draw(current, alpha, viewportWidthPx, viewportHeightPx)
    }

    // endregion

    /** Diagnostics for the debug overlay; not shown in release. */
    fun arrowCount(): Int = current.size

    /** Nodes sampled before projection/culling — "sampled N / visible N" in the debug overlay. */
    fun sampledCount(): Int = lastSampledCount

    /** Nearest drawn arrow's distance in metres, or null when nothing is drawn. */
    fun nearestArrowMeters(): Double? = current.minOfOrNull { it.node.distanceAheadMeters }
}