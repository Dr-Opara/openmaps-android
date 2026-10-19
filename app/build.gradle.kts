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
val stadiaMapsApiKey = providers.gradleProperty("STADIA_MAPS_API_KEY")
    .orElse(providers.environmentVariable("STADIA_MAPS_API_KEY"))
    .orElse(localProperties.getProperty("STADIA_MAPS_API_KEY") ?: "")
    .getOrElse("")
    .trim()
val stadiaStyleId = providers.gradleProperty("openmaps.stadia.style")
    .orElse("alidade_smooth")
    .get()
val stadiaStyleUrl = if (stadiaMapsApiKey.isNotBlank()) {
    "https://tiles.stadiamaps.com/styles/$stadiaStyleId.json?api_key=$stadiaMapsApiKey"
} else {
    ""
}
val explicitStyleUrl = providers.gradleProperty("openmaps.style.url").orNull?.trim().orEmpty()
val styleUrl = when {
    explicitStyleUrl.isNotBlank() -> explicitStyleUrl
    stadiaStyleUrl.isNotBlank() -> stadiaStyleUrl
    else -> defaultStyleUrl
}
val defaultStyleName = if (stadiaStyleUrl.isNotBlank()) "Stadia Maps" else "OpenFreeMap Liberty"
val styleName = providers.gradleProperty("openmaps.style.name").orElse(defaultStyleName).get()
val styleDevOnly = providers.gradleProperty("openmaps.style.devOnly")
    .orElse(if (stadiaStyleUrl.isNotBlank()) "false" else "true")
    .get()
    .toBoolean()

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
// OpenMaps production backend gateway.
//
// One base URL identifies the OpenMaps service layer. Individual endpoint overrides remain for
// operational migration/rollback, but Android no longer needs nine unrelated backend origins.
// The Supabase publishable key is intentionally safe for public/mobile clients. Provider secrets
// never ship in the APK; they stay behind the Edge Function. CI/local builds can override either
// value with -P, environment variables, or local.properties.
// ---------------------------------------------------------------------------
val defaultBackendBaseUrl = "https://upcfmhcfuagloqlcmezr.supabase.co/functions/v1"
val backendBaseUrl = providers.gradleProperty("OPENMAPS_BACKEND_BASE_URL")
    .orElse(providers.environmentVariable("OPENMAPS_BACKEND_BASE_URL"))
    .orElse(localProperties.getProperty("OPENMAPS_BACKEND_BASE_URL") ?: defaultBackendBaseUrl)
    .getOrElse(defaultBackendBaseUrl)
    .trimEnd('/')

val defaultGeocodingGatewayUrl = "$backendBaseUrl/geocode"
val defaultReverseGeocodingGatewayUrl = "$backendBaseUrl/reverse-geocode"
val defaultRoutingGatewayUrl = "$backendBaseUrl/route"
val defaultTrafficIncidentsGatewayUrl = "$backendBaseUrl/traffic-incidents"
val defaultLaneGuidanceGatewayUrl = "$backendBaseUrl/lane-guidance"
val defaultPlacesGatewayUrl = "$backendBaseUrl/places"
val defaultTripShareGatewayUrl = "$backendBaseUrl/trip-share"
val defaultCommunityReportsGatewayUrl = "$backendBaseUrl/community-reports"
val defaultVoiceGatewayUrl = "$backendBaseUrl/voice"
val defaultReliabilityGatewayUrl = "$backendBaseUrl/reliability"
val defaultBackendPublishableKey =
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

val communityReportsGatewayUrl = providers.gradleProperty("OPENMAPS_COMMUNITY_REPORTS_GATEWAY_URL")
    .orElse(providers.environmentVariable("OPENMAPS_COMMUNITY_REPORTS_GATEWAY_URL"))
    .orElse(localProperties.getProperty("OPENMAPS_COMMUNITY_REPORTS_GATEWAY_URL") ?: defaultCommunityReportsGatewayUrl)
    .getOrElse(defaultCommunityReportsGatewayUrl)

val voiceGatewayUrl = providers.gradleProperty("OPENMAPS_VOICE_GATEWAY_URL")
    .orElse(providers.environmentVariable("OPENMAPS_VOICE_GATEWAY_URL"))
    .orElse(localProperties.getProperty("OPENMAPS_VOICE_GATEWAY_URL") ?: defaultVoiceGatewayUrl)
    .getOrElse(defaultVoiceGatewayUrl)

val reliabilityGatewayUrl = providers.gradleProperty("OPENMAPS_RELIABILITY_GATEWAY_URL")
    .orElse(providers.environmentVariable("OPENMAPS_RELIABILITY_GATEWAY_URL"))
    .orElse(localProperties.getProperty("OPENMAPS_RELIABILITY_GATEWAY_URL") ?: defaultReliabilityGatewayUrl)
    .getOrElse(defaultReliabilityGatewayUrl)

val legacyGeocodingPublishableKey = providers.gradleProperty("OPENMAPS_GEOCODING_PUBLISHABLE_KEY")
    .orElse(providers.environmentVariable("OPENMAPS_GEOCODING_PUBLISHABLE_KEY"))
    .orElse(localProperties.getProperty("OPENMAPS_GEOCODING_PUBLISHABLE_KEY") ?: defaultBackendPublishableKey)
    .getOrElse(defaultBackendPublishableKey)
