package com.openmaps.app.core.camera

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/**
 * Persists the one bit the OS will not tell us for the camera: "has the app ever asked?".
 *
 * Separate from [com.openmaps.app.core.location.LocationPermissionPrefs] rather than sharing its
 * file: the two permissions are granted and explained independently, and a user who allowed
 * location on first launch must still be asked about the camera (it is not requested until Live
 * is actually opened, per the product decision to avoid a camera prompt on launch).
 */
class CameraPermissionPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasRequested(): Boolean = prefs.getBoolean(KEY_REQUESTED, false)

    fun markRequested() {
        prefs.edit().putBoolean(KEY_REQUESTED, true).apply()
    }

    private companion object {
        const val FILE = "openmaps_prefs"
        const val KEY_REQUESTED = "camera_permission_requested"
    }
}

/**
 * Translates the OS permission state into [CameraPermissionState].
 *
 * Mirrors [com.openmaps.app.core.location.LocationPermissions.evaluate] exactly, including the
 * shared "the OS cannot tell never-asked from don't-ask-again, so combine
 * `shouldShowRequestPermissionRationale` with a persisted flag" approach.
 */
object CameraPermissions {
    val REQUEST: Array<String> = arrayOf(Manifest.permission.CAMERA)

    fun evaluate(activity: Activity, prefs: CameraPermissionPrefs): CameraPermissionState {
        if (isGranted(activity, Manifest.permission.CAMERA)) return CameraPermissionState.Granted

        val canShowRationale = REQUEST.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        return when {
            canShowRationale -> CameraPermissionState.Denied
            prefs.hasRequested() -> CameraPermissionState.PermanentlyDenied
            else -> CameraPermissionState.NotRequested
        }
    }

    fun isGranted(context: Context): Boolean = isGranted(context, Manifest.permission.CAMERA)

    private fun isGranted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}