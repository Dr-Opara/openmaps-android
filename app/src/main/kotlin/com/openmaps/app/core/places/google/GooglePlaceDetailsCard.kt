package com.openmaps.app.core.places.google

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.FragmentContainerView
import androidx.fragment.app.FragmentManager
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.widget.PlaceDetailsFragment
import com.google.android.libraries.places.widget.PlaceLoadListener
import com.google.android.libraries.places.widget.model.Orientation
import com.openmaps.app.BuildConfig
import com.openmaps.app.core.designsystem.OpenMapsColors

/**
 * Result of the most recent attempt to load Google's Place Details widget for a destination.
 * [Unavailable] covers every failure case (no match, network error, quota, etc.) uniformly: this
 * is a supplementary section, so it degrades to nothing rather than surfacing provider internals.
 */
private enum class GooglePlaceDetailsLoad { Loading, Loaded, Unavailable }

/**
 * Embeds Google's Places UI Kit `PlaceDetailsFragment` as an additional, clearly separate section
 * of the destination sheet (Phase 1, Issue #78). Uses the full fragment, not
 * `PlaceDetailsCompactFragment`: only the full fragment's `Content` enum includes
 * `PHONE_NUMBER`/`WEBSITE`/`OPENING_HOURS`/`REVIEWS`, which is what this phase asked for.
 *
 * Renders nothing when [BuildConfig.GOOGLE_PLACES_UI_KIT_CONFIGURED] is false (no API key
 * configured) or when the host Activity has no [FragmentManager] — both fail silently closed
 * rather than crashing.
 *
 * OpenMaps resolves destinations from OSM/Nominatim, not Google Place IDs, so this loads by
 * coordinates ([PlaceDetailsFragment.loadWithCoordinates]) rather than by Place ID. Google bills
 * each `loadWithCoordinates` call; [GooglePlaceDetailsEngine] only issues one per distinct
 * (latitude, longitude) pair for the lifetime of this composable, never on every recomposition.
 */
@Composable
fun GooglePlaceDetailsCard(
    latitude: Double,
    longitude: Double,
    modifier: Modifier = Modifier,
) {
    if (!BuildConfig.GOOGLE_PLACES_UI_KIT_CONFIGURED) return
    val fragmentActivity = LocalContext.current.findFragmentActivity() ?: return
    val engine = remember(fragmentActivity) {
        GooglePlaceDetailsEngine(fragmentActivity.supportFragmentManager)
    }
    var load by remember(engine) { mutableStateOf(GooglePlaceDetailsLoad.Loading) }

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "More from Google",
            style = MaterialTheme.typography.titleMedium,
        )
        AndroidView(
            modifier = Modifier.fillMaxWidth().heightIn(min = 280.dp),
            factory = { context -> engine.createContainer(context) },
            update = { container ->
                engine.ensureLoaded(
                    containerId = container.id,
                    latitude = latitude,
                    longitude = longitude,
                    onResult = { result -> load = result },
                )
            },
        )
        if (load == GooglePlaceDetailsLoad.Unavailable) {
            Text(
                text = "Additional details from Google aren't available for this location right now.",
                style = MaterialTheme.typography.bodySmall,
                color = OpenMapsColors.TextSecondary,
            )
        }
    }

    DisposableEffect(engine) {
        onDispose { engine.release() }
    }
}

/**
 * Owns the one [PlaceDetailsFragment] this card hosts: creates its container, loads it exactly
 * once per distinct coordinate pair (never on every recomposition — each load is a billable
 * Places API call), and removes it from the [FragmentManager] on disposal. Mirrors the
 * factory/bind/release split [com.openmaps.app.camera.RearCameraHost] uses for CameraX.
 */
private class GooglePlaceDetailsEngine(private val fragmentManager: FragmentManager) {
    private var loadedKey: String? = null

    fun createContainer(context: Context): FragmentContainerView =
        FragmentContainerView(context).apply { id = View.generateViewId() }

    fun ensureLoaded(
        containerId: Int,
        latitude: Double,
        longitude: Double,
        onResult: (GooglePlaceDetailsLoad) -> Unit,
    ) {
        val key = "$latitude,$longitude"
        if (key == loadedKey) return
        loadedKey = key
        onResult(GooglePlaceDetailsLoad.Loading)

        val fragment = PlaceDetailsFragment.newInstance(
            PlaceDetailsFragment.ALL_CONTENT,
            Orientation.VERTICAL,
        )
        fragment.setPlaceLoadListener(object : PlaceLoadListener {
            override fun onSuccess(place: Place) {
                onResult(GooglePlaceDetailsLoad.Loaded)
            }

            override fun onFailure(exception: Exception) {
                onResult(GooglePlaceDetailsLoad.Unavailable)
            }
        })
        fragmentManager.beginTransaction()
            .replace(containerId, fragment, TAG)
            .commitNowAllowingStateLoss()
        fragment.loadWithCoordinates(LatLng(latitude, longitude))
    }

    fun release() {
        fragmentManager.findFragmentByTag(TAG)?.let { fragment ->
            fragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
        }
        loadedKey = null
    }

    private companion object {
        const val TAG = "google_place_details_fragment"
    }
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
