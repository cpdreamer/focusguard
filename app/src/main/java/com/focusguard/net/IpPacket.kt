package com.focusguard.net

import java.nio.ByteBuffer

/**
 * Just enough IPv4/IPv6 + UDP parsing to pull a DNS query out of a TUN packet and to
 * build the reply packet with swapped endpoints. Pure Kotlin, unit-tested.
 */
class UdpPacket private constructor(
    val version: Int,
    val srcAddr: ByteArray,
    val dstAddr: ByteArray,
    val srcPort: Int,
    val dstPort: Int,
    val payload: ByteArray,
) {

    /** Build the reply datagram: dst->src, given payload. */
    fun reply(newPayload: ByteArray): ByteArray = when (version) {
        4 -> buildV4(dstAddr, srcAddr, dstPort, srcPort, newPayload)
        else -> buildV6(dstAddr, srcAddr, dstPort, srcPort, newPayload)
    }

    companion object {
        const val PROTO_UDP = 17

        fun parse(buf: ByteArray, length: Int): UdpPacket? {
            if (length < 20) return null
            val version = (buf[0].toInt() and 0xF0) shr 4
            return when (version) {
                4 -> parseV4(buf, length)
                6 -> parseV6(buf, length)
                else -> null
            }
        }

        private fun parseV4(b: ByteArray, len: Int): UdpPacket? {
            val ihl = (b[0].toInt() and 0x0F) * 4
            if (ihl < 20 || len < ihl + 8) return null
            if ((b[9].toInt() and 0xFF) != PROTO_UDP) return null
            val totalLen = u16(b, 2).coerceAtMost(len)
            val src = b.copyOfRange(12, 16)
            val dst = b.copyOfRange(16, 20)
            val sport = u16(b, ihl)
            val dport = u16(b, ihl + 2)
            val udpLen = u16(b, ihl + 4)
            val payloadEnd = minOf(ihl + udpLen, totalLen)
            if (payloadEnd < ihl + 8) return null
            return UdpPacket(4, src, dst, sport, dport, b.copyOfRange(ihl + 8, payloadEnd))
        }

        private fun parseV6(b: ByteArray, len: Int): UdpPacket? {
            if (len < 48) return null
            val next = b[6].toInt() and 0xFF
            if (next != PROTO_UDP) return null // extension headers not supported; DNS never needs them
            val payloadLen = u16(b, 4)
            val src = b.copyOfRange(8, 24)
            val dst = b.copyOfRange(24, 40)
            val sport = u16(b, 40)
            val dport = u16(b, 42)
            val udpLen = u16(b, 44)
            val payloadEnd = minOf(40 + udpLen, 40 + payloadLen, len)
            if (payloadEnd < 48) return null
            return UdpPacket(6, src, dst, sport, dport, b.copyOfRange(48, payloadEnd))
        }

        fun buildV4(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, payload: ByteArray): ByteArray {
            val total = 20 + 8 + payload.size
            val buf = ByteBuffer.allocate(total)
            buf.put(0x45.toByte())          // version 4, IHL 5
            buf.put(0)                      // DSCP/ECN
            buf.putShort(total.toShort())
            buf.putShort(0)                 // identification
            buf.putShort(0x4000.toShort())  // flags: DF
            buf.put(64)                     // TTL
            buf.put(PROTO_UDP.toByte())
            buf.putShort(0)                 // checksum placeholder
            buf.put(src); buf.put(dst)
            val arr = buf.array()
            val ipCk = checksum(arr, 0, 20, 0)
            arr[10] = (ipCk shr 8).toByte(); arr[11] = ipCk.toByte()
            putUdp(buf, arr, 20, src, dst, sport, dport, payload)
            return arr
        }

        fun buildV6(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, payload: ByteArray): ByteArray {
            val udpLen = 8 + payload.size
            val buf = ByteBuffer.allocate(40 + udpLen)
            buf.putInt(0x60000000)          // version 6, no traffic class / flow label
            buf.putShort(udpLen.toShort())
            buf.put(PROTO_UDP.toByte())
            buf.put(64)                     // hop limit
            buf.put(src); buf.put(dst)
            val arr = buf.array()
            putUdp(buf, arr, 40, src, dst, sport, dport, payload)
            return arr
        }

        private fun putUdp(
            buf: ByteBuffer, arr: ByteArray, off: Int, src: ByteArray, dst: ByteArray,
            sport: Int, dport: Int, payload: ByteArray,
        ) {
            val udpLen = 8 + payload.size
            buf.putShort(sport.toShort())
            buf.putShort(dport.toShort())
            buf.putShort(udpLen.toShort())
            buf.putShort(0)
            buf.put(payload)
            // pseudo-header: addresses + protocol + UDP length (same sum for v4 and v6 layouts)
            var sum = 0
            sum = addBytes(sum, src, 0, src.size)
            sum = addBytes(sum, dst, 0, dst.size)
            sum += PROTO_UDP + udpLen
            var ck = checksum(arr, off, udpLen, sum)
            if (ck == 0) ck = 0xFFFF
            arr[off + 6] = (ck shr 8).toByte(); arr[off + 7] = ck.toByte()
        }

        private fun addBytes(start: Int, b: ByteArray, off: Int, len: Int): Int {
            var sum = start
            var i = off
            while (i + 1 < off + len) { sum += u16(b, i); i += 2 }
            if (i < off + len) sum += (b[i].toInt() and 0xFF) shl 8
            return sum
        }

        /** One's complement checksum over [off, off+len) plus an initial partial sum. */
        fun checksum(b: ByteArray, off: Int, len: Int, initial: Int): Int {
            var sum = addBytes(initial, b, off, len)
            while (sum shr 16 != 0) sum = (sum and 0xFFFF) + (sum shr 16)
            return sum.inv() and 0xFFFF
        }

        private fun u16(b: ByteArray, i: Int): Int = ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF)
    }
}
