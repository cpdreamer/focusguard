package com.focusguard.core

import android.app.AlarmManager
import android.content.Context
import android.util.Log
import com.focusguard.data.AppDatabase
import com.focusguard.data.BlockMode
import com.focusguard.data.Commitment
import com.focusguard.data.CommitmentKind
import com.focusguard.data.JournalEntry
import com.focusguard.data.JournalKind
import com.focusguard.data.Settings
import com.focusguard.data.SessionOutcome
import com.focusguard.data.StudySession
import com.focusguard.data.Task
import com.focusguard.net.BlocklistUpdater
import com.focusguard.net.ConfigUpdater
import com.focusguard.net.DnsFilterVpnService
import com.focusguard.policy.PolicyController
import com.focusguard.scheduler.Assignment
import com.focusguard.scheduler.Interval
import com.focusguard.scheduler.Plan
import com.focusguard.scheduler.Planner
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The brain. Every external event (alarm, boot, task edited, user tapped Done/Skip)
 * calls [refresh]; refresh recomputes the plan, decides the current mode, enforces
 * app suspension, shows/updates the reminder, and schedules the next alarm.
 *
 * It is idempotent: calling it twice in a row changes nothing. All state that must
 * survive process death lives in [Settings] / the database, never in fields here.
 */
class Coordinator(
    private val context: Context,
    private val db: AppDatabase,
    private val settings: Settings,
    private val policy: PolicyController,
    private val directives: Directives,
) {
    enum class Mode { SLEEP, WORK, FREE }

    data class State(
        val mode: Mode,
        val current: Assignment?,
        val next: Assignment?,
        val infeasible: List<Task>,
        val plan: Plan,
        val computedAt: ZonedDateTime,
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val lock = Mutex()
    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state

    private val zone: ZoneId get() = ZoneId.systemDefault()
    private val alarms: AlarmManager = context.getSystemService(AlarmManager::class.java)

    /** Fire-and-forget refresh; a failure is logged, never allowed to take the guard process down. */
    fun refreshAsync(reason: String) {
        scope.launch {
            try {
                refresh(reason)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "refresh($reason) failed", e)
            }
        }
    }

    suspend fun refresh(reason: String) = lock.withLock {
        val now = ZonedDateTime.now(zone)
        Log.i(TAG, "refresh($reason) at $now")

        handleCommitmentLifecycle(now.toInstant())
        if (!settings.isProvisioned()) return
        for (line in directives.promote(now.toInstant())) Log.i(TAG, "window: $line")

        closeEndedSlot(now)
        val commitments = db.commitments().all()
        val tasks = db.tasks().open()
        val extraBusy = snoozeInterval(now)
        // Plan from the start of the slot in progress (not from "now") so that the plan is
        // stable across refreshes within a slot and the reminder is not re-issued.
        val anchor = if (settings.currentTaskId != -1L && settings.currentSlotStart > 0 &&
            now.toInstant().toEpochMilli() < settings.currentSlotEnd
        ) ZonedDateTime.ofInstant(Instant.ofEpochMilli(settings.currentSlotStart), zone) else now
        val plan = planner().plan(anchor, commitments, tasks, extraBusy)
        val current = plan.current(now)
        val next = plan.next(now)
        val mode = when {
            isSleepTime(now, commitments) -> Mode.SLEEP
            current != null -> Mode.WORK
            else -> Mode.FREE
        }

        // Publish before showing the reminder: ReminderActivity reads the current assignment from here.
        _state.value = State(mode, current, next, plan.infeasible, plan, now)

        enforceApps(mode)
        updateReminder(now, current)
        warnInfeasible(plan)
        maybePromptPlanning(now)
        maybePromptReview(now)
        scheduleNextAlarm(now, plan, current, commitments)
        GuardService.updateNotification(context, mode, current)
    }

    // ------------------------------------------------------------------ commitment / release

    private fun handleCommitmentLifecycle(now: Instant) {
        if (settings.isReleaseDue(now)) {
            Log.w(TAG, "release due: giving the phone back")
            val pkgs = policy.launchableApps(settings.managedPackages).map { it.packageName }
            policy.releaseDeviceOwner(pkgs)
            settings.managedPackages = emptySet()
            settings.clearCommitment()
            settings.setProvisioned(false)
            DnsFilterVpnService.stop(context)
            GuardService.stop(context)
            return
        }
        if (settings.isProvisioned() && policy.isDeviceOwner) {
            policy.applyBaseline()
            if (settings.webFilterEnabled) policy.setAlwaysOnVpn(true)
        }
        if (settings.isProvisioned() && settings.webFilterEnabled) {
            DnsFilterVpnService.startIfPrepared(context)
            BlocklistUpdater.schedule(context)
            ConfigUpdater.schedule(context)
        }
        maybePromptCommitmentOver(now)
    }

    /** Once per commitment, when it ends: tell the user the choice (renew / adjust / release) is open. */
    private fun maybePromptCommitmentOver(now: Instant) {
        if (!settings.isProvisioned()) return
        val end = settings.commitmentEnd() ?: return
        if (end.isAfter(now) || settings.commitmentOverNotifiedEnd == end.toEpochMilli()) return
        settings.commitmentOverNotifiedEnd = end.toEpochMilli()
        Reminders.showCommitmentOver(context)
    }

    // ------------------------------------------------------------------ apps

    private suspend fun enforceApps(mode: Mode) {
        if (!policy.isDeviceOwner) return
        val modes = policy.effectiveModes(db.appRules().all(), settings.commitmentStartMillis(), settings.managedPackages)
        val shouldSuspend = modes.filterValues { m ->
            when (m) {
                BlockMode.ALLOW -> false
                BlockMode.ALWAYS -> true
                BlockMode.FOCUS_ONLY -> mode != Mode.FREE
            }
        }.keys
        val shouldRun = modes.keys - shouldSuspend
        val toSuspend = shouldSuspend.filter { !policy.isSuspended(it) }
        val toResume = shouldRun.filter { policy.isSuspended(it) }
        if (toSuspend.isNotEmpty()) policy.setSuspended(toSuspend, true)
        if (toResume.isNotEmpty()) policy.setSuspended(toResume, false)
        if (!settings.managedPackages.containsAll(shouldSuspend)) settings.managedPackages = settings.managedPackages + shouldSuspend
        Log.i(TAG, "mode=$mode suspended=${shouldSuspend.size} (+${toSuspend.size}/-${toResume.size})")
    }

    // ------------------------------------------------------------------ reminder

    private suspend fun updateReminder(now: ZonedDateTime, current: Assignment?) {
        val prevTask = settings.currentTaskId
        val prevStart = settings.currentSlotStart
        if (current == null) {
            if (prevTask != -1L) closeCurrentSlot(now, prevTask, prevStart, settings.currentSlotEnd)
            return
        }
        val startMillis = current.slot.start.toInstant().toEpochMilli()
        val endMillis = current.slot.end.toInstant().toEpochMilli()
        val isNew = current.task.id != prevTask || startMillis != prevStart
        if (isNew) {
            if (prevTask != -1L) closeCurrentSlot(now, prevTask, prevStart, settings.currentSlotEnd)
            settings.currentTaskId = current.task.id
            settings.currentSlotStart = startMillis
            settings.currentSlotEnd = endMillis
            settings.currentStarted = false
            Reminders.showTask(context, current)
        } else if (settings.currentSlotEnd != endMillis) {
            settings.currentSlotEnd = endMillis
        }
    }

    /**
     * A slot whose end has passed is booked before planning, so the minutes it credited to the
     * task are known to the planner and the next slot (and its reminder) has the right length.
     */
    private suspend fun closeEndedSlot(now: ZonedDateTime) {
        val taskId = settings.currentTaskId
        val end = settings.currentSlotEnd
        if (taskId != -1L && end > 0 && now.toInstant().toEpochMilli() >= end) {
            closeCurrentSlot(now, taskId, settings.currentSlotStart, end)
        }
    }

    /** Book-keep the slot that just ended: credit time if it was started, else record a skip. */
    private suspend fun closeCurrentSlot(now: ZonedDateTime, taskId: Long, startMillis: Long, endMillis: Long) {
        val end = minOf(now.toInstant().toEpochMilli(), if (endMillis > 0) endMillis else Long.MAX_VALUE)
        val minutes = ((end - startMillis) / 60_000L).toInt().coerceAtLeast(0)
        val task = db.tasks().byId(taskId)
        if (task != null && !task.completed && settings.currentStarted && minutes >= 5) {
            db.tasks().update(task.copy(doneMinutes = task.doneMinutes + minutes))
            db.sessions().insert(StudySession(taskId = taskId, startMillis = startMillis, endMillis = end, outcome = SessionOutcome.PARTIAL))
        } else if (task != null && !task.completed && !settings.currentStarted && minutes >= 5) {
            db.sessions().insert(StudySession(taskId = taskId, startMillis = startMillis, endMillis = end, outcome = SessionOutcome.SKIPPED))
        }
        settings.currentTaskId = -1L
        settings.currentSlotStart = -1L
        settings.currentSlotEnd = -1L
        settings.currentStarted = false
        Reminders.cancelTask(context)
    }

    // ------------------------------------------------------------------ user actions

    /** User tapped "Start": the slot now counts as worked. */
    fun markStarted() {
        settings.currentStarted = true
        Reminders.cancelTask(context)
        GuardService.updateNotification(context, _state.value?.mode ?: Mode.WORK, _state.value?.current)
    }

    suspend fun markDone(taskId: Long) {
        val task = db.tasks().byId(taskId) ?: return
        val now = ZonedDateTime.now(zone)
        val start = if (settings.currentTaskId == taskId && settings.currentSlotStart > 0) settings.currentSlotStart else now.toInstant().toEpochMilli()
        db.sessions().insert(StudySession(taskId = taskId, startMillis = start, endMillis = now.toInstant().toEpochMilli(), outcome = SessionOutcome.DONE))
        db.tasks().update(task.copy(completed = true, doneMinutes = task.estimateMinutes))
        if (settings.currentTaskId == taskId) {
            settings.currentTaskId = -1L; settings.currentSlotStart = -1L; settings.currentSlotEnd = -1L; settings.currentStarted = false
            Reminders.cancelTask(context)
        }
        refresh("done")
    }

    /** User cannot work this slot: silence until the slot ends, replan. */
    suspend fun skipCurrentSlot() {
        val end = settings.currentSlotEnd
        val now = System.currentTimeMillis()
        settings.snoozeUntil = if (end > now) end else now + 30 * 60_000L
        val taskId = settings.currentTaskId
        if (taskId != -1L) {
            db.sessions().insert(StudySession(taskId = taskId, startMillis = settings.currentSlotStart, endMillis = now, outcome = SessionOutcome.SKIPPED))
            settings.currentTaskId = -1L; settings.currentSlotStart = -1L; settings.currentSlotEnd = -1L; settings.currentStarted = false
        }
        Reminders.cancelTask(context)
        refresh("skip")
    }

    /**
     * "Slipped": no verdict. Ten minutes of reset (shower/walk), then the next block counts.
     * Implemented as a 10-minute snooze followed by a normal refresh, plus a journal hook.
     */
    suspend fun slipped(note: String?) {
        val now = System.currentTimeMillis()
        val taskId = settings.currentTaskId
        if (taskId != -1L) {
            db.sessions().insert(StudySession(taskId = taskId, startMillis = settings.currentSlotStart, endMillis = now, outcome = SessionOutcome.SLIPPED))
            settings.currentTaskId = -1L; settings.currentSlotStart = -1L; settings.currentSlotEnd = -1L; settings.currentStarted = false
        } else {
            db.sessions().insert(StudySession(taskId = null, startMillis = now, endMillis = now, outcome = SessionOutcome.SLIPPED))
        }
        if (!note.isNullOrBlank()) {
            db.journal().insert(JournalEntry(createdMillis = now, kind = JournalKind.SLIP, text = note.trim()))
        }
        settings.snoozeUntil = now + RESET_MINUTES * 60_000L
        Reminders.cancelTask(context)
        Reminders.showReset(context, RESET_MINUTES)
        refresh("slipped")
    }

    // ------------------------------------------------------------------ helpers

    private fun planner(): Planner = Planner(
        zone = zone,
        blockMinutes = settings.blockMinutes,
        breakMinutes = settings.breakMinutes,
        sleepStartMinute = settings.bedtimeMinute,
        sleepEndMinute = settings.wakeMinute,
    )

    private fun snoozeInterval(now: ZonedDateTime): List<Interval> {
        val until = settings.snoozeUntil
        if (until <= now.toInstant().toEpochMilli()) return emptyList()
        return listOf(Interval(now, ZonedDateTime.ofInstant(Instant.ofEpochMilli(until), zone)))
    }

    internal fun isSleepTime(now: ZonedDateTime, commitments: List<Commitment>): Boolean {
        val sleeps = commitments.filter { it.kind == CommitmentKind.SLEEP }
        if (sleeps.isEmpty()) return inDailyWindow(now.toLocalTime(), settings.bedtimeMinute, settings.wakeMinute)
        // A sleep block on day D may run past midnight into D+1: check today and yesterday's entries.
        val todayDow = now.dayOfWeek.value
        val yesterdayDow = now.minusDays(1).dayOfWeek.value
        val minute = now.toLocalTime().toSecondOfDay() / 60
        return sleeps.any { s ->
            (s.dayOfWeek == todayDow && s.startMinute <= s.endMinute && minute in s.startMinute until s.endMinute) ||
                (s.dayOfWeek == todayDow && s.startMinute > s.endMinute && minute >= s.startMinute) ||
                (s.dayOfWeek == yesterdayDow && s.startMinute > s.endMinute && minute < s.endMinute)
        }
    }

    private fun inDailyWindow(t: LocalTime, startMin: Int, endMin: Int): Boolean {
        val m = t.toSecondOfDay() / 60
        return if (startMin <= endMin) m in startMin until endMin else m >= startMin || m < endMin
    }

    /** Tell the user once (per set of tasks) that something cannot be finished in time. */
    private fun warnInfeasible(plan: Plan) {
        val key = plan.infeasible.map { it.id }.sorted().joinToString(",")
        if (key == settings.lastInfeasibleKey) return
        settings.lastInfeasibleKey = key
        Reminders.showInfeasible(context, plan.infeasible.map { it.title })
    }

    private fun maybePromptPlanning(now: ZonedDateTime) {
        val today = now.toLocalDate().toEpochDay()
        val minute = now.toLocalTime().toSecondOfDay() / 60
        if (minute >= settings.planningMinute && settings.lastPlannedEpochDay < today) {
            settings.lastPlannedEpochDay = today
            Reminders.showPlanTomorrow(context)
        }
    }

    private fun maybePromptReview(now: ZonedDateTime) {
        if (now.dayOfWeek.value != 7) return
        val today = now.toLocalDate().toEpochDay()
        val minute = now.toLocalTime().toSecondOfDay() / 60
        if (minute >= REVIEW_MINUTE && settings.lastReviewEpochDay < today) {
            settings.lastReviewEpochDay = today
            Reminders.showWeeklyReview(context)
        }
    }

    /** Next moment anything can change: earliest of slot boundaries, mode boundaries, prompts. */
    private fun scheduleNextAlarm(now: ZonedDateTime, plan: Plan, current: Assignment?, commitments: List<Commitment>) {
        val candidates = mutableListOf<ZonedDateTime>()
        current?.let { candidates += it.slot.end }
        plan.next(now)?.let { candidates += it.slot.start }
        candidates += nextDailyTime(now, settings.bedtimeMinute)
        candidates += nextDailyTime(now, settings.wakeMinute)
        candidates += nextDailyTime(now, settings.planningMinute)
        candidates += nextDailyTime(now, REVIEW_MINUTE)
        commitments.forEach { c ->
            candidates += nextWeeklyTime(now, c.dayOfWeek, c.startMinute)
            candidates += nextWeeklyTime(now, c.dayOfWeek, c.endMinute)
        }
        settings.commitmentEnd()?.let { candidates += ZonedDateTime.ofInstant(it, zone) }
        settings.releaseAt()?.let { candidates += ZonedDateTime.ofInstant(it, zone) }
        if (settings.snoozeUntil > 0) candidates += ZonedDateTime.ofInstant(Instant.ofEpochMilli(settings.snoozeUntil), zone)
        settings.rules.pending.values.minOrNull()?.let { candidates += ZonedDateTime.ofInstant(Instant.ofEpochMilli(it), zone) }
        candidates += now.plusMinutes(HEARTBEAT_MINUTES)

        val next = candidates.filter { it.isAfter(now) }.minOrNull() ?: now.plusMinutes(HEARTBEAT_MINUTES)
        val trigger = next.toInstant().toEpochMilli() + 500 // half a second past the boundary
        val pi = AlarmReceiver.pendingIntent(context)
        if (alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pi)
        } else {
            alarms.setWindow(AlarmManager.RTC_WAKEUP, trigger, 60_000L, pi)
        }
        Log.i(TAG, "next alarm at $next")
    }

    private fun nextDailyTime(now: ZonedDateTime, minute: Int): ZonedDateTime {
        val today = now.toLocalDate().atStartOfDay(zone).plusMinutes(minute.toLong())
        return if (today.isAfter(now)) today else today.plusDays(1)
    }

    private fun nextWeeklyTime(now: ZonedDateTime, dayOfWeek: Int, minute: Int): ZonedDateTime {
        var d: LocalDate = now.toLocalDate()
        repeat(8) {
            if (d.dayOfWeek.value == dayOfWeek) {
                val t = d.atStartOfDay(zone).plusMinutes(minute.toLong())
                if (t.isAfter(now)) return t
            }
            d = d.plusDays(1)
        }
        return now.plusDays(7)
    }

    companion object {
        private const val TAG = "Coordinator"
        const val HEARTBEAT_MINUTES = 30L
        const val RESET_MINUTES = 10
        /** Sunday 20:00. */
        const val REVIEW_MINUTE = 20 * 60
    }
}
