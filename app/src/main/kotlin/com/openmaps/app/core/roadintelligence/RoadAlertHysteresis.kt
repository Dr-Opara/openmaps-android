package com.openmaps.app.core.roadintelligence

/**
 * Hysteresis over which [RouteAlert] ids are "active" tick to tick, generalizing
 * [com.openmaps.app.core.navigation.OffRouteDetector]'s confirm-sample pattern from one boolean to a
 * set of ids. Without this, an alert sitting right at its activation-distance boundary (section 14)
 * would flicker on/off with ordinary GPS noise on every location update — exactly the "alerts
 * appearing/disappearing every GPS update" failure section 15 calls out by name.
 *
 * Two tiers, both counted in consecutive calls to [update], not wall-clock time (matching
 * [com.openmaps.app.core.navigation.OffRouteDetector], which also counts samples rather than
 * milliseconds — the two naturally fire at the same cadence, one per location tick):
 *  - an id must appear in [confirmSamples] consecutive calls before it is reported as active (enter
 *    debounce);
 *  - once active, it must be *absent from the raw candidates* for [confirmSamples] consecutive
 *    calls before it is actually dropped from the output (exit debounce) — during that grace window
 *    the last-known alert keeps rendering, so one borderline sample does not blank it out and bring
 *    it straight back next tick.
 *
 * Deterministic ids ([RouteAlert.id]) are what make this possible: two fetches of the same
 * real-world incident must resolve to the same id, or every refetch would look like a brand-new
 * alert and this class could never confirm anything (see `RoadEvent` id derivation in
 * `DefaultRoadIntelligenceRepository` for how provider duplicates are collapsed before alerts are
 * ever built).
 */
class RoadAlertHysteresis(private val confirmSamples: Int = DEFAULT_CONFIRM_SAMPLES) {

    private val presentStreak = mutableMapOf<String, Int>()
    private val absentStreak = mutableMapOf<String, Int>()
    private val active = mutableSetOf<String>()
    private val lastSeen = mutableMapOf<String, RouteAlert>()

    /**
     * Feeds one tick's raw candidate alerts through the hysteresis and returns the stabilized set,
     * priority-ordered ([RoadAlertPriority.sorted]) for rendering as-is.
     */
    fun update(candidates: List<RouteAlert>): List<RouteAlert> {
        val candidateIds = candidates.associateBy { it.id }

        for ((id, alert) in candidateIds) {
            lastSeen[id] = alert // Always keep the freshest data for an id, confirmed or not.
            val streak = (presentStreak[id] ?: 0) + 1
            presentStreak[id] = streak
            absentStreak.remove(id)
            if (streak >= confirmSamples) active += id
        }

        val missingThisTick = active - candidateIds.keys
        for (id in missingThisTick) {
            val streak = (absentStreak[id] ?: 0) + 1
            absentStreak[id] = streak
            presentStreak.remove(id)
            if (streak >= confirmSamples) {
                active -= id
                lastSeen.remove(id)
                absentStreak.remove(id)
            }
        }

        // Entrance streaks for ids that never got confirmed and have now dropped out of the raw
        // candidates entirely (not even in grace) have nothing left to debounce.
        presentStreak.keys.retainAll(candidateIds.keys)

        return RoadAlertPriority.sorted(active.mapNotNull { lastSeen[it] })
    }

    /** Drops all remembered state. Call on reroute/arrival/navigation-stop — a new trip, fresh start. */
    fun reset() {
        presentStreak.clear()
        absentStreak.clear()
        active.clear()
        lastSeen.clear()
    }

    private companion object {
        const val DEFAULT_CONFIRM_SAMPLES = 2
    }
}
