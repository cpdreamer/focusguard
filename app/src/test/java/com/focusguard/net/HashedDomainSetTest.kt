package com.focusguard.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class HashedDomainSetTest {

    @Test
    fun `contains is exact for members and rejects others`() {
        val set = HashedDomainSet.fromDomains(listOf("Bad.Example", "worse.example.", "bad.example"))
        assertEquals(2, set.size)
        assertTrue("bad.example" in set)
        assertTrue("worse.example" in set)
        assertFalse("good.example" in set)
        assertFalse("sub.bad.example" in set) // parent matching is DomainFilter's job
    }

    @Test
    fun `serialisation round-trips`() {
        val set = HashedDomainSet.fromDomains((1..1000).map { "host$it.example" })
        val out = ByteArrayOutputStream()
        set.writeTo(out)
        val back = HashedDomainSet.readFrom(ByteArrayInputStream(out.toByteArray()))!!
        assertEquals(1000, back.size)
        assertTrue("host500.example" in back)
        assertFalse("host1001.example" in back)
    }

    @Test
    fun `corrupt files are rejected`() {
        assertNull(HashedDomainSet.readFrom(ByteArrayInputStream(byteArrayOf(1, 2, 3))))
        assertNull(HashedDomainSet.readFrom(ByteArrayInputStream(ByteArray(8))))
    }

    @Test
    fun `works as the downloaded set of a DomainFilter`() {
        val set = HashedDomainSet.fromDomains(listOf("bad.example"))
        val f = DomainFilter(downloaded = set)
        assertTrue(f.decide("cdn.bad.example") is DomainFilter.Decision.Block)
        assertTrue(f.decide("fine.example") is DomainFilter.Decision.Allow)
    }

    @Test
    fun `parses hosts, plain, wildcard and adblock formats`() {
        assertEquals("bad.example", HashedDomainSet.parseListLine("0.0.0.0 bad.example"))
        assertEquals("bad.example", HashedDomainSet.parseListLine("127.0.0.1\tBad.Example  # comment"))
        assertEquals("bad.example", HashedDomainSet.parseListLine("bad.example"))
        assertEquals("bad.example", HashedDomainSet.parseListLine("*.bad.example"))
        assertEquals("bad.example", HashedDomainSet.parseListLine("||bad.example^"))
    }

    @Test
    fun `skips comments, localhost entries and junk`() {
        for (line in listOf("", "   ", "# a comment", "! adblock comment", "127.0.0.1 localhost", "::1 ip6-localhost",
            "0.0.0.0 0.0.0.0", "nodots", "bad domain.example/path", "0.0.0.0 bad.exa\$mple")) {
            assertNull(line, HashedDomainSet.parseListLine(line))
        }
    }
}
