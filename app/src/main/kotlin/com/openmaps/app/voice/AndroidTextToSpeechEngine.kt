package com.openmaps.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.util.Log
import com.openmaps.app.core.voice.VoiceAnnouncementTemplates
import com.openmaps.app.core.voice.VoiceEvent
import com.openmaps.app.core.voice.VoiceGuidanceEngine
import com.openmaps.app.core.voice.VoiceGuidanceState
import com.openmaps.app.core.voice.VoiceLanguage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * The only file that imports `android.speech.tts` (delta-spec section 14), mirroring how
 * `spatial/arcore` is the only file that imports `com.google.ar.core`.
 *
 * Initialization is asynchronous (the platform `TextToSpeech` constructor takes a callback), so
 * [VoiceGuidanceState.ttsAvailable] starts `false` and flips true only once the engine genuinely
 * reports [TextToSpeech.SUCCESS] — [speak] is a silent no-op before that and on any failure, never
 * a crash and never something navigation waits on (section 17).
 */
@Suppress("LogNotTimber")
class AndroidTextToSpeechEngine(context: Context) : VoiceGuidanceEngine {

    private val _state = MutableStateFlow(VoiceGuidanceState(ttsAvailable = false))
    override val state: Flow<VoiceGuidanceState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null

    init {
        tts = TextToSpeech(context.applicationContext) { status ->
            val available = status == TextToSpeech.SUCCESS
            _state.value = _state.value.copy(ttsAvailable = available)
            if (available) {
                tts?.setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                applyLocale(_state.value.language)
            }
        }
    }

    override fun speak(event: VoiceEvent) {
        val current = _state.value
        if (!current.enabled || !current.ttsAvailable) return
        val engine = tts ?: return
        val phrase = VoiceAnnouncementTemplates.phraseFor(event, current.language)
        try {
            engine.speak(phrase, TextToSpeech.QUEUE_FLUSH, null, event::class.simpleName)
        } catch (t: Throwable) {
            // A platform TTS call failing must never take navigation down with it.
            Log.w(TAG, "speak() failed", t)
        }
    }

    override fun setEnabled(enabled: Boolean) {
        _state.value = _state.value.copy(enabled = enabled)
        if (!enabled) stop()
    }

    override fun setLanguage(language: VoiceLanguage) {
        _state.value = _state.value.copy(language = language)
        if (_state.value.ttsAvailable) applyLocale(language)
    }

    override fun stop() {
        try {
            tts?.stop()
        } catch (t: Throwable) {
            Log.w(TAG, "stop() failed", t)
        }
    }

    override fun shutdown() {
        try {
            tts?.stop()
            tts?.shutdown()
        } catch (t: Throwable) {
            Log.w(TAG, "shutdown() failed", t)
        } finally {
            tts = null
        }
    }

    /**
     * Section 23: Android has no native Nigerian Pidgin TTS voice. For [VoiceLanguage.NigerianPidgin]
     * this tries a Nigerian English locale first (closer in rhythm/vocabulary to Pidgin than generic
     * US English), then falls back to US English — either way the *text* stays the real Pidgin
     * template from [VoiceAnnouncementTemplates]; only the voice reading it changes.
     */
    private fun applyLocale(language: VoiceLanguage) {
        val engine = tts ?: return
        if (language != VoiceLanguage.NigerianPidgin) {
            engine.setLanguage(language.toPreferredLocale())
            _state.value = _state.value.copy(usingFallbackVoice = false)
            return
        }
        val nigerianEnglishResult = engine.setLanguage(Locale("en", "NG"))
        val usedFallback = if (nigerianEnglishResult.isUnavailable()) {
            engine.setLanguage(Locale.US)
            true
        } else {
            true // Nigerian English is itself a fallback voice reading Pidgin text — always true here.
        }
        _state.value = _state.value.copy(usingFallbackVoice = usedFallback)
    }

    private fun Int.isUnavailable(): Boolean =
        this == TextToSpeech.LANG_MISSING_DATA || this == TextToSpeech.LANG_NOT_SUPPORTED

    private fun VoiceLanguage.toPreferredLocale(): Locale = when (this) {
        VoiceLanguage.EnglishUs -> Locale.US
        VoiceLanguage.EnglishUk -> Locale.UK
        VoiceLanguage.Spanish -> Locale("es", "ES")
        VoiceLanguage.French -> Locale.FRANCE
        VoiceLanguage.NigerianPidgin -> Locale("en", "NG") // handled above; listed for exhaustiveness
    }

    private companion object {
        const val TAG = "VoiceGuidance"
    }
}
