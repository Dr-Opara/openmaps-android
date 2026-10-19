package com.openmaps.app.core.voice

import com.openmaps.app.core.localization.MeasurementSystem
import com.openmaps.app.core.localization.RegionMeasurementPolicy
import com.openmaps.app.core.localization.ResolvedMeasurementSystem
import com.openmaps.app.core.routing.ManeuverType
import java.util.Locale

/**
 * Deterministic navigation phrases. Provider street/place names are inserted as returned; OpenMaps
 * never machine-translates or invents a road name. Phase 7 makes the canonical far/near distances
 * follow the active regional measurement preference and adds Arabic/Hebrew RTL speech.
 */
object VoiceAnnouncementTemplates {

    fun phraseFor(
        event: VoiceEvent,
        language: VoiceLanguage,
        measurementSystem: MeasurementSystem = MeasurementSystem.Imperial,
        locale: Locale = Locale.getDefault(),
    ): String = when (event) {
        VoiceEvent.NavigationStarted -> fixed(language, NAVIGATION_STARTED)
        is VoiceEvent.Maneuver -> maneuverPhrase(event, language, measurementSystem, locale)
        VoiceEvent.Rerouting -> fixed(language, REROUTING)
        VoiceEvent.RouteUpdated -> fixed(language, ROUTE_UPDATED)
        VoiceEvent.DestinationApproaching -> fixed(language, DESTINATION_APPROACHING)
        VoiceEvent.Arrived -> fixed(language, ARRIVED)
    }

    private fun maneuverPhrase(
        event: VoiceEvent.Maneuver,
        language: VoiceLanguage,
        measurementSystem: MeasurementSystem,
        locale: Locale,
    ): String {
        val table = maneuverTables.getValue(language)
        val action = table.getValue(event.type).let { phrase ->
            event.streetName?.takeIf { it.isNotBlank() }?.let { street ->
                "$phrase ${onto(language)} $street"
            } ?: phrase
        }
        val prefix = stagePrefix(event.stage, language, measurementSystem, locale)
        val sentence = if (prefix.isEmpty()) action.replaceFirstChar { it.uppercase() } else "$prefix$action"
        return "$sentence."
    }

    private fun stagePrefix(
        stage: AnnouncementStage,
        language: VoiceLanguage,
        measurementSystem: MeasurementSystem,
        locale: Locale,
    ): String {
        if (stage == AnnouncementStage.Immediate) return ""
        val resolved = RegionMeasurementPolicy.resolve(measurementSystem, locale)
        val far = stage == AnnouncementStage.Far
        return when (language) {
            VoiceLanguage.EnglishUs,
            VoiceLanguage.EnglishUk,
            -> when (resolved) {
                ResolvedMeasurementSystem.Metric -> if (far) "In 800 meters, " else "In 150 meters, "
                ResolvedMeasurementSystem.ImperialUk -> if (far) "In half a mile, " else "In 200 yards, "
                ResolvedMeasurementSystem.ImperialUs -> if (far) "In half a mile, " else "In 500 feet, "
            }
            VoiceLanguage.Spanish -> when (resolved) {
                ResolvedMeasurementSystem.Metric -> if (far) "En 800 metros, " else "En 150 metros, "
                ResolvedMeasurementSystem.ImperialUk -> if (far) "En media milla, " else "En 200 yardas, "
                ResolvedMeasurementSystem.ImperialUs -> if (far) "En media milla, " else "En 500 pies, "
            }
            VoiceLanguage.French -> when (resolved) {
                ResolvedMeasurementSystem.Metric -> if (far) "Dans 800 mètres, " else "Dans 150 mètres, "
                ResolvedMeasurementSystem.ImperialUk -> if (far) "Dans un demi-mile, " else "Dans 200 yards, "
                ResolvedMeasurementSystem.ImperialUs -> if (far) "Dans un demi-mile, " else "Dans 500 pieds, "
            }
            VoiceLanguage.Arabic -> when (resolved) {
                ResolvedMeasurementSystem.Metric -> if (far) "بعد 800 متر، " else "بعد 150 متر، "
                ResolvedMeasurementSystem.ImperialUk -> if (far) "بعد نصف ميل، " else "بعد 200 ياردة، "
                ResolvedMeasurementSystem.ImperialUs -> if (far) "بعد نصف ميل، " else "بعد 500 قدم، "
            }
            VoiceLanguage.Hebrew -> when (resolved) {
                ResolvedMeasurementSystem.Metric -> if (far) "בעוד 800 מטר, " else "בעוד 150 מטר, "
                ResolvedMeasurementSystem.ImperialUk -> if (far) "בעוד חצי מייל, " else "בעוד 200 יארד, "
                ResolvedMeasurementSystem.ImperialUs -> if (far) "בעוד חצי מייל, " else "בעוד 500 רגל, "
            }
            VoiceLanguage.NigerianPidgin -> when (resolved) {
                ResolvedMeasurementSystem.Metric -> if (far) "For about 800 meters, " else "For about 150 meters, "
                ResolvedMeasurementSystem.ImperialUk -> if (far) "For about half mile, " else "For about 200 yards, "
                ResolvedMeasurementSystem.ImperialUs -> if (far) "For about half mile, " else "For about 500 feet, "
            }
        }
    }

