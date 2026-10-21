package com.openmaps.app.core.arrow

import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.core.routing.RouteManeuver
import com.openmaps.app.core.routing.RouteResult
import com.openmaps.app.core.spatial.LocalizationQuality
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The AR chevron placement rules — the part that decides what the driver actually sees on the road.
 *
 * Pure geometry, no ARCore, no device. This is where the visual rules get locked down: if spacing,
 * culling or quality gating is wrong, it is wrong in a way that only shows up while driving, which
 * is the worst place to find it.
 */
class ARArrowSamplerTest {

    private val sampler = ARArrowSampler(ARArrowConfig())

    /** Straight east-bound route: 11.13 m per 0.0001 degree at the equator. */
    private fun straightGeometry(count: Int = 20, stepDegrees: Double = 0.0001) =
        (0 until count).map { Coordinate(0.0, it * stepDegrees) }

    private fun progress(
        snappedShapeIndex: Int = 0,
        distanceToNextManeuver: Double = 500.0,
        remainingDistance: Double = 1000.0,
    ) = RouteProgress(
        snappedShapeIndex = snappedShapeIndex,
        lateralDistanceMeters = 2.0,
        distanceTraveledMeters = 0.0,
        remainingDistanceMeters = remainingDistance,
        remainingDurationSeconds = 300.0,
        progressFraction = 0.1,
        currentManeuver = maneuver(0),
        nextManeuver = maneuver(1),
        distanceToNextManeuverMeters = distanceToNextManeuver,
        isOffRoute = false,
    )

    private fun maneuver(index: Int) = RouteManeuver(
        instruction = "Maneuver $index",
        verbalInstruction = null,
        type = ManeuverType.Continue,
        streetName = null,
        distanceMeters = 100.0,
        beginShapeIndex = index * 3,
        endShapeIndex = index * 3,
    )

    private fun route(geometry: List<Coordinate>) =
        RouteResult(geometry = geometry, distanceMeters = 1000.0, durationSeconds = 300.0)

    // region Basic placement

    @Test
    fun sample_producesArrowsAheadOfTheUser() {
        val geometry = straightGeometry()
        val nodes = sampler.sample(geometry, progress(), LocalizationQuality.Good, remainingDistanceMeters = 1000.0)

        assertTrue("expected arrows", nodes.isNotEmpty())
        assertTrue("every arrow must be ahead", nodes.all { it.distanceAheadMeters > 0 })
    }

    @Test
    fun sample_noArrowsWhenNotNavigating() {
        val geometry = straightGeometry()
        assertTrue(sampler.sample(geometry, progress(), LocalizationQuality.Good).isNotEmpty())
        // No progress means no trip: nothing to point at.
        assertTrue(sampler.sample(geometry, null, LocalizationQuality.Good).isEmpty())
    }

    @Test
    fun sample_respectsMaximumArrowCount() {
        val geometry = straightGeometry(count = 200)
        val nodes = sampler.sample(geometry, progress(), LocalizationQuality.Good)
        assertTrue(
            "cap exceeded: ${nodes.size}",
            nodes.size <= ARArrowConfig().maxVisibleArrows,
        )
    }

    @Test
    fun sample_capsTheLookaheadWindow() {
        val geometry = straightGeometry(count = 500)
        val config = ARArrowConfig()
        val nodes = sampler.sample(geometry, progress(), LocalizationQuality.Good)

        nodes.forEach { node ->
            assertTrue(
                "arrow at ${node.distanceAheadMeters} m beyond the ${config.maxDistanceAheadMeters} m window",
                node.distanceAheadMeters <= config.maxDistanceAheadMeters,
            )
        }
    }

    @Test
    fun sample_neverPlacesAnArrowInsideTheNearCutoff() {
        val geometry = straightGeometry()
        val config = ARArrowConfig()
        val nodes = sampler.sample(geometry, progress(), LocalizationQuality.Good)

        nodes.forEach { node ->
            assertTrue(
                "arrow at ${node.distanceAheadMeters} m is inside the ${config.nearCutoffMeters} m cut-off",
                node.distanceAheadMeters >= config.nearCutoffMeters,
            )
        }
    }

    @Test
    fun sample_cullsArrowsBehindTheUser() {
        // Starting mid-route: no arrow may be sampled at or before the driver's own position.
        val geometry = straightGeometry()
        val nodes = sampler.sample(geometry, progress(snappedShapeIndex = 10), LocalizationQuality.Good)

        assertTrue(nodes.isNotEmpty())
        assertTrue(nodes.all { it.distanceAheadMeters > 0 })
        // The first arrow must be ahead of the route point the driver is snapped to.
        assertTrue(nodes.first().distanceAheadMeters > 0.0)
    }

