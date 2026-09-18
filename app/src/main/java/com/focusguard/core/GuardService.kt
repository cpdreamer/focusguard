package com.focusguard.core

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.focusguard.FocusGuardApp
import com.focusguard.R
import com.focusguard.scheduler.Assignment
import com.focusguard.ui.MainActivity
import java.time.format.DateTimeFormatter

/**
 * Lightweight foreground service: keeps the process alive so alarms and receivers run
 * promptly, and owns the persistent "Now: ..." notification. All logic is in [Coordinator].
 *
 * Also hosts the runtime-registered package receiver: since Android 8, PACKAGE_ADDED /
 * PACKAGE_REPLACED / PACKAGE_REMOVED are not delivered to manifest receivers, so a freshly
 * installed app would otherwise stay unsuspended until the next alarm boundary.
 */
class GuardService : Service() {

    private val packageReceiver = PackageChangeReceiver()

    override fun onCreate() {
        super.onCreate()
        startInForeground(buildNotification(this, Coordinator.Mode.FREE, null))
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addDataScheme("package")
        }
        registerReceiver(packageReceiver, filter)
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(packageReceiver) }
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Graph.coordinator.refreshAsync("service-start")
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startInForeground(n: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(ID, n)
        }
    }

    companion object {
        private const val TAG = "GuardService"
        const val ID = 1
        private val hm: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

        fun start(context: Context) {
            runCatching { context.startForegroundService(Intent(context, GuardService::class.java)) }
                .onFailure { Log.w(TAG, "start failed", it) }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, GuardService::class.java))
        }

        fun updateNotification(context: Context, mode: Coordinator.Mode, current: Assignment?) {
            context.getSystemService(NotificationManager::class.java).notify(ID, buildNotification(context, mode, current))
        }

        fun buildNotification(context: Context, mode: Coordinator.Mode, current: Assignment?): Notification {
            val title: String
            val text: String
            when {
                current != null -> {
                    title = context.getString(R.string.now_title, current.task.title)
                    text = context.getString(R.string.now_text, current.slot.end.format(hm), current.task.firstAction.ifBlank { "" }).trim()
                }
                mode == Coordinator.Mode.SLEEP -> {
                    title = context.getString(R.string.sleep_title); text = context.getString(R.string.sleep_text)
                }
                else -> {
                    title = context.getString(R.string.free_title); text = context.getString(R.string.free_text)
                }
            }
            val pi = PendingIntent.getActivity(
                context, 0, MainActivity.intent(context),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val b = Notification.Builder(context, FocusGuardApp.CHANNEL_GUARD)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
            if (current != null) {
                b.addAction(action(context, R.string.action_done, ActionReceiver.ACTION_DONE, current.task.id))
                b.addAction(action(context, R.string.action_skip, ActionReceiver.ACTION_SKIP, current.task.id))
            }
            return b.build()
        }

        private fun action(context: Context, label: Int, action: String, taskId: Long): Notification.Action {
            val pi = PendingIntent.getBroadcast(
                context, action.hashCode() + 7, ActionReceiver.intent(context, action, taskId),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            return Notification.Action.Builder(null, context.getString(label), pi).build()
        }
    }
}
