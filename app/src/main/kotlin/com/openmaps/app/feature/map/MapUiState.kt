package com.openmaps.app.feature.map

import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.LocationPermissionState
import com.openmaps.app.core.location.LocationStatus
import com.openmaps.app.core.location.UserLocation
import com.openmaps.app.core.navigation.ArrivalPreviewPlan
import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.core.environment.DrivingEnvironment
import com.openmaps.app.core.environment.EnvironmentNavigationProfile
import com.openmaps.app.core.environment.EnvironmentProfiles
import com.openmaps.app.core.places.Place
import com.openmaps.app.core.places.PlaceCategory
import com.openmaps.app.core.places.PlaceCategoryMapper
import com.openmaps.app.core.roadintelligence.RoadIntelligenceState
import com.openmaps.app.core.routing.RouteResult
import com.openmaps.app.core.voice.VoiceLanguage
import com.openmaps.app.map.CameraState
import com.openmaps.app.map.MapError
import com.openmaps.app.map.MapMode

/**
 * Everything the Map screen renders. Deliberately free of MapLibre types.
 *
 * High-frequency camera data (the live bearing during a rotate gesture) is NOT in here;
 * it is exposed separately as `MapViewModel.compassBearing` so a rotating map does not
 * recompose the whole screen every frame.
 */
