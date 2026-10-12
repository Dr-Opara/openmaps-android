package com.openmaps.app.camera

import android.content.Context
import android.util.Log
import android.util.Size
import android.view.View
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.Executor

/**
 * [RearCameraEngine] backed by CameraX.
 *
 * The only file in the app that imports CameraX. Everything above it (`LiveViewModel`, the HUD,
 * navigation) talks in [RearCameraState] and never learns that CameraX exists.
 *
 * Binding is **lifecycle-owner driven** rather than started/stopped by hand: CameraX closes the
 * camera when the owner stops and reopens it when it starts, so backgrounding the app cannot leave
 * the camera held. [release] unbinds explicitly, which is what makes repeated Map -> Live -> Map
 * cycles safe.
 *
 * Logging: this project has no logging convention and no Timber dependency, so `Log.w` is used
 * directly (hence the lint suppression). It fires only on the failure paths — a camera that will
 * not open is otherwise indistinguishable, from outside, from a black screen, and diagnosing that
 * on a physical device without any logcat output is the kind of thing that costs a day. Nothing is
 * logged per frame.
 */
@Suppress("LogNotTimber")
class CameraXRearEngine : RearCameraEngine {

    private val _events = MutableSharedFlow<CameraEngineEvent>(
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val events: Flow<CameraEngineEvent> = _events.asSharedFlow()

    private var provider: ProcessCameraProvider? = null
    private var previewView: PreviewView? = null
    private var boundOwner: LifecycleOwner? = null

    private var executor: Executor? = null

    override fun createView(context: Context): View {
        check(previewView == null) { "CameraXRearEngine.createView() was already called" }
        val view = PreviewView(context).apply {
            // FILL_CENTER fills the screen and crops, which keeps the road ahead visible under a
            // full-bleed HUD; FIT_CENTER would letterbox and shrink it. The aspect ratio is
            // preserved either way. COMPATIBLE (TextureView) rather than the default PERFORMANCE
            // mode so the preview composites cleanly with Compose overlays; the cost is one extra
            // copy per frame, which is affordable for a preview-only use case.
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
        previewView = view
        executor = ContextCompat.getMainExecutor(context)
        return view
    }

    override fun bind(lifecycleOwner: LifecycleOwner) {
        val view = previewView ?: return
        val exec = executor ?: ContextCompat.getMainExecutor(view.context).also { executor = it }
        if (boundOwner === lifecycleOwner && provider != null) return
        boundOwner = lifecycleOwner
        emit(RearCameraState.Starting)

        val future = ProcessCameraProvider.getInstance(view.context)
        future.addListener({
            val cameraProvider = try {
                future.get()
            } catch (t: Throwable) {
                // Provider unavailable entirely: no camera service, or the device has no camera.
                Log.w(TAG, "Rear camera provider unavailable", t)
                emit(RearCameraState.Unavailable(CameraFailure.NoRearCamera))
                return@addListener
            }
            provider = cameraProvider
            attachUseCases(cameraProvider, view)
        }, exec)
    }

    private fun attachUseCases(cameraProvider: ProcessCameraProvider, view: PreviewView) {
        val owner = boundOwner ?: return
        val selector = CameraSelector.Builder()
            .requireLensFacing(CameraSelector.LENS_FACING_BACK)
            .build()

        // A *strategy*, not a fixed size: pinning one size would break across the variety of
        // sensors in the device fleet, while letting CameraX choose stays correct without a
        // device allow-list. Preview-only, so 720p-class output is plenty and keeps decode cheap.
        val resolution = ResolutionSelector.Builder()
            .setResolutionStrategy(
                ResolutionStrategy(
                    Size(PREVIEW_TARGET_WIDTH, PREVIEW_TARGET_HEIGHT),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                ),
            )
            .build()

        val preview = Preview.Builder()
            .setResolutionSelector(resolution)
            .build()
            .also { it.surfaceProvider = view.surfaceProvider }

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(owner, selector, preview)
            emit(RearCameraState.Ready)
        } catch (e: IllegalArgumentException) {
            // Most often "no back-facing camera exists" or the selector matched nothing.
            val failure = if (cameraProvider.hasCameraSafely(selector)) CameraFailure.BindingFailed else CameraFailure.NoRearCamera
            Log.w(TAG, "Rear camera bind failed: $failure", e)
            emit(RearCameraState.Unavailable(failure))
        } catch (e: IllegalStateException) {
            // Lifecycle already destroyed, or the camera is held by another client.
            Log.w(TAG, "Rear camera unavailable (state)", e)
            emit(RearCameraState.Unavailable(CameraFailure.InUse))
        } catch (t: Throwable) {
            Log.w(TAG, "Rear camera bind failed", t)
            emit(RearCameraState.Unavailable(CameraFailure.BindingFailed))
        }
    }

    private fun ProcessCameraProvider.hasCameraSafely(selector: CameraSelector): Boolean = try {
        hasCamera(selector)
    } catch (t: Throwable) {
        false
    }

    override fun release() {
        provider?.let { cameraProvider ->
            try {
                cameraProvider.unbindAll()
            } catch (t: Throwable) {
                Log.w(TAG, "Unbind failed", t)
            }
        }
        provider = null
        boundOwner = null
        previewView = null
        executor = null
    }

    private fun emit(state: RearCameraState) {
        _events.tryEmit(CameraEngineEvent.StateChanged(state))
    }

    private companion object {
        const val TAG = "CameraXRearEngine"
        const val PREVIEW_TARGET_WIDTH = 1280
        const val PREVIEW_TARGET_HEIGHT = 720
    }
}