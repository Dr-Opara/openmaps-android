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

/** Android rotation-vector maths in plain Kotlin, so live code and JVM replay compute identical attitudes. */
object RotationVector {
    /**
     * Same result as `SensorManager.getRotationMatrixFromVector` for a 3x3 matrix: [values] is the
     * sensor's `(x, y, z[, w])` unit quaternion. Returns null for input that is not a rotation.
     */
    fun toRotationMatrix(values: FloatArray): FloatArray? {
        if (values.size < 3) return null
        val q1 = values[0]
        val q2 = values[1]
        val q3 = values[2]
        if (q1.isNaN() || q2.isNaN() || q3.isNaN()) return null
        val q0 = if (values.size >= 4) {
            values[3]
        } else {
            val rest = 1f - q1 * q1 - q2 * q2 - q3 * q3
            if (rest > 0f) kotlin.math.sqrt(rest) else 0f
        }
        if (q0.isNaN()) return null
        val sq1 = 2 * q1 * q1
        val sq2 = 2 * q2 * q2
        val sq3 = 2 * q3 * q3
        val q1q2 = 2 * q1 * q2
        val q3q0 = 2 * q3 * q0
        val q1q3 = 2 * q1 * q3
        val q2q0 = 2 * q2 * q0
        val q2q3 = 2 * q2 * q3
        val q1q0 = 2 * q1 * q0
        return floatArrayOf(
            1 - sq2 - sq3, q1q2 - q3q0, q1q3 + q2q0,
            q1q2 + q3q0, 1 - sq1 - sq3, q2q3 - q1q0,
            q1q3 - q2q0, q2q3 + q1q0, 1 - sq1 - sq2,
        )
    }
}
