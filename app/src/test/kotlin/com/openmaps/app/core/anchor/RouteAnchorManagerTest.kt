package com.openmaps.app.core.anchor

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.spatial.LocalizationQuality
import com.openmaps.app.core.spatial.SpatialLocalizationState
import com.openmaps.app.core.spatial.SpatialPose
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Anchor lifecycle: creation, reuse, pruning, reroute replacement and cleanup.
 *
 * All of it runs against a fake [AnchorBackend], which is the reason the anchor backend is an
 * interface at all. These are the behaviours that are hardest to observe on a real device and most
 * expensive to get wrong — anchor churn is invisible until a phone is mounted in a windscreen
 * holder, at which point it is a very visible stutter.
 */
class RouteAnchorManagerTest {

    /**
     * Stand-in for ARCore's `Earth.createAnchor`. Records every call so the tests can assert on
     * churn, which is the single most important property of this layer: anchors must be *reused*,
     * not recreated, as the driver moves.
     */
    private class FakeAnchorBackend : AnchorBackend {
        val created = LinkedHashMap<Long, Triple<Double, Double, Double>>()
        val detached = mutableListOf<Long>()
        var detachAllCalls = 0

        /** World poses keyed by anchor key; null means "created but not yet tracked". */
        val poses = LinkedHashMap<Long, WorldPose>()

        /** When false, `isAvailable` reports unavailable, as on a non-AR device. */
        var available = true

        /** When false, anchor creation fails, as it does transiently when Earth is not ready. */
        var creationSucceeds = true

        var camera: WorldTransform? = WorldTransform(WorldPose(0.0, 0.0, 0.0))

        var createCallCount = 0
            private set

        override fun createAnchor(
            key: Long,
            latitude: Double,
            longitude: Double,
            altitudeMeters: Double,
            quaternion: FloatArray,
        ): Boolean {
            createCallCount++
            if (!creationSucceeds) return false
            created[key] = Triple(latitude, longitude, altitudeMeters)
            // Anchors take a moment to resolve; leaving the pose absent models that honestly.
            poses.remove(key)
            return true
        }

        override fun worldPose(key: Long): WorldPose? = poses[key]
        override fun trackingState(key: Long): AnchorTrackingState =
            if (key in created) AnchorTrackingState.Tracking else AnchorTrackingState.Stopped

        override fun detach(key: Long) {
            detached += key
            created.remove(key)
            poses.remove(key)
        }

        override fun detachAll() {
            detachAllCalls++
            created.keys.toList().forEach { detached += it }
            created.clear()
            poses.clear()
        }

        override fun cameraTransform(): WorldTransform? = camera
        override fun isAvailable(): Boolean = available
    }

    private val mpd = RouteAnchorConfig.METERS_PER_DEGREE

    private fun straightNorth(lengthMeters: Double, perMeter: Int = 1): List<Coordinate> =
        (0..(lengthMeters * perMeter).toInt()).map { Coordinate(it / perMeter / mpd, 0.0) }

    private fun cumulative(geometry: List<Coordinate>): DoubleArray {
        val out = DoubleArray(geometry.size)
        for (i in 1 until geometry.size) {
            val a = geometry[i - 1]
            val b = geometry[i]
            val mid = Math.toRadians((a.latitude + b.latitude) / 2.0)
            val dLat = (b.latitude - a.latitude) * mpd
            val dLon = (b.longitude - a.longitude) * mpd * Math.cos(mid)
            out[i] = out[i - 1] + Math.sqrt(dLat * dLat + dLon * dLon)
        }
        return out
    }

    private fun tracking(quality: LocalizationQuality) = SpatialLocalizationState.Tracking(
        SpatialPose(
            latitude = 0.0,
            longitude = 0.0,
            altitudeMeters = 5.0,
            headingDegrees = 0.0,
            horizontalAccuracyMeters = 2.0,
            verticalAccuracyMeters = 2.0,
            headingAccuracyDegrees = 1.0,
            quality = quality,
        ),
    )

    private val goodState = tracking(LocalizationQuality.Good)

    private fun update(
        manager: RouteAnchorManager,
        routeKey: String = "route-a",
        geometry: List<Coordinate>,
        startIndex: Int = 0,
        state: SpatialLocalizationState = goodState,
        isNavigating: Boolean = true,
        distanceToNextManeuver: Double = 5_000.0,
    ): List<AnchoredRoutePoint> = manager.update(
        routeKey = routeKey,
        geometry = geometry,
        startIndex = startIndex,
        distanceToNextManeuverMeters = distanceToNextManeuver,
        remainingDistanceMeters = Double.MAX_VALUE,
        routeDistanceAtIndex = cumulative(geometry),
        spatialState = state,
        isNavigating = isNavigating,
    )

