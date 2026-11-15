package com.openmaps.app.vision.recording

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where recorded sessions live, and the only code that deletes them.
 *
 * Storage is the app's own external files directory (`Android/data/com.openmaps.app/files/`),
 * falling back to internal storage. Both are private to the app, are removed when the app is
 * uninstalled, and are not backed up (`allowBackup="false"`). **Nothing in this package opens a
 * network connection**: a recording leaves the phone only if a person copies it off.
 */
class RecordingStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("vision_recording", Context.MODE_PRIVATE)

    val root: File
        get() = File(appContext.getExternalFilesDir(null) ?: appContext.filesDir, "vision-recordings")

    data class StoredSession(val directory: File, val name: String, val bytes: Long, val frameCount: Int)

    /** Explicit, revocable consent to record camera, location and motion data. */
    var consentGiven: Boolean
        get() = prefs.getBoolean(KEY_CONSENT, false)
        set(value) = prefs.edit().putBoolean(KEY_CONSENT, value).apply()

    fun newSessionDirectory(): File {
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        var directory = File(root, name)
        var suffix = 2
        while (directory.exists()) directory = File(root, "$name-${suffix++}")
        directory.mkdirs()
        return directory
    }

    fun sessions(): List<StoredSession> =
        root.listFiles { file -> file.isDirectory }
            ?.sortedByDescending { it.name }
            ?.map { directory ->
                StoredSession(
                    directory = directory,
                    name = directory.name,
                    bytes = directory.walkTopDown().filter { it.isFile }.sumOf { it.length() },
                    frameCount = File(directory, "frames").list()?.size ?: 0,
                )
            }
            .orEmpty()

    fun delete(session: StoredSession): Boolean = isInsideRoot(session.directory) && session.directory.deleteRecursively()

    fun deleteAll(): Boolean = root.deleteRecursively()

    fun freeBytes(): Long = (root.takeIf { it.exists() } ?: root.parentFile ?: appContext.filesDir).usableSpace

    /** Never delete anything that is not a direct child of the recordings folder. */
    private fun isInsideRoot(directory: File): Boolean =
        directory.parentFile?.canonicalPath == root.canonicalPath

    private companion object {
        const val KEY_CONSENT = "consentGiven"
    }
}
