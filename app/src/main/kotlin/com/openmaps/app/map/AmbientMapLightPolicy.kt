package com.openmaps.app.map

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay

/** User preference, persisted by the app shell. Auto observes ambient light in the foreground. */
enum class MapAppearanceMode { Auto, Day, Night }

enum class AmbientLightDecision { Day, Night, Hold }

/**
 * Two separate thresholds avoid rapid mode switching as ambient illumination changes.
 * A tunnel or temporary shadow shouldn't change cartography until the reading stays in
 * the candidate category for the settling interval in [rememberAmbientMapNight].
 */
object AmbientMapLightPolicy {
    const val SETTLE_MILLIS: Long = 15_000L
    fun classify(lux: Float?): AmbientLightDecision = when {
        lux == null || !lux.isFinite() || lux < 0 -> AmbientLightDecision.Hold
        lux <= 12f -> AmbientLightDecision.Night
        lux >= 85f -> AmbientLightDecision.Day
        else -> AmbientLightDecision.Hold
    }
}

/**
 * Ambient sensor readings are from the PHONE, not a verified outdoor photometer. Mounted
 * phones, dashboard shadows, tunnels and covered sensors can influence them. The user can
 * choose Day or Night in Settings. With no light sensor, fall back to Android's theme.
 *
 * A first reading is never enough: the same classification must persist for 15 seconds.
 * Sensor is only registered while the host lifecycle is RESUMED and Auto is selected.
 */
@Composable
fun rememberAmbientMapNight(enabled: Boolean): Boolean {
    val systemNight = isSystemInDarkTheme()
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val manager = remember(context) { context.getSystemService(Context.SENSOR_SERVICE) as SensorManager }
    val sensor = remember(manager) { manager.getDefaultSensor(Sensor.TYPE_LIGHT) }
    var ambientLux by remember { mutableStateOf<Float?>(null) }
    var measuredNight by remember { mutableStateOf<Boolean?>(null) }
    val decision = AmbientMapLightPolicy.classify(ambientLux)

    DisposableEffect(manager, sensor, owner, enabled) {
        if (!enabled || sensor == null) {
            onDispose { }
        } else {
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    if (event.sensor.type == Sensor.TYPE_LIGHT) {
                        ambientLux = event.values.firstOrNull()
                    }
                }
                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            fun register() { manager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL) }
            fun unregister() { manager.unregisterListener(listener) }
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> register()
                    Lifecycle.Event.ON_PAUSE, Lifecycle.Event.ON_STOP -> unregister()
                    else -> Unit
                }
            }
            owner.lifecycle.addObserver(observer)
            if (owner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) register()
            onDispose {
                unregister()
                owner.lifecycle.removeObserver(observer)
            }
        }
    }

    LaunchedEffect(enabled, decision) {
        if (!enabled || decision == AmbientLightDecision.Hold) return@LaunchedEffect
        delay(AmbientMapLightPolicy.SETTLE_MILLIS)
        // Coroutine is automatically cancelled if the sensor moves to a different band.
        measuredNight = decision == AmbientLightDecision.Night
    }
    return if (enabled && sensor != null) measuredNight ?: systemNight else systemNight
}
