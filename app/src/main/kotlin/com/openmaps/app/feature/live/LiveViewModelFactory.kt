package com.openmaps.app.feature.live

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.openmaps.app.AppContainer
import com.openmaps.app.camera.CameraXRearEngine
import com.openmaps.app.camera.RearCameraEngine
import com.openmaps.app.core.spatial.SpatialLocalizationEngine

/**
 * Builds [LiveViewModel] from the app container (no DI framework), mirroring
 * `mapViewModelFactory`. Kept as a factory (rather than the parameterless `viewModel()` Live used
 * before Phase 6) because Live now needs two injected engines.
 *
 * Both engines are created **once** and shared with the ViewModel, so repeated Map <-> Live cycles
 * reuse the same instances rather than building a new camera provider or AR session each time.
 */
fun liveViewModelFactory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
    initializer {
        LiveViewModel(
            // Phase 5 fallback camera. Only composed when ARCore cannot run, but always available so
            // Live degrades to it rather than showing nothing.
            cameraEngine = CameraXRearEngine() as RearCameraEngine,
            // Phase 6: ARCore engine (support checked on start, never at construction).
            spatialEngine = container.spatialLocalizationEngine,
        )
    }
}