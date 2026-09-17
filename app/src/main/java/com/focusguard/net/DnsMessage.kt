package com.focusguard.net

import java.nio.ByteBuffer

/**
 * Minimal DNS wire-format handling: enough to read the question of a query and to
 * build a synthetic response. No dependency on Android so it is unit-testable.
 */
data class DnsQuestion(val name: String, val type: Int, val clazz: Int)

class DnsMessage private constructor(
    val id: Int,
    val flags: Int,
    val question: DnsQuestion,
    /** Raw bytes of the whole message, as received. */
    val raw: ByteArray,
    /** Byte offset right after the question section. */
    private val questionEnd: Int,
) {
    val isQuery: Boolean get() = (flags and 0x8000) == 0

    /**
     * Build a response to this query.
     * @param answerA IPv4 to answer for type A questions (null = no data).
     * @param answerAAAA IPv6 for type AAAA questions.
     * @param nxDomain if true, RCODE=3 with no answers regardless of the above.
     */
    fun buildResponse(
        answerA: ByteArray? = null,
        answerAAAA: ByteArray? = null,
        ttlSeconds: Int = 60,
        nxDomain: Boolean = false,
    ): ByteArray {
        val answer: ByteArray? = when {
            nxDomain -> null
            question.type == TYPE_A && answerA != null -> answerA
            question.type == TYPE_AAAA && answerAAAA != null -> answerAAAA
            else -> null
        }
        val buf = ByteBuffer.allocate(questionEnd + 16 + (answer?.size ?: 0))
        buf.putShort(id.toShort())
        // QR=1, opcode copied, AA=0, TC=0, RD copied, RA=1, RCODE
        val rd = flags and 0x0100
        val opcode = flags and 0x7800
        val rcode = if (nxDomain) 3 else 0
        buf.putShort((0x8000 or opcode or rd or 0x0080 or rcode).toShort())
        buf.putShort(1)                         // QDCOUNT
        buf.putShort(if (answer != null) 1 else 0) // ANCOUNT
        buf.putShort(0)                         // NSCOUNT
        buf.putShort(0)                         // ARCOUNT
        buf.put(raw, 12, questionEnd - 12)      // copy question verbatim
        if (answer != null) {
            buf.putShort(0xC00C.toShort())      // pointer to name at offset 12
            buf.putShort(question.type.toShort())
            buf.putShort(question.clazz.toShort())
            buf.putInt(ttlSeconds)
            buf.putShort(answer.size.toShort())
            buf.put(answer)
        }
        return buf.array().copyOf(buf.position())
    }

    companion object {
        const val TYPE_A = 1
        const val TYPE_AAAA = 28
        const val TYPE_HTTPS = 65

        val ZERO_V4 = byteArrayOf(0, 0, 0, 0)
        val ZERO_V6 = ByteArray(16)

        /** Parse a message; returns null if malformed or if it has no question. */
        fun parse(bytes: ByteArray, length: Int = bytes.size): DnsMessage? {
            if (length < 12) return null
            val buf = ByteBuffer.wrap(bytes, 0, length)
            val id = buf.short.toInt() and 0xFFFF
            val flags = buf.short.toInt() and 0xFFFF
            val qd = buf.short.toInt() and 0xFFFF
            buf.short; buf.short; buf.short // AN, NS, AR
            if (qd < 1) return null
            val sb = StringBuilder()
            var pos = 12
            var hops = 0
            while (true) {
                if (pos >= length) return null
                val len = bytes[pos].toInt() and 0xFF
                if (len == 0) { pos++; break }
                if (len and 0xC0 != 0) return null // compression in question: not valid
                if (pos + 1 + len > length) return null
                if (sb.isNotEmpty()) sb.append('.')
                for (i in 1..len) sb.append((bytes[pos + i].toInt() and 0xFF).toChar())
                pos += 1 + len
                if (++hops > 128) return null
            }
            if (pos + 4 > length) return null
            val type = ((bytes[pos].toInt() and 0xFF) shl 8) or (bytes[pos + 1].toInt() and 0xFF)
            val clazz = ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            pos += 4
            return DnsMessage(id, flags, DnsQuestion(sb.toString().lowercase(), type, clazz), bytes.copyOf(length), pos)
        }
    }
}
