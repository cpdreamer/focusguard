package com.focusguard.core

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "Receivers"

/** Runs [block] with the receiver kept alive until it completes (up to ~10 s). */
private fun BroadcastReceiver.async(block: suspend () -> Unit) {
    val pending = goAsync()
    CoroutineScope(Dispatchers.Default).launch {
        try { block() } catch (t: Throwable) { Log.e(TAG, "receiver failed", t) } finally { pending.finish() }
    }
}

/** Fired by our own exact alarms at every planned boundary. */
class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        GuardService.start(context)
        async { Graph.coordinator.refresh("alarm") }
    }

    companion object {
        const val ACTION = "com.focusguard.TICK"
        fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
            context, 0, Intent(context, AlarmReceiver::class.java).setAction(ACTION),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}

/** Boot, time changes and app updates: re-apply everything. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "boot-ish event ${intent.action}")
        if (!Graph.settings.isProvisioned()) return
        GuardService.start(context)
        async { Graph.coordinator.refresh(intent.action ?: "boot") }
    }
}

/** A package was added/removed/changed: the suspension set may need to change. */
class PackageChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!Graph.settings.isProvisioned()) return
        Log.i(TAG, "package event ${intent.action} ${intent.data}")
        async { Graph.coordinator.refresh("package") }
    }
}

/** Notification buttons on the task reminder. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val taskId = intent.getLongExtra(EXTRA_TASK, -1L)
        when (intent.action) {
            ACTION_START -> { Graph.coordinator.markStarted() }
            ACTION_DONE -> async { Graph.coordinator.markDone(taskId) }
            ACTION_SKIP -> async { Graph.coordinator.skipCurrentSlot() }
        }
    }

    companion object {
        const val ACTION_START = "com.focusguard.action.START"
        const val ACTION_DONE = "com.focusguard.action.DONE"
        const val ACTION_SKIP = "com.focusguard.action.SKIP"
        const val EXTRA_TASK = "task"

        fun intent(context: Context, action: String, taskId: Long): Intent =
            Intent(context, ActionReceiver::class.java).setAction(action).putExtra(EXTRA_TASK, taskId)
    }
}
