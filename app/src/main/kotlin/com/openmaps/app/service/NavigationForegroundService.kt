package com.openmaps.app.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.openmaps.app.R
import com.openmaps.app.core.navigation.NavigationForegroundController

class NavigationForegroundService : Service() {

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_navigation_notification)
            .setContentTitle(getString(R.string.navigation_service_title))
            .setContentText(getString(R.string.navigation_service_body))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOnlyAlertOnce(true)
            .build()

        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            },
        )
        return START_STICKY
    }

    override fun onDestroy() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.navigation_service_channel),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.navigation_service_channel_description)
                setShowBadge(false)
            },
        )
    }

    companion object {
        private const val CHANNEL_ID = "openmaps_navigation"
        private const val NOTIFICATION_ID = 4101

        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, NavigationForegroundService::class.java),
            )
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NavigationForegroundService::class.java))
        }
    }
}

class AndroidNavigationForegroundController(
    context: Context,
) : NavigationForegroundController {
    private val appContext = context.applicationContext

    override fun start() = NavigationForegroundService.start(appContext)
    override fun stop() = NavigationForegroundService.stop(appContext)
}
