package com.openmaps.app

import android.app.Application
import com.openmaps.app.core.geocoding.GeocodingRepository
import com.openmaps.app.core.geocoding.NominatimGeocodingRepository
import com.openmaps.app.core.geocoding.RecentSearchStore
import com.openmaps.app.core.geocoding.SharedPreferencesRecentSearchStore
import com.openmaps.app.core.camera.CameraPermissionPrefs
import com.openmaps.app.core.environment.OverpassRoadClassRepository
import com.openmaps.app.core.environment.RoadClassRepository
import com.openmaps.app.core.location.AndroidLocationRepository
import com.openmaps.app.core.location.LocationPermissionPrefs
import com.openmaps.app.core.location.LocationRepository
import com.openmaps.app.core.places.OverpassPlaceRepository
import com.openmaps.app.core.places.PlaceRepository
import com.openmaps.app.core.roadintelligence.DefaultRoadIntelligenceRepository
import com.openmaps.app.core.roadintelligence.RoadIntelligenceRepository
import com.openmaps.app.core.routing.RoutingRepository
import com.openmaps.app.core.routing.ValhallaRoutingRepository
import com.openmaps.app.core.spatial.SpatialLocalizationEngine
import com.openmaps.app.core.spatial.arcore.ArCoreSpatialLocalization
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

    /** Phase 9 contextual places (restaurants, parking, gas, EV charging, ...). See ARCHITECTURE.md. */
    val placesRepository: PlaceRepository = OverpassPlaceRepository(
        userAgent = "${application.packageName}/${BuildConfig.VERSION_NAME} (OpenMaps Android; development)",
    )

    /**
     * Phase 10 road intelligence (hazards, weather, speed limits — see ARCHITECTURE.md's Road
     * intelligence section for exactly which of those are real today versus absent-by-design).
     */
    val roadIntelligenceRepository: RoadIntelligenceRepository = DefaultRoadIntelligenceRepository(
        userAgent = "${application.packageName}/${BuildConfig.VERSION_NAME} (OpenMaps Android; development)",
    )

    /**
     * Phase 11 environment classification's one real network signal (see ARCHITECTURE.md's Adaptive
     * environment modes section). A separate Overpass client from [roadIntelligenceRepository] by
     * design — Phase 10's seam is not touched by Phase 11.
     */
    val roadClassRepository: RoadClassRepository = OverpassRoadClassRepository(
        userAgent = "${application.packageName}/${BuildConfig.VERSION_NAME} (OpenMaps Android; development)",
    )

    /**
     * Phase 6 spatial localization (ARCore). **Constructed eagerly but not started** — support is
     * checked inside the engine on `start`, because `ArCoreApk.getInstance().requestInstall` needs an
     * Activity and must only run when the user actually opens Live. Constructing a Session is
     * deliberately deferred to `start()` so nothing touches the camera at app launch.
     */
    val spatialLocalizationEngine: SpatialLocalizationEngine = ArCoreSpatialLocalization(application)
}

class OpenMapsApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
