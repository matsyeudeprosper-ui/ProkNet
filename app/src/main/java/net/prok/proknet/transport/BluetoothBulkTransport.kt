package net.prok.proknet.transport

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.io.OutputStream
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import net.prok.proknet.core.BulkPlan
import net.prok.proknet.core.DeliveryResult
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.LinkIo
import net.prok.proknet.core.Routing
import net.prok.proknet.core.Signer
import net.prok.proknet.core.Tunnel
import net.prok.proknet.core.Wire

/**
 * v0.10.0: **the Bluetooth bulk link.**
 *
 * A Bluetooth L2CAP connection-oriented channel between the two phones,
 * carrying the same authenticated ProkNet stream as a Wi-Fi link: signed
 * HELLO/AUTH, PACKET/RECEIPT, tunnel frames, relay frames. The provider
 * listens and gets a dynamic PSM, tells the customer over the encrypted BLE
 * control channel, and the customer connects to that exact peer.
 *
 * Why L2CAP: it is a byte stream, which is what the tunnel wants; the peer
 * relationship already comes from BLE discovery and the ProkNet identity, so
 * no device is ever matched by name; and it needs no IP network, no
 * hotspot and no Wi-Fi Direct group, which is where the last twenty versions
 * went to die. Both test phones are on Android 14 and 15, above the API 29
 * these calls need.
 *
 * "Insecure" in the Android API names means no OS pairing dialog. It means
 * nothing to ProkNet: not one tunnel frame is accepted before the signed
 * handshake verifies the peer, exactly as on Wi-Fi. A nearby phone that
 * reaches the PSM gets a closed socket.
 *
 * GATT stays [BleTransport]: control and store-carry-forward, never an
 * Internet tunnel.
 */
