import org.jetbrains.kotlin.gradle.dsl.JvmTarget

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

    // CameraX (Phase 5, Live camera preview). Preview use cases only; no ImageAnalysis yet.
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    // Unit tests run against the Android stub jar, where org.json methods return defaults
    // instead of parsing. This test-only dependency shadows the stub with a real implementation
    // so NominatimResponseParser (production code, uses the platform org.json) is testable here.
    testImplementation(libs.json)
}
