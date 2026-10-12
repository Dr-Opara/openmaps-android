package com.openmaps.app.map

import android.content.Context
import android.view.View
import androidx.lifecycle.Lifecycle
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.UserLocation
import kotlinx.coroutines.flow.Flow

/**
 * The seam between OpenMaps and a map renderer. Feature UI depends on this interface and the
 * engine-neutral types in MapModels.kt; only [MapLibreMapEngine] imports MapLibre.
 *
 * Responsibilities today: view creation + lifecycle, style loading, camera commands/events,
 * current-location puck, destination marker.
 *
 * Planned extension points (intentionally NOT declared yet, to avoid speculative API):
 *  - route rendering   -> e.g. `setRoute(polyline)`, drawn on a GeoJSON line layer (Phase 3+)
 *  - POI overlays      -> e.g. `setPlaces(list)`, symbol layers
 *  - AR/map sync       -> expose camera + projection (`latLngToScreen`) once Live needs it
 */
interface MapEngine {
    /** Engine -> app events (style loaded/failed, user gesture, camera idle, bearing). */
    val events: Flow<MapEvent>

    /** Creates the map view exactly once per engine instance. Safe to hand to AndroidView. */
    fun createView(context: Context): View

    /** Forward the host lifecycle; the engine maps it onto the renderer's lifecycle calls. */
    fun onLifecycleEvent(event: Lifecycle.Event)

    fun onLowMemory()

    /** Tear down (idempotent). Called when the host leaves composition. */
    fun release()

    fun execute(command: MapCommand)

    /** Show the blue location indicator at [location], or hide it when null. */
    fun setUserLocation(location: UserLocation?)

    /** Show a destination marker at [destination], or hide it when null. Replaces any previous one. */
    fun setDestination(destination: SearchResult?)

    /**
     * Space covered by overlays at the bottom of the map. Keeps required attribution visible
     * above the bottom panel (and, later, lets camera fitting avoid the panel).
     */
    fun setBottomInset(pixels: Int)
}