class BluetoothBulkTransport(
    private val context: Context,
    private val identity: Identity,
) : Transport {

    interface Hooks {
        /** The link is authenticated and up with [peerShort]. */
        fun onBulkUp(peerShort: String, isHost: Boolean)
        /** The link is gone. */
        fun onBulkDown(peerShort: String?, reason: String)
        /** The 1 MB each way probe finished (or timed out). */
        fun onProbe(peerShort: String, verdict: BulkPlan.Verdict, report: String)
        fun onChanged()
    }

    override val name: String = Routing.TRANSPORT_BT_BULK
    override val bulkCapable = true
    private val tag = "BTBULK"
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newCachedThreadPool()
    private val adapter: BluetoothAdapter? = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private var listener: TransportListener? = null
    @Volatile var tunnelSink: WifiTransport.TunnelSink? = null
    @Volatile var hooks: Hooks? = null
    override val isRunning: Boolean get() = running
    @Volatile private var running = false

    private val sent = AtomicLong(); private val received = AtomicLong()
    private val relaySent = AtomicLong(); private val relayReceived = AtomicLong()
    override val bytesSent: Long get() = sent.get()
    override val bytesReceived: Long get() = received.get()

    /** The pure lifecycle: who, which session, which PSM, authenticated or not. */
    @Volatile var state: BulkPlan.State = BulkPlan.IDLE
        private set
    @Volatile var lastError: String = ""
        private set
    val phase: String get() = state.phase.toString()
    val linkedPeer: String? get() = if (state.phase == BulkPlan.Phase.UP && state.authenticated) state.peer else null

    private var server: BluetoothServerSocket? = null
    private var pendingSocket: BluetoothSocket? = null
    private var link: StreamLink? = null
    private var timer: Runnable? = null

    // ---- the probe ------------------------------------------------------------------------------------------
    @Volatile private var probeSendStart = 0L
    @Volatile private var probeRecvStart = 0L
    @Volatile private var probeRecvBytes = 0L
    @Volatile private var probeOut: BulkPlan.Direction? = null       // what the PEER received from us
    @Volatile private var probeIn: BulkPlan.Direction? = null        // what we received from the peer
    @Volatile private var probeDone = false
    @Volatile var lastProbe: String = "not run"
        private set
    @Volatile var lastVerdict: BulkPlan.Verdict = BulkPlan.Verdict.NOT_RUN
        private set

    val supported: Boolean get() = Build.VERSION.SDK_INT >= 29 && adapter != null
    val isBluetoothOn: Boolean get() = adapter?.isEnabled == true

    override fun start(listener: TransportListener): Boolean {
        this.listener = listener
        running = true
        DiagLog.i(tag, "transport started (L2CAP " + (if (supported) "available" else "NOT available on this Android") + ")")
        return true
    }

    override fun stop() {
        cancel("transport stopped")
        running = false
    }

    override fun canReach(peerShort: String): Boolean = running && BulkPlan.mayCarry(state, peerShort) && link?.isOpen == true

    override fun linkState(): String = (if (!running) "off" else state.describe()) + (if (lastProbe != "not run") " | probe " + lastProbe else "")

    private fun set(s: BulkPlan.State, why: String = "") {
        state = s
        if (why.isNotEmpty()) DiagLog.i(tag, "BULK " + s.phase + ": " + why)
        listener?.onLinkState(name, s.describe())
        main.post { hooks?.onChanged() }
    }

    // ---- provider: listen ---------------------------------------------------------------------------------------

    /**
     * Open an L2CAP listener for [peerShort] and this [session]. Returns the
     * dynamic PSM to send in BULK_OFFER, or null with [lastError] set.
     */
    fun listen(session: Int, peerShort: String): Int? {
        if (!supported) { lastError = "Bluetooth L2CAP needs Android 10 or newer"; return null }
        val a = adapter ?: run { lastError = "no Bluetooth adapter"; return null }
        if (!a.isEnabled) { lastError = "Bluetooth is off"; return null }
        cancel("a new listener replaces the old one", quiet = true)
        val ss = try { a.listenUsingInsecureL2capChannel() }
        catch (e: SecurityException) { lastError = "permission: " + e.message; DiagLog.e(tag, "BULK LISTENER refused: " + lastError); return null }
        catch (e: Exception) { lastError = "listen failed: " + LinkIo.describe(e); DiagLog.e(tag, "BULK LISTENER failed: " + lastError); return null }
        val psm = try { ss.psm } catch (e: Exception) { try { ss.close() } catch (_: Exception) {}; lastError = "no PSM: " + e; return null }
        server = ss
        lastError = ""
        set(BulkPlan.listening(BulkPlan.IDLE, session, peerShort, psm), "BULK LISTENER READY on PSM " + psm + " for prok-" + peerShort + ", session " + BulkPlan.sessionHex(session))
        armTimer(BulkPlan.Phase.LISTENING, session)
        io.execute {
            val s = try { ss.accept(BulkPlan.ACCEPT_TIMEOUT_MS.toInt()) } catch (e: Exception) {
                main.post { if (state.phase == BulkPlan.Phase.LISTENING && state.session == session) fail("nobody connected to PSM " + psm + ": " + LinkIo.describe(e)) }
                return@execute
            }
            try { ss.close() } catch (_: Exception) {}
            main.post { if (state.session == session) onSocket(s, isHost = true) else { try { s.close() } catch (_: Exception) {} } }
        }
        return psm
    }

    // ---- customer: connect -------------------------------------------------------------------------------------

    /** Connect to the exact peer at [address] (its current BLE address) on [psm]. */
    fun connect(session: Int, peerShort: String, address: String, psm: Int): Boolean {
        if (!supported) { lastError = "Bluetooth L2CAP needs Android 10 or newer"; return false }
        val a = adapter ?: run { lastError = "no Bluetooth adapter"; return false }
        if (!a.isEnabled) { lastError = "Bluetooth is off"; return false }
        cancel("a new connection replaces the old one", quiet = true)
        val device = try { a.getRemoteDevice(address) } catch (e: Exception) { lastError = "bad address " + address; return false }
        lastError = ""
        set(BulkPlan.offerReceived(BulkPlan.request(BulkPlan.IDLE, session, peerShort), session, peerShort, BulkPlan.TECH_L2CAP, psm),
            "BULK CONNECTING to prok-" + peerShort + " @ " + address + " PSM " + psm + ", session " + BulkPlan.sessionHex(session))
        armTimer(BulkPlan.Phase.CONNECTING, session)
        io.execute {
            val s = try { device.createInsecureL2capChannel(psm) } catch (e: SecurityException) {
                main.post { fail("permission: " + e.message) }; return@execute
            } catch (e: Exception) { main.post { fail("channel: " + LinkIo.describe(e)) }; return@execute }
            pendingSocket = s
            try { s.connect() } catch (e: Exception) {
                try { s.close() } catch (_: Exception) {}
                main.post { if (state.session == session) fail("connect to PSM " + psm + " failed: " + LinkIo.describe(e)) }
                return@execute
            }
            main.post { if (state.session == session) onSocket(s, isHost = false) else { try { s.close() } catch (_: Exception) {} } }
        }
        return true
    }

    // ---- both: the socket exists, now the signed handshake ---------------------------------------------------------

    private fun onSocket(s: BluetoothSocket, isHost: Boolean) {
        val session = state.session
        DiagLog.i(tag, "BULK SOCKET CONNECTED (" + (if (isHost) "host" else "client") + ") with " + (s.remoteDevice?.address ?: "?"))
        set(BulkPlan.socketConnected(state), "AUTH START, signed handshake (" + (if (isHost) "host" else "client") + ")")
        armTimer(BulkPlan.Phase.AUTH, session)
        val l = StreamLink(BluetoothEndpoint(s), isHost, linkHost)
        io.execute {
            val ok = l.handshake()
            main.post {
                if (state.session != session || state.phase != BulkPlan.Phase.AUTH) { l.close(); return@post }
                if (!ok) { l.close(); fail("handshake failed"); return@post }
                val peer = l.peerRecord!!.shortId
                val next = BulkPlan.authenticated(state, peer)
                if (next.phase != BulkPlan.Phase.UP) { l.close(); fail(next.error); return@post }
                link?.close(); link = l
                clearTimer()
                listener?.onIdentity(name, l.peerRecord!!.idHex, l.peerRecord!!.pub, l.peerRecord!!.name)
                set(next, "AUTH OK, BULK UP with prok-" + peer + " (" + (if (isHost) "host" else "client") + ", both signatures verified)")
                io.execute { l.readLoop() }
                hooks?.onBulkUp(peer, isHost)
            }
        }
    }

    private fun fail(why: String) {
        lastError = why
        val peer = state.peer.ifEmpty { null }
        DiagLog.e(tag, "BULK FAILED at " + state.phase + ": " + why)
        closeEverything()
        set(BulkPlan.failed(state, why))
        if (peer != null) try { tunnelSink?.onLinkClosed(peer, why) } catch (e: Exception) { DiagLog.w(tag, "sink: " + e) }
        hooks?.onBulkDown(peer, why)
    }

    /** Drop everything, on purpose. */
    fun cancel(reason: String, quiet: Boolean = false) {
        val had = state.active
        val peer = state.peer.ifEmpty { null }
        closeEverything()
        if (had) {
            if (!quiet) DiagLog.i(tag, "BULK cancelled: " + reason)
            set(BulkPlan.reset(state))
            if (peer != null && !quiet) try { tunnelSink?.onLinkClosed(peer, reason) } catch (e: Exception) { DiagLog.w(tag, "sink: " + e) }
            if (!quiet) hooks?.onBulkDown(peer, reason)
        } else state = BulkPlan.IDLE
    }

    private fun closeEverything() {
        clearTimer()
        try { server?.close() } catch (_: Exception) {}; server = null
        try { pendingSocket?.close() } catch (_: Exception) {}; pendingSocket = null
        link?.close(); link = null
        probeDone = false; probeOut = null; probeIn = null; probeRecvBytes = 0L
    }

    private fun armTimer(phase: BulkPlan.Phase, session: Int) {
        clearTimer()
        val r = Runnable {
            timer = null
            if (BulkPlan.timerApplies(state, phase, session)) fail(BulkPlan.timeoutReason(phase) + " within " + (BulkPlan.timeoutMs(phase) / 1000) + "s")
        }
        timer = r
        main.postDelayed(r, BulkPlan.timeoutMs(phase))
    }

    private fun clearTimer() { timer?.let { main.removeCallbacks(it) }; timer = null }

    // ---- what the tunnel and the gateway use, exactly as on Wi-Fi --------------------------------------------------

    fun sendTunnel(type: Int, streamId: Int, data: ByteArray = ByteArray(0)): Boolean {
        val l = link ?: return false
        if (state.phase != BulkPlan.Phase.UP || !l.isOpen) return false
        val bytes = try { Tunnel.encode(type, streamId, data) } catch (e: Exception) { DiagLog.e(tag, "tunnel encode: " + LinkIo.describe(e)); return false }
        return l.writeTunnel(bytes)
    }

    fun sendRaw(type: Int, payload: ByteArray): Boolean {
        val l = link ?: return false
        if (state.phase != BulkPlan.Phase.UP || !l.isOpen) return false
        val ok = l.writeRaw(type, payload)
        if (ok) relaySent.addAndGet(payload.size.toLong())
        return ok
    }

    val tunnelBytesSent: Long get() = link?.tunnelSent ?: 0L
    val tunnelBytesReceived: Long get() = link?.tunnelReceived ?: 0L

    override fun sendBatch(peerShort: String, frames: List<Frame>, onEach: (Int, DeliveryResult, String) -> Boolean, onDone: () -> Unit) {
        val l = link
        if (l == null || !BulkPlan.mayCarry(state, peerShort) || !l.isOpen) {
            if (frames.isNotEmpty()) onEach(0, DeliveryResult.TRANSPORT_FAILED, "no Bluetooth bulk link to prok-" + peerShort)
            onDone(); return
        }
        io.execute {
            for ((i, f) in frames.withIndex()) {
                val (res, detail) = l.sendAndWait(f)
                val cont = try { onEach(i, res, detail) } catch (e: Exception) { false }
                if (res == DeliveryResult.TRANSPORT_FAILED) { main.post { fail(detail) }; break }
                if (!cont) break
            }
            onDone()
        }
    }

    // ---- the probe: 1 MB each way, both reported separately -----------------------------------------------------------

    /** Send [BulkPlan.PROBE_BYTES] to the peer and wait for both directions to be judged. */
    fun startProbe() {
        val l = link ?: return
        val peer = state.peer
        probeDone = false; probeOut = null; probeIn = null; probeRecvBytes = 0L; probeRecvStart = 0L
        lastProbe = "running"; lastVerdict = BulkPlan.Verdict.NOT_RUN
        DiagLog.i(tag, "BULK PROBE start: sending " + BulkPlan.PROBE_BYTES + " B to prok-" + peer)
        probeSendStart = System.currentTimeMillis()
        io.execute {
            val chunk = ByteArray(BulkPlan.PROBE_CHUNK) { (it and 0xFF).toByte() }
            var left = BulkPlan.PROBE_BYTES
            while (left > 0 && l.isOpen) {
                val n = minOf(left, chunk.size)
                if (!l.writeRaw(Wire.FRAME_BULK_PROBE, if (n == chunk.size) chunk else chunk.copyOf(n))) break
                left -= n
            }
            DiagLog.i(tag, "BULK PROBE: " + (BulkPlan.PROBE_BYTES - left) + " B queued to prok-" + peer + " in " + (System.currentTimeMillis() - probeSendStart) + " ms")
        }
        main.postDelayed({ finishProbe("timeout") }, BulkPlan.PROBE_TIMEOUT_MS)
    }

    private fun onProbeFrame(peer: String, type: Int, p: ByteArray) {
        val l = link ?: return
        when (type) {
            Wire.FRAME_BULK_PROBE -> {
                if (probeRecvStart == 0L) probeRecvStart = System.currentTimeMillis()
                probeRecvBytes += p.size
                if (probeRecvBytes >= BulkPlan.PROBE_BYTES && probeIn == null) {
                    val ms = System.currentTimeMillis() - probeRecvStart
                    probeIn = BulkPlan.Direction(probeRecvBytes, ms, true)
                    l.writeRaw(Wire.FRAME_BULK_PROBE_DONE, Wire.bulkProbeDone(probeRecvBytes, ms))
                    DiagLog.i(tag, "BULK PROBE: received " + probeRecvBytes + " B from prok-" + peer + " in " + ms + " ms")
                    main.post { if (probeOut != null) finishProbe("both directions reported") }
                }
            }
            Wire.FRAME_BULK_PROBE_DONE -> {
                val r = Wire.parseBulkProbeDone(p) ?: return
                probeOut = BulkPlan.Direction(r.first, r.second, r.first >= BulkPlan.PROBE_BYTES)
                DiagLog.i(tag, "BULK PROBE: prok-" + peer + " received " + r.first + " B from us in " + r.second + " ms")
                main.post { if (probeIn != null) finishProbe("both directions reported") }
            }
        }
    }

    private fun finishProbe(why: String) {
        if (probeDone) return
        probeDone = true
        val out = probeOut ?: BulkPlan.Direction(0, 0, false)
        val inn = probeIn ?: BulkPlan.Direction(probeRecvBytes, 0, false)
        val me = if (link?.isHost == true) "seller" else "buyer"
        val other = if (link?.isHost == true) "buyer" else "seller"
        val v = BulkPlan.verdict(out, inn)
        lastVerdict = v
        lastProbe = me + " -> " + other + ": " + out.describe() + " | " + other + " -> " + me + ": " + inn.describe() + " | VERDICT: " + BulkPlan.verdictText(v) + (if (why == "timeout") " (timeout)" else "")
        DiagLog.i(tag, "BLUETOOTH BULK PROBE\n  " + me + " -> " + other + ": " + out.describe() + "\n  " + other + " -> " + me + ": " + inn.describe() + "\n  VERDICT: " + BulkPlan.verdictText(v))
        val peer = state.peer
        hooks?.onProbe(peer, v, lastProbe)
        main.post { hooks?.onChanged() }
    }

    // ---- diagnostics -----------------------------------------------------------------------------------------------------

    fun diag(): String {
        val sb = StringBuilder()
        sb.append("bluetooth bulk:\n")
        sb.append("  state: ").append(state.phase).append(if (running) "" else " (transport off)").append("\n")
        sb.append("  role: ").append(when (state.side) { BulkPlan.Side.HOST -> "HOST"; BulkPlan.Side.CLIENT -> "CLIENT"; else -> "-" }).append("\n")
        sb.append("  technology: L2CAP").append(if (supported) "" else " (NOT supported on this Android)").append("\n")
        sb.append("  session: ").append(if (state.session == 0) "-" else BulkPlan.sessionHex(state.session)).append("\n")
        sb.append("  peer: ").append(state.peer.ifEmpty { "-" }).append("\n")
        sb.append("  PSM: ").append(if (state.psm == 0) "-" else state.psm.toString()).append("\n")
        sb.append("  authenticated: ").append(state.authenticated).append("\n")
        sb.append("  sent: ").append(bytesSent + tunnelBytesSent + relaySent.get()).append(" B | received: ").append(bytesReceived + tunnelBytesReceived + relayReceived.get()).append(" B\n")
        sb.append("  probe: ").append(lastProbe).append("\n")
        sb.append("  last error: ").append(lastError.ifEmpty { "-" }).append("\n")
        return sb.toString()
    }

    // ---- the Bluetooth socket as a stream endpoint ----------------------------------------------------------------------

    private class BluetoothEndpoint(private val s: BluetoothSocket) : StreamLink.Endpoint {
        override val input: InputStream get() = s.inputStream
        override val output: OutputStream get() = s.outputStream
        /** Bluetooth sockets have no read timeout; the handshake is bounded by the transport's own timer instead. */
        override fun setReadTimeout(ms: Int) {}
        override fun close() { try { s.close() } catch (_: Exception) {} }
        override val socket: Socket? get() = null
        override fun describe(): String = "L2CAP with " + (s.remoteDevice?.address ?: "?")
    }

    private val linkHost = object : StreamLink.Host {
        override val tag: String get() = this@BluetoothBulkTransport.tag
        override val transportName: String get() = name
        override val signer: Signer get() = identity
        override fun onPacket(fromShort: String?, bytes: ByteArray): Int = listener?.onFrame(name, fromShort, bytes) ?: Routing.RECEIPT_REJECTED
        override fun onTunnel(peerShort: String, frame: Tunnel.Frame) { tunnelSink?.onTunnelFrame(peerShort, frame) }
        override fun onRaw(peerShort: String, type: Int, payload: ByteArray) { tunnelSink?.onRawFrame(peerShort, type, payload) }
        override fun onProbe(peerShort: String, type: Int, payload: ByteArray) { onProbeFrame(peerShort, type, payload) }
        override fun onFailed(link: StreamLink, why: String) { main.post { if (this@BluetoothBulkTransport.link === link) fail(why) } }
        override fun countSent(n: Long) { sent.addAndGet(n) }
        override fun countReceived(n: Long) { received.addAndGet(n) }
        override fun countRelaySent(n: Long) { relaySent.addAndGet(n) }
        override fun countRelayReceived(n: Long) { relayReceived.addAndGet(n) }
    }
}
