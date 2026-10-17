package com.openmaps.app.core.localization

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

enum class MeasurementSystem {
    Auto,
    Imperial,
    Metric,
}

enum class ResolvedMeasurementSystem {
    ImperialUs,
    ImperialUk,
    Metric,
}

object RegionMeasurementPolicy {
    fun resolve(preference: MeasurementSystem, locale: Locale = Locale.getDefault()): ResolvedMeasurementSystem =
        when (preference) {
            MeasurementSystem.Metric -> ResolvedMeasurementSystem.Metric
            MeasurementSystem.Imperial -> if (locale.country.equals("GB", ignoreCase = true)) {
                ResolvedMeasurementSystem.ImperialUk
            } else {
                ResolvedMeasurementSystem.ImperialUs
            }
            MeasurementSystem.Auto -> when (locale.country.uppercase(Locale.ROOT)) {
                "US", "LR", "MM" -> ResolvedMeasurementSystem.ImperialUs
                "GB" -> ResolvedMeasurementSystem.ImperialUk
                else -> ResolvedMeasurementSystem.Metric
            }
        }
}

object NavigationUnits {
    private const val FEET_PER_METER = 3.280839895
    private const val YARDS_PER_METER = 1.093613298
    private const val METERS_PER_MILE = 1609.344
    private const val METERS_PER_SECOND_TO_MPH = 2.236936292
    private const val METERS_PER_SECOND_TO_KMH = 3.6

    fun formatDistance(
        meters: Double,
        preference: MeasurementSystem = MeasurementSystem.Auto,
        locale: Locale = Locale.getDefault(),
    ): String = when (RegionMeasurementPolicy.resolve(preference, locale)) {
        ResolvedMeasurementSystem.Metric -> formatMetricDistance(meters.coerceAtLeast(0.0), locale)
        ResolvedMeasurementSystem.ImperialUs -> formatUsDistance(meters.coerceAtLeast(0.0), locale)
        ResolvedMeasurementSystem.ImperialUk -> formatUkDistance(meters.coerceAtLeast(0.0), locale)
    }

    fun formatDuration(seconds: Double, locale: Locale = Locale.getDefault()): String {
        val totalMinutes = (seconds / 60.0).roundToInt().coerceAtLeast(1)
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        val number = NumberFormat.getIntegerInstance(locale)
        return if (hours > 0) {
            "${number.format(hours)} h ${number.format(minutes)} min"
        } else {
            "${number.format(minutes)} min"
        }
    }

    fun formatCurrentSpeed(
        metersPerSecond: Float,
        preference: MeasurementSystem = MeasurementSystem.Auto,
        locale: Locale = Locale.getDefault(),
    ): String {
        val resolved = RegionMeasurementPolicy.resolve(preference, locale)
        val value = when (resolved) {
            ResolvedMeasurementSystem.ImperialUs,
            ResolvedMeasurementSystem.ImperialUk,
            -> metersPerSecond * METERS_PER_SECOND_TO_MPH
            ResolvedMeasurementSystem.Metric -> metersPerSecond * METERS_PER_SECOND_TO_KMH
        }
        return formatSpeed(value.toDouble(), resolved, locale)
    }

    fun formatPostedSpeed(
        value: Double,
        sourceIsMph: Boolean,
        preference: MeasurementSystem = MeasurementSystem.Auto,
        locale: Locale = Locale.getDefault(),
    ): String {
        val resolved = RegionMeasurementPolicy.resolve(preference, locale)
        val converted = when {
            resolved == ResolvedMeasurementSystem.Metric && sourceIsMph -> value * 1.609344
            resolved != ResolvedMeasurementSystem.Metric && !sourceIsMph -> value / 1.609344
            else -> value
        }
        return formatSpeed(converted, resolved, locale)
    }

    private fun formatMetricDistance(meters: Double, locale: Locale): String {
        val number = NumberFormat.getNumberInstance(locale)
        return if (meters < 1000.0) {
            number.maximumFractionDigits = 0
            "${number.format(meters.roundToInt())} m"
        } else {
            val km = meters / 1000.0
            number.maximumFractionDigits = if (km < 10.0) 1 else 0
            number.minimumFractionDigits = 0
            "${number.format(km)} km"
        }
    }

    private fun formatUsDistance(meters: Double, locale: Locale): String {
        val feet = meters * FEET_PER_METER
        val miles = meters / METERS_PER_MILE
        val number = NumberFormat.getNumberInstance(locale)
        return if (feet < 1000.0) {
            number.maximumFractionDigits = 0
            "${number.format(feet.roundToInt())} ft"
        } else {
            number.maximumFractionDigits = if (miles < 10.0) 1 else 0
            number.minimumFractionDigits = 0
            "${number.format(miles)} mi"
        }
    }

    private fun formatUkDistance(meters: Double, locale: Locale): String {
        val yards = meters * YARDS_PER_METER
        val miles = meters / METERS_PER_MILE
        val number = NumberFormat.getNumberInstance(locale)
        return if (miles < 0.5) {
            number.maximumFractionDigits = 0
            "${number.format(yards.roundToInt())} yd"
        } else {
            number.maximumFractionDigits = if (miles < 10.0) 1 else 0
            number.minimumFractionDigits = 0
            "${number.format(miles)} mi"
        }
    }

    private fun formatSpeed(value: Double, resolved: ResolvedMeasurementSystem, locale: Locale): String {
        val number = NumberFormat.getIntegerInstance(locale)
        val unit = if (resolved == ResolvedMeasurementSystem.Metric) "km/h" else "mph"
        return "${number.format(value.roundToInt())} $unit"
    }
}
