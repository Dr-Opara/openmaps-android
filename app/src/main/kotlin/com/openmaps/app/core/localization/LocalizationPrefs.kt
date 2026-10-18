package com.openmaps.app.core.localization

import android.content.Context

class LocalizationPrefs(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun measurementSystem(): MeasurementSystem =
        prefs.getString(KEY_MEASUREMENT_SYSTEM, null)?.let { stored ->
            runCatching { MeasurementSystem.valueOf(stored) }.getOrNull()
        } ?: MeasurementSystem.Auto

    fun setMeasurementSystem(system: MeasurementSystem) {
        prefs.edit().putString(KEY_MEASUREMENT_SYSTEM, system.name).apply()
    }

    fun currentLocaleTag(): String =
        appContext.resources.configuration.locales[0].toLanguageTag()

    private companion object {
        const val FILE = "openmaps_localization_prefs"
        const val KEY_MEASUREMENT_SYSTEM = "measurement_system"
    }
}
