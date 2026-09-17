package com.focusguard.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A homework / task the user must do. Times are epoch millis in the device zone.
 */
@Entity(tableName = "tasks")
data class Task(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val subject: String,
    /** Deadline as epoch millis; null = no deadline (lowest urgency). */
    val deadlineMillis: Long?,
    /** Total estimated work in minutes. */
    val estimateMinutes: Int,
    /** Minutes already logged as done on this task. */
    val doneMinutes: Int = 0,
    /** 1 = low, 2 = normal, 3 = high (colle / DM / DS). */
    val importance: Int = 2,
    /** The concrete two-minute first action shown on the reminder. */
    val firstAction: String = "",
    val completed: Boolean = false,
    val createdMillis: Long,
) {
    val remainingMinutes: Int get() = (estimateMinutes - doneMinutes).coerceAtLeast(0)
}

/**
 * Recurring fixed commitment: class, meal, sleep, sport. Weekly, by day-of-week.
 * [dayOfWeek] uses java.time.DayOfWeek values 1 (Monday) .. 7 (Sunday).
 * Times are minutes from midnight. A block may cross midnight (endMinute < startMinute).
 */
@Entity(tableName = "commitments")
data class Commitment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val kind: CommitmentKind,
    val dayOfWeek: Int,
    val startMinute: Int,
    val endMinute: Int,
)

enum class CommitmentKind { CLASS, MEAL, SLEEP, SPORT, OTHER }

/**
 * A concrete study session the scheduler produced or the user completed.
 * Kept as history for the weekly review and streak.
 */
@Entity(tableName = "sessions")
data class StudySession(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val taskId: Long?,
    val startMillis: Long,
    val endMillis: Long,
    val outcome: SessionOutcome,
)

enum class SessionOutcome { DONE, PARTIAL, SKIPPED, SLIPPED }

/** One free-text line of journal (Sunday review, slip note, tomorrow plan). */
@Entity(tableName = "journal")
data class JournalEntry(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val createdMillis: Long,
    val kind: JournalKind,
    val text: String,
)

enum class JournalKind { WEEKLY_REVIEW, SLIP, TOMORROW_PLAN }

/** User-managed rule for an app package. */
@Entity(tableName = "app_rules")
data class AppRule(
    @PrimaryKey val packageName: String,
    val mode: BlockMode,
)

enum class BlockMode {
    /** Never suspended. */
    ALLOW,
    /** Suspended only during work slots and sleep. */
    FOCUS_ONLY,
    /** Always suspended. */
    ALWAYS,
}

/** Extra domains the user adds to the blocklist (exact host or suffix). */
@Entity(tableName = "domain_rules")
data class DomainRule(
    @PrimaryKey val host: String,
    val blocked: Boolean,
)
