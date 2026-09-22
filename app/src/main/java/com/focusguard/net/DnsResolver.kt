package com.focusguard.net

import android.util.Log
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.ConcurrentHashMap

/**
 * Turns a DNS query into a DNS response, applying the [DomainFilter]:
 *  - Block   -> 0.0.0.0 / :: (or NODATA for other types)
 *  - Rewrite -> A/AAAA of the safe-search host (resolved upstream, cached)
 *  - Allow   -> forwarded to the family-filtering upstream resolvers
 *
 * All sockets used to reach upstreams are passed through [protect] so they bypass the VPN.
 */
class DnsResolver(
    private val filterProvider: () -> DomainFilter,
    private val protect: (DatagramSocket) -> Boolean,
    private val upstreams: List<InetSocketAddress> = DEFAULT_UPSTREAMS,
    private val timeoutMs: Int = 3000,
) {
    /** Small positive cache for forwarded answers to cut latency on repeated lookups. */
    private data class CacheEntry(val response: ByteArray, val expiresAt: Long)
    private val cache = ConcurrentHashMap<String, CacheEntry>()
    private val rewriteCache = ConcurrentHashMap<String, Pair<InetAddress?, Long>>()

    var onDecision: ((name: String, decision: DomainFilter.Decision) -> Unit)? = null

    /** Returns the response bytes, or null if upstream failed (caller drops the packet). */
    fun resolve(query: ByteArray, length: Int): ByteArray? {
        val msg = DnsMessage.parse(query, length) ?: return null
        if (!msg.isQuery) return null
        val q = msg.question
        val decision = filterProvider().decide(q.name)
        onDecision?.invoke(q.name, decision)
        return when (decision) {
            is DomainFilter.Decision.Block -> msg.buildResponse(DnsMessage.ZERO_V4, DnsMessage.ZERO_V6, ttlSeconds = 300)
            is DomainFilter.Decision.Rewrite -> rewrite(msg, decision.target)
            DomainFilter.Decision.Allow -> forward(msg)
        }
    }

    private fun rewrite(msg: DnsMessage, target: String): ByteArray? {
        val q = msg.question
        if (q.type != DnsMessage.TYPE_A && q.type != DnsMessage.TYPE_AAAA) {
            // HTTPS/SVCB records could leak an ECH/alt-svc that bypasses the rewrite: answer empty.
            return msg.buildResponse()
        }
        val addr = resolveTarget(target, q.type) ?: return msg.buildResponse()
        return if (q.type == DnsMessage.TYPE_A) msg.buildResponse(answerA = addr.address, ttlSeconds = 300)
        else msg.buildResponse(answerAAAA = addr.address, ttlSeconds = 300)
    }

    private fun resolveTarget(target: String, type: Int): InetAddress? {
        val key = "$target/$type"
        val now = System.currentTimeMillis()
        rewriteCache[key]?.let { (addr, exp) -> if (exp > now) return addr }
        val query = buildQuery(target, type)
        val resp = sendUpstream(query) ?: return null
        val addr = firstAddress(resp, type)
        rewriteCache[key] = addr to now + 10 * 60_000L
        return addr
    }

    private fun forward(msg: DnsMessage): ByteArray? {
        val key = "${msg.question.name}/${msg.question.type}"
        val now = System.currentTimeMillis()
        cache[key]?.let { e ->
            if (e.expiresAt > now) return e.response.copyOf().also { it[0] = (msg.id shr 8).toByte(); it[1] = msg.id.toByte() }
            cache.remove(key)
        }
        val resp = sendUpstream(msg.raw) ?: return null
        if (cache.size > 4000) cache.clear()
        cache[key] = CacheEntry(resp, now + CACHE_TTL_MS)
        return resp
    }

    private fun sendUpstream(query: ByteArray): ByteArray? {
        for (up in upstreams) {
            var socket: DatagramSocket? = null
            try {
                socket = DatagramSocket()
                if (!protect(socket)) Log.w(TAG, "protect() failed")
                socket.soTimeout = timeoutMs
                socket.send(DatagramPacket(query, query.size, up))
                val buf = ByteArray(4096)
                val p = DatagramPacket(buf, buf.size)
                socket.receive(p)
                return buf.copyOf(p.length)
            } catch (e: SocketTimeoutException) {
                Log.d(TAG, "timeout from $up")
            } catch (e: Exception) {
                Log.w(TAG, "upstream $up failed: ${e.message}")
            } finally {
                socket?.close()
            }
        }
        return null
    }

    companion object {
        private const val TAG = "DnsResolver"
        private const val CACHE_TTL_MS = 60_000L

        /**
         * CleanBrowsing "Family" filter as primary: besides adult content it blocks the proxy /
         * anonymizer / VPN category (probed: 35 of 38 public web proxies incl. trickybird.com,
         * translate.goog, web.archive.org, 12ft.io, hosts no static list carried) and forces
         * SafeSearch. Remote-browser / cloud-PC services are only partly covered there, hence the
         * built-in list. Cloudflare for Families (malware + adult) is the fallback, used only when
         * CleanBrowsing times out or errors: a NXDOMAIN / empty answer is a block, not a failure,
         * and is never retried elsewhere.
         */
        val DEFAULT_UPSTREAM_ADDRESSES: List<InetAddress> = listOf(
            InetAddress.getByAddress(byteArrayOf(185.toByte(), 228.toByte(), 168.toByte(), 168.toByte())),
            InetAddress.getByAddress(byteArrayOf(185.toByte(), 228.toByte(), 169.toByte(), 168.toByte())),
            InetAddress.getByAddress(byteArrayOf(1, 1, 1, 3)),
            InetAddress.getByAddress(byteArrayOf(1, 0, 0, 3)),
        )
        val DEFAULT_UPSTREAMS: List<InetSocketAddress> = DEFAULT_UPSTREAM_ADDRESSES.map { InetSocketAddress(it, 53) }

        /** Build a minimal A/AAAA query for [name]. */
        fun buildQuery(name: String, type: Int, id: Int = (System.nanoTime() and 0xFFFF).toInt()): ByteArray {
            val labels = name.trimEnd('.').split('.')
            val size = 12 + labels.sumOf { it.length + 1 } + 1 + 4
            val out = ByteArray(size)
            out[0] = (id shr 8).toByte(); out[1] = id.toByte()
            out[2] = 0x01 // RD
            out[5] = 1    // QDCOUNT
            var p = 12
            for (l in labels) {
                out[p++] = l.length.toByte()
                for (ch in l) out[p++] = ch.code.toByte()
            }
            out[p++] = 0
            out[p++] = (type shr 8).toByte(); out[p++] = type.toByte()
            out[p++] = 0; out[p] = 1
            return out
        }

        /** Extract the first A/AAAA record address from a response (skips CNAMEs). */
        fun firstAddress(resp: ByteArray, wantType: Int): InetAddress? {
            if (resp.size < 12) return null
            val qd = u16(resp, 4); val an = u16(resp, 6)
            var p = 12
            repeat(qd) { p = skipName(resp, p) ?: return null; p += 4 }
            repeat(an) {
                p = skipName(resp, p) ?: return null
                if (p + 10 > resp.size) return null
                val type = u16(resp, p); val rdlen = u16(resp, p + 8)
                p += 10
                if (p + rdlen > resp.size) return null
                if (type == wantType && (rdlen == 4 || rdlen == 16)) {
                    return runCatching { InetAddress.getByAddress(resp.copyOfRange(p, p + rdlen)) }.getOrNull()
                }
                p += rdlen
            }
            return null
        }

        private fun skipName(b: ByteArray, start: Int): Int? {
            var p = start
            while (true) {
                if (p >= b.size) return null
                val len = b[p].toInt() and 0xFF
                if (len == 0) return p + 1
                if (len and 0xC0 == 0xC0) return p + 2
                p += 1 + len
            }
        }

        private fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    }
}
