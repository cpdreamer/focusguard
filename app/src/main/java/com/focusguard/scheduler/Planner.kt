package com.focusguard.scheduler

import com.focusguard.data.Commitment
import com.focusguard.data.CommitmentKind
import com.focusguard.data.Task
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** A half-open interval of wall-clock time [start, end). */
data class Interval(val start: ZonedDateTime, val end: ZonedDateTime) {
    init { require(!end.isBefore(start)) { "end before start" } }
    val minutes: Long get() = Duration.between(start, end).toMinutes()
    fun contains(t: ZonedDateTime): Boolean = !t.isBefore(start) && t.isBefore(end)
}

/** One planned piece of work: [task] during [slot]. */
data class Assignment(val task: Task, val slot: Interval)

/** Output of [Planner.plan]. */
data class Plan(
    val assignments: List<Assignment>,
    /** Tasks that cannot be finished before their deadline given the free time available. */
    val infeasible: List<Task>,
) {
    /** Whatever should be shown "now"; null if the user is in a commitment or idle time. */
    fun current(now: ZonedDateTime): Assignment? = assignments.firstOrNull { it.slot.contains(now) }

    /** Next assignment starting strictly after [now]. */
    fun next(now: ZonedDateTime): Assignment? =
        assignments.filter { it.slot.start.isAfter(now) }.minByOrNull { it.slot.start }
}

/**
 * Pure, deterministic scheduler. Given fixed commitments and open tasks, it fills the
 * free time of the coming [horizonDays] with tasks, earliest-deadline-first with a
 * secondary weight on importance, cutting work into blocks of [blockMinutes].
 *
 * Deliberately simple and explainable: the user must be able to predict why a task
 * was chosen. No machine learning, no randomness.
 */
