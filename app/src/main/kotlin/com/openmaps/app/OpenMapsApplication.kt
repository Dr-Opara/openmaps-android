package com.openmaps.app

import android.app.Application
import com.openmaps.app.core.geocoding.BackendGeocodingRepository
import com.openmaps.app.core.geocoding.BackendReverseGeocodingRepository
import com.openmaps.app.core.geocoding.CompositeGeocodingRepository
import com.openmaps.app.core.geocoding.GeocodingRepository
import com.openmaps.app.core.geocoding.NominatimGeocodingRepository
import com.openmaps.app.core.geocoding.RecentSearchStore
import com.openmaps.app.core.geocoding.ReverseGeocodingRepository
import com.openmaps.app.core.geocoding.SharedPreferencesRecentSearchStore
import com.openmaps.app.core.camera.CameraPermissionPrefs
import com.openmaps.app.core.environment.OverpassRoadClassRepository
import com.openmaps.app.core.environment.RoadClassRepository
import com.openmaps.app.core.location.AndroidLocationRepository
import com.openmaps.app.core.location.LocationPermissionPrefs
import com.openmaps.app.core.location.LocationRepository
import com.openmaps.app.core.places.CompositePlaceRepository
import com.openmaps.app.core.places.OverpassPlaceRepository
import com.openmaps.app.core.places.PlaceRepository
import com.openmaps.app.core.reporting.DriverReportRepository
import com.openmaps.app.core.reporting.UnavailableDriverReportRepository
import com.openmaps.app.core.roadintelligence.DefaultRoadIntelligenceRepository
import com.openmaps.app.core.roadintelligence.RoadIntelligenceRepository
import com.openmaps.app.core.routing.BackendRoutingRepository
import com.openmaps.app.core.routing.CompositeRoutingRepository
import com.openmaps.app.core.routing.RoutingRepository
import com.openmaps.app.core.sharing.TripShareRepository
import com.openmaps.app.core.sharing.UnavailableTripShareRepository
import com.openmaps.app.core.routing.ValhallaRoutingRepository
import com.openmaps.app.core.spatial.SpatialLocalizationEngine
import com.openmaps.app.core.spatial.arcore.ArCoreSpatialLocalization
import com.openmaps.app.core.voice.VoiceGuidanceEngine
import com.openmaps.app.core.voice.VoiceGuidancePrefs
import com.openmaps.app.map.BuildConfigMapStyleProvider
import com.openmaps.app.map.MapStyleProvider
import com.openmaps.app.voice.AndroidTextToSpeechEngine

/** Minimal manual dependency container (no DI framework needed at this size). */
class AppContainer(application: Application) {
    val locationRepository: LocationRepository = AndroidLocationRepository(application)
    val locationPermissionPrefs: LocationPermissionPrefs = LocationPermissionPrefs(application)
    val cameraPermissionPrefs: CameraPermissionPrefs = CameraPermissionPrefs(application)
    val mapStyleProvider: MapStyleProvider = BuildConfigMapStyleProvider()
    /**
     * Production geocoding path:
     *   OpenMaps backend gateway -> direct worldwide Nominatim fallback.
     *
     * The backend is the primary seam for contracted/SLA providers. Direct public Nominatim is
     * deliberately only a last-resort fallback so the app can still resolve a destination during a
     * gateway/provider outage without pretending it has production SLA guarantees.
     */
    val geocodingRepository: GeocodingRepository = CompositeGeocodingRepository(
        providers = listOf(
            BackendGeocodingRepository(
                endpointUrl = BuildConfig.GEOCODING_GATEWAY_URL,
                publishableKey = BuildConfig.GEOCODING_PUBLISHABLE_KEY,
            ),
            NominatimGeocodingRepository(
                userAgent = "${application.packageName}/${BuildConfig.VERSION_NAME} (OpenMaps Android; fallback)",
            ),
        ),
    )
    val reverseGeocodingRepository: ReverseGeocodingRepository = BackendReverseGeocodingRepository(
        endpointUrl = BuildConfig.REVERSE_GEOCODING_GATEWAY_URL,
        publishableKey = BuildConfig.GEOCODING_PUBLISHABLE_KEY,
    )
    val recentSearchStore: RecentSearchStore = SharedPreferencesRecentSearchStore(application)

    /** Community reporting is intentionally unavailable until a moderated provider is connected. */
    val driverReportRepository: DriverReportRepository = UnavailableDriverReportRepository()

    /** Live trip sharing is intentionally unavailable until an expiring-token backend is connected. */
    val tripShareRepository: TripShareRepository = UnavailableTripShareRepository()

    /**
     * Production routing path:
     *   OpenMaps traffic-aware routing gateway -> Valhalla fallback.
     *
     * HERE traffic-aware routing is activated server-side when HERE_API_KEY is configured. Until
     * then, the backend fails closed and this composite transparently falls back to Valhalla.
     */
    val routingRepository: RoutingRepository = CompositeRoutingRepository(
        providers = listOf(
            BackendRoutingRepository(
                endpointUrl = BuildConfig.ROUTING_GATEWAY_URL,
                publishableKey = BuildConfig.GEOCODING_PUBLISHABLE_KEY,
            ),
            ValhallaRoutingRepository(
                userAgent = "${application.packageName}/${BuildConfig.VERSION_NAME} (OpenMaps Android; routing fallback)",
            ),
        ),
    )

    /**
     * Phase 9 contextual places (restaurants, parking, gas, EV charging, ...).
     *
     * Phase 12B wraps the Overpass provider in [CompositePlaceRepository] so the chain is
     * **cache → provider → stale cache → explicit failure**. That is what makes Nearby Places usable
     * against a provider that is measurably flaky: whichever mirror is currently healthy answers
     * first, and a total outage degrades to cached data instead of an error screen.
     *
     * **Production seam:** when a contracted/self-hosted places provider exists, add it to [providers]
     * ahead of Overpass — nothing else changes. The UI never learns which provider answered.
     */
    val placesRepository: PlaceRepository = CompositePlaceRepository(
        providers = listOf(
            OverpassPlaceRepository(
                userAgent = "${application.packageName}/${BuildConfig.VERSION_NAME} (OpenMaps Android; development)",
            ),
        ),
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

    /** Delta-spec section 24: persisted language/mute preference, no account required. */
    val voiceGuidancePrefs: VoiceGuidancePrefs = VoiceGuidancePrefs(application)

    /**
     * Delta-spec section 14. Constructed eagerly (TTS initialization is itself async and harmless
     * to kick off at app start — unlike ARCore it never touches the camera or needs an Activity),
     * seeded from the persisted language/mute preference.
     */
    val voiceGuidanceEngine: VoiceGuidanceEngine = AndroidTextToSpeechEngine(application).apply {
        setLanguage(voiceGuidancePrefs.language())
        setEnabled(voiceGuidancePrefs.isEnabled())
    }
}

class OpenMapsApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
