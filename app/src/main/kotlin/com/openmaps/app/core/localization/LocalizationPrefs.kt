package com.openmaps.app.core.localization

import android.content.Context

class LocalizationPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun measurementSystem(): MeasurementSystem =
        prefs.getString(KEY_MEASUREMENT_SYSTEM, null)?.let { stored ->
            runCatching { MeasurementSystem.valueOf(stored) }.getOrNull()
        } ?: MeasurementSystem.Auto

    fun setMeasurementSystem(system: MeasurementSystem) {
        prefs.edit().putString(KEY_MEASUREMENT_SYSTEM, system.name).apply()
    }

    private companion object {
        const val FILE = "openmaps_localization_prefs"
        const val KEY_MEASUREMENT_SYSTEM = "measurement_system"
    }
}
