package com.openmaps.app.core.anchor

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.spatial.LocalizationQuality
import com.openmaps.app.core.spatial.SpatialLocalizationState

/**
 * Backend that actually creates world anchors. Implemented once against ARCore
 * (`com.openmaps.app.anchor.ArCoreAnchorBackend`) and faked in tests.
 *
 * Keeping this behind an interface is what lets the whole lifecycle — window movement, pruning,
 * reroute replacement, quality gating — be tested on the JVM without any AR hardware, while the
 * real implementation still uses genuine `Earth.createAnchor` calls.
 */
interface AnchorBackend {
    /**
     * Creates (or returns an existing) world anchor at a geographic point.
     * [quaternion] is the route direction as an ARCore east-up-south quaternion.
     */
    fun createAnchor(
        key: Long,
        latitude: Double,
        longitude: Double,
        altitudeMeters: Double,
        quaternion: FloatArray,
    ): Boolean

    /** Current world pose for [key], or null if it is not tracked (or was never created). */
    fun worldPose(key: Long): WorldPose?

    fun trackingState(key: Long): AnchorTrackingState

    /** Releases the ARCore anchor. Must be idempotent. */
    fun detach(key: Long)

    /** Releases everything. */
    fun detachAll()

    /**
     * The camera's full world transform, needed to turn anchor world positions into view-relative
     * ones. Includes orientation: yaw is what makes arrows swing correctly when the phone turns.
     */
    fun cameraTransform(): WorldTransform?

    fun isAvailable(): Boolean
}

/**
 * Owns the set of live route anchors: which ones should exist, which must be pruned, and when
 * localization is good enough to create any at all.
 *
 * **Pure with respect to ARCore** — all real anchor work is delegated to [AnchorBackend], so this is
 * fully unit-testable. Its job is policy: sampling, windowing, churn control, quality gating and
 * cleanup.
 */
