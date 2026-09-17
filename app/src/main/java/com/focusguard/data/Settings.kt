package com.focusguard.data

import android.content.Context
import android.content.SharedPreferences
import java.time.Duration
import java.time.Instant

/**
 * Small typed wrapper over SharedPreferences for configuration and the lock state.
 *
 * Lock model: the user commits for a fixed period (default 14 days). While the
 * commitment is active, policies cannot be relaxed from the phone at all. Once it has
 * ended, the user can renew, adjust, or release the device from the app. The only
 * exit during the commitment is deliberate: a computer with ADB (see AdbReleaseReceiver).
 */
class Settings(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("focusguard", Context.MODE_PRIVATE)

    fun isProvisioned(): Boolean = prefs.getBoolean(KEY_PROVISIONED, false)
    fun setProvisioned(v: Boolean) = prefs.edit().putBoolean(KEY_PROVISIONED, v).apply()

    fun commitmentEnd(): Instant? =
        prefs.getLong(KEY_COMMIT_END, -1L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    fun startCommitment(now: Instant, duration: Duration) {
        prefs.edit()
            .putLong(KEY_COMMIT_START, now.toEpochMilli())
            .putLong(KEY_COMMIT_END, now.plus(duration).toEpochMilli())
            .remove(KEY_RELEASE_AT)
            .apply()
    }

    /** Epoch millis when the (first) commitment started; -1 if none. Apps installed later are blocked. */
    fun commitmentStartMillis(): Long = prefs.getLong(KEY_COMMIT_START, -1L)

    /** Every package we have ever suspended, so it is always re-evaluated (and released) later. */
    var managedPackages: Set<String>
        get() = prefs.getStringSet(KEY_MANAGED, emptySet()) ?: emptySet()
        set(v) = prefs.edit().putStringSet(KEY_MANAGED, v).apply()

    fun isCommitmentActive(now: Instant): Boolean = commitmentEnd()?.isAfter(now) == true

    fun releaseAt(): Instant? =
        prefs.getLong(KEY_RELEASE_AT, -1L).takeIf { it > 0 }?.let(Instant::ofEpochMilli)

    /** Schedules a release; refused while the commitment is active unless [force]. */
    fun requestRelease(now: Instant, force: Boolean = false): Boolean {
        if (!force && isCommitmentActive(now)) return false
        prefs.edit().putLong(KEY_RELEASE_AT, now.toEpochMilli()).apply()
        return true
    }

    fun isReleaseDue(now: Instant): Boolean = releaseAt()?.let { !it.isAfter(now) } == true

    fun clearCommitment() = prefs.edit().remove(KEY_COMMIT_END).remove(KEY_COMMIT_START).remove(KEY_RELEASE_AT).apply()

    /** Minutes from midnight. Defaults: sleep 23:00 -> 07:00. */
    var bedtimeMinute: Int
        get() = prefs.getInt(KEY_BEDTIME, 23 * 60)
        set(v) = prefs.edit().putInt(KEY_BEDTIME, v).apply()

    var wakeMinute: Int
        get() = prefs.getInt(KEY_WAKE, 7 * 60)
        set(v) = prefs.edit().putInt(KEY_WAKE, v).apply()

    /** Evening time at which "plan tomorrow" pops. */
    var planningMinute: Int
        get() = prefs.getInt(KEY_PLANNING, 21 * 60 + 30)
        set(v) = prefs.edit().putInt(KEY_PLANNING, v).apply()

    /** Minimum minutes of study per day to count for the streak. */
    var dailyMinimumMinutes: Int
        get() = prefs.getInt(KEY_DAILY_MIN, 45)
        set(v) = prefs.edit().putInt(KEY_DAILY_MIN, v).apply()

    /** Length of one study block in minutes. */
    var blockMinutes: Int
        get() = prefs.getInt(KEY_BLOCK_MIN, 50)
        set(v) = prefs.edit().putInt(KEY_BLOCK_MIN, v).apply()

    var breakMinutes: Int
        get() = prefs.getInt(KEY_BREAK_MIN, 10)
        set(v) = prefs.edit().putInt(KEY_BREAK_MIN, v).apply()

    /** Whether web filtering (VPN) is enabled. Cannot be disabled while committed. */
    var webFilterEnabled: Boolean
        get() = prefs.getBoolean(KEY_WEB_FILTER, true)
        set(v) = prefs.edit().putBoolean(KEY_WEB_FILTER, v).apply()

    /** Id of the task currently shown as "now", and when that slot started. */
    var currentTaskId: Long
        get() = prefs.getLong(KEY_CURRENT_TASK, -1L)
        set(v) = prefs.edit().putLong(KEY_CURRENT_TASK, v).apply()

    var currentSlotStart: Long
        get() = prefs.getLong(KEY_SLOT_START, -1L)
        set(v) = prefs.edit().putLong(KEY_SLOT_START, v).apply()

    var currentSlotEnd: Long
        get() = prefs.getLong(KEY_SLOT_END, -1L)
        set(v) = prefs.edit().putLong(KEY_SLOT_END, v).apply()

    /** True once the user tapped "Start" on the current slot: its time then counts as worked. */
    var currentStarted: Boolean
        get() = prefs.getBoolean(KEY_SLOT_STARTED, false)
        set(v) = prefs.edit().putBoolean(KEY_SLOT_STARTED, v).apply()

    /** Epoch millis until which the user asked for a break from reminders (after "slipped"). */
    var snoozeUntil: Long
        get() = prefs.getLong(KEY_SNOOZE, -1L)
        set(v) = prefs.edit().putLong(KEY_SNOOZE, v).apply()

    /** Epoch day (LocalDate.toEpochDay) of the last day where planning prompt was answered. */
    var lastPlannedEpochDay: Long
        get() = prefs.getLong(KEY_LAST_PLANNED, -1L)
        set(v) = prefs.edit().putLong(KEY_LAST_PLANNED, v).apply()

    /** Sorted task ids of the last "cannot finish in time" warning, to warn only on change. */
    var lastInfeasibleKey: String
        get() = prefs.getString(KEY_LAST_INFEASIBLE, "") ?: ""
        set(v) = prefs.edit().putString(KEY_LAST_INFEASIBLE, v).apply()

    var lastReviewEpochDay: Long
        get() = prefs.getLong(KEY_LAST_REVIEW, -1L)
        set(v) = prefs.edit().putLong(KEY_LAST_REVIEW, v).apply()

    /** Commitment end (epoch millis) for which the "commitment over" prompt was already shown. */
    var commitmentOverNotifiedEnd: Long
        get() = prefs.getLong(KEY_COMMIT_OVER_NOTIFIED, -1L)
        set(v) = prefs.edit().putLong(KEY_COMMIT_OVER_NOTIFIED, v).apply()

    companion object {
        val DEFAULT_COMMITMENT: Duration = Duration.ofDays(14)

        private const val KEY_PROVISIONED = "provisioned"
        private const val KEY_COMMIT_END = "commit_end"
        private const val KEY_COMMIT_START = "commit_start"
        private const val KEY_MANAGED = "managed_pkgs"
        private const val KEY_RELEASE_AT = "release_at"
        private const val KEY_BEDTIME = "bedtime"
        private const val KEY_WAKE = "wake"
        private const val KEY_PLANNING = "planning"
        private const val KEY_DAILY_MIN = "daily_min"
        private const val KEY_BLOCK_MIN = "block_min"
        private const val KEY_BREAK_MIN = "break_min"
        private const val KEY_WEB_FILTER = "web_filter"
        private const val KEY_CURRENT_TASK = "current_task"
        private const val KEY_SLOT_START = "slot_start"
        private const val KEY_SLOT_END = "slot_end"
        private const val KEY_SLOT_STARTED = "slot_started"
        private const val KEY_SNOOZE = "snooze"
        private const val KEY_LAST_PLANNED = "last_planned"
        private const val KEY_LAST_REVIEW = "last_review"
        private const val KEY_LAST_INFEASIBLE = "last_infeasible"
        private const val KEY_COMMIT_OVER_NOTIFIED = "commit_over_notified"
    }
}