    @Test
    fun sample_advancingProgressPushesArrowsForward() {
        val geometry = straightGeometry(count = 60)

        val early = sampler.sample(geometry, progress(snappedShapeIndex = 0), LocalizationQuality.Good)
        val late = sampler.sample(geometry, progress(snappedShapeIndex = 30), LocalizationQuality.Good)

        assertTrue(early.isNotEmpty() && late.isNotEmpty())
        // No stale arrows left behind: the nearest arrow moves forward with the driver.
        assertTrue(
            "arrows did not advance with progress",
            late.first().distanceAheadMeters >= early.first().distanceAheadMeters,
        )
    }

    @Test
    fun sample_sparseLongSegmentEmitsMultipleArrowsAtCorrectIntervals() {
        // Some routing providers return a single long straight segment. Sampling must not
        // render just one chevron and leave the rest of the visible road without guidance.
        val geometry = listOf(Coordinate(0.0, 0.0), Coordinate(0.0, 0.002))
        val nodes = sampler.sample(geometry, progress(), LocalizationQuality.Good)

        assertTrue("long segment should produce a sequence of arrows", nodes.size >= 6)
        assertTrue(nodes.size <= ARArrowConfig().maxVisibleArrows)
        assertTrue(nodes.all { it.distanceAheadMeters <= ARArrowConfig().maxDistanceAheadMeters })
        assertTrue(nodes.zipWithNext().all { (a, b) ->
            b.distanceAheadMeters > a.distanceAheadMeters &&
                b.worldPosition.longitude > a.worldPosition.longitude
        })
        nodes.forEach { assertEquals(90.0, Math.toDegrees(it.headingRadians), 1.0) }
    }

    @Test
    fun sample_sparseLeftTurnPlacesAndOrientsArrowsOnBothLegs() {
        // Eastbound then northbound is a left turn; both long legs must contain chevrons.
        val geometry = listOf(
            Coordinate(0.0, 0.0),
            Coordinate(0.0, 0.00035),
            Coordinate(0.00035, 0.00035),
        )
        val nodes = sampler.sample(geometry, progress(distanceToNextManeuver = 20.0), LocalizationQuality.Good)

        assertTrue("arrows missing on approach to left turn", nodes.any {
            Math.toDegrees(it.headingRadians) in 80.0..100.0
        })
        assertTrue("arrows missing on outbound northbound leg", nodes.any {
            Math.toDegrees(it.headingRadians) in -10.0..10.0
        })
    }

    @Test
    fun sample_sparseRightTurnPlacesAndOrientsArrowsOnBothLegs() {
        // Northbound then eastbound is a right turn.
        val geometry = listOf(
            Coordinate(0.0, 0.0),
            Coordinate(0.00035, 0.0),
            Coordinate(0.00035, 0.00035),
        )
        val nodes = sampler.sample(geometry, progress(distanceToNextManeuver = 20.0), LocalizationQuality.Good)

        assertTrue("arrows missing on approach to right turn", nodes.any {
            Math.toDegrees(it.headingRadians) in -10.0..10.0
        })
        assertTrue("arrows missing on outbound eastbound leg", nodes.any {
            Math.toDegrees(it.headingRadians) in 80.0..100.0
        })
    }

    @Test
    fun sample_degenerateGeometryProducesNothingRatherThanCrashing() {
        // Real polyline data contains duplicate points; this must not divide by zero or throw.
        val duplicated = listOf(
            Coordinate(0.0, 0.0),
            Coordinate(0.0, 0.0),
            Coordinate(0.0, 0.0001),
        )
        val nodes = sampler.sample(duplicated, progress(), LocalizationQuality.Good)
        assertEquals("duplicate vertex should not prevent placing the valid arrow", 1, nodes.size)

        // Fewer than two points cannot define a direction at all.
        assertTrue(sampler.sample(listOf(Coordinate(0.0, 0.0)), progress(), LocalizationQuality.Good).isEmpty())
    }

