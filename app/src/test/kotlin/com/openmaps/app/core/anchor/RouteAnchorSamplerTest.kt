package com.openmaps.app.core.anchor

import com.openmaps.app.core.routing.Coordinate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * Anchor control-point sampling.
 *
 * The rules here are invisible on a desktop and obvious in a car: too dense and ARCore is swamped
 * (and arrows thrash), too coarse and a turn is drawn as a straight line. So the metric spacing,
 * the duplicate suppression and the heading across corners are all pinned down here.
 */
class RouteAnchorSamplerTest {

    private val sampler = RouteAnchorSampler()

    private val mpd = RouteAnchorConfig.METERS_PER_DEGREE

    /** A straight run northwards of [lengthMeters], as [count] evenly spaced vertices. */
    private fun straightNorth(lengthMeters: Double, count: Int): List<Coordinate> =
        (0..count).map { Coordinate(latitude = it * (lengthMeters / count) / mpd, longitude = 0.0) }

    private fun distanceMeters(a: Coordinate, b: Coordinate): Double {
        val mid = Math.toRadians((a.latitude + b.latitude) / 2.0)
        val dLat = (b.latitude - a.latitude) * mpd
        val dLon = (b.longitude - a.longitude) * mpd * Math.cos(mid)
        return Math.sqrt(dLat * dLat + dLon * dLon)
    }

    @Test
    fun plan_shortRoute_hasNoPlans() {
        assertTrue(sampler.plan(listOf(Coordinate(0.0, 0.0)), 0, 100.0).isEmpty())
    }

    @Test
    fun plan_straightRoute_placesAnchorsAllAroundTheDriver() {
        // Anchors are windowed from the driver's position, not from the start of the route: a
        // driver at 150 m must get anchors near 150 m, not anchors spanning the whole 300 m.
        val geometry = straightNorth(300.0, 300)
        val driverAt = 150.0
        val plans = sampler.plan(geometry, startIndex = 150, distanceToNextManeuverMeters = 500.0)
        assertTrue("expected at least one anchor", plans.isNotEmpty())

        // Signed offset along the route: negative is behind the driver.
        val offsets = plans.map { it.absoluteDistanceMeters - driverAt }
        val config = RouteAnchorConfig()
        assertTrue(
            "anchors span ${offsets.minOrNull()}..${offsets.maxOrNull()}m around the driver, expected a window around 0",
            offsets.any { it > 0.0 } && offsets.any { it < 0.0 },
        )
        offsets.forEach { offset ->
            assertTrue("anchor at ${offset}m is outside the window", abs(offset) <= config.aheadWindowMeters + 1.0)
        }
        // The nearest anchor ahead must be close enough that the first arrow (past a 6 m near
        // cut-off) can be interpolated rather than dropped.
        val nearestAhead = offsets.filter { it > 0.0 }.minOrNull() ?: Double.MAX_VALUE
        assertTrue(
            "nearest anchor ahead was $nearestAhead m",
            nearestAhead <= config.spacingStraightMeters + config.anchorGridMeters,
        )
    }

    @Test
    fun plan_neverPlacesAnchorsBeyondTheLookAheadWindow() {
        // A 600 m route viewed from the start: anchoring the destination 600 m ahead would describe
        // road nothing is drawing, and cost a real ARCore object.
        val geometry = straightNorth(600.0, 600)
        val plans = sampler.plan(geometry, startIndex = 0, distanceToNextManeuverMeters = 5_000.0)
        assertTrue("expected anchors inside the window", plans.isNotEmpty())
        val config = RouteAnchorConfig()
        plans.forEach { plan ->
            assertTrue(
                "anchor at ${plan.absoluteDistanceMeters}m is outside the ${config.aheadWindowMeters}m window",
                plan.absoluteDistanceMeters <= config.aheadWindowMeters + config.anchorGridMeters,
            )
        }
    }

    @Test
    fun plan_spacingIsMetricAndUniformOnAStreight() {
        // Uniform by design: a driver-relative spacing would re-create the whole anchor set on every
        // position update, because the same road would yield different control points over time.
        val geometry = straightNorth(400.0, 400)
        val plans = sampler.plan(geometry, startIndex = 0, distanceToNextManeuverMeters = 5_000.0)
        assertTrue(plans.size >= 4)

        val config = RouteAnchorConfig()
        val gaps = plans.zipWithNext { a, b -> b.absoluteDistanceMeters - a.absoluteDistanceMeters }
        gaps.forEach { gap ->
            assertEquals("gap ${gap}m should be the straight-run spacing", config.spacingStraightMeters, gap, config.anchorGridMeters)
        }
    }

