package net.prok.proknet.core

import java.nio.ByteBuffer

/**
 * Live relay, pure part (v0.9, handshake reworked in v0.9.1). A relay phone B
 * sits between a buyer A and a seller C on two separate authenticated Wi-Fi
 * links:
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
 *   FRAME_RELAY_INFO = [ver 1][role 1][price u16][flags 1][identity record...]
 *
 * v0.9.0 introduced both sides once, unsolicited, at the moment the second
 * link came up. The 3-phone test showed why that is not enough: the buyer
 * taps CONNECT minutes later, when the relay has long finished introducing,
 * and then waits for an introduction that will never be repeated. v0.9.1
 * makes it a request/answer handshake: the buyer ASKS (repeatedly, it is
 * idempotent), the relay always answers (INTRODUCE or NO_UPSTREAM), and each
 * introduced side ACKs so the relay knows the far application processed it
 * (a successful socket write proves nothing).
 *
 * Identity records are self-certifying (id = hash(pub)), so A and C can
 * trust the introduced identity without trusting B. What B could still do
 * is drop or delay traffic, which the usual keepalives detect.
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

    const val ROLE_UPSTREAM_SELLER = 1   // relay -> buyer: "the seller behind me is this, at this price" (record required)
    const val ROLE_DOWNSTREAM_BUYER = 2  // relay -> seller: "a buyer behind me wants a session" (record required)
    const val ROLE_PEER_GONE = 3         // relay -> either: "the other side is gone" (no record)
    const val ROLE_INTRO_REQUEST = 4     // v0.9.1 buyer -> relay: "introduce your seller to me" (record = the asker's own)
    const val ROLE_INTRO_ACK = 5         // v0.9.1 either -> relay: "I received and stored this introduction" (record = the far end's)
    const val ROLE_NO_UPSTREAM = 6       // v0.9.1 relay -> buyer: "I have no seller right now" (no record)
    const val ROLE_LAST = 6

    /** Roles that carry an identity record; the others must not. */
    val ROLES_WITH_RECORD = setOf(ROLE_UPSTREAM_SELLER, ROLE_DOWNSTREAM_BUYER, ROLE_INTRO_REQUEST, ROLE_INTRO_ACK)

    class Info(val role: Int, val pricePerMb: Int, val flags: Int, val record: Wire.IdentityRecord?)

    fun info(role: Int, pricePerMb: Int, flags: Int, record: ByteArray?): ByteArray {
        require(role in 1..ROLE_LAST && pricePerMb in 0..65535)
        val r = record ?: ByteArray(0)
        return ByteBuffer.allocate(5 + r.size).put(VERSION.toByte()).put(role.toByte()).putShort(pricePerMb.toShort()).put(flags.toByte()).put(r).array()
    }

    /** Null if malformed or if the record is not self-consistent (id must derive from the key). */
    fun parseInfo(bytes: ByteArray?): Info? {
        if (bytes == null || bytes.size < 5 || (bytes[0].toInt() and 0xFF) != VERSION) return null
        val role = bytes[1].toInt() and 0xFF
        if (role !in 1..ROLE_LAST) return null
        val price = ((bytes[2].toInt() and 0xFF) shl 8) or (bytes[3].toInt() and 0xFF)
        val flags = bytes[4].toInt() and 0xFF
        val rec = if (bytes.size > 5) (Wire.parseIdentityRecord(bytes.copyOfRange(5, bytes.size)) ?: return null) else null
        if (role in ROLES_WITH_RECORD && rec == null) return null
        return Info(role, price, flags, rec)
    }

    fun roleName(role: Int) = when (role) {
        ROLE_UPSTREAM_SELLER -> "UPSTREAM_SELLER"; ROLE_DOWNSTREAM_BUYER -> "DOWNSTREAM_BUYER"; ROLE_PEER_GONE -> "PEER_GONE"
        ROLE_INTRO_REQUEST -> "INTRO_REQUEST"; ROLE_INTRO_ACK -> "INTRO_ACK"; ROLE_NO_UPSTREAM -> "NO_UPSTREAM"; else -> "role " + role
    }

    // ---- pure decisions (v0.9.1) ----------------------------------------------------------------------

    enum class Side { DOWN, UP }
    fun forwardTo(from: Side): Side = if (from == Side.DOWN) Side.UP else Side.DOWN

    /**
     * Where a sealed frame arriving on [from] from [peer] must go. Null = drop.
     * Depends only on the current session's two peers, never on what was sent
     * before, so a late or repeated frame is treated exactly like the first.
     */
    fun forward(from: Side, peer: String, sessionDown: String?, sessionUp: String?): Side? = when {
        sessionDown == null || sessionUp == null -> null
        from == Side.DOWN && peer == sessionDown -> Side.UP
        from == Side.UP && peer == sessionUp -> Side.DOWN
        else -> null
    }

    /**
     * What the relay answers an INTRO_REQUEST. Idempotent by construction: the
     * answer is a function of the relay's CURRENT state only, so asking again
     * (or late, long after the links came up) always gets a real answer.
     */
    enum class Answer { INTRODUCE, NO_UPSTREAM, NOT_MY_BUYER, NOT_A_RELAY }

    fun onIntroRequest(relayMode: Boolean, downPeer: String?, upPeer: String?, sellerSelling: Boolean, from: String): Answer = when {
        !relayMode -> Answer.NOT_A_RELAY
        downPeer == null || from != downPeer -> Answer.NOT_MY_BUYER
        upPeer == null || !sellerSelling -> Answer.NO_UPSTREAM
        else -> Answer.INTRODUCE
    }

    /**
     * Relay session lifecycle from the two link peers. A session always has
     * both peers; [sessionUp]/[sessionDown] are null when there is none.
     */
    enum class LinkChange { START, RESTART, END, KEEP, IDLE }

    fun onLinks(relayMode: Boolean, sessionUp: String?, sessionDown: String?, up: String?, down: String?): LinkChange {
        val has = sessionUp != null && sessionDown != null
        return when {
            !relayMode -> if (has) LinkChange.END else LinkChange.IDLE
            !has -> if (up != null && down != null) LinkChange.START else LinkChange.IDLE
            up == sessionUp && down == sessionDown -> LinkChange.KEEP
            up != null && down != null -> LinkChange.RESTART
            else -> LinkChange.END
        }
    }

    /** How many times the buyer asks to be introduced, and how long it waits between asks. */
    const val INTRO_ATTEMPTS = 5
    const val INTRO_RETRY_MS = 2500L

    /** The buyer's next step while it waits to be introduced to the seller behind the relay. */
    enum class BuyerStep { ASK, START_CONTRACT, NO_SELLER, GIVE_UP }

    fun buyerStep(introduced: Boolean, refused: Boolean, attempts: Int, maxAttempts: Int = INTRO_ATTEMPTS): BuyerStep = when {
        introduced -> BuyerStep.START_CONTRACT
        refused -> BuyerStep.NO_SELLER
        attempts >= maxAttempts -> BuyerStep.GIVE_UP
        else -> BuyerStep.ASK
    }

    // ---- accounting -----------------------------------------------------------------------------------

    /** One relayed session as the relay sees it: sizes and timing only. */
    class Session(val id: String, val upPeer: String, val downPeer: String, val startedAt: Long) {
        @Volatile var bytesToUp = 0L      // buyer -> seller
        @Volatile var bytesToDown = 0L    // seller -> buyer
        @Volatile var framesToUp = 0L
        @Volatile var framesToDown = 0L
        /** v0.9.1: how many times each side was introduced, and whether it confirmed. */
        @Volatile var introductionsDown = 0
        @Volatile var introductionsUp = 0
        @Volatile var ackedDown = false
        @Volatile var ackedUp = false
        @Volatile var endedAt = 0L
        @Volatile var reason = ""
        val durationMs: Long get() = (if (endedAt > 0) endedAt else System.currentTimeMillis()) - startedAt
        fun end(why: String, now: Long = System.currentTimeMillis()) { if (endedAt == 0L) { endedAt = now; reason = why } }
        fun count(to: Side, bytes: Int) { if (to == Side.UP) { bytesToUp += bytes; framesToUp++ } else { bytesToDown += bytes; framesToDown++ } }
        fun summary(): String = "relay " + id + ": prok-" + downPeer + " -> me -> prok-" + upPeer + ", to seller " + bytesToUp + " B / " + framesToUp + " frames, to buyer " + bytesToDown + " B / " + framesToDown +
            " frames, introductions " + introductionsDown + "/" + introductionsUp + " (buyer ack " + ackedDown + ", seller ack " + ackedUp + "), " + (durationMs / 1000) + " s" + (if (endedAt > 0) ", ended: " + reason else "")
    }
}
