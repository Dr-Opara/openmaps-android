package com.openmaps.app.map

/**
 * Reuses the authenticated Stadia style endpoint that ships in the existing build.
 * The query string (including the existing API key, when supplied) is preserved verbatim.
 * No second provider credential is introduced or logged.
 */
object StadiaSatelliteStyle {
    private val styleUrl = Regex(
        """^(https://tiles(?:-eu)?\.stadiamaps\.com)/styles/[^/?]+\.json(\?.*)?$""",
    )

    const val ATTRIBUTION =
        "© Stadia Maps © OpenMapTiles © OpenStreetMap contributors | " +
            "© CNES, Distribution Airbus DS, © Airbus DS, © PlanetObserver (Contains Copernicus Data)"

    fun fromStandard(standard: MapStyleSource): MapStyleSource.Url? {
        val url = (standard as? MapStyleSource.Url)?.url ?: return null
        if (standard.isDevelopmentOnly) return null
        val match = styleUrl.matchEntire(url) ?: return null
        val host = match.groupValues[1]
        val query = match.groupValues[2]
        return MapStyleSource.Url(
            url = "$host/styles/alidade_satellite.json$query",
            name = "Stadia Maps Satellite",
            isDevelopmentOnly = false,
            attribution = ATTRIBUTION,
        )
    }
}
