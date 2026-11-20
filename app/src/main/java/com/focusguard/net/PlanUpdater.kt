package com.focusguard.net

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.focusguard.core.Graph
import com.focusguard.core.Reminders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * The planner channel: the coach (Devin) writes `config/plan.txt` in the repo, one reminder per
 * line, `YYYY-MM-DD HH:MM | title | first action` (phone local time, `#` comments). The phone
 * fetches it every 15 min and shows a plain notification at each time (no alarm, dismissible).
 * The user never types a task here. Entries already shown are remembered by key so a re-fetch
 * does not repeat them; past entries (> 6 h old) are skipped.
 */
class PlanUpdater(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val text = runCatching {
            val conn = URL(PLAN_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.setRequestProperty("User-Agent", "FocusGuard/1.0")
            conn.inputStream.bufferedReader().use { it.readText() }
        }.onFailure { Log.w(TAG, "plan fetch failed", it) }.getOrNull() ?: return@withContext Result.retry()
        Graph.settings.planText = text.take(MAX_CHARS)
        schedule(applicationContext)
        Result.success()
    }

    data class Entry(val at: LocalDateTime, val title: String, val action: String) {
        val key: String get() = "${at.format(FMT)}|$title"
    }

    class AlarmReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val title = intent.getStringExtra(EXTRA_TITLE) ?: return
            val action = intent.getStringExtra(EXTRA_ACTION) ?: ""
            val key = intent.getStringExtra(EXTRA_KEY) ?: return
            Graph.settings.planShown = (Graph.settings.planShown + key).toList().takeLast(200).toSet()
            Reminders.showPlan(context, title, action)
            schedule(context)
        }
    }

    companion object {
        private const val TAG = "PlanUpdater"
        private const val MAX_CHARS = 100_000
        const val PLAN_URL = "https://raw.githubusercontent.com/cpdreamer/focusguard/main/config/plan.txt"
        const val EXTRA_TITLE = "title"
        const val EXTRA_ACTION = "action"
        const val EXTRA_KEY = "key"
        private val FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

        fun parse(text: String): List<Entry> = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val parts = line.split("|").map { it.trim() }
                if (parts.size < 2 || parts[1].isEmpty()) return@mapNotNull null
                val at = runCatching { LocalDateTime.parse(parts[0], FMT) }.getOrNull() ?: return@mapNotNull null
                Entry(at, parts[1].take(80), parts.getOrNull(2)?.take(200) ?: "")
            }
            .sortedBy { it.at }
            .toList()

        /** The next entry not yet shown and not older than 6 h, or null. */
        fun next(entries: List<Entry>, shown: Set<String>, now: LocalDateTime): Entry? =
            entries.firstOrNull { it.key !in shown && it.at.isAfter(now.minusHours(6)) }

        /** (Re)arms one exact alarm for the next due entry; past-due entries fire right away. */
        fun schedule(context: Context) {
            val alarms = context.getSystemService(AlarmManager::class.java)
            val pi = pending(context, null)
            alarms.cancel(pi)
            val e = next(parse(Graph.settings.planText), Graph.settings.planShown, LocalDateTime.now()) ?: return
            val trigger = e.at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val epi = pending(context, e)
            runCatching {
                alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, maxOf(trigger, System.currentTimeMillis() + 1000), epi)
            }.onFailure { Log.w(TAG, "exact alarm refused, using inexact", it); alarms.set(AlarmManager.RTC_WAKEUP, trigger, epi) }
        }

        private fun pending(context: Context, e: Entry?): PendingIntent {
            val i = Intent(context, AlarmReceiver::class.java).setAction("com.focusguard.PLAN")
            if (e != null) i.putExtra(EXTRA_TITLE, e.title).putExtra(EXTRA_ACTION, e.action).putExtra(EXTRA_KEY, e.key)
            return PendingIntent.getBroadcast(context, 7, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }

        fun scheduleFetch(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "plan",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<PlanUpdater>(15, TimeUnit.MINUTES).setConstraints(constraints).build()
            )
        }
    }
}
