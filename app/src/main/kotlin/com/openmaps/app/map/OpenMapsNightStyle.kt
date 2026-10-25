package com.openmaps.app.map

/** Use Stadia Maps' actual companion dark style, preserving the original API-key query string.
 * For custom/self-hosted/OpenFreeMap styles the engine keeps its original style and applies the
 * existing OpenMapsCartography palette; never fabricate an unverified dark tile endpoint.
 */
object OpenMapsNightStyle {
    private const val BASE = "/styles/alidade_smooth.json"
    private const val DARK = "/styles/alidade_smooth_dark.json"
    fun isSupported(source: MapStyleSource): Boolean =
        source is MapStyleSource.Url &&
            source.url.startsWith("https://tiles.stadiamaps.com/styles/") &&
            (source.url.contains(BASE) || source.url.contains(DARK))

    fun resolve(source: MapStyleSource, night: Boolean): MapStyleSource {
        if (!isSupported(source)) return source
        val url = (source as MapStyleSource.Url).url
        return source.copy(
            url = if (night) url.replace(BASE, DARK) else url.replace(DARK, BASE),
            name = if (night) "Stadia Maps · Night" else "Stadia Maps · Day",
        )
    }
}
