package net.prok.proknet.core

import java.nio.ByteBuffer
import java.security.SecureRandom

/**
 * ProkNet v0.1 wire packet. This is the ONLY thing that crosses the air.
 *
 * Layout (big-endian):
 *   0   2  magic "PK"
 *   2   1  version (1)
 *   3   1  type (1 = TEXT)
 *   4  16  sender ID
 *   20  8  message ID (random)
 *   28  8  timestamp, ms since epoch
 *   36  2  text length N
 *   38  N  text, UTF-8
 *
 * Max total size is 512 bytes (the BLE attribute limit), so N <= 474.
 * No encryption yet: that is a later milestone.
 */
class Packet(
    val senderId: ByteArray,
    val msgId: ByteArray,
    val timestamp: Long,
    val text: String,
    val type: Int = TYPE_TEXT,
) {
    val senderIdHex get() = senderId.toHex()
    val msgIdHex get() = msgId.toHex()

    fun encode(): ByteArray {
        val textBytes = text.toByteArray(Charsets.UTF_8)
        require(textBytes.size <= MAX_TEXT_BYTES) { "text too long: " + textBytes.size + " > " + MAX_TEXT_BYTES }
        val buf = ByteBuffer.allocate(HEADER_LEN + textBytes.size)
        buf.put(MAGIC)
        buf.put(VERSION.toByte())
        buf.put(type.toByte())
        buf.put(senderId)
        buf.put(msgId)
        buf.putLong(timestamp)
        buf.putShort(textBytes.size.toShort())
        buf.put(textBytes)
        return buf.array()
    }

    companion object {
        val MAGIC: ByteArray = byteArrayOf(0x50, 0x4B) // "PK"
        const val VERSION = 1
        const val TYPE_TEXT = 1
        const val HEADER_LEN = 38
        const val MAX_PACKET_BYTES = 512
        const val MAX_TEXT_BYTES = MAX_PACKET_BYTES - HEADER_LEN

        fun text(sender: Identity, text: String): Packet {
            val msgId = ByteArray(8).also { SecureRandom().nextBytes(it) }
            return Packet(sender.idBytes, msgId, System.currentTimeMillis(), text)
        }

        /** Returns null (never throws) when bytes are not a valid packet. */
        fun decode(bytes: ByteArray): Packet? {
            if (bytes.size < HEADER_LEN) return null
            if (bytes[0] != MAGIC[0] || bytes[1] != MAGIC[1]) return null
            val buf = ByteBuffer.wrap(bytes)
            buf.position(2)
            val version = buf.get().toInt() and 0xFF
            if (version != VERSION) return null
            val type = buf.get().toInt() and 0xFF
            val sender = ByteArray(Identity.ID_LEN).also { buf.get(it) }
            val msgId = ByteArray(8).also { buf.get(it) }
            val ts = buf.long
            val n = buf.short.toInt() and 0xFFFF
            if (n > buf.remaining()) return null
            val textBytes = ByteArray(n).also { buf.get(it) }
            return Packet(sender, msgId, ts, String(textBytes, Charsets.UTF_8), type)
        }
    }
}
