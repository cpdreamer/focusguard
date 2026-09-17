package com.focusguard.net

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.focusguard.core.Graph
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Downloads the community NSFW blocklists, compiles them into a [HashedDomainSet]
 * and atomically swaps the on-disk file. The VPN service reloads it via [Graph.filterStore].
 *
 * Sources (maintained daily by their authors, hundreds of thousands of domains):
 *  - oisd NSFW      https://oisd.nl
 *  - StevenBlack porn extension
 *  - Sinfonietta pornography hosts
 */
class BlocklistUpdater(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val domains = HashSet<String>(600_000)
        var okSources = 0
        for (src in SOURCES) {
            runCatching {
                val conn = URL(src).openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.setRequestProperty("User-Agent", "FocusGuard/1.0")
                conn.inputStream.bufferedReader().useLines { lines ->
                    for (line in lines) HashedDomainSet.parseListLine(line)?.let(domains::add)
                }
                okSources++
            }.onFailure { Log.w(TAG, "source failed: $src", it) }
        }
        if (okSources == 0 || domains.size < MIN_ACCEPTABLE) {
            Log.w(TAG, "update rejected: sources=$okSources domains=${domains.size}")
            return@withContext if (runAttemptCount < 3) Result.retry() else Result.failure()
        }
        val set = HashedDomainSet.fromDomains(domains)
        val target = file(applicationContext)
        val tmp = File(target.path + ".tmp")
        tmp.outputStream().use(set::writeTo)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return@withContext Result.retry()
        }
        Graph.filterStore.reloadDownloaded()
        Log.i(TAG, "blocklist updated: ${set.size} domains from $okSources sources")
        Result.success()
    }

    companion object {
        private const val TAG = "BlocklistUpdater"
        private const val MIN_ACCEPTABLE = 20_000
        const val FILE_NAME = "nsfw.bin"

        val SOURCES = listOf(
            "https://nsfw.oisd.nl/domainswild",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
            "https://raw.githubusercontent.com/Sinfonietta/hostfiles/master/pornography-hosts",
        )

        fun file(context: Context): File = File(context.filesDir, FILE_NAME)

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "blocklist-daily",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<BlocklistUpdater>(1, TimeUnit.DAYS)
                    .setConstraints(constraints)
                    .build()
            )
            if (!file(context).exists()) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "blocklist-initial",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<BlocklistUpdater>().setConstraints(constraints).build()
                )
            }
        }
    }
}
