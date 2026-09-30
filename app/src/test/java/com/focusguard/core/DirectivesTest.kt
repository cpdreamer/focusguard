package com.focusguard.core

import com.focusguard.net.DomainFilter
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

class DirectivesTest {

    /** In-memory store: the filter is rebuilt from the same inputs FilterStore uses. */
    private class FakeStore : Directives.Store {
        val base = setOf("wikipedia.org", "google.com", "school.example")
        val userBlock = mutableSetOf<String>()
        val installed = setOf("com.android.chrome", "org.telegram.messenger")
        val blockedApps = mutableSetOf<String>()
        override var rules = RuleBook()
        override val window = Window.parse("06:00-08:00 +01:00")

        fun filter() = DomainFilter(userBlock = userBlock, allowOnly = base + rules.granted, unallowed = rules.unallowed)
        override fun blockedRegardless(host: String) = filter().blockedRegardless(host)
        override suspend fun blockHost(host: String) { userBlock += host }
        override suspend fun blockApp(packageName: String): Boolean {
            if (packageName !in installed) return false
            blockedApps += packageName
            return true
        }
    }

    private val store = FakeStore()
    private val d = Directives(store)
    private val evening = Instant.parse("2026-10-02T18:00:00Z")          // 19:00 local
    private val nextMorning = Instant.parse("2026-10-03T05:00:00Z")      // 06:00 local
    private fun run(line: String, at: Instant = evening) = runBlocking { d.apply(line, at) }
    private fun allowed(host: String) = store.filter().decide(host) is DomainFilter.Decision.Allow

    @Test
    fun `block is immediate, permanent, and beats allow`() {
        assertTrue(allowed("school.example"))
        assertTrue(run("block school.example").ok)
        assertFalse(allowed("school.example"))
        assertFalse(run("unblock school.example").ok)
        assertFalse(run("allow school.example").ok)
        assertFalse(allowed("school.example"))
    }

    @Test
    fun `unallow is immediate and covers subdomains`() {
        assertTrue(allowed("docs.google.com"))
        assertTrue(run("unallow google.com").ok)
        assertFalse(allowed("docs.google.com"))
        assertFalse(allowed("google.com"))
    }

    @Test
    fun `allow waits for the window and lands automatically, never earlier`() {
        val r = run("allow new.example")
        assertTrue(r.message, r.ok)
        assertFalse(allowed("new.example"))
        assertEquals(mapOf("new.example" to nextMorning.toEpochMilli()), store.rules.pending)

        assertTrue(d.promote(nextMorning.minusSeconds(1)).isEmpty())
        assertFalse(allowed("new.example"))

        assertEquals(listOf("new.example allowed"), d.promote(nextMorning))
        assertTrue(allowed("new.example"))
        assertTrue(store.rules.pending.isEmpty())
    }

    @Test
    fun `allow requested after 22 00 lands the morning after`() {
        run("allow late.example", Instant.parse("2026-10-02T21:30:00Z"))   // 22:30 local
        assertEquals(Instant.parse("2026-10-04T05:00:00Z").toEpochMilli(), store.rules.pending.getValue("late.example"))
    }

    @Test
    fun `a block added while an allow is waiting wins at promotion time`() {
        run("allow soon.example")
        run("block soon.example")
        assertTrue(store.rules.pending.isEmpty())                            // block cancels the request
        run("allow other.example")
        store.userBlock += "other.example"                                   // e.g. a downloaded list catches up
        assertEquals(listOf("other.example dropped (block rule)"), d.promote(nextMorning))
        assertFalse(allowed("other.example"))
        assertTrue(store.rules.granted.isEmpty())
    }

    @Test
    fun `re-allowing an unallowed name goes through the window`() {
        run("unallow school.example")
        assertFalse(allowed("school.example"))
        assertTrue(run("allow school.example").ok)
        assertFalse(allowed("school.example"))
        d.promote(nextMorning)
        assertTrue(allowed("school.example"))
        assertFalse("school.example" in store.rules.unallowed)
    }

    @Test
    fun `cancel drops a waiting request, allow refuses garbage and duplicates`() {
        assertTrue(run("allow x.example").ok)
        assertFalse(run("allow x.example").ok)
        assertFalse(run("allow not a host").ok)
        assertFalse(run("allow http://x.example/path").ok)
        assertTrue(run("cancel x.example").ok)
        assertFalse(run("cancel x.example").ok)
        assertTrue(store.rules.pending.isEmpty())
    }

    @Test
    fun `at most eight requests wait at once`() {
        for (i in 1..Directives.MAX_PENDING) assertTrue(run("allow s$i.example").ok)
        assertFalse(run("allow s9.example").ok)
    }

    @Test
    fun `blockapp is immediate and there is no way back`() {
        assertTrue(run("blockapp com.android.chrome").ok)
        assertEquals(setOf("com.android.chrome"), store.blockedApps)
        assertFalse(run("blockapp com.not.installed").ok)
        assertFalse(run("blockapp chrome").ok)
        for (line in listOf("unblockapp com.android.chrome", "allowapp com.android.chrome", "browsers on", "browsers off",
            "mode blocklist", "window 00:00-23:59", "release", "unlock", "extend 1")) {
            assertFalse(line, run(line).ok)
        }
        assertTrue(run("# comment").ok)
        assertTrue(run("").ok)
        assertFalse(run("frobnicate x").ok)
    }
}
