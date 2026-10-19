package com.openmaps.app.core.navigation

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.core.routing.RouteManeuver
import com.openmaps.app.core.routing.RouteResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Progress/off-route/camera behaviour against a synthetic north-bound route.
 *
 * The fixture is a straight line along the equator-ish (lat fixed, longitude increasing), so a
 * degree of longitude is a known distance and every assertion about "how far along" is checkable
 * by hand: 1 degree of longitude at the equator is ~111.32 km.
 */
class NavigationProgressTest {

    private fun straightRoute(
        points: Int = 10,
        maneuvers: Int = 3,
        stepDegrees: Double = 0.001,
    ): RouteResult {
        val geometry = (0 until points).map { Coordinate(0.0, it * stepDegrees) }
        // Evenly spaced maneuvers so each covers ~1/maneuvers of the route.
        val perManeuver = (points - 1) / maneuvers
        val routeManeuvers = (0 until maneuvers).map { index ->
            val begin = index * perManeuver
            val end = if (index == maneuvers - 1) points - 1 else begin + perManeuver - 1
            RouteManeuver(
                instruction = "Maneuver $index",
                verbalInstruction = null,
                type = if (index == maneuvers - 1) ManeuverType.Destination else ManeuverType.Continue,
                streetName = null,
                distanceMeters = (end - begin) * stepDegrees * METERS_PER_DEGREE,
                beginShapeIndex = begin,
                endShapeIndex = end,
            )
        }
        return RouteResult(
            geometry = geometry,
            distanceMeters = (points - 1) * stepDegrees * METERS_PER_DEGREE,
            durationSeconds = 600.0,
            maneuvers = routeManeuvers,
        )
    }

