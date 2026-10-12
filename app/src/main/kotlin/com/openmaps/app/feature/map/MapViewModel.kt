package com.openmaps.app.feature.map

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.LocationPermissionState
import com.openmaps.app.core.location.LocationRepository
import com.openmaps.app.core.location.LocationStatus
import com.openmaps.app.core.location.LocationUpdate
import com.openmaps.app.core.location.UserLocation
import com.openmaps.app.core.location.isGranted
import com.openmaps.app.core.navigation.NavigationSession
import com.openmaps.app.core.navigation.OffRouteDecision
import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.core.places.Place
import com.openmaps.app.core.places.PlaceCategory
import com.openmaps.app.core.places.PlaceCategoryMapper
import com.openmaps.app.core.places.PlaceException
import com.openmaps.app.core.places.PlaceRanking
import com.openmaps.app.core.places.PlaceRepository
import com.openmaps.app.core.places.RouteCorridor
import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.RouteResult
import com.openmaps.app.core.routing.RoutingException
import com.openmaps.app.core.routing.RoutingRepository
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
    private val fixTimeoutMillis: Long = DEFAULT_FIX_TIMEOUT_MILLIS,
) : ViewModel() {

    private val _uiState = MutableStateFlow(MapUiState())
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
    private var isAppVisible = false
    private var autoCenterHandled = false
    private var userHasMovedCamera = false
    private var autoPromptEmitted = false

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
            if (state.isGranted) next else next.copy(currentLocation = null, locationStatus = LocationStatus.Idle)
        }
        if (state == LocationPermissionState.NotRequested && !autoPromptEmitted) {
            autoPromptEmitted = true
            eventChannel.trySend(MapUiEvent.RequestLocationPermission)
        }
        syncLocationCollection()
    }

    /** GPS/network listeners run only while the app is visible (battery and privacy). */
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
        _uiState.update { it.copy(selectedMapMode = mode) }
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
        // doesn't snap the camera back onto the user's own position.
        val navigationTarget = state.navigationProgress
            ?.let { progress -> session?.camera?.targetFor(progress, location.bearingDegrees?.toDouble(), state.cameraState.bearing) }
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

    /** A search result (fresh or recent) was picked: show its marker, fly the camera, request a route. */
    fun onDestinationSelected(result: SearchResult) {
        // A different destination is a different trip; the old session's geometry is gone.
        if (_uiState.value.isNavigating) stopNavigation()
        _uiState.update { it.copy(selectedDestination = result) }
        moveCamera(CameraState(result.latitude, result.longitude, DESTINATION_ZOOM), durationMillis = 900)
        requestRoute()
    }

    /** Clears the destination marker, summary and any route (in flight or loaded). */
    fun onDestinationCleared() {
        routeJob?.cancel()
        routeJob = null
        // Clearing the destination ends any trip: the route it was navigating is gone.
        stopNavigation()
        _uiState.update { it.copy(selectedDestination = null, routeState = RouteState.Idle) }
    }

    // endregion

    // region Routing

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
        val destination = _uiState.value.selectedDestination ?: return
        val origin = _uiState.value.currentLocation
        if (origin == null) {
            _uiState.update { it.copy(routeState = RouteState.WaitingForLocation) }
            return
        }
        _uiState.update { it.copy(routeState = RouteState.Loading) }
        routeJob = viewModelScope.launch {
            routingRepository.route(
                origin = Coordinate(origin.latitude, origin.longitude),
                destination = Coordinate(destination.latitude, destination.longitude),
            ).onSuccess { route ->
                _uiState.update { it.copy(routeState = RouteState.Ready(route)) }
                route.bounds?.let { bounds -> commandChannel.trySend(MapCommand.FitBounds(bounds)) }
                fetchPlacesAlongRoute(route)
            }.onFailure { cause ->
                _uiState.update { it.copy(routeState = RouteState.Error(cause.toRouteError())) }
            }
        }
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
     * routing path.
     */
    fun onNavigateToPlace(place: Place) {
        onPlaceSelected(null)
        onDestinationSelected(place.toSearchResult())
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
        lastPlacesFetchLocation = location
        fetchPlaces {
            placesRepository.nearbyPlaces(
                center = Coordinate(location.latitude, location.longitude),
                radiusMeters = NEARBY_RADIUS_METERS,
                categories = PlaceCategoryMapper.supportedCategories,
            )
        }
    }

    /**
     * Fetched once per route (initial or reroute) rather than refreshed as the trip progresses —
     * Phase 9 deliberately has no sophisticated detour routing, and the corridor query already
     * covers the whole route's bounding box (capped — see `OverpassPlaceRepository`).
     */
    private fun fetchPlacesAlongRoute(route: RouteResult) {
        fetchPlaces {
            placesRepository.placesAlongRoute(
                routeGeometry = route.geometry,
                corridorMeters = ROUTE_CORRIDOR_METERS,
                categories = PlaceCategoryMapper.supportedCategories,
            )
        }
    }

    /**
     * Shared fetch plumbing: cancels any in-flight request first (only the latest call's result
     * can land), and a failure never clears a result that is already showing — places are additive
     * context, never a core dependency (see "Network/failure handling" in ARCHITECTURE.md).
     */
    private fun fetchPlaces(request: suspend () -> Result<List<Place>>) {
        placesJob?.cancel()
        if (_uiState.value.placesState !is PlacesState.Ready) {
            _uiState.update { it.copy(placesState = PlacesState.Loading) }
        }
        placesJob = viewModelScope.launch {
            request()
                .onSuccess { places ->
                    _uiState.update { it.copy(placesState = PlacesState.Ready(places)) }
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
        val ranked = PlaceRanking.rank(filtered, PlaceRanking.Context(isNavigating = isNav, aheadPlaceIds = aheadIds))
        val cap = if (isNav) MAX_VISIBLE_PLACES_NAVIGATING else MAX_VISIBLE_PLACES_BROWSING
        _uiState.update { it.copy(visiblePlaces = ranked.take(cap)) }
    }

    private fun Place.toSearchResult(): SearchResult = SearchResult(
        id = id,
        name = name,
        displayName = address ?: name,
        latitude = latitude,
        longitude = longitude,
        category = category.name,
        address = address,
    )

    private fun Throwable.toPlacesError(): PlacesError = when (this) {
        is PlaceException.NoNetwork -> PlacesError.NoNetwork
        is PlaceException.Timeout -> PlacesError.Timeout
        is PlaceException.Server -> PlacesError.ServerError
        is PlaceException.RateLimited -> PlacesError.RateLimited
        is PlaceException.Malformed -> PlacesError.Malformed
        else -> PlacesError.Malformed
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
    fun startNavigation() {
        val state = _uiState.value
        val route = (state.routeState as? RouteState.Ready)?.route
        if (route == null) return
        if (state.currentLocation == null) {
            _uiState.update { it.copy(navigationState = NavigationState.Error(NavigationError.LocationUnavailable)) }
            return
        }
        if (route.maneuvers.isEmpty()) {
            _uiState.update { it.copy(navigationState = NavigationState.Error(NavigationError.RouteUnavailable)) }
            return
        }

        session = NavigationSession(route)
        navigationFollowSuspended = false
        // Seed progress immediately from the last known fix so the banner has something to show
        // before the next location tick arrives.
        val seeded = state.currentLocation.let { session?.locate(it.toCoordinate()) }
        _uiState.update {
            it.copy(
                navigationFollowSuspended = false,
                navigationState = if (seeded != null) {
                    NavigationState.Active(withOffRouteFlag(seeded, false))
                } else {
                    NavigationState.Error(NavigationError.RouteUnavailable)
                },
            )
        }
        if (seeded != null) {
            followCameraWith(seeded)
            publishRouteProgress(seeded)
        }
        // Ranking/caps differ once navigating (ahead-of-driver, utility categories) — see
        // `recomputeVisiblePlaces`.
        recomputeVisiblePlaces()
    }

    /** Ends the trip and returns the map to plain route browsing. Never touches the route itself. */
    fun stopNavigation() {
        session = null
        navigationFollowSuspended = false
        rerouteInFlight = false
        _uiState.update {
            it.copy(navigationState = NavigationState.Idle, navigationFollowSuspended = false)
        }
        _routeProgressView.value = null
        recomputeVisiblePlaces()
    }

    /**
     * Advances the trip from a fresh fix. Called for every location update, so it must be cheap and
     * must never throw: a navigation bug should not be able to take down the map screen.
     */
    private fun onNavigationTick(location: UserLocation) {
        val activeSession = session ?: return
        val progress = activeSession.locate(location.toCoordinate()) ?: return
        publishRouteProgress(progress)

        if (progress.hasArrived) {
            session = null
            // Arrival ends the trip: release the AR anchors and remove the arrows.
            onNavigationEnded()
            // End the split too: on arrival the whole route is traveled, so keeping the
            // muted overlay would suggest there is still road ahead to drive.
            _routeProgressView.value = null
            _uiState.update { it.copy(navigationState = NavigationState.Arrived(progress)) }
            return
        }

        when (activeSession.updateOffRoute(progress)) {
            OffRouteDecision.OnRoute -> {
                _uiState.update { it.copy(navigationState = NavigationState.Active(withOffRouteFlag(progress, false))) }
                followCameraWith(progress)
            }
            OffRouteDecision.OffRouteConfirmed -> {
                // Flagged but not rerouting yet: hold the route and keep tracking. Still follow,
                // so the driver keeps seeing where they are relative to it.
                _uiState.update { it.copy(navigationState = NavigationState.OffRoute(withOffRouteFlag(progress, true))) }
                followCameraWith(progress)
            }
            OffRouteDecision.TriggerReroute -> {
                _uiState.update { it.copy(navigationState = NavigationState.Rerouting) }
                requestReroute(location)
            }
        }
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
                    rerouteInFlight = false
                    session = NavigationSession(route)
                    // Anchors and arrows were derived from the OLD route's geometry, so they are now
                    // stale. Notify before the new set is produced so two routes never overlap.
                    onRouteReplaced()
                    navigationFollowSuspended = false
                    val progress = _uiState.value.currentLocation?.let { session?.locate(it.toCoordinate()) }
                    _uiState.update {
                        it.copy(
                            navigationFollowSuspended = false,
                            routeState = RouteState.Ready(route),
                            navigationState = progress
                                ?.let { p -> NavigationState.Active(withOffRouteFlag(p, false)) }
                                ?: NavigationState.Ready,
                        )
                    }
                    progress?.let {
                        publishRouteProgress(it)
                        followCameraWith(it)
                    }
                    // The old route's corridor places are stale now; fetch against the new one.
                    fetchPlacesAlongRoute(route)
                }
                .onFailure {
                    rerouteInFlight = false
                    // Not a dead end: the old route is still valid and still displayed, so return
                    // to tracking against it and surface a one-time notice instead of an error.
                    session?.offRouteDetector?.reset()
                    val progress = _uiState.value.currentLocation?.let { loc -> session?.locate(loc.toCoordinate()) }
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

    /** Pushes the traveled/remaining split to the renderer. */
    private fun publishRouteProgress(progress: RouteProgress) {
        _routeProgressView.value = RouteProgressView(progress.snappedShapeIndex)
    }

    /** Moves the camera to the follow position, unless the user has taken over. */
    private fun followCameraWith(progress: RouteProgress) {
        if (navigationFollowSuspended) return
        val activeSession = session ?: return
        val target = activeSession.camera.targetFor(
            progress = progress,
            headingDegrees = _uiState.value.currentLocation?.bearingDegrees?.toDouble(),
            fallbackBearing = _uiState.value.cameraState.bearing,
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
        if (_uiState.value.locationPermission.isGranted && isAppVisible) {
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
                _uiState.update { it.copy(currentLocation = update.location, locationStatus = LocationStatus.Available) }
                centerOnFirstFix(update.location)
                // A route that was only missing an origin can proceed now that one exists.
                if (_uiState.value.routeState == RouteState.WaitingForLocation) requestRoute()
                // Navigation advances from every fix, but only once a trip is actually underway.
                if (_uiState.value.isNavigating) onNavigationTick(update.location)
                // Nearby places are location-driven only while not navigating (see Phase 9 region).
                maybeFetchNearbyPlaces(update.location)
            }
            LocationUpdate.ServicesDisabled -> {
                timeoutJob?.cancel()
                _uiState.update { it.copy(currentLocation = null, locationStatus = LocationStatus.ServicesDisabled) }
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

    // endregion

    private fun moveCamera(target: CameraState, durationMillis: Int, animated: Boolean = true) {
        // Optimistic update so a configuration change mid-animation restores the destination.
        _uiState.update { it.copy(cameraState = target) }
        commandChannel.trySend(MapCommand.MoveCamera(target, animated = animated, durationMillis = durationMillis))
    }

    private fun UserLocation.toCoordinate(): Coordinate = Coordinate(latitude, longitude)

    override fun onCleared() {
        commandChannel.close()
        eventChannel.close()
    }

    companion object {
        const val DEFAULT_FIX_TIMEOUT_MILLIS = 20_000L
        const val INITIAL_USER_ZOOM = 15.0
        const val RECENTER_MIN_ZOOM = 14.0
        const val DESTINATION_ZOOM = 15.0

        // Phase 9: contextual places.

        /** Nearby-fetch radius while browsing (not navigating). */
        const val NEARBY_RADIUS_METERS = 1_500.0

        /** Minimum movement before a nearby refetch — avoids refetching the same area continuously. */
        const val NEARBY_REFETCH_DISTANCE_METERS = 400.0

        /** How far off the active route a place can sit and still be surfaced. */
        const val ROUTE_CORRIDOR_METERS = 250.0

        /** Map-mode density cap while just browsing ("Map: moderate" — see ARCHITECTURE.md). */
        const val MAX_VISIBLE_PLACES_BROWSING = 40

        /** Map-mode density cap while navigating: fewer, more relevant places. */
        const val MAX_VISIBLE_PLACES_NAVIGATING = 20

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
