package com.openmaps.app.core.routing

/**
 * Builds the driver-facing maneuver line from provider-backed guidance only.
 *
 * Exit/signpost text is appended only when HERE supplied it and the provider instruction does not
 * already contain the same wording. This prevents duplicate "Exit 763 / Exit 763" banners.
 */
fun RouteManeuver.displayInstruction(): String {
    val base = instruction.ifBlank { streetName.orEmpty() }.trim()
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
