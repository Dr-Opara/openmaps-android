package com.openmaps.app.feature.map

import com.openmaps.app.core.localization.MeasurementSystem
import com.openmaps.app.core.localization.NavigationUnits
import com.openmaps.app.core.roadintelligence.RoadEventType
import com.openmaps.app.core.roadintelligence.RouteAlert
import com.openmaps.app.core.roadintelligence.SpeedUnit
import com.openmaps.app.core.roadintelligence.TrafficCongestionLevel
import com.openmaps.app.core.roadintelligence.WeatherConditionType
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure text formatting for a [RouteAlert], shared between Map's compact banner and Live's HUD pills.
 *
 * Provider-supplied hazard/weather titles are preserved. OpenMaps-owned framework copy is localized
 * from the active app locale so RTL and non-English navigation never falls back to English chrome.
 */
object RoadAlertPresentation {

    fun headline(
        alert: RouteAlert,
        measurementSystem: MeasurementSystem = MeasurementSystem.Auto,
        locale: Locale = Locale.getDefault(),
    ): String = when (alert) {
        is RouteAlert.Hazard -> eventTitle(alert.event.type, alert.event.title, alert.event.metadata, locale)
        is RouteAlert.Informational -> eventTitle(alert.event.type, alert.event.title, alert.event.metadata, locale)
        is RouteAlert.Weather -> weatherLabel(alert.condition.type, alert.condition.description, locale)
        is RouteAlert.Lane -> copy(locale).laneGuidance
        is RouteAlert.SpeedLimit ->
            "${copy(locale).speedLimit} ${formatSpeedValue(alert.info.value, alert.info.unit, measurementSystem, locale)}"
        is RouteAlert.Congestion ->
            "${copy(locale).traffic}: ${trafficLevel(alert.condition.congestionLevel, locale)}"
    }

    fun distanceAheadLabel(
        alert: RouteAlert,
        measurementSystem: MeasurementSystem = MeasurementSystem.Auto,
        locale: Locale = Locale.getDefault(),
    ): String? = when (alert) {
        is RouteAlert.Hazard -> alert.distanceAheadMeters?.let {
            copy(locale).distanceAhead(NavigationUnits.formatDistance(it, measurementSystem, locale))
        }
        else -> null
    }

    /** Live traffic delay when the provider supplied a real traffic-adjusted duration. */
    fun delayLabel(alert: RouteAlert, locale: Locale = Locale.getDefault()): String? {
        val congestion = alert as? RouteAlert.Congestion ?: return null
        val condition = congestion.condition
        val adjusted = condition.trafficAdjustedDurationSeconds ?: return null
        if (!condition.isLive) return null
        val delaySeconds = (adjusted - condition.baseDurationSeconds).coerceAtLeast(0.0)
        if (delaySeconds < 60.0) return null
        val minutes = (delaySeconds / 60.0).roundToInt()
        return "+${NumberFormat.getIntegerInstance(locale).format(minutes)} ${copy(locale).minuteUnit}"
    }

    fun formatSpeedValue(
        value: Double,
        unit: SpeedUnit,
        measurementSystem: MeasurementSystem = MeasurementSystem.Auto,
        locale: Locale = Locale.getDefault(),
    ): String = NavigationUnits.formatPostedSpeed(
        value = value,
        sourceIsMph = unit == SpeedUnit.Mph,
        preference = measurementSystem,
        locale = locale,
    )

    fun formatCurrentSpeed(
        metersPerSecond: Float,
        unit: SpeedUnit,
        measurementSystem: MeasurementSystem = MeasurementSystem.Auto,
        locale: Locale = Locale.getDefault(),
    ): String = NavigationUnits.formatCurrentSpeed(
        metersPerSecond = metersPerSecond,
        preference = measurementSystem,
        locale = locale,
    )

