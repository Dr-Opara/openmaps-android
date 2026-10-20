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
import com.google.gson.JsonObject
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.UserLocation
import com.openmaps.app.core.places.Place
import com.openmaps.app.core.places.PlaceCategory
import com.openmaps.app.core.roadintelligence.RoadEvent
import com.openmaps.app.core.roadintelligence.RoadEventSeverity
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
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.BackgroundLayer
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.SymbolLayer
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
    private var basemapStyle: BasemapStyle = BasemapStyle.Standard
    private var styleRequestGeneration = 0
    private var pendingMove: MapCommand.MoveCamera? = null
    private var userLocation: UserLocation? = null
    private var destination: SearchResult? = null
    private var route: RouteResult? = null
    private var routeProgress: RouteProgressView? = null
    private var places: List<Place> = emptyList()
    private var roadEvents: List<RoadEvent> = emptyList()
    private var cartographyMode: CartographyMode = CartographyMode.BrowseDay
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

        // Phase 9: tap-to-select on place markers. queryRenderedFeatures against just the places
        // layer means a tap elsewhere on the map (or on the destination/route layers) is never
        // misread as a place tap; returning false lets any other click handling still run.
        readyMap.addOnMapClickListener { latLng ->
            val screenPoint = readyMap.projection.toScreenLocation(latLng)
            val placeId = readyMap.queryRenderedFeatures(screenPoint, PLACES_LAYER_ID)
                .firstOrNull()
                ?.getStringProperty(PLACE_FEATURE_ID_KEY)
            if (placeId != null) {
                _events.tryEmit(MapEvent.PlaceTapped(placeId))
                true
            } else {
                false
            }
        }

        pendingMove?.let { move ->
            pendingMove = null
            applyMove(readyMap, move.copy(animated = false))
        }
        loadStyle(readyMap)
    }

    private fun loadStyle(readyMap: MapLibreMap) {
        styleLoaded = false
        val generation = ++styleRequestGeneration
        val source = styleProvider.resolve(basemapStyle) ?: styleProvider.resolve()
        val builder = Style.Builder()
        when (source) {
            is MapStyleSource.Url -> builder.fromUri(source.url)
            is MapStyleSource.InlineJson -> builder.fromJson(source.json)
        }
        readyMap.setStyle(builder) { style ->
            if (generation != styleRequestGeneration) return@setStyle
            styleLoaded = true
            if (basemapStyle == BasemapStyle.Standard) applyCartography(style)
            _events.tryEmit(MapEvent.StyleLoaded)
            syncUserLocation()
            syncDestination()
            syncRoute()
            // After syncRoute(): that call unconditionally creates ROUTE_TRAVELED_LAYER_ID (even
            // with no route yet), which is the layer the places layer is anchored below — see
            // ensurePlacesLayer.
            syncPlaces()
            // After syncPlaces(): ensureRoadEventsLayer anchors above PLACES_LAYER_ID when it
            // exists, so hazards render above general POIs but still below route/destination.
            syncRoadEvents()
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
            // Route progress arrives via MapEngine.setRouteProgress, not as a command.
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
        // A new route invalidates the previous route's progress split.
        if (this.routeProgress?.traveledShapeIndex != null && routeProgressIsStale()) {
            routeProgress = null
        }
        syncRoute()
    }

    override fun setRouteProgress(progress: RouteProgressView?) {
        routeProgress = progress
        syncRoute()
    }

    private fun routeProgressIsStale(): Boolean {
        val currentRoute = route ?: return true
        val index = routeProgress?.traveledShapeIndex ?: return false
        return index >= currentRoute.geometry.size
    }

    /**
     * Route line as a GeoJSON source + line layer, same approach and same reasoning as the
     * destination marker (a dedicated source/layer pair, added below the destination layer so
     * the marker stays on top of the line it leads to). [GeoJsonSource.setGeoJson] replaces the
     * previous geometry wholesale, so there is never more than one route line, and passing
     * `null` clears it (e.g. when the destination is cleared or replaced while a new route is
     * still loading).
     *
     * While navigating the line is drawn twice: the already-driven portion from [ROUTE_TRAVELED_*]
     * (desaturated, behind) and the road still ahead from [ROUTE_SOURCE_ID] (accent blue, over it).
     * The remaining segment always includes the point at the split index, so the two halves meet at
     * exactly one point instead of leaving a visible gap at the user's position.
     */
    private fun syncRoute() {
        val readyMap = map ?: return
        if (!styleLoaded) return // Re-applied from the style-loaded callback.
        val style = readyMap.style ?: return
        ensureRouteLayer(style)
        ensureRouteTraveledLayer(style)
        val source = style.getSourceAs<GeoJsonSource>(ROUTE_SOURCE_ID) ?: return
        val traveledSource = style.getSourceAs<GeoJsonSource>(ROUTE_TRAVELED_SOURCE_ID)
        val currentRoute = route
        if (currentRoute == null || currentRoute.geometry.isEmpty()) {
            source.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            traveledSource?.setGeoJson(FeatureCollection.fromFeatures(emptyArray()))
            return
        }

        val splitIndex = routeProgress?.traveledShapeIndex?.takeIf { it in 0 until currentRoute.geometry.size }
        val remaining = if (splitIndex == null) currentRoute.geometry else currentRoute.geometry.drop(splitIndex)
        source.setGeoJson(
            if (remaining.size >= 2) {
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(LineString.fromLngLats(remaining.map { Point.fromLngLat(it.longitude, it.latitude) })),
                )
            } else {
                FeatureCollection.fromFeatures(emptyArray())
            },
        )
        traveledSource?.setGeoJson(
            if (splitIndex != null && splitIndex >= 1) {
                FeatureCollection.fromFeature(
                    Feature.fromGeometry(
                        LineString.fromLngLats(
                            currentRoute.geometry.take(splitIndex + 1).map { Point.fromLngLat(it.longitude, it.latitude) },
                        ),
                    ),
                )
            } else {
                FeatureCollection.fromFeatures(emptyArray())
            },
        )
    }

    private fun ensureRouteTraveledLayer(style: Style) {
        if (style.getLayer(ROUTE_TRAVELED_LAYER_ID) != null) return
        style.addSource(GeoJsonSource(ROUTE_TRAVELED_SOURCE_ID))
        val palette = OpenMapsCartography.palette(cartographyMode)
        val layer = LineLayer(ROUTE_TRAVELED_LAYER_ID, ROUTE_TRAVELED_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(palette.traveledRoute),
            PropertyFactory.lineWidth(ROUTE_LINE_WIDTH),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        // Directly below the remaining line so the accent stroke stays legible on top of it.
        if (style.getLayer(ROUTE_LAYER_ID) != null) style.addLayerBelow(layer, ROUTE_LAYER_ID) else style.addLayer(layer)
    }

    private fun ensureRouteLayer(style: Style) {
        if (style.getLayer(ROUTE_LAYER_ID) != null) return
        val palette = OpenMapsCartography.palette(cartographyMode)
        if (style.getSource(ROUTE_SOURCE_ID) == null) {
            style.addSource(GeoJsonSource(ROUTE_SOURCE_ID))
        }
        val casing = LineLayer(ROUTE_CASING_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(palette.routeCasing),
            PropertyFactory.lineWidth(ROUTE_CASING_WIDTH),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        val layer = LineLayer(ROUTE_LAYER_ID, ROUTE_SOURCE_ID).withProperties(
            PropertyFactory.lineColor(palette.route),
            PropertyFactory.lineWidth(ROUTE_LINE_WIDTH),
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        )
        if (style.getLayer(DESTINATION_LAYER_ID) != null) {
            style.addLayerBelow(casing, DESTINATION_LAYER_ID)
        } else {
            style.addLayer(casing)
        }
        style.addLayerAbove(layer, ROUTE_CASING_LAYER_ID)
    }

    override fun setPlaces(places: List<Place>) {
        this.places = places
        syncPlaces()
    }

    /**
     * Contextual place markers (Phase 9) as a GeoJSON source + circle layer, same "whole-collection
     * replace" approach as the destination marker and route line. A `CircleLayer` with a
     * data-driven color expression (keyed on each feature's `category` property), **not** a
     * `SymbolLayer` with per-category icons — see [ensureDestinationLayer]'s known limitation: a
     * programmatically added custom icon consistently failed to render anything on this project's
     * test emulator, while a plain `CircleLayer` renders correctly. Eight icons would hit the exact
     * same issue, so markers are color-coded circles instead.
     */
    private fun syncPlaces() {
        val readyMap = map ?: return
        if (!styleLoaded) return // Re-applied from the style-loaded callback.
        val style = readyMap.style ?: return
        ensurePlacesLayer(style)
        val source = style.getSourceAs<GeoJsonSource>(PLACES_SOURCE_ID) ?: return
        source.setGeoJson(
            FeatureCollection.fromFeatures(
                places.map { place ->
                    val properties = JsonObject().apply {
                        addProperty(PLACE_FEATURE_ID_KEY, place.id)
                        addProperty(PLACE_FEATURE_CATEGORY_KEY, place.category.name)
                    }
                    Feature.fromGeometry(Point.fromLngLat(place.longitude, place.latitude), properties)
                }.toTypedArray(),
            ),
        )
    }

    /**
     * Added below [ROUTE_TRAVELED_LAYER_ID] (which always exists once [syncRoute] has run once —
     * see the style-loaded callback), so the destination marker and route line always render on
     * top of contextual places: navigation content stays visually dominant (Phase 9 "visual
     * density" rule).
     */
    private fun ensurePlacesLayer(style: Style) {
        if (style.getLayer(PLACES_LAYER_ID) != null) return
        style.addSource(GeoJsonSource(PLACES_SOURCE_ID))
        val palette = OpenMapsCartography.palette(cartographyMode)
        val layer = CircleLayer(PLACES_LAYER_ID, PLACES_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(PLACE_MARKER_RADIUS),
            PropertyFactory.circleOpacity(palette.poiOpacity),
            PropertyFactory.circleColor(placeCategoryColorExpression()),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(PLACE_MARKER_STROKE_WIDTH),
        )
        if (style.getLayer(ROUTE_TRAVELED_LAYER_ID) != null) {
            style.addLayerBelow(layer, ROUTE_TRAVELED_LAYER_ID)
        } else {
            style.addLayer(layer)
        }
    }

    /** Maps each [com.openmaps.app.core.places.PlaceCategory] to a distinguishable marker color. */
    private fun placeCategoryColorExpression(): Expression = Expression.match(
        Expression.get(PLACE_FEATURE_CATEGORY_KEY),
        Expression.literal(PlaceCategory.Restaurant.name), Expression.color(PLACE_COLOR_RESTAURANT),
        Expression.literal(PlaceCategory.Coffee.name), Expression.color(PLACE_COLOR_COFFEE),
        Expression.literal(PlaceCategory.Hotel.name), Expression.color(PLACE_COLOR_HOTEL),
        Expression.literal(PlaceCategory.Grocery.name), Expression.color(PLACE_COLOR_GROCERY),
        Expression.literal(PlaceCategory.Pharmacy.name), Expression.color(PLACE_COLOR_PHARMACY),
        Expression.literal(PlaceCategory.Parking.name), Expression.color(PLACE_COLOR_PARKING),
        Expression.literal(PlaceCategory.Gas.name), Expression.color(PLACE_COLOR_GAS),
        Expression.literal(PlaceCategory.EvCharging.name), Expression.color(PLACE_COLOR_EV),
        Expression.literal(PlaceCategory.Bank.name), Expression.color(PLACE_COLOR_BANK),
        Expression.literal(PlaceCategory.Atm.name), Expression.color(PLACE_COLOR_ATM),
        Expression.literal(PlaceCategory.Convenience.name), Expression.color(PLACE_COLOR_CONVENIENCE),
        Expression.literal(PlaceCategory.Medical.name), Expression.color(PLACE_COLOR_MEDICAL),
        Expression.literal(PlaceCategory.Entertainment.name), Expression.color(PLACE_COLOR_ENTERTAINMENT),
        Expression.literal(PlaceCategory.Shopping.name), Expression.color(PLACE_COLOR_SHOPPING),
        Expression.literal(PlaceCategory.Education.name), Expression.color(PLACE_COLOR_EDUCATION),
        Expression.color(PLACE_COLOR_DEFAULT),
    )

    override fun setRoadEvents(events: List<RoadEvent>) {
        this.roadEvents = events
        syncRoadEvents()
    }

    override fun setBasemapStyle(style: BasemapStyle) {
        val selected = if (style == BasemapStyle.Satellite && styleProvider.resolve(style) == null) BasemapStyle.Standard else style
        if (basemapStyle == selected) return
        basemapStyle = selected
        map?.let(::loadStyle)
    }

    override fun setCartographyMode(mode: CartographyMode) {
        if (cartographyMode == mode) return
        cartographyMode = mode
        val style = map?.style ?: return
        if (!styleLoaded) return
        if (basemapStyle == BasemapStyle.Standard) applyCartography(style)
    }

    private fun applyCartography(style: Style) {
        val palette = OpenMapsCartography.palette(cartographyMode)
        style.layers.forEach { layer ->
            val id = layer.id.lowercase()
            when (layer) {
                is BackgroundLayer -> layer.setProperties(
                    PropertyFactory.backgroundColor(palette.background),
                )
                is FillLayer -> when {
                    id.contains("water") -> layer.setProperties(PropertyFactory.fillColor(palette.water))
                    id.contains("park") || id.contains("landcover") || id.contains("green") ->
                        layer.setProperties(PropertyFactory.fillColor(palette.park))
                    id.contains("building") -> layer.setProperties(PropertyFactory.fillColor(palette.building))
                    id.contains("land") || id.contains("background") ->
                        layer.setProperties(PropertyFactory.fillColor(palette.land))
                }
                is LineLayer -> when {
                    id.contains("motorway") || id.contains("trunk") ->
                        layer.setProperties(PropertyFactory.lineColor(palette.motorway))
                    id.contains("primary") || id.contains("secondary") || id.contains("tertiary") ->
                        layer.setProperties(PropertyFactory.lineColor(palette.majorRoad))
                    id.contains("road") || id.contains("street") || id.contains("path") ->
                        layer.setProperties(PropertyFactory.lineColor(palette.minorRoad))
                }
                is SymbolLayer -> {
                    if (id.contains("label") || id.contains("place") || id.contains("road") || id.contains("street")) {
                        layer.setProperties(
                            PropertyFactory.textColor(palette.label),
                            PropertyFactory.textHaloColor(palette.labelHalo),
                            PropertyFactory.textOpacity(palette.baseLabelOpacity),
                        )
                    }
                    if (id.contains("poi") || id.contains("amenity") || id.contains("shop")) {
                        layer.setProperties(
                            PropertyFactory.iconOpacity(palette.poiOpacity),
                            PropertyFactory.textOpacity(palette.poiOpacity),
                        )
                    }
                }
            }
        }

        style.getLayerAs<LineLayer>(ROUTE_CASING_LAYER_ID)?.setProperties(
            PropertyFactory.lineColor(palette.routeCasing),
        )
        style.getLayerAs<LineLayer>(ROUTE_LAYER_ID)?.setProperties(
            PropertyFactory.lineColor(palette.route),
        )
        style.getLayerAs<LineLayer>(ROUTE_TRAVELED_LAYER_ID)?.setProperties(
            PropertyFactory.lineColor(palette.traveledRoute),
        )
        style.getLayerAs<CircleLayer>(PLACES_LAYER_ID)?.setProperties(
            PropertyFactory.circleOpacity(palette.poiOpacity),
        )
    }

    /**
     * Road hazards (Phase 10) as a GeoJSON source + circle layer, the same "whole-collection
     * replace" approach [syncPlaces] established. Colored by [RoadEvent.severity] rather than
     * [RoadEvent.type] — "how urgent" is what a glance at the map needs, and it also means any
     * future [com.openmaps.app.core.roadintelligence.RoadEventType] this app doesn't have a bespoke
     * color for yet still renders sensibly.
     */
    private fun syncRoadEvents() {
        val readyMap = map ?: return
        if (!styleLoaded) return // Re-applied from the style-loaded callback.
        val style = readyMap.style ?: return
        ensureRoadEventsLayer(style)
        val source = style.getSourceAs<GeoJsonSource>(ROAD_EVENTS_SOURCE_ID) ?: return
        source.setGeoJson(
            FeatureCollection.fromFeatures(
                roadEvents.map { event ->
                    val properties = JsonObject().apply {
                        addProperty(ROAD_EVENT_FEATURE_ID_KEY, event.id)
                        addProperty(ROAD_EVENT_FEATURE_SEVERITY_KEY, event.severity.name)
                    }
                    Feature.fromGeometry(Point.fromLngLat(event.longitude, event.latitude), properties)
                }.toTypedArray(),
            ),
        )
    }

    /**
     * Anchored above [PLACES_LAYER_ID] when it exists (hazards read as more urgent than a general
     * POI), falling back to the same below-[ROUTE_TRAVELED_LAYER_ID] anchor [ensurePlacesLayer]
     * uses when it does not — both keep navigation content on top either way.
     */
    private fun ensureRoadEventsLayer(style: Style) {
        if (style.getLayer(ROAD_EVENTS_LAYER_ID) != null) return
        style.addSource(GeoJsonSource(ROAD_EVENTS_SOURCE_ID))
        val layer = CircleLayer(ROAD_EVENTS_LAYER_ID, ROAD_EVENTS_SOURCE_ID).withProperties(
            PropertyFactory.circleRadius(ROAD_EVENT_MARKER_RADIUS),
            PropertyFactory.circleColor(roadEventSeverityColorExpression()),
            PropertyFactory.circleStrokeColor(Color.WHITE),
            PropertyFactory.circleStrokeWidth(ROAD_EVENT_MARKER_STROKE_WIDTH),
        )
        when {
            style.getLayer(PLACES_LAYER_ID) != null -> style.addLayerAbove(layer, PLACES_LAYER_ID)
            style.getLayer(ROUTE_TRAVELED_LAYER_ID) != null -> style.addLayerBelow(layer, ROUTE_TRAVELED_LAYER_ID)
            else -> style.addLayer(layer)
        }
    }

    /** Maps each [RoadEventSeverity] to a color, increasing in alarm from low to severe. */
    private fun roadEventSeverityColorExpression(): Expression = Expression.match(
        Expression.get(ROAD_EVENT_FEATURE_SEVERITY_KEY),
        Expression.literal(RoadEventSeverity.Low.name), Expression.color(ROAD_EVENT_COLOR_LOW),
        Expression.literal(RoadEventSeverity.Moderate.name), Expression.color(ROAD_EVENT_COLOR_MODERATE),
        Expression.literal(RoadEventSeverity.High.name), Expression.color(ROAD_EVENT_COLOR_HIGH),
        Expression.literal(RoadEventSeverity.Severe.name), Expression.color(ROAD_EVENT_COLOR_SEVERE),
        Expression.color(ROAD_EVENT_COLOR_MODERATE),
    )

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
        const val ROUTE_CASING_LAYER_ID = "openmaps-route-casing-layer"
        const val ROUTE_LAYER_ID = "openmaps-route-layer"
        const val ROUTE_LINE_WIDTH = 9f
        const val ROUTE_CASING_WIDTH = 13f
        const val ROUTE_TRAVELED_SOURCE_ID = "openmaps-route-traveled-source"
        const val ROUTE_TRAVELED_LAYER_ID = "openmaps-route-traveled-layer"

        /** Muted slate for road already driven, so the accent line ahead reads as "what's next". */
        val ROUTE_TRAVELED_COLOR: Int = Color.parseColor("#8A94A6")

        // Phase 9: contextual place markers.
        const val PLACES_SOURCE_ID = "openmaps-places-source"
        const val PLACES_LAYER_ID = "openmaps-places-layer"
        const val PLACE_FEATURE_ID_KEY = "id"
        const val PLACE_FEATURE_CATEGORY_KEY = "category"

        /** Smaller than the destination marker so the destination always reads as "the" pin. */
        const val PLACE_MARKER_RADIUS = 6f
        const val PLACE_MARKER_STROKE_WIDTH = 1.5f

        val PLACE_COLOR_RESTAURANT: Int = Color.parseColor("#FFC107")
        val PLACE_COLOR_COFFEE: Int = Color.parseColor("#A9713A")
        val PLACE_COLOR_HOTEL: Int = Color.parseColor("#E91E8C")
        val PLACE_COLOR_GROCERY: Int = Color.parseColor("#3DBA6B")
        val PLACE_COLOR_PHARMACY: Int = Color.parseColor("#FF5C7A")
        val PLACE_COLOR_PARKING: Int = Color.parseColor("#2F7BFF")
        val PLACE_COLOR_GAS: Int = Color.parseColor("#E03131")
        val PLACE_COLOR_EV: Int = Color.parseColor("#3DD6C6")
        val PLACE_COLOR_BANK: Int = Color.parseColor("#4A7FE8")
        val PLACE_COLOR_ATM: Int = Color.parseColor("#6B8FE0")
        val PLACE_COLOR_CONVENIENCE: Int = Color.parseColor("#8ED16F")
        val PLACE_COLOR_MEDICAL: Int = Color.parseColor("#E05C5C")
        val PLACE_COLOR_ENTERTAINMENT: Int = Color.parseColor("#D16FD1")
        val PLACE_COLOR_SHOPPING: Int = Color.parseColor("#E89B3D")
        val PLACE_COLOR_EDUCATION: Int = Color.parseColor("#6F8FD1")
        val PLACE_COLOR_DEFAULT: Int = Color.parseColor("#B9C6DD")

        // Phase 10: road-hazard markers.
        const val ROAD_EVENTS_SOURCE_ID = "openmaps-road-events-source"
        const val ROAD_EVENTS_LAYER_ID = "openmaps-road-events-layer"
        const val ROAD_EVENT_FEATURE_ID_KEY = "id"
        const val ROAD_EVENT_FEATURE_SEVERITY_KEY = "severity"

        /** Slightly larger than a place marker — a hazard should draw the eye first. */
        const val ROAD_EVENT_MARKER_RADIUS = 7f
        const val ROAD_EVENT_MARKER_STROKE_WIDTH = 1.5f

        val ROAD_EVENT_COLOR_LOW: Int = Color.parseColor("#E8C340")
        val ROAD_EVENT_COLOR_MODERATE: Int = Color.parseColor("#FF8A3D")
        val ROAD_EVENT_COLOR_HIGH: Int = Color.parseColor("#FF5C3D")
        val ROAD_EVENT_COLOR_SEVERE: Int = Color.parseColor("#D32F2F")
    }
}
