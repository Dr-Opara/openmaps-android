package com.openmaps.app.core.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Phase 0 location layer built on the platform [LocationManager] (no Google Play services,
 * no proprietary SDKs). While the flow is collected it:
 *  - reports whether device location services are on, and tracks changes;
 *  - emits a recent last-known fix immediately (if any) for a fast first paint;
 *  - streams live fixes from GPS (precise permission) and/or network (any permission).
 *
 * Providers are re-registered whenever the enabled-provider set changes.
 * NOT verified on a device from the remote build environment.
 */
class AndroidLocationRepository(context: Context) : LocationRepository {

    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    private fun servicesEnabled(): Boolean = LocationManagerCompat.isLocationEnabled(locationManager)

    private fun hasPermission(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission") // Guarded by hasPermission() below.
    override fun updates(): Flow<LocationUpdate> = callbackFlow {
        fun reportServices() {
            trySend(if (servicesEnabled()) LocationUpdate.ServicesEnabled else LocationUpdate.ServicesDisabled)
        }

        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                trySend(LocationUpdate.Fix(location.toUserLocation()))
            }

            // Overridden explicitly: on API < 30 these are abstract and a missing
            // implementation crashes with AbstractMethodError.
            override fun onProviderEnabled(provider: String) = Unit
            override fun onProviderDisabled(provider: String) = Unit

            @Deprecated("Deprecated in Java")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }

        fun register() {
            locationManager.removeUpdates(listener)
            if (!servicesEnabled()) return
            val fine = hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)
            val coarse = hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (!fine && !coarse) {
                trySend(LocationUpdate.Failure(SecurityException("Location permission not granted")))
                return
            }
            val providers = buildList {
                if (fine) add(LocationManager.GPS_PROVIDER)
                add(LocationManager.NETWORK_PROVIDER)
            }
            var registeredAny = false
            for (provider in providers) {
                try {
                    if (!locationManager.isProviderEnabled(provider)) continue
                    locationManager.requestLocationUpdates(
                        provider, UPDATE_INTERVAL_MILLIS, MIN_DISTANCE_METERS, listener, Looper.getMainLooper(),
                    )
                    registeredAny = true
                    bestRecent(provider)?.let { trySend(LocationUpdate.Fix(it.toUserLocation())) }
                } catch (e: SecurityException) {
                    trySend(LocationUpdate.Failure(e))
                } catch (e: IllegalArgumentException) {
                    // Provider not present on this device: skip it.
                }
            }
            if (!registeredAny) trySend(LocationUpdate.Failure())
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                reportServices()
                register()
            }
        }
        ContextCompat.registerReceiver(
            appContext,
            receiver,
            IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION).apply { addAction(MODE_CHANGED_ACTION) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        reportServices()
        register()

        awaitClose {
            runCatching { appContext.unregisterReceiver(receiver) }
            runCatching { locationManager.removeUpdates(listener) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun bestRecent(provider: String): Location? {
        val last: Location? = try {
            locationManager.getLastKnownLocation(provider)
        } catch (e: SecurityException) {
            null
        }
        if (last == null) return null
        val ageMillis = (SystemClock.elapsedRealtimeNanos() - last.elapsedRealtimeNanos) / 1_000_000L
        return last.takeIf { ageMillis in 0..MAX_LAST_KNOWN_AGE_MILLIS }
    }

    private fun Location.toUserLocation() = UserLocation(
        latitude = latitude,
        longitude = longitude,
        accuracyMeters = if (hasAccuracy()) accuracy else null,
        bearingDegrees = if (hasBearing()) bearing else null,
        timestampMillis = time,
    )

    private companion object {
        const val UPDATE_INTERVAL_MILLIS = 1_000L
        const val MIN_DISTANCE_METERS = 1f
        const val MAX_LAST_KNOWN_AGE_MILLIS = 120_000L

        // LocationManager.MODE_CHANGED_ACTION is public only from API 28; minSdk is 26.
        const val MODE_CHANGED_ACTION = "android.location.MODE_CHANGED"
    }
}
