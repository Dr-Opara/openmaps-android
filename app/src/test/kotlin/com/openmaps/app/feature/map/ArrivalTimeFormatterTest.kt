package com.openmaps.app.feature.map

import java.time.ZoneId
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test

class ArrivalTimeFormatterTest {
    private val utc = ZoneId.of("UTC")
    private val noonUtc = 1_767_268_800_000L // 2026-01-01T12:00:00Z

    @Test
    fun usLocale_usesTwelveHourClock() {
        assertEquals(
            "1:05 PM",
            ArrivalTimeFormatter.format(3900.0, noonUtc, utc, Locale.US),
        )
    }

    @Test
    fun frenchLocale_usesTwentyFourHourClock() {
        assertEquals(
            "13:05",
            ArrivalTimeFormatter.format(3900.0, noonUtc, utc, Locale.FRANCE),
        )
    }
}
