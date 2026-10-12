package com.openmaps.app.core.navigation

/**
 * Hysteresis over consecutive off-route samples so a single noisy GPS reading never flips
 * navigation state or triggers a reroute. Three tiers:
 *  - fewer than [confirmSamples] consecutive bad samples: treated as still on-route (noise).
 *  - [confirmSamples] or more: confirmed off-route (visible UI flag, no network call).
 *  - [rerouteSamples] or more: triggers a reroute.
 * Any on-route sample immediately resets the streak, so returning to the route recovers at once.
 */
class OffRouteDetector(
    private val confirmSamples: Int = DEFAULT_CONFIRM_SAMPLES,
    private val rerouteSamples: Int = DEFAULT_REROUTE_SAMPLES,
) {
    private var consecutiveOffRoute = 0

    fun update(isOffRouteSample: Boolean): OffRouteDecision {
        if (!isOffRouteSample) {
            consecutiveOffRoute = 0
            return OffRouteDecision.OnRoute
        }
        consecutiveOffRoute++
        return when {
            consecutiveOffRoute >= rerouteSamples -> OffRouteDecision.TriggerReroute
            consecutiveOffRoute >= confirmSamples -> OffRouteDecision.OffRouteConfirmed
            else -> OffRouteDecision.OnRoute
        }
    }

    /** Called once a reroute attempt resolves (success or failure) so the next excursion starts counting fresh. */
    fun reset() {
        consecutiveOffRoute = 0
    }

    companion object {
        const val DEFAULT_CONFIRM_SAMPLES = 2
        const val DEFAULT_REROUTE_SAMPLES = 5
    }
}

enum class OffRouteDecision { OnRoute, OffRouteConfirmed, TriggerReroute }
