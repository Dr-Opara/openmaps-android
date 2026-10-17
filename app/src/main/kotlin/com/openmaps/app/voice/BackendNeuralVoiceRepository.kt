package com.openmaps.app.voice

import com.openmaps.app.core.voice.NeuralVoiceException
import com.openmaps.app.core.voice.NeuralVoiceRepository
import com.openmaps.app.core.voice.VoiceLanguage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

class BackendNeuralVoiceRepository(
    private val endpointUrl: String,
    private val publishableKey: String,
) : NeuralVoiceRepository {

    override suspend fun synthesize(text: String, language: VoiceLanguage): Result<ByteArray> =
        withContext(Dispatchers.IO) {
            try {
                Result.success(post(text, language))
            } catch (e: CancellationException) {
                throw e
            } catch (e: SocketTimeoutException) {
                Result.failure(NeuralVoiceException.Unavailable)
            } catch (e: IOException) {
                Result.failure(NeuralVoiceException.Unavailable)
            } catch (e: NeuralVoiceException) {
                Result.failure(e)
            }
        }

    private fun post(text: String, language: VoiceLanguage): ByteArray {
        if (endpointUrl.isBlank() || publishableKey.isBlank()) throw NeuralVoiceException.Unavailable
        val connection = URL(endpointUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "audio/aac")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("apikey", publishableKey)

        val body = JSONObject()
            .put("text", text)
            .put("language", language.name)
            .toString()
            .toByteArray(Charsets.UTF_8)

        try {
            connection.outputStream.use { it.write(body) }
            if (connection.responseCode !in 200..299) throw NeuralVoiceException.Unavailable

            val out = ByteArrayOutputStream()
            connection.inputStream.use { input ->
                val buffer = ByteArray(8192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_AUDIO_BYTES) throw NeuralVoiceException.Malformed
                    out.write(buffer, 0, count)
                }
            }
            return out.toByteArray().takeIf { it.isNotEmpty() } ?: throw NeuralVoiceException.Malformed
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 2_500
        const val READ_TIMEOUT_MILLIS = 4_000
        const val MAX_AUDIO_BYTES = 2_000_000
    }
}
