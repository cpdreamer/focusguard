package com.focusguard.core

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.focusguard.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit

/**
 * The phone's own copy of the reset-protection escrow (account + password), with the slow door
 * the user chose (21 Nov 2026): `request` reveals the password [DELAY_HOURS] later, `cancel`
 * drops the request before that, and both post a GitHub issue on the repo so the coach is woken
 * by an automation and can talk. The password is also revealed when the commitment is over.
 * Setup (account, password, GitHub token) is possible only while no commitment is active.
 */
object Escrow {
    const val DELAY_HOURS = 72L
    private const val ISSUES_URL = "https://api.github.com/repos/cpdreamer/focusguard/issues"
    private val fmt = DateTimeFormatter.ofPattern("EEE dd MMM HH:mm")

    val isSealed: Boolean get() = Graph.settings.escrowPassword.isNotEmpty()
    val due: Instant? get() = Graph.settings.escrowDueMillis.takeIf { it > 0 }?.let { Instant.ofEpochMilli(it) }
    val hasToken: Boolean get() = Graph.settings.escrowToken.isNotEmpty()

    /** Pure rule, unit-tested: sealed and (delay elapsed, or no commitment running). */
    fun revealed(sealed: Boolean, dueMillis: Long, nowMillis: Long, commitmentActive: Boolean): Boolean =
        sealed && (!commitmentActive || (dueMillis > 0 && nowMillis >= dueMillis))

    fun isRevealed(now: Instant = Instant.now()): Boolean =
        revealed(isSealed, Graph.settings.escrowDueMillis, now.toEpochMilli(), Graph.settings.isCommitmentActive(now))

    fun setup(account: String, password: String, token: String, now: Instant = Instant.now()): Boolean {
        if (Graph.settings.isCommitmentActive(now) && isSealed) return false
        if (account.isBlank() || password.length < 8) return false
        Graph.settings.escrowAccount = account.trim()
        Graph.settings.escrowPassword = password
        Graph.settings.escrowToken = token.trim()
        Graph.settings.escrowDueMillis = -1
        return true
    }

    fun request(context: Context, now: Instant = Instant.now()): Instant? {
        if (!isSealed || due != null) return due
        val at = now.plus(DELAY_HOURS, ChronoUnitHours)
        Graph.settings.escrowDueMillis = at.toEpochMilli()
        postIssue(context, "escrow request", "The phone asked for the reset-protection password at ${fmt(now)}. It is revealed on the phone at ${fmt(at)} unless cancelled before.")
        WorkManager.getInstance(context).enqueueUniqueWork(
            DUE_WORK, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DueWorker>().setInitialDelay(DELAY_HOURS, TimeUnit.HOURS).build()
        )
        return at
    }

    fun cancel(context: Context, now: Instant = Instant.now()): Boolean {
        if (due == null) return false
        Graph.settings.escrowDueMillis = -1
        WorkManager.getInstance(context).cancelUniqueWork(DUE_WORK)
        postIssue(context, "escrow cancelled", "The phone cancelled its reset-password request at ${fmt(now)}. The password stays sealed.")
        return true
    }

    fun fmt(i: Instant): String = fmt.format(i.atZone(ZoneId.systemDefault()))

    fun statusLine(context: Context): String {
        if (!isSealed) return context.getString(R.string.escrow_none)
        if (isRevealed()) return context.getString(R.string.escrow_revealed, Graph.settings.escrowAccount)
        val d = due
        return if (d == null) context.getString(R.string.escrow_sealed, Graph.settings.escrowAccount, if (hasToken) "" else context.getString(R.string.escrow_no_token))
        else context.getString(R.string.escrow_requested, fmt(d))
    }

    private fun postIssue(context: Context, title: String, body: String) {
        if (!hasToken) return
        val data = Data.Builder().putString("title", title).putString("body", body).build()
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<IssueWorker>()
                .setInputData(data)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES)
                .build()
        )
    }

    private const val DUE_WORK = "escrow-due"
    private val ChronoUnitHours = java.time.temporal.ChronoUnit.HOURS

    class IssueWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
            val token = Graph.settings.escrowToken
            if (token.isEmpty()) return@withContext Result.success()
            val payload = JSONObject().put("title", inputData.getString("title")).put("body", inputData.getString("body")).toString()
            val ok = runCatching {
                val c = URL(ISSUES_URL).openConnection() as HttpURLConnection
                c.requestMethod = "POST"
                c.connectTimeout = 15_000; c.readTimeout = 30_000
                c.setRequestProperty("Authorization", "Bearer $token")
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.setRequestProperty("Content-Type", "application/json")
                c.setRequestProperty("User-Agent", "FocusGuard/1.0")
                c.doOutput = true
                c.outputStream.use { it.write(payload.toByteArray()) }
                c.responseCode in 200..299
            }.getOrDefault(false)
            if (ok) Result.success() else if (runAttemptCount < 20) Result.retry() else Result.failure()
        }
    }

    class DueWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
        override suspend fun doWork(): Result {
            if (isRevealed()) Reminders.showEscrowReady(applicationContext)
            return Result.success()
        }
    }
}
