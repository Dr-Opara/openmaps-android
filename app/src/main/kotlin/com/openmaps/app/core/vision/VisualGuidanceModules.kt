package com.openmaps.app.core.vision

import com.openmaps.app.core.navigation.RouteProgress
import com.openmaps.app.core.routing.RouteResult
import kotlinx.coroutines.flow.Flow

/*
 * Module boundaries of the Level 2 visual navigation engine (docs/VISUAL_NAVIGATION_ENGINE.md).
 *
 * Only what Stage 1 needs is implemented: CameraFrameProvider (CameraXFrameProvider), the
 * projection maths in this package, and GuidanceConfidenceGate. The perception, motion, fusion,
 * alignment and surface modules are interfaces with no implementation on purpose — there is no
 * stub that returns plausible-looking values, so nothing downstream can mistake "not built" for
 * "detected". Until they exist the gate has no evidence and stays closed.
 */

/** Timing and geometry of one camera frame. Pixels are deliberately not part of Stage 1. */
data class FrameMetadata(
    /** Sensor timestamp (`ImageInfo.timestamp`), nanoseconds, monotonic; same clock as sensor events on most devices. */
    val timestampNanos: Long,
    val bufferWidth: Int,
    val bufferHeight: Int,
    /** Clockwise rotation that makes the buffer upright for the current display. */
    val rotationDegrees: Int,
)

/** What is known about the camera producing the frames. */
data class CameraCalibration(
    /** Intrinsics for the buffer as delivered (before rotation); null when the device reports no lens/sensor data. */
    val bufferIntrinsics: CameraIntrinsics?,
    val sensorOrientationDegrees: Int?,
    val focalLengthMm: Double?,
    val sensorWidthMm: Double?,
    val sensorHeightMm: Double?,
    /** True when the device also publishes `LENS_INTRINSIC_CALIBRATION` (not yet consumed). */
    val deviceReportsIntrinsicCalibration: Boolean,
)

/** 1. Camera frames, timestamps, calibration and coordinate transforms. */
interface CameraFrameProvider {
    val frames: Flow<FrameMetadata>
    val calibration: Flow<CameraCalibration>
}

/** 2. Road segmentation, lane boundaries, road perspective, visual confidence. Not implemented. */
interface RoadPerceptionEngine {
    data class RoadObservation(
        val timestampNanos: Long,
        /** Drivable-surface boundary in upright image pixels, left and right, near to far. */
        val leftEdge: List<ImagePoint>,
        val rightEdge: List<ImagePoint>,
        /** Detected lane *markings*. Seeing markings is not knowing which lane the vehicle is in. */
        val laneMarkings: List<List<ImagePoint>>,
        val vanishingPoint: ImagePoint?,
        /** 0..1, from the model and from temporal consistency. */
        val confidence: Double,
    )

    val observations: Flow<RoadObservation>
}

/** 3. Feature tracking and frame-to-frame camera motion. Not implemented. */
interface VisualMotionEstimator {
    data class MotionEstimate(
        val fromTimestampNanos: Long,
        val toTimestampNanos: Long,
        val yawDeltaRadians: Double,
        val pitchDeltaRadians: Double,
        val rollDeltaRadians: Double,
        val trackedFeatureCount: Int,
        val confidence: Double,
    )

    val motion: Flow<MotionEstimate>
}

/** 4. Camera pose from IMU, location and visual observations, with explicit uncertainty. Not implemented. */
interface PoseFusionEngine {
    data class FusedPose(
        val timestampNanos: Long,
        val attitude: CameraAttitude,
        /** Camera heading relative to the route direction at the matched position; null when unknown. */
        val yawToRouteRadians: Double?,
        val yawSigmaRadians: Double?,
        val lateralOffsetMeters: Double?,
        val lateralSigmaMeters: Double?,
    )

    val pose: Flow<FusedPose>
}

/** 5. Aligns the active route and upcoming maneuvers with the observed road. Not implemented. */
interface RouteAlignmentEngine {
    data class RouteAlignment(
        val timestampNanos: Long,
        /** Upcoming route centreline in the camera's gravity-levelled frame: (forward, right) metres. */
        val forwardPath: List<Pair<Double, Double>>,
        /** Mean distance between the projected route and the observed road, in metres on the ground. */
        val residualMeters: Double,
        val confidence: Double,
    )

    fun align(route: RouteResult, progress: RouteProgress, pose: PoseFusionEngine.FusedPose, road: RoadPerceptionEngine.RoadObservation): RouteAlignment?
}

/** 6. Local 3D road surface. Stage 1 has only the flat-plane assumption in [GroundPlaneProjector]. */
interface RoadSurfaceModel {
    /** Height of the road relative to the flat plane at a ground point, in metres; null when unknown. */
    fun heightAt(forwardMeters: Double, rightMeters: Double): Double?
}

/** 7. Draws chevrons on validated road-relative geometry. Only ever called with an open gate. */
interface RoadArrowRenderer {
    fun render(alignment: RouteAlignmentEngine.RouteAlignment, decision: GuidanceDecision.RoadAnchored)
}
