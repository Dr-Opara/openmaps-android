package com.openmaps.app.core.environment

/**
 * Debounces [DrivingEnvironment] across ticks so it does not flip on a single noisy classification —
 * section C of the Phase 11 brief ("environment mode must NOT flip constantly"). Mirrors
 * [com.openmaps.app.core.navigation.OffRouteDetector]'s confirm-sample shape: a candidate mode must
 * be classified [confirmTicks] times **consecutively** before it replaces the current one. Stricter
 * than Phase 10's per-alert hysteresis (which tolerates a one-tick gap) on purpose — an environment
 * mode is a broader behavioral switch (arrow density, camera, POI priority all change at once), so it
 * should move only on sustained evidence, not on first contact with a differing sample.
 */
class EnvironmentHysteresis(private val confirmTicks: Int = DEFAULT_CONFIRM_TICKS) {

    private var current: DrivingEnvironment = DrivingEnvironment.Unknown
    private var candidate: DrivingEnvironment? = null
    private var candidateStreak = 0

    /** Feeds one tick's raw classification through the debounce and returns the stabilized mode. */
    fun update(classified: DrivingEnvironment): DrivingEnvironment {
        if (classified == current) {
            candidate = null
            candidateStreak = 0
            return current
        }
        if (classified == candidate) {
            candidateStreak++
        } else {
            candidate = classified
            candidateStreak = 1
        }
        if (candidateStreak >= confirmTicks) {
            current = classified
            candidate = null
            candidateStreak = 0
        }
        return current
    }

    /** The currently confirmed mode, without feeding a new sample. */
    fun currentMode(): DrivingEnvironment = current

    /** Drops all state back to [DrivingEnvironment.Unknown]. Call on a new trip (start/reroute). */
    fun reset() {
        current = DrivingEnvironment.Unknown
        candidate = null
        candidateStreak = 0
    }

    private companion object {
        const val DEFAULT_CONFIRM_TICKS = 3
    }
}
