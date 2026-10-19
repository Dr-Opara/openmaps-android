package com.openmaps.app.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmaps.app.core.environment.DrivingEnvironment
import com.openmaps.app.core.environment.EnvironmentClassifier
import com.openmaps.app.core.environment.EnvironmentHysteresis
import com.openmaps.app.core.environment.EnvironmentSignals
import com.openmaps.app.core.environment.ManeuverSpacing
import com.openmaps.app.core.environment.RoadClass
import com.openmaps.app.core.environment.RoadClassRepository
import com.openmaps.app.core.geocoding.ReverseGeocodingRepository
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.LocationPermissionState
import com.openmaps.app.core.location.LocationRepository
import com.openmaps.app.core.location.LocationStatus
import com.openmaps.app.core.location.LocationUpdate
import com.openmaps.app.core.location.UserLocation
import com.openmaps.app.core.location.isGranted
import com.openmaps.app.core.localization.LocalizationPrefs
import com.openmaps.app.core.localization.MeasurementSystem
import com.openmaps.app.core.navigation.NavigationSession
import com.openmaps.app.core.navigation.OffRouteDecision
import com.openmaps.app.core.navigation.NavigationForegroundController
import com.openmaps.app.core.navigation.NavigationRecoveryStore
import com.openmaps.app.core.navigation.NoopNavigationRecoveryStore
import com.openmaps.app.core.navigation.NoopNavigationForegroundController
import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.core.reliability.NoopReliabilityReporter
import com.openmaps.app.core.reliability.ReliabilityEvent
import com.openmaps.app.core.reliability.ReliabilityEventName
import com.openmaps.app.core.reliability.ReliabilityOutcome
import com.openmaps.app.core.reliability.ReliabilityReporter
import com.openmaps.app.core.places.Place
import com.openmaps.app.core.places.PlaceCategory
import com.openmaps.app.core.places.PlaceCategoryMapper
import com.openmaps.app.core.places.PlaceQueryResult
import com.openmaps.app.core.places.PlaceSource
import com.openmaps.app.core.places.PlaceException
import com.openmaps.app.core.places.PlaceRanking
import com.openmaps.app.core.places.PlaceRepository
import com.openmaps.app.core.places.RouteCorridor
import com.openmaps.app.core.reporting.DriverReport
import com.openmaps.app.core.reporting.DriverReportRepository
import com.openmaps.app.core.reporting.DriverReportType
import com.openmaps.app.core.reporting.UnavailableDriverReportRepository
import com.openmaps.app.core.roadintelligence.RoadAlertActivation
import com.openmaps.app.core.roadintelligence.RoadAlertHysteresis
import com.openmaps.app.core.roadintelligence.RoadAlertPriority
import com.openmaps.app.core.roadintelligence.RoadEventSeverity
import com.openmaps.app.core.roadintelligence.RoadEventType
import com.openmaps.app.core.roadintelligence.LaneGuidanceRepository
import com.openmaps.app.core.roadintelligence.RoadIntelligenceRepository
import com.openmaps.app.core.roadintelligence.RouteAlert
import com.openmaps.app.core.roadintelligence.SpeedLimitInfo
import com.openmaps.app.core.roadintelligence.SpeedUnit
import com.openmaps.app.core.roadintelligence.TrafficCondition
import com.openmaps.app.core.roadintelligence.TrafficCongestionLevel
import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.RouteResult
import com.openmaps.app.core.routing.RoutingException
import com.openmaps.app.core.routing.RoutingRepository
import com.openmaps.app.core.sharing.TripShareRepository
import com.openmaps.app.core.sharing.TripShareRecoveryStore
import com.openmaps.app.core.sharing.NoopTripShareRecoveryStore
import com.openmaps.app.core.sharing.TripShareRequest
import com.openmaps.app.core.sharing.TripShareSession
import com.openmaps.app.core.sharing.TripShareUpdate
import com.openmaps.app.core.sharing.UnavailableTripShareRepository
import com.openmaps.app.core.voice.NoopVoiceGuidanceEngine
import com.openmaps.app.core.voice.VoiceAnnouncementPlanner
import com.openmaps.app.core.voice.VoiceEvent
import com.openmaps.app.core.voice.VoiceGuidanceEngine
import com.openmaps.app.core.voice.VoiceGuidancePrefs
import com.openmaps.app.core.voice.VoiceLanguage
import com.openmaps.app.feature.live.LiveReadiness
import com.openmaps.app.map.CameraState
import com.openmaps.app.map.MapCommand
import com.openmaps.app.map.MapError
import com.openmaps.app.map.MapEvent
import com.openmaps.app.map.MapMode
import com.openmaps.app.map.RouteProgressView
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * Owns Map-screen state and all camera/location policy. Contains no Android framework
 * and no MapLibre types: the engine is driven through [commands] and reports back through
 * [onMapEvent].
 *
 * Camera policy (Phase 1):
 *  - The first location fix centers the map once, unless the user already moved it.
 *  - Later fixes only move the blue dot, never the camera.
 *  - [onRecenterClicked] is the only way the camera returns to the user afterwards.
 */
