package net.prok.proknet.core

import java.nio.ByteBuffer

/**
 * Internet tunnel protocol (v0.6), pure part. Runs INSIDE the authenticated
 * Wi-Fi TCP link as link frame type FRAME_TUNNEL:
 *
 *   link frame: [u32 len][FRAME_TUNNEL][tunnel type 1][stream id u32][data]
 *
 * Streams are long-lived and multiplexed; there are no per-frame receipts.
 * The link's own TCP provides ordering, reliability and backpressure.
 */
object Tunnel {
    const val T_SESSION_START = 1   // buyer -> provider: [version 1][buyer id 16]
    const val T_SESSION_OK = 2      // provider -> buyer: [version 1][provider id 16][upstream type 1][validated 1]
    const val T_SESSION_END = 3     // either: [reason utf8]
    const val T_OPEN_TCP = 4        // buyer -> provider, new stream id: [port u16][hostLen u8][host]
    const val T_TCP_OPEN_OK = 5     // provider -> buyer: stream connected
    const val T_TCP_DATA = 6        // either: raw bytes
    const val T_TCP_CLOSE = 7       // either: stream finished (half-close from that side)
    const val T_DNS_REQUEST = 8     // buyer -> provider, stream id = query id: raw DNS message
    const val T_DNS_RESPONSE = 9    // provider -> buyer, same id: raw DNS message
    const val T_ERROR = 10          // either: [code 1][message utf8]; stream id 0 = session-level
    const val T_KEEPALIVE = 11      // either: [seq u32]; answered with the same
    const val T_UPSTREAM_STATE = 12 // provider -> buyer: [available 1][type 1][validated 1]
    // v0.7 marketplace
    // v0.14.1: the contract body is VERSIONED, not a fixed 62 bytes. Its first byte is the
    // version and the version determines the length (Market.Contract.bodyLenFor). Parse it with
    // Market.decodeSignedContract, never with a hardcoded length.
    const val T_CONTRACT_PROPOSE = 13 // buyer -> seller: [versioned contract][sigLen 1][buyer sig]
    const val T_CONTRACT_ACCEPT = 14  // seller -> buyer: [contract hash 32][sigLen 1][seller sig]
    const val T_CONTRACT_REJECT = 15  // seller -> buyer: [reason utf8]
    const val T_USAGE_CHECKPOINT = 16 // seller -> buyer: [checkpoint 45][sigLen 1][seller sig]
    const val T_USAGE_ACK = 17        // buyer -> seller: [checkpoint 45][sigLen 1][buyer sig]  (or ERROR with reason)
    const val T_LAST = 17

    /** Frames a BUYER sends to a SELLER. */
    val BUYER_TO_SELLER = setOf(T_CONTRACT_PROPOSE, T_SESSION_START, T_OPEN_TCP, T_DNS_REQUEST, T_USAGE_ACK)
    /** Frames a SELLER sends to a BUYER. */
    val SELLER_TO_BUYER = setOf(T_CONTRACT_ACCEPT, T_CONTRACT_REJECT, T_SESSION_OK, T_TCP_OPEN_OK, T_DNS_RESPONSE, T_UPSTREAM_STATE, T_USAGE_CHECKPOINT)
    // The rest (SESSION_END, TCP_DATA, TCP_CLOSE, ERROR, KEEPALIVE) flow both ways.

    enum class Side { GATEWAY, CLIENT, MISDIRECTED }

    /**
     * Where an incoming tunnel frame goes on THIS phone (v0.7.1). Decided by
     * the frame's direction and this phone's role only: NEVER by whether a
     * buyer is already registered, because the first frame of a negotiation
     * (CONTRACT_PROPOSE) arrives before any buyer exists. A phone is either
     * selling or buying on a link, not both (enforced by the node).
     */
    fun route(type: Int, providing: Boolean): Side = when {
        providing && type in SELLER_TO_BUYER -> Side.MISDIRECTED   // a seller never receives seller->buyer frames
        providing -> Side.GATEWAY
        type in BUYER_TO_SELLER -> Side.MISDIRECTED                 // a buyer never receives buyer->seller frames
        else -> Side.CLIENT
    }

    const val VERSION = 1
    const val MAX_DATA = 16 * 1024
    const val HEADER = 5

    const val UP_NONE = 0
    const val UP_CELLULAR = 1
    const val UP_WIFI = 2
    const val UP_OTHER = 3

    const val ERR_SESSION_REFUSED = 1
    const val ERR_NO_UPSTREAM = 2
    const val ERR_CONNECT_FAILED = 3
    const val ERR_STREAM_LIMIT = 4
    const val ERR_BAD_FRAME = 5

    const val MAX_STREAMS = 256
    const val KEEPALIVE_MS = 15_000L
    const val KEEPALIVE_TIMEOUT_MS = 45_000L
    const val STREAM_IDLE_MS = 5 * 60_000L

    class Frame(val type: Int, val streamId: Int, val data: ByteArray)