    // region Availability and quality gating

    @Test
    fun update_backendUnavailable_createsNothing() {
        val backend = FakeAnchorBackend().apply { available = false }
        val manager = RouteAnchorManager(backend)
        val points = update(manager, geometry = straightNorth(300.0))
        assertTrue("no anchors can exist without a backend", points.isEmpty())
        assertEquals(0, backend.createCallCount)
    }

    @Test
    fun update_qualityStartsUnavailable_createsNothingOnTheFirstCall() {
        // Hysteresis: an unreviewed fix must not immediately buy world anchors.
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        update(manager, geometry = straightNorth(300.0), state = tracking(LocalizationQuality.Fair))
        assertEquals("anchors must wait for sustained quality", 0, backend.createCallCount)
    }

    @Test
    fun update_sustainedFairQuality_eventuallyCreatesAnchors() {
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        val geometry = straightNorth(300.0)
        val fair = tracking(LocalizationQuality.Fair)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = geometry, state = fair) }
        assertTrue("sustained Fair quality should create anchors", backend.createCallCount > 0)
    }

    @Test
    fun update_qualityDegradesToPoor_retainsExistingAnchors() {
        // Losing the fix briefly must not destroy and recreate every anchor: that visibly resets
        // the whole arrow set, which is worse than leaving the last good positions up.
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        val geometry = straightNorth(300.0)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = geometry) }
        val createdBefore = backend.created.size
        assertTrue("precondition: anchors exist", createdBefore > 0)

        val poor = tracking(LocalizationQuality.Poor)
        repeat(4) { update(manager, geometry = geometry, state = poor) }
        assertEquals("existing anchors must be retained when quality drops", createdBefore, backend.created.size)
        assertTrue("nothing should have been detached", backend.detached.isEmpty())
    }

    // endregion

    // region Creation, reuse and churn

    @Test
    fun update_firstCall_createsAnchorsWithinTheCap() {
        // Quality must clear its hysteresis before any anchor is created, so this is repeated.
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        val geometry = straightNorth(600.0)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = geometry) }
        assertTrue("expected anchors", backend.created.isNotEmpty())
        assertTrue("cap is ${RouteAnchorConfig().maxAnchors}, got ${backend.created.size}", backend.created.size <= RouteAnchorConfig().maxAnchors)
    }

    @Test
    fun update_unchangedRoute_doesNotRecreateAnchors() {
        // The churn control that matters most: standing still must cost zero new anchors.
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        val geometry = straightNorth(300.0)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = geometry) }
        val afterFirstSettle = backend.createCallCount
        assertTrue("precondition: anchors were created", afterFirstSettle > 0)

        repeat(20) { update(manager, geometry = geometry) }
        assertEquals("a stationary driver must not trigger new anchors", afterFirstSettle, backend.createCallCount)
    }

    @Test
    fun update_advancingAlongTheRoute_reusesAheadAnchorsAndOnlyAddsNewOnes() {
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        val geometry = straightNorth(600.0)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = geometry) }
        val firstSet = backend.created.keys.toSet()
        assertTrue("precondition: anchors exist", firstSet.isNotEmpty())

        // Drive 30 m along a 1 m-per-vertex route.
        update(manager, geometry = geometry, startIndex = 30)

        val survivorsOfFirstSet = firstSet.intersect(backend.created.keys)
        // Keys are absolute grid indices, so every anchor ahead of the new position keeps its exact
        // identity and is reused. This is the property that stops the arrow set resetting itself on
        // every location update.
        assertTrue(
            "only the anchors that fell behind the window should have been dropped (kept $survivorsOfFirstSet of $firstSet)",
            survivorsOfFirstSet.size >= firstSet.size - 3,
        )
        assertTrue("the anchors left behind must have been detached", backend.detached.isNotEmpty())
    }

    @Test
    fun update_advancingOneMetreAtATime_createsAlmostNothing() {
        // The churn guard, stated as a budget: 100 location updates over 100 m must not come near
        // creating 100 anchors. Anything close to that is the per-update rebuild bug returning.
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        val geometry = straightNorth(400.0)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = geometry) }
        val afterFirstSettle = backend.createCallCount

        repeat(100) { step -> update(manager, geometry = geometry, startIndex = step + 1) }
        val createdWhileDriving = backend.createCallCount - afterFirstSettle
        assertTrue(
            "100 one-metre updates created $createdWhileDriving anchors; the set must not be rebuilt per update",
            createdWhileDriving <= 20,
        )
    }

    @Test
    fun update_creationFailure_isToleratedAndRetriedLater() {
        // ARCore refuses anchor creation while Earth is still converging; that must degrade, not
        // crash, and must not permanently poison the manager.
        val backend = FakeAnchorBackend().apply { creationSucceeds = false }
        val manager = RouteAnchorManager(backend)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = straightNorth(300.0)) }
        assertTrue("creation was attempted", backend.createCallCount > 0)
        assertEquals("nothing should be tracked", 0, manager.size)

        backend.creationSucceeds = true
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = straightNorth(300.0)) }
        assertTrue("anchors must be created once the backend recovers", manager.size > 0)
    }

    @Test
    fun update_neverExceedsTheAnchorCap() {
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        val geometry = straightNorth(2_000.0)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) {
            update(manager, geometry = geometry, startIndex = it * 10)
        }
        assertTrue("precondition: anchors exist", manager.size > 0)
        assertTrue("live anchor count ${manager.size} exceeded the cap", manager.size <= RouteAnchorConfig().maxAnchors)
    }

    // endregion

    // region Reroute, arrival and cleanup

    @Test
    fun update_newRouteKey_replacesEveryOldAnchor() {
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        val geometry = straightNorth(300.0)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = geometry, routeKey = "route-a") }
        val oldKeys = backend.created.keys.toSet()
        val oldLocations = backend.created.toMap()
        assertTrue("precondition: anchors exist", oldKeys.isNotEmpty())

        // A genuinely different road (here: an eastward one), under a new route key.
        val newGeometry = (0..300).map { Coordinate(0.0, it / mpd) }
        update(manager, geometry = newGeometry, routeKey = "route-b")

        assertTrue("every old anchor must be detached on a reroute", oldKeys.all { it in backend.detached })
        // Grid keys are route-relative, so a new route may legitimately reuse a key — including key
        // 0, which is the shared start point. What must never happen is a key beyond the first
        // keeping the *old road's* coordinates: that is the ghost anchor that leaves an arrow
        // floating over the road just abandoned.
        backend.created.filterKeys { it > 0L }.forEach { (key, location) ->
            val old = oldLocations[key]
            assertTrue(
                "key $key still carries its pre-reroute position",
                old == null || old != location,
            )
        }
        assertTrue("the new road must have its own anchors", backend.created.isNotEmpty())
        assertTrue(
            "the new anchors must sit on the new (eastward) road",
            backend.created.values.any { it.second != 0.0 },
        )
    }

    @Test
    fun update_navigationStopped_clearsEveryAnchor() {
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        val geometry = straightNorth(300.0)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = geometry) }
        assertTrue("precondition: anchors exist", manager.size > 0)

        update(manager, geometry = geometry, isNavigating = false)
        assertEquals("stopping navigation must release all anchors", 0, manager.size)
        assertTrue("the backend must have been told to detach everything", backend.detachAllCalls > 0)
    }

    @Test
    fun clear_isIdempotent() {
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = straightNorth(300.0)) }
        manager.clear()
        manager.clear()
        assertEquals(0, manager.size)
    }

    @Test
    fun clear_thenUpdate_rebuildsFromScratch() {
        // Arrival clears, then a new trip starts: the manager must not believe the old route is
        // still current and refuse to recreate anything.
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = straightNorth(300.0)) }
        manager.clear()
        val createdBefore = backend.createCallCount
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = straightNorth(300.0)) }
        assertTrue("a new trip must create its own anchors", backend.createCallCount > createdBefore)
    }

    @Test
    fun anchorPoints_reportDistanceAheadRelativeToTheDriver() {
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        val geometry = straightNorth(600.0)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = geometry, startIndex = 100) }
        val distances = manager.anchorPoints().mapNotNull { it.distanceAheadMeters }
        val config = RouteAnchorConfig()
        assertTrue("precondition: distances are known", distances.isNotEmpty())
        assertTrue(
            "anchors must not reach further behind than the behind window: ${distances.minOrNull()}",
            distances.min() >= -config.behindWindowMeters - 1.0,
        )
        assertTrue("anchors must stay inside the look-ahead window", distances.all { it <= config.aheadWindowMeters + 1.0 })
        // The first Phase 8 arrow sits past a 6 m near cut-off, so an anchor must exist just behind
        // the driver or that arrow falls outside the window and is dropped every frame.
        assertTrue(
            "no anchor behind the driver: $distances",
            distances.any { it < 0.0 },
        )
    }

    @Test
    fun anchorPoints_worldPoseIsNullUntilTheBackendTracksIt() {
        // A freshly created ARCore anchor has no pose for a few frames. Exposing it as tracked
        // before then would place arrows from a guess, which is the misleading-arrow case.
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = straightNorth(300.0)) }
        assertTrue(manager.anchorPoints().all { !it.isSpatiallyTracked })

        backend.poses[manager.anchorPoints().first().anchorKey] = WorldPose(1.0, 2.0, 3.0)
        update(manager, geometry = straightNorth(300.0))
        assertTrue(manager.anchorPoints().any { it.isSpatiallyTracked })
    }

    @Test
    fun headingToQuaternion_encodesYawInTheYComponent() {
        // ARCore anchors take an east-up-south quaternion; only yaw varies for a road heading, so
        // x and z must stay zero, and y = sin(yaw/2), w = cos(yaw/2). A wrong component puts the
        // anchor on its side, which would tilt every arrow derived from it.
        val q = headingToQuaternion(Math.toRadians(90.0))
        val half = Math.toRadians(45.0)
        assertEquals(4, q.size)
        assertEquals(0f, q[0], 1e-6f)
        assertEquals(Math.sin(half).toFloat(), q[1], 1e-6f)
        assertEquals(0f, q[2], 1e-6f)
        assertEquals(Math.cos(half).toFloat(), q[3], 1e-6f)
    }

    @Test
    fun headingToQuaternion_isIdentityForNorth() {
        val q = headingToQuaternion(0.0)
        assertEquals(0f, q[1], 1e-6f)
        assertEquals(1f, q[3], 1e-6f)
    }

    @Test
    fun createdAndDetached_areExposedForDiagnostics() {
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) { update(manager, geometry = straightNorth(300.0)) }
        assertTrue("createdCount should track real anchor creation", manager.createdCount > 0)
        assertFalse("nothing has moved yet, so nothing should be detached", manager.detachedCount > 0)
    }

    // region Phase 1 guidance gate

    @Test
    fun update_notStarted_createsNoAnchors() {
        // Destination selected but navigation never started: the gate is off from the first
        // update, so no anchor may be created for a route nobody is driving.
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) {
            update(manager, geometry = straightNorth(300.0), isNavigating = false)
        }
        assertEquals("no anchors may exist before Start", 0, manager.size)
        assertEquals("no anchor creation may even be attempted", 0, backend.createCallCount)
    }

    @Test
    fun update_activeOffRouteReroutingActiveReplacement_keepsOnlyReplacementAnchors() {
        // The Live call site passes guidance-active (NavigationState.Active) as isNavigating:
        // OffRoute and Rerouting arrive here as false. The stale route's anchors must be released
        // at the OffRoute transition — not kept until the replacement route happens to arrive —
        // and the replacement route must then build a set that sits on the new road only.
        val backend = FakeAnchorBackend()
        val manager = RouteAnchorManager(backend)
        val geometryA = straightNorth(300.0)
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) {
            update(manager, geometry = geometryA, routeKey = "route-a")
        }
        val oldKeys = backend.created.keys.toSet()
        assertTrue("precondition: anchors exist", oldKeys.isNotEmpty())

        // OffRoute: guidance inactive, same (stale) route still supplied.
        update(manager, geometry = geometryA, routeKey = "route-a", isNavigating = false)
        assertEquals("OffRoute must release the stale route's anchors", 0, manager.size)
        assertTrue("every old anchor must be detached", oldKeys.all { it in backend.detached })

        // Rerouting: still inactive, no new route yet.
        update(manager, geometry = geometryA, routeKey = "route-a", isNavigating = false)
        assertEquals("Rerouting must stay anchor-free", 0, manager.size)

        // Replacement route arrives and guidance resumes: only the new road's anchors may exist.
        val geometryB = (0..300).map { Coordinate(0.0, it / mpd) }
        repeat(RouteAnchorConfig().qualityHysteresisSteps + 1) {
            update(manager, geometry = geometryB, routeKey = "route-b")
        }
        assertTrue("the replacement route must have its own anchors", manager.size > 0)
        assertTrue(
            "the new anchors must sit on the new (eastward) road",
            manager.anchorPoints().any { it.longitude != 0.0 },
        )
    }

    // endregion
}
