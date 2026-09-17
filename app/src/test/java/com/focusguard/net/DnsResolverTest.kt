package com.focusguard.net

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Exercises the resolver against a fake upstream on localhost that answers every A query with
 * 93.184.216.34 and every AAAA query with 2606:2800:220:1:248:1893:25c8:1946.
 */
class DnsResolverTest {

    private val v4 = byteArrayOf(93, 184.toByte(), 216.toByte(), 34)
    private val v6 = InetAddress.getByName("2606:2800:220:1:248:1893:25c8:1946").address

    private lateinit var server: DatagramSocket
    private val upstreamQueries = AtomicInteger()
    private val seenNames: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val decisions = mutableListOf<Pair<String, DomainFilter.Decision>>()
    private var protectCalls = 0
    private lateinit var resolver: DnsResolver

    @Before
    fun setUp() {
        server = DatagramSocket(0, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            val buf = ByteArray(1500)
            while (!server.isClosed) {
                val p = DatagramPacket(buf, buf.size)
                try { server.receive(p) } catch (_: Exception) { return@thread }
                val q = DnsMessage.parse(buf, p.length) ?: continue
                upstreamQueries.incrementAndGet()
                seenNames += q.question.name
                val resp = q.buildResponse(answerA = v4, answerAAAA = v6, ttlSeconds = 30)
                server.send(DatagramPacket(resp, resp.size, p.socketAddress))
            }
        }
        resolver = DnsResolver(
            filterProvider = { DomainFilter() },
            protect = { protectCalls++; true },
            upstreams = listOf(InetSocketAddress(InetAddress.getLoopbackAddress(), server.localPort)),
            timeoutMs = 1000,
        ).also { it.onDecision = { n, d -> decisions += n to d } }
    }

    @After
    fun tearDown() { server.close() }

    private fun ask(name: String, type: Int, id: Int = 1): ByteArray? {
        val q = DnsResolver.buildQuery(name, type, id)
        return resolver.resolve(q, q.size)
    }

    @Test
    fun `allowed names are forwarded upstream through a protected socket`() {
        val resp = ask("fr.wikipedia.org", DnsMessage.TYPE_A, id = 0x4242)
        assertNotNull(resp)
        val parsed = DnsMessage.parse(resp!!)!!
        assertEquals(0x4242, parsed.id)
        assertEquals("fr.wikipedia.org", parsed.question.name)
        assertArrayEquals(v4, DnsResolver.firstAddress(resp, DnsMessage.TYPE_A)!!.address)
        assertEquals(listOf("fr.wikipedia.org"), seenNames)
        assertEquals(1, protectCalls)
        assertTrue(decisions.single().second is DomainFilter.Decision.Allow)
    }

    @Test
    fun `forwarded answers are cached and the cached copy carries the new query id`() {
        ask("codeforces.com", DnsMessage.TYPE_A, id = 1)
        val second = ask("codeforces.com", DnsMessage.TYPE_A, id = 2)!!
        assertEquals(1, upstreamQueries.get())
        assertEquals(2, DnsMessage.parse(second)!!.id)
        // a different type is a different cache key
        ask("codeforces.com", DnsMessage.TYPE_AAAA, id = 3)
        assertEquals(2, upstreamQueries.get())
    }

    @Test
    fun `blocked names are answered locally with the sinkhole address and never forwarded`() {
        val resp = ask("www.youtube.com", DnsMessage.TYPE_A, id = 7)!!
        val parsed = DnsMessage.parse(resp)!!
        assertEquals(7, parsed.id)
        assertEquals("www.youtube.com", parsed.question.name)
        assertEquals(InetAddress.getByAddress(DnsMessage.ZERO_V4), DnsResolver.firstAddress(resp, DnsMessage.TYPE_A))
        assertEquals(0, upstreamQueries.get())
        assertTrue(decisions.single().second is DomainFilter.Decision.Block)
    }