    fun encode(type: Int, streamId: Int, data: ByteArray = ByteArray(0)): ByteArray {
        require(type in 1..T_LAST) { "bad tunnel type" }
        require(data.size <= MAX_DATA) { "tunnel data too large" }
        return ByteBuffer.allocate(HEADER + data.size).put(type.toByte()).putInt(streamId).put(data).array()
    }

    /** Null (never throws) for anything malformed. */
    fun decode(bytes: ByteArray?): Frame? {
        if (bytes == null || bytes.size < HEADER || bytes.size > HEADER + MAX_DATA) return null
        val type = bytes[0].toInt() and 0xFF
        if (type !in 1..T_LAST) return null
        val id = ByteBuffer.wrap(bytes, 1, 4).int
        return Frame(type, id, bytes.copyOfRange(HEADER, bytes.size))
    }

    fun typeName(t: Int) = when (t) {
        T_SESSION_START -> "SESSION_START"; T_SESSION_OK -> "SESSION_OK"; T_SESSION_END -> "SESSION_END"
        T_OPEN_TCP -> "OPEN_TCP"; T_TCP_OPEN_OK -> "TCP_OPEN_OK"; T_TCP_DATA -> "TCP_DATA"; T_TCP_CLOSE -> "TCP_CLOSE"
        T_DNS_REQUEST -> "DNS_REQUEST"; T_DNS_RESPONSE -> "DNS_RESPONSE"; T_ERROR -> "ERROR"; T_KEEPALIVE -> "KEEPALIVE"
        T_UPSTREAM_STATE -> "UPSTREAM_STATE"; T_CONTRACT_PROPOSE -> "CONTRACT_PROPOSE"; T_CONTRACT_ACCEPT -> "CONTRACT_ACCEPT"
        T_CONTRACT_REJECT -> "CONTRACT_REJECT"; T_USAGE_CHECKPOINT -> "USAGE_CHECKPOINT"; T_USAGE_ACK -> "USAGE_ACK"; else -> "type " + t
    }

    // ---- payloads ------------------------------------------------------------------------------

    /** v0.7: the start carries the hash of the accepted contract; the seller refuses any other. */
    fun sessionStart(buyerId: ByteArray, contractHash: ByteArray? = null): ByteArray =
        ByteBuffer.allocate(1 + 16 + (if (contractHash != null) 32 else 0)).put(VERSION.toByte()).put(buyerId, 0, 16).also { if (contractHash != null) it.put(contractHash, 0, 32) }.array()
    class SessionStart(val version: Int, val buyerId: ByteArray, val contractHash: ByteArray?)
    fun parseSessionStart(d: ByteArray?): SessionStart? =
        if (d == null || d.size < 17) null else SessionStart(d[0].toInt() and 0xFF, d.copyOfRange(1, 17), if (d.size >= 49) d.copyOfRange(17, 49) else null)

    /** [body][sigLen 1][sig] helpers for signed marketplace frames. */
    fun signed(body: ByteArray, sig: ByteArray): ByteArray = ByteBuffer.allocate(body.size + 1 + sig.size).put(body).put(sig.size.toByte()).put(sig).array()
    class SignedBody(val body: ByteArray, val sig: ByteArray)
    fun parseSigned(d: ByteArray?, bodyLen: Int): SignedBody? {
        if (d == null || d.size < bodyLen + 2) return null
        val n = d[bodyLen].toInt() and 0xFF
        if (n == 0 || d.size != bodyLen + 1 + n) return null
        return SignedBody(d.copyOfRange(0, bodyLen), d.copyOfRange(bodyLen + 1, d.size))
    }

    fun sessionOk(providerId: ByteArray, upstreamType: Int, validated: Boolean): ByteArray =
        ByteBuffer.allocate(1 + 16 + 2).put(VERSION.toByte()).put(providerId, 0, 16).put(upstreamType.toByte()).put(if (validated) 1 else 0).array()
    class SessionOk(val version: Int, val providerId: ByteArray, val upstreamType: Int, val validated: Boolean)
    fun parseSessionOk(d: ByteArray?): SessionOk? =
        if (d == null || d.size < 19) null else SessionOk(d[0].toInt() and 0xFF, d.copyOfRange(1, 17), d[17].toInt() and 0xFF, d[18].toInt() != 0)

