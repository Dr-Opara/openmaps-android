package com.openmaps.app.vision

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.openmaps.app.core.vision.CameraAttitude
import com.openmaps.app.core.vision.RotationVector
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Camera pitch and roll from the device rotation sensor.
 *
 * Prefers the *game* rotation vector (gyroscope + accelerometer, no magnetometer): pitch and roll
 * only need gravity, and leaving the compass out keeps a car's magnetic disturbances from leaking
 * into them. Heading is deliberately not read here.
 *
 * Known limitation, not handled in Stage 1: while the vehicle accelerates, brakes or corners, the
 * accelerometer's "down" is biased, so pitch and roll are wrong by up to a few degrees until the
 * filter settles.
 */
class DeviceAttitudeSource(
    context: Context,
    private val displayRotationDegrees: () -> Int,
) : SensorEventListener {

    private val sensorManager = context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val sensor: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR)
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

    private val _attitude = MutableStateFlow<CameraAttitude?>(null)
    val attitude: StateFlow<CameraAttitude?> = _attitude.asStateFlow()

    val isAvailable: Boolean get() = sensor != null

    fun start() {
        val rotationSensor = sensor ?: return
        sensorManager?.registerListener(this, rotationSensor, SensorManager.SENSOR_DELAY_GAME)
    }

    fun stop() {
        sensorManager?.unregisterListener(this)
        _attitude.value = null
    }

    override fun onSensorChanged(event: SensorEvent) {
        // Plain-Kotlin maths shared with JVM replay, so a recorded drive reproduces this exactly.
        val rotationMatrix = RotationVector.toRotationMatrix(event.values) ?: return
        _attitude.value = CameraAttitude.fromRotationMatrix(
            rotationMatrix = rotationMatrix,
            displayRotationDegrees = displayRotationDegrees(),
            timestampNanos = event.timestamp,
        )
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
