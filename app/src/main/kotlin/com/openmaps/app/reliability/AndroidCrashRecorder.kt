package com.openmaps.app.reliability

import android.content.Context
import com.openmaps.app.core.reliability.ReliabilityEvent
import com.openmaps.app.core.reliability.ReliabilityEventName
import com.openmaps.app.core.reliability.ReliabilityOutcome
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Captures the previous process's fatal exception without retaining exception messages or user data.
 *
 * Android cannot reliably upload during process death, so the minimal envelope is committed to local
 * preferences synchronously and uploaded on the next launch. A successful upload clears it.
 */
class AndroidCrashRecorder(context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val installed = AtomicBoolean(false)

    fun install() {
        if (!installed.compareAndSet(false, true)) return
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { persist(throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun pendingEvent(): ReliabilityEvent? {
        val raw = prefs.getString(PENDING_CRASH_KEY, null) ?: return null
        return runCatching {
            val json = JSONObject(raw)
            val fingerprint = json.getString("fingerprint")
            ReliabilityEvent(
                name = ReliabilityEventName.PreviousCrash,
                outcome = ReliabilityOutcome.Error,
                reason = json.optString("exceptionType").takeIf { it.isNotBlank() },
                metadata = buildMap {
                    put("fingerprint", fingerprint)
                    json.optString("topFrames").takeIf { it.isNotBlank() }?.let { put("topFrames", it) }
                },
            )
        }.getOrNull()
    }

    fun clearPending(fingerprint: String) {
        val raw = prefs.getString(PENDING_CRASH_KEY, null) ?: return
        val stored = runCatching { JSONObject(raw).optString("fingerprint") }.getOrNull()
        if (stored == fingerprint) prefs.edit().remove(PENDING_CRASH_KEY).apply()
    }

    private fun persist(throwable: Throwable) {
        val exceptionType = throwable.javaClass.name.take(MAX_EXCEPTION_TYPE_LENGTH)
        val frames = throwable.stackTrace
            .asSequence()
            .take(MAX_STACK_FRAMES)
            .map { frame ->
                buildString {
                    append(frame.className)
                    append('#')
                    append(frame.methodName)
                    if (frame.lineNumber >= 0) {
                        append(':')
                        append(frame.lineNumber)
                    }
                }
            }
            .toList()
        val canonical = (listOf(exceptionType) + frames).joinToString("|")
        val fingerprint = sha256Hex(canonical)

        val envelope = JSONObject()
            .put("fingerprint", fingerprint)
            .put("exceptionType", exceptionType)
            .put("topFrames", frames.joinToString(";").take(MAX_FRAMES_LENGTH))
            .toString()

        // commit() is intentional here: this runs during process death and must finish synchronously.
        prefs.edit().putString(PENDING_CRASH_KEY, envelope).commit()
    }

    private fun sha256Hex(value: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(value.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte) }

    private companion object {
        const val PREFS_NAME = "openmaps_reliability"
        const val PENDING_CRASH_KEY = "pending_crash"
        const val MAX_EXCEPTION_TYPE_LENGTH = 120
        const val MAX_STACK_FRAMES = 8
        const val MAX_FRAMES_LENGTH = 1_500
    }
}
