package com.openmaps.app.core.vision

import kotlin.math.atan
import kotlin.math.max

/**
 * Pinhole intrinsics for one image size, in pixels. `u` grows right, `v` grows down.
 *
 * Lens distortion is **not** modelled. Phone main cameras are corrected in the ISP well enough for
 * a ground grid a few tens of metres out, but this is an assumption the device test must check
 * (straight road edges staying straight near the image border), not a guarantee.
 */
data class CameraIntrinsics(
    val focalXPixels: Double,
    val focalYPixels: Double,
    val principalXPixels: Double,
    val principalYPixels: Double,
    val imageWidth: Int,
    val imageHeight: Int,
    val source: IntrinsicsSource,
) {
    val horizontalFovDegrees: Double
        get() = Math.toDegrees(2 * atan(imageWidth / (2 * focalXPixels)))

    val verticalFovDegrees: Double
        get() = Math.toDegrees(2 * atan(imageHeight / (2 * focalYPixels)))

    /**
     * The same camera after the image buffer is rotated clockwise by [rotationDegrees] to be upright
     * for the display (CameraX `ImageInfo.rotationDegrees`). Only multiples of 90 are valid.
     */
    fun rotatedUpright(rotationDegrees: Int): CameraIntrinsics = when (((rotationDegrees % 360) + 360) % 360) {
        0 -> this
        90 -> copy(
            focalXPixels = focalYPixels,
            focalYPixels = focalXPixels,
            principalXPixels = imageHeight - principalYPixels,
            principalYPixels = principalXPixels,
            imageWidth = imageHeight,
            imageHeight = imageWidth,
        )
        180 -> copy(
            principalXPixels = imageWidth - principalXPixels,
            principalYPixels = imageHeight - principalYPixels,
        )
        270 -> copy(
            focalXPixels = focalYPixels,
            focalYPixels = focalXPixels,
            principalXPixels = principalYPixels,
            principalYPixels = imageWidth - principalXPixels,
            imageWidth = imageHeight,
            imageHeight = imageWidth,
        )
        else -> throw IllegalArgumentException("rotationDegrees must be a multiple of 90, was $rotationDegrees")
    }

    companion object {
        /**
         * Intrinsics derived from the lens focal length and physical sensor size, for an output
         * buffer that is a **centred aspect-ratio crop of the full sensor, then uniformly scaled**.
         *
         * That is what CameraX produces at 1x with no digital zoom and no video stabilisation crop.
         * It is wrong if the device applies EIS cropping, a zoom ratio other than 1, or silently
         * switches physical lenses on a logical multi-camera; [IntrinsicsSource.SensorGeometry]
         * marks the result as derived, never as measured.
         */
        fun fromSensorGeometry(
            focalLengthMm: Double,
            sensorWidthMm: Double,
            sensorHeightMm: Double,
            imageWidth: Int,
            imageHeight: Int,
        ): CameraIntrinsics? {
            if (focalLengthMm <= 0 || sensorWidthMm <= 0 || sensorHeightMm <= 0 || imageWidth <= 0 || imageHeight <= 0) {
                return null
            }
            // Buffers are delivered in sensor (landscape) orientation; normalise so the long sides match.
            val sensorLong = max(sensorWidthMm, sensorHeightMm)
            val sensorShort = minOf(sensorWidthMm, sensorHeightMm)
            val imageLong = max(imageWidth, imageHeight)
            val imageShort = minOf(imageWidth, imageHeight)
            val sensorAspect = sensorLong / sensorShort
            val imageAspect = imageLong.toDouble() / imageShort
            // Whichever dimension is NOT cropped spans the whole sensor in that direction.
            val pixelsPerMm = if (imageAspect >= sensorAspect) imageLong / sensorLong else imageShort / sensorShort
            val focalPixels = focalLengthMm * pixelsPerMm
            return CameraIntrinsics(
                focalXPixels = focalPixels,
                focalYPixels = focalPixels,
                principalXPixels = imageWidth / 2.0,
                principalYPixels = imageHeight / 2.0,
                imageWidth = imageWidth,
                imageHeight = imageHeight,
                source = IntrinsicsSource.SensorGeometry,
            )
        }
    }
}

enum class IntrinsicsSource {
    /** Derived from focal length and sensor size reported by the device. Not a calibration. */
    SensorGeometry,

    /** Measured for this exact phone with a calibration target (not implemented in Stage 1). */
    Measured,
}

/**
 * How the phone sits in the vehicle. Stage 1 supports exactly one fixed configuration: these values
 * are **measured by a person**, never estimated by the app.
 */
data class MountConfiguration(
    val name: String,
    /** Height of the camera lens above the road surface, in metres. */
    val cameraHeightMeters: Double,
    /** True once a person has measured [cameraHeightMeters] for the vehicle under test. */
    val heightMeasured: Boolean,
) {
    companion object {
        /**
         * Placeholder until the test vehicle is measured. 1.25 m is a plausible windshield/dash mount
         * in a passenger car, used only to draw the calibration grid; [heightMeasured] = false keeps
         * [GuidanceConfidenceGate] closed regardless of anything else.
         */
        val UnmeasuredPrototype = MountConfiguration(
            name = "prototype-unmeasured",
            cameraHeightMeters = 1.25,
            heightMeasured = false,
        )
    }
}
