package com.focusguard.net

import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream

/**
 * Memory-compact set of domain names stored as sorted 64-bit FNV-1a hashes
 * (8 bytes per entry; ~500k entries ≈ 4 MB). Lookup is a binary search.
 *
 * False positives are possible in theory (hash collision) but at 2^-64 per
 * pair they are irrelevant for a blocklist. Implemented as a [Set] so it can be
 * plugged straight into [DomainFilter].
 */
class HashedDomainSet private constructor(private val hashes: LongArray) : AbstractSet<String>() {

    override val size: Int get() = hashes.size

    override fun contains(element: String): Boolean {
        val h = fnv1a64(element)
        return hashes.binarySearch(h) >= 0
    }

    /** Iteration is not meaningful (hashes only); provided to satisfy [Set]. */
    override fun iterator(): Iterator<String> = emptyList<String>().iterator()

    fun writeTo(out: OutputStream) {
        DataOutputStream(out.buffered()).use { d ->
            d.writeInt(MAGIC)
            d.writeInt(hashes.size)
            for (h in hashes) d.writeLong(h)
        }
    }

    companion object {
        private const val MAGIC = 0x46474453 // "FGDS"

        fun fromDomains(domains: Iterable<String>): HashedDomainSet {
            val arr = domains.map { fnv1a64(DomainFilter.normalize(it)) }.distinct().toLongArray()
            arr.sort()
            return HashedDomainSet(arr)
        }

        fun readFrom(input: InputStream): HashedDomainSet? = runCatching {
            DataInputStream(input.buffered()).use { d ->
                if (d.readInt() != MAGIC) return null
                val n = d.readInt()
                if (n < 0 || n > 20_000_000) return null
                val arr = LongArray(n) { d.readLong() }
                HashedDomainSet(arr)
            }
        }.getOrNull()

        fun readFrom(file: File): HashedDomainSet? =
            if (file.exists()) file.inputStream().use { readFrom(it) } else null

        fun empty(): HashedDomainSet = HashedDomainSet(LongArray(0))

        /**
         * Parse hosts-file ("0.0.0.0 host") or plain/wildcard ("host", "*.host") formats.
         * Comments (#) and localhost entries are skipped.
         */
        fun parseListLine(line: String): String? {
            var s = line.trim()
            if (s.isEmpty() || s.startsWith("#") || s.startsWith("!")) return null
            val hash = s.indexOf('#')
            if (hash >= 0) s = s.substring(0, hash).trim()
            val parts = s.split(' ', '\t').filter { it.isNotEmpty() }
            var host = when (parts.size) {
                0 -> return null
                1 -> parts[0]
                else -> parts[1]
            }
            if (host.startsWith("*.")) host = host.substring(2)
            if (host.startsWith("||")) host = host.removePrefix("||").removeSuffix("^")
            host = DomainFilter.normalize(host)
            if (host in setOf("localhost", "localhost.localdomain", "local", "broadcasthost", "0.0.0.0", "ip6-localhost")) return null
            if (!host.contains('.')) return null
            if (host.any { !(it.isLetterOrDigit() || it == '.' || it == '-' || it == '_') }) return null
            return host
        }

        fun fnv1a64(s: String): Long {
            var h = -0x340d631b7bdddcdbL // 0xcbf29ce484222325
            for (ch in s) {
                h = h xor ch.code.toLong()
                h *= 0x100000001b3L
            }
            return h
        }
    }
}