    private fun eventTitle(
        type: RoadEventType,
        providerTitle: String,
        metadata: Map<String, String>,
        locale: Locale,
    ): String {
        if (metadata["source"] != "community") return providerTitle
        val language = locale.language.lowercase(Locale.ROOT)
        return when (language) {
            "es" -> when (type) {
                RoadEventType.Congestion -> "Tráfico informado más adelante"
                RoadEventType.Crash -> "Accidente informado más adelante"
                RoadEventType.Construction -> "Obras informadas más adelante"
                RoadEventType.Closure -> "Cierre de carril informado más adelante"
                RoadEventType.Debris -> "Objeto informado en la vía"
                RoadEventType.Flooding -> "Inundación informada más adelante"
                else -> providerTitle
            }
            "fr" -> when (type) {
                RoadEventType.Congestion -> "Trafic signalé plus loin"
                RoadEventType.Crash -> "Accident signalé plus loin"
                RoadEventType.Construction -> "Travaux signalés plus loin"
                RoadEventType.Closure -> "Fermeture de voie signalée plus loin"
                RoadEventType.Debris -> "Objet signalé sur la route"
                RoadEventType.Flooding -> "Inondation signalée plus loin"
                else -> providerTitle
            }
            "ar" -> when (type) {
                RoadEventType.Congestion -> "ازدحام مُبلّغ عنه أمامك"
                RoadEventType.Crash -> "حادث مُبلّغ عنه أمامك"
                RoadEventType.Construction -> "أعمال طريق مُبلّغ عنها أمامك"
                RoadEventType.Closure -> "إغلاق مسار مُبلّغ عنه أمامك"
                RoadEventType.Debris -> "جسم مُبلّغ عنه على الطريق"
                RoadEventType.Flooding -> "فيضان مُبلّغ عنه أمامك"
                else -> providerTitle
            }
            "he" -> when (type) {
                RoadEventType.Congestion -> "דווח על עומס בהמשך"
                RoadEventType.Crash -> "דווח על תאונה בהמשך"
                RoadEventType.Construction -> "דווח על עבודות בדרך בהמשך"
                RoadEventType.Closure -> "דווח על נתיב סגור בהמשך"
                RoadEventType.Debris -> "דווח על חפץ בכביש"
                RoadEventType.Flooding -> "דווח על הצפה בהמשך"
                else -> providerTitle
            }
            else -> providerTitle
        }
    }

    private fun weatherLabel(type: WeatherConditionType, providerDescription: String, locale: Locale): String {
        val language = locale.language.lowercase(Locale.ROOT)
        if (language !in setOf("es", "fr", "ar", "he")) return providerDescription
        return when (language) {
            "es" -> when (type) {
                WeatherConditionType.Clear -> "Despejado"
                WeatherConditionType.Rain -> "Lluvia"
                WeatherConditionType.HeavyRain -> "Lluvia intensa"
                WeatherConditionType.Snow -> "Nieve"
                WeatherConditionType.Ice -> "Hielo"
                WeatherConditionType.Fog -> "Niebla"
                WeatherConditionType.HighWind -> "Viento fuerte"
                WeatherConditionType.Storm -> "Tormenta"
                WeatherConditionType.ReducedVisibility -> "Visibilidad reducida"
                WeatherConditionType.Unknown -> providerDescription
            }
            "fr" -> when (type) {
                WeatherConditionType.Clear -> "Temps dégagé"
                WeatherConditionType.Rain -> "Pluie"
                WeatherConditionType.HeavyRain -> "Forte pluie"
                WeatherConditionType.Snow -> "Neige"
                WeatherConditionType.Ice -> "Verglas"
                WeatherConditionType.Fog -> "Brouillard"
                WeatherConditionType.HighWind -> "Vent fort"
                WeatherConditionType.Storm -> "Orage"
                WeatherConditionType.ReducedVisibility -> "Visibilité réduite"
                WeatherConditionType.Unknown -> providerDescription
            }
            "ar" -> when (type) {
                WeatherConditionType.Clear -> "طقس صافٍ"
                WeatherConditionType.Rain -> "أمطار"
                WeatherConditionType.HeavyRain -> "أمطار غزيرة"
                WeatherConditionType.Snow -> "ثلوج"
                WeatherConditionType.Ice -> "جليد"
                WeatherConditionType.Fog -> "ضباب"
                WeatherConditionType.HighWind -> "رياح قوية"
                WeatherConditionType.Storm -> "عاصفة"
                WeatherConditionType.ReducedVisibility -> "رؤية منخفضة"
                WeatherConditionType.Unknown -> providerDescription
            }
            "he" -> when (type) {
                WeatherConditionType.Clear -> "בהיר"
                WeatherConditionType.Rain -> "גשם"
                WeatherConditionType.HeavyRain -> "גשם כבד"
                WeatherConditionType.Snow -> "שלג"
                WeatherConditionType.Ice -> "קרח"
                WeatherConditionType.Fog -> "ערפל"
                WeatherConditionType.HighWind -> "רוח חזקה"
                WeatherConditionType.Storm -> "סערה"
                WeatherConditionType.ReducedVisibility -> "ראות מוגבלת"
                WeatherConditionType.Unknown -> providerDescription
            }
            else -> providerDescription
        }
    }

