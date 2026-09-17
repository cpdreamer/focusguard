package com.focusguard.core

import com.focusguard.data.SessionOutcome
import com.focusguard.data.StudySession
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Pure helpers over session history: streak and weekly totals. */
object Progress {

    /** Minutes credited (DONE + PARTIAL sessions) per local day. */
    fun minutesPerDay(sessions: List<StudySession>, zone: ZoneId): Map<LocalDate, Int> {
        val out = HashMap<LocalDate, Int>()
        for (s in sessions) {
            if (s.outcome != SessionOutcome.DONE && s.outcome != SessionOutcome.PARTIAL) continue
            val day = Instant.ofEpochMilli(s.startMillis).atZone(zone).toLocalDate()
            val minutes = ((s.endMillis - s.startMillis) / 60_000L).toInt().coerceAtLeast(0)
            out[day] = (out[day] ?: 0) + minutes
        }
        return out
    }

    /**
     * Consecutive days (ending today or yesterday) with at least [dailyMinimum] minutes.
     * Today only counts once it has reached the minimum; an unfinished today does not break the streak.
     */
    fun streak(sessions: List<StudySession>, zone: ZoneId, today: LocalDate, dailyMinimum: Int): Int {
        val perDay = minutesPerDay(sessions, zone)
        var day = if ((perDay[today] ?: 0) >= dailyMinimum) today else today.minusDays(1)
        var n = 0
        while ((perDay[day] ?: 0) >= dailyMinimum) { n++; day = day.minusDays(1) }
        return n
    }

    /** Minutes credited since the Monday of [today]'s week. */
    fun minutesThisWeek(sessions: List<StudySession>, zone: ZoneId, today: LocalDate): Int {
        val monday = today.minusDays((today.dayOfWeek.value - 1).toLong())
        return minutesPerDay(sessions, zone).filterKeys { !it.isBefore(monday) }.values.sum()
    }
}