    @Test
    fun plan_spacingTightensOnTheApproachToAManeuver() {
        // Extra control points before a turn are what stop the interpolated arrows cutting the
        // corner. The maneuver must sit inside the look-ahead window for this to be observable.
        val geometry = straightNorth(400.0, 400)
        val config = RouteAnchorConfig()
        val maneuverAt = 120.0

        val plans = sampler.plan(geometry, startIndex = 0, distanceToNextManeuverMeters = maneuverAt)
        val approach = plans.filter { it.absoluteDistanceMeters in (maneuverAt - config.maneuverApproachBandMeters + 1)..maneuverAt }
        val plain = plans.filter { it.absoluteDistanceMeters < maneuverAt - config.maneuverApproachBandMeters - 1 }

        val approachGaps = approach.zipWithNext { a, b -> b.absoluteDistanceMeters - a.absoluteDistanceMeters }
        val plainGaps = plain.zipWithNext { a, b -> b.absoluteDistanceMeters - a.absoluteDistanceMeters }
        assertTrue("expected tightened anchors on the approach", approachGaps.isNotEmpty())
        assertTrue("expected ordinary anchors before the band", plainGaps.isNotEmpty())
        assertTrue(
            "approach spacing ${approachGaps.min()}m should be tighter than straight-run ${plainGaps.min()}m",
            approachGaps.min() < plainGaps.min(),
        )
        approachGaps.forEach { gap ->
            assertEquals(config.spacingNearManeuverMeters, gap, config.anchorGridMeters)
        }
    }

    @Test
    fun plan_suppressesNearIdenticalConsecutiveAnchors() {
        // A polyline with a duplicated vertex pair: the grid must not place anchors on top of each
        // other, which would cost real ARCore objects and define nothing.
        val geometry = listOf(
            Coordinate(0.0, 0.0),
            Coordinate(0.0, 0.0),
            Coordinate(50.0 / mpd, 0.0),
        )
        val plans = sampler.plan(geometry, startIndex = 0, distanceToNextManeuverMeters = 5_000.0)
        val gaps = plans.zipWithNext { a, b -> distanceMeters(Coordinate(a.latitude, a.longitude), Coordinate(b.latitude, b.longitude)) }
        assertTrue("anchors should never be placed on top of each other: $gaps", gaps.all { it >= 1.0 })
    }

    @Test
    fun plan_neverPlacesAnchorsBeforeTheStartOfTheRoute() {
        // Grid indices below zero are before the route begins. Clamping them to 0 would stack
        // several anchors on the very first vertex, which is both wasteful and meaningless.
        val geometry = straightNorth(300.0, 300)
        val plans = sampler.plan(geometry, startIndex = 0, distanceToNextManeuverMeters = 5_000.0)
        assertTrue(plans.all { it.absoluteDistanceMeters >= 0.0 })
        assertEquals("anchor 0 may appear once", 1, plans.count { it.absoluteDistanceMeters == 0.0 })
    }

    @Test
    fun plan_includesTheDestination_whenItIsInsideTheWindow() {
        val geometry = straightNorth(120.0, 120)
        val plans = sampler.plan(geometry, startIndex = 0, distanceToNextManeuverMeters = 5_000.0)
        val destination = geometry.last()
        assertEquals(destination.latitude, plans.last().latitude, 1e-9)
        assertEquals(destination.longitude, plans.last().longitude, 1e-9)
    }

    @Test
    fun plan_anchorPositionsAreIndependentOfDriverPosition() {
        // The stability property the whole layer exists for: an anchor's position is a function of
        // the route alone. If this fails, every location update moves every anchor and the arrows
        // reset instead of sitting still.
        val geometry = straightNorth(400.0, 400)
        val first = sampler.plan(geometry, startIndex = 0, distanceToNextManeuverMeters = 5_000.0)
            .associate { it.key to Coordinate(it.latitude, it.longitude) }
        val later = sampler.plan(geometry, startIndex = 120, distanceToNextManeuverMeters = 5_000.0)
            .associate { it.key to Coordinate(it.latitude, it.longitude) }

        val shared = first.keys.intersect(later.keys)
        assertTrue("precondition: some anchors are common to both windows", shared.isNotEmpty())
        shared.forEach { key ->
            assertEquals("anchor $key moved when the driver moved", first.getValue(key), later.getValue(key))
        }
    }

