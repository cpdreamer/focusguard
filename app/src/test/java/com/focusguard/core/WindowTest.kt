package com.focusguard.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

class WindowTest {

    private val w = Window.parse("06:00-08:00 +01:00")
    private fun t(s: String) = Instant.parse(s)

    @Test
    fun `parse and print round-trip`() {
        assertEquals("06:00-08:00 +01:00", w.toString())
        assertEquals("22:30-23:00 -04:30", Window.parse("22:30-23:00 -04:30").toString())
        assertEquals(ZoneOffset.ofHours(1), w.offset)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `window longer than four hours is refused`() { Window.parse("06:00-11:00 +00:00") }

    @Test(expected = IllegalArgumentException::class)
    fun `window crossing midnight is refused`() { Window.parse("23:00-01:00 +00:00") }

    @Test(expected = IllegalArgumentException::class)
    fun `garbage is refused`() { Window.parse("morning") }

    @Test
    fun `isOpen uses the frozen offset, start inclusive, end exclusive`() {
        assertFalse(w.isOpen(t("2026-10-03T04:59:00Z")))   // 05:59 local
        assertTrue(w.isOpen(t("2026-10-03T05:00:00Z")))    // 06:00
        assertTrue(w.isOpen(t("2026-10-03T06:59:59Z")))
        assertFalse(w.isOpen(t("2026-10-03T07:00:00Z")))   // 08:00
        assertFalse(w.isOpen(t("2026-10-03T18:00:00Z")))
    }

    @Test
    fun `nextStart is the coming 06 00 local`() {
        assertEquals(t("2026-10-03T05:00:00Z"), w.nextStart(t("2026-10-02T18:00:00Z")))
        assertEquals(t("2026-10-03T05:00:00Z"), w.nextStart(t("2026-10-03T05:00:00Z")))   // exactly at start: now
        assertEquals(t("2026-10-04T05:00:00Z"), w.nextStart(t("2026-10-03T05:00:01Z")))
        assertEquals(t("2026-10-04T05:00:00Z"), w.nextStart(t("2026-10-03T06:30:00Z")))   // inside the window: tomorrow
    }

    @Test
    fun `a request lands at the first window start at least 8 h later`() {
        assertEquals(t("2026-10-03T05:00:00Z"), w.lands(t("2026-10-02T18:00:00Z")))  // 19:00 -> next morning
        assertEquals(t("2026-10-03T05:00:00Z"), w.lands(t("2026-10-02T21:00:00Z")))  // 22:00 exactly -> next morning
        assertEquals(t("2026-10-04T05:00:00Z"), w.lands(t("2026-10-02T21:00:01Z")))  // 22:00:01 -> the morning after
        assertEquals(t("2026-10-04T05:00:00Z"), w.lands(t("2026-10-03T02:00:00Z")))  // 03:00 -> not the same morning
        assertEquals(t("2026-10-04T05:00:00Z"), w.lands(t("2026-10-03T05:30:00Z")))  // inside the window -> tomorrow
    }

    @Test
    fun `of freezes the zone offset at the given instant`() {
        val paris = ZoneId.of("Europe/Paris")
        assertEquals("06:00-08:00 +02:00", Window.of("06:00-08:00", paris, t("2026-07-01T12:00:00Z")).toString())
        assertEquals("06:00-08:00 +01:00", Window.of("06:00-08:00", paris, t("2026-12-01T12:00:00Z")).toString())
        assertEquals("06:00-08:00 +01:00", Window.of(Window.DEFAULT_RANGE, ZoneId.of("Africa/Casablanca"), t("2026-10-02T16:00:00Z")).toString())
    }
}
