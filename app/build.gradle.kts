import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// Explicit import: inside a Gradle script `java` resolves to Gradle's `java` extension, not the
// package, so `java.util.Properties` does not compile without this.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// ---------------------------------------------------------------------------
// Map style configuration (see docs/ARCHITECTURE.md "Map style configuration").
// Resolution order: -P / ~/.gradle/gradle.properties  ->  defaults below.
//
// DEFAULT = OpenFreeMap "liberty" (OpenStreetMap-based vector tiles, no API key).
// It is a free public instance without an SLA, so it is flagged DEVELOPMENT ONLY
// until you self-host / move to PMTiles or a paid provider.
// ---------------------------------------------------------------------------
val defaultStyleUrl = "https://tiles.openfreemap.org/styles/liberty"
val styleUrl = providers.gradleProperty("openmaps.style.url").orElse(defaultStyleUrl).get()
val styleName = providers.gradleProperty("openmaps.style.name").orElse("OpenFreeMap Liberty").get()
val styleDevOnly = providers.gradleProperty("openmaps.style.devOnly").orElse("true").get().toBoolean()

// ---------------------------------------------------------------------------
// ARCore Geospatial API key.
//
// ARCore's Geospatial mode is a Google Cloud service, so it needs an API key
// in AndroidManifest under the meta-data name `com.google.android.ar.API_KEY`.
// Without it the session starts, but `Earth` reports
// `ERROR_NOT_AUTHORIZED` and never tracks -- which is why AR arrows silently
// never appear. See docs/SETUP.md "ARCore Geospatial provisioning" for the
// Google Cloud side; this block is only the delivery mechanism.
//
// Resolution order (first non-empty wins):
//   1. -PARCORE_API_KEY=...   (CI / one-off)
//   2. ARCORE_API_KEY          (environment)
//   3. local.properties        -> ARCORE_API_KEY=...   (dev, git-ignored)
//
// It is deliberately NEVER committed, and defaults to empty so that a build
// without credentials still compiles, installs, and falls back to the CameraX
// path exactly as before.
// ---------------------------------------------------------------------------
val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Note: `orElse(null)` is illegal in Gradle's Provider API (IllegalArgumentException), so the
// local.properties fallback is coerced to "" here rather than passed straight in.
val arcoreApiKey = providers.gradleProperty("ARCORE_API_KEY")
    .orElse(providers.environmentVariable("ARCORE_API_KEY"))
    .orElse(localProperties.getProperty("ARCORE_API_KEY") ?: "")
    .getOrElse("")

// ---------------------------------------------------------------------------
// OpenMaps production geocoding gateway.
//
// The Supabase publishable key is intentionally safe for public/mobile clients. Provider secrets
// never ship in the APK; they stay behind the Edge Function. CI/local builds can override either
// value with -P, environment variables, or local.properties.
// ---------------------------------------------------------------------------
val defaultGeocodingGatewayUrl =
    "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1/geocode"
val defaultReverseGeocodingGatewayUrl =
    "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1/reverse-geocode"
val defaultRoutingGatewayUrl =
    "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1/route"
val defaultTrafficIncidentsGatewayUrl =
    "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1/traffic-incidents"
val defaultLaneGuidanceGatewayUrl =
    "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1/lane-guidance"
val defaultPlacesGatewayUrl =
    "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1/places"
val defaultTripShareGatewayUrl =
    "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1/trip-share"
val defaultGeocodingPublishableKey =
    "sb_publishable_gF9KreSTfMY24vhsaVd_bQ_WVGEqRYH"

val geocodingGatewayUrl = providers.gradleProperty("OPENMAPS_GEOCODING_GATEWAY_URL")
    .orElse(providers.environmentVariable("OPENMAPS_GEOCODING_GATEWAY_URL"))
    .orElse(localProperties.getProperty("OPENMAPS_GEOCODING_GATEWAY_URL") ?: defaultGeocodingGatewayUrl)
    .getOrElse(defaultGeocodingGatewayUrl)

val reverseGeocodingGatewayUrl = providers.gradleProperty("OPENMAPS_REVERSE_GEOCODING_GATEWAY_URL")
    .orElse(providers.environmentVariable("OPENMAPS_REVERSE_GEOCODING_GATEWAY_URL"))
    .orElse(localProperties.getProperty("OPENMAPS_REVERSE_GEOCODING_GATEWAY_URL") ?: defaultReverseGeocodingGatewayUrl)
    .getOrElse(defaultReverseGeocodingGatewayUrl)

val routingGatewayUrl = providers.gradleProperty("OPENMAPS_ROUTING_GATEWAY_URL")
    .orElse(providers.environmentVariable("OPENMAPS_ROUTING_GATEWAY_URL"))
    .orElse(localProperties.getProperty("OPENMAPS_ROUTING_GATEWAY_URL") ?: defaultRoutingGatewayUrl)
    .getOrElse(defaultRoutingGatewayUrl)