open class RouteAnchorManager(
    private val backend: AnchorBackend,
    private val config: RouteAnchorConfig = RouteAnchorConfig(),
) {

    private val sampler = RouteAnchorSampler(config)

    /**
     * key -> last known anchored point. The key is the sampler's **absolute grid index** (metres
     * along the route / `anchorGridMeters`), not a vertex index — see [RouteAnchorSampler] for why
     * that distinction is the whole difference between a stable anchor set and a per-update rebuild.
     */
    private val anchors = LinkedHashMap<Long, AnchoredRoutePoint>()

    /** Current route identity; a change invalidates the whole set (reroute / new destination). */
    private var routeKey: String? = null

    private var qualityCandidate: LocalizationQuality? = null
    private var qualityCount = 0
    private var lastAppliedQuality: LocalizationQuality = LocalizationQuality.Unavailable

    /** Instrumentation for the debug overlay; never logged. */
    var createdCount = 0
        private set
    var detachedCount = 0
        private set

    val size: Int get() = anchors.size

    fun anchorPoints(): List<AnchoredRoutePoint> = anchors.values.toList()

    /**
     * Brings the live anchor set in line with the current route and driver position.
     *
     * Call when route/progress/quality materially change — never per frame: creating an ARCore
     * anchor is a real cost and must not sit in the render loop.
     *
     * Phase 1 guidance gate: `isNavigating` here is the *guidance* gate, not the broad
     * trip-in-progress flag. Callers must pass true only while guidance is valid
     * (`NavigationState.Active`); passing broad trip-in-progress would keep anchors for a stale
     * route through OffRoute, Rerouting and Arrived. When false, the whole set is released
     * immediately by the `!isNavigating` branch below.
     *
     * @return the active anchored points, refreshed from the backend.
     */
    fun update(
        routeKey: String,
        geometry: List<Coordinate>,
        startIndex: Int,
        distanceToNextManeuverMeters: Double,
        remainingDistanceMeters: Double,
        routeDistanceAtIndex: DoubleArray?,
        spatialState: SpatialLocalizationState,
        isNavigating: Boolean,
    ): List<AnchoredRoutePoint> {
        // Route replaced (reroute or new destination): every old anchor describes the old road.
        if (routeKey != this.routeKey) {
            clear()
            this.routeKey = routeKey
        }

        if (!isNavigating || geometry.size < 2 || !backend.isAvailable()) {
            clear()
            return emptyList()
        }

        val quality = gateQuality(spatialState)

        // Degraded: stop creating, but keep existing anchors so a brief signal loss does not
        // visibly reset every arrow.
        if (quality.ordinal <= LocalizationQuality.Poor.ordinal) {
            if (!config.retainAnchorsWhenDegraded && quality == LocalizationQuality.Unavailable) clear()
            return refreshFromBackend()
        }

        val baseDistance = routeDistanceAtIndex?.getOrElse(startIndex.coerceIn(0, geometry.lastIndex)) { 0.0 } ?: 0.0
        val plans = sampler.plan(geometry, startIndex, distanceToNextManeuverMeters, routeDistanceAtIndex)

        // Apply the cap to the anchors **ahead** of the driver, not to the list head. The window also
        // contains anchors behind the driver (needed to interpolate the nearest arrow), and taking
        // those first would push the far anchors out of the cap — so as the driver advances, the
        // same far anchors would be dropped and recreated over and over. That churn is precisely what
        // makes anchored content shimmer.
        val (behind, ahead) = plans.partition { it.absoluteDistanceMeters < baseDistance }
        val wanted = ahead.take(config.maxAnchors) + behind
        val wantedKeys = wanted.map { it.key }.toSet()

        // Prune anything no longer wanted. Keys are absolute grid indices, so an anchor only leaves
        // the set when it genuinely leaves the window — never because the driver moved a metre.
        anchors.keys.toList().filter { it !in wantedKeys }.forEach { key ->
            backend.detach(key)
            anchors.remove(key)
            detachedCount++
        }

        // Create only what is missing. Existing anchors are reused, never recreated: this is the
        // churn control that keeps arrows stable while driving.
        for (plan in wanted) {
            if (anchors.containsKey(plan.key)) continue
            if (anchors.size >= config.maxAnchors) break
            val point = createFor(plan, baseDistance)
            if (point != null) anchors[plan.key] = point
        }

        return refreshFromBackend()
    }

    private fun createFor(
        plan: RouteAnchorSampler.AnchorPlan,
        baseDistance: Double,
    ): AnchoredRoutePoint? {
        val q = headingToQuaternion(plan.headingRadians)
        // Ellipsoid altitude is unknown before the anchor resolves, so anchors are created at
        // altitude 0 and lifted by the documented road offset. See RouteAnchorConfig.
        val ok = backend.createAnchor(plan.key, plan.latitude, plan.longitude, 0.0, q)
        if (!ok) return null
        createdCount++
        return AnchoredRoutePoint(
            id = plan.key.toInt(),
            anchorKey = plan.key,
            latitude = plan.latitude,
            longitude = plan.longitude,
            altitudeMeters = 0.0,
            heightAboveRoadMeters = config.roadHeightOffsetMeters,
            headingRadians = plan.headingRadians,
            worldPose = backend.worldPose(plan.key),
            trackingState = backend.trackingState(plan.key),
            distanceAheadMeters = plan.absoluteDistanceMeters - baseDistance,
        )
    }

    /** Refreshes cached points from the backend so world poses track ARCore over time. */
    private fun refreshFromBackend(): List<AnchoredRoutePoint> {
        val refreshed = anchors.mapNotNull { (key, point) ->
            val world = backend.worldPose(key)
            val state = backend.trackingState(key)
            if (world == null && state == AnchorTrackingState.Stopped) null
            else point.copy(worldPose = world, trackingState = state, anchorKey = key)
        }.toList()
        anchors.clear()
        // Re-key from the original key rather than the point's `routeIndex`: the key is a Long grid
        // index and must survive the round trip intact, or the set would quietly re-key itself.
        refreshed.forEach { anchors[it.anchorKey] = it }
        return refreshed
    }

    /**
     * Releases every anchor. Idempotent. Used on arrival, stop, leaving Live, and session release.
     *
     * Open so a test can observe that the *caller* actually releases resources at each of those
     * moments — the bug this guards against (an anchor set outliving the screen that drew it) is
     * invisible from inside the manager.
     */
    open fun clear() {
        backend.detachAll()
        anchors.clear()
        routeKey = null
        qualityCandidate = null
        qualityCount = 0
    }

    /** The camera's world transform, so consumers can build a view-relative transform from anchors. */
    fun backendCameraTransform(): WorldTransform? = backend.cameraTransform()

    /**
     * Hysteresis on localization quality. Degradation applies faster than improvement so a lost fix
     * stops anchor creation promptly, while a shaky fix does not start creating anchors.
     */
    private fun gateQuality(state: SpatialLocalizationState): LocalizationQuality {
        val observed = if (state is SpatialLocalizationState.Tracking) state.pose?.quality else null
        if (observed == null) return LocalizationQuality.Unavailable
        if (observed == lastAppliedQuality) {
            qualityCandidate = null
            qualityCount = 0
            return lastAppliedQuality
        }
        val degrading = observed.ordinal < lastAppliedQuality.ordinal
        val required = if (degrading) DEGRADE_STEPS else config.qualityHysteresisSteps
        if (observed == qualityCandidate) qualityCount++ else { qualityCandidate = observed; qualityCount = 1 }
        if (qualityCount >= required) {
            lastAppliedQuality = observed
            qualityCandidate = null
            qualityCount = 0
        }
        return lastAppliedQuality
    }

    private companion object {
        const val DEGRADE_STEPS = 2
    }
}

/**
 * Route direction as an ARCore **east-up-south** quaternion for
 * `Earth.createAnchor(lat, lon, alt, quaternion)`. Only the yaw component varies (the device is
 * assumed upright); x = sin(yaw/2), y = 0, z = 0, w = cos(yaw/2).
 */
fun headingToQuaternion(headingRadians: Double): FloatArray {
    val half = (headingRadians / 2.0).toFloat()
    return floatArrayOf(0f, kotlin.math.sin(half), 0f, kotlin.math.cos(half))
}