val backendPublishableKey = providers.gradleProperty("OPENMAPS_BACKEND_PUBLISHABLE_KEY")
    .orElse(providers.environmentVariable("OPENMAPS_BACKEND_PUBLISHABLE_KEY"))
    .orElse(localProperties.getProperty("OPENMAPS_BACKEND_PUBLISHABLE_KEY") ?: legacyGeocodingPublishableKey)
    .getOrElse(legacyGeocodingPublishableKey)


/**
 * Release signing is configured only from local/CI secrets. Nothing from this block is emitted into
 * BuildConfig, resources, the manifest or source control.
 *
 * Resolution order for every value: Gradle property -> environment variable -> local.properties.
 */
fun secretSetting(name: String): String =
    providers.gradleProperty(name)
        .orElse(providers.environmentVariable(name))
        .orElse(localProperties.getProperty(name) ?: "")
        .getOrElse("")
        .trim()

val releaseStoreFilePath = secretSetting("OPENMAPS_RELEASE_STORE_FILE")
val releaseStorePassword = secretSetting("OPENMAPS_RELEASE_STORE_PASSWORD")
val releaseKeyAlias = secretSetting("OPENMAPS_RELEASE_KEY_ALIAS")
val releaseKeyPassword = secretSetting("OPENMAPS_RELEASE_KEY_PASSWORD")
val releaseSigningConfigured =
    releaseStoreFilePath.isNotBlank() &&
        releaseStorePassword.isNotBlank() &&
        releaseKeyAlias.isNotBlank() &&
        releaseKeyPassword.isNotBlank() &&
        rootProject.file(releaseStoreFilePath).exists()

android {
    namespace = "com.openmaps.app"
    compileSdk = 35

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = rootProject.file(releaseStoreFilePath)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = true
            }
        }
    }

    defaultConfig {
        applicationId = "com.openmaps.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-phase1"

        buildConfigField("String", "MAP_STYLE_URL", "\"$styleUrl\"")
        buildConfigField("String", "MAP_STYLE_PROVIDER", "\"${if (stadiaStyleUrl.isNotBlank()) "stadia" else "development"}\"")
        buildConfigField("boolean", "OFFLINE_MAP_CACHE_ENABLED", (!styleDevOnly && stadiaStyleUrl.isNotBlank()).toString())
        buildConfigField("long", "OFFLINE_MAP_CACHE_LIMIT_BYTES", "94371840L")
        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
        buildConfigField("String", "BACKEND_PUBLISHABLE_KEY", "\"$backendPublishableKey\"")
        buildConfigField("String", "MAP_STYLE_NAME", "\"$styleName\"")
        buildConfigField("boolean", "MAP_STYLE_DEV_ONLY", styleDevOnly.toString())
        buildConfigField("String", "GEOCODING_GATEWAY_URL", "\"$geocodingGatewayUrl\"")
        buildConfigField("String", "REVERSE_GEOCODING_GATEWAY_URL", "\"$reverseGeocodingGatewayUrl\"")
        buildConfigField("String", "ROUTING_GATEWAY_URL", "\"$routingGatewayUrl\"")
        buildConfigField("String", "TRAFFIC_INCIDENTS_GATEWAY_URL", "\"$trafficIncidentsGatewayUrl\"")
        buildConfigField("String", "LANE_GUIDANCE_GATEWAY_URL", "\"$laneGuidanceGatewayUrl\"")
        buildConfigField("String", "PLACES_GATEWAY_URL", "\"$placesGatewayUrl\"")
        buildConfigField("String", "TRIP_SHARE_GATEWAY_URL", "\"$tripShareGatewayUrl\"")
        buildConfigField("String", "COMMUNITY_REPORTS_GATEWAY_URL", "\"$communityReportsGatewayUrl\"")
        buildConfigField("String", "VOICE_GATEWAY_URL", "\"$voiceGatewayUrl\"")
        buildConfigField("String", "RELIABILITY_GATEWAY_URL", "\"$reliabilityGatewayUrl\"")
        buildConfigField("String", "GEOCODING_PUBLISHABLE_KEY", "\"$backendPublishableKey\"")

        // Feeds the `com.google.android.ar.API_KEY` meta-data in AndroidManifest.xml.
        manifestPlaceholders["ARCORE_API_KEY"] = arcoreApiKey
    }

    buildTypes {
        release {
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
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

tasks.register("verifyProductionMapConfiguration") {
    group = "verification"
    description = "Fails unless a production Stadia Maps key/provider is configured."
    doLast {
        check(stadiaMapsApiKey.isNotBlank()) {
            "Production map provider is not configured. Provide STADIA_MAPS_API_KEY through a " +
                "Gradle property, environment variable, or git-ignored local.properties."
        }
        check(!styleDevOnly) {
            "Production map style is still marked development-only."
        }
        check(styleUrl.startsWith("https://tiles.stadiamaps.com/styles/")) {
            "Production map style must use the approved Stadia Maps tile host."
        }
    }
}

tasks.register("verifyReleaseSigningConfiguration") {
    group = "verification"
    description = "Fails unless all production release-signing secrets are configured and the keystore exists."
    doLast {
        check(releaseSigningConfigured) {
            "Release signing is not configured. Provide OPENMAPS_RELEASE_STORE_FILE, " +
                "OPENMAPS_RELEASE_STORE_PASSWORD, OPENMAPS_RELEASE_KEY_ALIAS and " +
                "OPENMAPS_RELEASE_KEY_PASSWORD through Gradle properties, environment variables, " +
                "or git-ignored local.properties."
        }
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
