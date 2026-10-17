package com.openmaps.app.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import com.openmaps.app.core.voice.AnnouncementStage
import com.openmaps.app.core.voice.NeuralVoiceRepository
import com.openmaps.app.core.voice.VoiceAnnouncementTemplates
import com.openmaps.app.core.voice.VoiceEvent
import com.openmaps.app.core.voice.VoiceGuidanceEngine
import com.openmaps.app.core.voice.VoiceGuidanceState
import com.openmaps.app.core.voice.VoiceLanguage
import com.openmaps.app.core.voice.VoicePronunciationNormalizer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicLong

/**
 * Neural-first navigation voice with a deterministic Android TTS safety fallback.
 *
 * Immediate turn/reroute prompts never wait on the network: cached neural audio is used when
 * available, otherwise Android TTS speaks immediately. Earlier-stage prompts can wait briefly for
 * neural synthesis; a failed/slow request falls back automatically.
 */
@Suppress("LogNotTimber")
class HybridVoiceGuidanceEngine(
    context: Context,
    private val neuralRepository: NeuralVoiceRepository,
    private val fallback: VoiceGuidanceEngine,
) : VoiceGuidanceEngine {

    override val state: Flow<VoiceGuidanceState> = fallback.state

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val cacheDirectory = File(context.applicationContext.cacheDir, "openmaps_neural_voice").apply { mkdirs() }
    private val generation = AtomicLong(0L)
    private val playerLock = Any()

    @Volatile
    private var currentPlayer: MediaPlayer? = null

    @Volatile
    private var enabled: Boolean = true

    @Volatile
    private var language: VoiceLanguage = VoiceLanguage.EnglishUs

    init {
        pruneCache()
    }

    override fun speak(event: VoiceEvent) {
        if (!enabled) return

        val token = generation.incrementAndGet()
        val selectedLanguage = language
        val spokenText = spokenText(event, selectedLanguage)
        val cached = cacheFile(spokenText, selectedLanguage)

        if (cached.isFile && cached.length() > 0) {
            playCached(cached, token, event)
            maybePrefetchLaterStages(event, selectedLanguage)
            return
        }

        if (requiresImmediateFallback(event)) {
            fallback.speak(event)
            return
        }

        scope.launch {
            val result = neuralRepository.synthesize(spokenText, language)
            if (generation.get() != token || !enabled) return@launch

            result.onSuccess { bytes ->
                runCatching {
                    cached.parentFile?.mkdirs()
                    cached.writeBytes(bytes)
                    playCached(cached, token, event)
                    maybePrefetchLaterStages(event, selectedLanguage)
                }.onFailure {
                    Log.w(TAG, "Unable to cache/play neural voice; using Android TTS", it)
                    if (generation.get() == token && enabled) fallback.speak(event)
                }
            }.onFailure {
                if (generation.get() == token && enabled) fallback.speak(event)
            }
        }
    }

    override fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        fallback.setEnabled(enabled)
        if (!enabled) stop()
    }

    override fun setLanguage(language: VoiceLanguage) {
        this.language = language
        generation.incrementAndGet()
        stopNeuralPlayer()
        fallback.setLanguage(language)
    }

    override fun stop() {
        generation.incrementAndGet()
        stopNeuralPlayer()
        fallback.stop()
    }

    override fun shutdown() {
        generation.incrementAndGet()
        stopNeuralPlayer()
        scope.cancel()
        fallback.shutdown()
    }

    private fun playCached(file: File, token: Long, event: VoiceEvent) {
        if (generation.get() != token || !enabled) return

        try {
            fallback.stop()
            val player = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build(),
                )
                setDataSource(file.absolutePath)
                prepare()
            }

            synchronized(playerLock) {
                currentPlayer?.runCatching { stop() }
                currentPlayer?.release()
                currentPlayer = player
            }

            player.setOnCompletionListener { completed ->
                synchronized(playerLock) {
                    if (currentPlayer === completed) currentPlayer = null
                }
                completed.release()
            }
            player.setOnErrorListener { failed, _, _ ->
                synchronized(playerLock) {
                    if (currentPlayer === failed) currentPlayer = null
                }
                failed.release()
                file.delete()
                if (generation.get() == token && enabled) fallback.speak(event)
                true
            }
            player.start()
        } catch (t: Throwable) {
            file.delete()
            Log.w(TAG, "Neural voice playback failed; using Android TTS", t)
            if (generation.get() == token && enabled) fallback.speak(event)
        }
    }

    private fun maybePrefetchLaterStages(event: VoiceEvent, selectedLanguage: VoiceLanguage) {
        val maneuver = event as? VoiceEvent.Maneuver ?: return
        if (maneuver.stage != AnnouncementStage.Far) return

        listOf(AnnouncementStage.Near, AnnouncementStage.Immediate).forEach { stage ->
            val futureEvent = maneuver.copy(stage = stage)
            val text = spokenText(futureEvent, selectedLanguage)
            val target = cacheFile(text, selectedLanguage)
            if (target.isFile && target.length() > 0) return@forEach

            scope.launch {
                neuralRepository.synthesize(text, selectedLanguage).onSuccess { bytes ->
                    runCatching {
                        target.parentFile?.mkdirs()
                        target.writeBytes(bytes)
                    }
                }
            }
        }
    }

    private fun spokenText(event: VoiceEvent, selectedLanguage: VoiceLanguage): String =
        VoicePronunciationNormalizer.normalize(
            VoiceAnnouncementTemplates.phraseFor(event, selectedLanguage),
            selectedLanguage,
        )

    private fun requiresImmediateFallback(event: VoiceEvent): Boolean =
        event == VoiceEvent.Rerouting ||
            (event is VoiceEvent.Maneuver && event.stage == AnnouncementStage.Immediate)

    private fun cacheFile(text: String, selectedLanguage: VoiceLanguage): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest((selectedLanguage.name + "\n" + text).toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(cacheDirectory, "$digest.wav")
    }

    private fun stopNeuralPlayer() {
        synchronized(playerLock) {
            currentPlayer?.runCatching { stop() }
            currentPlayer?.release()
            currentPlayer = null
        }
    }

    private fun pruneCache() {
        runCatching {
            cacheDirectory.listFiles()
                ?.filter { it.isFile }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(MAX_CACHE_FILES)
                ?.forEach(File::delete)
        }
    }

    private companion object {
        const val TAG = "HybridVoiceGuidance"
        const val MAX_CACHE_FILES = 160
    }
}
