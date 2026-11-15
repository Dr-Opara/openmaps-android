package com.openmaps.app.vision

import android.content.Context
import com.openmaps.app.core.vision.CalibrationProfile

/**
 * Persists one [CalibrationProfile] per phone model, camera and display rotation, on this device
 * only (app-private preferences; the manifest sets `allowBackup="false"`, so it is not backed up).
 */
class CalibrationProfileStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("vision_calibration_profiles", Context.MODE_PRIVATE)

    fun load(deviceModel: String, cameraId: String?, displayRotationDegrees: Int): CalibrationProfile? {
        val prefix = prefix(deviceModel, cameraId, displayRotationDegrees)
        val values = prefs.all
            .filterKeys { it.startsWith(prefix) }
            .mapNotNull { (key, value) -> (value as? String)?.let { key.removePrefix(prefix) to it } }
            .toMap()
        if (values.isEmpty()) return null
        return CalibrationProfile.fromMap(values)?.takeIf { it.appliesTo(deviceModel, cameraId, displayRotationDegrees) }
    }

    fun save(profile: CalibrationProfile) {
        val prefix = prefix(profile.deviceModel, profile.cameraId, profile.displayRotationDegrees)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        profile.toMap().forEach { (key, value) -> editor.putString(prefix + key, value) }
        editor.apply()
    }

    fun clear(deviceModel: String, cameraId: String?, displayRotationDegrees: Int) {
        val prefix = prefix(deviceModel, cameraId, displayRotationDegrees)
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith(prefix) }.forEach { editor.remove(it) }
        editor.apply()
    }

    private fun prefix(deviceModel: String, cameraId: String?, displayRotationDegrees: Int): String =
        "$deviceModel|${cameraId ?: "?"}|$displayRotationDegrees|"
}
