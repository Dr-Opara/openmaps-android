package com.openmaps.app.core.anchor

import com.openmaps.app.core.routing.Coordinate

/**
 * Provider-neutral route-anchoring types. Deliberately free of `com.google.ar.core` types, mirroring
 * how `core/spatial` and `core/arrow` keep their engines out of the UI: no `Anchor`, `Earth` or
 * `Session` ever crosses this boundary. The only ARCore-aware file is
 * `com.openmaps.app.anchor.ArCoreAnchorBackend`.
 */

/**
 * A position in ARCore's world frame (metres). This is the thing that stays put in the world while
 * the device moves — the whole reason anchoring exists. A re-projected lat/lng never has this
 * property, because it is recomputed from a moving pose every frame.
 */
data class WorldPose(
    val x: Double,
    val y: Double,
    val z: Double,
) {
    fun distanceTo(other: WorldPose): Double =
        kotlin.math.sqrt((x - other.x) * (x - other.x) + (y - other.y) * (y - other.y) + (z - other.z) * (z - other.z))
}

/**
 * A full pose in ARCore's world frame: a position plus the orientation that maps camera-local axes
 * into it, stored as a Hamilton quaternion in ARCore's east-up-south convention.
 *
 * Anchors are points, so they need only [WorldPose]. The **camera** needs the rotation too, and it is
 * the rotation that makes AR work: without the camera's yaw, turning the phone would not move the
 * arrows at all, which is the single most obvious way to get world-locked content wrong.
 *
 * Deliberately four named floats rather than a `FloatArray`: a data class holding an array has
 * identity-based equality, which makes these silently unusable as map keys or in tests.
 */
data class WorldTransform(
    val position: WorldPose,
    val qx: Double = 0.0,
    val qy: Double = 0.0,
    val qz: Double = 0.0,
    val qw: Double = 1.0,
) {
    /**
     * Rotates a world-space offset into camera space, where ARCore uses x right, y up and z
     * *backward* — so a point in front of the camera has negative z and depth is `-z`.
     *
     * This is the rotation by the conjugate of [qw] (the inverse), i.e. world -> camera.
     */
    fun toCameraSpace(vx: Double, vy: Double, vz: Double): DoubleArray {
        // Inverse quaternion.
        val cx = -qx
        val cy = -qy
        val cz = -qz
        val cw = qw
        // v' = v + 2 * cross(q.xyz, cross(q.xyz, v) + q.w * v)
        val tx = 2.0 * (cy * vz - cz * vy)
        val ty = 2.0 * (cz * vx - cx * vz)
        val tz = 2.0 * (cx * vy - cy * vx)
        return doubleArrayOf(
            vx + cw * tx + (cy * tz - cz * ty),
            vy + cw * ty + (cz * tx - cx * tz),
            vz + cw * tz + (cx * ty - cy * tx),
        )
    }

    /** Camera-space offset from [other] (a world point) to this transform's origin. */
    fun offsetFrom(world: WorldPose): DoubleArray = toCameraSpace(
        world.x - position.x,
        world.y - position.y,
        world.z - position.z,
    )
}

/** Anchor tracking, provider-neutral. Mirrors ARCore's TrackingState. */
enum class AnchorTrackingState {
    /** ARCore knows where this point is. */
    Tracking,

    /** The point is known but not yet refined. */
    Paused,

    /** Stopped / no usable pose. */
    Stopped,
}

/**
 * One anchored route control point.
 *
 * Both coordinate representations are carried deliberately: [latitude]/[longitude] are what the
 * anchor was *created from*, and [worldPose] is where it has *settled*. They drift apart over time —
 * that drift is precisely the spatial stability ARCore anchors buy, and losing it by trusting only
 * the input lat/lng would defeat the purpose.
 */
data class AnchoredRoutePoint(
    /** Stable, derived from the anchor grid key so the same road point keeps its identity. */
    val id: Int,
    /**
     * The absolute anchor grid key (metres along the route / `anchorGridMeters`).
     *
     * Kept as a Long because route distances in metres overflow Int well before the cap matters,
     * and it is the identity the manager reuses anchors on — see [RouteAnchorSampler].
     */
    val anchorKey: Long = id.toLong(),
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double,
    /** Metres above the road plane at this point; see [RouteAnchorConfig.roadHeightOffsetMeters]. */
    val heightAboveRoadMeters: Double,
    /** Route direction at this point, radians clockwise from north. Never from device heading. */
    val headingRadians: Double,
    /** Null until ARCore reports a tracked world pose for this anchor. */
    val worldPose: WorldPose?,
    val trackingState: AnchorTrackingState,
    /** Distance along the route from the driver, when known. */
    val distanceAheadMeters: Double?,
) {
    /** Route-vertex index, retained for the arrow renderer's stable per-sample id. */
    val routeIndex: Int get() = id
    /** True only when this point can be used for world-space placement. */
    val isSpatiallyTracked: Boolean
        get() = worldPose != null && trackingState == AnchorTrackingState.Tracking

    val coordinate: Coordinate get() = Coordinate(latitude, longitude)
}

