package com.openmaps.app.core.vision

import kotlin.math.asin
import kotlin.math.atan2

/**
 * The camera's tilt relative to gravity. Derived from the device rotation sensor only, so it carries
 * no heading: where the camera points along the road (yaw) is unknown in Stage 1.
 *
 * @property pitchDownRadians how far the optical axis points below the horizon (positive = down).
 * @property rollRadians rotation about the optical axis; positive = the right edge of the screen is
 *   lower than the left.
 */
data class CameraAttitude(
    val pitchDownRadians: Double,
    val rollRadians: Double,
    val timestampNanos: Long = 0L,
) {
    companion object {
        /**
         * @param rotationMatrix the 9-element row-major matrix from
         *   `SensorManager.getRotationMatrixFromVector`: it maps device coordinates (x right, y up,
         *   z out of the screen, in the phone's natural portrait orientation) to world coordinates
         *   whose z axis points up.
         * @param displayRotationDegrees `Display.rotation` in degrees (0, 90, 180, 270), so roll is
         *   measured against what is currently "right" and "up" on screen.
         */
        fun fromRotationMatrix(
            rotationMatrix: FloatArray,
            displayRotationDegrees: Int,
            timestampNanos: Long = 0L,
        ): CameraAttitude? {
            if (rotationMatrix.size < 9) return null
            // World-up component (third row) of each device axis.
            val xUp = rotationMatrix[6].toDouble()
            val yUp = rotationMatrix[7].toDouble()
            val zUp = rotationMatrix[8].toDouble()
            // The rear camera looks along device -z, so it looks down exactly when +z points up.
            val pitchDown = asin(zUp.coerceIn(-1.0, 1.0))
            val (rightUp, upUp) = when (((displayRotationDegrees % 360) + 360) % 360) {
                0 -> xUp to yUp
                // ROTATION_90: the phone is turned counter-clockwise, so its natural right edge is up.
                90 -> -yUp to xUp
                180 -> -xUp to -yUp
                270 -> yUp to -xUp
                else -> return null
            }
            return CameraAttitude(
                pitchDownRadians = pitchDown,
                rollRadians = atan2(-rightUp, upUp),
                timestampNanos = timestampNanos,
            )
        }
    }
}
