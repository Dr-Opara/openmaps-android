package com.openmaps.app.core.spatial.arcore

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.google.ar.core.ArCoreApk
import com.google.ar.core.Camera
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Earth
import com.google.ar.core.Frame
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.CameraNotAvailableException
import com.google.ar.core.exceptions.NotYetAvailableException
import com.google.ar.core.exceptions.SessionUnsupportedException
import com.google.ar.core.exceptions.UnavailableException
import com.openmaps.app.BuildConfig
import com.openmaps.app.core.spatial.LimitedReason
import com.openmaps.app.core.spatial.LocalizationQualityMapper
import com.openmaps.app.core.spatial.SpatialAvailability
import com.openmaps.app.core.spatial.SpatialFailure
import com.openmaps.app.core.spatial.SpatialLocalizationEngine
import com.openmaps.app.core.spatial.SpatialLocalizationState
import com.openmaps.app.core.spatial.SpatialPose
import com.openmaps.app.core.spatial.normalizeHeadingDegrees
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.Locale
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * The ONLY file that imports `com.google.ar.core`.
 *
 * Runs an ARCore [Session] in **Geospatial mode** to obtain an Earth-relative device pose and a
 * device-facing heading. It owns the rear camera while running.
 *
 * **Why this replaces CameraX rather than running beside it.** The Android camera stack allows one
 * owner at a time. ARCore's tracking pipeline needs those frames itself (motion tracking, VPS), so a
 * CameraX preview bound at the same time would starve it or be starved by it, and the failure mode is
 * nondeterministic. Because ARCore is *also* the source of the camera image once
 * `setCameraTextureName` is called, one owner can serve both: this class renders the camera
 * background itself via a minimal [GLSurfaceView] (hence [providesPreview] = true) and Live shows
 * this engine's surface instead of the CameraX preview. When ARCore cannot run at all, Live keeps
 * using the Phase 5 CameraX engine untouched. There is never a moment with two camera consumers.
 *
 * Lifecycle: `resume()` on ON_RESUME, `pause()` on ON_PAUSE, `close()` on destroy, all tied to the
 * lifecycle owner, so backgrounding cannot leave the camera held.
 */
