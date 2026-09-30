package com.focusguard.net

import com.focusguard.net.DomainFilter.Decision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AllowOnlyFilterTest {

    private val base = listOf("wikipedia.org", "google.com", "school.example", "bad.example", "okay.example")

    @Test
    fun `unknown names are denied by default, listed names and their subdomains resolve`() {
        val f = DomainFilter(allowOnly = base)
        assertTrue(f.isAllowOnly)
        assertEquals(Decision.Block(DomainFilter.REASON_DEFAULT_DENY), f.decide("some-new-proxy.example"))
        assertEquals(Decision.Block(DomainFilter.REASON_DEFAULT_DENY), f.decide("wikipedia.org.evil.example"))
        assertTrue(f.decide("fr.wikipedia.org") is Decision.Allow)
        assertTrue(f.decide("portal.school.example") is Decision.Allow)
    }

    @Test
    fun `a block rule beats an allow entry, whatever its source`() {
        val f = DomainFilter(
            userBlock = listOf("okay.example"),
            downloaded = setOf("bad.example"),
            allowOnly = base + listOf("youtube.com", "cdn.discordapp.com"),
        )
        assertTrue(f.decide("www.youtube.com") is Decision.Block)          // built-in
        assertEquals(Decision.Block("${DomainFilter.REASON_DOWNLOAD}bad.example"), f.decide("bad.example"))
        assertEquals(Decision.Block("${DomainFilter.REASON_USER}okay.example"), f.decide("www.okay.example"))
        assertTrue(f.decide("xn--pornhub.example") is Decision.Block)     // keyword wins over default deny too
    }

    @Test
    fun `user block beats a built-in allow even when the built-in allow is more specific`() {
        val f = DomainFilter(userBlock = listOf("discordapp.com"), allowOnly = listOf("discordapp.com"))
        assertEquals(Decision.Block("${DomainFilter.REASON_USER}discordapp.com"), f.decide("cdn.discordapp.com"))
    }

    @Test
    fun `unallowed names are blocked, including under an allowed parent`() {
        val f = DomainFilter(allowOnly = base, unallowed = listOf("translate.google.com", "school.example"))
        assertEquals(Decision.Block("${DomainFilter.REASON_UNALLOWED}translate.google.com"), f.decide("translate.google.com"))
        assertTrue(f.decide("docs.google.com") is Decision.Allow)
        assertTrue(f.decide("portal.school.example") is Decision.Block)
    }

    @Test
    fun `safe search rewrite survives allow-only`() {
        val f = DomainFilter(allowOnly = base)
        assertTrue(f.decide("www.google.com") is Decision.Rewrite)
    }

    @Test
    fun `blockedRegardless separates block rules from default deny and unallow`() {
        val f = DomainFilter(userBlock = listOf("okay.example"), downloaded = setOf("bad.example"), allowOnly = base, unallowed = listOf("school.example"))
        assertTrue(f.blockedRegardless("youtube.com"))
        assertTrue(f.blockedRegardless("cdn.bad.example"))
        assertTrue(f.blockedRegardless("okay.example"))
        assertFalse(f.blockedRegardless("brand-new.example"))
        assertFalse(f.blockedRegardless("school.example"))
    }

    @Test
    fun `blocklist mode is unchanged when no allow list is given`() {
        val f = DomainFilter()
        assertFalse(f.isAllowOnly)
        assertTrue(f.decide("some-new-proxy.example") is Decision.Allow)
    }
}
