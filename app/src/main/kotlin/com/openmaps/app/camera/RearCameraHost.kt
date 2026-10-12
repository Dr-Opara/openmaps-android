package com.openmaps.app.camera

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView

/**
 * Hosts a [RearCameraEngine]'s view in Compose, mirroring `map.MapLibreHost`:
 *
 *  - only stable inputs (engine, an event callback, a modifier), and **no `update` block**, so
 *    ordinary state changes elsewhere on the screen never touch the camera view;
 *  - `factory` creates the view exactly once and the engine is created once by `remember`;
 *  - `bind` runs in a `LaunchedEffect` (after the composition is applied) and `release` in a
 *    `DisposableEffect`, so leaving Live tears the camera down and Map -> Live -> Map cycles do not
 *    accumulate bindings.
 *
 * The camera is bound to the composition's lifecycle owner, so CameraX closes it whenever the app
 * is backgrounded — there is no manual pause/resume here to get wrong.
 */
@Composable
fun RearCameraHost(
    engine: RearCameraEngine,
    onEvent: (CameraEngineEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    val currentOnEvent by rememberUpdatedState(onEvent)

    AndroidView(
        factory = { viewContext -> engine.createView(viewContext) },
        modifier = modifier,
    )

    LaunchedEffect(engine, lifecycleOwner) { engine.bind(lifecycleOwner) }

    DisposableEffect(engine) {
        onDispose { engine.release() }
    }

    LaunchedEffect(engine) { engine.events.collect { currentOnEvent(it) } }
}