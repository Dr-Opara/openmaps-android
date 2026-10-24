import org.jetbrains.kotlin.gradle.dsl.JvmTarget
// Explicit import: inside a Gradle script `java` resolves to Gradle's `java` extension, not the
// package, so `java.util.Properties` does not compile without this.
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

val openMapsVersionName = providers.gradleProperty("OPENMAPS_VERSION_NAME")
    .orElse(providers.environmentVariable("OPENMAPS_VERSION_NAME"))
    .orElse(localProperties.getProperty("OPENMAPS_VERSION_NAME") ?: "0.9.0-beta.1")
    .getOrElse("0.9.0-beta.1")
    .trim()
val openMapsVersionCode = providers.gradleProperty("OPENMAPS_VERSION_CODE")
    .orElse(providers.environmentVariable("OPENMAPS_VERSION_CODE"))
    .orElse(localProperties.getProperty("OPENMAPS_VERSION_CODE") ?: "9001")
    .getOrElse("9001")
    .trim()
    .toInt()

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
val usingStadiaMaps = styleUrl.startsWith("https://tiles.stadiamaps.com/styles/")
val defaultStyleName = if (usingStadiaMaps) "Stadia Maps" else "OpenFreeMap Liberty"
val styleName = providers.gradleProperty("openmaps.style.name").orElse(defaultStyleName).get()
val styleDevOnly = providers.gradleProperty("openmaps.style.devOnly")
    .orElse(if (usingStadiaMaps) "false" else "true")
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
// Note: `orElse(null)` is illegal in Gradle's Provider API (IllegalArgumentException), so the
// local.properties fallback is coerced to "" here rather than passed straight in.
val arcoreApiKey = providers.gradleProperty("ARCORE_API_KEY")
    .orElse(providers.environmentVariable("ARCORE_API_KEY"))
    .orElse(localProperties.getProperty("ARCORE_API_KEY") ?: "")
    .getOrElse("")
val arcoreProductionConfigured = arcoreApiKey.isNotBlank()

// ---------------------------------------------------------------------------
// Google Places UI Kit API key (Phase 1, Issue #78).
//
// A separate Google Cloud API key from ARCORE_API_KEY above — restricted to the
// Places API (New) rather than the ARCore API, with its own Android app
// (package + SHA-1) restriction. See docs/SETUP.md "Google Places UI Kit
// provisioning" for the Cloud Console side.
//
// Unlike the other providers in this file, the Places UI Kit widget
// (PlaceDetailsFragment) is a native Google-rendered client component: it
// calls Google's servers directly from the device, so this key necessarily
// ships inside the APK (restricted by package+fingerprint in Cloud Console,
// not hidden behind the OpenMaps backend gateway the way provider secrets
// for geocoding/routing/places-nearby are).
//
// Resolution order (first non-empty wins), same convention as ARCORE_API_KEY:
//   1. -PGOOGLE_PLACES_API_KEY=...   (CI / one-off)
//   2. GOOGLE_PLACES_API_KEY          (environment)
//   3. local.properties               -> GOOGLE_PLACES_API_KEY=...   (dev, git-ignored)
//
// Deliberately never committed, and defaults to empty so a build without
// credentials still compiles and installs; the feature stays hidden rather
// than crashing (see GooglePlaceDetailsCard.kt).
// ---------------------------------------------------------------------------
val googlePlacesApiKey = providers.gradleProperty("GOOGLE_PLACES_API_KEY")
    .orElse(providers.environmentVariable("GOOGLE_PLACES_API_KEY"))
    .orElse(localProperties.getProperty("GOOGLE_PLACES_API_KEY") ?: "")
    .getOrElse("")
