package com.openmaps.app.core.routing

/**
 * Driver-facing instruction based on the actual maneuver type and provider-supplied details.
 * Some routing fallbacks provide placeholder strings like "turn" or "roundaboutEnter".
 * Never show those raw API tokens to drivers; use the maneuver's real classified type instead.
 * Do not infer which road to turn ONTO from a streetName whose segment semantics may vary.
 */
fun RouteManeuver.displayInstruction(): String {
    val raw = instruction.trim()
    val genericToken = raw.lowercase()
        .replace(Regex("[^a-z]"), "")
    val fallback = when (type) {
        ManeuverType.Start -> "Depart"
        ManeuverType.Continue -> "Continue straight"
        ManeuverType.SlightLeft -> "Bear left"
        ManeuverType.SlightRight -> "Bear right"
        ManeuverType.TurnLeft -> "Turn left"
        ManeuverType.TurnRight -> "Turn right"
        ManeuverType.SharpLeft -> "Make a sharp left"
        ManeuverType.SharpRight -> "Make a sharp right"
        ManeuverType.UTurn -> "Make a U-turn"
        ManeuverType.Merge -> "Merge"
        ManeuverType.Ramp -> "Take the ramp"
        ManeuverType.Roundabout -> when (genericToken) {
            "roundaboutexit" -> "Exit the roundabout"
            else -> "Enter the roundabout"
        }
        ManeuverType.Destination -> "Arrive at destination"
        ManeuverType.Unknown -> "Continue"
    }
    val isGeneric = genericToken in setOf(
        "turn", "depart", "start", "continue", "straight",
        "roundaboutenter", "roundaboutexit", "ramp", "merge",
        "arrive", "destination", "unknown",
    )
    val base = when {
        raw.isBlank() -> streetName.orEmpty()
        isGeneric -> fallback
        else -> raw
    }
    val extras = listOfNotNull(exitSign, signpost)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .distinct()
        .filterNot { extra -> base.contains(extra, ignoreCase = true) }

    return buildList {
        if (base.isNotEmpty()) add(base)
        addAll(extras)
    }.joinToString(" · ")
}
