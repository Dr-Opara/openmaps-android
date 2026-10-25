package com.openmaps.app.core.places.google

import android.content.Context
import android.content.ContextWrapper
import android.view.View
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import com.openmaps.app.R
import com.openmaps.app.core.designsystem.OpenMapsColors
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Result of the most recent attempt to load Google's Place Details widget for a destination.
 * [Unavailable] covers every failure case uniformly — load error, no match nearby, AND a resolved
 * place that [placeLikelyMatches] rejects as probably the wrong business. This is a supplementary
 * section, so it degrades to nothing rather than ever risking showing another business's (or
 * another branch of the same chain's) photos or reviews under this destination's name.
 */
private enum class GooglePlaceDetailsLoad { Loading, Loaded, Unavailable }

/**
 * Embeds Google's Places UI Kit `PlaceDetailsFragment` as an additional, clearly separate section
 * of the destination sheet (Phase 1, Issue #78) — NOT a replacement for OpenMaps' own Reviews/
 * Photos tabs, which stay on their own real-provider roadmap. Uses the full fragment, not
 * `PlaceDetailsCompactFragment`: only the full fragment's `Content` enum includes
 * `PHONE_NUMBER`/`WEBSITE`/`OPENING_HOURS`/`REVIEWS`, which is what this phase asked for.
 *
 * Renders nothing when [BuildConfig.GOOGLE_PLACES_UI_KIT_CONFIGURED] is false (no API key
 * configured) or when the host Activity has no [FragmentManager] — both fail silently closed
 * rather than crashing.
 *
 * OpenMaps resolves destinations from OSM/Nominatim, not Google Place IDs, so this loads by
 * coordinates ([PlaceDetailsFragment.loadWithCoordinates]) rather than by Place ID — Google's own
 * nearest-place match, which can resolve to the wrong business at a shared address (a strip mall,
 * food court, or another branch of the same chain). [GooglePlaceDetailsEngine] checks the loaded
 * [Place]'s name and location against [expectedName]/the requested coordinates
 * ([placeLikelyMatches]) before ever surfacing it; a weak match is treated identically to a load
 * failure. Google bills each `loadWithCoordinates` call; the engine only issues one per distinct
 * (latitude, longitude) pair for the lifetime of this composable, never on every recomposition.
 */
@Composable
fun GooglePlaceDetailsCard(
    expectedName: String,
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
    val showWidget = load != GooglePlaceDetailsLoad.Unavailable

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "More from Google",
            style = MaterialTheme.typography.titleMedium,
        )
        AndroidView(
            // Collapsed to zero height on failure/rejected-match rather than removed from
            // composition entirely: the Fragment/container relationship stays consistent (no
            // Fragment left attached to a container view that no longer exists in the hierarchy),
            // and there is no large blank widget left behind for the user to see.
            modifier = if (showWidget) {
                Modifier.fillMaxWidth().heightIn(min = 280.dp)
            } else {
                Modifier.fillMaxWidth().height(0.dp)
            },
            factory = { context -> engine.createContainer(context) },
            update = { container ->
                engine.ensureLoaded(
                    containerId = container.id,
                    expectedName = expectedName,
                    latitude = latitude,
                    longitude = longitude,
                    onResult = { result -> load = result },
                )
            },
        )
        if (!showWidget) {
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
 * Places API call), checks the result against [expectedName]/the requested coordinates before
 * reporting it as [GooglePlaceDetailsLoad.Loaded], and removes it from the [FragmentManager] on
 * disposal. Mirrors the factory/bind/release split
 * [com.openmaps.app.camera.RearCameraHost] uses for CameraX.
 *
 * The fragment tag is derived from the container's own view id (unique per [AndroidView] instance
 * via [View.generateViewId]), not a shared constant — two [GooglePlaceDetailsCard]s alive at once
 * (e.g. `DestinationDetailsSheet` and `PlaceDetailsSheet` both mounted) must not remove each
 * other's fragment from the same [FragmentManager].
 */
private class GooglePlaceDetailsEngine(private val fragmentManager: FragmentManager) {
    private var loadedKey: String? = null
    private var disposed = false
    private var containerId: Int? = null

