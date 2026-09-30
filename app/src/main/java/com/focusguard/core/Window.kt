package com.focusguard.core

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The daily window in which loosening lands (same model as the Windows kit's `state\window.txt`):
 * `HH:mm-HH:mm +HH:mm`, local start/end plus the UTC offset frozen when the commitment started.
 * The offset is frozen on purpose: DISALLOW_CONFIG_DATE_TIME also covers the time zone, but the
 * window must not move even if the zone somehow did.
 *
 * A request made at `t` lands at [lands]: the first window start at least [MIN_AGE] after `t`.
 */
class Window(val startMin: Int, val endMin: Int, val offset: ZoneOffset) {

    init {
        require(startMin in 0 until 1440 && endMin in 1..1440) { "window out of range" }
        require(startMin < endMin) { "window start must precede its end (same day)" }
        require(endMin - startMin <= MAX_LENGTH_MIN) { "window longer than 4 h" }
    }

    private fun local(utc: Instant): LocalDateTime = LocalDateTime.ofInstant(utc, offset)

    fun isOpen(utc: Instant): Boolean {
        val l = local(utc)
        val m = l.hour * 60 + l.minute
        return m >= startMin && m < endMin
    }

    /** First window start strictly after `utc` (or `utc` itself if it is exactly a start). */
    fun nextStart(utc: Instant): Instant {
        val l = local(utc)
        var s = l.toLocalDate().atStartOfDay().plusMinutes(startMin.toLong())
        if (s.isBefore(l)) s = s.plusDays(1)
        return s.toInstant(offset)
    }

    fun lands(requestedUtc: Instant): Instant = nextStart(requestedUtc.plus(MIN_AGE))

    override fun toString(): String =
        "%02d:%02d-%02d:%02d %s".format(startMin / 60, startMin % 60, endMin / 60, endMin % 60, offsetText())

    private fun offsetText(): String {
        val total = offset.totalSeconds
        val sign = if (total < 0) '-' else '+'
        val abs = Math.abs(total) / 60
        return "%c%02d:%02d".format(sign, abs / 60, abs % 60)
    }

    companion object {
        val MIN_AGE: Duration = Duration.ofHours(8)
        const val MAX_LENGTH_MIN = 240
        const val DEFAULT_RANGE = "06:00-08:00"

        private val FORMAT = Regex("""^(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})\s+([+-])(\d{2}):(\d{2})$""")
        private val RANGE = Regex("""^(\d{1,2}):(\d{2})-(\d{1,2}):(\d{2})$""")

        fun parse(text: String): Window {
            val m = FORMAT.matchEntire(text.trim()) ?: throw IllegalArgumentException("window must read like 06:00-08:00 +01:00")
            val g = m.groupValues
            val start = g[1].toInt() * 60 + g[2].toInt()
            val end = g[3].toInt() * 60 + g[4].toInt()
            val offSec = (g[6].toInt() * 3600 + g[7].toInt() * 60) * (if (g[5] == "-") -1 else 1)
            return Window(start, end, ZoneOffset.ofTotalSeconds(offSec))
        }

        /** `06:00-08:00` in the given zone, offset frozen at `now`. */
        fun of(range: String, zone: ZoneId, now: Instant): Window {
            val m = RANGE.matchEntire(range.trim()) ?: throw IllegalArgumentException("range must read like 06:00-08:00")
            val g = m.groupValues
            return Window(g[1].toInt() * 60 + g[2].toInt(), g[3].toInt() * 60 + g[4].toInt(), zone.rules.getOffset(now))
        }
    }
}
