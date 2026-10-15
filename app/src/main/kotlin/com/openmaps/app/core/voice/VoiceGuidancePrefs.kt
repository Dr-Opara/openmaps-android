package com.openmaps.app.core.voice

import android.content.Context

/**
 * Persists the two local, no-account voice preferences (delta-spec sections 24-25): the selected
 * [VoiceLanguage] and whether voice is muted. Same flat-`SharedPreferences` shape as
 * `CameraPermissionPrefs`/`RecentSearchStore` — a Phase-sized concern kept Phase-sized.
 */
class VoiceGuidancePrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun language(): VoiceLanguage =
        prefs.getString(KEY_LANGUAGE, null)?.let { stored ->
            runCatching { VoiceLanguage.valueOf(stored) }.getOrNull()
        } ?: VoiceLanguage.EnglishUs

    fun setLanguage(language: VoiceLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.name).apply()
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, true)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val FILE = "openmaps_voice_prefs"
        const val KEY_LANGUAGE = "voice_language"
        const val KEY_ENABLED = "voice_enabled"
    }
}
