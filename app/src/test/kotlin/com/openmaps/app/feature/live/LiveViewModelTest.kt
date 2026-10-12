package com.openmaps.app.feature.live

import androidx.lifecycle.LifecycleOwner
import com.openmaps.app.camera.CameraEngineEvent
import com.openmaps.app.camera.CameraFailure
import com.openmaps.app.camera.RearCameraEngine
import com.openmaps.app.camera.RearCameraState
import com.openmaps.app.core.camera.CameraPermissionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Live's camera-permission and camera-health state machine.
 *
 * CameraX is never involved: [FakeRearCameraEngine] implements the engine-neutral interface, which
 * is the point of the `camera` seam — this whole file runs on the JVM with no camera hardware.
 *
 * Engine events are pushed in through [LiveViewModel.onCameraEvent] rather than by collecting the
 * engine's flow, because that is the real production path: `RearCameraHost` owns the collection
 * and forwards, exactly as `MapLibreHost`/`MapEvent` already does for the map.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LiveViewModelTest {

    private val dispatcher = UnconfinedTestDispatcher()
    private val engine = FakeRearCameraEngine()
    private lateinit var viewModel: LiveViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(dispatcher)
        viewModel = LiveViewModel(engine)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun cameraIsNotRequestedBeforePermissionIsGranted() = runTest(dispatcher) {
        assertFalse(viewModel.canStartCamera)
        assertEquals(RearCameraState.Idle, viewModel.uiState.value.cameraState)
        assertFalse(viewModel.uiState.value.isCameraUsable)
    }

    @Test
    fun grantingPermissionAllowsTheCamera() = runTest(dispatcher) {
        viewModel.onCameraPermissionChanged(CameraPermissionState.Granted)
        assertTrue(viewModel.canStartCamera)
        assertTrue(viewModel.uiState.value.isCameraUsable)
    }

    @Test
    fun requestingPermission_emitsExactlyOneRequest() = runTest(dispatcher) {
        val events = collectEvents()
        viewModel.onCameraPermissionChanged(CameraPermissionState.NotRequested)

        viewModel.onRequestCameraPermission()

        assertEquals(listOf(LiveUiEvent.RequestCameraPermission), events)
    }

    @Test
    fun requestingPermission_whenAlreadyGranted_neverReprompts() = runTest(dispatcher) {
        val events = collectEvents()
        viewModel.onCameraPermissionChanged(CameraPermissionState.Granted)

        viewModel.onRequestCameraPermission()

        assertTrue("already granted must not re-prompt", events.isEmpty())
    }

    @Test
    fun requestingPermission_whenDenied_stillPrompts() = runTest(dispatcher) {
        val events = collectEvents()
        viewModel.onCameraPermissionChanged(CameraPermissionState.Denied)

        viewModel.onRequestCameraPermission()

        assertEquals(listOf(LiveUiEvent.RequestCameraPermission), events)
    }

    @Test
    fun permanentlyDenied_opensSettingsInsteadOfPromptingAgain() = runTest(dispatcher) {
        val events = collectEvents()
        viewModel.onCameraPermissionChanged(CameraPermissionState.PermanentlyDenied)

        viewModel.onRequestCameraPermission()

        assertEquals(listOf(LiveUiEvent.OpenAppSettings), events)
    }

    @Test
    fun engineEvents_mirrorIntoState() = runTest(dispatcher) {
        viewModel.onCameraPermissionChanged(CameraPermissionState.Granted)

        viewModel.onCameraEvent(CameraEngineEvent.StateChanged(RearCameraState.Starting))
        assertEquals(RearCameraState.Starting, viewModel.uiState.value.cameraState)

        viewModel.onCameraEvent(CameraEngineEvent.StateChanged(RearCameraState.Ready))
        assertTrue(viewModel.uiState.value.isCameraUsable)
        assertNull(viewModel.uiState.value.cameraFailure)
    }

    @Test
    fun everyCameraFailureSurfacesAsAClassifiedReason() = runTest(dispatcher) {
        viewModel.onCameraPermissionChanged(CameraPermissionState.Granted)

        CameraFailure.entries.forEach { failure ->
            viewModel.onCameraEvent(CameraEngineEvent.StateChanged(RearCameraState.Unavailable(failure)))

            assertEquals(failure, viewModel.uiState.value.cameraFailure)
            assertFalse("a failed camera is not usable", viewModel.uiState.value.isCameraUsable)
        }
    }

    @Test
    fun recoveringFromAFailureClearsIt() = runTest(dispatcher) {
        viewModel.onCameraPermissionChanged(CameraPermissionState.Granted)
        viewModel.onCameraEvent(CameraEngineEvent.StateChanged(RearCameraState.Unavailable(CameraFailure.InUse)))
        assertFalse(viewModel.uiState.value.isCameraUsable)

        viewModel.onCameraEvent(CameraEngineEvent.StateChanged(RearCameraState.Ready))

        assertNull(viewModel.uiState.value.cameraFailure)
        assertTrue(viewModel.uiState.value.isCameraUsable)
    }

    @Test
    fun revokingPermission_makesTheCameraUnusableEvenIfFramesWereFlowing() = runTest(dispatcher) {
        viewModel.onCameraPermissionChanged(CameraPermissionState.Granted)
        viewModel.onCameraEvent(CameraEngineEvent.StateChanged(RearCameraState.Ready))
        assertTrue(viewModel.uiState.value.isCameraUsable)

        viewModel.onCameraPermissionChanged(CameraPermissionState.PermanentlyDenied)

        assertFalse(viewModel.uiState.value.isCameraUsable)
        assertFalse(viewModel.canStartCamera)
    }

    @Test
    fun liveWithoutACameraEngine_neverClaimsTheCameraCanStart() = runTest(dispatcher) {
        // Defensive: a build with no engine wired up must degrade, not crash or pretend.
        val engineLess = LiveViewModel(cameraEngine = null)
        engineLess.onCameraPermissionChanged(CameraPermissionState.Granted)

        assertFalse(engineLess.canStartCamera)
        assertTrue(engineLess.uiState.value.isCameraUsable)
    }

    private fun TestScope.collectEvents(): MutableList<LiveUiEvent> =
        mutableListOf<LiveUiEvent>().also { list ->
            backgroundScope.launch(dispatcher) { viewModel.events.collect { list += it } }
        }

    private class FakeRearCameraEngine : RearCameraEngine {
        private val _events = MutableSharedFlow<CameraEngineEvent>(extraBufferCapacity = 8)
        override val events: Flow<CameraEngineEvent> = _events

        var releaseCount = 0
            private set

        override fun createView(context: android.content.Context): android.view.View =
            throw UnsupportedOperationException("no Android views in unit tests")

        override fun bind(lifecycleOwner: LifecycleOwner) = Unit

        override fun release() {
            releaseCount++
        }
    }
}