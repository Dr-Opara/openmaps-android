package com.openmaps.app.map

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.location.Location
import android.view.Gravity
import android.view.View
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.UserLocation
import com.openmaps.app.core.routing.RouteResult
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.location.LocationComponentActivationOptions
import org.maplibre.android.location.LocationComponentOptions
import org.maplibre.android.location.modes.CameraMode
import org.maplibre.android.location.modes.RenderMode
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
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
    private var destination: SearchResult? = null
    private var route: RouteResult? = null
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
            syncDestination()
            syncRoute()
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
            is MapCommand.FitBounds -> {
                val readyMap = map ?: return
                val bounds = LatLngBounds.from(
                    command.bounds.north,
                    command.bounds.east,
                    command.bounds.south,
                    command.bounds.west,
                )
                val update = CameraUpdateFactory.newLatLngBounds(bounds, command.paddingPx)
                readyMap.animateCamera(update, command.durationMillis)
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
        val hasLocationPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        ) == PackageManager.PERMISSION_GRANTED

        if (location == null || !hasLocationPermission) {
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

    override fun setDestination(destination: SearchResult?) {
        this.destination = destination
        syncDestination()
    }

    /**
     * Destination marker as a GeoJSON source + circle layer (the extension point this codebase
     * already documented for POI-style overlays), not the separate annotation plugin, so no new
     * MapLibre dependency is needed. [GeoJsonSource.setGeoJson] always replaces the previous
     * data wholesale, so there is never more than one destination feature.
     *
     * Deliberately a plain circle, not a custom pin icon: a `SymbolLayer` with a programmatically
     * added image (both via `Style.addImage(String, Drawable)` and a manually rasterized
     * `Bitmap`, with icon-size/anchor/overlap properties explicitly set and the icon content
     * verified non-transparent) consistently failed to render anything on this project's test
     * emulator (MapLibre 11.13.5, 16 KB page size, OpenGL backend) — no exceptions, correct
     * layer/property/visibility state, simply no pixels drawn. A `CircleLayer` with the same
     * source and camera position renders correctly, so that is what is used here.
     */
    private fun syncDestination() {
        val readyMap = map ?: return
        if (!styleLoaded) return // Re-applied from the style-loaded callback.
        val style = readyMap.style ?: return
        ensureDestinationLayer(style)
        val source = style.getSourceAs<GeoJsonSource>(DESTINATION_SOURCE_ID) ?: return
        val point = destination
        source.setGeoJson(
            if (point == null) {
                FeatureCollection.fromFeatures(emptyArray())
            } else {
                FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(point.longitude, point.latitude)))
            },
        )
    }

    private fun ensureDestinationLayer(style: Style) {
        if (style.getLayer(DESTINATION_LAYER_ID) != null) return
        style.addSource(GeoJsonSource(DESTINATION_SOURCE_ID))
        style.addLayer(
            CircleLayer(DESTINATION_LAYER_ID, DESTINATION_SOURCE_ID).withProperties(
                PropertyFactory.circleRadius(DESTINATION_MARKER_RADIUS),
                PropertyFactory.circleColor(ACCENT_BLUE),
                PropertyFactory.circleStrokeColor(Color.WHITE),
                PropertyFactory.circleStrokeWidth(DESTINATION_MARKER_STROKE_WIDTH),
            ),
        )
    }

    override fun setRoute(route: RouteResult?) {
        this.route = route
        syncRoute()
    }

    /**
     * Route line as a GeoJSON source + line layer, same approach and same reasoning as the
     * destination marker (a dedicated source/layer pair, added below the destination layer so
     * the marker stays on top of the line it leads to). [GeoJsonSource.setGeoJson] replaces the
     * previous geometry wholesale, so there is never more than one route line, and passing
     * `null` clears it (e.g. when the destination is cleared or replaced while a new route is
     * still loading).
     */
    private fun syncRoute() {
        val readyMap = map ?: return
        if (!styleLoaded) return // Re-applied from the style-loaded callback.
        val style = readyMap.style ?: return
        ensureRouteLayer(style)
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID) ?: return
        val currentRoute = route
        source.setGeoJson(
            if (currentRoute == null || currentRoute.geometry.isEmpty()) {
                FeatureCollection.fromFeatures(emptyArray())
            } else {
                val points = currentRoute.geometry.map { Point.fromLngLat(it.longitude, it.latitude) }
                FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(points)))
            },
        )
    }

    private fun ensureRouteLayer(style: Style) {
        if (style.getLayer(ROUTE_LAYER_ID) != null) return
        style.addSource(GeoJsonSource(ROUTE_SOURCE_ID))
        val layer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(ACCENT_BLUE),
            PropertyFactory.lineWidth(ROUTE_LINE_WIDTH),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        if (style.getLayer(DESTINATION_LAYER_ID) != null) {
            style.addLayerBelow(layer, DESTINATION_LAYER_ID)
        } else {
            style.addLayer(layer)
        }
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
        const val DESTINATION_SOURCE_ID = "openmaps-destination-source"
        const val DESTINATION_LAYER_ID = "openmaps-destination-layer"
        const val DESTINATION_MARKER_RADIUS = 9f
        const val DESTINATION_MARKER_STROKE_WIDTH = 3f
        const val ROUTE_SOURCE_ID = "openmaps-route-source"
        const val ROUTE_LAYER_ID = "openmaps-route-layer"
        const val ROUTE_LINE_WIDTH = 5f
    }
}