    private fun fixed(language: VoiceLanguage, table: Map<VoiceLanguage, String>): String = table.getValue(language)

    private fun onto(language: VoiceLanguage): String = when (language) {
        VoiceLanguage.EnglishUs, VoiceLanguage.EnglishUk -> "onto"
        VoiceLanguage.Spanish -> "en"
        VoiceLanguage.French -> "sur"
        VoiceLanguage.Arabic -> "إلى"
        VoiceLanguage.Hebrew -> "אל"
        VoiceLanguage.NigerianPidgin -> "enter"
    }

    private val NAVIGATION_STARTED = mapOf(
        VoiceLanguage.EnglishUs to "Navigation started.",
        VoiceLanguage.EnglishUk to "Navigation started.",
        VoiceLanguage.Spanish to "Navegación iniciada.",
        VoiceLanguage.French to "Navigation commencée.",
        VoiceLanguage.Arabic to "بدأت الملاحة.",
        VoiceLanguage.Hebrew to "הניווט התחיל.",
        VoiceLanguage.NigerianPidgin to "Navigation don start.",
    )

    private val REROUTING = mapOf(
        VoiceLanguage.EnglishUs to "Rerouting.",
        VoiceLanguage.EnglishUk to "Rerouting.",
        VoiceLanguage.Spanish to "Recalculando ruta.",
        VoiceLanguage.French to "Recalcul de l’itinéraire.",
        VoiceLanguage.Arabic to "جارٍ إعادة التوجيه.",
        VoiceLanguage.Hebrew to "מחשב מסלול מחדש.",
        VoiceLanguage.NigerianPidgin to "I dey find new road.",
    )

    private val ROUTE_UPDATED = mapOf(
        VoiceLanguage.EnglishUs to "Route updated.",
        VoiceLanguage.EnglishUk to "Route updated.",
        VoiceLanguage.Spanish to "Ruta actualizada.",
        VoiceLanguage.French to "Itinéraire mis à jour.",
        VoiceLanguage.Arabic to "تم تحديث المسار.",
        VoiceLanguage.Hebrew to "המסלול עודכן.",
        VoiceLanguage.NigerianPidgin to "New road don ready.",
    )

    private val DESTINATION_APPROACHING = mapOf(
        VoiceLanguage.EnglishUs to "Approaching your destination.",
        VoiceLanguage.EnglishUk to "Approaching your destination.",
        VoiceLanguage.Spanish to "Se acerca a su destino.",
        VoiceLanguage.French to "Vous approchez de votre destination.",
        VoiceLanguage.Arabic to "أنت تقترب من وجهتك.",
        VoiceLanguage.Hebrew to "אתם מתקרבים ליעד.",
        VoiceLanguage.NigerianPidgin to "You dey near your destination.",
    )

    private val ARRIVED = mapOf(
        VoiceLanguage.EnglishUs to "You have arrived.",
        VoiceLanguage.EnglishUk to "You have arrived.",
        VoiceLanguage.Spanish to "Ha llegado a su destino.",
        VoiceLanguage.French to "Vous êtes arrivé à destination.",
        VoiceLanguage.Arabic to "لقد وصلت إلى وجهتك.",
        VoiceLanguage.Hebrew to "הגעתם ליעד.",
        VoiceLanguage.NigerianPidgin to "You don reach your destination.",
    )

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
        VoiceLanguage.Arabic to mapOf(
            ManeuverType.Start to "استمر",
            ManeuverType.Continue to "استمر مستقيمًا",
            ManeuverType.SlightLeft to "الزم اليسار قليلًا",
            ManeuverType.SlightRight to "الزم اليمين قليلًا",
            ManeuverType.TurnLeft to "انعطف يسارًا",
            ManeuverType.TurnRight to "انعطف يمينًا",
            ManeuverType.SharpLeft to "انعطف يسارًا بحدة",
            ManeuverType.SharpRight to "انعطف يمينًا بحدة",
            ManeuverType.UTurn to "قم بالدوران للخلف",
            ManeuverType.Merge to "اندمج مع الطريق",
            ManeuverType.Ramp to "اسلك المخرج",
            ManeuverType.Roundabout to "ادخل الدوار",
            ManeuverType.Destination to "لقد وصلت إلى وجهتك",
            ManeuverType.Unknown to "استمر",
        ),
        VoiceLanguage.Hebrew to mapOf(
            ManeuverType.Start to "המשיכו",
            ManeuverType.Continue to "המשיכו ישר",
            ManeuverType.SlightLeft to "היצמדו מעט לשמאל",
            ManeuverType.SlightRight to "היצמדו מעט לימין",
            ManeuverType.TurnLeft to "פנו שמאלה",
            ManeuverType.TurnRight to "פנו ימינה",
            ManeuverType.SharpLeft to "פנו בחדות שמאלה",
            ManeuverType.SharpRight to "פנו בחדות ימינה",
            ManeuverType.UTurn to "בצעו פניית פרסה",
            ManeuverType.Merge to "השתלבו בדרך",
            ManeuverType.Ramp to "צאו ביציאה",
            ManeuverType.Roundabout to "היכנסו לכיכר",
            ManeuverType.Destination to "הגעתם ליעד",
            ManeuverType.Unknown to "המשיכו",
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
