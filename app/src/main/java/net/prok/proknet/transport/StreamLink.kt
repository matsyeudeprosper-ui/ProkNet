package net.prok.proknet.transport

import android.os.Looper
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import net.prok.proknet.core.DeliveryResult
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Handshake
import net.prok.proknet.core.LinkIo
import net.prok.proknet.core.Packet
import net.prok.proknet.core.Routing
import net.prok.proknet.core.Signer
import net.prok.proknet.core.Tunnel
import net.prok.proknet.core.Wire

/**
 * v0.10.0: **one authenticated ProkNet stream, whatever carries it.**
 *
 * This is the upper half of the old `WifiTransport.Link`, lifted out so
 * that a TCP socket from Wi-Fi and a Bluetooth socket feed the same code:
 * the signed HELLO/AUTH handshake, the frame writer thread, PACKET and
 * RECEIPT, tunnel frames, relay frames, byte accounting. The security
 * semantics are exactly the ones every Wi-Fi session has run on: nothing on
 * the stream is trusted until [handshake] has verified the peer's signature.
 *
 * The transport that owns the link supplies an [Endpoint] (the bytes) and a
 * [Host] (where verified frames go).
 */
class StreamLink(private val endpoint: Endpoint, val isHost: Boolean, private val host: Host) {

    /** The raw byte pipe, plus what the VPN needs to know about it. */
    interface Endpoint {
        val input: InputStream
        val output: OutputStream
        /** 0 = no read timeout. */
        fun setReadTimeout(ms: Int)
        fun close()
        /** The TCP socket, when there is one, so the VPN can keep it outside the tunnel. Null for Bluetooth. */
        val socket: Socket?
        fun describe(): String
    }

    /** Where verified frames go, and who counts the bytes. */
    interface Host {
        val tag: String
        val transportName: String
        val signer: Signer
        /** A PACKET frame from the authenticated peer. Returns the receipt code. */
        fun onPacket(fromShort: String?, bytes: ByteArray): Int
        fun onTunnel(peerShort: String, frame: Tunnel.Frame)
        fun onRaw(peerShort: String, type: Int, payload: ByteArray)
        /** v0.10.0: bulk probe frames. Default: ignored. */
        fun onProbe(peerShort: String, type: Int, payload: ByteArray) {}
        /** The link died. Called on a link thread; the host decides what to do and where. */
        fun onFailed(link: StreamLink, why: String)
        fun countSent(n: Long)
        fun countReceived(n: Long)
        fun countRelaySent(n: Long)
        fun countRelayReceived(n: Long)
    }

    class TcpEndpoint(override val socket: Socket) : Endpoint {
        init { socket.soTimeout = 0; socket.tcpNoDelay = true; socket.keepAlive = true }
        override val input: InputStream get() = socket.getInputStream()
        override val output: OutputStream get() = socket.getOutputStream()
        override fun setReadTimeout(ms: Int) { socket.soTimeout = ms }
        override fun close() { try { socket.close() } catch (_: Exception) {} }
        override fun describe(): String = (socket.localAddress?.hostAddress ?: "?") + ":" + socket.localPort + " -> " +
            (socket.inetAddress?.hostAddress ?: "?") + ":" + socket.port
    }

    private val io = LinkIo(endpoint.input, endpoint.output, host.transportName + (if (isHost) "-host" else "-client"))
    var peerRecord: Wire.IdentityRecord? = null
        private set
    val isOpen: Boolean get() = io.isOpen
    val socket: Socket? get() = endpoint.socket
    fun describeEndpoint(): String = endpoint.describe()

    val tunnelSent: Long get() = tunnelOut
    val tunnelReceived: Long get() = tunnelIn
    @Volatile private var tunnelOut = 0L
    @Volatile private var tunnelIn = 0L
    private val receiptLock = Object()
    private var awaitingMsg: ByteArray? = null
    private var receiptStatus = -1

    /** The signed HELLO/AUTH exchange. False, and the link is worthless, unless the peer's signature verifies. */
    fun handshake(): Boolean {
        endpoint.setReadTimeout(HANDSHAKE_TIMEOUT_MS)
        val r = Handshake.perform(io, host.signer)
        if (r.peer == null) { DiagLog.w(host.tag, "AUTH failed: " + r.why); return false }
        DiagLog.i(host.tag, "AUTH: HELLO from prok-" + r.peer.shortId + " \"" + r.peer.name + "\", peer signature VERIFIED")
        peerRecord = r.peer
        endpoint.setReadTimeout(0)
        io.startWriter { why -> DiagLog.e(host.tag, "link writer stopped: " + why); host.onFailed(this, why) }
        return true
    }

