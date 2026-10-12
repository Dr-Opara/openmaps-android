package com.openmaps.app.map

import android.content.Context
import android.graphics.Color
import android.location.Location
import android.view.Gravity
import android.view.View
import androidx.lifecycle.Lifecycle
import com.openmaps.app.core.location.UserLocation
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import kotlin.math.abs

/**
 * [MapEngine] backed by MapLibre Native.
 *
 * Notes:
 *  - One engine owns exactly one [MapView]; the view is created once and never rebuilt on
 *    state changes (Compose creates the engine with `remember` and the view in AndroidView's
 *    `factory`).
 *  - Commands that arrive before the map is ready are held (latest camera move wins) and
 *    applied non-animated when it is ready.
 *  - The blue dot is the MapLibre `LocationComponent` fed by *our* location layer
 *    (default engine disabled), so there is a single source of location truth.
 *
 * NOT compiled or run in the remote environment (MapLibre could not be resolved there).
 */
class MapLibreMapEngine(
    private val styleProvider: MapStyleProvider,
    private val initialCamera: CameraState,
) : MapEngine {

    private val _events = MutableSharedFlow<MapEvent>(
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<MapEvent> = _events.asSharedFlow()

    private enum class Stage { None, Created, Started, Resumed, Destroyed }

    private var stage = Stage.None
    private var mapView: MapView? = null
    private var map: MapLibreMap? = null
    private var styleLoaded = false
    private var pendingMove: MapCommand.MoveCamera? = null
    private var userLocation: UserLocation? = null
    private var bottomInsetPx = 0
    private var density = 1f
    private var lastReportedBearing = Double.NaN

    override fun createView(context: Context): View {
        check(mapView == null) { "MapLibreMapEngine.createView() was already called" }
        // Must be called before any MapView is inflated. Idempotent.
        MapLibre.getInstance(context.applicationContext)
        density = context.resources.displayMetrics.density

        val options = MapLibreMapOptions.createFromAttributes(context)
            .camera(initialCamera.toCameraPosition())
            .compassEnabled(false) // OpenMaps draws its own compass control.
            .logoEnabled(false)
            .attributionEnabled(true) // Keep the data attribution the licenses require.

        val view = MapView(context, options)
        view.addOnDidFailLoadingMapListener { message ->
            // Only a failure of the initial/retried style load counts; later tile errors do not.
            if (!styleLoaded) _events.tryEmit(MapEvent.StyleFailed(message))
        }
        mapView = view
        view.getMapAsync { onMapReady(it) }
        return view
    }

    private fun onMapReady(readyMap: MapLibreMap) {
        map = readyMap
        readyMap.setMinZoomPreference(MIN_ZOOM)
        readyMap.setMaxZoomPreference(MAX_ZOOM)
        readyMap.uiSettings.apply {
            isCompassEnabled = false
            isLogoEnabled = false
            isAttributionEnabled = true
            attributionGravity = Gravity.BOTTOM or Gravity.START
            isScrollGesturesEnabled = true
            isZoomGesturesEnabled = true
            isDoubleTapGesturesEnabled = true
            isRotateGesturesEnabled = true
            isTiltGesturesEnabled = true
        }
        applyAttributionMargins()

        readyMap.addOnCameraMoveStartedListener { reason ->
            if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {
                _events.tryEmit(MapEvent.UserGestureStarted)
            }
        }
        readyMap.addOnCameraMoveListener {
            val bearing = readyMap.cameraPosition.bearing
            if (lastReportedBearing.isNaN() || abs(bearing - lastReportedBearing) >= BEARING_EPSILON_DEGREES) {
                lastReportedBearing = bearing
                _events.tryEmit(MapEvent.BearingChanged(bearing))
            }
        }
        readyMap.addOnCameraIdleListener {
            val camera = readyMap.cameraPosition.toCameraState()
            lastReportedBearing = camera.bearing
            _events.tryEmit(MapEvent.CameraIdle(camera))
        }

        pendingMove?.let { move ->
            pendingMove = null
            applyMove(readyMap, move.copy(animated = false))
        }
        loadStyle(readyMap)
    }

    private fun loadStyle(readyMap: MapLibreMap) {
        styleLoaded = false
        val builder = Style.Builder()
        when (val source = styleProvider.resolve()) {
            is MapStyleSource.Url -> builder.fromUri(source.url)
            is MapStyleSource.InlineJson -> builder.fromJson(source.json)
        }
        readyMap.setStyle(builder) {
            styleLoaded = true
            _events.tryEmit(MapEvent.StyleLoaded)
            syncUserLocation()
        }
    }

    override fun execute(command: MapCommand) {
        when (command) {
            is MapCommand.MoveCamera -> {
                val readyMap = map
                if (readyMap == null) pendingMove = command else applyMove(readyMap, command)
            }
            MapCommand.RetryStyle -> {
                val readyMap = map
                if (readyMap != null) loadStyle(readyMap) else mapView?.getMapAsync { onMapReady(it) }
            }
        }
    }

    private fun applyMove(readyMap: MapLibreMap, move: MapCommand.MoveCamera) {
        val update = CameraUpdateFactory.newCameraPosition(move.target.toCameraPosition())
        if (move.animated) readyMap.animateCamera(update, move.durationMillis) else readyMap.moveCamera(update)
    }

    override fun setUserLocation(location: UserLocation?) {
        userLocation = location
        syncUserLocation()
    }

    private fun syncUserLocation() {
        val readyMap = map ?: return
        val context = mapView?.context ?: return
        if (!styleLoaded) return // Re-applied from the style-loaded callback.
        val style = readyMap.style ?: return
        val location = userLocation
        val component = readyMap.locationComponent

        if (location == null) {
            if (component.isLocationComponentActivated) component.isLocationComponentEnabled = false
            return
        }
        if (!component.isLocationComponentActivated) {
            val options = LocationComponentOptions.builder(context)
                .accuracyColor(ACCENT_BLUE)
                .accuracyAlpha(0.14f)
                .foregroundTintColor(ACCENT_BLUE)
                .pulseEnabled(true)
                .pulseColor(ACCENT_BLUE)
                .build()
            component.activateLocationComponent(
                LocationComponentActivationOptions.builder(context, style)
                    .locationComponentOptions(options)
                    .useDefaultLocationEngine(false) // Locations come from our location layer.
                    .build(),
            )
            component.cameraMode = CameraMode.NONE // Never let the SDK move the camera for us.
            component.renderMode = RenderMode.NORMAL
        }
        component.isLocationComponentEnabled = true
        component.forceLocationUpdate(location.toAndroidLocation())
    }

    override fun setBottomInset(pixels: Int) {
        bottomInsetPx = pixels
        applyAttributionMargins()
    }

    private fun applyAttributionMargins() {
        val readyMap = map ?: return
        val margin = (ATTRIBUTION_MARGIN_DP * density).toInt()
        readyMap.uiSettings.setAttributionMargins(margin, 0, 0, bottomInsetPx + margin)
    }

    // region Lifecycle

    override fun onLifecycleEvent(event: Lifecycle.Event) {
        val view = mapView ?: return
        when (event) {
            Lifecycle.Event.ON_CREATE -> if (stage == Stage.None) {
                view.onCreate(null)
                stage = Stage.Created
            }
            Lifecycle.Event.ON_START -> if (stage == Stage.Created) {
                view.onStart()
                stage = Stage.Started
            }
            Lifecycle.Event.ON_RESUME -> if (stage == Stage.Started) {
                view.onResume()
                stage = Stage.Resumed
            }
            Lifecycle.Event.ON_PAUSE -> if (stage == Stage.Resumed) {
                view.onPause()
                stage = Stage.Started
            }
            Lifecycle.Event.ON_STOP -> if (stage == Stage.Started) {
                view.onStop()
                stage = Stage.Created
            }
            Lifecycle.Event.ON_DESTROY -> release()
            Lifecycle.Event.ON_ANY -> Unit
        }
    }

    override fun onLowMemory() {
        if (stage != Stage.None && stage != Stage.Destroyed) mapView?.onLowMemory()
    }

    /** Walk the renderer down through whatever lifecycle stages it is still in. */
    override fun release() {
        val view = mapView ?: return
        if (stage == Stage.Destroyed) return
        if (stage == Stage.Resumed) {
            view.onPause()
            stage = Stage.Started
        }
        if (stage == Stage.Started) {
            view.onStop()
            stage = Stage.Created
        }
        if (stage == Stage.Created) view.onDestroy()
        stage = Stage.Destroyed
        map = null
        mapView = null
    }

    // endregion

    private fun CameraState.toCameraPosition(): CameraPosition = CameraPosition.Builder()
        .target(LatLng(latitude, longitude))
        .zoom(zoom)
        .bearing(bearing)
        .tilt(pitch)
        .build()

    private fun CameraPosition.toCameraState(): CameraState {
        val center = target ?: LatLng(initialCamera.latitude, initialCamera.longitude)
        return CameraState(center.latitude, center.longitude, zoom, bearing, tilt)
    }

    private fun UserLocation.toAndroidLocation(): Location = Location(PROVIDER_TAG).also {
        it.latitude = latitude
        it.longitude = longitude
        accuracyMeters?.let { accuracy -> it.accuracy = accuracy }
        bearingDegrees?.let { bearing -> it.bearing = bearing }
        it.time = if (timestampMillis > 0L) timestampMillis else System.currentTimeMillis()
    }

    private companion object {
        const val MIN_ZOOM = 2.0
        const val MAX_ZOOM = 20.0
        const val BEARING_EPSILON_DEGREES = 0.5
        const val ATTRIBUTION_MARGIN_DP = 8
        const val PROVIDER_TAG = "openmaps"
        val ACCENT_BLUE: Int = Color.parseColor("#2F7BFF")
    }
}
