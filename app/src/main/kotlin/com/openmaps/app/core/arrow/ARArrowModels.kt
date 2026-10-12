package com.openmaps.app.core.arrow

import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.spatial.LocalizationQuality
import kotlin.math.PI

/**
 * One AR chevron, in provider-neutral terms.
 *
 * Deliberately holds **geographic** coordinates plus a route tangent rather than a screen position:
 * the geographic position is the thing that stays meaningful across a pose update, and projecting it
 * is a separate, separately testable step (`ARArrowProjector`). A node whose position were already
 * projected could not be re-used when the device moves, only re-created.
 */
data class ARArrowNode(
    /** Stable within a route sample; used for diffing between frames, not for AR tracking. */
    val id: Int,
    val worldPosition: Coordinate,
    /** Direction of travel along the route, radians clockwise from north. */
    val headingRadians: Double,
    /** Metres ahead of the user along the route. Always > 0 — arrows are never behind. */
    val distanceAheadMeters: Double,
    /** False when the node is inside the near cut-off (too close to read / under the user's chin). */
    val visibility: Boolean,
    /** Visual scale multiplier, already distance-adjusted by the sampler. */
    val scale: Float,
    /** True when this arrow is close enough to a maneuver that the turn should read clearly. */
    val isApproachingManeuver: Boolean,
)

/**
 * A node after projection into the current camera view — what the renderer actually draws.
 */
data class ScreenArrow(
    val node: ARArrowNode,
    val x: Float,
    val y: Float,
    val radiusPx: Float,
    /** False when behind the device, past the field of view, or off the near plane. */
    val onScreen: Boolean,
)

/**
 * All tunable arrow behaviour in one place.
 *
 * Every value here is a *decision about how the route reads while driving*, which is exactly the
 * kind of number that needs field tuning, so none of them are buried as literals in the sampler.
 * Defaults are chosen for a city street at ~30 km/h and are documented with the reasoning.
 */
data class ARArrowConfig(
    // region Sampling window

    /** Arrows further than this along the route are dropped; they are too small to read. */
    val maxDistanceAheadMeters: Double = 120.0,

    /**
     * First arrow's distance. Kept off zero so an arrow never renders directly under the user's
     * chin, where it would fill the screen and block the road.
     */
    val nearCutoffMeters: Double = 6.0,

    /**
     * Hard cap on simultaneous arrows. The camera scene must stay the dominant visual, and this is
     * also the real performance bound (one GL draw call is batched, but each node still costs).
     */
    val maxVisibleArrows: Int = 12,

    // endregion

    // region Spacing

    /**
     * Spacing for arrows well ahead, on a straight run. Wide: at 110 m out an arrow is small, and
     * densely packed arrows there read as noise rather than as a path.
     */
    val spacingFarMeters: Double = 14.0,

    /**
     * Spacing right in front of the user. Tighter than [spacingFarMeters] so the immediate path is
     * unambiguous at the moment the driver is choosing a line.
     */
    val spacingNearMeters: Double = 6.0,

    /**
     * Within this distance of the next maneuver, spacing tightens again. A turn is the only place
     * where the exact path is ambiguous, so it is the only place that deserves the extra detail.
     */
    val spacingNearManeuverMeters: Double = 4.0,

    /** How far from a maneuver the "approaching" band starts. */
    val maneuverLookaheadMeters: Double = 25.0,

    // endregion

    // region Scale

    /** Screen radius of an arrow at the reference distance, in dp-ish pixels. */
    val baseRadiusPx: Float = 26f,

    /** Arrows at or nearer this distance render at [baseRadiusPx]. */
    val scaleReferenceDistanceMeters: Double = 10.0,

    /**
     * Hard bounds on scale. The lower bound matters most: without it a distant arrow collapses to a
     * sub-pixel dot and the path visually breaks up just when the driver is looking furthest ahead.
     */
    val minRadiusPx: Float = 11f,
    val maxRadiusPx: Float = 54f,

    // endregion

    // region Localization gating

    /** Below this quality no arrows are drawn at all. */
    val minimumQuality: LocalizationQuality = LocalizationQuality.Poor,

    /**
     * At [Localizability] fair, density is reduced rather than quality reduced: fewer, larger arrows
     * are safer than many flickering ones when the fix is only moderately good.
     */
    val reducedDensityFactor: Double = 0.5,

    /**
     * Quality must be this good for [reducedDensityFactor] to stop applying. Combined with
     * [qualityHysteresisSteps] this stops the density oscillating when accuracy hovers at a boundary.
     */
    val fullDensityQuality: LocalizationQuality = LocalizationQuality.Good,

    /**
     * Consecutive frames a quality change must persist before it is applied. Localization accuracy
     * jitters frame to frame; without this the arrow set would visibly flicker between densities.
     */
    val qualityHysteresisSteps: Int = 5,

    /**
     * Frames required for quality to get *worse*. Shorter than [qualityHysteresisSteps] because a
     * lost fix should drop arrows promptly — but still more than one, since applying a degradation
     * on a single frame was itself enough to make a wobbling signal flicker the arrow density.
     */
    val qualityDegradeSteps: Int = 2,

    // endregion

    // region Vertical placement

    /**
     * Height above the road surface, in metres, used when placing arrows.
     *
     * **This is a fixed constant, not terrain.** ARCore anchors/terrain are not available in this
     * build, so there is no road-surface elevation to follow, and inventing a fake terrain model
     * would be worse than a constant. 0.35 m is chosen to sit clearly on the road rather than
     * floating (readable as a marking) or sinking into it (lost behind the car's own occlusion).
     * Re-evaluate when real road elevation exists; see the Phase 8 limitations note.
     */
    val roadHeightOffsetMeters: Double = 0.35,

    // endregion

    // region Arrival

    /**
     * Within this distance of the destination, arrow density thins out. The arrival cue should take
     * over from the path; a dense arrow run to the door is visual noise.
     */
    val arrivalThinningDistanceMeters: Double = 25.0,

    // endregion
) {
    internal fun qualityRank(quality: LocalizationQuality): Int = quality.ordinal

    companion object {
        /** Radians per degree, kept here so the tangent maths below reads in degrees. */
        const val DEG_TO_RAD: Double = PI / 180.0
    }
}