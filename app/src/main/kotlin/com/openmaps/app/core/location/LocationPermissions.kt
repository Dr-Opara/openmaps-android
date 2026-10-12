package com.openmaps.app.core.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat

/** Persists the one bit the OS will not tell us: "has the app ever asked?". */
class LocationPermissionPrefs(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun hasRequested(): Boolean = prefs.getBoolean(KEY_REQUESTED, false)

    fun markRequested() {
        prefs.edit().putBoolean(KEY_REQUESTED, true).apply()
    }

    private companion object {
        const val FILE = "openmaps_prefs"
        const val KEY_REQUESTED = "location_permission_requested"
    }
}

/** Translates the OS permission state into [LocationPermissionState]. */
object LocationPermissions {
    val REQUEST: Array<String> = arrayOf(
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION,
    )

    fun evaluate(activity: Activity, prefs: LocationPermissionPrefs): LocationPermissionState {
        val fine = isGranted(activity, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = isGranted(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (fine || coarse) return LocationPermissionState.Granted(precise = fine)

        val canShowRationale = REQUEST.any { ActivityCompat.shouldShowRequestPermissionRationale(activity, it) }
        return when {
            canShowRationale -> LocationPermissionState.Denied
            prefs.hasRequested() -> LocationPermissionState.PermanentlyDenied
            else -> LocationPermissionState.NotRequested
        }
    }

    private fun isGranted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