data class MapUiState(
    val currentLocation: UserLocation? = null,
    val locationPermission: LocationPermissionState = LocationPermissionState.Unknown,
    val locationStatus: LocationStatus = LocationStatus.Idle,
    /** Last settled camera (updated when the camera goes idle). Used to restore the view. */
    val cameraState: CameraState = CameraState.UnitedStates,
    val selectedMapMode: MapMode = MapMode.Map,
    /** Navigation presentation chosen on Route Preview. Map is the safe/default first-run choice. */
    val selectedNavigationMode: NavigationMode = NavigationMode.Map,
    /** Live requires a deliberate safety acknowledgement before the camera driving experience starts. */
    val isLiveSafetyWarningVisible: Boolean = false,
    val isMapReady: Boolean = false,
    val error: MapError? = null,
    /** The prompt the user dismissed, if any. Re-shows automatically if the situation changes. */
    val dismissedPrompt: LocationPrompt? = null,
    /** The destination marker shown on the map, or null when none is selected. */
    val selectedDestination: SearchResult? = null,
    /**
     * Optional route-preview origin chosen by the user. Null means "Your location".
     * This is intentionally a SearchResult so the same search flow can edit either endpoint.
     */
    val routeOriginOverride: SearchResult? = null,
    /** The route to [selectedDestination], as one lifecycle value (see [RouteState]). */
    val routeState: RouteState = RouteState.Idle,
    /** Turn-by-turn navigation over [routeState]'s route (see [NavigationState]). */
    val navigationState: NavigationState = NavigationState.Idle,
    /**
     * True while the user has panned the camera away during a trip, so the banner can offer
     * recenter. Derived from engine events rather than stored separately, so it can never drift
     * out of sync with what actually happened.
     */
    val navigationFollowSuspended: Boolean = false,
    /** Lifecycle of the current contextual-places fetch (Phase 9). See [PlacesState]. */
    val placesState: PlacesState = PlacesState.Idle,
    /** Which categories the user wants surfaced. All supported categories are shown by default. */
    val placeCategoryFilters: Set<PlaceCategory> = PlaceCategoryMapper.supportedCategories,
    /** The place shown in the compact details sheet, or null when none is selected. */
    val selectedPlace: Place? = null,
    /**
     * Ranked, capped places to actually render (Map markers; Live derives its own further-capped
     * subset from this same list — see [com.openmaps.app.core.places.LivePlaceProjector]).
     * Recomputed explicitly by `MapViewModel.recomputeVisiblePlaces` whenever [placesState],
     * [placeCategoryFilters] or navigation state changes — not a per-access derived property,
     * because ranking walks the route geometry and must not re-run on every recomposition.
     */
    val visiblePlaces: List<Place> = emptyList(),
    /** Hazards, weather, speed limit, lane guidance and prioritized alerts (Phase 10). */
    val roadIntelligenceState: RoadIntelligenceState = RoadIntelligenceState(),
    /**
     * The debounced environment mode (Phase 11), recomputed on each navigation tick and held
     * through [com.openmaps.app.core.environment.EnvironmentHysteresis] so it never flickers.
     * [DrivingEnvironment.Unknown] (and therefore [activeProfile] == [EnvironmentProfiles.unknown],
     * the pre-Phase-11 baseline) whenever not navigating or no signal has resolved yet.
     */
    val drivingEnvironment: DrivingEnvironment = DrivingEnvironment.Unknown,
    /**
     * Set once by [com.openmaps.app.feature.map.MapViewModel.onLiveReadinessChanged] when `Start`'s
     * automatic switch to Live turned out to be unusable (camera denied/unavailable) and navigation
     * was automatically presented in Map instead (section A7). Null once dismissed or once Live is
     * next entered successfully.
     */
    val liveFallbackMessage: String? = null,
    /**
     * Phase 12A, section D: the user tapped "Directions" again from Route Preview to review the
     * destination without discarding the already-computed route. Shows [DestinationDetailsSheet]
     * over an unchanged [routeState]/[selectedDestination] — distinct from
     * [isShowingDestinationDetails], which only applies before a route has ever been requested.
     */
    val isReviewingDirections: Boolean = false,
    /** Phase 12A, section B5: the "Explore what's around you" bottom sheet is open. */
    val isExploreOpen: Boolean = false,
    /** Mockup Route Preview: the "Steps" maneuver list is open over the route preview. */
    val isShowingSteps: Boolean = false,
    /**
     * The route whose steps [isShowingSteps] is showing. Held alongside the flag rather than
     * re-read from [routeState] so the list cannot silently change under the user if a reroute
     * lands while it is open.
     */
    val stepsRouteSummary: RouteResult? = null,
    /** Delta-spec section 25: the compact mute control in Live reads/writes this. */
    val voiceEnabled: Boolean = true,
    /** Delta-spec section 24: the selected navigation voice language, set from Settings. */
    val voiceLanguage: VoiceLanguage = VoiceLanguage.EnglishUs,
    /** Community report submission state; unavailable until a real moderated provider is wired. */
    val driverReportStatus: ExternalActionStatus = ExternalActionStatus.Idle,
    /** Temporary live-trip sharing state; unavailable until an expiring-link provider is wired. */
    val tripShareStatus: ExternalActionStatus = ExternalActionStatus.Idle,
) {
    /** The centralized Phase 11 tuning for [drivingEnvironment] — see `EnvironmentNavigationProfile`. */
    val activeProfile: EnvironmentNavigationProfile
        get() = EnvironmentProfiles.forEnvironment(drivingEnvironment)

    /** Final ~500 m route slice for a future real Arrival Preview renderer. Hidden until supported. */
    val arrivalPreviewPlan: ArrivalPreviewPlan?
        get() = (routeState as? RouteState.Ready)?.route?.let(ArrivalPreviewPlan::from)

    /** The progress snapshot to render, or null when navigation isn't reporting one. */
    val navigationProgress: RouteProgress?
        get() = when (val state = navigationState) {
            is NavigationState.Active -> state.progress
            is NavigationState.OffRoute -> state.progress
            is NavigationState.Arrived -> state.progress
            NavigationState.Idle,
            NavigationState.Ready,
            NavigationState.Rerouting,
            is NavigationState.Error,
            -> null
        }

    /**
     * Part A2/A3/O: a destination was picked but "Directions" has not been tapped yet, so no route
     * has been requested. Derived from the two fields that already exist rather than a new
     * tri-state flag — once [routeState] leaves [RouteState.Idle] (Directions tapped), this flips to
     * false and the Route Preview panel ([BottomPanel] + [NavigationControls]) takes over instead.
     */
    val isShowingDestinationDetails: Boolean
        get() = selectedDestination != null && routeState is RouteState.Idle && selectedPlace == null

    /**
     * Either the pre-route details step or a post-route "Directions" review ([isReviewingDirections])
     * — both render [DestinationDetailsSheet], just with a different dismiss action (see `MapScreen`).
     */
    val isShowingAnyDestinationDetails: Boolean
        get() = isShowingDestinationDetails || (isReviewingDirections && selectedDestination != null && selectedPlace == null)

    /** True while a trip is underway: following, off route, rerouting, or arrived. */
    val isNavigating: Boolean
        get() = when (navigationState) {
            NavigationState.Idle, NavigationState.Ready, is NavigationState.Error -> false
            is NavigationState.Active, is NavigationState.OffRoute, NavigationState.Rerouting, is NavigationState.Arrived -> true
        }

    /** "Start" is offered once a route and a location fix both exist, and not mid-trip. */
    val canStartNavigation: Boolean
        get() = routeState is RouteState.Ready &&
            currentLocation != null &&
            routeOriginOverride == null &&
            navigationState is NavigationState.Idle

    val isLocationLoading: Boolean
        get() = locationStatus == LocationStatus.Loading

    /** The location card to show, or null when nothing needs the user's attention. */
    val locationPrompt: LocationPrompt?
        get() {
            val prompt = when (locationPermission) {
                LocationPermissionState.Unknown -> null
                LocationPermissionState.NotRequested -> LocationPrompt.AllowLocation
                LocationPermissionState.Denied -> LocationPrompt.Denied
                LocationPermissionState.PermanentlyDenied -> LocationPrompt.Blocked
                is LocationPermissionState.Granted -> when (locationStatus) {
                    LocationStatus.Loading -> LocationPrompt.Loading
                    LocationStatus.ServicesDisabled -> LocationPrompt.ServicesOff
                    LocationStatus.Unavailable -> LocationPrompt.Unavailable
                    LocationStatus.Idle, LocationStatus.Available -> null
                }
            }
            return if (prompt != null && prompt == dismissedPrompt && prompt != LocationPrompt.Loading) null else prompt
        }
}

/** One user-facing location situation. The UI maps each to copy and a primary action. */
/** The two first-class turn-by-turn experiences OpenMaps offers. */
enum class NavigationMode {
    Map,
    Live,
}

enum class ExternalActionStatus {
    Idle,
    Working,
    Success,
    Unavailable,
}

enum class LocationPrompt {
    Loading,
    AllowLocation,
    Denied,
    Blocked,
    ServicesOff,
    Unavailable,
}

/** One-shot effects the UI must perform (they need an Activity/Context). */
sealed interface MapUiEvent {
    data object RequestLocationPermission : MapUiEvent
    data object OpenAppSettings : MapUiEvent
    data object OpenLocationSettings : MapUiEvent
    data class ShareTripUrl(val url: String) : MapUiEvent

    /**
     * A reroute attempted during navigation failed. The old route is still active and the user is
     * free to keep driving or correct course; this is a transient "we couldn't fix the route
     * right now" notice, not a dead end.
     */
    data object RerouteFailed : MapUiEvent
}