val googlePlacesUiKitConfigured = googlePlacesApiKey.isNotBlank()

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
// Public Mapillary client token. Optional, for nearby street imagery only.
// It is intentionally not a private server secret; mobile build constants are inspectable.
val mapillaryClientToken = providers.gradleProperty("MAPILLARY_CLIENT_TOKEN")
    .orElse(providers.environmentVariable("MAPILLARY_CLIENT_TOKEN"))
    .orElse(localProperties.getProperty("MAPILLARY_CLIENT_TOKEN") ?: "")
    .getOrElse("")
    .trim()

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
        versionCode = openMapsVersionCode
        versionName = openMapsVersionName

        buildConfigField("String", "MAP_STYLE_URL", "\"$styleUrl\"")
        buildConfigField("String", "MAP_STYLE_PROVIDER", "\"${if (usingStadiaMaps) "stadia" else "development"}\"")
        buildConfigField("boolean", "OFFLINE_MAP_CACHE_ENABLED", (!styleDevOnly && usingStadiaMaps).toString())
        buildConfigField("long", "OFFLINE_MAP_CACHE_LIMIT_BYTES", "94371840L")
        buildConfigField("String", "BACKEND_BASE_URL", "\"$backendBaseUrl\"")
        buildConfigField("String", "MAPILLARY_CLIENT_TOKEN", "\"$mapillaryClientToken\"")
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
        buildConfigField("boolean", "ARCORE_GEOSPATIAL_CONFIGURED", arcoreProductionConfigured.toString())

        // Google Places UI Kit (Phase 1, Issue #78). No manifest meta-data entry: unlike ARCore,
        // the Places SDK (New) is initialized in code via Places.initializeWithNewPlacesApiEnabled,
        // not an AndroidManifest meta-data key.
        buildConfigField("String", "GOOGLE_PLACES_API_KEY", "\"$googlePlacesApiKey\"")
        buildConfigField("boolean", "GOOGLE_PLACES_UI_KIT_CONFIGURED", googlePlacesUiKitConfigured.toString())

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

    lint {
        // Places UI Kit (Phase 1, Issue #78) transitively pulls in Glide 4.11.0 for the widget's
        // own photo loading. Lint's NotificationPermission check fires on the mere presence of
        // Glide's NotificationTarget class on the classpath — it does not mean OpenMaps posts
        // notifications. Confirmed: no OpenMaps code references NotificationTarget or any
        // notification API; grep the repo before ever removing this if that changes.
        disable += "NotificationPermission"
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
        check(usingStadiaMaps) {
            "Production map style must use the approved Stadia Maps tile host."
        }
    }
}

tasks.register("verifyProductionArCoreConfiguration") {
    group = "verification"
    description = "Fails unless ARCore Geospatial production credentials are configured."
    doLast {
        check(arcoreProductionConfigured) {
            "ARCore Geospatial production key is not configured. Provide ARCORE_API_KEY through a " +
                "Gradle property, environment variable, or git-ignored local.properties."
        }
        check(arcoreApiKey.length >= 20) {
            "ARCORE_API_KEY is present but does not look like a complete Google API key."
        }
    }
}

tasks.register("verifyProductionReleaseConfiguration") {
    group = "verification"
    description = "Fails unless all production release prerequisites are configured."
    doLast {
        check(openMapsVersionCode > 0) { "OPENMAPS_VERSION_CODE must be a positive integer." }
        check(openMapsVersionName.isNotBlank()) { "OPENMAPS_VERSION_NAME must not be blank." }
        check(!openMapsVersionName.contains("phase", ignoreCase = true) &&
            !openMapsVersionName.contains("snapshot", ignoreCase = true) &&
            !openMapsVersionName.contains("dev", ignoreCase = true)
        ) {
            "Production version name must not contain phase/dev/snapshot markers: $openMapsVersionName"
        }
        check(usingStadiaMaps && stadiaMapsApiKey.isNotBlank() && !styleDevOnly) {
            "Production Stadia Maps configuration is incomplete."
        }
        check(arcoreProductionConfigured && arcoreApiKey.length >= 20) {
            "Production ARCore Geospatial configuration is incomplete."
        }
        check(releaseSigningConfigured) {
            "Production release signing configuration is incomplete."
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

    // androidx.fragment: MainActivity hosts FragmentManager-backed children (Phase 1, Issue #78)
    // for the Google Places UI Kit's PlaceDetailsFragment.
    implementation(libs.androidx.fragment.ktx)

    // Google Places UI Kit (Phase 1, Issue #78). Place Details widget only; runtime-gated on
    // BuildConfig.GOOGLE_PLACES_UI_KIT_CONFIGURED. MapLibre remains the map renderer.
    implementation(libs.google.places)
    // LatLng only, for PlaceDetailsCompactFragment.loadWithCoordinates — see libs.versions.toml.
    implementation(libs.google.play.services.maps)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Unit tests run against the Android stub jar, where org.json methods return defaults
    // instead of parsing. This test-only dependency shadows the stub with a real implementation
    // so NominatimResponseParser (production code, uses the platform org.json) is testable here.
    testImplementation(libs.json)
}