    @Test
    fun sample_spacingIsMeasuredAlongTheRouteNotCompounded() {
        // Regression: an earlier revision added `travelled` to an already-absolute target, which
        // inflated every gap after the first arrow (6 -> 23.5 -> 42.2 instead of 6 -> 12.4 -> 19.2)
        // and wasted most of the visibility window.
        val geometry = straightGeometry(count = 200)
        val config = ARArrowConfig()
        val nodes = sampler.sample(geometry, progress(), LocalizationQuality.Good)

        assertTrue(nodes.size >= 4)
        val gaps = nodes.zipWithNext { a, b -> b.distanceAheadMeters - a.distanceAheadMeters }
        gaps.forEach { gap ->
            assertTrue(
                "gap of ${"%.1f".format(gap)} m is outside the authored spacing band",
                gap >= config.spacingNearManeuverMeters * 0.9 && gap <= config.spacingFarMeters * 1.5,
            )
        }
    }

    // endregion

    // region Spacing

    @Test
    fun spacing_isDenserNearTheUserThanFarAhead() {
        val config = ARArrowConfig()
        val samplerUnderTest = ARArrowSampler(config)
        val near = samplerUnderTest.spacingAt(distanceAheadMeters = 8.0, approachingManeuver = false, nearDestination = false)
        val far = samplerUnderTest.spacingAt(distanceAheadMeters = 110.0, approachingManeuver = false, nearDestination = false)
        assertTrue("near spacing ($near) should be tighter than far ($far)", near < far)
    }

    @Test
    fun spacing_tightensApproachingAManeuver() {
        val samplerUnderTest = ARArrowSampler(ARArrowConfig())
        val normal = samplerUnderTest.spacingAt(60.0, approachingManeuver = false, nearDestination = false)
        val atTurn = samplerUnderTest.spacingAt(60.0, approachingManeuver = true, nearDestination = false)
        assertTrue("spacing must tighten at a turn", atTurn < normal)
    }

    @Test
    fun spacing_widensNearTheDestination() {
        val samplerUnderTest = ARArrowSampler(ARArrowConfig())
        val normal = samplerUnderTest.spacingAt(60.0, approachingManeuver = false, nearDestination = false)
        val nearEnd = samplerUnderTest.spacingAt(60.0, approachingManeuver = false, nearDestination = true)
        assertTrue("arrows should thin out at the destination", nearEnd > normal)
    }

    @Test
    fun spacing_neverCollapsesToZero() {
        val samplerUnderTest = ARArrowSampler(ARArrowConfig())
        val config = ARArrowConfig()
        val tightest = samplerUnderTest.spacingAt(5.0, approachingManeuver = true, nearDestination = false)
        assertTrue("spacing collapsed", tightest >= config.spacingNearManeuverMeters * 0.99)
    }

    // endregion

    // region Tangent / orientation

    @Test
    fun tangent_eastBoundRoutePointsDueEast() {
        val geometry = straightGeometry()
        val heading = sampler.tangentHeading(geometry, 2, 3)
        // Due east is a bearing of 90 degrees.
        assertEquals(90.0, Math.toDegrees(heading), 1.0)
    }

    @Test
    fun tangent_northBoundRoutePointsDueNorth() {
        val geometry = (0 until 20).map { Coordinate(it * 0.0001, 0.0) }
        val heading = sampler.tangentHeading(geometry, 2, 3)
        assertEquals(0.0, Math.toDegrees(heading), 1.0)
    }

    @Test
    fun tangent_southBoundRoutePointsDueSouth() {
        val geometry = (0 until 20).map { Coordinate(-it * 0.0001, 0.0) }
        val heading = sampler.tangentHeading(geometry, 2, 3)
        assertEquals(180.0, Math.toDegrees(heading), 1.0)
    }

    @Test
    fun tangent_duplicatePointsDoNotProduceNaN() {
        val geometry = listOf(
            Coordinate(0.0, 0.0),
            Coordinate(0.0, 0.0),
            Coordinate(0.0, 0.0),
        )
        val heading = sampler.tangentHeading(geometry, 1, 2)
        assertFalse("heading must never be NaN", heading.isNaN())
    }

    @Test
    fun tangent_outOfBoundsIndexesAreSafe() {
        val geometry = straightGeometry()
        assertFalse(sampler.tangentHeading(geometry, 99, 100).isNaN())
    }

    @Test
    fun tangent_followsASharpTurnRatherThanAveragingItAway() {
        // Coordinate(latitude, longitude): north leg, then an east leg. The arrow after the
        // corner must point east. (An earlier version of this test transposed lat/lon and so
        // described a straight northward line.)
        val geometry = listOf(
            Coordinate(0.0, 0.0),
            Coordinate(0.0002, 0.0), // corner: heading north into it
            Coordinate(0.0002, 0.0002), // heading east out of it
            Coordinate(0.0002, 0.0004),
        )
        val heading = sampler.tangentHeading(geometry, 1, 2)
        assertEquals("must follow the turn, not the previous leg", 90.0, Math.toDegrees(heading), 5.0)
    }

