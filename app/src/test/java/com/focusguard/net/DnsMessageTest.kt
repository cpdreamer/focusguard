package com.focusguard.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class DnsMessageTest {

    /** Hand-built standard query (RD=1) for [name] of [type]. */
    private fun query(name: String, type: Int, id: Int = 0x1234): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) { out.write(v shr 8 and 0xFF); out.write(v and 0xFF) }
        u16(id); u16(0x0100); u16(1); u16(0); u16(0); u16(0)
        for (label in name.split('.')) { out.write(label.length); out.write(label.toByteArray(Charsets.US_ASCII)) }
        out.write(0)
        u16(type); u16(1)
        return out.toByteArray()
    }

    @Test
    fun `parses a standard A query`() {
        val m = DnsMessage.parse(query("www.Example.com", DnsMessage.TYPE_A))
        assertNotNull(m)
        m!!
        assertEquals(0x1234, m.id)
        assertTrue(m.isQuery)
        assertEquals(DnsQuestion("www.example.com", DnsMessage.TYPE_A, 1), m.question)
    }

    @Test
    fun `rejects truncated and malformed packets`() {
        val q = query("example.com", DnsMessage.TYPE_A)
        assertNull(DnsMessage.parse(ByteArray(5)))
        assertNull(DnsMessage.parse(q, q.size - 3))
        assertNull(DnsMessage.parse(q.copyOf(13)))
        val noQuestion = q.copyOf().also { it[5] = 0 }
        assertNull(DnsMessage.parse(noQuestion))
        val compressed = q.copyOf().also { it[12] = 0xC0.toByte() }
        assertNull(DnsMessage.parse(compressed))
    }

    @Test
    fun `A response carries the sinkhole address and mirrors id and question`() {
        val q = DnsMessage.parse(query("blocked.example", DnsMessage.TYPE_A))!!
        val r = q.buildResponse(answerA = DnsMessage.ZERO_V4, answerAAAA = DnsMessage.ZERO_V6, ttlSeconds = 60)
        val parsed = DnsMessage.parse(r)!!
        assertEquals(q.id, parsed.id)
        assertFalse(parsed.isQuery)
        assertEquals(q.question, parsed.question)
        // header: QR=1, RD=1, RA=1, RCODE=0 ; QD=1 AN=1
        assertEquals(0x8180, parsed.flags)
        assertEquals(1, (r[6].toInt() shl 8) or (r[7].toInt() and 0xFF))
        // answer: name pointer, type A, class IN, ttl 60, rdlength 4, 0.0.0.0
        val a = r.copyOfRange(r.size - 16, r.size)
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 60, 0, 4, 0, 0, 0, 0), a)
    }

    @Test
    fun `AAAA response uses the v6 answer and A response does not answer AAAA`() {
        val q6 = DnsMessage.parse(query("blocked.example", DnsMessage.TYPE_AAAA))!!
        val r6 = q6.buildResponse(answerA = DnsMessage.ZERO_V4, answerAAAA = DnsMessage.ZERO_V6)
        assertEquals(1, (r6[6].toInt() shl 8) or (r6[7].toInt() and 0xFF))
        assertEquals(16, r6[r6.size - 17].toInt())

        val onlyV4 = q6.buildResponse(answerA = DnsMessage.ZERO_V4)
        assertEquals(0, (onlyV4[6].toInt() shl 8) or (onlyV4[7].toInt() and 0xFF))
    }

    @Test
    fun `NXDOMAIN response has rcode 3 and no answers`() {
        val q = DnsMessage.parse(query("blocked.example", DnsMessage.TYPE_HTTPS))!!
        val r = q.buildResponse(nxDomain = true)
        val parsed = DnsMessage.parse(r)!!
        assertEquals(3, parsed.flags and 0x000F)
        assertEquals(0, (r[6].toInt() shl 8) or (r[7].toInt() and 0xFF))
        assertEquals(q.question, parsed.question)
    }
}
