package net.prok.proknet.core

import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * ProkNet wire packet, version 4 (v0.5). Pure Kotlin, unit-tested.
 *
 * Layout (big-endian):
 *   0   2  magic "PK"
 *   2   1  version (4)
 *   3   1  type: 1 TEXT (plaintext, legacy), 2 ENVELOPE (E2E encrypted), 3 CHUNK (piece of a large payload)
 *   4  16  origin ID       (who wrote the message; never changes on relay)
 *   20 16  destination ID  (final recipient; never changes on relay)
 *   36 16  last-hop ID     (the phone physically transmitting THIS hop)
 *   52  8  message ID      (random, chosen by the origin; never changes; for CHUNK = transfer ID)
 *   60  8  timestamp, ms since epoch (origin's clock)
 *   68  1  TTL  (max custody transfers allowed)
 *   69  1  hops (custody transfers so far; a relay increments it when forwarding)
 *   70  1  flags (reserved, 0)
 *   71  2  payload length N
 *   73  N  payload (opaque bytes; for TEXT it is UTF-8 text, for ENVELOPE the ciphertext, for CHUNK a chunk record)
 *
 * Max total size is 512 bytes (the BLE attribute limit), so N <= 439.
 * The fields origin, dest, msgId, ts, type never change and are the GCM
 * associated data of an ENVELOPE ([aad]); a relay may change only lastHop and hops.
 * Older versions still decode: v3 (no flags, header 72), v2 (no last hop),
 * v1 (no destination: addressed to whoever receives it).
 */
class Packet(
    val originId: ByteArray,
    val destId: ByteArray,
    val msgId: ByteArray,
    val timestamp: Long,
    val payload: ByteArray,
    val type: Int,
    val ttl: Int = DEFAULT_TTL,
    val hops: Int = 0,
    val lastHopId: ByteArray = ByteArray(ID_LEN),
    val legacy: Boolean = false,
    val wireVersion: Int = VERSION,
) {
    /** Convenience for a plaintext TEXT packet (legacy type, used by tests and v1-v3 compatibility). */
    constructor(
        originId: ByteArray, destId: ByteArray, msgId: ByteArray, timestamp: Long, text: String,
        ttl: Int = DEFAULT_TTL, hops: Int = 0, type: Int = TYPE_TEXT, lastHopId: ByteArray = ByteArray(ID_LEN), legacy: Boolean = false,
    ) : this(originId, destId, msgId, timestamp, text.toByteArray(Charsets.UTF_8), type, ttl, hops, lastHopId, legacy)

    init {
        require(originId.size == ID_LEN) { "origin must be " + ID_LEN + " bytes" }
        require(destId.size == ID_LEN) { "destination must be " + ID_LEN + " bytes" }
        require(msgId.size == MSG_ID_LEN) { "message ID must be " + MSG_ID_LEN + " bytes" }
        require(lastHopId.size == ID_LEN) { "last hop must be " + ID_LEN + " bytes" }
        require(ttl in 0..255 && hops in 0..255) { "ttl/hops out of range" }
        require(type in 1..255) { "type out of range" }
    }

    val originIdHex get() = originId.toHex()
    val originShort get() = originIdHex.substring(0, SHORT_ID_LEN * 2)
    val destIdHex get() = destId.toHex()
    val destShort get() = destIdHex.substring(0, SHORT_ID_LEN * 2)
    val lastHopIdHex get() = lastHopId.toHex()
    val lastHopShort get() = lastHopIdHex.substring(0, SHORT_ID_LEN * 2)
    val hasLastHop get() = lastHopId.any { it.toInt() != 0 }
    val msgIdHex get() = msgId.toHex()
    /** UTF-8 view of the payload; meaningful for TEXT only. */
    val text: String get() = String(payload, Charsets.UTF_8)
    val isEncrypted get() = type == TYPE_ENVELOPE
    val isChunk get() = type == TYPE_CHUNK

    /** The immutable routing header, used as authenticated data for the envelope. */
    fun aad(): ByteArray = ByteBuffer.allocate(ID_LEN * 2 + MSG_ID_LEN + 8 + 1)
        .put(originId).put(destId).put(msgId).putLong(timestamp).put(type.toByte()).array()

    /** True if this packet is addressed to [myId]. A destination whose last 12 bytes are zero matches on the short ID only. */
    fun isFor(myId: ByteArray): Boolean {
        if (legacy) return true
        if (destId.contentEquals(myId)) return true
        var tailZero = true
        for (i in SHORT_ID_LEN until ID_LEN) if (destId[i].toInt() != 0) { tailZero = false; break }
        return tailZero && destId.copyOfRange(0, SHORT_ID_LEN).contentEquals(myId.copyOfRange(0, SHORT_ID_LEN))
    }

    /** The packet as [transmitterId] will put it on the air: same identity and payload, last hop stamped, hops advanced by [extraHops]. */
    fun stamped(transmitterId: ByteArray, extraHops: Int = 0): Packet =
        Packet(originId, destId, msgId, timestamp, payload, type, ttl, hops + extraHops, transmitterId.copyOf())

    fun encode(): ByteArray {
        require(payload.size <= MAX_PAYLOAD_BYTES) { "payload too long: " + payload.size + " > " + MAX_PAYLOAD_BYTES }
        val buf = ByteBuffer.allocate(HEADER_LEN + payload.size)
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
        buf.put(0)
        buf.putShort(payload.size.toShort())
        buf.put(payload)
        return buf.array()
    }

    companion object {
        val MAGIC: ByteArray = byteArrayOf(0x50, 0x4B) // "PK"
        const val VERSION = 4
        const val TYPE_TEXT = 1
        const val TYPE_ENVELOPE = 2
        const val TYPE_CHUNK = 3
        const val ID_LEN = 16
        const val SHORT_ID_LEN = 4
        const val MSG_ID_LEN = 8
        const val HEADER_LEN = 73
        const val HEADER_LEN_V3 = 72
        const val HEADER_LEN_V2 = 56
        const val HEADER_LEN_V1 = 38
        const val MAX_PACKET_BYTES = 512
        const val MAX_PAYLOAD_BYTES = MAX_PACKET_BYTES - HEADER_LEN
        /** Kept for older call sites/tests: a plaintext TEXT packet's text budget. */
        const val MAX_TEXT_BYTES = MAX_PAYLOAD_BYTES
        const val DEFAULT_TTL = 3

        fun newMsgId(): ByteArray = ByteArray(MSG_ID_LEN).also { SecureRandom().nextBytes(it) }

        /** Plaintext text packet (legacy type). */
        fun text(originId: ByteArray, destId: ByteArray, text: String, timestamp: Long = System.currentTimeMillis()): Packet =
            Packet(originId, destId, newMsgId(), timestamp, text)

        /** Build a 16-byte destination from a short ID when the full ID is unknown. */
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
                when (version) {
                    4 -> {
                        if (bytes.size < HEADER_LEN) return null
                        if (type != TYPE_TEXT && type != TYPE_ENVELOPE && type != TYPE_CHUNK) return null
                        val origin = ByteArray(ID_LEN).also { buf.get(it) }
                        val dest = ByteArray(ID_LEN).also { buf.get(it) }
                        val lastHop = ByteArray(ID_LEN).also { buf.get(it) }
                        val msgId = ByteArray(MSG_ID_LEN).also { buf.get(it) }
                        val ts = buf.long
                        val ttl = buf.get().toInt() and 0xFF
                        val hops = buf.get().toInt() and 0xFF
                        buf.get() // flags
                        val n = buf.short.toInt() and 0xFFFF
                        if (n != buf.remaining()) return null
                        val payload = ByteArray(n).also { buf.get(it) }
                        Packet(origin, dest, msgId, ts, payload, type, ttl, hops, lastHop, wireVersion = 4)
                    }
                    3 -> {
                        if (bytes.size < HEADER_LEN_V3 || type != TYPE_TEXT) return null
                        val origin = ByteArray(ID_LEN).also { buf.get(it) }
                        val dest = ByteArray(ID_LEN).also { buf.get(it) }
                        val lastHop = ByteArray(ID_LEN).also { buf.get(it) }
                        val msgId = ByteArray(MSG_ID_LEN).also { buf.get(it) }
                        val ts = buf.long
                        val ttl = buf.get().toInt() and 0xFF
                        val hops = buf.get().toInt() and 0xFF
                        val n = buf.short.toInt() and 0xFFFF
                        if (n != buf.remaining()) return null
                        val payload = ByteArray(n).also { buf.get(it) }
                        Packet(origin, dest, msgId, ts, payload, TYPE_TEXT, ttl, hops, lastHop, wireVersion = 3)
                    }
                    2 -> {
                        if (bytes.size < HEADER_LEN_V2 || type != TYPE_TEXT) return null
                        val origin = ByteArray(ID_LEN).also { buf.get(it) }
                        val dest = ByteArray(ID_LEN).also { buf.get(it) }
                        val msgId = ByteArray(MSG_ID_LEN).also { buf.get(it) }
                        val ts = buf.long
                        val ttl = buf.get().toInt() and 0xFF
                        val hops = buf.get().toInt() and 0xFF
                        val n = buf.short.toInt() and 0xFFFF
                        if (n != buf.remaining()) return null
                        val payload = ByteArray(n).also { buf.get(it) }
                        Packet(origin, dest, msgId, ts, payload, TYPE_TEXT, ttl, hops, wireVersion = 2)
                    }
                    1 -> {
                        if (type != TYPE_TEXT) return null
                        val origin = ByteArray(ID_LEN).also { buf.get(it) }
                        val msgId = ByteArray(MSG_ID_LEN).also { buf.get(it) }
                        val ts = buf.long
                        val n = buf.short.toInt() and 0xFFFF
                        if (n != buf.remaining()) return null
                        val payload = ByteArray(n).also { buf.get(it) }
                        Packet(origin, ByteArray(ID_LEN), msgId, ts, payload, TYPE_TEXT, 0, 0, legacy = true, wireVersion = 1)
                    }
                    else -> null
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