val trafficIncidentsGatewayUrl = providers.gradleProperty("OPENMAPS_TRAFFIC_INCIDENTS_GATEWAY_URL")
    .orElse(providers.environmentVariable("OPENMAPS_TRAFFIC_INCIDENTS_GATEWAY_URL"))
    .orElse(localProperties.getProperty("OPENMAPS_TRAFFIC_INCIDENTS_GATEWAY_URL") ?: defaultTrafficIncidentsGatewayUrl)
    .getOrElse(defaultTrafficIncidentsGatewayUrl)

val laneGuidanceGatewayUrl = providers.gradleProperty("OPENMAPS_LANE_GUIDANCE_GATEWAY_URL")
    .orElse(providers.environmentVariable("OPENMAPS_LANE_GUIDANCE_GATEWAY_URL"))
    .orElse(localProperties.getProperty("OPENMAPS_LANE_GUIDANCE_GATEWAY_URL") ?: defaultLaneGuidanceGatewayUrl)
    .getOrElse(defaultLaneGuidanceGatewayUrl)

val placesGatewayUrl = providers.gradleProperty("OPENMAPS_PLACES_GATEWAY_URL")
    .orElse(providers.environmentVariable("OPENMAPS_PLACES_GATEWAY_URL"))
    .orElse(localProperties.getProperty("OPENMAPS_PLACES_GATEWAY_URL") ?: defaultPlacesGatewayUrl)
    .getOrElse(defaultPlacesGatewayUrl)

val tripShareGatewayUrl = providers.gradleProperty("OPENMAPS_TRIP_SHARE_GATEWAY_URL")
    .orElse(providers.environmentVariable("OPENMAPS_TRIP_SHARE_GATEWAY_URL"))
    .orElse(localProperties.getProperty("OPENMAPS_TRIP_SHARE_GATEWAY_URL") ?: defaultTripShareGatewayUrl)
    .getOrElse(defaultTripShareGatewayUrl)

val geocodingPublishableKey = providers.gradleProperty("OPENMAPS_GEOCODING_PUBLISHABLE_KEY")
    .orElse(providers.environmentVariable("OPENMAPS_GEOCODING_PUBLISHABLE_KEY"))
    .orElse(localProperties.getProperty("OPENMAPS_GEOCODING_PUBLISHABLE_KEY") ?: defaultGeocodingPublishableKey)
    .getOrElse(defaultGeocodingPublishableKey)

android {
    namespace = "com.openmaps.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.openmaps.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-phase1"

        buildConfigField("String", "MAP_STYLE_URL", "\"$styleUrl\"")
        buildConfigField("String", "MAP_STYLE_NAME", "\"$styleName\"")
        buildConfigField("boolean", "MAP_STYLE_DEV_ONLY", styleDevOnly.toString())
        buildConfigField("String", "GEOCODING_GATEWAY_URL", "\"$geocodingGatewayUrl\"")
        buildConfigField("String", "REVERSE_GEOCODING_GATEWAY_URL", "\"$reverseGeocodingGatewayUrl\"")
        buildConfigField("String", "ROUTING_GATEWAY_URL", "\"$routingGatewayUrl\"")
        buildConfigField("String", "TRAFFIC_INCIDENTS_GATEWAY_URL", "\"$trafficIncidentsGatewayUrl\"")
        buildConfigField("String", "LANE_GUIDANCE_GATEWAY_URL", "\"$laneGuidanceGatewayUrl\"")
        buildConfigField("String", "PLACES_GATEWAY_URL", "\"$placesGatewayUrl\"")
        buildConfigField("String", "TRIP_SHARE_GATEWAY_URL", "\"$tripShareGatewayUrl\"")
        buildConfigField("String", "GEOCODING_PUBLISHABLE_KEY", "\"$geocodingPublishableKey\"")

        // Feeds the `com.google.android.ar.API_KEY` meta-data in AndroidManifest.xml.
        manifestPlaceholders["ARCORE_API_KEY"] = arcoreApiKey
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)

    implementation(libs.kotlinx.coroutines.android)

    // MapLibre Native (OpenStreetMap-compatible, no proprietary SDKs).
    implementation(libs.maplibre.android)

    // CameraX (Phase 5, Live camera preview + Phase 6 fallback camera when ARCore is absent).
    // Preview use cases only; no ImageAnalysis yet.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    // ARCore (Phase 6, Geospatial/Earth localization). Support is runtime-checked; the CameraX
    // engine remains the fallback when ARCore is unsupported, not installed, or unusable.
    implementation(libs.google.ar.core)
    // Required by ARCore's Geospatial mode at runtime, not merely transitively — confirmed on a
    // physical device in Phase 12A (see ARCHITECTURE.md "AR stuck at Idle"): without this,
    // Session.configure(GeospatialMode.ENABLED) throws
    // GooglePlayServicesLocationLibraryNotLinkedException and the session never starts.
    implementation(libs.google.play.services.location)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Unit tests run against the Android stub jar, where org.json methods return defaults
    // instead of parsing. This test-only dependency shadows the stub with a real implementation
    // so NominatimResponseParser (production code, uses the platform org.json) is testable here.
    testImplementation(libs.json)
}
