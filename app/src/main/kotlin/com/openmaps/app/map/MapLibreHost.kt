package com.openmaps.app.map

import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.openmaps.app.core.location.UserLocation
import kotlinx.coroutines.flow.Flow

/**
 * Hosts a [MapEngine]'s view in Compose.
 *
 * Performance contract: this composable takes only stable inputs (engine, flows, a callback),
 * and the AndroidView has no `update` block, so ordinary state changes elsewhere on the
 * screen never touch the map view. Data reaches the engine through the collected flows.
 */
@Composable
fun MapLibreHost(
    engine: MapEngine,
    commands: Flow<MapCommand>,
    userLocation: Flow<UserLocation?>,
    onEvent: (MapEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentOnEvent by rememberUpdatedState(onEvent)

    // `factory` runs once per AndroidView instance; the engine creates its MapView exactly once.
    AndroidView(
        factory = { viewContext -> engine.createView(viewContext) },
        modifier = modifier,
    )

    // Replays the lifecycle up to the current state when added, then follows it.
    DisposableEffect(lifecycle, engine) {
        val observer = LifecycleEventObserver { _, event -> engine.onLifecycleEvent(event) }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            engine.release()
        }
    }

    DisposableEffect(context, engine) {
        val callbacks = object : ComponentCallbacks2 {
            override fun onLowMemory() = engine.onLowMemory()
            override fun onTrimMemory(level: Int) = Unit
            override fun onConfigurationChanged(newConfig: Configuration) = Unit
        }
        context.registerComponentCallbacks(callbacks)
        onDispose { context.unregisterComponentCallbacks(callbacks) }
    }

    LaunchedEffect(engine) { engine.events.collect { currentOnEvent(it) } }
    LaunchedEffect(engine, commands) { commands.collect { engine.execute(it) } }
    LaunchedEffect(engine, userLocation) { userLocation.collect { engine.setUserLocation(it) } }
}