    @Test
    fun tangent_doesNotDeriveFromDeviceHeading() {
        // The sampler has no pose/heading input at all — direction comes only from geometry.
        val geometry = straightGeometry()
        val a = sampler.tangentHeading(geometry, 2, 3)
        val b = sampler.tangentHeading(geometry, 2, 3)
        assertEquals("heading must be deterministic from geometry", a, b, 0.0)
    }

    // endregion

    // region Scaling

    @Test
    fun scale_shrinksWithDistanceButNeverVanishes() {
        val samplerUnderTest = ARArrowSampler(ARArrowConfig())
        val config = ARArrowConfig()

        val near = samplerUnderTest.scaleAt(5.0)
        val far = samplerUnderTest.scaleAt(150.0)

        assertTrue("near should be larger", near > far)
        assertTrue("must not vanish", far >= config.minRadiusPx)
    }

    @Test
    fun scale_isClampedAtTheTop() {
        val samplerUnderTest = ARArrowSampler(ARArrowConfig())
        val config = ARArrowConfig()
        assertTrue(samplerUnderTest.scaleAt(0.0) <= config.maxRadiusPx)
    }

    @Test
    fun scale_neverExceedsBoundsAnywhereInTheWindow() {
        val samplerUnderTest = ARArrowSampler(ARArrowConfig())
        val config = ARArrowConfig()
        var d = 0.1
        while (d < 200.0) {
            val s = samplerUnderTest.scaleAt(d)
            assertTrue("scale $s out of bounds at ${d}m", s >= config.minRadiusPx && s <= config.maxRadiusPx)
            d *= 1.5
        }
    }

    // endregion

    // region Quality gating

    @Test
    fun quality_goodFairAndPoorDrawArrows_butAcquiringAndBelowDoNot() {
        val geometry = straightGeometry()
        assertTrue(sampler.sample(geometry, progress(), LocalizationQuality.Good).isNotEmpty())
        assertTrue(sampler.sample(geometry, progress(), LocalizationQuality.Fair).isNotEmpty())
        // Poor is a usable fix ("you are here"), not "no fix" — the sampler must still produce
        // candidate nodes so RouteAnchorManager's already-tracked anchors (created earlier under a
        // good fix, and stable regardless of live Geospatial jitter) can keep being projected through
        // a momentary Poor dip. See ARArrowConfig.minimumQuality's doc for the full reasoning; the
        // *unanchored* fallback path still refuses Poor separately, in ARArrowPipeline.
        assertTrue(sampler.sample(geometry, progress(), LocalizationQuality.Poor).isNotEmpty())
        // Acquiring and worse: no usable fix exists yet at all.
        assertTrue(sampler.sample(geometry, progress(), LocalizationQuality.Acquiring).isEmpty())
        assertTrue(sampler.sample(geometry, progress(), LocalizationQuality.Unavailable).isEmpty())
    }

    @Test
    fun quality_fairDrawsFewerArrowsThanGood() {
        // Long route so the reduced-density cap, not the visibility window, is what binds for Fair.
        val geometry = straightGeometry(count = 800)
        val good = sampler.sample(geometry, progress(), LocalizationQuality.Good).size
        val fair = sampler.sample(geometry, progress(), LocalizationQuality.Fair).size
        assertTrue("good produced no arrows", good > 0)
        assertTrue("reduced quality should reduce density ($fair vs $good)", fair < good)
    }

    @Test
    fun arrival_drawsNoArrows() {
        val geometry = straightGeometry()
        assertTrue(sampler.sample(geometry, progress(), LocalizationQuality.Good, isArrived = true).isEmpty())
    }

    @Test
    fun nearDestination_thinsDensityOut() {
        val geometry = straightGeometry(count = 200)
        val farFromEnd = sampler.sample(geometry, progress(), LocalizationQuality.Good, remainingDistanceMeters = 500.0).size
        val nearEnd = sampler.sample(geometry, progress(), LocalizationQuality.Good, remainingDistanceMeters = 5.0).size
        assertTrue("density should reduce near the destination ($nearEnd vs $farFromEnd)", nearEnd < farFromEnd)
    }

    @Test
    fun approachingManeuver_marksNodesSoTheRendererCanEmphasiseThem() {
        val geometry = straightGeometry(count = 60)
        val nodes = sampler.sample(geometry, progress(distanceToNextManeuver = 15.0), LocalizationQuality.Good)
        assertTrue(nodes.any { it.isApproachingManeuver })
    }

    // endregion
}