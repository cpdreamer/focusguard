package com.focusguard.net

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.focusguard.core.Directives
import com.focusguard.core.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Remote directive channel, the phone-side twin of the Windows kit's `[config]` URL: a plain text
 * file, one [Directives] line each, fetched every 15 min. Every line is applied once and remembered
 * ([com.focusguard.data.Settings.configSeen]); editing a line makes it a new line. What the file
 * can do is exactly what the grammar allows: tighten now, or queue an `allow` for the window. A
 * fetch failure changes nothing.
 */
class ConfigUpdater(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val text = runCatching {
            val conn = URL(CONFIG_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "FocusGuard/1.0")
            conn.inputStream.bufferedReader().use { it.readText() }
        }.onFailure { Log.w(TAG, "config fetch failed", it) }.getOrNull() ?: return@withContext Result.retry()
        val applied = apply(text.take(MAX_CHARS), Graph.directives, Graph.settings.configSeen) { Graph.settings.configSeen = it }
        if (applied > 0) Graph.coordinator.refresh("config")
        Result.success()
    }

    companion object {
        private const val TAG = "ConfigUpdater"
        private const val MAX_CHARS = 200_000
        const val CONFIG_URL = "https://raw.githubusercontent.com/cpdreamer/focusguard/main/config/phone.txt"

        /** Applies every unseen non-empty line, records it as seen whatever the verdict. Returns the number applied. */
        suspend fun apply(text: String, directives: Directives, seen: Set<String>, save: (Set<String>) -> Unit): Int {
            var applied = 0
            val nowSeen = seen.toMutableSet()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#") || line in nowSeen) continue
                val r = directives.apply(line, Instant.now())
                Log.i(TAG, "remote '$line' -> $r")
                nowSeen += line
                applied++
            }
            if (applied > 0) save(nowSeen)
            return applied
        }

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "config-poll",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<ConfigUpdater>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
            )
        }
    }
}
