package com.openmaps.app.feature.map

import com.openmaps.app.core.environment.DrivingEnvironment
import com.openmaps.app.core.environment.EnvironmentProfiles
import com.openmaps.app.core.environment.RoadClass
import com.openmaps.app.core.environment.RoadClassRepository
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.LocationPermissionState
import com.openmaps.app.core.location.LocationRepository
import com.openmaps.app.core.location.LocationStatus
import com.openmaps.app.core.location.LocationUpdate
import com.openmaps.app.core.location.UserLocation
import com.openmaps.app.core.navigation.OffRouteDetector
import com.openmaps.app.core.places.Place
import com.openmaps.app.core.places.PlaceCategory
import com.openmaps.app.core.places.PlaceException
import com.openmaps.app.core.places.PlaceRepository
import com.openmaps.app.core.roadintelligence.RoadEvent
import com.openmaps.app.core.roadintelligence.RoadEventSeverity
import com.openmaps.app.core.roadintelligence.RoadEventType
import com.openmaps.app.core.roadintelligence.RoadIntelligenceException
import com.openmaps.app.core.roadintelligence.RoadIntelligenceRepository
import com.openmaps.app.core.roadintelligence.RouteAlert
import com.openmaps.app.core.roadintelligence.SpeedLimitInfo
import com.openmaps.app.core.roadintelligence.WeatherCondition
import com.openmaps.app.core.routing.Coordinate
import com.openmaps.app.core.routing.ManeuverType
import com.openmaps.app.core.routing.RouteBounds
import com.openmaps.app.core.routing.RouteManeuver
import com.openmaps.app.core.routing.RouteResult
import com.openmaps.app.core.routing.RoutingException
import com.openmaps.app.core.routing.RoutingRepository
import com.openmaps.app.feature.live.LiveReadiness
import com.openmaps.app.map.CameraState
import com.openmaps.app.map.MapCommand
import com.openmaps.app.map.MapError
import com.openmaps.app.map.MapEvent
import com.openmaps.app.map.MapMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MapViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val repository = FakeLocationRepository()
    private val routingRepository = FakeRoutingRepository()
    private val placesRepository = FakePlaceRepository()
    private val roadIntelligenceRepository = FakeRoadIntelligenceRepository()
    private val roadClassRepository = FakeRoadClassRepository()
    private lateinit var viewModel: MapViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = MapViewModel(
            repository,
            routingRepository,
            placesRepository,
            roadIntelligenceRepository,
            roadClassRepository,
            fixTimeoutMillis = TIMEOUT,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun firstFix_centersOnce_laterFixesOnlyMoveTheDot() = runTest(dispatcher) {
        val commands = collectCommands()
        startWithPermission()

        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))
        val move = commands.single() as MapCommand.MoveCamera
        assertEquals(40.0, move.target.latitude, EPS)
        assertEquals(-74.0, move.target.longitude, EPS)
        assertEquals(MapViewModel.INITIAL_USER_ZOOM, move.target.zoom, EPS)

        repository.emit(LocationUpdate.Fix(at(40.001, -74.001)))
        assertEquals("camera must not follow later fixes", 1, commands.size)
        assertEquals(40.001, viewModel.uiState.value.currentLocation!!.latitude, EPS)
        assertEquals(LocationStatus.Available, viewModel.uiState.value.locationStatus)
    }

    @Test
    fun userGestureBeforeFirstFix_suppressesAutoCenter() = runTest(dispatcher) {
        val commands = collectCommands()
        startWithPermission()

        viewModel.onMapEvent(MapEvent.UserGestureStarted)
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))

        assertTrue(commands.isEmpty())
        assertEquals(LocationStatus.Available, viewModel.uiState.value.locationStatus)
    }

    @Test
    fun recenter_animatesToUser_keepingBearingPitchAndAtLeastMinZoom() = runTest(dispatcher) {
        val commands = collectCommands()
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))
        commands.clear()

        // User pans/zooms/rotates away; the camera settles somewhere else.
        viewModel.onMapEvent(MapEvent.UserGestureStarted)
        viewModel.onMapEvent(MapEvent.CameraIdle(CameraState(10.0, 10.0, zoom = 6.0, bearing = 40.0, pitch = 30.0)))
        assertTrue("panning must not move the camera back", commands.isEmpty())

        viewModel.onRecenterClicked()
        val move = commands.single() as MapCommand.MoveCamera
        assertTrue(move.animated)
        assertEquals(40.0, move.target.latitude, EPS)
        assertEquals(-74.0, move.target.longitude, EPS)
        assertEquals(MapViewModel.RECENTER_MIN_ZOOM, move.target.zoom, EPS)
        assertEquals(40.0, move.target.bearing, EPS)
        assertEquals(30.0, move.target.pitch, EPS)
    }

    @Test
    fun recenter_withoutFix_triggersTheRightRecovery() = runTest(dispatcher) {
        val events = collectEvents()
        viewModel.onAppVisibilityChanged(true)

        viewModel.onPermissionStateChanged(LocationPermissionState.Denied)
        viewModel.onRecenterClicked()
        assertEquals(listOf<MapUiEvent>(MapUiEvent.RequestLocationPermission), events)

        events.clear()
        viewModel.onPermissionStateChanged(LocationPermissionState.PermanentlyDenied)
        viewModel.onRecenterClicked()
        assertEquals(listOf<MapUiEvent>(MapUiEvent.OpenAppSettings), events)
    }

    @Test
    fun notRequested_promptsOnlyOnce() = runTest(dispatcher) {
        val events = collectEvents()
        viewModel.onPermissionStateChanged(LocationPermissionState.NotRequested)
        viewModel.onPermissionStateChanged(LocationPermissionState.Unknown)
        viewModel.onPermissionStateChanged(LocationPermissionState.NotRequested)
        assertEquals(listOf<MapUiEvent>(MapUiEvent.RequestLocationPermission), events)
        assertEquals(LocationPrompt.AllowLocation, viewModel.uiState.value.locationPrompt)
    }

    @Test
    fun deniedAndBlocked_mapToPrompts_andMapStaysUsable() = runTest(dispatcher) {
        viewModel.onPermissionStateChanged(LocationPermissionState.Denied)
        assertEquals(LocationPrompt.Denied, viewModel.uiState.value.locationPrompt)
        viewModel.onPermissionStateChanged(LocationPermissionState.PermanentlyDenied)
        assertEquals(LocationPrompt.Blocked, viewModel.uiState.value.locationPrompt)
        assertNull(viewModel.uiState.value.error)
        assertNull(viewModel.uiState.value.currentLocation)
    }

    @Test
    fun servicesDisabled_clearsDot_showsPrompt_andOpensLocationSettings() = runTest(dispatcher) {
        val events = collectEvents()
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))

        repository.emit(LocationUpdate.ServicesDisabled)
        assertNull(viewModel.uiState.value.currentLocation)
        assertEquals(LocationPrompt.ServicesOff, viewModel.uiState.value.locationPrompt)

        viewModel.onLocationPromptAction()
        assertEquals(listOf<MapUiEvent>(MapUiEvent.OpenLocationSettings), events)

        repository.emit(LocationUpdate.ServicesEnabled)
        assertEquals(LocationStatus.Loading, viewModel.uiState.value.locationStatus)
        assertTrue(viewModel.uiState.value.isLocationLoading)
    }

    @Test
    fun noFixInTime_becomesUnavailable_andRetryRestartsCollection() = runTest(dispatcher) {
        startWithPermission()
        assertEquals(LocationStatus.Loading, viewModel.uiState.value.locationStatus)

        dispatcher.scheduler.advanceTimeBy(TIMEOUT + 1)
        assertEquals(LocationStatus.Unavailable, viewModel.uiState.value.locationStatus)
        assertEquals(LocationPrompt.Unavailable, viewModel.uiState.value.locationPrompt)

        viewModel.onLocationPromptAction()
        assertEquals(LocationStatus.Loading, viewModel.uiState.value.locationStatus)
        repository.emit(LocationUpdate.Fix(at(1.0, 2.0)))
        assertEquals(LocationStatus.Available, viewModel.uiState.value.locationStatus)
    }

    @Test
    fun permissionRevoked_clearsLocationAndStopsCollecting() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))
        assertEquals(1, repository.subscribers)

        viewModel.onPermissionStateChanged(LocationPermissionState.Denied)
        assertNull(viewModel.uiState.value.currentLocation)
        assertEquals(LocationStatus.Idle, viewModel.uiState.value.locationStatus)
        assertEquals(0, repository.subscribers)
    }

    @Test
    fun backgrounding_stopsLocationUpdates_andForegroundResumesThem() = runTest(dispatcher) {
        startWithPermission()
        assertEquals(1, repository.subscribers)

        viewModel.onAppVisibilityChanged(false)
        assertEquals(0, repository.subscribers)

        viewModel.onAppVisibilityChanged(true)
        assertEquals(1, repository.subscribers)
    }

    @Test
    fun mapFailure_showsUnavailable_andRetryClearsItAndAsksEngineToReload() = runTest(dispatcher) {
        val commands = collectCommands()
        viewModel.onMapEvent(MapEvent.StyleFailed("offline"))
        assertEquals(MapError.Unavailable("offline"), viewModel.uiState.value.error)

        viewModel.onRetryMap()
        assertNull(viewModel.uiState.value.error)
        assertEquals(listOf<MapCommand>(MapCommand.RetryStyle), commands)

        viewModel.onMapEvent(MapEvent.StyleLoaded)
        assertTrue(viewModel.uiState.value.isMapReady)
    }

    @Test
    fun dismissedPrompt_staysHidden_untilTheSituationChanges() = runTest(dispatcher) {
        viewModel.onPermissionStateChanged(LocationPermissionState.Denied)
        viewModel.onDismissLocationPrompt()
        assertNull(viewModel.uiState.value.locationPrompt)

        viewModel.onPermissionStateChanged(LocationPermissionState.PermanentlyDenied)
        assertEquals(LocationPrompt.Blocked, viewModel.uiState.value.locationPrompt)
    }

    @Test
    fun compass_resetsBearingOnly_andBearingNormalizes() = runTest(dispatcher) {
        val commands = collectCommands()
        viewModel.onMapEvent(MapEvent.CameraIdle(CameraState(1.0, 2.0, 12.0, bearing = 270.0, pitch = 25.0)))
        assertEquals(-90f, viewModel.compassBearing.value, 0.001f)

        viewModel.onCompassClicked()
        val move = commands.single() as MapCommand.MoveCamera
        assertEquals(0.0, move.target.bearing, EPS)
        assertEquals(25.0, move.target.pitch, EPS)
        assertEquals(12.0, move.target.zoom, EPS)
    }

    @Test
    fun modeToggle_switchesBetweenMapAndLive() = runTest(dispatcher) {
        assertEquals(MapMode.Map, viewModel.uiState.value.selectedMapMode)
        viewModel.onModeSelected(MapMode.Live)
        assertEquals(MapMode.Live, viewModel.uiState.value.selectedMapMode)
    }

    @Test
    fun destinationSelected_setsMarkerState_andFliesCameraToIt() = runTest(dispatcher) {
        val commands = collectCommands()
        val destination = place("empire-state", 40.7484, -73.9857)

        viewModel.onDestinationSelected(destination)

        assertEquals(destination, viewModel.uiState.value.selectedDestination)
        val move = commands.single() as MapCommand.MoveCamera
        assertTrue(move.animated)
        assertEquals(40.7484, move.target.latitude, EPS)
        assertEquals(-73.9857, move.target.longitude, EPS)
    }

    @Test
    fun selectingAnotherDestination_replacesThePrevious_noDuplicateState() = runTest(dispatcher) {
        val commands = collectCommands()
        val first = place("a", 1.0, 1.0)
        val second = place("b", 2.0, 2.0)

        viewModel.onDestinationSelected(first)
        viewModel.onDestinationSelected(second)

        assertEquals(second, viewModel.uiState.value.selectedDestination)
        assertEquals(2, commands.size)
        val lastMove = commands.last() as MapCommand.MoveCamera
        assertEquals(2.0, lastMove.target.latitude, EPS)
    }

    @Test
    fun destinationCleared_removesMarkerState() = runTest(dispatcher) {
        viewModel.onDestinationSelected(place("a", 1.0, 1.0))
        viewModel.onDestinationCleared()
        assertNull(viewModel.uiState.value.selectedDestination)
    }

    // region Routing

    @Test
    fun requestingDirections_withLocationAlready_computesTheRoute_andBecomesReady() = runTest(dispatcher) {
        val commands = collectCommands()
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))
        commands.clear()
        routingRepository.result = Result.success(route(distanceMeters = 1609.0, durationSeconds = 300.0))

        viewModel.selectAndRequestDirections(place("dest", 41.0, -75.0))

        assertEquals(listOf(Coordinate(40.0, -74.0)), routingRepository.originsReceived)
        assertEquals(listOf(Coordinate(41.0, -75.0)), routingRepository.destinationsReceived)
        val ready = viewModel.uiState.value.routeState as RouteState.Ready
        assertEquals(1609.0, ready.route.distanceMeters, EPS)
        assertEquals(300.0, ready.route.durationSeconds, EPS)
        // A FitBounds command follows the MoveCamera from onDestinationSelected.
        assertTrue(commands.any { it is MapCommand.FitBounds })
    }

    @Test
    fun requestingDirections_withoutLocation_waitsForLocation_andNeverCallsProvider() = runTest(dispatcher) {
        viewModel.selectAndRequestDirections(place("dest", 41.0, -75.0))

        assertEquals(RouteState.WaitingForLocation, viewModel.uiState.value.routeState)
        assertTrue(routingRepository.originsReceived.isEmpty())
    }

    @Test
    fun locationArrivingLater_triggersTheWaitingRoute() = runTest(dispatcher) {
        routingRepository.result = Result.success(route())
        viewModel.selectAndRequestDirections(place("dest", 41.0, -75.0))
        assertEquals(RouteState.WaitingForLocation, viewModel.uiState.value.routeState)

        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))

        assertTrue(viewModel.uiState.value.routeState is RouteState.Ready)
        assertEquals(1, routingRepository.originsReceived.size)
    }

    @Test
    fun noDestination_neverRequestsARoute() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))

        assertEquals(RouteState.Idle, viewModel.uiState.value.routeState)
        assertTrue(routingRepository.originsReceived.isEmpty())
    }

    @Test
    fun selectingAnotherDestination_replacesTheRoute_onlyFinalRequestLands() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))
        routingRepository.result = Result.success(route(distanceMeters = 111.0))

        viewModel.selectAndRequestDirections(place("a", 1.0, 1.0))
        routingRepository.result = Result.success(route(distanceMeters = 222.0))
        viewModel.selectAndRequestDirections(place("b", 2.0, 2.0))

        assertEquals(2, routingRepository.destinationsReceived.size)
        val ready = viewModel.uiState.value.routeState as RouteState.Ready
        assertEquals(222.0, ready.route.distanceMeters, EPS)
    }

    @Test
    fun destinationCleared_cancelsAndClearsTheRoute() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))
        routingRepository.result = Result.success(route())
        viewModel.selectAndRequestDirections(place("a", 1.0, 1.0))
        assertTrue(viewModel.uiState.value.routeState is RouteState.Ready)

        viewModel.onDestinationCleared()

        assertEquals(RouteState.Idle, viewModel.uiState.value.routeState)
    }

    @Test
    fun providerFailure_mapsToTypedRouteError() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))
        routingRepository.result = Result.failure(RoutingException.NoRouteFound)

        viewModel.selectAndRequestDirections(place("a", 1.0, 1.0))

        val error = viewModel.uiState.value.routeState as RouteState.Error
        assertEquals(RouteError.NoRouteFound, error.reason)
    }

    @Test
    fun networkFailure_mapsToNoNetworkRouteError() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))
        routingRepository.result = Result.failure(RoutingException.NoNetwork)

        viewModel.selectAndRequestDirections(place("a", 1.0, 1.0))

        val error = viewModel.uiState.value.routeState as RouteState.Error
        assertEquals(RouteError.NoNetwork, error.reason)
    }

    // endregion

    // region Navigation (Phase 4)

    @Test
    fun startNavigation_withoutARoute_doesNothing() = runTest(dispatcher) {
        viewModel.startNavigation()
        assertEquals(NavigationState.Idle, viewModel.uiState.value.navigationState)
    }

    @Test
    fun startNavigation_withAFixButNoRoute_staysIdle() = runTest(dispatcher) {
        // A location alone is not enough: there is no route to navigate.
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0005)))

        viewModel.startNavigation()

        assertEquals(NavigationState.Idle, viewModel.uiState.value.navigationState)
        assertFalse(viewModel.uiState.value.isNavigating)
    }

    @Test
    fun startNavigation_withARouteAndFix_becomesActive() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0005)))
        routingRepository.result = Result.success(navigableRoute())

        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()

        assertTrue(viewModel.uiState.value.isNavigating)
        val progress = viewModel.uiState.value.navigationProgress
        assertNotNull("navigation must report progress immediately", progress)
        assertFalse(progress!!.currentManeuver.instruction.isBlank())
    }

    @Test
    fun startNavigation_routeWithoutManeuvers_reportsRouteUnavailable() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0005)))
        routingRepository.result = Result.success(route()) // Phase 3 route: no maneuvers

        viewModel.selectAndRequestDirections(place("dest", 41.0, -75.0))
        viewModel.startNavigation()

        val error = viewModel.uiState.value.navigationState as NavigationState.Error
        assertEquals(NavigationError.RouteUnavailable, error.reason)
    }

    @Test
    fun navigating_forwardProgress_decreasesRemainingDistanceAndTime() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()

        val startRemaining = viewModel.uiState.value.navigationProgress!!.remainingDistanceMeters
        val startDuration = viewModel.uiState.value.navigationProgress!!.remainingDurationSeconds

        repository.emit(LocationUpdate.Fix(at(0.0, 0.004)))

        val progress = viewModel.uiState.value.navigationProgress!!
        assertTrue("distance must decrease", progress.remainingDistanceMeters < startRemaining)
        assertTrue("time must decrease", progress.remainingDurationSeconds < startDuration)
        assertTrue(progress.progressFraction > 0.0)
    }

    @Test
    fun navigating_publishesTraveledProgressForTheRenderer() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()
        // Starting seeds progress from the last fix, so a split already exists (at the start).
        assertEquals(0, viewModel.routeProgressView.value?.traveledShapeIndex)

        repository.emit(LocationUpdate.Fix(at(0.0, 0.004)))

        assertEquals("the split must advance as the driver moves", 4, viewModel.routeProgressView.value?.traveledShapeIndex)
    }

    @Test
    fun stopNavigation_clearsTheTraveledSplit() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.004)))
        assertNotNull(viewModel.routeProgressView.value)

        viewModel.stopNavigation()

        assertNull("no traveled overlay after the trip ends", viewModel.routeProgressView.value)
    }

    @Test
    fun stopNavigation_returnsToIdleAndClearsTheSplit() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.004)))
        assertNotNull(viewModel.routeProgressView.value)

        viewModel.stopNavigation()

        assertEquals(NavigationState.Idle, viewModel.uiState.value.navigationState)
        assertNull(viewModel.routeProgressView.value)
        // The route itself survives stopping: browsing back to it should not need a refetch.
        assertTrue(viewModel.uiState.value.routeState is RouteState.Ready)
    }

    @Test
    fun stoppedNavigation_ignoresLaterLocationFixes() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()
        viewModel.stopNavigation()
        val snapshot = viewModel.uiState.value.navigationProgress

        repository.emit(LocationUpdate.Fix(at(0.0, 0.006)))

        assertEquals(snapshot, viewModel.uiState.value.navigationProgress)
    }

    @Test
    fun clearingDestination_endsTheTrip() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()

        viewModel.onDestinationCleared()

        assertEquals(NavigationState.Idle, viewModel.uiState.value.navigationState)
    }

    @Test
    fun navigating_toTheDestination_reportsArrival() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()

        repository.emit(LocationUpdate.Fix(at(0.0, 0.009)))

        assertTrue(viewModel.uiState.value.navigationState is NavigationState.Arrived)
        assertNull("the traveled split is cleared on arrival", viewModel.routeProgressView.value)
    }

    @Test
    fun briefDeviation_doesNotReroute() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()
        val requestsBefore = routingRepository.destinationsReceived.size

        // A single fix 100 m off the line (a wrong turn, or urban GPS drift) must not cost a request.
        repository.emit(LocationUpdate.Fix(at(0.001, 0.003)))

        assertEquals(
            "a single deviation must never trigger a reroute",
            requestsBefore,
            routingRepository.destinationsReceived.size,
        )
        assertTrue(viewModel.uiState.value.navigationState is NavigationState.Active)
    }

    @Test
    fun persistentDeviation_triggersExactlyOneReroute() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()
        val requestsBefore = routingRepository.destinationsReceived.size

        repeat(OffRouteDetector.DEFAULT_REROUTE_SAMPLES) {
            repository.emit(LocationUpdate.Fix(at(0.002, 0.003)))
        }

        assertEquals(1, routingRepository.destinationsReceived.size - requestsBefore)
        // The reroute keeps driving the same destination.
        assertEquals(Coordinate(0.009, 0.0), routingRepository.destinationsReceived.last())
    }

    @Test
    fun persistentDeviation_notifiesRouteReplaced_soArrowsAndAnchorsAreDropped() = runTest(dispatcher) {
        // Phase 7 anchors and Phase 8 arrows are derived from the route geometry. If the reroute
        // does not tell Live, the previous road's anchors keep being refined and its arrows keep
        // being drawn — ghost guidance pointing down a road the driver just left.
        var notifications = 0
        viewModel.onRouteReplaced = { notifications++ }

        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()
        assertEquals("starting navigation is not a route replacement", 0, notifications)

        repeat(OffRouteDetector.DEFAULT_REROUTE_SAMPLES) {
            repository.emit(LocationUpdate.Fix(at(0.002, 0.003)))
        }

        assertEquals("the reroute must be announced exactly once", 1, notifications)
    }

    @Test
    fun arriving_notifiesNavigationEnded_soAnchorsAreReleased() = runTest(dispatcher) {
        // The trip is over: keeping world anchors attached would have ARCore refining a road nobody
        // is driving, and keeping arrows would leave guidance on the camera at the destination.
        var ended = 0
        viewModel.onNavigationEnded = { ended++ }

        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()
        assertEquals(0, ended)

        repository.emit(LocationUpdate.Fix(at(0.0, 0.009)))

        assertTrue(viewModel.uiState.value.navigationState is NavigationState.Arrived)
        assertEquals("arrival must release the AR content exactly once", 1, ended)
    }

    @Test
    fun failedReroute_keepsTheOldRouteAndStaysNavigable() = runTest(dispatcher) {
        val events = collectEvents()
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()

        routingRepository.result = Result.failure(RoutingException.NoNetwork)
        repeat(OffRouteDetector.DEFAULT_REROUTE_SAMPLES) {
            repository.emit(LocationUpdate.Fix(at(0.002, 0.003)))
        }

        // A network blip mid-trip must not dead-end the trip.
        assertEquals(listOf<MapUiEvent>(MapUiEvent.RerouteFailed), events)
        assertTrue(viewModel.uiState.value.navigationState is NavigationState.OffRoute)
        assertTrue("the original route is still displayed", viewModel.uiState.value.routeState is RouteState.Ready)
    }

    @Test
    fun successfulReroute_swapsInTheNewRouteAndResumesProgress() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()

        val replacement = navigableRoute(distanceMeters = 555.0)
        routingRepository.result = Result.success(replacement)
        repeat(OffRouteDetector.DEFAULT_REROUTE_SAMPLES) {
            repository.emit(LocationUpdate.Fix(at(0.002, 0.003)))
        }

        val ready = viewModel.uiState.value.routeState as RouteState.Ready
        assertEquals(555.0, ready.route.distanceMeters, EPS)
        assertTrue(viewModel.uiState.value.navigationState is NavigationState.Active)
    }

    @Test
    fun navigation_followsTheCamera_untillTheUserTakesOver() = runTest(dispatcher) {
        val commands = collectCommands()
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()
        commands.clear()

        repository.emit(LocationUpdate.Fix(at(0.0, 0.003)))
        assertTrue("follow must move the camera", commands.isNotEmpty())

        // A user pan suspends follow...
        viewModel.onMapEvent(MapEvent.UserGestureStarted)
        commands.clear()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.006)))
        assertTrue("a panned camera must not be yanked back", commands.isEmpty())
        assertTrue(viewModel.uiState.value.navigationFollowSuspended)
    }

    @Test
    fun recenter_duringNavigation_resumesFollow() = runTest(dispatcher) {
        val commands = collectCommands()
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()
        viewModel.onMapEvent(MapEvent.UserGestureStarted)
        commands.clear()

        viewModel.onRecenterClicked()

        assertFalse(viewModel.uiState.value.navigationFollowSuspended)
        assertTrue(commands.isNotEmpty())
        // And follow continues on the next fix rather than freezing where the user panned to.
        commands.clear()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.007)))
        assertTrue("follow must resume after recenter", commands.isNotEmpty())
    }

    @Test
    fun notNavigating_recenterKeepsThePhase1BehaviourAndNeverSetsFollowFlags() = runTest(dispatcher) {
        val commands = collectCommands()
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))
        commands.clear()

        viewModel.onRecenterClicked()

        val move = commands.single() as MapCommand.MoveCamera
        assertEquals(40.0, move.target.latitude, EPS)
        assertEquals(-74.0, move.target.longitude, EPS)
        // "At least the minimum zoom": the camera was already at INITIAL_USER_ZOOM, so recenter
        // keeps it rather than forcing a zoom change. (Phase 1 behaviour, unchanged.)
        assertEquals(MapViewModel.INITIAL_USER_ZOOM, move.target.zoom, EPS)
        assertTrue(move.target.zoom >= MapViewModel.RECENTER_MIN_ZOOM)
        assertFalse(viewModel.uiState.value.navigationFollowSuspended)
    }

    @Test
    fun userGestureWhileNotNavigating_doesNotArmFollowSuspension() = runTest(dispatcher) {
        startWithPermission()
        viewModel.onMapEvent(MapEvent.UserGestureStarted)
        assertFalse("Phase 1 pans must not arm the navigation flag", viewModel.uiState.value.navigationFollowSuspended)
    }

    @Test
    fun navigating_thenPanning_thenStartingANewTrip_startsInFollow() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()
        viewModel.onMapEvent(MapEvent.UserGestureStarted)
        viewModel.stopNavigation()

        // The suspension must not leak into the next trip.
        assertFalse(viewModel.uiState.value.navigationFollowSuspended)
    }

    @Test
    fun modeToggle_duringNavigation_doesNotDisturbTheTrip() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()

        viewModel.onModeSelected(MapMode.Live)

        assertEquals(MapMode.Live, viewModel.uiState.value.selectedMapMode)
        assertTrue("switching modes must not end the trip", viewModel.uiState.value.isNavigating)
    }

    @Test
    fun canStartNavigation_requiresBothARouteAndAFix() = runTest(dispatcher) {
        assertFalse("no route", viewModel.uiState.value.canStartNavigation)

        startWithPermission()
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        assertFalse("route but no fix", viewModel.uiState.value.canStartNavigation)

        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        assertTrue(viewModel.uiState.value.canStartNavigation)

        viewModel.startNavigation()
        assertFalse("cannot start twice", viewModel.uiState.value.canStartNavigation)
    }

    // endregion

    // region Contextual places (Phase 9)

    @Test
    fun nearbyFetch_firstFixFetches_smallMovementDoesNotRefetch_bigMovementDoes() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))
        assertEquals(1, placesRepository.nearbyCenters.size)

        // ~11 m away: well under the refetch threshold.
        repository.emit(LocationUpdate.Fix(at(40.0001, -74.0)))
        assertEquals("small movement must not refetch", 1, placesRepository.nearbyCenters.size)

        // ~1.1 km away: comfortably over MapViewModel.NEARBY_REFETCH_DISTANCE_METERS.
        repository.emit(LocationUpdate.Fix(at(40.01, -74.0)))
        assertEquals("large movement must refetch", 2, placesRepository.nearbyCenters.size)
    }

    @Test
    fun nearbyFetch_doesNotRunWhileNavigating() = runTest(dispatcher) {
        startWithPermission()
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        viewModel.startNavigation()
        val fetchesSoFar = placesRepository.nearbyCenters.size

        // A big move while navigating must not trigger the nearby path; the route-corridor fetch
        // (already fired once from the route becoming Ready) is what drives Places during a trip.
        repository.emit(LocationUpdate.Fix(at(0.01, 0.01)))
        assertEquals(fetchesSoFar, placesRepository.nearbyCenters.size)
    }

    @Test
    fun routeReady_fetchesPlacesAlongTheRouteGeometry() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0))) // an origin, so requestRoute() fires immediately
        val readyRoute = navigableRoute()
        routingRepository.result = Result.success(readyRoute)
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        assertEquals(1, placesRepository.alongRouteCalls.size)
        assertEquals(readyRoute.geometry, placesRepository.alongRouteCalls.single())
    }

    @Test
    fun categoryFilterChanged_filtersClientSideOnly_neverRefetches() = runTest(dispatcher) {
        placesRepository.nearbyResult = Result.success(
            listOf(testPlace("gas", PlaceCategory.Gas), testPlace("cafe", PlaceCategory.Coffee)),
        )
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))
        assertEquals(setOf("gas", "cafe"), viewModel.uiState.value.visiblePlaces.map { it.id }.toSet())
        val fetchesSoFar = placesRepository.nearbyCenters.size

        viewModel.onCategoryFilterChanged(setOf(PlaceCategory.Gas))
        assertEquals(listOf("gas"), viewModel.uiState.value.visiblePlaces.map { it.id })
        assertEquals("a filter toggle must never trigger a network fetch", fetchesSoFar, placesRepository.nearbyCenters.size)
    }

    @Test
    fun placeSelected_thenNavigate_becomesTheDestinationAndRequestsARoute() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0))) // an origin, so requestRoute() actually calls out
        routingRepository.result = Result.success(route())
        val target = testPlace("gas-1", PlaceCategory.Gas, latitude = 40.5, longitude = -74.5)

        viewModel.onPlaceSelected(target)
        assertEquals(target, viewModel.uiState.value.selectedPlace)

        viewModel.onNavigateToPlace(target)
        assertNull("navigating away clears the details sheet", viewModel.uiState.value.selectedPlace)
        assertEquals(40.5, viewModel.uiState.value.selectedDestination!!.latitude, EPS)
        assertEquals(-74.5, viewModel.uiState.value.selectedDestination!!.longitude, EPS)
        assertTrue(routingRepository.destinationsReceived.any { it.latitude == 40.5 && it.longitude == -74.5 })
    }

    @Test
    fun placesProviderError_keepsPreviousReadyPlaces_navigationUnaffected() = runTest(dispatcher) {
        placesRepository.nearbyResult = Result.success(listOf(testPlace("gas", PlaceCategory.Gas)))
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))
        assertTrue(viewModel.uiState.value.placesState is PlacesState.Ready)

        placesRepository.nearbyResult = Result.failure(PlaceException.NoNetwork)
        repository.emit(LocationUpdate.Fix(at(40.01, -74.0))) // far enough to refetch
        assertTrue("a failed refresh must not clear good data", viewModel.uiState.value.placesState is PlacesState.Ready)
        assertEquals(listOf("gas"), viewModel.uiState.value.visiblePlaces.map { it.id })
        // The rest of the map keeps working: a Places failure is additive, never a core dependency.
        assertEquals(LocationStatus.Available, viewModel.uiState.value.locationStatus)
    }

    @Test
    fun visiblePlaces_capsAreLowerWhileNavigating() = runTest(dispatcher) {
        val many = (1..25).map { testPlace("p$it", PlaceCategory.Coffee) }
        placesRepository.nearbyResult = Result.success(many)
        placesRepository.alongRouteResult = Result.success(many)
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        assertEquals(25, viewModel.uiState.value.visiblePlaces.size)

        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()
        assertEquals(MapViewModel.MAX_VISIBLE_PLACES_NAVIGATING, viewModel.uiState.value.visiblePlaces.size)
    }

    // endregion

    // region Road intelligence (Phase 10)

    @Test
    fun routeReady_fetchesRoadEventsAlongTheRouteGeometry() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0))) // an origin, so requestRoute() fires immediately
        val readyRoute = navigableRoute()
        routingRepository.result = Result.success(readyRoute)
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        assertEquals(1, roadIntelligenceRepository.eventsAlongRouteCalls.size)
        assertEquals(readyRoute.geometry, roadIntelligenceRepository.eventsAlongRouteCalls.single())
    }

    @Test
    fun roadEventsProviderError_keepsPreviousEvents_navigationUnaffected() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        roadIntelligenceRepository.eventsResult = Result.success(listOf(testHazard("h1")))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        assertEquals(listOf("h1"), viewModel.uiState.value.roadIntelligenceState.events.map { it.id })

        roadIntelligenceRepository.eventsResult = Result.failure(RoadIntelligenceException.NoNetwork)
        // Trigger a reroute to force a refetch attempt.
        repeat(OffRouteDetector.DEFAULT_REROUTE_SAMPLES) {
            repository.emit(LocationUpdate.Fix(at(0.002, 0.003)))
        }
        assertEquals(
            "a failed refresh must not clear good data",
            listOf("h1"),
            viewModel.uiState.value.roadIntelligenceState.events.map { it.id },
        )
        assertEquals(LocationStatus.Available, viewModel.uiState.value.locationStatus)
    }

    @Test
    fun weatherFetch_gatedByMovementDistance() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))
        assertEquals(1, roadIntelligenceRepository.weatherCalls.size)

        // ~11 m: well under WEATHER_REFETCH_DISTANCE_METERS.
        repository.emit(LocationUpdate.Fix(at(40.0001, -74.0)))
        assertEquals("small movement must not refetch weather", 1, roadIntelligenceRepository.weatherCalls.size)

        // ~11 km: comfortably over MapViewModel.WEATHER_REFETCH_DISTANCE_METERS (8 km).
        repository.emit(LocationUpdate.Fix(at(40.1, -74.0)))
        assertEquals("large movement must refetch weather", 2, roadIntelligenceRepository.weatherCalls.size)
    }

    @Test
    fun speedLimitFetch_gatedByMovementDistance() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))
        assertEquals(1, roadIntelligenceRepository.speedLimitCalls.size)

        // ~1.1 m: well under SPEED_LIMIT_REFETCH_DISTANCE_METERS.
        repository.emit(LocationUpdate.Fix(at(40.00001, -74.0)))
        assertEquals("tiny movement must not refetch the speed limit", 1, roadIntelligenceRepository.speedLimitCalls.size)

        // ~222 m: over MapViewModel.SPEED_LIMIT_REFETCH_DISTANCE_METERS (150 m).
        repository.emit(LocationUpdate.Fix(at(40.002, -74.0)))
        assertEquals("a block-sized movement must refetch the speed limit", 2, roadIntelligenceRepository.speedLimitCalls.size)
    }

    @Test
    fun currentSpeed_populatedFromADirectReading() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0, speedMetersPerSecond = 12.5f)))
        assertEquals(12.5f, viewModel.uiState.value.roadIntelligenceState.currentSpeedMetersPerSecond!!, 1e-6f)
    }

    @Test
    fun currentSpeed_nullWhenProviderHasNoReading() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))
        assertNull(viewModel.uiState.value.roadIntelligenceState.currentSpeedMetersPerSecond)
    }

    @Test
    fun hazardAlert_requiresTwoConsecutiveTicksBeforeActivating() = runTest(dispatcher) {
        // Sits 333.96 m into the route (3 steps of ~111.32 m along `navigableRoute()`'s geometry),
        // well inside the default 800 m collision-class approach distance from the origin.
        roadIntelligenceRepository.eventsResult = Result.success(
            listOf(testHazard("h1", routeDistanceMeters = 333.96, severity = RoadEventSeverity.High)),
        )
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))

        viewModel.startNavigation() // first candidate tick
        assertTrue(
            "one tick must not be enough to confirm a hazard alert",
            viewModel.uiState.value.roadIntelligenceState.activeAlerts.none { it is RouteAlert.Hazard },
        )

        repository.emit(LocationUpdate.Fix(at(0.0, 0.0002))) // second consecutive candidate tick
        val alerts = viewModel.uiState.value.roadIntelligenceState.activeAlerts
        assertTrue("a high-severity hazard must appear as RouteAlert.Hazard", alerts.any { it is RouteAlert.Hazard })
    }

    @Test
    fun hazardBehindTheDriver_isNeverActivated() = runTest(dispatcher) {
        // Sits only 333.96 m into the route; the driver below is seeded well past it.
        roadIntelligenceRepository.eventsResult = Result.success(
            listOf(testHazard("h1", routeDistanceMeters = 333.96, severity = RoadEventSeverity.High)),
        )
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        // Move to ~667 m along the route (0.006 deg * 111,320 m/deg) before starting navigation, so
        // the very first progress snapshot already has the hazard behind it.
        repository.emit(LocationUpdate.Fix(at(0.0, 0.006)))

        viewModel.startNavigation()
        repeat(5) { repository.emit(LocationUpdate.Fix(at(0.0, 0.006))) }

        assertTrue(
            "an event behind the driver must never become an active alert",
            viewModel.uiState.value.roadIntelligenceState.activeAlerts.none { it is RouteAlert.Hazard },
        )
    }

    @Test
    fun stopNavigation_clearsActiveHazardAlerts() = runTest(dispatcher) {
        roadIntelligenceRepository.eventsResult = Result.success(
            listOf(testHazard("h1", routeDistanceMeters = 333.96, severity = RoadEventSeverity.High)),
        )
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0002)))
        assertTrue(viewModel.uiState.value.roadIntelligenceState.activeAlerts.any { it is RouteAlert.Hazard })

        viewModel.stopNavigation()
        assertTrue(
            "ending the trip must drop route-relative hazard alerts",
            viewModel.uiState.value.roadIntelligenceState.activeAlerts.none { it is RouteAlert.Hazard },
        )
    }

    @Test
    fun reroute_refetchesRoadEventsAndDropsStaleHazardAlerts() = runTest(dispatcher) {
        roadIntelligenceRepository.eventsResult = Result.success(
            listOf(testHazard("stale", routeDistanceMeters = 333.96, severity = RoadEventSeverity.High)),
        )
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.selectAndRequestDirections(place("dest", 0.009, 0.0))
        viewModel.startNavigation()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0002)))
        assertTrue(viewModel.uiState.value.roadIntelligenceState.activeAlerts.any { it is RouteAlert.Hazard })
        val fetchesBefore = roadIntelligenceRepository.eventsAlongRouteCalls.size

        // The replacement route has no hazards of its own.
        roadIntelligenceRepository.eventsResult = Result.success(emptyList())
        routingRepository.result = Result.success(navigableRoute(distanceMeters = 555.0))
        repeat(OffRouteDetector.DEFAULT_REROUTE_SAMPLES) {
            repository.emit(LocationUpdate.Fix(at(0.002, 0.003)))
        }

        assertEquals(
            "a reroute must refetch hazards against the new route",
            fetchesBefore + 1,
            roadIntelligenceRepository.eventsAlongRouteCalls.size,
        )
        assertTrue(
            "the stale hazard from the old route must not survive a reroute",
            viewModel.uiState.value.roadIntelligenceState.activeAlerts.none { it is RouteAlert.Hazard },
        )
    }

    private fun testHazard(
        id: String,
        routeDistanceMeters: Double? = null,
        severity: RoadEventSeverity = RoadEventSeverity.High,
    ) = RoadEvent(
        id = id,
        type = RoadEventType.Crash,
        latitude = 0.0,
        longitude = 0.0,
        routeDistanceMeters = routeDistanceMeters,
        severity = severity,
        title = "Crash",
    )

    // endregion

    // region Navigation UX (Phase 11)

    @Test
    fun selectingDestination_doesNotRequestARoute() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        viewModel.onDestinationSelected(place("dest", 0.009, 0.0))

        assertEquals("selection alone must never request a route", 0, routingRepository.destinationsReceived.size)
        assertEquals(RouteState.Idle, viewModel.uiState.value.routeState)
        assertTrue(viewModel.uiState.value.isShowingDestinationDetails)
    }

    @Test
    fun onDirectionsRequested_computesTheRouteAndShowsRoutePreview() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(route())
        viewModel.onDestinationSelected(place("dest", 0.009, 0.0))

        viewModel.onDirectionsRequested()

        assertEquals(1, routingRepository.destinationsReceived.size)
        assertTrue(viewModel.uiState.value.routeState is RouteState.Ready)
        assertFalse(
            "Route Preview must replace Destination Details once Directions is tapped",
            viewModel.uiState.value.isShowingDestinationDetails,
        )
    }

    @Test
    fun selectingANewDestination_afterAPreviousRouteExisted_showsDetailsNotStaleRoutePreview() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(route())
        viewModel.onDestinationSelected(place("first", 0.009, 0.0))
        viewModel.onDirectionsRequested()
        assertTrue(viewModel.uiState.value.routeState is RouteState.Ready)

        viewModel.onDestinationSelected(place("second", 0.02, 0.0))

        assertEquals(
            "a freshly selected destination must not inherit the previous one's route",
            RouteState.Idle,
            viewModel.uiState.value.routeState,
        )
        assertTrue(viewModel.uiState.value.isShowingDestinationDetails)
    }

    @Test
    fun onNavigateToPlace_stillGoesStraightToRoutePreview_oneTapBehaviorPreserved() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(route())

        viewModel.onNavigateToPlace(testPlace("gas-1", PlaceCategory.Gas, latitude = 0.009, longitude = 0.0))

        assertTrue(
            "a place's details are already shown before Navigate is reachable, so one tap must act like Directions",
            viewModel.uiState.value.routeState is RouteState.Ready,
        )
    }

    @Test
    fun startNavigation_automaticallySwitchesToLiveMode() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.onDestinationSelected(place("dest", 0.009, 0.0))
        viewModel.onDirectionsRequested()
        assertEquals(MapMode.Map, viewModel.uiState.value.selectedMapMode)

        viewModel.startNavigation()

        assertEquals(MapMode.Live, viewModel.uiState.value.selectedMapMode)
        assertTrue(viewModel.uiState.value.navigationState is NavigationState.Active)
    }

    @Test
    fun startNavigation_thatFailsToStart_doesNotSwitchToLive() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.onDestinationSelected(place("dest", 0.009, 0.0))
        viewModel.onDirectionsRequested()
        assertTrue(viewModel.uiState.value.routeState is RouteState.Ready)
        // The route is ready, but the location fix is lost right before Start is pressed.
        repository.emit(LocationUpdate.ServicesDisabled)

        viewModel.startNavigation()

        assertEquals(MapMode.Map, viewModel.uiState.value.selectedMapMode)
        assertTrue(viewModel.uiState.value.navigationState is NavigationState.Error)
    }

    @Test
    fun manualMapSelection_preservesNavigation_doesNotRestartTheRoute() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.onDestinationSelected(place("dest", 0.009, 0.0))
        viewModel.onDirectionsRequested()
        viewModel.startNavigation()
        val requestsBefore = routingRepository.destinationsReceived.size

        viewModel.onModeSelected(MapMode.Map)

        assertEquals(MapMode.Map, viewModel.uiState.value.selectedMapMode)
        assertTrue("navigation must continue in Map mode", viewModel.uiState.value.navigationState is NavigationState.Active)
        assertEquals("switching mode must never re-request the route", requestsBefore, routingRepository.destinationsReceived.size)
    }

    @Test
    fun returningToLive_resumesTheSameProgress_doesNotRestartNavigation() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.onDestinationSelected(place("dest", 0.009, 0.0))
        viewModel.onDirectionsRequested()
        viewModel.startNavigation()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.003)))
        viewModel.onModeSelected(MapMode.Map)
        val progressBeforeReturn = viewModel.uiState.value.navigationProgress

        viewModel.onModeSelected(MapMode.Live)

        assertEquals(MapMode.Live, viewModel.uiState.value.selectedMapMode)
        assertEquals(
            "the same NavigationSession must still be tracking — progress is unchanged by the switch",
            progressBeforeReturn?.snappedShapeIndex,
            viewModel.uiState.value.navigationProgress?.snappedShapeIndex,
        )
    }

    @Test
    fun onLiveReadinessChanged_unusableWhileArmed_fallsBackToMapWithAReason() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.onDestinationSelected(place("dest", 0.009, 0.0))
        viewModel.onDirectionsRequested()
        viewModel.startNavigation()
        assertEquals(MapMode.Live, viewModel.uiState.value.selectedMapMode)

        viewModel.onLiveReadinessChanged(LiveReadiness.Unusable, "Camera access denied — continuing in Map")

        assertEquals(MapMode.Map, viewModel.uiState.value.selectedMapMode)
        assertEquals("Camera access denied — continuing in Map", viewModel.uiState.value.liveFallbackMessage)
        assertTrue("navigation must continue despite the fallback", viewModel.uiState.value.navigationState is NavigationState.Active)
    }

    @Test
    fun onLiveReadinessChanged_pendingWhileArmed_staysInLiveWithNoMessage() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.onDestinationSelected(place("dest", 0.009, 0.0))
        viewModel.onDirectionsRequested()
        viewModel.startNavigation()

        viewModel.onLiveReadinessChanged(LiveReadiness.Pending, null)

        assertEquals("the permission dialog may still be in flight; must not bounce back yet", MapMode.Live, viewModel.uiState.value.selectedMapMode)
        assertNull(viewModel.uiState.value.liveFallbackMessage)
    }

    @Test
    fun onLiveReadinessChanged_readyWhileArmed_disarmsAndStaysInLive() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.onDestinationSelected(place("dest", 0.009, 0.0))
        viewModel.onDirectionsRequested()
        viewModel.startNavigation()

        viewModel.onLiveReadinessChanged(LiveReadiness.Ready, null)
        // A later Unusable signal must now be ignored: disarmed after the first Ready resolution.
        viewModel.onLiveReadinessChanged(LiveReadiness.Unusable, "should be ignored")

        assertEquals(MapMode.Live, viewModel.uiState.value.selectedMapMode)
        assertNull(viewModel.uiState.value.liveFallbackMessage)
    }

    @Test
    fun onLiveReadinessChanged_ignoredWhenNotArmed_manualReentryUnaffected() = runTest(dispatcher) {
        // A manual Live visit (never auto-armed by `startNavigation`) must not be yanked back to Map
        // just because the camera happens to be unusable — the existing recoverable panel must get
        // its chance, exactly as before Phase 11.
        viewModel.onModeSelected(MapMode.Live)

        viewModel.onLiveReadinessChanged(LiveReadiness.Unusable, "denied")

        assertEquals(MapMode.Live, viewModel.uiState.value.selectedMapMode)
        assertNull(viewModel.uiState.value.liveFallbackMessage)
    }

    @Test
    fun manualModeSelection_disarmsTheAutomaticFallback() = runTest(dispatcher) {
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.onDestinationSelected(place("dest", 0.009, 0.0))
        viewModel.onDirectionsRequested()
        viewModel.startNavigation()

        // The user manually leaves Live before camera readiness ever resolves, then manually
        // returns — a later Unusable signal must now be ignored (disarmed by the manual switch),
        // or it would incorrectly bounce this manual re-entry straight back to Map.
        viewModel.onModeSelected(MapMode.Map)
        viewModel.onModeSelected(MapMode.Live)
        viewModel.onLiveReadinessChanged(LiveReadiness.Unusable, "should be ignored")

        assertEquals(MapMode.Live, viewModel.uiState.value.selectedMapMode)
        assertNull(viewModel.uiState.value.liveFallbackMessage)
    }

    // endregion

    // region Environment (Phase 11)

    @Test
    fun drivingEnvironment_defaultsToUnknownWhileNotNavigating() = runTest(dispatcher) {
        startWithPermission()
        roadClassRepository.result = Result.success(RoadClass.Motorway)
        repository.emit(LocationUpdate.Fix(at(40.0, -74.0)))

        assertEquals(DrivingEnvironment.Unknown, viewModel.uiState.value.drivingEnvironment)
        assertEquals(EnvironmentProfiles.unknown, viewModel.uiState.value.activeProfile)
    }

    @Test
    fun drivingEnvironment_resolvesToHighway_fromRealRoadClassSignalWhileNavigating() = runTest(dispatcher) {
        roadClassRepository.result = Result.success(RoadClass.Motorway)
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.onDestinationSelected(place("dest", 0.009, 0.0))
        viewModel.onDirectionsRequested()
        viewModel.startNavigation()

        // Several ticks, each far enough apart to refetch road class, so the hysteresis has every
        // chance to confirm — the exact tick-count guarantee is covered by EnvironmentHysteresisTest.
        repeat(5) { i -> repository.emit(LocationUpdate.Fix(at(0.0, 0.0001 * i))) }

        assertEquals(DrivingEnvironment.Highway, viewModel.uiState.value.drivingEnvironment)
        assertEquals(EnvironmentProfiles.highway, viewModel.uiState.value.activeProfile)
    }

    @Test
    fun drivingEnvironment_resetsToUnknownOnStopNavigation() = runTest(dispatcher) {
        roadClassRepository.result = Result.success(RoadClass.Motorway)
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.onDestinationSelected(place("dest", 0.009, 0.0))
        viewModel.onDirectionsRequested()
        viewModel.startNavigation()
        repeat(5) { i -> repository.emit(LocationUpdate.Fix(at(0.0, 0.0001 * i))) }
        assertEquals(DrivingEnvironment.Highway, viewModel.uiState.value.drivingEnvironment)

        viewModel.stopNavigation()

        assertEquals(DrivingEnvironment.Unknown, viewModel.uiState.value.drivingEnvironment)
    }

    @Test
    fun activeProfile_tunesVisiblePlacesCapWhileNavigating() = runTest(dispatcher) {
        roadClassRepository.result = Result.success(RoadClass.Motorway)
        val many = (1..30).map { testPlace("p$it", PlaceCategory.Coffee) }
        placesRepository.alongRouteResult = Result.success(many)
        startWithPermission()
        repository.emit(LocationUpdate.Fix(at(0.0, 0.0)))
        routingRepository.result = Result.success(navigableRoute())
        viewModel.onDestinationSelected(place("dest", 0.009, 0.0))
        viewModel.onDirectionsRequested()
        viewModel.startNavigation()

        repeat(5) { i -> repository.emit(LocationUpdate.Fix(at(0.0, 0.0001 * i))) }
        assertEquals(DrivingEnvironment.Highway, viewModel.uiState.value.drivingEnvironment)

        assertEquals(
            "Highway's lower POI cap must be in effect, not the Unknown-profile default",
            EnvironmentProfiles.highway.poiLimitNavigating,
            viewModel.uiState.value.visiblePlaces.size,
        )
    }

    // endregion

    // region helpers

    /**
     * The Phase 11 selection flow: pick a result, then ask for Directions.
     *
     * Picking a destination deliberately does **not** request a route any more — it opens Destination
     * Details, and Directions is the explicit next step. Tests that go on to assert about the route
     * therefore have to take that second step too.
     *
     * Going through one helper rather than sprinkling `onDirectionsRequested()` everywhere is
     * deliberate: it keeps every test reading like the flow a user actually performs, and makes the
     * old one-step assumption impossible to reintroduce silently. Tests that are specifically about
     * the two steps being separate call the ViewModel methods directly.
     */
    private fun MapViewModel.selectAndRequestDirections(result: SearchResult) {
        onDestinationSelected(result)
        onDirectionsRequested()
    }

    private fun startWithPermission() {
        viewModel.onAppVisibilityChanged(true)
        viewModel.onPermissionStateChanged(LocationPermissionState.Granted(precise = true))
    }

    private fun TestScope.collectCommands(): MutableList<MapCommand> =
        mutableListOf<MapCommand>().also { list ->
            backgroundScope.launch(dispatcher) { viewModel.commands.collect { list += it } }
        }

    private fun TestScope.collectEvents(): MutableList<MapUiEvent> =
        mutableListOf<MapUiEvent>().also { list ->
            backgroundScope.launch(dispatcher) { viewModel.events.collect { list += it } }
        }

    private fun at(lat: Double, lon: Double, speedMetersPerSecond: Float? = null) =
        UserLocation(lat, lon, accuracyMeters = 8f, speedMetersPerSecond = speedMetersPerSecond)

    private fun place(id: String, lat: Double, lon: Double) = SearchResult(
        id = id,
        name = "Place $id",
        displayName = "Place $id, USA",
        latitude = lat,
        longitude = lon,
    )

    private fun testPlace(id: String, category: PlaceCategory, latitude: Double = 0.0, longitude: Double = 0.0) = Place(
        id = id,
        name = "Test $id",
        category = category,
        latitude = latitude,
        longitude = longitude,
    )

    private fun route(distanceMeters: Double = 1000.0, durationSeconds: Double = 120.0) = RouteResult(
        geometry = listOf(Coordinate(40.0, -74.0), Coordinate(41.0, -75.0)),
        distanceMeters = distanceMeters,
        durationSeconds = durationSeconds,
        bounds = RouteBounds(south = 40.0, west = -75.0, north = 41.0, east = -74.0),
    )

    /**
     * A north-bound (increasing longitude) route at the equator with evenly spaced maneuvers, so a
     * fix's longitude maps directly onto how far along the route the driver is.
     */
    private fun navigableRoute(
        points: Int = 10,
        maneuvers: Int = 3,
        stepDegrees: Double = 0.001,
        distanceMeters: Double = (points - 1) * stepDegrees * 111_320.0,
    ): RouteResult {
        val geometry = (0 until points).map { Coordinate(0.0, it * stepDegrees) }
        val perManeuver = (points - 1) / maneuvers
        val routeManeuvers = (0 until maneuvers).map { index ->
            val begin = index * perManeuver
            val end = if (index == maneuvers - 1) points - 1 else begin + perManeuver - 1
            RouteManeuver(
                instruction = if (index == maneuvers - 1) "You have arrived." else "Continue on Route $index.",
                verbalInstruction = null,
                type = if (index == maneuvers - 1) ManeuverType.Destination else ManeuverType.Continue,
                streetName = null,
                distanceMeters = (end - begin) * stepDegrees * 111_320.0,
                beginShapeIndex = begin,
                endShapeIndex = end,
            )
        }
        return RouteResult(
            geometry = geometry,
            distanceMeters = distanceMeters,
            durationSeconds = 600.0,
            maneuvers = routeManeuvers,
        )
    }

    private class FakeLocationRepository : LocationRepository {
        private val flow = MutableSharedFlow<LocationUpdate>(extraBufferCapacity = 16)
        val subscribers: Int get() = flow.subscriptionCount.value
        fun emit(update: LocationUpdate) {
            check(flow.tryEmit(update))
        }
        override fun updates(): Flow<LocationUpdate> = flow
    }

    private class FakeRoutingRepository : RoutingRepository {
        val originsReceived = mutableListOf<Coordinate>()
        val destinationsReceived = mutableListOf<Coordinate>()
        var result: Result<RouteResult> = Result.success(
            RouteResult(geometry = emptyList(), distanceMeters = 0.0, durationSeconds = 0.0),
        )

        override suspend fun route(origin: Coordinate, destination: Coordinate): Result<RouteResult> {
            originsReceived += origin
            destinationsReceived += destination
            return result
        }
    }

    private class FakePlaceRepository : PlaceRepository {
        var nearbyResult: Result<List<Place>> = Result.success(emptyList())
        var alongRouteResult: Result<List<Place>> = Result.success(emptyList())
        val nearbyCenters = mutableListOf<Coordinate>()
        val alongRouteCalls = mutableListOf<List<Coordinate>>()

        override suspend fun nearbyPlaces(
            center: Coordinate,
            radiusMeters: Double,
            categories: Set<PlaceCategory>,
        ): Result<List<Place>> {
            nearbyCenters += center
            return nearbyResult
        }

        override suspend fun placesAlongRoute(
            routeGeometry: List<Coordinate>,
            corridorMeters: Double,
            categories: Set<PlaceCategory>,
        ): Result<List<Place>> {
            alongRouteCalls += routeGeometry
            return alongRouteResult
        }
    }

    private class FakeRoadIntelligenceRepository : RoadIntelligenceRepository {
        var eventsResult: Result<List<RoadEvent>> = Result.success(emptyList())
        var speedLimitResult: Result<SpeedLimitInfo?> = Result.success(null)
        var weatherResult: Result<WeatherCondition?> = Result.success(null)
        val eventsAlongRouteCalls = mutableListOf<List<Coordinate>>()
        val speedLimitCalls = mutableListOf<Coordinate>()
        val weatherCalls = mutableListOf<Coordinate>()

        override suspend fun roadEventsAlongRoute(
            routeGeometry: List<Coordinate>,
            corridorMeters: Double,
        ): Result<List<RoadEvent>> {
            eventsAlongRouteCalls += routeGeometry
            return eventsResult
        }

        override suspend fun speedLimitNear(location: Coordinate): Result<SpeedLimitInfo?> {
            speedLimitCalls += location
            return speedLimitResult
        }

        override suspend fun weatherNear(location: Coordinate): Result<WeatherCondition?> {
            weatherCalls += location
            return weatherResult
        }
    }

    private class FakeRoadClassRepository : RoadClassRepository {
        var result: Result<RoadClass?> = Result.success(null)
        val calls = mutableListOf<Coordinate>()

        override suspend fun roadClassNear(location: Coordinate): Result<RoadClass?> {
            calls += location
            return result
        }
    }

    // endregion

    private companion object {
        const val EPS = 1e-9
        const val TIMEOUT = 1_000L
    }
}
