package com.openmaps.app.core.voice

import com.openmaps.app.core.localization.MeasurementSystem
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A [VoiceGuidanceEngine] that never speaks. Used by JVM unit tests (no `android.speech.tts` on
 * the classpath there) and as the real fallback when TTS genuinely cannot initialize — navigation
 * must continue exactly the same either way (section 17).
 */
class NoopVoiceGuidanceEngine(
    language: VoiceLanguage = VoiceLanguage.EnglishUs,
) : VoiceGuidanceEngine {

    private val _state = MutableStateFlow(VoiceGuidanceState(language = language, ttsAvailable = false))
    override val state: Flow<VoiceGuidanceState> = _state.asStateFlow()

    var spokenEvents: List<VoiceEvent> = emptyList()
        private set

    override fun speak(event: VoiceEvent) {
        spokenEvents = spokenEvents + event
    }

    override fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)
    }

    override fun setLanguage(language: VoiceLanguage) {
        _state.value = _state.value.copy(language = language)
    }

    override fun setMeasurementSystem(system: MeasurementSystem) = Unit

    override fun stop() = Unit

    override fun shutdown() = Unit
}