@Suppress("LogNotTimber")
class ArCoreSpatialLocalization(
    private val context: Context,
    private val qualityMapper: LocalizationQualityMapper = LocalizationQualityMapper(),
) : SpatialLocalizationEngine, LifecycleEventObserver {

    private val _events = MutableSharedFlow<SpatialLocalizationState>(
        replay = 1,
        extraBufferCapacity = 8,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    override val state: Flow<SpatialLocalizationState> = _events.asSharedFlow()

    /**
     * True because this engine also renders the camera image (see class docs). Live uses this to
     * choose between this engine's GL surface and the CameraX preview — never both.
     */
    override val providesPreview: Boolean = true

    /**
     * The live ARCore [Session], or null when none has been created.
     *
     * Exposed deliberately and read-only: the anchor backend is the one legitimate second consumer
     * of the session (it needs `Earth.createAnchor`). Rather than widen this class's API with anchor
     * methods, the backend receives a supplier and stays ignorant of session lifecycle.
     */
    fun session(): Session? = session

    /**
     * The camera's pose in ARCore's world frame, published from the GL thread each frame.
     *
     * ARCore exposes the camera only through [Frame], which is valid for exactly one frame, so it is
     * copied out here into plain numbers rather than handing out a stale `Frame`. Phase 7 anchors live
     * in the same world frame, so this is what turns an anchor position into a view-relative one —
     * and it is the input that makes anchored placement stable as the device moves.
     *
     * Orientation is included, not just position: it is the camera's yaw that swings arrows across
     * the screen when the phone turns.
     */
    @Volatile
    private var cameraTransform: com.openmaps.app.core.anchor.WorldTransform? = null

    /** Latest camera world transform, or null before the first frame. */
    fun cameraTransform(): com.openmaps.app.core.anchor.WorldTransform? = cameraTransform

    private var session: Session? = null
    private var surfaceView: GLSurfaceView? = null
    private var boundOwner: LifecycleOwner? = null
    private var installPrompted = false

    /** GL-thread-only, debug-only diagnostic throttling: never log coordinates or headings. */
    private var lastGateSignature = ""
    private var lastGateLogMillis = 0L

    /** GL texture ARCore writes camera frames into. Owned by the renderer; -1 until GL is ready. */
    @Volatile
    private var cameraTextureId: Int = NO_TEXTURE

    /**
     * Optional overlay drawn on top of the camera background each frame.
     *
     * Typed as an interface so the AR engine knows nothing about anchors, arrows, geometry or
     * rendering policy — it only knows "draw this after the camera". That keeps ARCore session
     * ownership strictly separate from what is drawn on it, and is the boundary later phases reuse
     * for additional overlays without touching this class.
     */
    @Volatile
    var overlayRenderer: ArOverlayRenderer? = null

    /**
     * Opacity for the overlay layer (0 hides it entirely).
     * @Volatile because it is written from the main thread and read on the GL thread.
     */
    @Volatile
    var overlayAlpha: Float = 0f
        private set

    fun setOverlayAlpha(alpha: Float) {
        overlayAlpha = alpha.coerceIn(0f, 1f)
    }

    /** The minimum interface the AR engine needs from anything drawn over the camera. */
    interface ArOverlayRenderer {
        /** Called once when the GL surface is created, so the overlay can build GL resources. */
        fun onSurfaceCreated()

        /** Called every frame after the camera background. [alpha] is 0..1. */
        fun drawFrame(alpha: Float)

        /** Renderer readiness is queried on the GL thread, not latched in a Compose side effect. */
        fun hasDrawableContent(): Boolean = true
    }

    /** The GL surface Live hosts when [providesPreview]. */
    fun previewView(): GLSurfaceView = surfaceView ?: GLSurfaceView(context).also { view ->
        view.preserveEGLContextOnPause = true
        view.setEGLContextClientVersion(2)
        view.setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        view.setRenderer(CameraBackgroundRenderer())
        view.renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY
        surfaceView = view
    }

    // region Lifecycle

    /**
     * Camera-free support probe. `checkAvailability` inspects the installed ARCore service and the
     * device's AR capability without opening a camera or creating a Session, which is exactly what
     * Live needs: decide who owns the camera *before* any camera is opened.
     */
    override fun probeAvailability(): SpatialAvailability {
        val raw = ArCoreApk.getInstance().checkAvailability(context)
        Log.i(TAG, "probeAvailability: $raw")
        return mapAvailability(raw)
    }

    private fun mapAvailability(raw: ArCoreApk.Availability): SpatialAvailability =
        when (raw) {
            ArCoreApk.Availability.SUPPORTED_INSTALLED -> SpatialAvailability.Supported
            ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED,
            ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD,
            -> SpatialAvailability.NotInstalled
            // Device genuinely cannot run ARCore. Not fixable by re-querying.
            ArCoreApk.Availability.UNSUPPORTED_DEVICE_NOT_CAPABLE,
            -> SpatialAvailability.Unsupported
            // The UNKNOWN_* family means the check itself has not settled yet — Google's guidance is
            // to re-query, not to treat this as a permanent answer. Collapsing these into Unsupported
            // (the previous behaviour here) is what let a single unlucky first probe permanently
            // strand Live on the CameraX fallback with AR never attempted at all — see
            // `LiveViewModel.chooseInitialProvider`, which now re-probes while this is reported.
            ArCoreApk.Availability.UNKNOWN_ERROR,
            ArCoreApk.Availability.UNKNOWN_CHECKING,
            ArCoreApk.Availability.UNKNOWN_TIMED_OUT,
            -> SpatialAvailability.Checking
        }

    override fun start(lifecycleOwner: LifecycleOwner) {
        if (boundOwner === lifecycleOwner) return
        boundOwner?.lifecycle?.removeObserver(this)
        boundOwner = lifecycleOwner
        lifecycleOwner.lifecycle.addObserver(this)
        // Live may be entered while the Activity is *already* RESUMED. In that case
        // LifecycleRegistry does not dispatch another ON_RESUME to this new observer.
        // The persistent GLSurfaceView was paused on our previous stop(), so explicitly
        // resume its GL render thread before resuming the new ARCore Session. Without
        // this the camera image and Earth frame pump can remain black/TrackingNotReady
        // until the entire Activity is backgrounded and foregrounded.
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            surfaceView?.onResume()
        }
        resumeSession()
    }

    override fun stop() {
        boundOwner?.lifecycle?.removeObserver(this)
        boundOwner = null
        pauseSession()
        closeSession()
        surfaceView?.onPause()
        _events.tryEmit(SpatialLocalizationState.Idle)
    }

    override fun onStateChanged(owner: LifecycleOwner, event: Lifecycle.Event) {
        when (event) {
            Lifecycle.Event.ON_RESUME -> {
                surfaceView?.onResume()
                resumeSession()
            }
            Lifecycle.Event.ON_PAUSE -> {
                pauseSession()
                surfaceView?.onPause()
            }
            Lifecycle.Event.ON_DESTROY -> {
                pauseSession()
                closeSession()
                owner.lifecycle.removeObserver(this)
                if (boundOwner === owner) boundOwner = null
            }
            else -> Unit
        }
    }

    private fun resumeSession() {
        val owner = boundOwner ?: return
        if (!owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
            // Still land on a real state rather than leaving `_events` silent — a silent flow is
            // indistinguishable from "never started" to anything collecting it with an `Idle`
            // placeholder (see the Phase 12A AR-diagnostics note in `LiveScreen.LocalDebugOverlay`).
            _events.tryEmit(SpatialLocalizationState.Initializing)
            return
        }
        // `context` is the process-wide Application context (see `AppContainer`), which
        // `findActivityOrNull()` can never resolve to an Activity through — `Application` extends
        // `ContextWrapper`, but its own `baseContext` bottoms out at a bare `ContextImpl`, never an
        // `Activity`. That made this branch fire unconditionally, on every device, every time,
        // which is the confirmed real-device root cause of ARCore never starting at all (section
        // A2's "do not assume Idle is enough information" — the debug overlay used to just say
        // "Idle" because the engine was never started past this point). The lifecycle owner handed
        // to `start()` is the real fix: in this single-Activity app it already *is* the hosting
        // `ComponentActivity` (`LocalLifecycleOwner.current` inside `MainActivity.setContent`), so
        // it is tried first; `context.findActivityOrNull()` is kept only as a defensive fallback for
        // a lifecycle owner that is not itself an Activity.
        val activity = (owner as? Activity) ?: context.findActivityOrNull()
        if (activity == null) {
            _events.tryEmit(SpatialLocalizationState.Failed(SpatialFailure.SessionCreationFailed))
            return
        }
        try {
            // Runtime support check — never assume ARCore works. The device may not support it, the
            // service may be missing, or the installed version may be too old (three distinct cases).
            when (ArCoreApk.getInstance().requestInstall(activity, !installPrompted)) {
                ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                    // ARCore is installing/updating; the user leaves and returns. Re-check on resume.
                    installPrompted = true
                    _events.tryEmit(SpatialLocalizationState.NotInstalled(requestInstall = true))
                    return
                }
                ArCoreApk.InstallStatus.INSTALLED -> Unit
            }

            if (session == null) {
                val created = Session(context)
                // Checked explicitly (section A3) rather than left to surface as a generic
                // `configure()` exception: a device/ARCore combination that cannot do Geospatial at
                // all is a distinct, nameable diagnostic, not "session creation failed".
                if (!created.isGeospatialModeSupported(Config.GeospatialMode.ENABLED)) {
                    Log.w(TAG, "Geospatial mode not supported on this device")
                    created.close()
                    _events.tryEmit(SpatialLocalizationState.Failed(SpatialFailure.GeospatialUnsupported))
                    return
                }
                created.configure(
                    created.config.apply {
                        geospatialMode = Config.GeospatialMode.ENABLED // the point of Phase 6
                        planeFindingMode = Config.PlaneFindingMode.DISABLED // no anchors yet
                        focusMode = Config.FocusMode.AUTO
                        updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
                        lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
                    },
                )
                session = created
                Log.i(TAG, "ARCore session created with Geospatial mode enabled")
            }

            // Hand ARCore our camera texture once GL has produced one. Not a precondition: before
            // that the session runs without an image (tracking still works).
            if (cameraTextureId != NO_TEXTURE) session?.setCameraTextureName(cameraTextureId)

            session?.resume()
            _events.tryEmit(SpatialLocalizationState.Initializing)
        } catch (e: SessionUnsupportedException) {
            Log.w(TAG, "ARCore session unsupported on this device", e)
            _events.tryEmit(SpatialLocalizationState.Unsupported)
            closeSession()
        } catch (e: UnavailableException) {
            // Device not AR-capable, or the ARCore service is unavailable/broken. Not recoverable.
            Log.w(TAG, "ARCore unavailable", e)
            _events.tryEmit(SpatialLocalizationState.Unsupported)
            closeSession()
        } catch (e: NotYetAvailableException) {
            _events.tryEmit(SpatialLocalizationState.NotInstalled(requestInstall = true))
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "AR camera unavailable", e)
            _events.tryEmit(SpatialLocalizationState.Failed(SpatialFailure.CameraUnavailable))
        } catch (t: Throwable) {
            // Anything else ARCore throws must degrade Live, never crash it.
            Log.w(TAG, "ARCore session creation failed", t)
            _events.tryEmit(SpatialLocalizationState.Failed(SpatialFailure.SessionCreationFailed))
        }
    }

    private fun pauseSession() {
        try {
            session?.pause()
        } catch (t: Throwable) {
            Log.w(TAG, "ARCore pause failed", t)
        }
    }

    private fun closeSession() {
        try {
            session?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "ARCore close failed", t)
        }
        session = null
        // Do NOT clear cameraTextureId here. GLSurfaceView uses
        // preserveEGLContextOnPause=true, so its OES texture may still exist after
        // stop() closes the Session. Clearing the ID while retaining the GL context
        // means the *next* Session has no camera texture and cannot render frames.
        // CameraBackgroundRenderer.onSurfaceCreated() replaces this ID if EGL actually
        // recreates the context. No Session holds onto the old texture on close.
        // The world frame dies with the session; a pose left over from it would place arrows in a
        // frame that no longer exists.
        cameraTransform = null
        lastGateSignature = ""
        lastGateLogMillis = 0L
    }

    // endregion

    // region Frame -> provider-neutral state

    /**
     * Called once per rendered frame by [CameraBackgroundRenderer]. ARCore is only pumped from the GL
     * thread so `update()` can never be called concurrently with itself.
     */
    internal fun onDrawFrame(): Frame? {
        val activeSession = session ?: return null
        if (boundOwner?.lifecycle?.currentState?.isAtLeast(Lifecycle.State.RESUMED) != true) return null
        try {
            val view = surfaceView
            if (view != null && view.width > 0 && view.height > 0) {
                // ARCore needs the device/display rotation that corresponds to this surface.
                // Hard-coding ROTATION_0 makes portrait camera frames appear 90 degrees sideways
                // on devices whose camera sensor is landscape-native (confirmed on Samsung Ultra).
                val displayRotation = view.display?.rotation ?: Surface.ROTATION_0
                activeSession.setDisplayGeometry(displayRotation, view.width, view.height)
            }
            val frame = activeSession.update()
            // Publish the camera's world transform before anything can ask for it: anchors and arrows
            // are positioned relative to this, so it must come from the same frame ARCore produced.
            cameraTransform = frame.camera.pose?.let { p ->
                com.openmaps.app.core.anchor.WorldTransform(
                    position = com.openmaps.app.core.anchor.WorldPose(
                        p.tx().toDouble(),
                        p.ty().toDouble(),
                        p.tz().toDouble(),
                    ),
                    qx = p.qx().toDouble(),
                    qy = p.qy().toDouble(),
                    qz = p.qz().toDouble(),
                    qw = p.qw().toDouble(),
                )
            }
            val earth = activeSession.earth
            val spatialState = toState(frame.camera, earth)
            logGeospatialGate(frame.camera, earth, spatialState)
            _events.tryEmit(spatialState)
            return frame
        } catch (e: CameraNotAvailableException) {
            Log.w(TAG, "ARCore lost the camera", e)
            _events.tryEmit(SpatialLocalizationState.Failed(SpatialFailure.CameraUnavailable))
            return null
        } catch (t: Throwable) {
            Log.w(TAG, "ARCore frame failed", t)
            _events.tryEmit(SpatialLocalizationState.Failed(SpatialFailure.ArCoreError))
            return null
        }
    }

    private fun toState(camera: Camera, earth: Earth?): SpatialLocalizationState {
        // Camera tracking gates everything: without a tracked camera there is no usable pose.
        if (camera.trackingState != TrackingState.TRACKING) {
            return when (camera.trackingState) {
                TrackingState.PAUSED -> SpatialLocalizationState.Initializing
                TrackingState.STOPPED -> SpatialLocalizationState.Limited(LimitedReason.Initializing)
                else -> SpatialLocalizationState.Limited(mapLimited(camera))
            }
        }

        // Camera is tracked, but `session.earth` itself may not have resolved yet — not the same
        // thing as a poor fix (section A4: never equate Earth existence with active tracking).
        if (earth == null) return SpatialLocalizationState.Limited(LimitedReason.WaitingForEarth)

        // The Earth (geospatial) solution may still be converging or erroring.
        when (earth.earthState) {
            Earth.EarthState.ENABLED -> Unit // good; fall through
            Earth.EarthState.ERROR_NOT_AUTHORIZED ->
                return SpatialLocalizationState.Failed(SpatialFailure.EarthUnavailable)
            Earth.EarthState.ERROR_GEOSPATIAL_MODE_DISABLED ->
                return SpatialLocalizationState.Failed(SpatialFailure.GeospatialDisabled)
            Earth.EarthState.ERROR_APK_VERSION_TOO_OLD ->
                return SpatialLocalizationState.NotInstalled(requestInstall = true)
            else -> return SpatialLocalizationState.Limited(LimitedReason.PoorGeolocation)
        }

        // `EarthState.ENABLED` is not the same as Earth actively *tracking* (section A4): Earth has
        // its own TrackingState, distinct from the camera's, and can read PAUSED/STOPPED while the
        // camera itself is tracking fine.
        if (earth.trackingState != TrackingState.TRACKING) {
            return SpatialLocalizationState.Limited(LimitedReason.EarthPaused)
        }

        // GeospatialPose carries position AND heading with their accuracies. Note this heading is
        // device-facing (compass-style, true north), NOT the GPS movement bearing: they differ
        // whenever the device is stationary or sliding, exactly when guidance matters most.
        //
        // ARCore's Java nullability annotation on this accessor disagrees with its own documented
        // behavior — it can genuinely be null while Earth is still converging, immediately after
        // `earthState` first reports ENABLED — so the null check is kept deliberately despite the
        // compiler treating it as unreachable.
        val geoPose = earth.cameraGeospatialPose
        @Suppress("SENSELESS_COMPARISON")
        val geoPoseMissing = geoPose == null
        if (geoPoseMissing) {
            // Tracking, but no geospatial pose yet (still converging).
            return SpatialLocalizationState.Tracking(null)
        }

        val horizontal = geoPose.horizontalAccuracy
        val vertical = geoPose.verticalAccuracy

        // Heading accuracy uses the current, non-deprecated accessor (a direct rename).
        // A negative value means "not yet known"; treat that as no heading at all.
        val headingAccuracy = geoPose.orientationYawAccuracy.takeIf { it >= 0.0 }

        // `heading` is deprecated in favour of deriving yaw from `eastUpSouthQuaternion`, but that
        // replacement requires an East-Up-South frame Euler derivation whose sign conventions cannot
        // be validated without ARCore hardware (none available here). A silently inverted heading
        // would point guidance the wrong way — far worse than a deprecation warning — so the exact,
        // documented accessor is kept deliberately until it can be verified on a real device.
        @Suppress("DEPRECATION")
        val headingDegrees = normalizeHeadingDegrees(geoPose.heading)

        val quality = qualityMapper.quality(
            horizontalAccuracyMeters = horizontal,
            headingAccuracyDegrees = headingAccuracy,
            isSettled = true,
        )

        return SpatialLocalizationState.Tracking(
            SpatialPose(
                latitude = geoPose.latitude,
                longitude = geoPose.longitude,
                altitudeMeters = geoPose.altitude,
                headingDegrees = headingDegrees,
                horizontalAccuracyMeters = horizontal,
                verticalAccuracyMeters = vertical,
                headingAccuracyDegrees = headingAccuracy,
                quality = quality,
            ),
        )
    }

    /**
     * DEBUG-only, privacy-minimal explanation of the exact ARCore tracking gate.
     *
     * The consumer HUD only shows a quiet status label. Engineers need to distinguish camera
     * tracking, Earth authorization, Earth tracking and accuracy gating; otherwise all three
     * look like "sampled 0" on a device. Never log latitude, longitude, altitude, heading,
     * API credentials or a route trace. Log only on state transitions or every 10 seconds.
     *
     * Invoked exclusively from onDrawFrame's GL thread.
     */
    private fun logGeospatialGate(
        camera: Camera,
        earth: Earth?,
        spatialState: SpatialLocalizationState,
    ) {
        if (!BuildConfig.DEBUG) return
        val pose = (spatialState as? SpatialLocalizationState.Tracking)?.pose
        val reason = when (spatialState) {
            is SpatialLocalizationState.Limited -> "Limited:" + spatialState.reason.name
            is SpatialLocalizationState.Failed -> "Failed:" + spatialState.reason.name
            is SpatialLocalizationState.Tracking ->
                if (pose == null) "Tracking:PosePending" else "Tracking:" + pose.quality.name
            else -> spatialState.javaClass.simpleName
        }
        val cameraState = camera.trackingState.name
        val cameraFailure = if (camera.trackingState == TrackingState.TRACKING) {
            "none"
        } else {
            camera.trackingFailureReason.name
        }
        val earthState = earth?.earthState?.name ?: "UNAVAILABLE"
        val earthTracking = earth?.trackingState?.name ?: "UNAVAILABLE"
        val signature = "$cameraState|$cameraFailure|$earthState|$earthTracking|$reason"
        val now = SystemClock.elapsedRealtime()
        if (signature == lastGateSignature && now - lastGateLogMillis < GATE_DIAGNOSTIC_INTERVAL_MS) {
            return
        }
        lastGateSignature = signature
        lastGateLogMillis = now

        fun accuracy(value: Double?): String = value
            ?.takeIf { it.isFinite() && it >= 0 }
            ?.let { String.format(Locale.US, "%.1f", it) } ?: "unknown"
        Log.d(
            TAG,
            "geospatialGate camera=$cameraState cameraFailure=$cameraFailure " +
                "earth=$earthState earthTracking=$earthTracking " +
                "localization=$reason posePresent=${pose != null} " +
                "horizontalAccuracyM=${accuracy(pose?.horizontalAccuracyMeters)} " +
                "yawAccuracyDeg=${accuracy(pose?.headingAccuracyDegrees)}",
        )
    }

    private fun mapLimited(camera: Camera): LimitedReason = when (camera.trackingFailureReason) {
        TrackingFailureReason.INSUFFICIENT_FEATURES -> LimitedReason.InsufficientFeatures
        TrackingFailureReason.EXCESSIVE_MOTION -> LimitedReason.ExcessiveMotion
        TrackingFailureReason.INSUFFICIENT_LIGHT -> LimitedReason.InsufficientFeatures
        TrackingFailureReason.CAMERA_UNAVAILABLE -> LimitedReason.Initializing
        else -> LimitedReason.Initializing
    }

    // endregion

    /**
     * Minimal GL renderer for the camera background: creates the external OES texture ARCore writes
     * into and draws it as a full-screen quad. This is the standard ARCore camera-background pattern;
     * it is deliberately primitive because Phase 6 renders no AR content yet — the same renderer is
     * the anchor point Phase 7+ extends to draw world-locked overlays.
     */
    private inner class CameraBackgroundRenderer : GLSurfaceView.Renderer {

        private var program = 0
        private var positionHandle = 0
        private var texCoordHandle = 0
        private var samplerUniform = 0

        // GL resources belong to this EGL context, not to the main thread. The Compose
        // DisposableEffect which attaches the AR overlay may run AFTER this surface's first
        // onSurfaceCreated(). Prepare the current overlay in the render loop whenever it
        // changes, and reset on context recreation. Otherwise the renderer stays uninitialized
        // and silently draws no arrows throughout an otherwise healthy AR session.
        private var preparedOverlayRenderer: ArOverlayRenderer? = null

        private val quadPositions = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
        private val quadTexCoords = floatArrayOf(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)
        private val positionBuffer: java.nio.FloatBuffer =
            java.nio.ByteBuffer.allocateDirect(quadPositions.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadPositions).position(0) }
        private val texCoordBuffer: java.nio.FloatBuffer =
            java.nio.ByteBuffer.allocateDirect(quadTexCoords.size * 4).order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer().apply { put(quadTexCoords).position(0) }

        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            val texture = textures[0]
            GLES20.glBindTexture(TEXTURE_TARGET, texture)
            GLES20.glTexParameteri(TEXTURE_TARGET, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(TEXTURE_TARGET, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(TEXTURE_TARGET, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(TEXTURE_TARGET, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            cameraTextureId = texture

            // GL resources are prepared in onDrawFrame(), not just here: an overlay
            // installed after this callback must still receive onSurfaceCreated() on
            // the GL thread. Reset on EGL context recreation.
            preparedOverlayRenderer = null

            program = buildProgram()
            positionHandle = GLES20.glGetAttribLocation(program, "a_Position")
            texCoordHandle = GLES20.glGetAttribLocation(program, "a_TexCoord")
            samplerUniform = GLES20.glGetUniformLocation(program, "u_Texture")

            // The texture name only exists once GL starts, so hand it to ARCore now if a session
            // already exists (e.g. resume before the surface was created).
            runCatching { session?.setCameraTextureName(texture) }
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            // Pump ARCore first so the texture below already holds this frame.
            val frame = this@ArCoreSpatialLocalization.onDrawFrame()
            if (frame != null) {
                updateCameraTextureCoordinates(frame)
            }
            drawCameraBackground()
            // Overlays draw on top of the camera. Snapshot the current renderer once so
            // attach/detach races cannot initialize one renderer and draw with another.
            // A late attachment still gets the GL-context callback before its first draw.
            val renderer = overlayRenderer
            if (renderer !== preparedOverlayRenderer) {
                preparedOverlayRenderer = renderer
                renderer?.onSurfaceCreated()
            }
            renderer?.let {
                // An older Compose callback may have latched overlayAlpha=0 before the
                // AR arrows were projected. Read drawable content on the GL thread each
                // frame instead; never make nonempty arrows permanently invisible.
                val visibleAlpha = if (it.hasDrawableContent()) 1f else 0f
                if (visibleAlpha > 0f) {
                    GLES20.glEnable(GLES20.GL_BLEND)
                    it.drawFrame(visibleAlpha)
                    GLES20.glDisable(GLES20.GL_BLEND)
                }
            }
        }

        /**
         * ARCore owns the camera image orientation/crop. The UVs must be transformed for the
         * current display geometry instead of assuming a fixed landscape sensor orientation.
         * This keeps the preview upright in portrait and also correct when the device rotates.
         */
        private fun updateCameraTextureCoordinates(frame: Frame) {
            positionBuffer.position(0)
            texCoordBuffer.position(0)
            frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                positionBuffer,
                Coordinates2d.TEXTURE_NORMALIZED,
                texCoordBuffer,
            )
            texCoordBuffer.position(0)
        }

        private fun drawCameraBackground() {
            if (program == 0 || cameraTextureId == NO_TEXTURE) return
            GLES20.glDisable(GLES20.GL_DEPTH_TEST)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(program)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
            GLES20.glBindTexture(TEXTURE_TARGET, cameraTextureId)
            GLES20.glUniform1i(samplerUniform, 0)
            GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
            GLES20.glEnableVertexAttribArray(positionHandle)
            GLES20.glVertexAttribPointer(texCoordHandle, 2, GLES20.GL_FLOAT, false, 0, texCoordBuffer)
            GLES20.glEnableVertexAttribArray(texCoordHandle)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            GLES20.glDisableVertexAttribArray(positionHandle)
            GLES20.glDisableVertexAttribArray(texCoordHandle)
        }

        private fun buildProgram(): Int {
            val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
            val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
            return GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, vertex)
                GLES20.glAttachShader(it, fragment)
                GLES20.glLinkProgram(it)
            }
        }

        private fun compile(type: Int, source: String): Int =
            GLES20.glCreateShader(type).also {
                GLES20.glShaderSource(it, source)
                GLES20.glCompileShader(it)
            }
    }

    companion object {
        internal const val NO_TEXTURE = -1
        private const val TAG = "ArCoreSpatialLoc"
        private const val GATE_DIAGNOSTIC_INTERVAL_MS = 10_000L

        /** GL_TEXTURE_EXTERNAL_OES (0x8D65), declared here to avoid importing GLES30 for one constant. */
        private const val TEXTURE_TARGET = 0x8D65

        private val VERTEX_SHADER = """
            attribute vec4 a_Position;
            attribute vec2 a_TexCoord;
            varying vec2 v_TexCoord;
            void main() {
                gl_Position = a_Position;
                v_TexCoord = a_TexCoord;
            }
        """.trimIndent()

        private val FRAGMENT_SHADER = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES u_Texture;
            varying vec2 v_TexCoord;
            void main() {
                gl_FragColor = texture2D(u_Texture, v_TexCoord);
            }
        """.trimIndent()
    }
}

private tailrec fun Context.findActivityOrNull(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivityOrNull()
    else -> null
}