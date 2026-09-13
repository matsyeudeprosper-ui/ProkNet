package net.prok.proknet.core

import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * ProkNet wire packet, version 3 (milestone 2C1-hardening). This is the ONLY
 * thing that crosses the air. Pure Kotlin: no Android imports, unit-tested.
 *
 * Layout (big-endian):
 *   0   2  magic "PK"
 *   2   1  version (3)
 *   3   1  type (1 = TEXT)
 *   4  16  origin ID       (who wrote the message; never changes on relay)
 *   20 16  destination ID  (final recipient; never changes on relay)
 *   36 16  last-hop ID     (the phone physically transmitting THIS hop; set by every transmitter)
 *   52  8  message ID      (random, chosen by the origin; never changes)
 *   60  8  timestamp, ms since epoch (origin's clock)
 *   68  1  TTL  (max custody transfers allowed)
 *   69  1  hops (custody transfers so far; a relay increments it when forwarding)
 *   70  2  text length N
 *   72  N  text, UTF-8
 *
 * Max total size is 512 bytes (the BLE attribute limit), so N <= 440.
 * (origin, message ID) is the global identity of a message.
 * Older versions still decode: v2 (no last hop: lastHop = zeros) and
 * v1 (no destination: treated as addressed to the receiver, TTL 0).
 * No encryption yet: that is a later milestone.
 */
class Packet(
    val originId: ByteArray,
    val destId: ByteArray,
    val msgId: ByteArray,
    val timestamp: Long,
    val text: String,
    val ttl: Int = DEFAULT_TTL,
    val hops: Int = 0,
    val type: Int = TYPE_TEXT,
    /** Transmitter of this hop. Zeros when unknown (v2 packets, or not yet stamped). */
    val lastHopId: ByteArray = ByteArray(ID_LEN),
    /** v1 packet (no destination field): it was written directly to us. */
    val legacy: Boolean = false,
    /** Wire version this packet was decoded from (VERSION for locally built packets). */
    val wireVersion: Int = VERSION,
) {
    init {
        require(originId.size == ID_LEN) { "origin must be " + ID_LEN + " bytes" }
        require(destId.size == ID_LEN) { "destination must be " + ID_LEN + " bytes" }
        require(msgId.size == MSG_ID_LEN) { "message ID must be " + MSG_ID_LEN + " bytes" }
        require(lastHopId.size == ID_LEN) { "last hop must be " + ID_LEN + " bytes" }
        require(ttl in 0..255 && hops in 0..255) { "ttl/hops out of range" }
    }

    val originIdHex get() = originId.toHex()
    val originShort get() = originIdHex.substring(0, SHORT_ID_LEN * 2)
    val destIdHex get() = destId.toHex()
    val destShort get() = destIdHex.substring(0, SHORT_ID_LEN * 2)
    val lastHopIdHex get() = lastHopId.toHex()
    val lastHopShort get() = lastHopIdHex.substring(0, SHORT_ID_LEN * 2)
    val hasLastHop get() = lastHopId.any { it.toInt() != 0 }
    val msgIdHex get() = msgId.toHex()

    /** True if this packet is addressed to [myId]. A destination whose last 12 bytes are zero matches on the short ID only. */
    fun isFor(myId: ByteArray): Boolean {
        if (legacy) return true
        if (destId.contentEquals(myId)) return true
        var tailZero = true
        for (i in SHORT_ID_LEN until ID_LEN) if (destId[i].toInt() != 0) { tailZero = false; break }
        return tailZero && destId.copyOfRange(0, SHORT_ID_LEN).contentEquals(myId.copyOfRange(0, SHORT_ID_LEN))
    }

    /** The packet as [transmitterId] will put it on the air: same identity, last hop stamped, hops advanced by [extraHops]. */
    fun stamped(transmitterId: ByteArray, extraHops: Int = 0): Packet =
        Packet(originId, destId, msgId, timestamp, text, ttl, hops + extraHops, type, transmitterId.copyOf())

    fun encode(): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        require(textBytes.size <= MAX_TEXT_BYTES) { "text too long: " + textBytes.size + " > " + MAX_TEXT_BYTES }
        val buf = ByteBuffer.allocate(HEADER_LEN + textBytes.size)
        buf.put(MAGIC)
        buf.put(VERSION.toByte())
        buf.put(type.toByte())
        buf.put(originId)
        buf.put(destId)
        buf.put(lastHopId)
        buf.put(msgId)
        buf.putLong(timestamp)
        buf.put(ttl.toByte())
        buf.put(hops.toByte())
        buf.putShort(textBytes.size.toShort())
        buf.put(textBytes)
        return buf.array()
    }

    companion object {
        val MAGIC: ByteArray = byteArrayOf(0x50, 0x4B) // "PK"
        const val VERSION = 3
        const val TYPE_TEXT = 1
        const val ID_LEN = 16
        const val SHORT_ID_LEN = 4
        const val MSG_ID_LEN = 8
        const val HEADER_LEN = 72
        const val HEADER_LEN_V2 = 56
        const val HEADER_LEN_V1 = 38
        const val MAX_PACKET_BYTES = 512
        const val MAX_TEXT_BYTES = MAX_PACKET_BYTES - HEADER_LEN
        const val DEFAULT_TTL = 3

        fun text(originId: ByteArray, destId: ByteArray, text: String, timestamp: Long = System.currentTimeMillis()): Packet {
            val msgId = ByteArray(MSG_ID_LEN).also { SecureRandom().nextBytes(it) }
            return Packet(originId, destId, msgId, timestamp, text)
        }

        /** Build a 16-byte destination from a short ID when the full ID is unknown (v1 peers). */
        fun destFromShort(shortIdHex: String): ByteArray {
            val d = ByteArray(ID_LEN)
            val s = shortIdHex.hexToBytes()
            System.arraycopy(s, 0, d, 0, minOf(s.size, SHORT_ID_LEN))
            return d
        }

        /** Returns null (never throws) when bytes are not a valid packet. */
        fun decode(bytes: ByteArray?): Packet? {
            if (bytes == null || bytes.size < HEADER_LEN_V1 || bytes.size > MAX_PACKET_BYTES) return null
            if (bytes[0] != MAGIC[0] || bytes[1] != MAGIC[1]) return null
            return try {
                val buf = ByteBuffer.wrap(bytes)
                buf.position(2)
                val version = buf.get().toInt() and 0xFF
                val type = buf.get().toInt() and 0xFF
                if (type != TYPE_TEXT) return null
                when (version) {
                    3 -> {
                        if (bytes.size < HEADER_LEN) return null
                        val origin = ByteArray(ID_LEN).also { buf.get(it) }
                        val dest = ByteArray(ID_LEN).also { buf.get(it) }
                        val lastHop = ByteArray(ID_LEN).also { buf.get(it) }
                        val msgId = ByteArray(MSG_ID_LEN).also { buf.get(it) }
                        val ts = buf.long
                        val ttl = buf.get().toInt() and 0xFF
                        val hops = buf.get().toInt() and 0xFF
                        val n = buf.short.toInt() and 0xFFFF
                        if (n != buf.remaining()) return null
                        val textBytes = ByteArray(n).also { buf.get(it) }
                        Packet(origin, dest, msgId, ts, String(textBytes, Charsets.UTF_8), ttl, hops, type, lastHop, wireVersion = 3)
                    }
                    2 -> {
                        if (bytes.size < HEADER_LEN_V2) return null
                        val origin = ByteArray(ID_LEN).also { buf.get(it) }
                        val dest = ByteArray(ID_LEN).also { buf.get(it) }
                        val msgId = ByteArray(MSG_ID_LEN).also { buf.get(it) }
                        val ts = buf.long
                        val ttl = buf.get().toInt() and 0xFF
                        val hops = buf.get().toInt() and 0xFF
                        val n = buf.short.toInt() and 0xFFFF
                        if (n != buf.remaining()) return null
                        val textBytes = ByteArray(n).also { buf.get(it) }
                        Packet(origin, dest, msgId, ts, String(textBytes, Charsets.UTF_8), ttl, hops, type, wireVersion = 2)
                    }
                    1 -> {
                        val origin = ByteArray(ID_LEN).also { buf.get(it) }
                        val msgId = ByteArray(MSG_ID_LEN).also { buf.get(it) }
                        val ts = buf.long
                        val n = buf.short.toInt() and 0xFFFF
                        if (n != buf.remaining()) return null
                        val textBytes = ByteArray(n).also { buf.get(it) }
                        // v1 has no destination: it was written directly to us.
                        Packet(origin, ByteArray(ID_LEN), msgId, ts, String(textBytes, Charsets.UTF_8), 0, 0, type, legacy = true, wireVersion = 1)
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