class MapViewModel(
    private val locationRepository: LocationRepository,
    private val routingRepository: RoutingRepository,
    private val placesRepository: PlaceRepository,
    private val roadIntelligenceRepository: RoadIntelligenceRepository,
    private val roadClassRepository: RoadClassRepository,
    private val laneGuidanceRepository: LaneGuidanceRepository? = null,
    private val reverseGeocodingRepository: ReverseGeocodingRepository? = null,
    private val driverReportRepository: DriverReportRepository = UnavailableDriverReportRepository(),
    private val tripShareRepository: TripShareRepository = UnavailableTripShareRepository(),
    private val tripShareRecoveryStore: TripShareRecoveryStore = NoopTripShareRecoveryStore,
    /** Delta-spec section 14. Defaults to a no-op so every existing test keeps working with no
     * voice at all; the real TTS engine is supplied by [AppContainer]. */
    private val voiceGuidanceEngine: VoiceGuidanceEngine = NoopVoiceGuidanceEngine(),
    /** Null in tests (no Android `Context` there); the real instance is supplied by [AppContainer]. */
    private val voiceGuidancePrefs: VoiceGuidancePrefs? = null,
    /** Phase 7 locale/unit preferences; null in pure ViewModel tests. */
    private val localizationPrefs: LocalizationPrefs? = null,
    private val reliabilityReporter: ReliabilityReporter = NoopReliabilityReporter,
    private val navigationForegroundController: NavigationForegroundController = NoopNavigationForegroundController,
    private val navigationRecoveryStore: NavigationRecoveryStore = NoopNavigationRecoveryStore,
    private val fixTimeoutMillis: Long = DEFAULT_FIX_TIMEOUT_MILLIS,
) : ViewModel() {

    private val recoveredNavigation = navigationRecoveryStore.load()

    private val _uiState = MutableStateFlow(
        MapUiState(
            voiceEnabled = voiceGuidancePrefs?.isEnabled() ?: true,
            voiceLanguage = voiceGuidancePrefs?.language() ?: VoiceLanguage.EnglishUs,
            measurementSystem = localizationPrefs?.measurementSystem() ?: MeasurementSystem.Auto,
            selectedDestination = recoveredNavigation?.destination,
            routeState = recoveredNavigation?.route?.let(RouteState::Ready) ?: RouteState.Idle,
            navigationState = if (recoveredNavigation != null) NavigationState.Ready else NavigationState.Idle,
            selectedMapMode = MapMode.Map,
            selectedNavigationMode = NavigationMode.Map,
        ),
    )
    val uiState: StateFlow<MapUiState> = _uiState.asStateFlow()

    /** Live bearing in degrees [-180, 180); separate from [uiState] to avoid per-frame recomposition. */
    private val _compassBearing = MutableStateFlow(0f)
    val compassBearing: StateFlow<Float> = _compassBearing.asStateFlow()

    private val commandChannel = Channel<MapCommand>(Channel.BUFFERED)
    val commands: Flow<MapCommand> = commandChannel.receiveAsFlow()

    private val eventChannel = Channel<MapUiEvent>(Channel.BUFFERED)
    val events: Flow<MapUiEvent> = eventChannel.receiveAsFlow()

    /**
     * The traveled/remaining split for the renderer. A StateFlow rather than a second collector on
     * [commands]: that is a Channel, so a second collector would take messages the engine needs
     * instead of receiving them.
     */
    private val _routeProgressView = MutableStateFlow<RouteProgressView?>(null)
    val routeProgressView: StateFlow<RouteProgressView?> = _routeProgressView.asStateFlow()

    private var locationJob: Job? = null
    private var timeoutJob: Job? = null
    private var routeJob: Job? = null
    private var reverseGeocodingJob: Job? = null
    private var lastReverseGeocodedLocation: UserLocation? = null
    private var isAppVisible = false
    private var autoCenterHandled = false
    private var userHasMovedCamera = false
    private var autoPromptEmitted = false
    private var routeRequestStartedAtMillis: Long? = null
    private var recoveryPending = recoveredNavigation != null

    private fun recordReliability(
        name: ReliabilityEventName,
        outcome: ReliabilityOutcome = ReliabilityOutcome.Info,
        reason: String? = null,
        durationMillis: Long? = null,
        metadata: Map<String, String> = emptyMap(),
    ) {
        reliabilityReporter.record(
            ReliabilityEvent(
                name = name,
                outcome = outcome,
                reason = reason,
                durationMillis = durationMillis,
                metadata = metadata,
            ),
        )
    }

    /**
     * Invoked after a reroute replaces the route. Phase 7 anchors and Phase 8 arrows are both
     * derived from the route geometry, so they must be dropped when the geometry changes. Kept as a
     * plain callback so the Map feature stays unaware of AR.
     */
    var onRouteReplaced: () -> Unit = {}

    /** Invoked once the destination is reached, so derived AR content (anchors, arrows) can clear. */
    var onNavigationEnded: () -> Unit = {}

    // region Contextual places (Phase 9)

    private var placesJob: Job? = null

    /** Where the last *nearby* (not along-route) fetch was centered, for the move-threshold check. */
    private var lastPlacesFetchLocation: UserLocation? = null

    // endregion

    // region Road intelligence (Phase 10)

    private var hazardsJob: Job? = null
    private var weatherJob: Job? = null
    private var speedLimitJob: Job? = null
    private var laneGuidanceJob: Job? = null
    private var lastLaneGuidanceKey: String? = null
    private var lastRoadEventsRefreshMillis: Long = 0L

    /** Where the last weather/speed-limit fetch was centered, for each's own move-threshold check. */
    private var lastWeatherFetchLocation: UserLocation? = null
    private var lastSpeedLimitFetchLocation: UserLocation? = null

    /**
     * Debounces which [RouteAlert]s are actually active across ticks (section 15 of the Phase 10
     * brief). Per-trip state, like [session] — reset on reroute and on navigation stop so a new
     * trip never inherits a stale confirm/exit streak from the one before it.
     */
    private val roadAlertHysteresis = RoadAlertHysteresis()

    // endregion

    // region Environment (Phase 11)

    private var roadClassJob: Job? = null
    private var roadClass: RoadClass? = null

    /** Where the last road-class fetch was centered, for its own move-threshold check. */
    private var lastRoadClassFetchLocation: UserLocation? = null

    /**
     * Debounces [DrivingEnvironment] across ticks (section C of the Phase 11 brief). Per-trip, like
     * [roadAlertHysteresis] — reset on a fresh `startNavigation()` so a new trip never inherits the
     * previous one's confirmed mode.
     */
    private val environmentHysteresis = EnvironmentHysteresis()

    // endregion

    // region Voice guidance (delta-spec sections 14-26)

    /**
     * Decides *when* a maneuver/approach announcement should fire (section 16's dedup rules).
     * Per-trip, like [environmentHysteresis] — reset on a fresh `startNavigation()` and on a
     * successful reroute, since both produce a new set of maneuvers nothing has been announced for
     * yet.
     */
    private val voicePlanner = VoiceAnnouncementPlanner()

    /** Delta-spec section 25: the compact mute control in Live. Persisted via [voiceGuidancePrefs]. */
    fun onVoiceMuteToggled() {
        val enabled = !_uiState.value.voiceEnabled
        voiceGuidanceEngine.setEnabled(enabled)
        voiceGuidancePrefs?.setEnabled(enabled)
        _uiState.update { it.copy(voiceEnabled = enabled) }
    }

    fun onMeasurementSystemChanged(system: MeasurementSystem) {
        localizationPrefs?.setMeasurementSystem(system)
        voiceGuidanceEngine.setMeasurementSystem(system)
        _uiState.update { it.copy(measurementSystem = system) }
    }

    /** Delta-spec section 24: Settings > Navigation > Voice Language. */
    fun onVoiceLanguageChanged(language: VoiceLanguage) {
        voiceGuidanceEngine.setLanguage(language)
        voiceGuidancePrefs?.setLanguage(language)
        _uiState.update { it.copy(voiceLanguage = language) }
    }

    // endregion

    // region Start -> Live (Phase 11)

    /**
     * True only in the window right after `startNavigation()` automatically switched
     * [MapUiState.selectedMapMode] to [MapMode.Live] (section A6/A7). [onLiveReadinessChanged] acts
     * only while this is true, so a *manual* later re-entry into Live (after the user has already
     * dismissed the automatic fallback, or switched back and forth by hand) is left to show its own
     * recoverable permission/failure panel exactly as before Phase 11 — repeatedly tapping Live with
     * a known-denied permission must not get yanked back to Map before the user can act on it.
     */
    private var liveAutoFallbackArmed = false

    // endregion

    private var activeTripShareSession: TripShareSession? = tripShareRecoveryStore.load()
    private var tripShareUpdateJob: Job? = null
    private var lastTripShareUpdateMillis: Long = 0L

    /**
     * Prevents a persistent live incident from triggering a network reroute on every GPS tick.
     * Cleared only when a genuinely new trip starts.
     */
    private val incidentRerouteIds = mutableSetOf<String>()

    // region Navigation session (Phase 4)

    /**
     * The trip in progress, or null when not navigating. Holds the geometry-dependent state
     * ([RouteProgressTracker] remembers the last matched segment) alongside the hysteresis counter
     * ([OffRouteDetector]); both are per-trip, so both are recreated whenever the route is replaced.
     */
    private var session: NavigationSession? = null

    /**
     * True while the user has taken the camera somewhere themselves. Suspends follow, exactly as a
     * user pan suspends follow in a real nav app — but navigation resumes on an explicit recenter
     * rather than never, which is the Phase 1 behaviour for a non-navigating map.
     */
    private var navigationFollowSuspended = false

    /** Guards against stacking reroutes while one is already in flight. */
    private var rerouteInFlight = false

    // endregion

    // region Inputs from the UI layer

    /** Called with the app's current view of the permission (on resume and after the dialog). */
    fun onPermissionStateChanged(state: LocationPermissionState) {
        if (_uiState.value.locationPermission == state) return
        _uiState.update {
            val next = it.copy(locationPermission = state)
            if (state.isGranted) next else next.copy(
                currentLocation = null,
                currentLocationPlace = null,
                locationStatus = LocationStatus.Idle,
            )
        }
        if (state == LocationPermissionState.NotRequested && !autoPromptEmitted) {
            autoPromptEmitted = true
            eventChannel.trySend(MapUiEvent.RequestLocationPermission)
        }
        syncLocationCollection()
    }

    /** GPS/network listeners run while visible, or while a user-started foreground trip is active. */
    fun onAppVisibilityChanged(visible: Boolean) {
        isAppVisible = visible
        syncLocationCollection()
    }

    fun onMapEvent(event: MapEvent) {
        when (event) {
            MapEvent.StyleLoaded -> _uiState.update { it.copy(isMapReady = true, error = null) }
            is MapEvent.StyleFailed -> _uiState.update {
                it.copy(isMapReady = false, error = MapError.Unavailable(event.message))
            }
            MapEvent.UserGestureStarted -> {
                userHasMovedCamera = true
                // A gesture during a trip takes the camera off follow; recenter gives it back.
                // Guarded so the flag is never set outside a trip (it would otherwise survive into
                // the next one and silently disable follow on start).
                if (_uiState.value.isNavigating && !navigationFollowSuspended) {
                    navigationFollowSuspended = true
                    _uiState.update { it.copy(navigationFollowSuspended = true) }
                }
            }
            is MapEvent.BearingChanged -> _compassBearing.value = normalizeBearing(event.bearingDegrees)
            is MapEvent.CameraIdle -> {
                _compassBearing.value = normalizeBearing(event.camera.bearing)
                _uiState.update { if (it.cameraState == event.camera) it else it.copy(cameraState = event.camera) }
            }
            is MapEvent.PlaceTapped -> onPlaceTapped(event.placeId)
        }
    }

    fun onModeSelected(mode: MapMode) {
        // Switching Map <-> Live during an active trip must not restart the route.
        liveAutoFallbackArmed = false
        _uiState.update {
            it.copy(
                selectedMapMode = mode,
                selectedNavigationMode = if (mode == MapMode.Live) NavigationMode.Live else NavigationMode.Map,
                liveFallbackMessage = null,
            )
        }
    }

    /**
     * Part A7: reactive (never timer-based) response to Live's camera readiness resolving, called
     * from the composition root (see `LiveReadinessMapper`) whenever it changes. Only acts during
     * the [liveAutoFallbackArmed] window right after `startNavigation()` auto-switched to Live:
     *  - [LiveReadiness.Pending] (permission not yet asked/resolved): keep waiting, stay armed — the
     *    normal camera-permission request flow is still in flight and must be allowed to resolve.
     *  - [LiveReadiness.Ready]: confirmed usable; disarm and do nothing else.
     *  - [LiveReadiness.Unusable]: camera permission denied/blocked, or the camera itself failed —
     *    Live is genuinely unusable, so navigation is automatically presented in Map instead (never
     *    blocked), with a concise, short-lived reason.
     *
     * Deliberately does **not** react to AR/ARCore-specific failure: an ARCore failure already falls
     * back to the CameraX preview *within* Live (Phase 6), which is a perfectly usable Live
     * experience, so it must never be treated as "Live is unusable."
     */
    fun onLiveReadinessChanged(readiness: LiveReadiness, reason: String?) {
        if (!liveAutoFallbackArmed) return
        when (readiness) {
            LiveReadiness.Pending -> Unit
            LiveReadiness.Ready -> liveAutoFallbackArmed = false
            LiveReadiness.Unusable -> {
                liveAutoFallbackArmed = false
                _uiState.update { it.copy(selectedMapMode = MapMode.Map, liveFallbackMessage = reason) }
            }
        }
    }

    fun onLiveFallbackMessageDismissed() {
        _uiState.update { it.copy(liveFallbackMessage = null) }
    }

    fun onRecenterClicked() {
        val state = _uiState.value
        val location = state.currentLocation
        if (location == null) {
            // No fix to fly to: fix whatever is blocking one (permission, GPS off, retry).
            onLocationPromptAction()
            return
        }
        // While navigating, recenter is the explicit "hand the camera back" action: clear the
        // suspended-follow flag *before* moving, so the next fix continues the trip in follow mode
        // instead of freezing the view where the user last panned it.
        if (state.isNavigating) {
            navigationFollowSuspended = false
            _uiState.update { it.copy(navigationFollowSuspended = false) }
        }

        // Prefer the navigation follow target (ahead on the route, bearing along it) so resuming
        // doesn't snap the camera back onto the user's own position. Same profile zoom/look-ahead
        // `followCameraWith` uses, so recenter resumes at the same place follow would already be.
        val profile = state.activeProfile
        val navigationTarget = state.navigationProgress
            ?.let { progress ->
                session?.camera?.targetFor(
                    progress,
                    location.bearingDegrees?.toDouble(),
                    state.cameraState.bearing,
                    zoom = profile.navigationCameraZoom,
                    lookAheadMeters = profile.navigationCameraLookAheadMeters,
                )
            }
        if (navigationTarget != null) {
            moveCamera(
                CameraState(
                    latitude = navigationTarget.latitude,
                    longitude = navigationTarget.longitude,
                    zoom = navigationTarget.zoom,
                    bearing = navigationTarget.bearingDegrees,
                    pitch = navigationTarget.pitch,
                ),
                durationMillis = 700,
            )
            return
        }

        val camera = state.cameraState
        moveCamera(
            CameraState(
                latitude = location.latitude,
                longitude = location.longitude,
                zoom = max(camera.zoom, RECENTER_MIN_ZOOM),
                bearing = camera.bearing,
                pitch = camera.pitch,
            ),
            durationMillis = 700,
        )
    }

    fun onCompassClicked() {
        moveCamera(_uiState.value.cameraState.copy(bearing = 0.0), durationMillis = 400)
    }

    fun onRetryMap() {
        _uiState.update { it.copy(error = null, isMapReady = false) }
        commandChannel.trySend(MapCommand.RetryStyle)
    }

    /** The primary button of the location card, or the recenter button when there is no fix. */
    fun onLocationPromptAction() {
        val state = _uiState.value
        when (state.locationPermission) {
            LocationPermissionState.Unknown -> Unit
            LocationPermissionState.NotRequested,
            LocationPermissionState.Denied -> eventChannel.trySend(MapUiEvent.RequestLocationPermission)
            LocationPermissionState.PermanentlyDenied -> eventChannel.trySend(MapUiEvent.OpenAppSettings)
            is LocationPermissionState.Granted -> {
                when (state.locationStatus) {
                    LocationStatus.ServicesDisabled -> eventChannel.trySend(MapUiEvent.OpenLocationSettings)
                    LocationStatus.Unavailable,
                    LocationStatus.Loading,
                    LocationStatus.Idle -> restartLocationCollection()
                    LocationStatus.Available -> Unit
                }
            }
        }
    }

    fun onDismissLocationPrompt() {
        _uiState.update { it.copy(dismissedPrompt = it.locationPrompt) }
    }

    /**
     * A search result (fresh or recent) was picked: show its marker, fly the camera, and show
     * Destination Details ([MapUiState.isShowingDestinationDetails]) — section A2 of the Phase 11
     * brief: "selecting a result should NOT immediately begin navigation" (nor even request a
     * route). [onDirectionsRequested] is the explicit next step that does.
     */
    fun onDestinationSelected(result: SearchResult) {
        // A different destination is a different trip; the old session's geometry is gone.
        if (_uiState.value.isNavigating) stopNavigation()
        // Cancel (don't just ignore) any route still in flight for a previous destination — its
        // result landing after this would show Route Preview for the new pin with the old route.
        routeJob?.cancel()
        routeJob = null
        _uiState.update {
            it.copy(
                selectedDestination = result,
                routeOriginOverride = null,
                routeState = RouteState.Idle,
                isReviewingDirections = false,
                // Picking a destination (including from a Nearby Explore card) always returns to
                // the main destination flow — the Explore sheet must not linger underneath it.
                isExploreOpen = false,
            )
        }
        moveCamera(CameraState(result.latitude, result.longitude, DESTINATION_ZOOM), durationMillis = 900)
    }

    /** Route Preview origin editor: choose an address instead of the device's current location. */
    fun onRouteOriginSelected(result: SearchResult) {
        routeJob?.cancel()
        routeJob = null
        _uiState.update { it.copy(routeOriginOverride = result, routeState = RouteState.Idle) }
        requestRoute()
    }

    /** Route Preview destination editor: replace the destination and immediately recalculate. */
    fun onRouteDestinationSelected(result: SearchResult) {
        routeJob?.cancel()
        routeJob = null
        _uiState.update {
            it.copy(
                selectedDestination = result,
                routeState = RouteState.Idle,
                isReviewingDirections = false,
                selectedPlace = null,
            )
        }
        moveCamera(CameraState(result.latitude, result.longitude, DESTINATION_ZOOM), durationMillis = 600)
        requestRoute()
    }

    /**
     * Part A4: "Directions" — computes the route and shows Route Preview, while staying in Map
     * mode. The only thing that turns a selected destination into a requested route. Also doubles
     * as the "Directions" review sheet's own action (section D): recomputing the same
     * origin/destination is idempotent, so reusing this one routing path rather than adding a
     * second is deliberate (B7's "no second routing system", applied to D as well).
     */
    fun onDirectionsRequested() {
        _uiState.update { it.copy(isReviewingDirections = false) }
        requestRoute()
    }

    /**
     * Phase 12A, section D: re-opens Destination Details from Route Preview to review the
     * destination, WITHOUT discarding the already-computed route — [routeState] is untouched.
     */
    fun onDirectionsReviewRequested() {
        _uiState.update { it.copy(isReviewingDirections = true) }
    }

    /** Returns from the review sheet to Route Preview; the route is still exactly as it was. */
    fun onDirectionsReviewDismissed() {
        _uiState.update { it.copy(isReviewingDirections = false) }
    }

    /** Clears the destination marker, summary and any route (in flight or loaded). */
    fun onDestinationCleared() {
        routeJob?.cancel()
        routeJob = null
        // Clearing the destination ends any trip: the route it was navigating is gone.
        stopNavigation()
        _uiState.update {
            it.copy(
                selectedDestination = null,
                routeOriginOverride = null,
                routeState = RouteState.Idle,
                isReviewingDirections = false,
            )
        }
    }

    // endregion

    // region Routing

    /**
     * Called from the process-wide connectivity monitor. A validated network recovery refreshes
     * only a cached degraded route; normal live routes are left untouched.
     */
    fun onConnectivityChanged(isOnline: Boolean) {
        if (!isOnline) return
        val state = _uiState.value
        val cached = (state.routeState as? RouteState.Ready)?.route?.isOfflineCached == true
        if (!cached) return

        if (state.isNavigating) {
            state.currentLocation?.let(::requestReroute)
        } else {
            requestRoute()
        }
    }

    /**
     * Fetches a route from the current location to [MapUiState.selectedDestination]. Cancels
     * any in-flight request first, so a destination change (or a second call for any other
     * reason) always replaces the pending route rather than racing it — only the latest call's
     * result can land.
     *
     * No origin yet: this is not an error, it is [RouteState.WaitingForLocation]. A location fix
     * arriving later re-triggers this automatically (see [onLocationUpdate]); nothing invents a
     * default origin.
     */
    private fun requestRoute() {
        routeJob?.cancel()
        val state = _uiState.value
        val destination = state.selectedDestination ?: return
        val originOverride = state.routeOriginOverride
        val deviceOrigin = state.currentLocation
        if (originOverride == null && deviceOrigin == null) {
            _uiState.update { it.copy(routeState = RouteState.WaitingForLocation) }
            return
        }
        val originCoordinate = originOverride?.let { Coordinate(it.latitude, it.longitude) }
            ?: Coordinate(deviceOrigin!!.latitude, deviceOrigin.longitude)
        _uiState.update { it.copy(routeState = RouteState.Loading) }
        routeRequestStartedAtMillis = System.currentTimeMillis()
        recordReliability(ReliabilityEventName.RouteRequest)
        routeJob = viewModelScope.launch {
            routingRepository.route(
                origin = originCoordinate,
                destination = Coordinate(destination.latitude, destination.longitude),
            ).onSuccess { route ->
                recordReliability(
                    ReliabilityEventName.RouteReady,
                    ReliabilityOutcome.Success,
                    durationMillis = routeRequestStartedAtMillis?.let { System.currentTimeMillis() - it },
                    metadata = mapOf("trafficAware" to route.isTrafficAware.toString()),
                )
                routeRequestStartedAtMillis = null
                _uiState.update {
                    it.copy(
                        routeState = RouteState.Ready(route),
                        roadIntelligenceState = it.roadIntelligenceState.copy(
                            traffic = trafficConditionFor(route),
                        ),
                    )
                }
                route.bounds?.let { bounds -> commandChannel.trySend(MapCommand.FitBounds(bounds)) }
                fetchPlacesAlongRoute(route)
                fetchRoadEventsAlongRoute(route)
            }.onFailure { cause ->
                recordReliability(
                    ReliabilityEventName.RouteFailed,
                    ReliabilityOutcome.Error,
                    reason = cause::class.java.simpleName,
                    durationMillis = routeRequestStartedAtMillis?.let { System.currentTimeMillis() - it },
                )
                routeRequestStartedAtMillis = null
                _uiState.update { it.copy(routeState = RouteState.Error(cause.toRouteError())) }
            }
        }
    }

    /**
     * Route Preview alternative selection. The backend already returned full navigation geometry,
     * so switching routes is instant and does not spend another provider request.
     */
    fun onRouteAlternativeSelected(index: Int) {
        val ready = _uiState.value.routeState as? RouteState.Ready ?: return
        val alternative = ready.route.alternatives.getOrNull(index) ?: return
        val currentAsAlternative = com.openmaps.app.core.routing.RouteAlternative(
            id = ready.route.id,
            label = ready.route.label,
            geometry = ready.route.geometry,
            distanceMeters = ready.route.distanceMeters,
            durationSeconds = ready.route.durationSeconds,
            bounds = ready.route.bounds,
            maneuvers = ready.route.maneuvers,
            baseDurationSeconds = ready.route.baseDurationSeconds,
            typicalDurationSeconds = ready.route.typicalDurationSeconds,
            trafficDelaySeconds = ready.route.trafficDelaySeconds,
            isTrafficAware = ready.route.isTrafficAware,
        )
        val remaining = ready.route.alternatives.toMutableList().apply {
            removeAt(index)
            add(0, currentAsAlternative)
        }
        val selected = alternative.asRouteResult(remaining)
        _uiState.update {
            it.copy(
                routeState = RouteState.Ready(selected),
                roadIntelligenceState = it.roadIntelligenceState.copy(
                    traffic = trafficConditionFor(selected),
                ),
            )
        }
        selected.bounds?.let { commandChannel.trySend(MapCommand.FitBounds(it)) }
        fetchPlacesAlongRoute(selected)
        fetchRoadEventsAlongRoute(selected)
    }

    private fun trafficConditionFor(route: RouteResult): TrafficCondition? {
        if (!route.isTrafficAware) return null
        val base = route.baseDurationSeconds ?: return null
        val adjusted = route.durationSeconds
        val delay = (adjusted - base).coerceAtLeast(0.0)
        val ratio = if (base > 0.0) delay / base else 0.0
        val level = when {
            delay < 60.0 -> TrafficCongestionLevel.Light
            ratio < 0.15 -> TrafficCongestionLevel.Moderate
            ratio < 0.35 -> TrafficCongestionLevel.Heavy
            else -> TrafficCongestionLevel.Severe
        }
        return TrafficCondition(
            congestionLevel = level,
            baseDurationSeconds = base,
            trafficAdjustedDurationSeconds = adjusted,
            isLive = true,
        )
    }

    // endregion

    // region Contextual places (Phase 9)

    /**
     * User-facing category toggles (see "User controls" in ARCHITECTURE.md). Deliberately
     * client-side only: the repository is always queried for every supported category (see
     * [fetchPlaces]), so toggling a filter is instant and never triggers a new network request.
     */
    fun onCategoryFilterChanged(categories: Set<PlaceCategory>) {
        _uiState.update { it.copy(placeCategoryFilters = categories) }
        recomputeVisiblePlaces()
    }

    /** A place marker (Map) or label (Live) was selected for the compact details sheet. */
    fun onPlaceSelected(place: Place?) {
        _uiState.update { it.copy(selectedPlace = place) }
    }

    fun onPlaceDetailsDismissed() {
        _uiState.update { it.copy(selectedPlace = null) }
    }

    private fun onPlaceTapped(placeId: String) {
        val place = _uiState.value.visiblePlaces.firstOrNull { it.id == placeId } ?: return
        onPlaceSelected(place)
    }

    /**
     * A place becomes the destination exactly like a search result does (see "Navigation
     * integration" in ARCHITECTURE.md) — reusing [onDestinationSelected] rather than a second
     * routing path. Unlike a raw search result, "Navigate" here goes straight to [onDirectionsRequested]
     * in the same call: [PlaceDetailsSheet] already showed the place's details (name, category,
     * distance) before this button was ever reachable, so there is no separate Destination Details
     * step left to show — the one tap plays the role of "Directions" directly.
     */
    fun onNavigateToPlace(place: Place) {
        onPlaceSelected(null)
        onDestinationSelected(place.toSearchResult())
        onDirectionsRequested()
    }

    /**
     * Delta-spec section 6: "Start" from Place Details. If this exact place is already the
     * `Ready` destination (the user already requested Directions for it earlier in the session),
     * starts navigation immediately — otherwise behaves exactly like [onNavigateToPlace] (computes
     * the route into Route Preview, where the real Start lives). Never a second routing system:
     * both branches reuse [onDestinationSelected]/[onDirectionsRequested]/[startNavigation]
     * unchanged.
     */
    fun onStartFromPlace(place: Place) {
        val state = _uiState.value
        val isAlreadyRoutedHere = state.routeState is RouteState.Ready &&
            state.selectedDestination?.let { it.latitude == place.latitude && it.longitude == place.longitude } == true
        if (isAlreadyRoutedHere) {
            onPlaceSelected(null)
            onStartNavigationRequested()
        } else {
            onNavigateToPlace(place)
        }
    }

    /**
     * Not navigating: nearby places around the user, refetched only once the user has moved far
     * enough (see [NEARBY_REFETCH_DISTANCE_METERS]) — never on every fix. A no-op while navigating:
     * that case is driven by the route instead (see [fetchPlacesAlongRoute]).
     */
    private fun maybeFetchNearbyPlaces(location: UserLocation) {
        if (_uiState.value.isNavigating) return
        val last = lastPlacesFetchLocation
        val movedEnough = last == null || RouteCorridor.metersBetween(
            Coordinate(last.latitude, last.longitude),
            Coordinate(location.latitude, location.longitude),
        ) >= NEARBY_REFETCH_DISTANCE_METERS
        if (!movedEnough) return
        fetchNearbyPlacesAt(location, PlaceCategoryMapper.supportedCategories)
    }

    /**
     * The actual nearby-places request, factored out of [maybeFetchNearbyPlaces] so
     * [onExploreRequested] can force one immediately on a cold open without duplicating the query.
     */
    private fun fetchNearbyPlacesAt(location: UserLocation, categories: Set<PlaceCategory>) {
        lastPlacesFetchLocation = location
        val center = Coordinate(location.latitude, location.longitude)
        fetchPlaces(categories) {
            adaptiveNearby(center, categories)
        }
    }

    /**
     * Nearby fetch with an **adaptive radius**: start tight (~1.2 km — a dense-urban default) and
     * widen only when the tighter query genuinely came back **empty**.
     *
     * The previous fixed 3 km asked a public, frequently-degraded instance for a large area on
     * every tap. Widening on demand keeps the common case cheap and only pays for area when the
     * neighbourhood really is sparse. A *failure* never widens — retrying a broken provider over a
     * bigger area only multiplies the wait.
     */
    private suspend fun adaptiveNearby(
        center: Coordinate,
        categories: Set<PlaceCategory>,
    ): Result<PlaceQueryResult> {
        var radius = NEARBY_RADIUS_METERS_PRIMARY
        repeat(MAX_RADIUS_EXPANSIONS) {
            lastNearbyRadiusMeters = radius
            val result = placesRepository.nearbyPlaces(center, radius, categories)
            // Failure, results, or the widest radius reached: done either way.
            if (result.isFailure) return result
            if (result.getOrThrow().places.isNotEmpty() || radius >= NEARBY_RADIUS_METERS_MAX) return result
            radius = (radius * NEARBY_RADIUS_GROWTH).coerceAtMost(NEARBY_RADIUS_METERS_MAX)
        }
        return placesRepository.nearbyPlaces(center, NEARBY_RADIUS_METERS_MAX, categories)
    }

    /**
     * The radius the last nearby fetch actually used. Exposed for tests and diagnostics; Phase 12B
     * makes the radius adaptive rather than a single fixed value.
     */
    internal var lastNearbyRadiusMeters: Double = NEARBY_RADIUS_METERS_PRIMARY
        private set

    /**
     * Phase 12A, section B5: opens the Nearby/Explore sheet from the current real device location
     * (section B1 — never the destination or a default). Forces one fetch immediately only when
     * there is genuinely nothing to show yet, so a cold open never reads as a no-op (B4/B9);
     * otherwise this relies on [maybeFetchNearbyPlaces]'s already-running cadence and
     * [com.openmaps.app.core.places.PlaceCache] (B10) exactly like every other places fetch.
     */
    fun onExploreRequested() {
        _uiState.update { it.copy(isExploreOpen = true) }
        val state = _uiState.value
        val location = state.currentLocation
        if (location != null && state.placesState !is PlacesState.Ready && state.placesState !is PlacesState.Loading) {
            fetchNearbyPlacesAt(location, state.placeCategoryFilters)
        }
    }

    /**
     * "Directions" from Place Details (a nearby POI), landing in **Route Preview**.
     *
     * Distinct from [onNavigateToPlace], which also selected the destination. Here the destination is
     * already set — the user tapped the place and saw its details — so this only computes the route
     * and hands over to Route Preview. It deliberately never starts navigation.
     */
    fun onDirectionsRequestedForPlace(place: Place) {
        val state = _uiState.value
        val isAlreadyThisPlace = state.selectedDestination?.let {
            it.latitude == place.latitude && it.longitude == place.longitude
        } == true
        if (!isAlreadyThisPlace) {
            _uiState.update { it.copy(selectedDestination = place.toSearchResult(), selectedPlace = null, isExploreOpen = false) }
        } else {
            _uiState.update { it.copy(selectedPlace = null, isExploreOpen = false) }
        }
        onDirectionsRequested()
    }

    /**
 * Retry after a nearby-places provider failure.
 *
 * Clears the failed state first so the sheet immediately shows the loading spinner again rather
 * than leaving the user staring at the error while the request runs.
 */
    fun onExploreRetryRequested() {
        _uiState.update { it.copy(placesState = PlacesState.Idle) }
        onExploreRequested()
    }

    fun onExploreDismissed() {
        _uiState.update { it.copy(isExploreOpen = false) }
    }

    /**
     * A discovery tile in the home panel was tapped (mockup: Food / Gas / Hotels / Parking).
     *
     * Opens Explore **already narrowed to that one category**, rather than opening the full list and
     * making the user repeat the filter. Narrowing here rather than in the sheet keeps a single
     * filter concept — the same `placeCategoryFilters` the map chips drive — so the chips, the
     * Explore sheet and this tile never disagree about what is selected.
     */
    fun onExploreCategoryClicked(category: PlaceCategory) {
        _uiState.update { it.copy(placeCategoryFilters = setOf(category), isExploreOpen = true) }
        val location = _uiState.value.currentLocation
        if (location != null && _uiState.value.placesState !is PlacesState.Ready) {
            fetchNearbyPlacesAt(location, setOf(category))
        }
    }

    /**
     * A single category chip was toggled. Phase 12B: this fetches **only** the categories on screen
     * rather than the whole taxonomy — tapping "Gas" must not issue a 26-selector query.
     */
    fun onCategoryChipToggled(category: PlaceCategory) {
        val current = _uiState.value.placeCategoryFilters
        val next = if (category in current) current - category else current + category
        _uiState.update { it.copy(placeCategoryFilters = next) }
        recomputeVisiblePlaces()

        if (next.isEmpty()) return // nothing selected: pure filtering, nothing to search for
        if (_uiState.value.isNavigating) return // route drives places while navigating
        _uiState.value.currentLocation?.let { fetchNearbyPlacesAt(it, next) }
    }

    /**
     * "Steps" on Route Preview: reveal the route's maneuver list before starting.
     *
     * Purely presentational — it reads the already-computed [RouteState.Ready] route and never
     * re-requests one, so it cannot race or replace the route the user is about to drive. With no
     * route yet there is nothing to list, so it is a no-op rather than a fetch.
     */
    fun onStepsRequested() {
        val route = (_uiState.value.routeState as? RouteState.Ready)?.route ?: return
        _uiState.update { it.copy(isShowingSteps = true, stepsRouteSummary = route) }
    }

    fun onStepsDismissed() {
        // Clears the held route as well as the flag: leaving it would keep a stale RouteResult
        // alive for the rest of the session, and [onStepsRequested] re-reads from [RouteState]
        // anyway, so nothing needs it once the sheet is closed.
        _uiState.update { it.copy(isShowingSteps = false, stepsRouteSummary = null) }
    }

    /**
     * The mockup's blue circular "navigate" action on the map.
     *
     * Reuses the existing recenter path rather than inventing a second camera entry point: with a
     * route ready it recenters and resumes following (so the driver is looking at the road ahead),
     * and with no route it is plain recenter. Either way there is exactly one implementation of
     * "point the map at me".
     */
    fun onNavigationActionClicked() {
        onRecenterClicked()
    }

    /**
     * "Start" pressed on Destination Details.
     *
     * With a route already computed for this exact destination it starts immediately; otherwise it
     * computes the route first and the user lands in Route Preview, where the real Start lives. The
     * flow therefore never skips Route Preview, and this is deliberately the same policy as
     * [onStartFromPlace] for nearby places — one rule, not two.
     */
    fun onStartRequestedFromDestination(destination: SearchResult) {
        val state = _uiState.value
        val alreadyRoutedHere = state.routeState is RouteState.Ready &&
            state.selectedDestination?.let { it.latitude == destination.latitude && it.longitude == destination.longitude } == true
        if (alreadyRoutedHere) {
            startNavigation()
        } else {
            onDestinationSelected(destination)
            onDirectionsRequested()
        }
    }

    /**
     * Fetched once per route (initial or reroute) rather than refreshed as the trip progresses —
     * Phase 9 deliberately has no sophisticated detour routing, and the corridor query already
     * covers the whole route's bounding box (capped — see `OverpassPlaceRepository`).
     */
    private fun fetchPlacesAlongRoute(route: RouteResult) {
        fetchPlaces(PlaceCategoryMapper.supportedCategories) {
            // Route-corridor places are a plain list (no cache/provenance involved on that path), so
            // it is lifted into the same wrapper every other fetch returns.
            placesRepository.placesAlongRoute(
                routeGeometry = route.geometry,
                corridorMeters = ROUTE_CORRIDOR_METERS,
                categories = PlaceCategoryMapper.supportedCategories,
            ).map { places -> PlaceQueryResult(places, PlaceSource.Network) }
        }
    }

    /**
     * Shared fetch plumbing: cancels any in-flight request first (only the latest call's result can
     * land), and a failure never clears a result that is already showing — places are additive
     * context, never a core dependency.
     *
     * [categories] is carried into [PlacesState.Loading] so the sheet can name what it is looking
     * for ("Finding nearby restaurants…") instead of a generic message.
     */
    private fun fetchPlaces(
        categories: Set<PlaceCategory>,
        request: suspend () -> Result<PlaceQueryResult>,
    ) {
        placesJob?.cancel()
        if (_uiState.value.placesState !is PlacesState.Ready) {
            _uiState.update { it.copy(placesState = PlacesState.Loading(categories)) }
        }
        placesJob = viewModelScope.launch {
            request()
                .onSuccess { result ->
                    // Provenance comes straight from the result, so cached/stale data is never shown
                    // as if it were live.
                    _uiState.update {
                        it.copy(
                            placesState = PlacesState.Ready(
                                places = result.places,
                                fromCache = result.isCached,
                                stale = result.source == PlaceSource.StaleCache,
                            ),
                        )
                    }
                    recomputeVisiblePlaces()
                }
                .onFailure { cause ->
                    _uiState.update {
                        if (it.placesState is PlacesState.Ready) it else it.copy(placesState = PlacesState.Error(cause.toPlacesError()))
                    }
                }
        }
    }

    /**
     * Filters by category, ranks (ahead-of-the-driver classification only while navigating — see
     * [PlaceRanking]) and caps the result. Explicit rather than a derived `MapUiState` getter: this
     * walks the route geometry via [RouteCorridor.isAhead], so it must run only when an input
     * actually changed, never on every recomposition.
     */
    private fun recomputeVisiblePlaces() {
        val state = _uiState.value
        val ready = (state.placesState as? PlacesState.Ready)?.places
        if (ready == null) {
            _uiState.update { it.copy(visiblePlaces = emptyList()) }
            return
        }
        val filtered = ready.filter { it.category in state.placeCategoryFilters }
        val isNav = state.isNavigating
        val geometry = (state.routeState as? RouteState.Ready)?.route?.geometry
        val progress = state.navigationProgress
        val aheadIds = if (isNav && geometry != null && progress != null) {
            filtered.filter { RouteCorridor.isAhead(it, geometry, progress.snappedShapeIndex) }
                .map { it.id }
                .toSet()
        } else {
            emptySet()
        }
        // Phase 11: the active environment profile tunes both the ranking weights and the density
        // cap — see `EnvironmentNavigationProfile`. `DrivingEnvironment.Unknown`'s profile carries the
        // exact pre-Phase-11 multipliers (1.0) and caps, so behavior is unchanged until an environment
        // actually resolves.
        val profile = state.activeProfile
        val ranked = PlaceRanking.rank(
            filtered,
            PlaceRanking.Context(
                isNavigating = isNav,
                aheadPlaceIds = aheadIds,
                utilityWeightMultiplier = profile.utilityPlaceWeightMultiplier,
                generalWeightMultiplier = profile.generalPlaceWeightMultiplier,
            ),
        )
        val cap = if (isNav) profile.poiLimitNavigating else profile.poiLimitBrowsing
        _uiState.update { it.copy(visiblePlaces = ranked.take(cap)) }
    }

    private fun Place.toSearchResult(): SearchResult {
        // Prefer a provider-supplied entrance/access point for routing when available. A POI's
        // visual centroid can be deep inside a mall, hospital campus, stadium or parking complex;
        // routing to that centroid is a common consumer-map failure. We only use an access point
        // when the provider explicitly supplied one, otherwise we preserve the canonical POI point.
        val destinationLatitude = metadata.accessLatitude ?: latitude
        val destinationLongitude = metadata.accessLongitude ?: longitude
        return SearchResult(
            id = id,
            name = name,
            displayName = address ?: name,
            latitude = destinationLatitude,
            longitude = destinationLongitude,
            category = category.name,
            address = address,
        )
    }

    private fun Throwable.toPlacesError(): PlacesError = when (this) {
        is PlaceException.NoNetwork -> PlacesError.NoNetwork
        is PlaceException.Timeout -> PlacesError.Timeout
        is PlaceException.Server -> PlacesError.ServerError
        is PlaceException.RateLimited -> PlacesError.RateLimited
        is PlaceException.Malformed -> PlacesError.Malformed
        else -> PlacesError.Malformed
    }

    // endregion

    // region Road intelligence (Phase 10)

    /**
     * Fetched once per route (initial or reroute), the same cadence `fetchPlacesAlongRoute` uses
     * and for the same reason: Phase 10 has no scrolling-window corridor refresh, and the corridor
     * query already covers the whole route. A failure is silent and never clears a result already
     * showing — see "Network/failure handling" in ARCHITECTURE.md's Road intelligence section; no
     * road-intelligence source may become a navigation dependency.
     */
    private fun fetchRoadEventsAlongRoute(route: RouteResult) {
        hazardsJob?.cancel()
        hazardsJob = viewModelScope.launch {
            roadIntelligenceRepository.roadEventsAlongRoute(route.geometry, ROAD_HAZARD_CORRIDOR_METERS)
                .onSuccess { events ->
                    _uiState.update { it.copy(roadIntelligenceState = it.roadIntelligenceState.copy(events = events)) }
                    recomputeRoadAlerts()
                }
            // onFailure: deliberately no-op. The previous (possibly empty) event list stays shown,
            // exactly like a failed places fetch never clears an already-showing `Ready` result.
        }
    }

    /**
     * Weather changes over tens of minutes, not metres (section 19: "slower cadence"), so this
     * refetches only once the user has moved [WEATHER_REFETCH_DISTANCE_METERS] from the last fetch
     * — a much coarser threshold than places' nearby refetch distance, deliberately.
     */
    private fun maybeFetchWeather(location: UserLocation) {
        val last = lastWeatherFetchLocation
        val movedEnough = last == null || RouteCorridor.metersBetween(
            Coordinate(last.latitude, last.longitude),
            Coordinate(location.latitude, location.longitude),
        ) >= WEATHER_REFETCH_DISTANCE_METERS
        if (!movedEnough) return
        lastWeatherFetchLocation = location
        weatherJob?.cancel()
        weatherJob = viewModelScope.launch {
            roadIntelligenceRepository.weatherNear(Coordinate(location.latitude, location.longitude))
                .onSuccess { weather ->
                    _uiState.update { it.copy(roadIntelligenceState = it.roadIntelligenceState.copy(weather = weather)) }
                    recomputeRoadAlerts()
                }
        }
    }

    /**
     * Speed limits are tied to the road segment, not the clock (section 19: "cacheable"), so this
     * refetches on a tight distance threshold — frequent enough to catch a posted-limit change at
     * the next intersection, still far short of "every GPS tick."
     */
    private fun maybeFetchSpeedLimit(location: UserLocation) {
        val last = lastSpeedLimitFetchLocation
        val movedEnough = last == null || RouteCorridor.metersBetween(
            Coordinate(last.latitude, last.longitude),
            Coordinate(location.latitude, location.longitude),
        ) >= SPEED_LIMIT_REFETCH_DISTANCE_METERS
        if (!movedEnough) return
        lastSpeedLimitFetchLocation = location
        speedLimitJob?.cancel()
        speedLimitJob = viewModelScope.launch {
            roadIntelligenceRepository.speedLimitNear(Coordinate(location.latitude, location.longitude))
                .onSuccess { speedLimit ->
                    _uiState.update { it.copy(roadIntelligenceState = it.roadIntelligenceState.copy(speedLimit = speedLimit)) }
                    recomputeRoadAlerts()
                }
        }
    }

    /**
     * Rebuilds `MapUiState.roadIntelligenceState.activeAlerts`: candidate alerts from whatever data
     * is currently known, deterministically prioritized ([RoadAlertPriority.sorted]), then
     * stabilized through [roadAlertHysteresis] (section 15 — never flickers on raw candidate
     * membership alone). Pure in-memory work over a small list — no network call here, so calling
     * this on every location tick (see [onLocationUpdate]) is cheap by construction, never a
     * per-frame cost and never a reason navigation could stall.
     *
     * Hazard/lane alerts need [MapUiState.navigationProgress] (an "ahead" distance only means
     * something relative to where the driver is on the route) and are simply absent while not
     * navigating; weather/speed-limit/congestion are position-driven, not progress-driven, and show
     * regardless.
     */
    private fun recomputeRoadAlerts() {
        val state = _uiState.value
        val roadState = state.roadIntelligenceState
        val progress = state.navigationProgress
        // Phase 11: the active profile tunes presentation thresholds only — the hazard distances
        // and the lane-guidance ranking band — never the underlying provider truth (section K).
        val profile = state.activeProfile
        val candidates = mutableListOf<RouteAlert>()

        if (progress != null) {
            val active = RoadAlertActivation.activeHazards(
                roadState.events,
                progress.distanceTraveledMeters,
                config = profile.hazardActivationConfig,
            )
            for ((event, aheadMeters) in active) {
                candidates += if (event.severity >= RoadEventSeverity.High) {
                    RouteAlert.Hazard(event, aheadMeters)
                } else {
                    RouteAlert.Informational(event)
                }
            }
        }
        roadState.weather?.let { weather ->
            if (weather.severity >= RoadEventSeverity.Moderate) candidates += RouteAlert.Weather(weather)
        }
        roadState.laneGuidance?.let { candidates += RouteAlert.Lane(it) }
        roadState.speedLimit?.let { candidates += RouteAlert.SpeedLimit(it) }
        roadState.traffic?.let { candidates += RouteAlert.Congestion(it) }

        val laneGuidanceBand = if (profile.laneGuidancePriorityBoost) ELEVATED_LANE_GUIDANCE_BAND else null
        val stabilized = roadAlertHysteresis
            .update(RoadAlertPriority.sorted(candidates, laneGuidanceBand), laneGuidanceBand)
            .take(MAX_ACTIVE_ALERTS)
        _uiState.update {
            it.copy(roadIntelligenceState = it.roadIntelligenceState.copy(activeAlerts = stabilized))
        }
    }

    // endregion

    // region Environment (Phase 11)

    /**
     * Speed limit and road class share one query radius/cadence story but are fetched separately
     * (see `RoadClassRepository`'s doc comment for why) — this mirrors `maybeFetchSpeedLimit`
     * exactly, just against the environment-classification seam instead of Phase 10's.
     */
    private fun maybeFetchRoadClass(location: UserLocation) {
        val last = lastRoadClassFetchLocation
        val movedEnough = last == null || RouteCorridor.metersBetween(
            Coordinate(last.latitude, last.longitude),
            Coordinate(location.latitude, location.longitude),
        ) >= ROAD_CLASS_REFETCH_DISTANCE_METERS
        if (!movedEnough) return
        lastRoadClassFetchLocation = location
        roadClassJob?.cancel()
        roadClassJob = viewModelScope.launch {
            roadClassRepository.roadClassNear(Coordinate(location.latitude, location.longitude))
                .onSuccess { result ->
                    roadClass = result
                    recomputeEnvironment()
                }
            // onFailure: deliberately no-op, same as every other Phase 10/11 provider call — a
            // stale (possibly null) road class is additive context, never a navigation dependency.
        }
    }

    /**
     * Classifies the current trip's [DrivingEnvironment] from whatever real signals are available
     * right now, then runs it through [environmentHysteresis] so the mode itself never flickers
     * (section C). Pure in-memory work — no network call here — so calling this on every location
     * tick (see [onLocationUpdate]) is as cheap as [recomputeRoadAlerts].
     *
     * Only meaningful while navigating: [EnvironmentSignals.averageManeuverSpacingMeters] needs an
     * active route's maneuvers, and section B's whole premise is adapting *navigation* behavior.
     * While not navigating this holds (and, if needed, resets back to) [DrivingEnvironment.Unknown]
     * — the safe default — rather than guessing from stale signals.
     */
    private fun recomputeEnvironment() {
        val state = _uiState.value
        val progress = state.navigationProgress
        if (progress == null) {
            if (state.drivingEnvironment != DrivingEnvironment.Unknown) {
                environmentHysteresis.reset()
                _uiState.update { it.copy(drivingEnvironment = DrivingEnvironment.Unknown) }
            }
            return
        }
        val route = (state.routeState as? RouteState.Ready)?.route
        val signals = EnvironmentSignals(
            roadClass = roadClass,
            speedLimitKmh = state.roadIntelligenceState.speedLimit?.toKmh(),
            currentSpeedKmh = state.roadIntelligenceState.currentSpeedMetersPerSecond?.let { it * KMH_PER_MPS },
            averageManeuverSpacingMeters = route?.maneuvers?.let { ManeuverSpacing.averageSpacingMeters(it) },
            nearbyPlaceCount = state.visiblePlaces.size,
        )
        val classified = EnvironmentClassifier.classify(signals)
        val stabilized = environmentHysteresis.update(classified)
        if (stabilized != state.drivingEnvironment) {
            _uiState.update { it.copy(drivingEnvironment = stabilized) }
            // The POI density cap/ranking weights are baked in at fetch time (see
            // `recomputeVisiblePlaces`'s own "never on every GPS tick" rule), so a newly-confirmed
            // environment is exactly the kind of "an input actually changed" moment that rule means —
            // without this, a profile change would only ever take visible effect on the next fetch.
            recomputeVisiblePlaces()
        }
    }

    private fun SpeedLimitInfo.toKmh(): Double = when (unit) {
        SpeedUnit.Kmh -> value
        SpeedUnit.Mph -> value * KMH_PER_MPH
    }

    // endregion

    // region Navigation (Phase 4)

    /**
     * Begins navigating the route currently in [MapUiState.routeState].
     *
     * Refuses (without side effects) unless a route is loaded *and* a fix is available: without a
     * starting position there is nothing to snap to, and silently "starting" would show a trip that
     * never moves. The route must also carry maneuvers — a route the provider returned without
     * them can't drive a maneuver banner.
     */
    /** Route Preview chooses the experience before a trip begins. */
    fun onNavigationModeSelected(mode: NavigationMode) {
        _uiState.update {
            it.copy(
                selectedNavigationMode = mode,
                isLiveSafetyWarningVisible = false,
                roadIntelligenceState = it.roadIntelligenceState.copy(laneGuidance = null),
            )
        }
    }

    /** Start button entry point. Live never starts without the mandatory safety acknowledgement. */
    fun onStartNavigationRequested() {
        val state = _uiState.value
        if (state.selectedNavigationMode == NavigationMode.Live) {
            _uiState.update { it.copy(isLiveSafetyWarningVisible = true) }
            return
        }
        startNavigation()
    }

    fun onLiveSafetyDismissed() {
        _uiState.update { it.copy(isLiveSafetyWarningVisible = false) }
    }

    /** Safety-screen fallback: continue the same route using conventional map navigation. */
    fun onUseMapNavigationFromSafety() {
        _uiState.update {
            it.copy(
                selectedNavigationMode = NavigationMode.Map,
                selectedMapMode = MapMode.Map,
                isLiveSafetyWarningVisible = false,
            )
        }
        startNavigation()
    }

    /** Deliberate acknowledgement required before the camera-based driving experience begins. */
    fun onLiveSafetyConfirmed() {
        _uiState.update { it.copy(isLiveSafetyWarningVisible = false) }
        startNavigation()
    }

    fun startNavigation() {
        val state = _uiState.value
        val route = (state.routeState as? RouteState.Ready)?.route
        if (route == null) return
        if (state.currentLocation == null) {
            recordReliability(
                ReliabilityEventName.NavigationStartFailed,
                ReliabilityOutcome.Error,
                reason = "location_unavailable",
            )
            _uiState.update { it.copy(navigationState = NavigationState.Error(NavigationError.LocationUnavailable)) }
            return
        }
        if (route.maneuvers.isEmpty()) {
            recordReliability(
                ReliabilityEventName.NavigationStartFailed,
                ReliabilityOutcome.Error,
                reason = "route_unavailable",
            )
            _uiState.update { it.copy(navigationState = NavigationState.Error(NavigationError.RouteUnavailable)) }
            return
        }

        session = NavigationSession(route)
        incidentRerouteIds.clear()
        laneGuidanceJob?.cancel()
        laneGuidanceJob = null
        lastLaneGuidanceKey = null
        _uiState.update {
            it.copy(
                roadIntelligenceState = it.roadIntelligenceState.copy(laneGuidance = null),
            )
        }
        navigationFollowSuspended = false
        // A fresh trip must not inherit the previous one's confirmed environment mode.
        environmentHysteresis.reset()
        // ...nor a previous trip's "already announced this stage" state (section 16).
        voicePlanner.reset()
        voiceGuidanceEngine.speak(VoiceEvent.NavigationStarted)
        // Seed progress immediately from the last known fix so the banner has something to show
        // before the next location tick arrives.
        val seeded = state.currentLocation.let {
            session?.locate(it.toCoordinate(), it.bearingDegrees?.toDouble())
        }
        // Route Preview owns the user's navigation choice. Conventional Map is the default;
        // Live is opt-in and only reaches this function after the safety acknowledgement.
        val didStart = seeded != null
        val requestedMode = state.selectedNavigationMode
        liveAutoFallbackArmed = didStart && requestedMode == NavigationMode.Live
        _uiState.update {
            it.copy(
                navigationFollowSuspended = false,
                selectedMapMode = if (didStart && requestedMode == NavigationMode.Live) MapMode.Live else MapMode.Map,
                liveFallbackMessage = if (didStart) null else it.liveFallbackMessage,
                isLiveSafetyWarningVisible = false,
                navigationState = if (seeded != null) {
                    NavigationState.Active(withOffRouteFlag(seeded, false))
                } else {
                    NavigationState.Error(NavigationError.RouteUnavailable)
                },
            )
        }
        if (seeded != null) {
            recoveryPending = false
            state.selectedDestination?.let { navigationRecoveryStore.save(route, it) }
            navigationForegroundController.start()
            syncLocationCollection()
            recordReliability(
                ReliabilityEventName.NavigationStarted,
                ReliabilityOutcome.Success,
                metadata = mapOf("mode" to requestedMode.name.lowercase()),
            )
            followCameraWith(seeded)
            publishRouteProgress(seeded)
        }
        // Ranking/caps differ once navigating (ahead-of-driver, utility categories) — see
        // `recomputeVisiblePlaces`.
        recomputeVisiblePlaces()
        // Hazard "ahead" distances only mean something once there is a progress to measure from.
        recomputeRoadAlerts()
        recomputeEnvironment()
    }

    fun onDriverReportRequested(type: DriverReportType) {
        val location = _uiState.value.currentLocation
        if (location == null) {
            _uiState.update { it.copy(driverReportStatus = ExternalActionStatus.Unavailable) }
            return
        }
        _uiState.update { it.copy(driverReportStatus = ExternalActionStatus.Working) }
        viewModelScope.launch {
            driverReportRepository.submit(
                DriverReport(
                    type = type,
                    location = location,
                    createdAtMillis = System.currentTimeMillis(),
                ),
            ).onSuccess {
                _uiState.update { it.copy(driverReportStatus = ExternalActionStatus.Success) }
            }.onFailure {
                _uiState.update { it.copy(driverReportStatus = ExternalActionStatus.Unavailable) }
            }
        }
    }

    fun onCommunityReportConfirmed(reportId: String) {
        voteOnCommunityReport(reportId, confirm = true)
    }

    fun onCommunityReportDismissed(reportId: String) {
        voteOnCommunityReport(reportId, confirm = false)
    }

    private fun voteOnCommunityReport(reportId: String, confirm: Boolean) {
        if (reportId.isBlank()) return
        viewModelScope.launch {
            val result = if (confirm) {
                driverReportRepository.confirm(reportId)
            } else {
                driverReportRepository.dismiss(reportId)
            }
            result.onSuccess {
                if (!confirm) {
                    _uiState.update { state ->
                        state.copy(
                            roadIntelligenceState = state.roadIntelligenceState.copy(
                                events = state.roadIntelligenceState.events.filterNot {
                                    it.metadata["communityReportId"] == reportId
                                },
                            ),
                        )
                    }
                    recomputeRoadAlerts()
                }
                refreshCommunityAndRoadEvents(force = true)
            }
        }
    }

    private fun refreshCommunityAndRoadEvents(force: Boolean = false) {
        val route = (_uiState.value.routeState as? RouteState.Ready)?.route ?: return
        val now = System.currentTimeMillis()
        if (!force && now - lastRoadEventsRefreshMillis < ROAD_EVENTS_REFRESH_INTERVAL_MILLIS) return
        lastRoadEventsRefreshMillis = now
        fetchRoadEventsAlongRoute(route)
    }

    fun onTripShareRequested() {
        val state = _uiState.value
        val destination = state.selectedDestination
        val location = state.currentLocation
        if (!state.isNavigating || destination == null || location == null) {
            _uiState.update { it.copy(tripShareStatus = ExternalActionStatus.Unavailable) }
            return
        }

        val existing = activeTripShareSession
        if (existing != null && existing.expiresAtMillis > System.currentTimeMillis()) {
            _uiState.update { it.copy(tripShareStatus = ExternalActionStatus.Success) }
            eventChannel.trySend(MapUiEvent.ShareTripUrl(existing.url))
            return
        }

        _uiState.update { it.copy(tripShareStatus = ExternalActionStatus.Working) }
        val progress = state.navigationProgress
        val remainingSeconds = progress?.remainingDurationSeconds ?: 0.0
        val expiresInMillis = maxOf(
            MIN_TRIP_SHARE_EXPIRY_MILLIS,
            (remainingSeconds * 1_000.0).toLong() + TRIP_SHARE_GRACE_MILLIS,
        ).coerceAtMost(MAX_TRIP_SHARE_EXPIRY_MILLIS)
        viewModelScope.launch {
            tripShareRepository.createSession(
                TripShareRequest(
                    destination = destination,
                    currentLocation = location,
                    expiresAtMillis = System.currentTimeMillis() + expiresInMillis,
                    remainingDistanceMeters = progress?.remainingDistanceMeters,
                    remainingDurationSeconds = progress?.remainingDurationSeconds,
                ),
            ).onSuccess { session ->
                activeTripShareSession = session
                tripShareRecoveryStore.save(session)
                lastTripShareUpdateMillis = System.currentTimeMillis()
                _uiState.update { it.copy(tripShareStatus = ExternalActionStatus.Success) }
                eventChannel.send(MapUiEvent.ShareTripUrl(session.url))
            }.onFailure {
                _uiState.update { it.copy(tripShareStatus = ExternalActionStatus.Unavailable) }
            }
        }
    }

    fun onExternalActionStatusDismissed() {
        _uiState.update {
            it.copy(
                driverReportStatus = ExternalActionStatus.Idle,
                tripShareStatus = ExternalActionStatus.Idle,
            )
        }
    }

    private fun stopTripSharingIfNeeded() {
        val session = activeTripShareSession ?: run {
            tripShareRecoveryStore.clear()
            return
        }
        activeTripShareSession = null
        tripShareRecoveryStore.clear()
        tripShareUpdateJob?.cancel()
        tripShareUpdateJob = null
        lastTripShareUpdateMillis = 0L
        viewModelScope.launch { tripShareRepository.stopSession(session) }
        _uiState.update { it.copy(tripShareStatus = ExternalActionStatus.Idle) }
    }

    private fun maybeUpdateTripSharing(location: UserLocation, progress: RouteProgress) {
        val session = activeTripShareSession ?: return
        if (System.currentTimeMillis() >= session.expiresAtMillis) {
            activeTripShareSession = null
            tripShareRecoveryStore.clear()
            tripShareUpdateJob?.cancel()
            tripShareUpdateJob = null
            _uiState.update { it.copy(tripShareStatus = ExternalActionStatus.Idle) }
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastTripShareUpdateMillis < TRIP_SHARE_UPDATE_INTERVAL_MILLIS) return
        if (tripShareUpdateJob?.isActive == true) return

        lastTripShareUpdateMillis = now
        tripShareUpdateJob = viewModelScope.launch {
            tripShareRepository.updateSession(
                session,
                TripShareUpdate(
                    currentLocation = location,
                    remainingDistanceMeters = progress.remainingDistanceMeters,
                    remainingDurationSeconds = progress.remainingDurationSeconds,
                ),
            ).onFailure { error ->
                if (error is com.openmaps.app.core.sharing.TripShareException.Expired ||
                    error is com.openmaps.app.core.sharing.TripShareException.Closed
                ) {
                    activeTripShareSession = null
                    tripShareRecoveryStore.clear()
                    _uiState.update { it.copy(tripShareStatus = ExternalActionStatus.Idle) }
                }
            }
        }
    }

    private fun completeTripSharingOnArrival(location: UserLocation, progress: RouteProgress) {
        val session = activeTripShareSession ?: run {
            tripShareRecoveryStore.clear()
            return
        }
        activeTripShareSession = null
        tripShareRecoveryStore.clear()
        tripShareUpdateJob?.cancel()
        lastTripShareUpdateMillis = 0L
        tripShareUpdateJob = viewModelScope.launch {
            tripShareRepository.updateSession(
                session,
                TripShareUpdate(
                    currentLocation = location,
                    remainingDistanceMeters = 0.0,
                    remainingDurationSeconds = 0.0,
                    arrived = true,
                ),
            ).onFailure {
                // Privacy fallback: if "arrived" cannot be persisted, attempt to invalidate the
                // public link rather than leaving a live-looking session behind until expiry.
                tripShareRepository.stopSession(session)
            }
        }
        _uiState.update { it.copy(tripShareStatus = ExternalActionStatus.Idle) }
    }

    /** Driver-requested alternative route using the same routing seam as automatic off-route recovery. */
    fun onManualRerouteRequested() {
        val state = _uiState.value
        val location = state.currentLocation ?: return
        if (!state.isNavigating || rerouteInFlight) return
        _uiState.update { it.copy(navigationState = NavigationState.Rerouting) }
        voiceGuidanceEngine.speak(VoiceEvent.Rerouting)
        requestReroute(location)
    }

    /** Explicit red Exit action: end the trip and return to Discover/Home, not Route Preview. */
    fun endNavigationToHome() {
        if (_uiState.value.isNavigating) {
            recordReliability(ReliabilityEventName.NavigationEnded, ReliabilityOutcome.Info, reason = "exit_to_home")
        }
        navigationForegroundController.stop()
        navigationRecoveryStore.clear()
        recoveryPending = false
        stopTripSharingIfNeeded()
        session = null
        navigationFollowSuspended = false
        rerouteInFlight = false
        liveAutoFallbackArmed = false
        laneGuidanceJob?.cancel()
        laneGuidanceJob = null
        lastLaneGuidanceKey = null
        routeJob?.cancel()
        routeJob = null
        _routeProgressView.value = null
        onNavigationEnded()
        roadAlertHysteresis.reset()
        voicePlanner.reset()
        voiceGuidanceEngine.stop()
        _uiState.update {
            it.copy(
                selectedDestination = null,
                routeOriginOverride = null,
                routeState = RouteState.Idle,
                navigationState = NavigationState.Idle,
                selectedMapMode = MapMode.Map,
                selectedNavigationMode = NavigationMode.Map,
                selectedPlace = null,
                isReviewingDirections = false,
                isExploreOpen = false,
                isShowingSteps = false,
                stepsRouteSummary = null,
                navigationFollowSuspended = false,
                isLiveSafetyWarningVisible = false,
                roadIntelligenceState = it.roadIntelligenceState.copy(laneGuidance = null),
            )
        }
        syncLocationCollection()
        recomputeVisiblePlaces()
        recomputeRoadAlerts()
        recomputeEnvironment()
    }

    /** Ends the trip and returns the map to plain route browsing. Never touches the route itself. */
    fun stopNavigation() {
        if (_uiState.value.isNavigating) {
            recordReliability(ReliabilityEventName.NavigationEnded, ReliabilityOutcome.Info, reason = "stopped")
        }
        navigationForegroundController.stop()
        stopTripSharingIfNeeded()
        session = null
        navigationFollowSuspended = false
        rerouteInFlight = false
        liveAutoFallbackArmed = false
        laneGuidanceJob?.cancel()
        laneGuidanceJob = null
        lastLaneGuidanceKey = null
        _uiState.update {
            it.copy(
                navigationState = NavigationState.Idle,
                navigationFollowSuspended = false,
                roadIntelligenceState = it.roadIntelligenceState.copy(laneGuidance = null),
            )
        }
        navigationRecoveryStore.clear()
        recoveryPending = false
        syncLocationCollection()
        _routeProgressView.value = null
        recomputeVisiblePlaces()
        // A new trip must not inherit stale confirm/exit streaks, and route-relative hazard alerts
        // have no "ahead" distance to show once there is no progress to measure from.
        roadAlertHysteresis.reset()
        voicePlanner.reset()
        voiceGuidanceEngine.stop()
        recomputeRoadAlerts()
        // No route left to measure maneuver spacing against: back to the safe Unknown default.
        recomputeEnvironment()
    }

    /**
     * Rehydrates a process-killed trip from local state only after a fresh device fix exists.
     * Recovery always returns in conventional Map mode; Live/AR remains opt-in after recreation.
     */
    private fun maybeResumeRecoveredNavigation(location: UserLocation) {
        if (!recoveryPending || !isAppVisible) return
        val state = _uiState.value
        val route = (state.routeState as? RouteState.Ready)?.route ?: run {
            navigationRecoveryStore.clear()
            recoveryPending = false
            return
        }
        val destination = state.selectedDestination ?: run {
            navigationRecoveryStore.clear()
            recoveryPending = false
            return
        }
        if (route.maneuvers.isEmpty()) {
            navigationRecoveryStore.clear()
            recoveryPending = false
            return
        }

        val restoredSession = NavigationSession(route)
        val progress = restoredSession.locate(
            location.toCoordinate(),
            location.bearingDegrees?.toDouble(),
        ) ?: return

        session = restoredSession
        recoveryPending = false
        navigationForegroundController.start()
        _uiState.update {
            it.copy(
                selectedDestination = destination,
                selectedMapMode = MapMode.Map,
                selectedNavigationMode = NavigationMode.Map,
                navigationState = NavigationState.Active(withOffRouteFlag(progress, false)),
                navigationFollowSuspended = false,
            )
        }
        publishRouteProgress(progress)
        followCameraWith(progress)
        syncLocationCollection()
        recordReliability(
            ReliabilityEventName.NavigationStarted,
            ReliabilityOutcome.Success,
            reason = "process_recovery",
            metadata = mapOf("mode" to "map"),
        )
    }

    /**
     * Advances the trip from a fresh fix. Called for every location update, so it must be cheap and
     * must never throw: a navigation bug should not be able to take down the map screen.
     */
    private fun onNavigationTick(location: UserLocation) {
        val activeSession = session ?: return
        val progress = activeSession.locate(
            location.toCoordinate(),
            location.bearingDegrees?.toDouble(),
        ) ?: return
        publishRouteProgress(progress)

        if (progress.hasArrived) {
            navigationForegroundController.stop()
            navigationRecoveryStore.clear()
            recoveryPending = false
            completeTripSharingOnArrival(location, progress)
            session = null
            // Arrival ends the trip: release the AR anchors and remove the arrows.
            onNavigationEnded()
            // End the split too: on arrival the whole route is traveled, so keeping the
            // muted overlay would suggest there is still road ahead to drive.
            _routeProgressView.value = null
            _uiState.update { it.copy(navigationState = NavigationState.Arrived(progress)) }
            syncLocationCollection()
            recordReliability(ReliabilityEventName.Arrived, ReliabilityOutcome.Success)
            voiceGuidanceEngine.speak(VoiceEvent.Arrived)
            viewModelScope.launch {
                delay(ARRIVAL_RETURN_HOME_DELAY_MILLIS)
                if (_uiState.value.navigationState is NavigationState.Arrived) {
                    finishArrivalToHome()
                }
            }
            return
        }

        maybeUpdateTripSharing(location, progress)
        refreshCommunityAndRoadEvents()
        dispatchVoiceForProgress(progress)
        maybeFetchLaneGuidance(progress)
        maybeRerouteForCriticalIncident(location, progress)

        when (activeSession.updateOffRoute(progress)) {
            OffRouteDecision.OnRoute -> {
                _uiState.update { it.copy(navigationState = NavigationState.Active(withOffRouteFlag(progress, false))) }
                followCameraWith(progress)
            }
            OffRouteDecision.OffRouteConfirmed -> {
                recordReliability(ReliabilityEventName.OffRouteConfirmed, ReliabilityOutcome.Degraded)
                // Flagged but not rerouting yet: hold the route and keep tracking. Still follow,
                // so the driver keeps seeing where they are relative to it.
                _uiState.update { it.copy(navigationState = NavigationState.OffRoute(withOffRouteFlag(progress, true))) }
                followCameraWith(progress)
            }
            OffRouteDecision.TriggerReroute -> {
                recordReliability(ReliabilityEventName.RerouteTriggered, ReliabilityOutcome.Degraded)
                _uiState.update { it.copy(navigationState = NavigationState.Rerouting) }
                voiceGuidanceEngine.speak(VoiceEvent.Rerouting)
                requestReroute(location)
            }
        }
    }

    /**
     * Requests lane guidance once per upcoming maneuver. The trace ends at that maneuver and uses
     * only real route geometry. The backend combines OSM turn-lane tags and Mapbox lane arrays,
     * and returns null whenever the evidence is missing, conflicting, or not maneuver-compatible.
     */
    private fun maybeFetchLaneGuidance(progress: RouteProgress) {
        val repository = laneGuidanceRepository ?: return
        val route = (_uiState.value.routeState as? RouteState.Ready)?.route ?: return
        val maneuver = progress.currentManeuver
        val key = "${maneuver.beginShapeIndex}:${maneuver.endShapeIndex}:${maneuver.type}"
        if (key == lastLaneGuidanceKey || laneGuidanceJob?.isActive == true) return

        val end = maneuver.endShapeIndex.coerceIn(1, route.geometry.lastIndex)
        val start = maxOf(0, minOf(maneuver.beginShapeIndex, end - 1, end - LANE_TRACE_MAX_POINTS))
        val trace = route.geometry.subList(start, end + 1)
        if (trace.size < 2) return

        lastLaneGuidanceKey = key
        _uiState.update {
            it.copy(
                roadIntelligenceState = it.roadIntelligenceState.copy(laneGuidance = null),
            )
        }
        laneGuidanceJob = viewModelScope.launch {
            repository.guidance(trace, maneuver.type)
                .onSuccess { guidance ->
                    // Do not allow a late response for a maneuver already passed to light up LaneLock.
                    val active = _uiState.value.navigationProgress?.currentManeuver
                    val stillCurrent = active != null &&
                        active.beginShapeIndex == maneuver.beginShapeIndex &&
                        active.endShapeIndex == maneuver.endShapeIndex &&
                        active.type == maneuver.type
                    if (stillCurrent) {
                        _uiState.update { state ->
                            state.copy(
                                roadIntelligenceState = state.roadIntelligenceState.copy(
                                    laneGuidance = guidance,
                                ),
                            )
                        }
                        recomputeRoadAlerts()
                    }
                }
        }
    }

    /**
     * A provider-confirmed severe closure/crash ahead is a routing input, not merely decoration.
     * Trigger one traffic-aware recalculation while preserving the current route until a replacement
     * succeeds. This is intentionally narrower than "reroute on any incident".
     */
    private fun maybeRerouteForCriticalIncident(
        location: UserLocation,
        progress: RouteProgress,
    ) {
        if (rerouteInFlight) return
        val candidate = _uiState.value.roadIntelligenceState.events
            .asSequence()
            .filter { event ->
                event.id !in incidentRerouteIds &&
                    event.severity >= RoadEventSeverity.High &&
                    (event.type == RoadEventType.Closure || event.type == RoadEventType.Crash) &&
                    event.isTrustedForAutomaticReroute()
            }
            .mapNotNull { event ->
                val routeMeters = event.routeDistanceMeters ?: return@mapNotNull null
                val ahead = routeMeters - progress.distanceTraveledMeters
                if (ahead in 0.0..CRITICAL_INCIDENT_REROUTE_DISTANCE_METERS) event to ahead else null
            }
            .minByOrNull { it.second }
            ?: return

        incidentRerouteIds += candidate.first.id
        _uiState.update { it.copy(navigationState = NavigationState.Rerouting) }
        voiceGuidanceEngine.speak(VoiceEvent.Rerouting)
        requestReroute(location)
    }

    private fun com.openmaps.app.core.roadintelligence.RoadEvent.isTrustedForAutomaticReroute(): Boolean {
        if (metadata["source"] != "community") return true
        val confidence = metadata["confidence"]?.toDoubleOrNull() ?: return false
        val confirmations = metadata["confirmations"]?.toIntOrNull() ?: 0
        return confidence >= COMMUNITY_REROUTE_MIN_CONFIDENCE && confirmations >= COMMUNITY_REROUTE_MIN_CONFIRMATIONS
    }

    /** Completes the arrival lifecycle and returns to the clean Discover/Home state. */
    private fun finishArrivalToHome() {
        navigationForegroundController.stop()
        navigationRecoveryStore.clear()
        recoveryPending = false
        stopTripSharingIfNeeded()
        session = null
        navigationFollowSuspended = false
        rerouteInFlight = false
        liveAutoFallbackArmed = false
        laneGuidanceJob?.cancel()
        laneGuidanceJob = null
        lastLaneGuidanceKey = null
        routeJob?.cancel()
        routeJob = null
        _routeProgressView.value = null
        onNavigationEnded()
        roadAlertHysteresis.reset()
        voicePlanner.reset()
        voiceGuidanceEngine.stop()
        _uiState.update {
            it.copy(
                selectedDestination = null,
                routeOriginOverride = null,
                routeState = RouteState.Idle,
                navigationState = NavigationState.Idle,
                selectedMapMode = MapMode.Map,
                selectedNavigationMode = NavigationMode.Map,
                selectedPlace = null,
                isReviewingDirections = false,
                isExploreOpen = false,
                isShowingSteps = false,
                stepsRouteSummary = null,
                navigationFollowSuspended = false,
                isLiveSafetyWarningVisible = false,
            )
        }
        syncLocationCollection()
        recomputeVisiblePlaces()
        recomputeRoadAlerts()
        recomputeEnvironment()
    }

    /**
     * Requests a fresh route from the current position to the same destination.
     *
     * Deliberately does not enter [RouteState.Loading]: the existing route stays displayed and
     * usable while the new one is fetched, because a driver mid-excursion still needs the old
     * line and their existing progress. One reroute can be in flight at a time.
     */
    private fun requestReroute(origin: UserLocation) {
        if (rerouteInFlight) return
        val destination = _uiState.value.selectedDestination ?: return
        rerouteInFlight = true
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            routingRepository.route(origin.toCoordinate(), Coordinate(destination.latitude, destination.longitude))
                .onSuccess { route ->
                    recordReliability(ReliabilityEventName.RerouteSucceeded, ReliabilityOutcome.Success)
                    navigationRecoveryStore.save(route, destination)
                    rerouteInFlight = false
                    session = NavigationSession(route)
                    laneGuidanceJob?.cancel()
                    laneGuidanceJob = null
                    lastLaneGuidanceKey = null
                    // Anchors and arrows were derived from the OLD route's geometry, so they are now
                    // stale. Notify before the new set is produced so two routes never overlap.
                    onRouteReplaced()
                    navigationFollowSuspended = false
                    val progress = _uiState.value.currentLocation?.let {
                        session?.locate(it.toCoordinate(), it.bearingDegrees?.toDouble())
                    }
                    _uiState.update {
                        it.copy(
                            navigationFollowSuspended = false,
                            routeState = RouteState.Ready(route),
                            navigationState = progress
                                ?.let { p -> NavigationState.Active(withOffRouteFlag(p, false)) }
                                ?: NavigationState.Ready,
                            roadIntelligenceState = it.roadIntelligenceState.copy(
                                traffic = trafficConditionFor(route),
                                laneGuidance = null,
                            ),
                        )
                    }
                    progress?.let {
                        publishRouteProgress(it)
                        followCameraWith(it)
                    }
                    // The old route's corridor places/hazards are stale now; fetch against the new one.
                    fetchPlacesAlongRoute(route)
                    // The old route's matched distances are meaningless against new geometry, and a
                    // fresh trip must not inherit the old one's confirm/exit streaks.
                    roadAlertHysteresis.reset()
                    // Nor the old maneuvers' "already announced this stage" state (section 16) —
                    // the new route has an entirely different maneuver sequence.
                    voicePlanner.reset()
                    voiceGuidanceEngine.speak(VoiceEvent.RouteUpdated)
                    fetchRoadEventsAlongRoute(route)
                    recomputeRoadAlerts()
                    // Deliberately not an `environmentHysteresis.reset()`: a reroute is still the
                    // same physical surroundings, just a different path through them, so carrying
                    // the confirmed mode over avoids a jarring profile flash on every reroute.
                    recomputeEnvironment()
                }
                .onFailure {
                    recordReliability(ReliabilityEventName.RerouteFailed, ReliabilityOutcome.Error)
                    rerouteInFlight = false
                    // Not a dead end: the old route is still valid and still displayed, so return
                    // to tracking against it and surface a one-time notice instead of an error.
                    session?.offRouteDetector?.reset()
                    val progress = _uiState.value.currentLocation?.let { loc ->
                        session?.locate(loc.toCoordinate(), loc.bearingDegrees?.toDouble())
                    }
                    _uiState.update {
                        it.copy(
                            navigationState = progress
                                ?.let { p -> NavigationState.OffRoute(withOffRouteFlag(p, true)) }
                                ?: NavigationState.Ready,
                        )
                    }
                    eventChannel.trySend(MapUiEvent.RerouteFailed)
                }
        }
    }

    /**
     * Section 16: at most one maneuver announcement (staged far/near/immediate, never repeated)
     * plus the one-shot "approaching destination" cue, both decided by [voicePlanner] from the
     * same [RouteProgress] every other per-tick consumer already reads — no second progress model.
     */
    private fun dispatchVoiceForProgress(progress: RouteProgress) {
        voicePlanner.planManeuver(progress)?.let { voiceGuidanceEngine.speak(it) }
        voicePlanner.planDestinationApproaching(progress)?.let { voiceGuidanceEngine.speak(it) }
    }

    /** Pushes the traveled/remaining split to the renderer. */
    private fun publishRouteProgress(progress: RouteProgress) {
        _routeProgressView.value = RouteProgressView(progress.snappedShapeIndex)
    }

    /** Moves the camera to the follow position, unless the user has taken over. */
    private fun followCameraWith(progress: RouteProgress) {
        if (navigationFollowSuspended) return
        val activeSession = session ?: return
        val profile = _uiState.value.activeProfile
        val target = activeSession.camera.targetFor(
            progress = progress,
            headingDegrees = _uiState.value.currentLocation?.bearingDegrees?.toDouble(),
            fallbackBearing = _uiState.value.cameraState.bearing,
            // Phase 11 (Part J): environment tunes the follow camera's zoom/look-ahead only. Manual
            // pan/recenter behavior is untouched — this only changes where the *automatic* target sits.
            zoom = profile.navigationCameraZoom,
            lookAheadMeters = profile.navigationCameraLookAheadMeters,
        ) ?: return
        moveCamera(
            CameraState(
                latitude = target.latitude,
                longitude = target.longitude,
                zoom = target.zoom,
                bearing = target.bearingDegrees,
                pitch = target.pitch,
            ),
            // Short, non-animated steps: a nav camera tracks continuously, so easing between every
            // GPS tick would read as lag rather than as smoothness.
            durationMillis = NAVIGATION_CAMERA_DURATION_MILLIS,
            animated = false,
        )
    }

    /** [RouteProgress.isOffRoute] is owned by the detector, so it is stamped on at the boundary. */
    private fun withOffRouteFlag(progress: RouteProgress, isOffRoute: Boolean): RouteProgress =
        if (progress.isOffRoute == isOffRoute) progress else progress.copy(isOffRoute = isOffRoute)

    // endregion

    // region Location pipeline

    private fun syncLocationCollection() {
        val state = _uiState.value
        val backgroundNavigationActive = when (state.navigationState) {
            is NavigationState.Active, is NavigationState.OffRoute, NavigationState.Rerouting -> true
            NavigationState.Idle, NavigationState.Ready, is NavigationState.Arrived, is NavigationState.Error -> false
        }
        val shouldCollect = state.locationPermission.isGranted && (isAppVisible || backgroundNavigationActive)
        if (shouldCollect) {
            if (locationJob?.isActive != true) startLocationCollection()
        } else {
            stopLocationCollection()
        }
    }

    private fun restartLocationCollection() {
        locationJob?.cancel()
        locationJob = null
        syncLocationCollection()
    }

    private fun startLocationCollection() {
        _uiState.update {
            it.copy(locationStatus = if (it.currentLocation != null) LocationStatus.Available else LocationStatus.Loading)
        }
        if (_uiState.value.locationStatus == LocationStatus.Loading) armFixTimeout()
        locationJob = viewModelScope.launch {
            locationRepository.updates()
                .catch { onLocationUpdate(LocationUpdate.Failure(it)) }
                .collect { onLocationUpdate(it) }
        }
    }

    private fun stopLocationCollection() {
        locationJob?.cancel()
        locationJob = null
        timeoutJob?.cancel()
        timeoutJob = null
        _uiState.update {
            it.copy(locationStatus = if (it.currentLocation != null) LocationStatus.Available else LocationStatus.Idle)
        }
    }

    private fun armFixTimeout() {
        timeoutJob?.cancel()
        timeoutJob = viewModelScope.launch {
            delay(fixTimeoutMillis)
            _uiState.update {
                if (it.locationStatus == LocationStatus.Loading) it.copy(locationStatus = LocationStatus.Unavailable) else it
            }
        }
    }

    private fun onLocationUpdate(update: LocationUpdate) {
        when (update) {
            is LocationUpdate.Fix -> {
                timeoutJob?.cancel()
                _uiState.update {
                    it.copy(
                        currentLocation = update.location,
                        locationStatus = LocationStatus.Available,
                        // A direct reading, never derived from consecutive fixes — see
                        // `UserLocation.speedMetersPerSecond`'s doc comment.
                        roadIntelligenceState = it.roadIntelligenceState.copy(
                            currentSpeedMetersPerSecond = update.location.speedMetersPerSecond,
                        ),
                    )
                }
                centerOnFirstFix(update.location)
                maybeReverseGeocodeCurrentLocation(update.location)
                maybeResumeRecoveredNavigation(update.location)
                // A route that was only missing an origin can proceed now that one exists.
                if (_uiState.value.routeState == RouteState.WaitingForLocation) requestRoute()
                // Navigation advances from every fix, but only once a trip is actually underway.
                if (_uiState.value.isNavigating) onNavigationTick(update.location)
                // Nearby places are location-driven only while not navigating (see Phase 9 region).
                maybeFetchNearbyPlaces(update.location)
                // Weather/speed-limit are current-location-driven regardless of navigation state —
                // see the Road intelligence (Phase 10) region below for each's own refetch cadence.
                maybeFetchWeather(update.location)
                maybeFetchSpeedLimit(update.location)
                recomputeRoadAlerts()
                // Phase 11: road class is only meaningful while navigating (see `recomputeEnvironment`),
                // but the fetch itself is gated the same movement-distance way regardless, so a route
                // picked up mid-fetch is never left waiting on a second location tick.
                maybeFetchRoadClass(update.location)
                recomputeEnvironment()
            }
            LocationUpdate.ServicesDisabled -> {
                timeoutJob?.cancel()
                _uiState.update {
                    it.copy(
                        currentLocation = null,
                        currentLocationPlace = null,
                        locationStatus = LocationStatus.ServicesDisabled,
                    )
                }
            }
            LocationUpdate.ServicesEnabled -> {
                if (_uiState.value.locationStatus != LocationStatus.Available) {
                    _uiState.update { it.copy(locationStatus = LocationStatus.Loading) }
                    armFixTimeout()
                }
            }
            is LocationUpdate.Failure -> {
                timeoutJob?.cancel()
                _uiState.update { it.copy(locationStatus = LocationStatus.Unavailable) }
            }
        }
    }

    private fun centerOnFirstFix(location: UserLocation) {
        if (autoCenterHandled) return
        autoCenterHandled = true
        // Respect a user who already started exploring before the first fix arrived.
        if (userHasMovedCamera) return
        moveCamera(
            CameraState(location.latitude, location.longitude, INITIAL_USER_ZOOM),
            durationMillis = 1400,
        )
    }

    /**
     * Resolves GPS coordinates into a human-readable location without hammering the backend on every
     * one-second location tick. A first fix resolves immediately; later requests require meaningful
     * movement. Failure never removes a previously resolved place because GPS itself is still valid.
     */
    private fun maybeReverseGeocodeCurrentLocation(location: UserLocation) {
        val repository = reverseGeocodingRepository ?: return
        val previous = lastReverseGeocodedLocation
        if (previous != null) {
            val movedMeters = RouteCorridor.metersBetween(
                previous.toCoordinate(),
                location.toCoordinate(),
            )
            if (movedMeters < REVERSE_GEOCODE_MOVE_THRESHOLD_METERS) return
        }
        if (reverseGeocodingJob?.isActive == true) return

        lastReverseGeocodedLocation = location
        reverseGeocodingJob = viewModelScope.launch {
            repository.reverse(
                location.latitude,
                location.longitude,
                localizationPrefs?.currentLocaleTag(),
            )
                .onSuccess { resolved ->
                    if (resolved != null) {
                        _uiState.update { state ->
                            state.copy(currentLocationPlace = resolved)
                        }
                    }
                }
        }
    }

    // endregion

    private fun moveCamera(target: CameraState, durationMillis: Int, animated: Boolean = true) {
        // Optimistic update so a configuration change mid-animation restores the destination.
        _uiState.update { it.copy(cameraState = target) }
        commandChannel.trySend(MapCommand.MoveCamera(target, animated = animated, durationMillis = durationMillis))
    }

    private fun UserLocation.toCoordinate(): Coordinate = Coordinate(latitude, longitude)

    override fun onCleared() {
        laneGuidanceJob?.cancel()
        reverseGeocodingJob?.cancel()
        commandChannel.close()
        eventChannel.close()
    }

    companion object {
        const val DEFAULT_FIX_TIMEOUT_MILLIS = 20_000L
        const val MIN_TRIP_SHARE_EXPIRY_MILLIS = 60 * 60 * 1_000L
        const val TRIP_SHARE_GRACE_MILLIS = 30 * 60 * 1_000L
        const val MAX_TRIP_SHARE_EXPIRY_MILLIS = 24 * 60 * 60 * 1_000L
        const val TRIP_SHARE_UPDATE_INTERVAL_MILLIS = 5_000L
        const val ROAD_EVENTS_REFRESH_INTERVAL_MILLIS = 90_000L
        const val COMMUNITY_REROUTE_MIN_CONFIDENCE = 0.65
        const val COMMUNITY_REROUTE_MIN_CONFIRMATIONS = 2
        const val ARRIVAL_RETURN_HOME_DELAY_MILLIS = 6_000L
        const val CRITICAL_INCIDENT_REROUTE_DISTANCE_METERS = 5_000.0
        const val LANE_TRACE_MAX_POINTS = 24
        const val REVERSE_GEOCODE_MOVE_THRESHOLD_METERS = 100.0
        const val INITIAL_USER_ZOOM = 15.0
        const val RECENTER_MIN_ZOOM = 14.0
        const val DESTINATION_ZOOM = 15.0

        // Phase 9: contextual places.

        /** Nearby-fetch radius while browsing (not navigating). */
        const val NEARBY_RADIUS_METERS = 1_500.0

        /**
         * Adaptive-radius ladder (Phase 12B). Start dense-urban and widen only on a genuinely empty
         * result, so a sparse area costs one extra small request instead of always querying 3 km.
         */
        const val NEARBY_RADIUS_METERS_PRIMARY = 1_200.0
        const val NEARBY_RADIUS_METERS_MAX = 3_000.0
        const val NEARBY_RADIUS_GROWTH = 2.0

        /** Bounded: at most two widening attempts, then the request ends. No unbounded search. */
        const val MAX_RADIUS_EXPANSIONS = 2

        /** Minimum movement before a nearby refetch — avoids refetching the same area continuously. */
        const val NEARBY_REFETCH_DISTANCE_METERS = 400.0

        /** How far off the active route a place can sit and still be surfaced. */
        const val ROUTE_CORRIDOR_METERS = 250.0

        /**
         * Map-mode density cap while just browsing ("Map: moderate" — see ARCHITECTURE.md).
         * As of Phase 11 this is only the [com.openmaps.app.core.environment.EnvironmentProfiles.unknown]
         * baseline value — `recomputeVisiblePlaces` reads `MapUiState.activeProfile.poiLimitBrowsing`,
         * which equals this constant exactly until an environment other than `Unknown` resolves.
         */
        const val MAX_VISIBLE_PLACES_BROWSING = 40

        /** Map-mode density cap while navigating: fewer, more relevant places. See the note above. */
        const val MAX_VISIBLE_PLACES_NAVIGATING = 20

        // Phase 10: road intelligence.

        /** How far off the active route a hazard/incident can sit and still be surfaced. */
        const val ROAD_HAZARD_CORRIDOR_METERS = 300.0

        /** Weather changes slowly — a much coarser refetch distance than places/hazards. */
        const val WEATHER_REFETCH_DISTANCE_METERS = 8_000.0

        /** Tight enough to catch a posted-limit change at the next intersection. */
        const val SPEED_LIMIT_REFETCH_DISTANCE_METERS = 150.0

        /** Keeps the rendered alert list short even when several hazards qualify at once. */
        const val MAX_ACTIVE_ALERTS = 5

        // Phase 11: environment classification.

        /** Same cadence as Phase 10's speed-limit fetch — a road's classification is just as stable. */
        const val ROAD_CLASS_REFETCH_DISTANCE_METERS = 150.0

        /**
         * Rank a boosted [com.openmaps.app.core.roadintelligence.RouteAlert.Lane] is given when
         * `EnvironmentNavigationProfile.laneGuidancePriorityBoost` is true: between collision-class
         * hazards and dangerous weather — "elevated" relative to lane guidance's own normal band, not
         * elevated above a life-safety alert.
         */
        const val ELEVATED_LANE_GUIDANCE_BAND = 25

        const val KMH_PER_MPS = 3.6
        const val KMH_PER_MPH = 1.60934

        /**
         * Camera step while following. Short and non-animated: the target updates on every GPS
         * tick, so easing each step would read as lag rather than as smooth tracking.
         */
        const val NAVIGATION_CAMERA_DURATION_MILLIS = 0

        /** Maps any bearing to [-180, 180) so "rotated" checks and the compass needle are symmetric. */
        fun normalizeBearing(degrees: Double): Float = (((degrees % 360.0) + 540.0) % 360.0 - 180.0).toFloat()
    }
}

private fun Throwable.toRouteError(): RouteError = when (this) {
    is RoutingException.NoNetwork -> RouteError.NoNetwork
    is RoutingException.Timeout -> RouteError.Timeout
    is RoutingException.Server -> RouteError.ServerError
    is RoutingException.Malformed -> RouteError.Malformed
    is RoutingException.NoRouteFound -> RouteError.NoRouteFound
    else -> RouteError.Malformed
}