    private fun trafficLevel(level: TrafficCongestionLevel, locale: Locale): String {
        val language = locale.language.lowercase(Locale.ROOT)
        return when (language) {
            "es" -> when (level) {
                TrafficCongestionLevel.Unknown -> "desconocido"
                TrafficCongestionLevel.Light -> "ligero"
                TrafficCongestionLevel.Moderate -> "moderado"
                TrafficCongestionLevel.Heavy -> "intenso"
                TrafficCongestionLevel.Severe -> "severo"
            }
            "fr" -> when (level) {
                TrafficCongestionLevel.Unknown -> "inconnu"
                TrafficCongestionLevel.Light -> "léger"
                TrafficCongestionLevel.Moderate -> "modéré"
                TrafficCongestionLevel.Heavy -> "dense"
                TrafficCongestionLevel.Severe -> "très dense"
            }
            "ar" -> when (level) {
                TrafficCongestionLevel.Unknown -> "غير معروف"
                TrafficCongestionLevel.Light -> "خفيف"
                TrafficCongestionLevel.Moderate -> "متوسط"
                TrafficCongestionLevel.Heavy -> "شديد"
                TrafficCongestionLevel.Severe -> "شديد جدًا"
            }
            "he" -> when (level) {
                TrafficCongestionLevel.Unknown -> "לא ידוע"
                TrafficCongestionLevel.Light -> "קלה"
                TrafficCongestionLevel.Moderate -> "בינונית"
                TrafficCongestionLevel.Heavy -> "כבדה"
                TrafficCongestionLevel.Severe -> "חמורה"
            }
            else -> level.name.lowercase(Locale.ENGLISH)
        }
    }

    private fun copy(locale: Locale): AlertCopy = when (locale.language.lowercase(Locale.ROOT)) {
        "es" -> AlertCopy(
            laneGuidance = "Guía de carril",
            speedLimit = "Límite de velocidad",
            traffic = "Tráfico",
            minuteUnit = "min",
            distanceAhead = { "a $it" },
        )
        "fr" -> AlertCopy(
            laneGuidance = "Guidage de voie",
            speedLimit = "Limite de vitesse",
            traffic = "Trafic",
            minuteUnit = "min",
            distanceAhead = { "dans $it" },
        )
        "ar" -> AlertCopy(
            laneGuidance = "إرشاد المسار",
            speedLimit = "حد السرعة",
            traffic = "حركة المرور",
            minuteUnit = "د",
            distanceAhead = { "بعد $it" },
        )
        "he" -> AlertCopy(
            laneGuidance = "הנחיית נתיב",
            speedLimit = "הגבלת מהירות",
            traffic = "תנועה",
            minuteUnit = "דק׳",
            distanceAhead = { "בעוד $it" },
        )
        else -> AlertCopy(
            laneGuidance = "Lane guidance",
            speedLimit = "Speed limit",
            traffic = "Traffic",
            minuteUnit = "min",
            distanceAhead = { "in $it" },
        )
    }

    private data class AlertCopy(
        val laneGuidance: String,
        val speedLimit: String,
        val traffic: String,
        val minuteUnit: String,
        val distanceAhead: (String) -> String,
    )
}
