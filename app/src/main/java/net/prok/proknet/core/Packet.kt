package net.prok.proknet.core

import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * ProkNet wire packet, version 2 (milestone 2C1). This is the ONLY thing that
 * crosses the air.
 *
 * Layout (big-endian):
 *   0   2  magic "PK"
 *   2   1  version (2)
 *   3   1  type (1 = TEXT)
 *   4  16  origin ID      (who wrote the message; never changes on relay)
 *   20 16  destination ID (final recipient; never changes on relay)
 *   36  8  message ID     (random, chosen by the origin; never changes)
 *   44  8  timestamp, ms since epoch (origin's clock)
 *   52  1  TTL  (max hops allowed)
 *   53  1  hops (how many custody transfers so far; a relay increments it)
 *   54  2  text length N
 *   56  N  text, UTF-8
 *
 * Max total size is 512 bytes (the BLE attribute limit), so N <= 456.
 * (origin, message ID) is the global identity of a message.
 * Version 1 packets (v0.1-v0.3, no destination) still decode: they are
 * treated as addressed to whoever receives them, TTL 0, hops 0.
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
    /** v1 packet (no destination field): it was written directly to us. */
    val legacy: Boolean = false,
) {
    val originIdHex get() = originId.toHex()
    val originShort get() = originIdHex.substring(0, Identity.SHORT_ID_LEN * 2)
    val destIdHex get() = destId.toHex()
    val destShort get() = destIdHex.substring(0, Identity.SHORT_ID_LEN * 2)
    val msgIdHex get() = msgId.toHex()

    /** True if this packet is addressed to [me]. A destination whose last 12 bytes are zero matches on the short ID only. */
    fun isFor(me: Identity): Boolean {
        if (legacy) return true
        if (destId.contentEquals(me.idBytes)) return true
        var tailZero = true
        for (i in Identity.SHORT_ID_LEN until Identity.ID_LEN) if (destId[i].toInt() != 0) { tailZero = false; break }
        return tailZero && destId.copyOfRange(0, Identity.SHORT_ID_LEN).contentEquals(me.shortIdBytes)
    }

    /** Same message, one more custody transfer. */
    fun nextHop(): Packet = Packet(originId, destId, msgId, timestamp, text, ttl, hops + 1, type)

    fun encode(): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        require(textBytes.size <= MAX_TEXT_BYTES) { "text too long: " + textBytes.size + " > " + MAX_TEXT_BYTES }
        val buf = ByteBuffer.allocate(HEADER_LEN + textBytes.size)
        buf.put(MAGIC)
        buf.put(VERSION.toByte())
        buf.put(type.toByte())
        buf.put(originId)
        buf.put(destId)
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
        const val VERSION = 2
        const val TYPE_TEXT = 1
        const val HEADER_LEN = 56
        const val HEADER_LEN_V1 = 38
        const val MAX_PACKET_BYTES = 512
        const val MAX_TEXT_BYTES = MAX_PACKET_BYTES - HEADER_LEN
        const val DEFAULT_TTL = 3

        fun text(origin: Identity, destId: ByteArray, text: String): Packet {
            val msgId = ByteArray(8).also { SecureRandom().nextBytes(it) }
            return Packet(origin.idBytes, destId, msgId, System.currentTimeMillis(), text)
        }

        /** Build a 16-byte destination from a short ID when the full ID is unknown (v1 peers). */
        fun destFromShort(shortIdHex: String): ByteArray {
            val d = ByteArray(Identity.ID_LEN)
            val s = shortIdHex.hexToBytes()
            System.arraycopy(s, 0, d, 0, minOf(s.size, Identity.SHORT_ID_LEN))
            return d
        }

        /** Returns null (never throws) when bytes are not a valid packet. */
        fun decode(bytes: ByteArray): Packet? {
            if (bytes.size < HEADER_LEN_V1) return null
            if (bytes[0] != MAGIC[0] || bytes[1] != MAGIC[1]) return null
            val buf = ByteBuffer.wrap(bytes)
            buf.position(2)
            val version = buf.get().toInt() and 0xFF
            val type = buf.get().toInt() and 0xFF
            return when (version) {
                2 -> {
                    if (bytes.size < HEADER_LEN) return null
                    val origin = ByteArray(Identity.ID_LEN).also { buf.get(it) }
                    val dest = ByteArray(Identity.ID_LEN).also { buf.get(it) }
                    val msgId = ByteArray(8).also { buf.get(it) }
                    val ts = buf.long
                    val ttl = buf.get().toInt() and 0xFF
                    val hops = buf.get().toInt() and 0xFF
                    val n = buf.short.toInt() and 0xFFFF
                    if (n > buf.remaining()) return null
                    val textBytes = ByteArray(n).also { buf.get(it) }
                    Packet(origin, dest, msgId, ts, String(textBytes, Charsets.UTF_8), ttl, hops, type)
                }
                1 -> {
                    val origin = ByteArray(Identity.ID_LEN).also { buf.get(it) }
                    val msgId = ByteArray(8).also { buf.get(it) }
                    val ts = buf.long
                    val n = buf.short.toInt() and 0xFFFF
                    if (n > buf.remaining()) return null
                    val textBytes = ByteArray(n).also { buf.get(it) }
                    // v1 has no destination: it was written directly to us.
                    Packet(origin, ByteArray(Identity.ID_LEN), msgId, ts, String(textBytes, Charsets.UTF_8), 0, 0, type, legacy = true)
                }
                else -> null
            }
        }
    }
}
