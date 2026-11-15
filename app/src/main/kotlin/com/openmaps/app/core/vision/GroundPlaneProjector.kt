package com.openmaps.app.core.vision

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** A point in upright image pixels (`u` right, `v` down). */
data class ImagePoint(val u: Double, val v: Double)

/**
 * Projects points on a **flat plane [MountConfiguration.cameraHeightMeters] below the camera** into
 * the upright image.
 *
 * This is geometry, not perception. It knows nothing about where the road actually is: it assumes
 * the ground is a level plane and that "forward" is wherever the camera points. It exists so the
 * calibration (intrinsics, image orientation, preview transform, attitude) can be verified on a
 * device before any road-relative rendering is built on top of it.
 *
 * Frames: ground points are (forward, right) in metres in a gravity-levelled frame whose forward
 * axis is the horizontal projection of the optical axis. The camera frame is x right, y down,
 * z forward.
 */
class GroundPlaneProjector(
    private val intrinsics: CameraIntrinsics,
    private val cameraHeightMeters: Double,
) {
    /** Null when the point is behind the camera or closer than [NEAR_PLANE_METERS]. */
    fun project(forwardMeters: Double, rightMeters: Double, attitude: CameraAttitude): ImagePoint? =
        projectLevelPoint(rightMeters, cameraHeightMeters, forwardMeters, attitude, NEAR_PLANE_METERS)

    /**
     * Two image points on the horizon (the image of infinitely distant ground), or null when the
     * horizon cannot be represented (camera pointing straight up or down).
     */
    fun horizon(attitude: CameraAttitude): Pair<ImagePoint, ImagePoint>? {
        val cosPitch = cos(attitude.pitchDownRadians)
        if (cosPitch < 0.05) return null
        // Directions on the horizon: level, at a lateral angle either side of straight ahead.
        val side = tan(Math.toRadians(HORIZON_HALF_ANGLE_DEGREES))
        // These are directions, not positions, so only "in front of the camera" matters.
        val left = projectLevelPoint(-side, 0.0, 1.0, attitude, minDepth = 0.0) ?: return null
        val right = projectLevelPoint(side, 0.0, 1.0, attitude, minDepth = 0.0) ?: return null
        return left to right
    }

    private fun projectLevelPoint(
        x: Double,
        y: Double,
        z: Double,
        attitude: CameraAttitude,
        minDepth: Double,
    ): ImagePoint? {
        val sinPitch = sin(attitude.pitchDownRadians)
        val cosPitch = cos(attitude.pitchDownRadians)
        // Pitch: rotate the level frame about the camera x axis.
        val yPitched = y * cosPitch - z * sinPitch
        val zCamera = y * sinPitch + z * cosPitch
        if (zCamera <= minDepth) return null
        // Roll: rotate about the optical axis.
        val sinRoll = sin(attitude.rollRadians)
        val cosRoll = cos(attitude.rollRadians)
        val xCamera = x * cosRoll + yPitched * sinRoll
        val yCamera = -x * sinRoll + yPitched * cosRoll
        return ImagePoint(
            u = intrinsics.principalXPixels + intrinsics.focalXPixels * xCamera / zCamera,
            v = intrinsics.principalYPixels + intrinsics.focalYPixels * yCamera / zCamera,
        )
    }

    companion object {
        const val NEAR_PLANE_METERS = 0.5
        private const val HORIZON_HALF_ANGLE_DEGREES = 25.0
    }
}

/**
 * Maps upright image pixels onto a view that shows the image with `FILL_CENTER` (scale to cover,
 * centred, edges cropped) — `PreviewView`'s scale type in [com.openmaps.app.camera.CameraXRearEngine].
 *
 * Valid only while the analysed image and the preview show the same field of view. Both use cases
 * are requested at the same aspect ratio for that reason; the device test checks it.
 */
class FillCenterTransform(
    private val imageWidth: Int,
    private val imageHeight: Int,
    private val viewWidth: Float,
    private val viewHeight: Float,
) {
    private val scale: Float =
        if (imageWidth <= 0 || imageHeight <= 0) 1f else maxOf(viewWidth / imageWidth, viewHeight / imageHeight)
    private val offsetX: Float = (viewWidth - imageWidth * scale) / 2f
    private val offsetY: Float = (viewHeight - imageHeight * scale) / 2f

    fun toViewX(u: Double): Float = (u * scale).toFloat() + offsetX

    fun toViewY(v: Double): Float = (v * scale).toFloat() + offsetY
}

/** A line segment of the calibration grid, in upright image pixels. */
data class GridSegment(val from: ImagePoint, val to: ImagePoint, val kind: Kind) {
    enum class Kind { Longitudinal, Cross, Horizon }
}

/**
 * The Stage 1 calibration figure: a flat-ground grid and the horizon line.
 *
 * It is a **test pattern**. The grid is centred on the camera, not on a detected lane, and it does
 * not follow the road. It must never be presented as guidance.
 */
object CalibrationGrid {
    val CROSS_DISTANCES_METERS = listOf(5.0, 10.0, 20.0, 40.0)
    const val HALF_WIDTH_METERS = 1.8
    private const val NEAR_METERS = 3.0
    private const val FAR_METERS = 60.0
    private const val LONGITUDINAL_STEPS = 24

    fun segments(projector: GroundPlaneProjector, attitude: CameraAttitude): List<GridSegment> = buildList {
        for (side in listOf(-HALF_WIDTH_METERS, 0.0, HALF_WIDTH_METERS)) {
            var previous: ImagePoint? = null
            for (i in 0..LONGITUDINAL_STEPS) {
                // Sample in 1/distance so segments are evenly spaced on screen, not bunched at the horizon.
                val t = i.toDouble() / LONGITUDINAL_STEPS
                val forward = 1.0 / (1.0 / NEAR_METERS + t * (1.0 / FAR_METERS - 1.0 / NEAR_METERS))
                val point = projector.project(forward, side, attitude)
                if (previous != null && point != null) add(GridSegment(previous, point, GridSegment.Kind.Longitudinal))
                previous = point
            }
        }
        for (distance in CROSS_DISTANCES_METERS) {
            val left = projector.project(distance, -HALF_WIDTH_METERS, attitude)
            val right = projector.project(distance, HALF_WIDTH_METERS, attitude)
            if (left != null && right != null) add(GridSegment(left, right, GridSegment.Kind.Cross))
        }
        projector.horizon(attitude)?.let { (left, right) -> add(GridSegment(left, right, GridSegment.Kind.Horizon)) }
    }
}
