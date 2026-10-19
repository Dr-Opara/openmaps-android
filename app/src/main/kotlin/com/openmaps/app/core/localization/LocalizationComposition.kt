package com.openmaps.app.core.localization

import androidx.compose.runtime.staticCompositionLocalOf
import java.util.Locale

val LocalMeasurementSystem = staticCompositionLocalOf { MeasurementSystem.Auto }
val LocalAppLocale = staticCompositionLocalOf { Locale.getDefault() }
