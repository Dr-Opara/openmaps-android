package com.openmaps.app.core.arrow

import com.openmaps.app.core.spatial.LocalizationQuality

/**
 * Smooths localization quality before it gates the arrow set.
 *
 * AR accuracy jitters frame to frame. Gating density directly on raw quality makes arrows visibly
 * pop in and out whenever accuracy hovers at a threshold — the single most distracting thing this
 * feature could do. So **both** directions must persist before they are applied: an earlier revision
 * applied degradations immediately (to react fast to a lost fix) and that alone was enough to make a
 * single wobbling frame visibly thin the arrow set.
 *
 * The asymmetry is in *how long* each direction must hold, not in whether it holds: improvement
 * takes [ARArrowConfig.qualityHysteresisSteps] frames (appearing late is fine — arrows on a shaky fix
 * are not), degradation takes [ARArrowConfig.qualityDegradeSteps] (dropping fast, because arrows in
 * the wrong place are worse than no arrows, but never on one bad frame).
 */
class ARArrowQualityGate(
    private val config: ARArrowConfig = ARArrowConfig(),
) {
    private var candidate: LocalizationQuality? = null
    private var candidateCount = 0

    /** The quality currently in force. */
    var applied: LocalizationQuality = LocalizationQuality.Unavailable
        private set

    /**
     * Feeds one observation and returns the quality to draw with.
     */
    fun observe(observed: LocalizationQuality): LocalizationQuality {
        if (observed == applied) {
            candidate = null
            candidateCount = 0
            return applied
        }

        val isDegrading = observed.ordinal < applied.ordinal
        val required = if (isDegrading) config.qualityDegradeSteps else config.qualityHysteresisSteps

        if (observed == candidate) {
            candidateCount++
        } else {
            candidate = observed
            candidateCount = 1
        }
        if (candidateCount >= required) {
            applied = observed
            candidate = null
            candidateCount = 0
        }
        return applied
    }

    /** Drops any pending candidate and forces [quality]. Used on route replacement and stop. */
    fun reset(quality: LocalizationQuality = LocalizationQuality.Unavailable) {
        candidate = null
        candidateCount = 0
        applied = quality
    }
}