    fun openTcp(host: String, port: Int): ByteArray {
        val h = host.toByteArray(Charsets.UTF_8)
        require(h.size in 1..255 && port in 1..65535)
        return ByteBuffer.allocate(2 + 1 + h.size).putShort(port.toShort()).put(h.size.toByte()).put(h).array()
    }
    class OpenTcp(val host: String, val port: Int)
    fun parseOpenTcp(d: ByteArray?): OpenTcp? {
        if (d == null || d.size < 4) return null
        val port = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF)
        val n = d[2].toInt() and 0xFF
        if (n == 0 || d.size != 3 + n || port == 0) return null
        return OpenTcp(String(d, 3, n, Charsets.UTF_8), port)
    }

    fun error(code: Int, msg: String): ByteArray = byteArrayOf(code.toByte()) + msg.toByteArray(Charsets.UTF_8)
    class Err(val code: Int, val message: String)
    fun parseError(d: ByteArray?): Err? = if (d == null || d.isEmpty()) null else Err(d[0].toInt() and 0xFF, String(d, 1, d.size - 1, Charsets.UTF_8))

    fun upstreamState(available: Boolean, type: Int, validated: Boolean): ByteArray = byteArrayOf(if (available) 1 else 0, type.toByte(), if (validated) 1 else 0)
    class Upstream(val available: Boolean, val type: Int, val validated: Boolean)
    fun parseUpstream(d: ByteArray?): Upstream? = if (d == null || d.size < 3) null else Upstream(d[0].toInt() != 0, d[1].toInt() and 0xFF, d[2].toInt() != 0)

    fun keepalive(seq: Int): ByteArray = ByteBuffer.allocate(4).putInt(seq).array()
    fun parseKeepalive(d: ByteArray?): Int? = if (d == null || d.size < 4) null else ByteBuffer.wrap(d).int

    fun upstreamName(t: Int) = when (t) { UP_CELLULAR -> "mobile data"; UP_WIFI -> "Wi-Fi"; UP_OTHER -> "other"; else -> "none" }

    // ---- upstream selection (provider) -------------------------------------------------------------

    /** What the Android layer knows about one network. */
    class NetView(val id: String, val internet: Boolean, val validated: Boolean, val cellular: Boolean, val wifi: Boolean, val isProkNetLink: Boolean)

    /**
     * The network real Internet sockets must be bound to: has INTERNET, is validated,
     * and is NOT the ProkNet hotspot/specifier network. Validated Wi-Fi is preferred
     * (cheaper), then cellular. Unvalidated networks are used only if nothing better exists.
     */
    fun chooseUpstream(nets: List<NetView>): NetView? {
        val ok = nets.filter { it.internet && !it.isProkNetLink }
        return ok.firstOrNull { it.validated && it.wifi } ?: ok.firstOrNull { it.validated && it.cellular } ?: ok.firstOrNull { it.validated }
            ?: ok.firstOrNull { it.cellular } ?: ok.firstOrNull()
    }

    fun upstreamType(n: NetView?): Int = when { n == null -> UP_NONE; n.cellular -> UP_CELLULAR; n.wifi -> UP_WIFI; else -> UP_OTHER }

    // ---- accounting --------------------------------------------------------------------------------

    /** One Internet session as seen by either side. Bytes are payload bytes of TCP_DATA and DNS frames. */
    class Accounting(val peerShort: String, val role: String, val startedAt: Long) {
        @Volatile var bytesUp = 0L        // buyer -> provider (uploaded by the buyer)
        @Volatile var bytesDown = 0L      // provider -> buyer (downloaded by the buyer)
        @Volatile var streamsOpened = 0
        @Volatile var dnsQueries = 0
        @Volatile var endedAt = 0L
        @Volatile var disconnectReason = ""
        val durationMs: Long get() = (if (endedAt > 0) endedAt else System.currentTimeMillis()) - startedAt
        fun end(reason: String, now: Long = System.currentTimeMillis()) { if (endedAt == 0L) { endedAt = now; disconnectReason = reason } }
        fun summary(): String = role + " with prok-" + peerShort + ": up " + bytesUp + " B, down " + bytesDown + " B, streams " + streamsOpened +
            ", dns " + dnsQueries + ", " + (durationMs / 1000) + " s" + (if (endedAt > 0) ", ended: " + disconnectReason else "")
    }

    // ---- stream table --------------------------------------------------------------------------------

    class Stream(val id: Int, val host: String, val port: Int, val openedAt: Long) {
        @Volatile var lastActivity = openedAt
        @Volatile var open = true
        @Volatile var localClosed = false
        @Volatile var remoteClosed = false
        @Volatile var bytesIn = 0L
        @Volatile var bytesOut = 0L
    }

    /** Stream lifecycle with ids, limits and idle cleanup. Thread-safe. */
    class StreamTable(private val max: Int = MAX_STREAMS) {
        private val streams = HashMap<Int, Stream>()
        private var next = 1

        @Synchronized fun nextId(): Int { do { next = if (next >= Int.MAX_VALUE - 1) 1 else next + 1 } while (streams.containsKey(next)); return next }
        @Synchronized fun open(id: Int, host: String, port: Int, now: Long): Stream? {
            if (streams.size >= max || streams.containsKey(id)) return null
            val s = Stream(id, host, port, now); streams[id] = s; return s
        }
        @Synchronized fun get(id: Int): Stream? = streams[id]
        @Synchronized fun close(id: Int): Stream? = streams.remove(id)?.also { it.open = false }
        @Synchronized fun closeAll(): List<Stream> { val all = streams.values.toList(); streams.clear(); all.forEach { it.open = false }; return all }
        @Synchronized fun count(): Int = streams.size
        @Synchronized fun ids(): List<Int> = streams.keys.toList()
        /** Streams idle longer than [idleMs] are removed and returned so the caller can close their sockets. */
        @Synchronized fun expire(now: Long, idleMs: Long = STREAM_IDLE_MS): List<Stream> {
            val dead = streams.values.filter { now - it.lastActivity > idleMs }
            dead.forEach { streams.remove(it.id); it.open = false }
            return dead
        }
    }
}
