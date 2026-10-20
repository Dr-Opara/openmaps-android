package com.openmaps.app.map

import android.content.Context
import android.view.View
import androidx.lifecycle.Lifecycle
import com.openmaps.app.core.geocoding.SearchResult
import com.openmaps.app.core.location.UserLocation
import com.openmaps.app.core.places.Place
import com.openmaps.app.core.roadintelligence.RoadEvent
import com.openmaps.app.core.routing.RouteResult
import kotlinx.coroutines.flow.Flow

/**
 * The seam between OpenMaps and a map renderer. Feature UI depends on this interface and the
 * engine-neutral types in MapModels.kt; only [MapLibreMapEngine] imports MapLibre.
 *
 * Responsibilities today: view creation + lifecycle, style loading, camera commands/events,
 * current-location puck, destination marker, route line, contextual place markers (Phase 9).
 *
 * Planned extension points (intentionally NOT declared yet, to avoid speculative API):
 *  - AR/map sync       -> expose camera + projection (`latLngToScreen`) once Live needs it
 *  - active navigation camera tracking (Phase 4+)
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

    /** Show the route line for [route], or hide it when null. Replaces any previous one. */
    fun setRoute(route: RouteResult?)

    /**
     * Show contextual place markers (Phase 9), replacing any previous set wholesale — same
     * "whole-collection replace" contract as [setDestination]/[setRoute], so there is never a
     * stale marker left behind from an earlier fetch or filter change.
     */
    fun setPlaces(places: List<Place>)

    /**
     * Show road-hazard markers (Phase 10), replacing any previous set wholesale — same
     * "whole-collection replace" contract as [setPlaces]. Rendered above contextual places but
     * below the route/destination layers, so navigation content stays visually dominant while
     * hazards still read as more urgent than a general POI — see docs/ARCHITECTURE.md's Road
     * intelligence section ("Map mode rendering").
     */
    fun setRoadEvents(events: List<RoadEvent>)

    /** Apply OpenMaps-owned browsing/navigation and day/night cartography without recreating the map. */
    fun setCartographyMode(mode: CartographyMode)

    /** Optional imagery switch; implementations without satellite support keep standard. */
    fun setBasemapStyle(style: BasemapStyle) = Unit

    /**
     * Mark the first [RouteProgressView.traveledShapeIndex] points of the current route as already
     * driven, so the renderer can draw them differently from the road still ahead. Null clears the
     * split. Must be called after [setRoute] for the route in question.
     */
    fun setRouteProgress(progress: RouteProgressView?)

    /**
     * Space covered by overlays at the bottom of the map. Keeps required attribution visible
     * above the bottom panel (and, later, lets camera fitting avoid the panel).
     */
    fun setBottomInset(pixels: Int)
}
