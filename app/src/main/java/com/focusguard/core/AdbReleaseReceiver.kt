package com.focusguard.core

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * The deliberate emergency exit. Protected in the manifest by `android.permission.DUMP`,
 * which only the ADB shell (and system) holds — no app on the phone can trigger it.
 *
 *   adb shell am broadcast -a com.focusguard.RELEASE -n com.focusguard/.core.AdbReleaseReceiver
 *
 * Requires: a computer, a USB cable, USB debugging enabled and the phone unlocked.
 * That is the intended cost: ten minutes of effort, not a tap at 1 a.m.
 */
class AdbReleaseReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION) return
        Log.w(TAG, "release requested over ADB")
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                Graph.settings.requestRelease(Instant.now(), force = true)
                Graph.coordinator.refresh("adb-release")
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "AdbRelease"
        const val ACTION = "com.focusguard.RELEASE"
    }
}
