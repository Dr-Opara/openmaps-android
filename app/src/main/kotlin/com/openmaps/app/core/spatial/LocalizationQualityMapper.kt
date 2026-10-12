package com.openmaps.app.core.spatial

import kotlin.math.abs

/**
 * Maps ARCore-style accuracy numbers onto [LocalizationQuality].
 *
 * This is a **pure function** on purpose. "Is localization good enough to anchor AR content to a real
 * place?" is the single most consequential judgment in Phase 6, and it is exactly the kind of
 * threshold that gets quietly wrong (too generous → arrows float metres from the real turn; too
 * strict → AR never appears usable). Keeping it pure and separately tested means the constants can
 * be re-tuned on real device data later without touching ARCore code.
 *
 * Thresholds are defaults, overridable per-engine, because they will want device-specific tuning
 * once real accuracy distributions exist.
 */
class LocalizationQualityMapper(
    /** Horizontal accuracy (m) at or below which position is considered excellent. */
    private val excellentHorizontalMeters: Double = EXCELLENT_HORIZONTAL_METERS,
    /** Horizontal accuracy (m) at or below which position is considered acceptable. */
    private val goodHorizontalMeters: Double = GOOD_HORIZONTAL_METERS,
    /** Maximum horizontal accuracy (m) that still counts as a fix at all. */
    private val usableHorizontalMeters: Double = USABLE_HORIZONTAL_METERS,
    /** Heading accuracy (deg) at or below which heading is excellent. */
    private val excellentHeadingDegrees: Double = EXCELLENT_HEADING_DEGREES,
    /** Heading accuracy (deg) at or below which heading is acceptable. */
    private val goodHeadingDegrees: Double = GOOD_HEADING_DEGREES,
    /** Maximum heading accuracy (deg) that still counts as a heading at all. */
    private val usableHeadingDegrees: Double = USABLE_HEADING_DEGREES,
) {

    /**
     * @param horizontalAccuracyMeters null when the provider reports no accuracy (treated as unusable).
     * @param headingAccuracyDegrees null likewise.
     * @param isSettled false while the engine is still accumulating its first fix.
     */
    fun quality(
        horizontalAccuracyMeters: Double?,
        headingAccuracyDegrees: Double?,
        isSettled: Boolean = true,
    ): LocalizationQuality {
        // No accuracy at all is not "good", it is unknown. Treating a missing value as perfect is the
        // single easiest way to show a confident heading that is pure noise.
        if (horizontalAccuracyMeters == null || horizontalAccuracyMeters > usableHorizontalMeters) {
            return LocalizationQuality.Unavailable
        }
        // A heading with no meaningful accuracy cannot steer anything. Reported as Unavailable rather
        // than Poor: an error larger than ~45 deg means "which way am I facing is unknown", and
        // calling that merely *poor* risks a caller treating it as usable-but-imprecise.
        if (headingAccuracyDegrees == null || headingAccuracyDegrees > usableHeadingDegrees) {
            return LocalizationQuality.Unavailable
        }
        if (!isSettled) return LocalizationQuality.Acquiring

        val horizontalGood = horizontalAccuracyMeters <= goodHorizontalMeters
        val headingGood = headingAccuracyDegrees <= goodHeadingDegrees

        return when {
            horizontalGood && headingGood &&
                horizontalAccuracyMeters <= excellentHorizontalMeters &&
                headingAccuracyDegrees <= excellentHeadingDegrees -> LocalizationQuality.Good

            horizontalGood && headingGood -> LocalizationQuality.Fair
            else -> LocalizationQuality.Poor
        }
    }

    /** True when the fix is precise enough to build an [SpatialPose] from at all. */
    fun isUsable(horizontalAccuracyMeters: Double?): Boolean =
        horizontalAccuracyMeters != null && horizontalAccuracyMeters <= usableHorizontalMeters

    companion object {
        /**
         * Consumer GNSS under open sky is ~3-5 m. 5 m is a genuinely tight fix; below this there is
         * little more to gain, so demanding it would only push quality down on good hardware.
         */
        const val EXCELLENT_HORIZONTAL_METERS = 5.0

        /** Urban-canyon-acceptable. Above this the position is visibly off a real turn. */
        const val GOOD_HORIZONTAL_METERS = 20.0

        /** Beyond this the fix is not worth showing at all (~a city block). */
        const val USABLE_HORIZONTAL_METERS = 100.0

        /**
         * A 3-degree heading error is ~0.5 m of lateral error per 10 m of look-ahead — tight enough
         * for AR anchoring. Note ARCore reports heading accuracy in degrees, and it is frequently
         * noisy at rest, so this is deliberately not tighter.
         */
        const val EXCELLENT_HEADING_DEGREES = 5.0

        /** ~20 m of error across a 200 m look-ahead: usable for guidance, not for fine anchoring. */
        const val GOOD_HEADING_DEGREES = 20.0

        /** Beyond this, "which way am I facing" is effectively unknown. */
        const val USABLE_HEADING_DEGREES = 45.0
    }
}

/** Normalises any bearing to [0, 360). Shared by the camera and navigation layers. */
fun normalizeHeadingDegrees(degrees: Double): Double {
    if (!degrees.isFinite()) return 0.0
    val wrapped = degrees % 360.0
    return if (wrapped < 0) wrapped + 360.0 else wrapped
}

/** Smallest signed difference between two headings, in [-180, 180]. */
fun headingDeltaDegrees(from: Double, to: Double): Double {
    var delta = (normalizeHeadingDegrees(to) - normalizeHeadingDegrees(from)) % 360.0
    if (delta > 180.0) delta -= 360.0
    if (delta < -180.0) delta += 360.0
    return delta
}

/** True when two headings agree to within [toleranceDegrees]. */
fun headingsAgree(a: Double, b: Double, toleranceDegrees: Double): Boolean =
    abs(headingDeltaDegrees(a, b)) <= toleranceDegrees