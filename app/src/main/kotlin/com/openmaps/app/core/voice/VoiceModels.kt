package com.openmaps.app.core.voice

import com.openmaps.app.core.routing.ManeuverType

/**
 * Provider-neutral voice guidance (delta-spec sections 14-26). Mirrors how `core/spatial` and
 * `core/anchor` keep their platform engine (ARCore) out of the rest of the app: only
 * `voice/AndroidTextToSpeechEngine` imports `android.speech.tts`.
 */

/** The five initial language/voice options (section 18). */
enum class VoiceLanguage {
    EnglishUs,
    EnglishUk,
    Spanish,
    French,
    NigerianPidgin,
}

/** How far ahead of a maneuver an announcement fires — maps to the spec's "far/near/immediate". */
enum class AnnouncementStage { Far, Near, Immediate }

/**
 * What can be announced. Deliberately a small, closed set (section 15) rather than a free-form
 * string: every event is built from data the navigation layer already has (a real [ManeuverType],
 * a real street name, a real stage), so a template can never be asked to speak something that
 * was not actually true of the trip.
 */
sealed interface VoiceEvent {
    data object NavigationStarted : VoiceEvent

    /** Continue-straight/turn/keep-left/merge/exit/roundabout — [type] carries which one. */
    data class Maneuver(val type: ManeuverType, val streetName: String?, val stage: AnnouncementStage) : VoiceEvent

    data object Rerouting : VoiceEvent
    data object RouteUpdated : VoiceEvent
    data object DestinationApproaching : VoiceEvent
    data object Arrived : VoiceEvent
}

/** Voice state the UI can render (section 17) — never a navigation dependency, only additive. */
data class VoiceGuidanceState(
    val enabled: Boolean = true,
    val language: VoiceLanguage = VoiceLanguage.EnglishUs,
    val ttsAvailable: Boolean = true,
    /** True only for [VoiceLanguage.NigerianPidgin] when no matching TTS voice exists and a
     * compatible English voice is reading the Pidgin text instead (section 23). */
    val usingFallbackVoice: Boolean = false,
)
