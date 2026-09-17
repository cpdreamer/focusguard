package com.focusguard.scheduler

import com.focusguard.data.Commitment
import com.focusguard.data.CommitmentKind
import com.focusguard.data.Task
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

class PlannerTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val planner = Planner(zone)

    // Monday 2026-09-14
    private val monday: LocalDate = LocalDate.of(2026, 9, 14)
    init { check(monday.dayOfWeek == DayOfWeek.MONDAY) }

    private fun at(day: LocalDate, h: Int, m: Int = 0): ZonedDateTime =
        ZonedDateTime.of(LocalDateTime.of(day, LocalTime.of(h, m)), zone)

    private fun millis(day: LocalDate, h: Int, m: Int = 0): Long = at(day, h, m).toInstant().toEpochMilli()

    private var nextId = 1L
    private fun task(
        title: String,
        estimate: Int,
        deadline: Long?,
        importance: Int = 2,
        done: Int = 0,
        created: Long = 0L,
    ) = Task(
        id = nextId++, title = title, subject = "", deadlineMillis = deadline,
        estimateMinutes = estimate, doneMinutes = done, importance = importance, createdMillis = created,
    )

    /** Classes every weekday 8:00-12:00 and 14:00-17:00, lunch 12:00-13:00. */
    private fun schoolWeek(): List<Commitment> = (1..5).flatMap { d ->
        listOf(
            Commitment(title = "Cours", kind = CommitmentKind.CLASS, dayOfWeek = d, startMinute = 8 * 60, endMinute = 12 * 60),
            Commitment(title = "Lunch", kind = CommitmentKind.MEAL, dayOfWeek = d, startMinute = 12 * 60, endMinute = 13 * 60),
            Commitment(title = "Cours", kind = CommitmentKind.CLASS, dayOfWeek = d, startMinute = 14 * 60, endMinute = 17 * 60),
        )
    }

    private fun assertNoOverlap(plan: Plan) {
        val sorted = plan.assignments.sortedBy { it.slot.start }
        for (i in 1 until sorted.size) {
            assertFalse("overlap between ${sorted[i - 1]} and ${sorted[i]}", sorted[i].slot.start.isBefore(sorted[i - 1].slot.end))
        }
    }

    private fun assertNeverDuring(plan: Plan, busy: List<Interval>) {
        for (a in plan.assignments) for (b in busy) {
            val overlaps = a.slot.start.isBefore(b.end) && b.start.isBefore(a.slot.end)
            assertFalse("assignment $a overlaps busy $b", overlaps)
        }
    }

    @Test
    fun `nothing to do gives an empty plan`() {
        val plan = planner.plan(at(monday, 17), schoolWeek(), emptyList())
        assertTrue(plan.assignments.isEmpty())
        assertTrue(plan.infeasible.isEmpty())
        assertNull(plan.current(at(monday, 17)))
        assertNull(plan.next(at(monday, 17)))
    }

    @Test
    fun `work is placed only in free time and never during class, meals or sleep`() {
        val commitments = schoolWeek()
        val now = at(monday, 17, 5)
        val tasks = listOf(task("DM", 600, millis(monday.plusDays(4), 8)))
        val plan = planner.plan(now, commitments, tasks)
        assertNoOverlap(plan)
        val busy = planner.expandCommitments(commitments, monday, monday.plusDays(11))
        assertNeverDuring(plan, busy)
        // sleep default 23:00-07:00 with 45 min wind-down and 30 min morning buffer
        for (a in plan.assignments) {
            val t = a.slot.start.toLocalTime()
            val endT = a.slot.end.toLocalTime()
            assertTrue("starts too late: $a", t.isBefore(LocalTime.of(22, 15)) || t == LocalTime.of(22, 15))
            assertTrue("ends too late: $a", !endT.isAfter(LocalTime.of(22, 15)) || endT == LocalTime.MIDNIGHT)
            assertTrue("starts too early: $a", !t.isBefore(LocalTime.of(7, 30)))
        }
        assertTrue(plan.assignments.isNotEmpty())
    }

    @Test
    fun `first slot starts now and is the current assignment`() {
        val now = at(monday, 17, 5)
        val tasks = listOf(task("DM", 120, millis(monday.plusDays(2), 8)))
        val plan = planner.plan(now, schoolWeek(), tasks)
        val cur = plan.current(now)
        assertNotNull(cur)
        assertEquals(now, cur!!.slot.start)
        assertEquals("DM", cur.task.title)
        assertEquals(50, cur.slot.minutes)
    }

    @Test
    fun `earliest deadline is scheduled first when slack is similar`() {
        val now = at(monday, 17)
        val soon = task("Soon", 60, millis(monday.plusDays(1), 8))
        val later = task("Later", 60, millis(monday.plusDays(3), 8))
        val plan = planner.plan(now, schoolWeek(), listOf(later, soon))
        assertEquals("Soon", plan.assignments.first().task.title)
        // everything for Soon is placed before its deadline
        val soonEnd = plan.assignments.filter { it.task.id == soon.id }.maxOf { it.slot.end }
        assertFalse(soonEnd.isAfter(at(monday.plusDays(1), 8)))
    }

    @Test
    fun `a big task with little slack beats a small task due earlier`() {
        val now = at(monday, 17)
        // Small: 30 min due tomorrow 7:00 (slack ~13h). Big: 200 min due tomorrow 8:00 (slack ~10h).
        val small = task("Small", 30, millis(monday.plusDays(1), 7))
        val big = task("Big", 200, millis(monday.plusDays(1), 8))
        val plan = planner.plan(now, schoolWeek(), listOf(small, big))
        assertEquals("Big", plan.assignments.first().task.title)
        assertTrue(plan.infeasible.isEmpty())
        // both fit tonight and Small is still done before its deadline
        val smallEnd = plan.assignments.filter { it.task.id == small.id }.maxOf { it.slot.end }
        assertFalse(smallEnd.isAfter(at(monday.plusDays(1), 7)))
    }

    @Test
    fun `higher importance lifts a task`() {
        val now = at(monday, 17)
        val normal = task("Normal", 60, millis(monday.plusDays(2), 8), importance = 2)
        val colle = task("Colle", 60, millis(monday.plusDays(2), 8), importance = 3)
        val plan = planner.plan(now, schoolWeek(), listOf(normal, colle))
        assertEquals("Colle", plan.assignments.first().task.title)
    }

    @Test
    fun `tasks without deadline fill remaining time after dated ones`() {
        val now = at(monday, 17)
        val dated = task("Dated", 50, millis(monday.plusDays(1), 8))
        val open = task("Open", 50, null)
        val plan = planner.plan(now, schoolWeek(), listOf(open, dated))
        assertEquals("Dated", plan.assignments[0].task.title)
        assertEquals("Open", plan.assignments[1].task.title)
    }

    @Test
    fun `work never crosses a live deadline and the shortfall is reported infeasible`() {
        val now = at(monday, 20)
        // Tonight 20:00-20:50 + 21:00-22:15 (one break) and tomorrow 7:30-8:00 give 155 min; task needs 300.
        val dm = task("DM", 300, millis(monday.plusDays(1), 8))
        val deadline = at(monday.plusDays(1), 8)
        val plan = planner.plan(now, schoolWeek(), listOf(dm))
        val (before, after) = plan.assignments.partition { !it.slot.end.isAfter(deadline) }
        assertEquals(155L, before.sumOf { it.slot.minutes })
        // the rest is planned late (after the deadline) rather than dropped, and the task is flagged
        assertTrue(after.all { !it.slot.start.isBefore(deadline) })
        assertEquals(145L, after.sumOf { it.slot.minutes })
        assertEquals(listOf(dm.id), plan.infeasible.map { it.id })
    }

    @Test
    fun `a task due in the middle of a slot is cut at the deadline and the slot continues with another task`() {
        val now = at(monday, 17)
        val due1730 = task("Quick", 60, millis(monday, 17, 30))
        val other = task("Other", 60, millis(monday.plusDays(2), 8))
        val plan = planner.plan(now, schoolWeek(), listOf(other, due1730))
        val first = plan.assignments[0]
        assertEquals("Quick", first.task.title)
        assertEquals(at(monday, 17, 30), first.slot.end)
        assertEquals("Other", plan.assignments[1].task.title)
        assertEquals(at(monday, 17, 30), plan.assignments[1].slot.start)
        assertTrue(due1730.id in plan.infeasible.map { it.id })
    }

    @Test
    fun `overdue work is still scheduled, after on-time work, and flagged`() {
        val now = at(monday, 17)
        val late = task("Late", 50, millis(monday.minusDays(1), 8))
        val onTime = task("OnTime", 50, millis(monday.plusDays(1), 8))
        val plan = planner.plan(now, schoolWeek(), listOf(late, onTime))
        assertEquals("OnTime", plan.assignments[0].task.title)
        assertEquals("Late", plan.assignments[1].task.title)
        assertEquals(listOf(late.id), plan.infeasible.map { it.id })
    }

    @Test
    fun `remaining minutes account for work already done`() {
        val now = at(monday, 17)
        val t = task("Half", 100, millis(monday.plusDays(2), 8), done = 60)
        val plan = planner.plan(now, schoolWeek(), listOf(t))
        assertEquals(40L, plan.assignments.sumOf { it.slot.minutes })
    }

    @Test
    fun `completed or finished tasks are not planned`() {
        val now = at(monday, 17)
        val done = task("Done", 100, millis(monday.plusDays(2), 8), done = 100)
        val completed = task("Completed", 100, millis(monday.plusDays(2), 8)).copy(completed = true)
        val plan = planner.plan(now, schoolWeek(), listOf(done, completed))
        assertTrue(plan.assignments.isEmpty())
        assertTrue(plan.infeasible.isEmpty())
    }

    @Test
    fun `extra busy intervals such as a snooze push work later`() {
        val now = at(monday, 17)
        val t = task("DM", 60, millis(monday.plusDays(2), 8))
        val snooze = Interval(now, now.plusMinutes(10))
        val plan = planner.plan(now, schoolWeek(), listOf(t), listOf(snooze))
        assertEquals(now.plusMinutes(10), plan.assignments.first().slot.start)
        assertNull(plan.current(now))
        assertEquals(now.plusMinutes(10), plan.next(now)!!.slot.start)
    }

    @Test
    fun `custom sleep commitment overrides the default sleep window`() {
        val sleep = (1..7).map { Commitment(title = "Sleep", kind = CommitmentKind.SLEEP, dayOfWeek = it, startMinute = 22 * 60, endMinute = 6 * 60) }
        val now = at(monday, 20)
        val t = task("DM", 600, millis(monday.plusDays(5), 8))
        val plan = planner.plan(now, schoolWeek() + sleep, listOf(t))
        for (a in plan.assignments) {
            assertFalse("ends after wind-down: $a", a.slot.end.toLocalTime().isAfter(LocalTime.of(21, 15)) && a.slot.end.toLocalTime() != LocalTime.MIDNIGHT)
            assertFalse("starts before buffer: $a", a.slot.start.toLocalTime().isBefore(LocalTime.of(6, 30)))
        }
    }

    @Test
    fun `plan starting inside a busy block begins at the end of it`() {
        val now = at(monday, 15) // in class until 17:00
        val t = task("DM", 60, millis(monday.plusDays(2), 8))
        val plan = planner.plan(now, schoolWeek(), listOf(t))
        assertNull(plan.current(now))
        assertEquals(at(monday, 17), plan.assignments.first().slot.start)
    }

    // ---------------------------------------------------------------- helpers

    @Test
    fun `splitIntoBlocks cuts into blocks with breaks and merges a short tail`() {
        val free = Interval(at(monday, 17), at(monday, 19, 5)) // 125 min
        val blocks = planner.splitIntoBlocks(free)
        // 50 + 10 break + 50 = 110, tail 15 min < 30 -> merged into the second block
        assertEquals(2, blocks.size)
        assertEquals(at(monday, 17), blocks[0].start)
        assertEquals(at(monday, 17, 50), blocks[0].end)
        assertEquals(at(monday, 18), blocks[1].start)
        assertEquals(at(monday, 19, 5), blocks[1].end)
    }

    @Test
    fun `splitIntoBlocks keeps a long tail as its own block`() {
        val free = Interval(at(monday, 17), at(monday, 19, 30)) // 150 min
        val blocks = planner.splitIntoBlocks(free)
        assertEquals(listOf(50L, 50L, 30L), blocks.map { it.minutes })
    }

    @Test
    fun `merge joins overlapping and touching intervals`() {
        val merged = planner.merge(
            listOf(
                Interval(at(monday, 10), at(monday, 11)),
                Interval(at(monday, 11), at(monday, 12)),
                Interval(at(monday, 13), at(monday, 14)),
                Interval(at(monday, 13, 30), at(monday, 15)),
            )
        )
        assertEquals(listOf(Interval(at(monday, 10), at(monday, 12)), Interval(at(monday, 13), at(monday, 15))), merged)
    }

    @Test
    fun `freeIntervals is the complement of busy within the window`() {
        val free = planner.freeIntervals(
            at(monday, 9), at(monday, 18),
            listOf(Interval(at(monday, 8), at(monday, 10)), Interval(at(monday, 12), at(monday, 13)), Interval(at(monday, 17), at(monday, 20))),
        )
        assertEquals(listOf(Interval(at(monday, 10), at(monday, 12)), Interval(at(monday, 13), at(monday, 17))), free)
    }

    @Test
    fun `commitments crossing midnight are expanded correctly`() {
        val night = Commitment(title = "Night", kind = CommitmentKind.OTHER, dayOfWeek = 1, startMinute = 23 * 60, endMinute = 1 * 60)
        val busy = planner.expandCommitments(listOf(night), monday, monday)
        assertTrue(busy.any { it.start == at(monday, 22, 15) && it.end == at(monday.plusDays(1), 7, 30) })
    }

    @Test
    fun `score is higher for less slack`() {
        val now = at(monday, 17)
        val tight = task("Tight", 120, millis(monday.plusDays(1), 8))
        val loose = task("Loose", 120, millis(monday.plusDays(4), 8))
        assertTrue(planner.score(tight, now) > planner.score(loose, now))
    }
}