/**
 * Anchor window / sampling policy.
 *
 * Anchors are expensive (each is a real ARCore object with a server-side refinement), so the whole
 * design goal is **sparse control points**, not dense ones. Phase 8 interpolates arrows between
 * them, which is why these numbers can be far larger than the arrow spacing.
 */
data class RouteAnchorConfig(
    /**
     * The absolute grid that anchor positions snap to, in metres along the route.
     *
     * This is the stability mechanism: anchor *n* always sits at `n * anchorGridMeters` regardless of
     * where the driver is, so driving forward adds anchors at the far end of the window and retires
     * them at the near end, rather than moving (and therefore recreating) the whole set. Must be
     * comfortably smaller than the tightest spacing in [spacingAt], or density control collapses.
     */
    val anchorGridMeters: Double = 5.0,

    /**
     * Spacing between control points on an ordinary straight run.
     *
     * Deliberately coarse: each anchor is a real, server-refined ARCore object, and a road's
     * direction is fully defined by a control point every 15 m. It is also **uniform along the
     * route** rather than tightening toward the driver — see [RouteAnchorSampler] for why a
     * driver-relative spacing re-creates the entire anchor set on every position update.
     */
    val spacingStraightMeters: Double = 15.0,

    /** Spacing on the approach to a maneuver, where a coarse control point rounds off the corner. */
    val spacingNearManeuverMeters: Double = 5.0,

    /**
     * How far back from a maneuver the tightened spacing applies, in absolute route metres.
     *
     * This is what makes a turn read as a turn: without extra control points on the approach, the
     * arrows interpolated between two anchors would cut the corner.
     */
    val maneuverApproachBandMeters: Double = 40.0,

    /**
     * How far behind progress anchors are kept.
     *
     * Not merely cosmetic: Phase 8 interpolates arrows *between* control points, and the first arrow
     * sits about `ARArrowConfig.nearCutoffMeters` (6 m) ahead. Without anchors behind the driver,
     * that first arrow falls outside the anchored window and is dropped every frame.
     */
    val behindWindowMeters: Double = 15.0,

    /** How far ahead of progress anchors are created. Must cover Phase 8's arrow window (120 m). */
    val aheadWindowMeters: Double = 160.0,

    /**
     * Hard cap on live anchors *ahead of the driver*.
     *
     * The real cost control: a 175 m window at 15 m spacing needs about 12 anchors, and near a
     * maneuver rather more. The cap bounds both ARCore's work and memory without ever being the
     * thing that decides whether a turn is drawn correctly.
     */
    val maxAnchors: Int = 20,

    /**
     * Vertical offset applied to every anchor, in metres.
     *
     * **This is a constant, not road elevation.** ARCore's terrain/rooftop anchors are available in
     * the API but are far less reliable than geospatial ones (they need extra imagery support and are
     * frequently unavailable), so this layer anchors geospatially at the *Earth ellipsoid* height and
     * lifts by this offset. A fixed lift reads as an arrow sitting on the road; zero would bury it in
     * the surface. It is documented as a limitation rather than presented as true elevation.
     */
    val roadHeightOffsetMeters: Double = 0.35,

    /** Frames of sustained good quality before anchors are created. */
    val qualityHysteresisSteps: Int = 5,

    /**
     * When quality drops to [com.openmaps.app.core.spatial.LocalizationQuality.Poor] or below, anchors
     * are retained rather than destroyed, so a momentary signal loss does not destroy and recreate
     * every anchor (which would visibly reset the arrows).
     */
    val retainAnchorsWhenDegraded: Boolean = true,
) {
    /**
     * Control-point spacing at an **absolute** route distance.
     *
     * Absolute is the whole point: see [RouteAnchorSampler]. A driver-relative spacing would make
     * the same stretch of road produce different anchors at different moments, which is the same as
     * saying an anchor's position depends on when you asked for it.
     */
    internal fun spacingAt(absoluteDistanceMeters: Double, inManeuverBand: Boolean): Double =
        if (inManeuverBand) spacingNearManeuverMeters else spacingStraightMeters

    companion object {
        /** Segments shorter than this are degenerate (duplicate polyline points). */
        const val DEGENERATE_SEGMENT_METERS = 0.05
        const val METERS_PER_DEGREE = 111_320.0
    }
}