    @Test
    fun `blocked AAAA gets the v6 sinkhole and HTTPS records get an empty answer`() {
        val r6 = ask("tiktok.com", DnsMessage.TYPE_AAAA)!!
        assertEquals(InetAddress.getByAddress(DnsMessage.ZERO_V6), DnsResolver.firstAddress(r6, DnsMessage.TYPE_AAAA))
        val rh = ask("tiktok.com", DnsMessage.TYPE_HTTPS)!!
        assertNull(DnsResolver.firstAddress(rh, DnsMessage.TYPE_HTTPS))
        assertEquals(0, (rh[6].toInt() shl 8) or (rh[7].toInt() and 0xFF))
        assertEquals(0, upstreamQueries.get())
    }

    @Test
    fun `safe-search hosts are rewritten to the address of the safe host`() {
        val resp = ask("www.google.com", DnsMessage.TYPE_A)!!
        val parsed = DnsMessage.parse(resp)!!
        assertEquals("www.google.com", parsed.question.name)
        assertArrayEquals(v4, DnsResolver.firstAddress(resp, DnsMessage.TYPE_A)!!.address)
        // the upstream was asked for the safe host, not the original one
        assertEquals(listOf("forcesafesearch.google.com"), seenNames)

        // second lookup uses the rewrite cache
        ask("google.fr", DnsMessage.TYPE_A)
        assertEquals(1, upstreamQueries.get())

        // HTTPS/SVCB for a rewritten host is answered empty so no alt-svc can bypass the rewrite
        val rh = ask("www.google.com", DnsMessage.TYPE_HTTPS)!!
        assertEquals(0, (rh[6].toInt() shl 8) or (rh[7].toInt() and 0xFF))
        assertEquals(1, upstreamQueries.get())
    }

    @Test
    fun `upstream failure yields null so the caller drops the packet`() {
        val port = server.localPort
        server.close()
        val dead = DnsResolver(
            filterProvider = { DomainFilter() },
            protect = { true },
            upstreams = listOf(InetSocketAddress(InetAddress.getLoopbackAddress(), port)),
            timeoutMs = 200,
        )
        val q = DnsResolver.buildQuery("fr.wikipedia.org", DnsMessage.TYPE_A)
        assertNull(dead.resolve(q, q.size))
        // blocked names still work with no upstream at all
        val b = DnsResolver.buildQuery("www.youtube.com", DnsMessage.TYPE_A)
        assertNotNull(dead.resolve(b, b.size))
    }

    @Test
    fun `non-queries and garbage are dropped`() {
        assertNull(resolver.resolve(ByteArray(3), 3))
        val q = DnsResolver.buildQuery("example.com", DnsMessage.TYPE_A)
        val response = DnsMessage.parse(q)!!.buildResponse()
        assertNull(resolver.resolve(response, response.size))
        assertEquals(0, upstreamQueries.get())
    }

    @Test
    fun `buildQuery round-trips through parse`() {
        val q = DnsResolver.buildQuery("a.b.example.org.", DnsMessage.TYPE_AAAA, id = 0xBEEF)
        val m = DnsMessage.parse(q)!!
        assertEquals(0xBEEF, m.id)
        assertTrue(m.isQuery)
        assertEquals(DnsQuestion("a.b.example.org", DnsMessage.TYPE_AAAA, 1), m.question)
    }

    @Test
    fun `firstAddress skips CNAME records and finds the wanted type`() {
        val q = DnsMessage.parse(DnsResolver.buildQuery("example.com", DnsMessage.TYPE_A))!!
        val head = q.buildResponse()
        val cnameTarget = byteArrayOf(1, 'x'.code.toByte(), 0xC0.toByte(), 0x0C)
        val cname = byteArrayOf(0xC0.toByte(), 0x0C, 0, 5, 0, 1, 0, 0, 0, 10, 0, cnameTarget.size.toByte()) + cnameTarget
        val a = byteArrayOf(0xC0.toByte(), 0x0C, 0, 1, 0, 1, 0, 0, 0, 10, 0, 4, 1, 2, 3, 4)
        val resp = head + cname + a
        resp[6] = 0; resp[7] = 2
        assertEquals(InetAddress.getByAddress(byteArrayOf(1, 2, 3, 4)), DnsResolver.firstAddress(resp, DnsMessage.TYPE_A))
        assertNull(DnsResolver.firstAddress(resp, DnsMessage.TYPE_AAAA))
        assertNull(DnsResolver.firstAddress(resp.copyOf(20), DnsMessage.TYPE_A))
    }
}
