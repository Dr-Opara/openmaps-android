package com.openmaps.app.feature.live

import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.feature.map.NavigationState

/**
 * What the Live HUD shows, derived from navigation state.
 *
 * A pure function of [NavigationState] (see [LiveHud.state]) for three reasons: it is the whole
 * Phase 5 testable surface without touching CameraX; it keeps the camera preview from recomposing
 * on every navigation tick, because the HUD depends on this one small derived value rather than the
 * entire UI state; and it guarantees the HUD never invents or recomputes trip figures — every number
 * is passed straight through from [RouteProgress], the single source of truth.
 *
 * Values are raw (meters, seconds, enum) rather than formatted strings, so localization stays in
 * the composable via `stringResource`.
 */
sealed interface LiveHudState {
    /** Nothing to navigate yet: no route, navigation not started, or a terminal error. */
    data object Idle : LiveHudState

    /** Navigating normally. Carries everything the HUD needs. */
    data class Maneuver(
        val type: ManeuverType,
        val instruction: String,
        val distanceToManeuverMeters: Double,
        val nextInstruction: String?,
        /** The upcoming maneuver's type, for the "Then" card's turn icon — null when there is none. */
        val nextManeuverType: ManeuverType?,
        val remainingDistanceMeters: Double,
        val remainingDurationSeconds: Double,
        val destinationName: String?,
    ) : LiveHudState

    /** Confirmed off route; still tracking the old route. */
    data class OffRoute(val destinationName: String?) : LiveHudState

    /** A replacement route is being fetched. */
    data object Rerouting : LiveHudState

    /** Destination reached. */
    data class Arrived(val destinationName: String?) : LiveHudState
}

/**
 * Maps navigation state onto HUD state. The single seam between Phase 4's navigation model and
 * Phase 5's Live UI — the camera layer never appears here, so this stays JVM-testable.
 */
object LiveHud {

    fun state(navigationState: NavigationState, destinationName: String?): LiveHudState =
        when (navigationState) {
            is NavigationState.Active -> maneuver(navigationState.progress, destinationName)

            // Rerouting carries no progress by design: the old route is still being driven, and the
            // HUD shows a transient status instead of stale numbers.
            NavigationState.Rerouting -> LiveHudState.Rerouting

            is NavigationState.OffRoute -> LiveHudState.OffRoute(destinationName)
            is NavigationState.Arrived -> LiveHudState.Arrived(destinationName)

            // Not navigating: no route, ready to start, or a terminal navigation error. Live is
            // still perfectly usable — it just shows the camera and no guidance.
            NavigationState.Idle,
            NavigationState.Ready,
            is NavigationState.Error,
            -> LiveHudState.Idle
        }

    /**
     * Builds the maneuver row from a progress snapshot.
     *
     * The primary line pairs the **current** maneuver with the distance to reach it:
     * `distanceToNextManeuverMeters` is measured to `currentManeuver.endShapeIndex`, and a
     * Valhalla maneuver's instruction is the action performed at the end of its own segment.
     * Pairing the *next* maneuver with that distance (as an earlier revision did) would label the
     * current turn's distance with the following turn's instruction. The maneuver after that is the
     * secondary "Then ..." line.
     */
    private fun maneuver(progress: RouteProgress, destinationName: String?): LiveHudState.Maneuver {
        val current = progress.currentManeuver
        val next = progress.nextManeuver
        val instruction = current.instruction.ifBlank { current.streetName.orEmpty() }
        return LiveHudState.Maneuver(
            type = current.type,
            instruction = instruction,
            distanceToManeuverMeters = progress.distanceToNextManeuverMeters,
            nextInstruction = next
                ?.instruction
                ?.takeIf { it.isNotBlank() && it != instruction },
            nextManeuverType = next?.type,
            remainingDistanceMeters = progress.remainingDistanceMeters,
            remainingDurationSeconds = progress.remainingDurationSeconds,
            destinationName = destinationName,
        )
    }
}