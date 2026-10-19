package com.openmaps.app.feature.map

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * "Arrive 3:45 PM" — section A5/M2 of the Phase 11 brief ("expected arrival time if calculable").
 * A pure, clock-injectable function (not a `@Composable`) so it is unit-testable against a fixed
 * instant rather than the device's real clock — `BottomPanel`/`LiveTripSummary` call it with
 * `System.currentTimeMillis()` at render time.
 *
 * Never a separate estimate: it is always `now + durationSeconds`, the same figure the remaining-time
 * text already shows, just expressed as a clock time rather than a duration — nothing here is a
 * second, independently-fallible prediction.
 */
object ArrivalTimeFormatter {
    fun format(
        remainingDurationSeconds: Double,
        nowMillis: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        locale: Locale = Locale.getDefault(),
    ): String {
        val arrival = Instant.ofEpochMilli(nowMillis + (remainingDurationSeconds * 1000).toLong())
        val formatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
        return arrival.atZone(zoneId).toLocalTime().format(formatter)
    }
}