class Planner(
    private val zone: ZoneId,
    private val blockMinutes: Int = 50,
    private val breakMinutes: Int = 10,
    private val minSlotMinutes: Int = 20,
    private val horizonDays: Int = 10,
    private val sleepStartMinute: Int = 23 * 60,
    private val sleepEndMinute: Int = 7 * 60,
    /** No work is planned this long before bedtime (wind-down) ... */
    private val windDownMinutes: Int = 45,
    /** ... nor this long after wake-up. */
    private val morningBufferMinutes: Int = 30,
) {

    /**
     * @param extraBusy one-off busy intervals (e.g. "user skipped until 18:50").
     */
    fun plan(
        now: ZonedDateTime,
        commitments: List<Commitment>,
        tasks: List<Task>,
        extraBusy: List<Interval> = emptyList(),
    ): Plan {
        val horizonEnd = now.plusDays(horizonDays.toLong())
        val busy = merge(
            expandCommitments(commitments, now.toLocalDate().minusDays(1), horizonEnd.toLocalDate().plusDays(1)) + extraBusy
        )
        val free = freeIntervals(now, horizonEnd, busy)
            .flatMap { splitIntoBlocks(it) }
            .filter { it.minutes >= minSlotMinutes }

        val remaining = tasks.filter { !it.completed && it.remainingMinutes > 0 }
            .associate { it.id to it.remainingMinutes }.toMutableMap()
        val byId = tasks.associateBy { it.id }

        val assignments = mutableListOf<Assignment>()
        for (slot in free) {
            var cursor = slot.start
            while (Duration.between(cursor, slot.end).toMinutes() >= MIN_PIECE_MINUTES) {
                val at = cursor
                val candidate = remaining.keys
                    .map { byId.getValue(it) }
                    .maxWithOrNull(priorityComparator(at))
                    ?: break
                val need = remaining.getValue(candidate.id)
                val hardEnd = candidate.deadlineMillis?.takeIf { isLive(it, at) }?.let { minOf(slot.end, toZdt(it)) } ?: slot.end
                val use = minOf(need.toLong(), Duration.between(at, hardEnd).toMinutes())
                val end = at.plusMinutes(use)
                assignments += Assignment(candidate, Interval(at, end))
                if (need - use <= 0) remaining.remove(candidate.id) else remaining[candidate.id] = (need - use).toInt()
                cursor = end
            }
        }

        // A task is infeasible when work is left after its deadline: either it was never fully
        // placed (deadline inside the horizon) or part of it had to be placed past the deadline.
        val late = assignments.filter { a -> a.task.deadlineMillis != null && a.slot.end.isAfter(toZdt(a.task.deadlineMillis)) }
            .map { it.task.id }.toSet()
        val infeasible = tasks.filter { t ->
            t.deadlineMillis != null && (t.id in late || (t.id in remaining && toZdt(t.deadlineMillis).isBefore(horizonEnd)))
        }
        return Plan(assignments, infeasible)
    }

    /** A deadline still worth racing: far enough ahead of [at] to fit a real piece of work. */
    private fun isLive(deadlineMillis: Long, at: ZonedDateTime): Boolean =
        Duration.between(at, toZdt(deadlineMillis)).toMinutes() >= MIN_PIECE_MINUTES

    /**
     * Higher = more urgent. Uses slack (time to deadline minus work left) as the main
     * signal so a big task due in three days beats a small one due in two if the big one
     * cannot otherwise be finished; importance breaks ties and lifts DS/colle prep.
     */
    private fun priorityComparator(at: ZonedDateTime): Comparator<Task> =
        compareBy<Task> { score(it, at) }.thenBy { it.importance }.thenByDescending { it.createdMillis }

    internal fun score(task: Task, at: ZonedDateTime): Double {
        val deadline = task.deadlineMillis ?: return NO_DEADLINE_SCORE + task.importance
        // Already late: still worth doing, but after everything that can still be on time.
        if (!isLive(deadline, at)) return OVERDUE_SCORE + task.importance
        val hoursLeft = Duration.between(at, toZdt(deadline)).toMinutes() / 60.0
        val workLeftHours = task.remainingMinutes / 60.0
        // slack in hours; negative slack means already late
        val slack = hoursLeft - workLeftHours * SLACK_FACTOR
        return -slack + (task.importance - 2) * IMPORTANCE_HOURS
    }

    private fun toZdt(millis: Long): ZonedDateTime =
        ZonedDateTime.ofInstant(Instant.ofEpochMilli(millis), zone)

    /** Concrete busy intervals across the given dates, including default sleep. */
    internal fun expandCommitments(commitments: List<Commitment>, from: LocalDate, to: LocalDate): List<Interval> {
        val out = mutableListOf<Interval>()
        var d = from
        while (!d.isAfter(to)) {
            val dow = d.dayOfWeek.value
            for (c in commitments.filter { it.dayOfWeek == dow }) out += toInterval(d, c.startMinute, c.endMinute)
            val sleeps = commitments.filter { it.dayOfWeek == dow && it.kind == CommitmentKind.SLEEP }
            if (sleeps.isEmpty()) {
                out += toInterval(d, sleepStartMinute - windDownMinutes, sleepEndMinute + morningBufferMinutes)
            } else {
                for (s in sleeps) out += toInterval(d, s.startMinute - windDownMinutes, s.endMinute + morningBufferMinutes)
            }
            d = d.plusDays(1)
        }
        return merge(out)
    }

    private fun toInterval(day: LocalDate, startMin: Int, endMin: Int): Interval {
        val start = ZonedDateTime.of(LocalDateTime.of(day, LocalTime.MIDNIGHT).plusMinutes(startMin.toLong()), zone)
        var end = ZonedDateTime.of(LocalDateTime.of(day, LocalTime.MIDNIGHT).plusMinutes(endMin.toLong()), zone)
        if (!end.isAfter(start)) end = end.plusDays(1)
        return Interval(start, end)
    }

    internal fun merge(intervals: List<Interval>): List<Interval> {
        val sorted = intervals.sortedBy { it.start }
        val out = mutableListOf<Interval>()
        for (i in sorted) {
            val last = out.lastOrNull()
            if (last != null && !i.start.isAfter(last.end)) {
                out[out.lastIndex] = Interval(last.start, maxOf(last.end, i.end))
            } else out += i
        }
        return out
    }

    internal fun freeIntervals(from: ZonedDateTime, to: ZonedDateTime, busy: List<Interval>): List<Interval> {
        val out = mutableListOf<Interval>()
        var cursor = from
        for (b in busy.sortedBy { it.start }) {
            if (b.end.isBefore(cursor) || b.end == cursor) continue
            if (b.start.isAfter(to)) break
            if (b.start.isAfter(cursor)) out += Interval(cursor, minOf(b.start, to))
            cursor = maxOf(cursor, b.end)
            if (!cursor.isBefore(to)) break
        }
        if (cursor.isBefore(to)) out += Interval(cursor, to)
        return out
    }

    /** Cut a free interval into work blocks separated by breaks; a short tail is merged into the previous block. */
    internal fun splitIntoBlocks(free: Interval): List<Interval> {
        val out = mutableListOf<Interval>()
        var s = free.start
        while (s.isBefore(free.end)) {
            var e = minOf(s.plusMinutes(blockMinutes.toLong()), free.end)
            val tail = Duration.between(e, free.end).toMinutes()
            if (tail in 1 until minSlotMinutes + breakMinutes) e = free.end
            out += Interval(s, e)
            s = e.plusMinutes(breakMinutes.toLong())
        }
        return out
    }

    companion object {
        /** Work is padded so a 2h task "needs" 3h of slack; accounts for fatigue and interruptions. */
        const val SLACK_FACTOR = 1.5
        /** Each importance step above normal is worth this many hours of urgency. */
        const val IMPORTANCE_HOURS = 12.0
        /** Smallest piece of a slot worth assigning to a task. */
        const val MIN_PIECE_MINUTES = 10
        private const val NO_DEADLINE_SCORE = -1e9
        private const val OVERDUE_SCORE = -1e9 + 100
    }
}
