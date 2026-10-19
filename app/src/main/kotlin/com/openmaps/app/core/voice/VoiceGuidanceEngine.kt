package com.openmaps.app.core.voice

import com.openmaps.app.core.localization.MeasurementSystem
import kotlinx.coroutines.flow.Flow

/**
 * The voice-guidance seam (delta-spec section 14), same shape as `SpatialLocalizationEngine` and
 * `RearCameraEngine`: a provider-neutral interface the feature layer depends on, with exactly one
 * Android-aware implementation (`voice/AndroidTextToSpeechEngine`) and a [NoopVoiceGuidanceEngine]
 * for tests and the "voice unavailable" fallback.
 *
 * **Voice is additive, never a navigation dependency** (section 17): nothing in `NavigationSession`,
 * `RouteProgressTracker` or `MapViewModel`'s trip state reads from this engine, and every method
 * here must be safe to call even when speech synthesis is unavailable.
 */
interface VoiceGuidanceEngine {

    val state: Flow<VoiceGuidanceState>

    /** Speaks [event] in the current language, or does nothing if muted/unavailable. */
    fun speak(event: VoiceEvent)

    fun setEnabled(enabled: Boolean)

    fun setLanguage(language: VoiceLanguage)

    /** Keeps spoken far/near maneuver distances aligned with the user’s regional unit setting. */
    fun setMeasurementSystem(system: MeasurementSystem)

    /** Stops any speech in progress — called on reroute/arrival so a stale phrase is never
     * still playing over a newer one. */
    fun stop()

    /** Releases the underlying synthesizer. Safe to call multiple times. */
    fun shutdown()
}
