package com.openmaps.app.core.voice

sealed class NeuralVoiceException(message: String) : Exception(message) {
    data object Unavailable : NeuralVoiceException("Neural navigation voice is unavailable")
    data object Malformed : NeuralVoiceException("Neural navigation voice returned invalid audio")
}

/**
 * Provider-neutral cloud voice seam. Provider credentials stay behind the OpenMaps voice gateway;
 * Android sends only the already-localized navigation phrase and selected navigation language.
 */
interface NeuralVoiceRepository {
    suspend fun synthesize(text: String, language: VoiceLanguage): Result<ByteArray>
}
