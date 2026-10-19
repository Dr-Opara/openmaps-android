package com.openmaps.app.core.navigation

/** Keeps an active user-started navigation session eligible for background location execution. */
interface NavigationForegroundController {
    fun start()
    fun stop()
}

object NoopNavigationForegroundController : NavigationForegroundController {
    override fun start() = Unit
    override fun stop() = Unit
}
