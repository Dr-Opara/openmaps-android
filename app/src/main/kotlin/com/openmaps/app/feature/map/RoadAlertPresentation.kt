package com.openmaps.app.feature.map

import com.openmaps.app.core.localization.MeasurementSystem
import com.openmaps.app.core.localization.NavigationUnits
import com.openmaps.app.core.roadintelligence.RouteAlert
import com.openmaps.app.core.roadintelligence.SpeedUnit
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Pure text formatting for a [RouteAlert], shared between Map's compact banner and Live's HUD pills
 * — mirrors [LiveHud]'s "one pure mapping, two renderers" shape so the wording never drifts between
 * the two surfaces.
 */
object RoadAlertPresentation {

    /** A short, glanceable label — what Map's compact chip and Live's pill both show as the headline. */
    fun headline(
        alert: RouteAlert,
        measurementSystem: MeasurementSystem = MeasurementSystem.Auto,
        locale: Locale = Locale.getDefault(),
    ): String = when (alert) {
        is RouteAlert.Hazard -> alert.event.title
        is RouteAlert.Informational -> alert.event.title
        is RouteAlert.Weather -> alert.condition.description
        is RouteAlert.Lane -> "Lane guidance"
        is RouteAlert.SpeedLimit -> "Speed limit ${formatSpeedValue(alert.info.value, alert.info.unit, measurementSystem, locale)}"
        is RouteAlert.Congestion -> "Traffic: ${alert.condition.congestionLevel.name.lowercase(Locale.US)}"
    }

    /** "in 0.3 mi" / "in 800 ft" style distance-ahead text, or null when the alert has none. */
    fun distanceAheadLabel(
        alert: RouteAlert,
        measurementSystem: MeasurementSystem = MeasurementSystem.Auto,
        locale: Locale = Locale.getDefault(),
    ): String? = when (alert) {
        is RouteAlert.Hazard -> alert.distanceAheadMeters?.let {
            "in ${NavigationUnits.formatDistance(it, measurementSystem, locale)}"
        }
        else -> null
    }

    /** Live traffic delay when the provider supplied a real traffic-adjusted duration. */
    fun delayLabel(alert: RouteAlert): String? {
        val congestion = alert as? RouteAlert.Congestion ?: return null
        val condition = congestion.condition
        val adjusted = condition.trafficAdjustedDurationSeconds ?: return null
        if (!condition.isLive) return null
        val delaySeconds = (adjusted - condition.baseDurationSeconds).coerceAtLeast(0.0)
        if (delaySeconds < 60.0) return null
        val minutes = (delaySeconds / 60.0).roundToInt()
        return "+${minutes} min"
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

    /** Current speed from a direct reading, converted to the same unit the posted limit uses. */
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
}
