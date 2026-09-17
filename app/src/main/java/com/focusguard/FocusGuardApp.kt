package com.focusguard

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.focusguard.core.Graph
import com.focusguard.core.GuardService

class FocusGuardApp : Application() {

    override fun onCreate() {
        super.onCreate()
        Graph.init(this)
        createChannels()
        if (Graph.settings.isProvisioned()) {
            GuardService.start(this)
        }
    }

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_GUARD,
                getString(R.string.channel_guard),
                NotificationManager.IMPORTANCE_LOW
            ).apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                CHANNEL_REMINDER,
                getString(R.string.channel_reminder),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                setShowBadge(true)
                enableVibration(true)
            }
        )
    }

    companion object {
        const val CHANNEL_GUARD = "guard"
        const val CHANNEL_REMINDER = "reminder"
    }
}
