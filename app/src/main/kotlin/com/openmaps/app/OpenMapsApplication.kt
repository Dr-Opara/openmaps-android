package com.openmaps.app

import android.app.Application
import com.openmaps.app.core.location.AndroidLocationRepository
import com.openmaps.app.core.location.LocationPermissionPrefs
import com.openmaps.app.core.location.LocationRepository
import com.openmaps.app.map.BuildConfigMapStyleProvider
import com.openmaps.app.map.MapStyleProvider

/** Minimal manual dependency container (no DI framework needed at this size). */
class AppContainer(application: Application) {
    val locationRepository: LocationRepository = AndroidLocationRepository(application)
    val locationPermissionPrefs: LocationPermissionPrefs = LocationPermissionPrefs(application)
    val mapStyleProvider: MapStyleProvider = BuildConfigMapStyleProvider()
}

class OpenMapsApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