    @Test
    fun plan_tightensSpacingApproachingAManeuver() {
        val geometry = straightNorth(400.0, 400)
        val far = sampler.plan(geometry, startIndex = 0, distanceToNextManeuverMeters = 5_000.0)
        val approaching = sampler.plan(geometry, startIndex = 0, distanceToNextManeuverMeters = 5.0)
        assertTrue(
            "approaching a maneuver should produce at least as many control points",
            approaching.size >= far.size,
        )
    }

    @Test
    fun plan_maneuverBandIsAbsoluteSoItDoesNotSlideWithTheDriver() {
        // The band must be anchored to the road, not the car: if it slid, every anchor near the turn
        // would be destroyed and recreated as the driver approached it.
        val geometry = straightNorth(400.0, 400)
        val maneuverAbsolute = 250.0

        val early = sampler.plan(geometry, startIndex = 0, distanceToNextManeuverMeters = maneuverAbsolute)
            .associate { it.key to it.absoluteDistanceMeters }
        val later = sampler.plan(geometry, startIndex = 100, distanceToNextManeuverMeters = maneuverAbsolute - 100.0)
            .associate { it.key to it.absoluteDistanceMeters }

        early.keys.intersect(later.keys).forEach { key ->
            assertEquals("anchor $key moved as the driver approached the maneuver", early.getValue(key), later.getValue(key), 1e-9)
        }
    }

    @Test
    fun heading_straightNorth_isNorth() {
        val geometry = straightNorth(100.0, 100)
        val plan = sampler.plan(geometry, 0, 5_000.0).first()
        assertEquals(0.0, Math.toDegrees(plan.headingRadians), 1.0)
    }

    @Test
    fun heading_straightEast_isEast() {
        val geometry = (0..100).map { Coordinate(0.0, it * (100.0 / 100) / mpd) }
        val plan = sampler.plan(geometry, 0, 5_000.0).first()
        assertEquals(90.0, Math.toDegrees(plan.headingRadians), 1.0)
    }

    @Test
    fun heading_afterAQuarterTurn_turnsWithTheRoad() {
        // 50 m north, then 50 m east. An anchor on the second leg must read east, not north.
        val geometry = listOf(
            Coordinate(0.0, 0.0),
            Coordinate(50.0 / mpd, 0.0),
            Coordinate(50.0 / mpd, 50.0 / mpd),
        )
        val plans = sampler.plan(geometry, startIndex = 0, distanceToNextManeuverMeters = 5_000.0)
        val eastLeg = plans.last()
        assertEquals(
            "the final anchor should face east along the second leg",
            90.0,
            Math.toDegrees(eastLeg.headingRadians),
            5.0,
        )
    }

    @Test
    fun heading_duplicatePointsDoNotProduceNorthByDefault() {
        // Two identical vertices then a northward leg: the sampler must find the real direction
        // rather than silently defaulting to 0 rad, which would point an arrow the wrong way.
        val geometry = listOf(
            Coordinate(0.0, 0.0),
            Coordinate(0.0, 0.0),
            Coordinate(40.0 / mpd, 0.0),
        )
        val plans = sampler.plan(geometry, startIndex = 0, distanceToNextManeuverMeters = 5_000.0)
        assertEquals(0.0, Math.toDegrees(plans.last().headingRadians), 5.0)
    }

    @Test
    fun plan_startingPartWayThroughTheRoute_doesNotAnchorBehindTheDriver() {
        val geometry = straightNorth(400.0, 400)
        val startPoint = geometry[200]
        val plans = sampler.plan(geometry, startIndex = 200, distanceToNextManeuverMeters = 5_000.0)
        assertTrue(plans.isNotEmpty())
        plans.forEach { plan ->
            val ahead = distanceMeters(startPoint, Coordinate(plan.latitude, plan.longitude))
            assertTrue("anchor was $ahead m from the start index", ahead >= -1.0)
        }
    }
}
