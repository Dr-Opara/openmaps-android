package com.openmaps.app.core.spatial

import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The **fallback contract**: when AR cannot localize, Live must stay fully usable on CameraX.
 *
 * This is the requirement that a broken AR stack must never take navigation down, so it is asserted
 * directly rather than assumed. [NoopSpatialLocalization] is what the JVM tests and non-AR devices
 * use in place of the real ARCore engine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NoopSpatialLocalizationTest {

    private val dispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun neverProvidesPreview_soLiveKeepsUsingCameraX() = runTest(dispatcher) {
        // Critical: if this were true, Live would render the AR surface and show black on a device
        // where AR cannot run at all.
        assertFalse(NoopSpatialLocalization().providesPreview)
    }

    @Test
    fun startAndStopAreSafeNoOps() = runTest(dispatcher) {
        val engine = NoopSpatialLocalization()
        val owner = FakeLifecycleOwner()
        engine.start(owner)
        engine.start(owner) // repeated start must not throw
        engine.stop()
        engine.stop() // repeated stop must not throw
    }

    @Test
    fun stateIsASupportedReason_soTheHudShowsNothing() = runTest(dispatcher) {
        // The default is Unsupported; Live shows no localization UI at all in that case.
        assertEquals(SpatialLocalizationState.Unsupported, NoopSpatialLocalization().state.value)
        assertEquals(LiveLocalizationStatus.None, LiveLocalizationStatusMapper.of(NoopSpatialLocalization().state.value))
    }

    @Test
    fun everyUnavailableReasonIsRepresentableAndHudSilent() {
        val reasons = listOf(
            SpatialLocalizationState.Unsupported,
            SpatialLocalizationState.PermissionRequired,
            SpatialLocalizationState.NotInstalled(),
            SpatialLocalizationState.Failed(SpatialFailure.SessionCreationFailed),
            SpatialLocalizationState.Failed(SpatialFailure.EarthUnavailable),
            SpatialLocalizationState.Failed(SpatialFailure.CameraUnavailable),
        )
        reasons.forEach { reason ->
            val engine = NoopSpatialLocalization(reason)
            assertFalse("must not claim a preview for $reason", engine.providesPreview)
            assertEquals("HUD must stay silent for $reason", LiveLocalizationStatus.None, LiveLocalizationStatusMapper.of(reason))
        }
    }

    private class FakeLifecycleOwner : LifecycleOwner {
        private val registry = androidx.lifecycle.LifecycleRegistry(this)
        override val lifecycle: androidx.lifecycle.Lifecycle get() = registry
    }
}

/** Minimal [SpatialLocalizationEngine] double, so LiveViewModel's AR wiring is testable on the JVM. */
class FakeSpatialLocalization(
    override val providesPreview: Boolean = true,
    private val availability: SpatialAvailability = SpatialAvailability.Supported,
) : SpatialLocalizationEngine {
    private val _state = MutableStateFlow<SpatialLocalizationState>(SpatialLocalizationState.Idle)
    override val state: Flow<SpatialLocalizationState> = _state

    var startCount = 0
        private set
    var stopCount = 0
        private set
    var probeCount = 0
        private set

    fun emit(next: SpatialLocalizationState) {
        _state.value = next
    }

    override fun probeAvailability(): SpatialAvailability {
        probeCount++
        return availability
    }

    override fun start(lifecycleOwner: LifecycleOwner) {
        startCount++
    }

    override fun stop() {
        stopCount++
    }
}