    fun createContainer(context: Context): FragmentContainerView =
        FragmentContainerView(context).apply {
            id = View.generateViewId()
            containerId = id
        }

    fun ensureLoaded(
        containerId: Int,
        expectedName: String,
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
            // R.style.OpenMapsPlacesDetailsTheme (not the library's own R.style.PlacesMaterialTheme
            // — that symbol does not resolve from app code; see res/values/styles.xml for why) is a
            // trivial extension of it, so this still follows Theme.AppCompat.DayNight's own
            // day/night resolution automatically. OpenMaps has no independent light/dark decision
            // on this branch yet (that lands with the separate night-mode work in progress
            // elsewhere) — see docs/SETUP.md "Google Places UI Kit provisioning" for the
            // follow-up this implies once the two branches merge.
            R.style.OpenMapsPlacesDetailsTheme,
        )
        fragment.setPlaceLoadListener(object : PlaceLoadListener {
            override fun onSuccess(place: Place) {
                if (disposed) return
                val accepted = placeLikelyMatches(place, expectedName, latitude, longitude)
                onResult(if (accepted) GooglePlaceDetailsLoad.Loaded else GooglePlaceDetailsLoad.Unavailable)
            }

            override fun onFailure(exception: Exception) {
                if (disposed) return
                onResult(GooglePlaceDetailsLoad.Unavailable)
            }
        })
        fragmentManager.beginTransaction()
            .replace(containerId, fragment, tagFor(containerId))
            .commitNowAllowingStateLoss()
        fragment.loadWithCoordinates(LatLng(latitude, longitude))
    }

    fun release() {
        disposed = true
        containerId?.let { id ->
            fragmentManager.findFragmentByTag(tagFor(id))?.let { fragment ->
                fragmentManager.beginTransaction().remove(fragment).commitNowAllowingStateLoss()
            }
        }
        loadedKey = null
    }

    private fun tagFor(id: Int) = "$TAG_PREFIX$id"

    private companion object {
        const val TAG_PREFIX = "google_place_details_fragment_"
    }
}

/**
 * True only when [place] is a plausible match for the business at ([latitude], [longitude])
 * believed to be named [expectedName] — never when uncertain. Two independent signals, both
 * required: the resolved place must be genuinely close to the requested point (loose enough for
 * normal GPS/geocoding slop, tight enough to reject a different unit in the same plaza), AND its
 * name must share a meaningful fraction of its significant words with [expectedName] (loose enough
 * for punctuation/ampersand/"The" differences, tight enough to reject "Starbucks" vs. a
 * same-plaza "Chipotle", or one chain branch's exact address vs. another's three blocks over).
 */
internal fun placeLikelyMatches(
    place: Place,
    expectedName: String,
    latitude: Double,
    longitude: Double,
): Boolean {
    val location = place.location ?: return false
    val distanceMeters = metersBetween(latitude, longitude, location.latitude, location.longitude)
    if (distanceMeters > MAX_MATCH_DISTANCE_METERS) return false
    return significantNameOverlapRatio(expectedName, place.displayName.orEmpty()) >= MIN_NAME_OVERLAP_RATIO
}

private const val MAX_MATCH_DISTANCE_METERS = 300.0
private const val MIN_NAME_OVERLAP_RATIO = 0.4f

private val NAME_STOPWORDS = setOf(
    "the", "a", "an", "and", "of", "at", "in", "on",
)

internal fun significantNameOverlapRatio(a: String, b: String): Float {
    fun tokens(s: String) = s.lowercase()
        .replace(Regex("[^a-z0-9 ]"), " ")
        .split(" ")
        .filter { it.isNotBlank() && it !in NAME_STOPWORDS }
        .toSet()
    val ours = tokens(a)
    val theirs = tokens(b)
    if (ours.isEmpty() || theirs.isEmpty()) return 0f
    val overlap = ours.intersect(theirs).size
    val smaller = min(ours.size, theirs.size)
    return overlap.toFloat() / smaller
}

private fun metersBetween(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val earthRadiusMeters = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    return earthRadiusMeters * c
}

private tailrec fun Context.findFragmentActivity(): FragmentActivity? = when (this) {
    is FragmentActivity -> this
    is ContextWrapper -> baseContext.findFragmentActivity()
    else -> null
}
