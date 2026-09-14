package net.prok.proknet.core

import java.nio.ByteBuffer

/**
 * Live relay, pure part (v0.9). A relay phone B sits between a buyer A and a
 * seller C on two separate authenticated Wi-Fi links:
 *
 *   A ==link A-B==> B ==link B-C==> C -> Internet
 *
 * B forwards FRAME_RELAY link frames from one link to the other without
 * looking inside: the tunnel frame is sealed end to end between A and C with
 * a key only they can derive (static ECDH of their identity keys + HKDF).
 * So the marketplace protocol (contract, session, checkpoints) and the
 * application data run unchanged between A and C; B sees only sizes.
 *
 *   FRAME_RELAY      = [ver 1][origin short id 4][nonce 12][AES-GCM(tunnel frame)]   aad = ver + origin
 *   FRAME_RELAY_INFO = [ver 1][role 1][price u16][flags 1][identity record...]       (B's own statements)
 *
 * Identity records are self-certifying (id = hash(pub)), so A and C can
 * trust the introduced identity without trusting B. What B could still do
 * is drop or delay traffic, which the usual keepalives detect.
 *
 * v0.9 status: IMPLEMENTED, tested on the JVM; hardware topology (STA+AP on
 * B) is the open question the Relay Lab must answer.
 */
object Relay {
    const val VERSION = 1
    const val ORIGIN_LEN = 4
    const val NONCE_LEN = 12
    const val HEADER = 1 + ORIGIN_LEN + NONCE_LEN

    // ---- end-to-end envelope ------------------------------------------------------------------------

    /** Same bytes on both ends whatever the order of the two IDs. */
    fun keyInfo(idA: ByteArray, idB: ByteArray): ByteArray {
        val a = idA.toHex(); val b = idB.toHex()
        return "ProkNet-relay-e2e-1".toByteArray(Charsets.UTF_8) + (if (a < b) idA + idB else idB + idA)
    }

    private fun aad(originShort: ByteArray): ByteArray = byteArrayOf(VERSION.toByte()) + originShort

    fun seal(key: ByteArray, originShortHex: String, tunnelFrame: ByteArray, nonce: ByteArray = Crypto.randomBytes(NONCE_LEN)): ByteArray {
        val origin = originShortHex.hexToBytes()
        require(origin.size == ORIGIN_LEN && nonce.size == NONCE_LEN)
        val ct = Crypto.gcmSeal(key, nonce, aad(origin), tunnelFrame)
        return ByteBuffer.allocate(HEADER + ct.size).put(VERSION.toByte()).put(origin).put(nonce).put(ct).array()
    }

    class Sealed(val originShort: String, val nonce: ByteArray, val ciphertext: ByteArray)

    /** Header only, no decryption: who sent it. Null if malformed. */
    fun peek(payload: ByteArray?): Sealed? {
        if (payload == null || payload.size < HEADER + Crypto.TAG_LEN) return null
        if ((payload[0].toInt() and 0xFF) != VERSION) return null
        return Sealed(payload.copyOfRange(1, 1 + ORIGIN_LEN).toHex(), payload.copyOfRange(1 + ORIGIN_LEN, HEADER), payload.copyOfRange(HEADER, payload.size))
    }

    /** The tunnel frame bytes, or null (wrong key, tampered, malformed). */
    fun open(key: ByteArray, payload: ByteArray?): ByteArray? {
        val s = peek(payload) ?: return null
        return Crypto.gcmOpen(key, s.nonce, aad(s.originShort.hexToBytes()), s.ciphertext)
    }

    // ---- introductions ------------------------------------------------------------------------------

    const val ROLE_UPSTREAM_SELLER = 1   // relay -> buyer: "the seller behind me is this, at this price"
    const val ROLE_DOWNSTREAM_BUYER = 2  // relay -> seller: "a buyer behind me wants a session"
    const val ROLE_PEER_GONE = 3         // relay -> either: "the other side is gone"

    class Info(val role: Int, val pricePerMb: Int, val flags: Int, val record: Wire.IdentityRecord?)

    fun info(role: Int, pricePerMb: Int, flags: Int, record: ByteArray?): ByteArray {
        require(role in 1..3 && pricePerMb in 0..65535)
        val r = record ?: ByteArray(0)
        return ByteBuffer.allocate(5 + r.size).put(VERSION.toByte()).put(role.toByte()).putShort(pricePerMb.toShort()).put(flags.toByte()).put(r).array()
    }

    /** Null if malformed or if the record is not self-consistent (id must derive from the key). */
    fun parseInfo(bytes: ByteArray?): Info? {
        if (bytes == null || bytes.size < 5 || (bytes[0].toInt() and 0xFF) != VERSION) return null
        val role = bytes[1].toInt() and 0xFF
        if (role !in 1..3) return null
        val price = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val flags = bytes[4].toInt() and 0xFF
        val rec = if (bytes.size > 5) (Wire.parseIdentityRecord(bytes.copyOfRange(5, bytes.size)) ?: return null) else null
        if (role != ROLE_PEER_GONE && rec == null) return null
        return Info(role, price, flags, rec)
    }

    fun roleName(role: Int) = when (role) { ROLE_UPSTREAM_SELLER -> "UPSTREAM_SELLER"; ROLE_DOWNSTREAM_BUYER -> "DOWNSTREAM_BUYER"; ROLE_PEER_GONE -> "PEER_GONE"; else -> "role " + role }

    // ---- forwarding decision and accounting -------------------------------------------------------

    enum class Side { DOWN, UP }
    fun forwardTo(from: Side): Side = if (from == Side.DOWN) Side.UP else Side.DOWN

    /** One relayed session as the relay sees it: sizes and timing only. */
    class Session(val id: String, val upPeer: String, val downPeer: String, val startedAt: Long) {
        @Volatile var bytesToUp = 0L      // buyer -> seller
        @Volatile var bytesToDown = 0L    // seller -> buyer
        @Volatile var framesToUp = 0L
        @Volatile var framesToDown = 0L
        @Volatile var endedAt = 0L
        @Volatile var reason = ""
        val durationMs: Long get() = (if (endedAt > 0) endedAt else System.currentTimeMillis()) - startedAt
        fun end(why: String, now: Long = System.currentTimeMillis()) { if (endedAt == 0L) { endedAt = now; reason = why } }
        fun count(to: Side, bytes: Int) { if (to == Side.UP) { bytesToUp += bytes; framesToUp++ } else { bytesToDown += bytes; framesToDown++ } }
        fun summary(): String = "relay " + id + ": prok-" + downPeer + " -> me -> prok-" + upPeer + ", to seller " + bytesToUp + " B / " + framesToUp + " frames, to buyer " + bytesToDown + " B / " + framesToDown +
            " frames, " + (durationMs / 1000) + " s" + (if (endedAt > 0) ", ended: " + reason else "")
    }
}
