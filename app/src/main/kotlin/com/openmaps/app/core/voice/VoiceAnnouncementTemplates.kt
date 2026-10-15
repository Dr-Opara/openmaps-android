package com.openmaps.app.core.voice

import com.openmaps.app.core.routing.ManeuverType

/**
 * Deterministic, template-based localization (delta-spec sections 20-22): every phrase below is a
 * fixed string or a fixed string with a street name substituted in — never a runtime translation
 * through an external service. [EnglishUk] intentionally uses the same wording as [EnglishUs]: the
 * two differ by TTS *voice/locale* (`AndroidTextToSpeechEngine` picks `en-GB` vs `en-US`), not by
 * word choice — section 19 keeps "voice locale" and "distance-unit preference" as separate
 * concepts, and this file is not where units are decided (`feet`/`miles` stay the app-wide US
 * convention regardless of spoken language, same as every other distance in the app).
 *
 * Coverage is the full [ManeuverType] set plus every [VoiceEvent] in section 15, in all five
 * languages. [AnnouncementStage.Far]/[AnnouncementStage.Near] use fixed canonical distance phrases
 * ("in half a mile" / "in 500 feet") rather than the live metres-remaining figure, matching the
 * spec's own examples and how real turn-by-turn voice guidance actually phrases things — a
 * constantly-changing exact number would also defeat [VoiceAnnouncementPlanner]'s whole point,
 * which is to announce a stage once, not a number every tick.
 */
object VoiceAnnouncementTemplates {

    fun phraseFor(event: VoiceEvent, language: VoiceLanguage): String = when (event) {
        VoiceEvent.NavigationStarted -> fixed(language, NAVIGATION_STARTED)
        is VoiceEvent.Maneuver -> maneuverPhrase(event, language)
        VoiceEvent.Rerouting -> fixed(language, REROUTING)
        VoiceEvent.RouteUpdated -> fixed(language, ROUTE_UPDATED)
        VoiceEvent.DestinationApproaching -> fixed(language, DESTINATION_APPROACHING)
        VoiceEvent.Arrived -> fixed(language, ARRIVED)
    }

    private fun maneuverPhrase(event: VoiceEvent.Maneuver, language: VoiceLanguage): String {
        val table = maneuverTables.getValue(language)
        val action = table.getValue(event.type).let { phrase ->
            event.streetName?.takeIf { it.isNotBlank() }?.let { street -> "$phrase ${onto(language)} $street" } ?: phrase
        }
        val prefix = stagePrefix(event.stage, language)
        val sentence = if (prefix.isEmpty()) action.replaceFirstChar { it.uppercase() } else "$prefix$action"
        return "$sentence."
    }

    private fun stagePrefix(stage: AnnouncementStage, language: VoiceLanguage): String = when (stage) {
        AnnouncementStage.Immediate -> ""
        AnnouncementStage.Far -> farPrefixes.getValue(language)
        AnnouncementStage.Near -> nearPrefixes.getValue(language)
    }

    private fun fixed(language: VoiceLanguage, table: Map<VoiceLanguage, String>): String = table.getValue(language)

    private fun onto(language: VoiceLanguage): String = when (language) {
        VoiceLanguage.EnglishUs, VoiceLanguage.EnglishUk -> "onto"
        VoiceLanguage.Spanish -> "en"
        VoiceLanguage.French -> "sur"
        VoiceLanguage.NigerianPidgin -> "enter"
    }

    private val farPrefixes = mapOf(
        VoiceLanguage.EnglishUs to "In half a mile, ",
        VoiceLanguage.EnglishUk to "In half a mile, ",
        VoiceLanguage.Spanish to "En media milla, ",
        VoiceLanguage.French to "Dans un demi-mile, ",
        VoiceLanguage.NigerianPidgin to "For about half mile, ",
    )

    private val nearPrefixes = mapOf(
        VoiceLanguage.EnglishUs to "In 500 feet, ",
        VoiceLanguage.EnglishUk to "In 500 feet, ",
        VoiceLanguage.Spanish to "En 500 pies, ",
        VoiceLanguage.French to "Dans 500 pieds, ",
        VoiceLanguage.NigerianPidgin to "For about 500 feet, ",
    )

    private val NAVIGATION_STARTED = mapOf(
        VoiceLanguage.EnglishUs to "Navigation started.",
        VoiceLanguage.EnglishUk to "Navigation started.",
        VoiceLanguage.Spanish to "Navegación iniciada.",
        VoiceLanguage.French to "Navigation commencée.",
        VoiceLanguage.NigerianPidgin to "Navigation don start.",
    )

    private val REROUTING = mapOf(
        VoiceLanguage.EnglishUs to "Rerouting.",
        VoiceLanguage.EnglishUk to "Rerouting.",
        VoiceLanguage.Spanish to "Recalculando ruta.",
        VoiceLanguage.French to "Recalcul de l'itinéraire.",
        VoiceLanguage.NigerianPidgin to "I dey find new road.",
    )