    private fun routeWithNoManeuvers() = RouteResult(
        geometry = listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 0.001)),
        distanceMeters = 111.32,
        durationSeconds = 60.0,
    )

    @Test
    fun locate_startOfRoute_reportsZeroProgressAndFirstManeuver() {
        val route = straightRoute()
        val progress = RouteProgressTracker(route).locate(Coordinate(0.0, 0.0))

        assertNotNull(progress)
        assertEquals(0, progress!!.snappedShapeIndex)
        assertEquals(0.0, progress.distanceTraveledMeters, 1.0)
        assertEquals(route.distanceMeters, progress.remainingDistanceMeters, 1.0)
        assertEquals(0.0, progress.progressFraction, 1e-9)
        assertEquals("Maneuver 0", progress.currentManeuver.instruction)
        assertFalse(progress.hasArrived)
    }

    @Test
    fun locate_halfway_reportsHalfProgressAndRemainingDistance() {
        val route = straightRoute()
        // Halfway along a 10-point route is index 4-5.
        val progress = RouteProgressTracker(route).locate(Coordinate(0.0, 0.0045))!!

        assertTrue(progress.progressFraction in 0.4..0.5)
        assertEquals(route.distanceMeters - progress.distanceTraveledMeters, progress.remainingDistanceMeters, 1.0)
        assertEquals(route.durationSeconds * progress.remainingDistanceMeters / route.distanceMeters, progress.remainingDurationSeconds, 1.0)
    }

    @Test
    fun locate_onAVertex_snapsForwardNotBackward() {
        // A fix exactly on a vertex is equidistant from the segments either side of it. It must
        // snap to the *later* one: snapping back would pin progress to the previous segment and put
        // the follow camera behind the driver.
        val route = straightRoute()
        val progress = RouteProgressTracker(route).locate(Coordinate(0.0, 0.002))!!

        assertEquals(2, progress.snappedShapeIndex)
        assertEquals(0.0, progress.lateralDistanceMeters, 1e-6)
    }

    @Test
    fun camera_onAVertex_leadsTheDriverNotTrailsThem() {
        val route = straightRoute()
        val userLongitude = 0.002
        val progress = RouteProgressTracker(route).locate(Coordinate(0.0, userLongitude))!!
        val target = NavigationCamera(route).targetFor(progress, headingDegrees = null)!!

        assertTrue("camera must lead the user up the route", target.longitude > userLongitude)
    }

    @Test
    fun locate_advancingProgress_neverRegressesEvenIfTheFixJumpsBackwards() {
        val tracker = RouteProgressTracker(straightRoute())

        val forward = tracker.locate(Coordinate(0.0, 0.005))!!
        // A stale or backwards GPS fix must not rewind the banner.
        val backwards = tracker.locate(Coordinate(0.0, 0.001))!!

        assertTrue(backwards.distanceTraveledMeters >= forward.distanceTraveledMeters)
    }

    @Test
    fun locate_onRoute_hasNearZeroLateralDistance() {
        val progress = RouteProgressTracker(straightRoute()).locate(Coordinate(0.0, 0.003))!!
        assertTrue("expected on-route, got ${progress.lateralDistanceMeters} m", progress.lateralDistanceMeters < 1.0)
    }

    @Test
    fun locate_farOffRoute_reportsTheFullLateralDistance() {
        // 0.001 degrees of latitude north is ~111 m; the threshold for "off route" is 75 m.
        val progress = RouteProgressTracker(straightRoute()).locate(Coordinate(0.002, 0.003))!!
        assertTrue("expected >100 m off route, got ${progress.lateralDistanceMeters}", progress.lateralDistanceMeters > 100.0)
    }

    @Test
    fun locate_exposesProjectedRoadCoordinateAndHighConfidence() {
        val rawFix = Coordinate(0.00005, 0.0035)
        val progress = RouteProgressTracker(straightRoute()).locate(rawFix, headingDegrees = 90.0)!!

        assertNotNull(progress.snappedCoordinate)
        assertEquals(0.0, progress.snappedCoordinate!!.latitude, 1e-7)
        assertEquals(0.0035, progress.snappedCoordinate!!.longitude, 1e-6)
        assertEquals(MapMatchConfidence.High, progress.mapMatchConfidence)
    }

    @Test
    fun locate_headingDisambiguatesParallelRoads() {
        // Route travels east, makes a short connector north, then returns west on a parallel road
        // about 11 m away. The raw fix is exactly between both roads. Pure distance would prefer
        // the later (westbound) segment due to forward tie-breaking; an eastward device bearing
        // must keep the match on the eastbound road.
        val geometry = listOf(
            Coordinate(0.0000, 0.0000),
            Coordinate(0.0000, 0.0040),
            Coordinate(0.0001, 0.0040),
            Coordinate(0.0001, 0.0000),
        )
        val route = RouteResult(
            geometry = geometry,
            distanceMeters = 900.0,
            durationSeconds = 300.0,
            maneuvers = listOf(
                RouteManeuver(
                    instruction = "Continue",
                    verbalInstruction = null,
                    type = ManeuverType.Continue,
                    streetName = null,
                    distanceMeters = 900.0,
                    beginShapeIndex = 0,
                    endShapeIndex = 3,
                ),
            ),
        )
        val fix = Coordinate(0.00005, 0.0020)

        val eastbound = RouteProgressTracker(route).locate(fix, headingDegrees = 90.0)!!
        val westbound = RouteProgressTracker(route).locate(fix, headingDegrees = 270.0)!!

        assertEquals("east heading should snap to outbound segment", 0, eastbound.snappedShapeIndex)
        assertEquals("west heading should snap to return segment", 2, westbound.snappedShapeIndex)
    }

    @Test
    fun locate_farOffRoute_reportsOffRouteMatchConfidence() {
        val progress = RouteProgressTracker(straightRoute())
            .locate(Coordinate(0.002, 0.003), headingDegrees = 90.0)!!

        assertEquals(MapMatchConfidence.OffRoute, progress.mapMatchConfidence)
    }

    @Test
    fun locate_atTheEnd_reportsArrival() {
        val route = straightRoute()
        val progress = RouteProgressTracker(route).locate(Coordinate(0.0, 0.009))!!

        assertEquals(ManeuverType.Destination, progress.currentManeuver.type)
        assertTrue(progress.remainingDistanceMeters <= 10.0)
        assertTrue(progress.hasArrived)
    }

    @Test
    fun locate_routeWithoutManeuvers_returnsNull() {
        // Nothing to navigate step-by-step, so no progress is reported rather than a bogus one.
        assertNull(RouteProgressTracker(routeWithNoManeuvers()).locate(Coordinate(0.0, 0.0005)))
    }

    @Test
    fun locate_routeWithNoUsableGeometry_returnsNull() {
        val route = straightRoute().copy(geometry = listOf(Coordinate(0.0, 0.0)))
        assertNull(RouteProgressTracker(route).locate(Coordinate(0.0, 0.0)))
    }

    @Test
    fun offRoute_singleBadSample_doesNotConfirm() {
        val session = NavigationSession(straightRoute())
        val progress = RouteProgressTracker(session.route).locate(Coordinate(0.002, 0.003))!!

        // One noisy fix far off the line must never flip state or hit the network.
        assertEquals(OffRouteDecision.OnRoute, session.updateOffRoute(progress))
    }

    @Test
    fun offRoute_consecutiveBadSamples_confirmThenReroute() {
        val session = NavigationSession(straightRoute())
        val offRoute = RouteProgressTracker(session.route).locate(Coordinate(0.002, 0.003))!!

        assertEquals(OffRouteDecision.OnRoute, session.updateOffRoute(offRoute))
        assertEquals(OffRouteDecision.OffRouteConfirmed, session.updateOffRoute(offRoute))
        repeat(OffRouteDetector.DEFAULT_REROUTE_SAMPLES - OffRouteDetector.DEFAULT_CONFIRM_SAMPLES) {
            session.updateOffRoute(offRoute)
        }
        assertEquals(OffRouteDecision.TriggerReroute, session.updateOffRoute(offRoute))
    }

    @Test
    fun offRoute_returningToRoute_resetsTheStreakImmediately() {
        val detector = OffRouteDetector()
        detector.update(true)
        detector.update(true)
        assertEquals(OffRouteDecision.OnRoute, detector.update(false))
        // The streak restarted, so a single new bad sample must not immediately reroute.
        assertEquals(OffRouteDecision.OnRoute, detector.update(true))
    }

    @Test
    fun offRoute_reset_startsCountingFresh() {
        val detector = OffRouteDetector()
        repeat(OffRouteDetector.DEFAULT_REROUTE_SAMPLES) { detector.update(true) }
        detector.reset()
        assertEquals(OffRouteDecision.OnRoute, detector.update(true))
    }

    @Test
    fun camera_lookAheadPoint_isAheadOnTheRouteNotOnTheUser() {
        val route = straightRoute()
        val userLongitude = 0.002
        val progress = RouteProgressTracker(route).locate(Coordinate(0.0, userLongitude))!!
        val target = NavigationCamera(route).targetFor(progress, headingDegrees = null)!!

        // The route continues to 0.009; a follow camera must sit up the road from the user, not on
        // top of them, or there is no road ahead visible.
        assertTrue("camera must lead the user up the route", target.longitude > userLongitude)
        assertTrue("camera must stay on the route", target.longitude <= route.geometry.last().longitude)
    }

    @Test
    fun camera_withoutHeading_usesTheRouteDirectionNorthbound() {
        val route = straightRoute()
        val progress = RouteProgressTracker(route).locate(Coordinate(0.0, 0.002))!!
        val target = NavigationCamera(route).targetFor(progress, headingDegrees = null)!!

        // Due east is a bearing of 90.
        assertEquals(90.0, target.bearingDegrees, 1.0)
    }

    @Test
    fun camera_withDeviceHeading_prefersItOverTheRoute() {
        val route = straightRoute()
        val progress = RouteProgressTracker(route).locate(Coordinate(0.0, 0.002))!!
        val target = NavigationCamera(route).targetFor(progress, headingDegrees = 275.0)!!

        assertEquals(275.0, target.bearingDegrees, 0.001)
    }

    @Test
    fun camera_bearingIsNormalizedToZeroThreeSixty() {
        val route = straightRoute()
        val progress = RouteProgressTracker(route).locate(Coordinate(0.0, 0.002))!!
        val target = NavigationCamera(route).targetFor(progress, headingDegrees = -90.0)!!
        assertTrue(target.bearingDegrees in 0.0..360.0)
        assertEquals(270.0, target.bearingDegrees, 0.001)
    }

    @Test
    fun camera_farOffRoute_holdsThePreviousBearingInsteadOfSwingingAtTheLine() {
        val route = straightRoute()
        // 0.004 deg of latitude is ~445 m: past the 250 m blend distance, so the route's own
        // direction is no longer a useful thing to point the camera at.
        val progress = RouteProgressTracker(route).locate(Coordinate(0.004, 0.003))!!
        assertTrue(progress.lateralDistanceMeters > 250.0)
        val target = NavigationCamera(route).targetFor(progress, headingDegrees = null, fallbackBearing = 42.0)!!

        assertEquals(42.0, target.bearingDegrees, 0.001)
    }

    @Test
    fun camera_nearTheRoute_keepsUsingTheRouteDirection() {
        // The counterpart to the test above: a modest excursion (inside the blend distance) should
        // still aim the camera back up the route, because that is the direction the driver wants.
        val route = straightRoute()
        val progress = RouteProgressTracker(route).locate(Coordinate(0.002, 0.003))!!
        assertTrue(progress.lateralDistanceMeters < 250.0)
        val target = NavigationCamera(route).targetFor(progress, headingDegrees = null, fallbackBearing = 42.0)!!

        assertEquals(90.0, target.bearingDegrees, 1.0)
    }

    @Test
    fun camera_nearTheEndOfTheRoute_doesNotOvershootTheDestination() {
        val route = straightRoute()
        val progress = RouteProgressTracker(route).locate(Coordinate(0.0, 0.0089))!!
        val target = NavigationCamera(route).targetFor(progress, headingDegrees = null)!!

        assertTrue(
            "camera must stay at or before the destination",
            target.longitude <= route.geometry.last().longitude + 1e-9,
        )
    }

    @Test
    fun camera_emptyRouteGeometry_returnsNull() {
        val route = straightRoute().copy(geometry = emptyList())
        val progress = RouteProgressTracker(straightRoute()).locate(Coordinate(0.0, 0.0))!!
        assertNull(NavigationCamera(route).targetFor(progress, headingDegrees = null))
    }

    private companion object {
        const val METERS_PER_DEGREE = 111_320.0
    }
}