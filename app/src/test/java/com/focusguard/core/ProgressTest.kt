package com.focusguard.core

import com.focusguard.data.SessionOutcome
import com.focusguard.data.StudySession
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class ProgressTest {

    private val zone: ZoneId = ZoneId.of("Europe/Paris")
    private val wed: LocalDate = LocalDate.of(2026, 9, 16) // Wednesday

    private fun session(day: LocalDate, startHour: Int, minutes: Int, outcome: SessionOutcome = SessionOutcome.DONE): StudySession {
        val start = day.atTime(LocalTime.of(startHour, 0)).atZone(zone).toInstant().toEpochMilli()
        return StudySession(taskId = 1, startMillis = start, endMillis = start + minutes * 60_000L, outcome = outcome)
    }

    @Test
    fun `only done and partial sessions count`() {
        val sessions = listOf(
            session(wed, 17, 50, SessionOutcome.DONE),
            session(wed, 18, 20, SessionOutcome.PARTIAL),
            session(wed, 19, 50, SessionOutcome.SKIPPED),
            session(wed, 20, 50, SessionOutcome.SLIPPED),
        )
        assertEquals(mapOf(wed to 70), Progress.minutesPerDay(sessions, zone))
    }

    @Test
    fun `streak counts consecutive days at or above the minimum`() {
        val sessions = listOf(
            session(wed.minusDays(3), 17, 60),
            session(wed.minusDays(2), 17, 45),
            session(wed.minusDays(1), 17, 50),
            session(wed, 17, 45),
        )
        assertEquals(4, Progress.streak(sessions, zone, wed, dailyMinimum = 45))
        // with a 50-minute minimum today (45) is unfinished, yesterday (50) counts, the day before (45) breaks it
        assertEquals(1, Progress.streak(sessions, zone, wed, dailyMinimum = 50))
    }

    @Test
    fun `an unfinished today does not break the streak but does not count yet`() {
        val sessions = listOf(
            session(wed.minusDays(2), 17, 60),
            session(wed.minusDays(1), 17, 60),
            session(wed, 17, 20),
        )
        assertEquals(2, Progress.streak(sessions, zone, wed, dailyMinimum = 45))
        assertEquals(0, Progress.streak(sessions, zone, wed.plusDays(1), dailyMinimum = 45))
    }

    @Test
    fun `a missed day resets the streak`() {
        val sessions = listOf(
            session(wed.minusDays(3), 17, 60),
            session(wed.minusDays(1), 17, 60),
            session(wed, 17, 60),
        )
        assertEquals(2, Progress.streak(sessions, zone, wed, dailyMinimum = 45))
    }

    @Test
    fun `week total starts on Monday`() {
        val sessions = listOf(
            session(wed.minusDays(3), 17, 60), // Sunday: previous week
            session(wed.minusDays(2), 17, 30), // Monday
            session(wed, 17, 50),
        )
        assertEquals(80, Progress.minutesThisWeek(sessions, zone, wed))
    }

    @Test
    fun `sessions are attributed to the local day they started`() {
        val lateNight = session(wed, 23, 90) // 23:00 -> 00:30 next day
        assertEquals(mapOf(wed to 90), Progress.minutesPerDay(listOf(lateNight), zone))
    }
}
