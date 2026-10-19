package com.openmaps.app.reliability

import com.openmaps.app.core.reliability.ReliabilityEvent
import com.openmaps.app.core.reliability.ReliabilityReporter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fire-and-forget first-party reliability telemetry.
 *
 * This client intentionally never sends coordinates, search/destination text, exception messages,
 * user names, emails or provider credentials. The stable install id is sent only to the OpenMaps
 * backend and hashed there before persistence.
 */
class BackendReliabilityReporter(
    private val endpointUrl: String,
    private val publishableKey: String,
    private val clientId: String,
    private val appVersion: String,
    private val appBuild: Int,
    private val deviceApi: Int,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : ReliabilityReporter {

    override fun record(event: ReliabilityEvent) {
        if (!isConfigured) return
        scope.launch { post(event) }
    }

    fun uploadPendingCrash(recorder: AndroidCrashRecorder) {
        val pending = recorder.pendingEvent() ?: return
        if (!isConfigured) return
        scope.launch {
            if (post(pending)) {
                pending.metadata["fingerprint"]?.let(recorder::clearPending)
            }
        }
    }

    private val isConfigured: Boolean
        get() = endpointUrl.isNotBlank() && publishableKey.isNotBlank() && clientId.isNotBlank()

    private fun post(event: ReliabilityEvent): Boolean {
        val connection = URL(endpointUrl).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.doOutput = true
        connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
        connection.readTimeout = READ_TIMEOUT_MILLIS
        connection.setRequestProperty("Accept", "application/json")
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("apikey", publishableKey)
        connection.setRequestProperty("x-openmaps-client-id", clientId)

        val safeMetadata = JSONObject()
        event.metadata.entries
            .asSequence()
            .filterNot { (key, _) -> key.containsSensitiveName() }
            .take(MAX_METADATA_ENTRIES)
            .forEach { (key, value) ->
                safeMetadata.put(key.take(MAX_METADATA_KEY_LENGTH), value.take(MAX_METADATA_VALUE_LENGTH))
            }

        val body = JSONObject()
            .put("event", event.name.wireName)
            .put("outcome", event.outcome.wireName)
            .put("appVersion", appVersion)
            .put("appBuild", appBuild)
            .put("deviceApi", deviceApi)
            .put("metadata", safeMetadata)
            .apply {
                event.reason?.takeIf { it.isNotBlank() }?.let { put("reason", it.take(MAX_REASON_LENGTH)) }
                event.durationMillis?.coerceAtLeast(0)?.let { put("durationMs", it) }
            }
            .toString()

        return try {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            connection.responseCode in 200..299
        } catch (_: Exception) {
            false
        } finally {
            connection.disconnect()
        }
    }

    private fun String.containsSensitiveName(): Boolean {
        val normalized = lowercase()
        return listOf("lat", "lon", "location", "address", "destination", "query", "email", "phone", "name")
            .any(normalized::contains)
    }

    private companion object {
        const val CONNECT_TIMEOUT_MILLIS = 2_500
        const val READ_TIMEOUT_MILLIS = 4_000
        const val MAX_REASON_LENGTH = 120
        const val MAX_METADATA_ENTRIES = 12
        const val MAX_METADATA_KEY_LENGTH = 64
        const val MAX_METADATA_VALUE_LENGTH = 1_500
    }
}
