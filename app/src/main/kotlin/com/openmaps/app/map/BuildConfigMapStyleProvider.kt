package com.openmaps.app.map

import com.openmaps.app.BuildConfig

/**
 * Reads the style configured at build time (see app/build.gradle.kts and
 * docs/SETUP.md "Changing the map style"). Swap this class for a PMTiles/Protomaps/
 * self-hosted provider later; nothing else in the app needs to change.
 */
class BuildConfigMapStyleProvider : MapStyleProvider {
    override fun resolve(): MapStyleSource = MapStyleSource.Url(
        url = BuildConfig.MAP_STYLE_URL,
        name = BuildConfig.MAP_STYLE_NAME,
        isDevelopmentOnly = BuildConfig.MAP_STYLE_DEV_ONLY,
    )
}