    fun writeRaw(type: Int, bytes: ByteArray): Boolean {
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        val ok = io.enqueue(type, bytes, block = !onMain)
        if (!ok) DiagLog.w(host.tag, "raw frame dropped (" + (if (onMain) "main thread, queue full " + io.queuedFrames else "link closed") + ")")
        return ok
    }

    /** Tunnel frames: block on data threads (backpressure), never on the main thread. */
    fun writeTunnel(bytes: ByteArray): Boolean {
        val onMain = Looper.myLooper() == Looper.getMainLooper()
        val ok = io.enqueue(Wire.FRAME_TUNNEL, bytes, block = !onMain)
        if (ok) tunnelOut += bytes.size else DiagLog.w(host.tag, "tunnel frame dropped (" + (if (onMain) "main thread, queue full " + io.queuedFrames else "link closed") + ")")
        return ok
    }

    fun readLoop() {
        io.startReader({ type, p ->
            when (type) {
                Wire.FRAME_PACKET -> {
                    val pkt = Packet.decode(p)
                    val code = if (pkt == null) Routing.RECEIPT_REJECTED else try { host.onPacket(peerRecord?.shortId, p) } catch (e: Exception) { DiagLog.e(host.tag, "frame handler", e); Routing.RECEIPT_REJECTED }
                    io.enqueue(Wire.FRAME_RECEIPT, Wire.receiptPayload(code, pkt?.msgId ?: ByteArray(8)), block = true)
                    host.countReceived(p.size.toLong())
                }
                Wire.FRAME_TUNNEL -> {
                    tunnelIn += p.size
                    val f = Tunnel.decode(p)
                    val peer = peerRecord?.shortId
                    if (f == null || peer == null) DiagLog.w(host.tag, "malformed tunnel frame ignored (" + p.size + " bytes)")
                    else try { host.onTunnel(peer, f) } catch (e: Exception) { DiagLog.e(host.tag, "tunnel sink: " + LinkIo.describe(e)) }
                }
                Wire.FRAME_RELAY, Wire.FRAME_RELAY_INFO -> {
                    host.countRelayReceived(p.size.toLong())
                    val peer = peerRecord?.shortId
                    if (peer != null) try { host.onRaw(peer, type, p) } catch (e: Exception) { DiagLog.e(host.tag, "relay sink: " + LinkIo.describe(e)) }
                }
                Wire.FRAME_BULK_PROBE, Wire.FRAME_BULK_PROBE_DONE -> {
                    val peer = peerRecord?.shortId
                    if (peer != null) try { host.onProbe(peer, type, p) } catch (e: Exception) { DiagLog.e(host.tag, "probe: " + LinkIo.describe(e)) }
                }
                Wire.FRAME_RECEIPT -> {
                    val r = Wire.parseReceipt(p)
                    if (r != null) synchronized(receiptLock) {
                        if (awaitingMsg != null && r.msgId.contentEquals(awaitingMsg)) { receiptStatus = r.status; receiptLock.notifyAll() }
                    }
                }
                else -> {}
            }
        }, { why ->
            DiagLog.w(host.tag, "link read loop ended: " + why)
            synchronized(receiptLock) { receiptLock.notifyAll() }
            host.onFailed(this, "connection closed: " + why)
        })
    }

    fun sendAndWait(f: Frame): Pair<DeliveryResult, String> {
        synchronized(receiptLock) { awaitingMsg = f.msgId; receiptStatus = -1 }
        if (!io.enqueue(Wire.FRAME_PACKET, f.bytes, block = true)) return DeliveryResult.TRANSPORT_FAILED to ("link closed: " + io.closeReason)
        host.countSent(f.bytes.size.toLong())
        val deadline = System.currentTimeMillis() + RECEIPT_TIMEOUT_MS
        synchronized(receiptLock) {
            while (receiptStatus < 0 && io.isOpen) {
                val left = deadline - System.currentTimeMillis()
                if (left <= 0) break
                receiptLock.wait(left)
            }
            val st = receiptStatus; awaitingMsg = null
            if (st < 0) return (if (io.isOpen) DeliveryResult.NO_RECEIPT else DeliveryResult.TRANSPORT_FAILED) to ("no receipt over " + host.transportName + " within " + (RECEIPT_TIMEOUT_MS / 1000) + "s")
            return Routing.resultFor(st) to (host.transportName + " receipt " + st)
        }
    }

    fun close() {
        io.close("closed by transport")
        endpoint.close()
        synchronized(receiptLock) { receiptLock.notifyAll() }
    }

    companion object {
        const val RECEIPT_TIMEOUT_MS = 15_000L
        const val HANDSHAKE_TIMEOUT_MS = 15_000
    }
}
