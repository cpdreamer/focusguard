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
 * Downloads the community blocklists, compiles them into a [HashedDomainSet]
 * and atomically swaps the on-disk file. The VPN service reloads it via [Graph.filterStore].
 *
 * Sources (maintained daily by their authors, ~1.2M domains in total):
 *  - oisd NSFW, StevenBlack porn, Sinfonietta pornography, HaGeZi NSFW (adult / soft adult)
 *  - HaGeZi anti-piracy (pirate streaming / torrent mirrors)
 *  - HaGeZi gambling
 *  - HaGeZi DoH / VPN / proxy bypass services
 *
 * A run that fails to fetch every source only replaces the on-disk file if there is none
 * yet (after retries), so a partial download never shrinks a previously complete list.
 */
class BlocklistUpdater(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val builder = HashedDomainSet.Builder(1_400_000)
        var okSources = 0
        for (src in SOURCES) if (fetch(src, builder)) okSources++
        val extra = updateExtra(applicationContext)
        extra.addAllTo(builder)
        val target = file(applicationContext)
        val complete = okSources == SOURCES.size
        val lastAttempt = runAttemptCount >= 2
        val haveCurrentList = !needsInitialDownload(applicationContext)
        if (okSources == 0 || builder.size < MIN_ACCEPTABLE || (!complete && (!lastAttempt || haveCurrentList))) {
            Log.w(TAG, "update rejected: sources=$okSources/${SOURCES.size} domains=${builder.size} attempt=$runAttemptCount")
            return@withContext if (lastAttempt) Result.failure() else Result.retry()
        }
        val set = builder.build()
        val tmp = File(target.path + ".tmp")
        tmp.outputStream().use(set::writeTo)
        if (!tmp.renameTo(target)) {
            tmp.delete()
            return@withContext Result.retry()
        }
        stampFile(applicationContext).writeText(sourcesStamp())
        Graph.filterStore.reloadDownloaded()
        Log.i(TAG, "blocklist updated: ${set.size} domains from $okSources sources (+${extra.size} extra)")
        Result.success()
    }

    /**
     * The optional sources feed a separate file that only ever grows: whatever they carried once
     * stays blocked even if a line is later removed upstream (or the repo disappears), so the
     * channel can tighten the filter but never loosen it.
     */
    private fun updateExtra(context: Context): HashedDomainSet {
        val file = extraFile(context)
        val previous = HashedDomainSet.readFrom(file) ?: HashedDomainSet.empty()
        val b = HashedDomainSet.Builder(previous.size + 1024)
        previous.addAllTo(b)
        var fetched = 0
        for (src in OPTIONAL_SOURCES) if (fetch(src, b)) fetched++
        val merged = b.build()
        if (fetched > 0 && merged.size > previous.size) {
            val tmp = File(file.path + ".tmp")
            tmp.outputStream().use(merged::writeTo)
            if (!tmp.renameTo(file)) tmp.delete()
        }
        return merged
    }

    private fun fetch(src: String, builder: HashedDomainSet.Builder): Boolean = runCatching {
        val conn = URL(src).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 120_000
        conn.setRequestProperty("User-Agent", "FocusGuard/1.0")
        conn.inputStream.bufferedReader().useLines { lines ->
            for (line in lines) HashedDomainSet.parseListLine(line)?.let(builder::add)
        }
    }.onFailure { Log.w(TAG, "source failed: $src", it) }.isSuccess

    companion object {
        private const val TAG = "BlocklistUpdater"
        private const val MIN_ACCEPTABLE = 200_000
        const val FILE_NAME = "nsfw.bin"

        private const val HAGEZI = "https://raw.githubusercontent.com/hagezi/dns-blocklists/main/wildcard"

        val SOURCES = listOf(
            "https://nsfw.oisd.nl/domainswild",
            "https://raw.githubusercontent.com/StevenBlack/hosts/master/alternates/porn-only/hosts",
            "https://raw.githubusercontent.com/Sinfonietta/hostfiles/master/pornography-hosts",
            "$HAGEZI/nsfw-onlydomains.txt",
            "$HAGEZI/anti.piracy-onlydomains.txt",
            "$HAGEZI/gambling-onlydomains.txt",
            "$HAGEZI/doh-vpn-proxy-bypass-onlydomains.txt",
        )

        /**
         * Block-only list we maintain ourselves: a hole found during a commitment is closed here the
         * same day, no reinstall needed. A failed fetch (repo unreachable) does not veto the update,
         * and entries are ratcheted (see [updateExtra]).
         */
        val OPTIONAL_SOURCES = listOf(
            "https://raw.githubusercontent.com/cpdreamer/focusguard/main/lists/extra-block.txt",
        )

        fun file(context: Context): File = File(context.filesDir, FILE_NAME)
        private fun extraFile(context: Context): File = File(context.filesDir, "extra.bin")
        private fun stampFile(context: Context): File = File(context.filesDir, "$FILE_NAME.sources")
        private fun sourcesStamp(): String = SOURCES.joinToString("\n")

        /** True when there is no compiled list yet, or the one on disk was built from a different set of sources. */
        fun needsInitialDownload(context: Context): Boolean =
            !file(context).exists() || runCatching { stampFile(context).readText() }.getOrNull() != sourcesStamp()

        fun schedule(context: Context) {
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "blocklist-daily",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<BlocklistUpdater>(6, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .build()
            )
            if (needsInitialDownload(context)) {
                WorkManager.getInstance(context).enqueueUniqueWork(
                    "blocklist-initial",
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<BlocklistUpdater>().setConstraints(constraints).build()
                )
            }
        }
    }
}
