package com.openmaps.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.fragment.app.FragmentActivity
import com.openmaps.app.core.designsystem.OpenMapsTheme

// FragmentActivity (not plain ComponentActivity, which it extends) so MainActivity exposes
// supportFragmentManager: the Google Places UI Kit's PlaceDetailsFragment (Phase 1, Issue #78)
// is an androidx.fragment.app.Fragment and needs a FragmentManager to host it from Compose.
class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        // Must run before super.onCreate: swaps the splash theme for Theme.OpenMaps.
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as OpenMapsApplication).container
        setContent {
            OpenMapsTheme {
                OpenMapsApp(container)
            }
        }
    }
}