    private val ROUTE_UPDATED = mapOf(
        VoiceLanguage.EnglishUs to "Route updated.",
        VoiceLanguage.EnglishUk to "Route updated.",
        VoiceLanguage.Spanish to "Ruta actualizada.",
        VoiceLanguage.French to "Itinéraire mis à jour.",
        VoiceLanguage.NigerianPidgin to "New road don ready.",
    )

    private val DESTINATION_APPROACHING = mapOf(
        VoiceLanguage.EnglishUs to "Approaching your destination.",
        VoiceLanguage.EnglishUk to "Approaching your destination.",
        VoiceLanguage.Spanish to "Se acerca a su destino.",
        VoiceLanguage.French to "Vous approchez de votre destination.",
        VoiceLanguage.NigerianPidgin to "You dey near your destination.",
    )

    private val ARRIVED = mapOf(
        VoiceLanguage.EnglishUs to "You have arrived.",
        VoiceLanguage.EnglishUk to "You have arrived.",
        VoiceLanguage.Spanish to "Ha llegado a su destino.",
        VoiceLanguage.French to "Vous êtes arrivé à destination.",
        VoiceLanguage.NigerianPidgin to "You don reach your destination.",
    )

    /** One phrase per [ManeuverType], per language. [ManeuverType.Start]/[Unknown] use a safe
     * generic phrase — the trip-start moment is actually announced via [VoiceEvent.NavigationStarted],
     * and arrival via [VoiceEvent.Arrived], so neither is expected to reach here in practice. */
    private val maneuverTables: Map<VoiceLanguage, Map<ManeuverType, String>> = mapOf(
        VoiceLanguage.EnglishUs to enManeuvers(),
        VoiceLanguage.EnglishUk to enManeuvers(),
        VoiceLanguage.Spanish to mapOf(
            ManeuverType.Start to "continúe",
            ManeuverType.Continue to "continúe recto",
            ManeuverType.SlightLeft to "manténgase a la izquierda",
            ManeuverType.SlightRight to "manténgase a la derecha",
            ManeuverType.TurnLeft to "gire a la izquierda",
            ManeuverType.TurnRight to "gire a la derecha",
            ManeuverType.SharpLeft to "gire bruscamente a la izquierda",
            ManeuverType.SharpRight to "gire bruscamente a la derecha",
            ManeuverType.UTurn to "haga un cambio de sentido",
            ManeuverType.Merge to "incorpórese",
            ManeuverType.Ramp to "tome la salida",
            ManeuverType.Roundabout to "entre en la rotonda",
            ManeuverType.Destination to "ha llegado a su destino",
            ManeuverType.Unknown to "continúe",
        ),
        VoiceLanguage.French to mapOf(
            ManeuverType.Start to "continuez",
            ManeuverType.Continue to "continuez tout droit",
            ManeuverType.SlightLeft to "restez à gauche",
            ManeuverType.SlightRight to "restez à droite",
            ManeuverType.TurnLeft to "tournez à gauche",
            ManeuverType.TurnRight to "tournez à droite",
            ManeuverType.SharpLeft to "tournez fortement à gauche",
            ManeuverType.SharpRight to "tournez fortement à droite",
            ManeuverType.UTurn to "faites demi-tour",
            ManeuverType.Merge to "rejoignez la voie",
            ManeuverType.Ramp to "prenez la sortie",
            ManeuverType.Roundabout to "entrez dans le rond-point",
            ManeuverType.Destination to "vous êtes arrivé",
            ManeuverType.Unknown to "continuez",
        ),
        VoiceLanguage.NigerianPidgin to mapOf(
            ManeuverType.Start to "continue",
            ManeuverType.Continue to "continue straight",
            ManeuverType.SlightLeft to "stay small to your left",
            ManeuverType.SlightRight to "stay small to your right",
            ManeuverType.TurnLeft to "turn left",
            ManeuverType.TurnRight to "turn right",
            ManeuverType.SharpLeft to "make sharp turn to your left",
            ManeuverType.SharpRight to "make sharp turn to your right",
            ManeuverType.UTurn to "turn back the way wey you come from",
            ManeuverType.Merge to "merge enter the road",
            ManeuverType.Ramp to "take the exit",
            ManeuverType.Roundabout to "enter the roundabout",
            ManeuverType.Destination to "you don reach your destination",
            ManeuverType.Unknown to "continue",
        ),
    )

    private fun enManeuvers(): Map<ManeuverType, String> = mapOf(
        ManeuverType.Start to "continue",
        ManeuverType.Continue to "continue straight",
        ManeuverType.SlightLeft to "keep left",
        ManeuverType.SlightRight to "keep right",
        ManeuverType.TurnLeft to "turn left",
        ManeuverType.TurnRight to "turn right",
        ManeuverType.SharpLeft to "make a sharp left",
        ManeuverType.SharpRight to "make a sharp right",
        ManeuverType.UTurn to "make a U-turn",
        ManeuverType.Merge to "merge",
        ManeuverType.Ramp to "take the exit",
        ManeuverType.Roundabout to "enter the roundabout",
        ManeuverType.Destination to "you have arrived",
        ManeuverType.Unknown to "continue",
    )
}
