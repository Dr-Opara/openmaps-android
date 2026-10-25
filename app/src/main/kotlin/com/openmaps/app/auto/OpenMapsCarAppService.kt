package com.openmaps.app.auto

import android.content.Intent
import androidx.car.app.CarAppService
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.Session
import androidx.car.app.model.Action
import androidx.car.app.model.Header
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.validation.HostValidator
import com.openmaps.app.BuildConfig

/**
 * Android Auto projected host-discovery proof of concept (#83), not navigation functionality.
 *
 * The service is enabled only for debug builds via @bool/openmaps_auto_service_enabled.
 * Actual navigation needs MapLibre rendering to the host's Surface, shared route progress,
 * real maneuver messages, car-safe reporting, and DHU/vehicle acceptance BEFORE release.
 *
 * No access to vehicle cameras or phone Live AR imagery is requested or supported.
 */
class OpenMapsCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (BuildConfig.DEBUG) {
            // Developer-only: lets Android Auto Desktop Head Unit attach during local tests.
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            // Defense in depth if a later release enables this service: never allow arbitrary hosts.
            HostValidator.Builder(this)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(): Session = object : Session() {
        override fun onCreateScreen(intent: Intent): Screen =
            OpenMapsAutoPrototypeScreen(carContext)
    }
}

/**
 * Intentionally non-navigating: never show fabricated map tiles, ETA, maneuver or road alerts.
 * The only purpose is to test discovery, session creation, and host template rendering in DHU.
 */
private class OpenMapsAutoPrototypeScreen(carContext: CarContext) : Screen(carContext) {
    override fun onGetTemplate(): Template {
        val pane = Pane.Builder()
            .addRow(
                Row.Builder()
                    .setTitle("Android Auto connected")
                    .addText("OpenMaps navigation map is not enabled in this developer build.")
                    .build(),
            )
            .build()
        return PaneTemplate.Builder(pane)
            .setHeader(
                Header.Builder()
                    .setTitle("OpenMaps Auto - Development")
                    .setStartHeaderAction(Action.APP_ICON)
                    .build(),
            )
            .build()
    }
}
