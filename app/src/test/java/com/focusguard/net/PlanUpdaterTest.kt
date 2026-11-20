package com.focusguard.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class PlanUpdaterTest {
    private val text = """
        # comment
        2026-11-21 08:30 | Maths: ex 3 | Open the sheet, read the statement
        2026-11-21 07:00 | Physics fiche | Reread chapter 4 definitions
        garbage line
        2026-11-21 10:00 |  | no title
        2026-11-21 11:00 | No action
    """.trimIndent()

    @Test fun parsesSortsAndSkipsBadLines() {
        val e = PlanUpdater.parse(text)
        assertEquals(listOf("Physics fiche", "Maths: ex 3", "No action"), e.map { it.title })
        assertEquals("Reread chapter 4 definitions", e[0].action)
        assertEquals("", e[2].action)
        assertEquals("2026-11-21 08:30|Maths: ex 3", e[1].key)
    }

    @Test fun nextSkipsShownAndStale() {
        val e = PlanUpdater.parse(text)
        val now = LocalDateTime.of(2026, 11, 21, 6, 0)
        assertEquals("Physics fiche", PlanUpdater.next(e, emptySet(), now)?.title)
        assertEquals("Maths: ex 3", PlanUpdater.next(e, setOf(e[0].key), now)?.title)
        assertEquals("No action", PlanUpdater.next(e, emptySet(), LocalDateTime.of(2026, 11, 21, 16, 0))?.title)
        assertNull(PlanUpdater.next(e, emptySet(), LocalDateTime.of(2026, 11, 21, 18, 0)))
    }
}
