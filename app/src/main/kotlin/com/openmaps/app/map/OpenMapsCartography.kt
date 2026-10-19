package com.openmaps.app.map

import android.graphics.Color

/**
 * Provider-neutral OpenMaps cartography modes.
 *
 * The basemap provider supplies geometry/labels. OpenMaps owns the visual hierarchy applied on top
 * of that provider so changing tile infrastructure does not change navigation semantics.
 */
enum class CartographyMode {
    BrowseDay,
    BrowseNight,
    NavigationDay,
    NavigationNight,
}

data class CartographyPalette(
    val background: Int,
    val land: Int,
    val water: Int,
    val park: Int,
    val building: Int,
    val minorRoad: Int,
    val majorRoad: Int,
    val motorway: Int,
    val roadCasing: Int,
    val label: Int,
    val labelHalo: Int,
    val route: Int,
    val routeCasing: Int,
    val traveledRoute: Int,
    val poiOpacity: Float,
    val baseLabelOpacity: Float,
)

object OpenMapsCartography {
    val BrowseDay = CartographyPalette(
        background = Color.parseColor("#EEF3F8"),
        land = Color.parseColor("#F7F9FC"),
        water = Color.parseColor("#B7D9F2"),
        park = Color.parseColor("#DCEBD8"),
        building = Color.parseColor("#E4E8EE"),
        minorRoad = Color.parseColor("#FFFFFF"),
        majorRoad = Color.parseColor("#F7F9FC"),
        motorway = Color.parseColor("#E8EDF5"),
        roadCasing = Color.parseColor("#CCD4DF"),
        label = Color.parseColor("#26354D"),
        labelHalo = Color.parseColor("#FFFFFF"),
        route = Color.parseColor("#2F7BFF"),
        routeCasing = Color.parseColor("#FFFFFF"),
        traveledRoute = Color.parseColor("#A7B0BF"),
        poiOpacity = 0.85f,
        baseLabelOpacity = 1.0f,
    )

    val BrowseNight = CartographyPalette(
        background = Color.parseColor("#070D1B"),
        land = Color.parseColor("#0A1224"),
        water = Color.parseColor("#0B263A"),
        park = Color.parseColor("#10271F"),
        building = Color.parseColor("#172238"),
        minorRoad = Color.parseColor("#1B2943"),
        majorRoad = Color.parseColor("#263652"),
        motorway = Color.parseColor("#334A6D"),
        roadCasing = Color.parseColor("#07101E"),
        label = Color.parseColor("#D9E4F4"),
        labelHalo = Color.parseColor("#0A1224"),
        route = Color.parseColor("#5A91FF"),
        routeCasing = Color.parseColor("#DCE8FF"),
        traveledRoute = Color.parseColor("#56647A"),
        poiOpacity = 0.72f,
        baseLabelOpacity = 0.9f,
    )

    val NavigationDay = BrowseDay.copy(
        background = Color.parseColor("#F2F5F9"),
        land = Color.parseColor("#F8FAFC"),
        minorRoad = Color.parseColor("#F7F8FA"),
        majorRoad = Color.parseColor("#FFFFFF"),
        motorway = Color.parseColor("#EDF2F8"),
        route = Color.parseColor("#2468E0"),
        routeCasing = Color.parseColor("#FFFFFF"),
        traveledRoute = Color.parseColor("#B2BAC7"),
        poiOpacity = 0.42f,
        baseLabelOpacity = 0.78f,
    )

    val NavigationNight = BrowseNight.copy(
        route = Color.parseColor("#6A9CFF"),
        routeCasing = Color.parseColor("#E7EEFF"),
        traveledRoute = Color.parseColor("#4C596D"),
        poiOpacity = 0.35f,
        baseLabelOpacity = 0.72f,
    )

    fun palette(mode: CartographyMode): CartographyPalette = when (mode) {
        CartographyMode.BrowseDay -> BrowseDay
        CartographyMode.BrowseNight -> BrowseNight
        CartographyMode.NavigationDay -> NavigationDay
        CartographyMode.NavigationNight -> NavigationNight
    }
}
