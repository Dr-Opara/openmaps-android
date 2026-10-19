package com.openmaps.app.map

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
        background = 0xFFEEF3F8.toInt(),
        land = 0xFFF7F9FC.toInt(),
        water = 0xFFB7D9F2.toInt(),
        park = 0xFFDCEBD8.toInt(),
        building = 0xFFE4E8EE.toInt(),
        minorRoad = 0xFFFFFFFF.toInt(),
        majorRoad = 0xFFF7F9FC.toInt(),
        motorway = 0xFFE8EDF5.toInt(),
        roadCasing = 0xFFCCD4DF.toInt(),
        label = 0xFF26354D.toInt(),
        labelHalo = 0xFFFFFFFF.toInt(),
        route = 0xFF2F7BFF.toInt(),
        routeCasing = 0xFFFFFFFF.toInt(),
        traveledRoute = 0xFFA7B0BF.toInt(),
        poiOpacity = 0.85f,
        baseLabelOpacity = 1.0f,
    )

    val BrowseNight = CartographyPalette(
        background = 0xFF070D1B.toInt(),
        land = 0xFF0A1224.toInt(),
        water = 0xFF0B263A.toInt(),
        park = 0xFF10271F.toInt(),
        building = 0xFF172238.toInt(),
        minorRoad = 0xFF1B2943.toInt(),
        majorRoad = 0xFF263652.toInt(),
        motorway = 0xFF334A6D.toInt(),
        roadCasing = 0xFF07101E.toInt(),
        label = 0xFFD9E4F4.toInt(),
        labelHalo = 0xFF0A1224.toInt(),
        route = 0xFF5A91FF.toInt(),
        routeCasing = 0xFFDCE8FF.toInt(),
        traveledRoute = 0xFF56647A.toInt(),
        poiOpacity = 0.72f,
        baseLabelOpacity = 0.9f,
    )

    val NavigationDay = BrowseDay.copy(
        background = 0xFFF2F5F9.toInt(),
        land = 0xFFF8FAFC.toInt(),
        minorRoad = 0xFFF7F8FA.toInt(),
        majorRoad = 0xFFFFFFFF.toInt(),
        motorway = 0xFFEDF2F8.toInt(),
        route = 0xFF2468E0.toInt(),
        routeCasing = 0xFFFFFFFF.toInt(),
        traveledRoute = 0xFFB2BAC7.toInt(),
        poiOpacity = 0.42f,
        baseLabelOpacity = 0.78f,
    )

    val NavigationNight = BrowseNight.copy(
        route = 0xFF6A9CFF.toInt(),
        routeCasing = 0xFFE7EEFF.toInt(),
        traveledRoute = 0xFF4C596D.toInt(),
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
