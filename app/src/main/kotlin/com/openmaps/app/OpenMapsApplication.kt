package com.openmaps.app

import android.app.Application
import com.openmaps.app.core.geocoding.GeocodingRepository
import com.openmaps.app.core.geocoding.NominatimGeocodingRepository
import com.openmaps.app.core.geocoding.RecentSearchStore
import com.openmaps.app.core.geocoding.SharedPreferencesRecentSearchStore
import com.openmaps.app.core.camera.CameraPermissionPrefs
import com.openmaps.app.core.location.AndroidLocationRepository
import com.openmaps.app.core.location.LocationPermissionPrefs
import com.openmaps.app.core.location.LocationRepository
import com.openmaps.app.core.routing.RoutingRepository
import com.openmaps.app.core.routing.ValhallaRoutingRepository
import com.openmaps.app.map.BuildConfigMapStyleProvider
import com.openmaps.app.map.MapStyleProvider

/** Minimal manual dependency container (no DI framework needed at this size). */
class AppContainer(application: Application) {
    val locationRepository: LocationRepository = AndroidLocationRepository(application)
    val locationPermissionPrefs: LocationPermissionPrefs = LocationPermissionPrefs(application)
    val cameraPermissionPrefs: CameraPermissionPrefs = CameraPermissionPrefs(application)
    val mapStyleProvider: MapStyleProvider = BuildConfigMapStyleProvider()
    val geocodingRepository: GeocodingRepository = NominatimGeocodingRepository(
        userAgent = "${application.packageName}/${BuildConfig.VERSION_NAME} (OpenMaps Android; development)",
    )
    val recentSearchStore: RecentSearchStore = SharedPreferencesRecentSearchStore(application)
    val routingRepository: RoutingRepository = ValhallaRoutingRepository(
        userAgent = "${application.packageName}/${BuildConfig.VERSION_NAME} (OpenMaps Android; development)",
    )
}

class OpenMapsApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
