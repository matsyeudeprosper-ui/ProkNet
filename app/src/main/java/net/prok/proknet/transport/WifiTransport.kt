package net.prok.proknet.transport

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiConfiguration
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import net.prok.proknet.core.Crypto
import net.prok.proknet.core.DeliveryResult
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.LinkState
import net.prok.proknet.core.Packet
import net.prok.proknet.core.Routing
import net.prok.proknet.core.Wire
import net.prok.proknet.core.toHex

/**
 * Wi-Fi as a ProkNet transport (v0.5): phone-to-phone TCP over a local-only
 * hotspot. No router, no Internet.
 *
 *   initiator (wants the link)  --BLE: WIFI_REQUEST (encrypted)-->  host
 *   host: startLocalOnlyHotspot, ServerSocket           --BLE: WIFI_OFFER(ssid, pass, ip, port)-->  initiator
 *   initiator: join SSID (WifiNetworkSpecifier, one system dialog on Android 10+), TCP connect
 *   both: HELLO (identity record + nonce), AUTH (signature over both IDs and nonces) -> link UP
 *   frames: [u32 len][type][payload]; every PACKET is answered by a RECEIPT
 *
 * Credentials travel only inside an end-to-end encrypted control message.
 * The link is bound to the peer's cryptographic identity by the handshake,
 * never to an address. Decisions (state, retry) live in core/LinkState.
 */
class WifiTransport(
    private val context: Context,
    private val identity: Identity,
    private val control: ControlChannel,
) : Transport {
    /** How control bodies reach the peer: the node encrypts them and sends them over BLE. */
    interface ControlChannel {
        fun sendControl(peerShort: String, body: ByteArray, cb: (Boolean) -> Unit)
        fun knownPeerPub(peerShort: String): ByteArray?
    }

    private val tag = "WIFI"
    override val name = Routing.TRANSPORT_WIFI
    override val bulkCapable = true
    private val main = Handler(Looper.getMainLooper())
    /** Long-running loops (accept, read) each need their own thread; sends are serialised on one. */
    private val io = Executors.newCachedThreadPool()
    private val sendExec = Executors.newSingleThreadExecutor()
    private val fsm = LinkState()
    private var listener: TransportListener? = null
    @Volatile override var isRunning = false
        private set
    private val sent = AtomicLong(); private val received = AtomicLong()
    override val bytesSent: Long get() = sent.get()
    override val bytesReceived: Long get() = received.get()

    private val wifi: WifiManager get() = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val cm: ConnectivityManager get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var reservation: WifiManager.LocalOnlyHotspotReservation? = null
    private var serverSocket: ServerSocket? = null
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private var link: Link? = null
    private var pendingOffer: Wire.Control.WifiOffer? = null
    @Volatile private var lastEvent = ""
    private val ticker = object : Runnable {
        override fun run() {
            if (!isRunning) return
            if (fsm.tick(now(), STEP_TIMEOUT_MS) == LinkState.Action.TEARDOWN) teardown("step timeout")
            main.postDelayed(this, 5000)
        }
    }

    private fun now() = System.currentTimeMillis()
    val state: LinkState get() = fsm
    val linkedPeer: String? get() = if (fsm.isUp) fsm.peer else null

    override fun start(listener: TransportListener): Boolean {
        this.listener = listener
        isRunning = true
        fsm.reset(now())
        main.postDelayed(ticker, 5000)
        push("idle")
        DiagLog.i(tag, "transport started (hotspot/TCP; Wi-Fi " + (if (wifi.isWifiEnabled) "enabled" else "DISABLED - turn Wi-Fi on for fast links") + ")")
        return true
    }

    override fun stop() {
        if (!isRunning) return
        isRunning = false
        main.removeCallbacks(ticker)
        teardown("transport stopped")
        DiagLog.i(tag, "transport stopped")
    }

    override fun canReach(peerShort: String): Boolean = isRunning && fsm.isUp && fsm.peer == peerShort && link?.isOpen == true
    override fun linkState(): String = (if (!isRunning) "off" else fsm.describe()) + (if (lastEvent.isNotEmpty()) " | " + lastEvent else "")
    private fun push(event: String) { lastEvent = event; listener?.onLinkState(name, linkState()) }

    // ---- negotiation (public entry points) -------------------------------------------------------

    /** Ask [peerShort] for a Wi-Fi link. We become the initiator (we join their hotspot). */
    fun requestLink(peerShort: String): Boolean {
        if (!isRunning) return false
        if (control.knownPeerPub(peerShort) == null) { DiagLog.w(tag, "cannot negotiate with prok-" + peerShort + ": public key unknown (needs one BLE contact first)"); return false }
        if (!fsm.canRetry(now())) { DiagLog.w(tag, "link attempt too soon after failure (" + fsm.describe() + ")"); return false }
        when (fsm.request(peerShort, now())) {
            LinkState.Action.SEND_REQUEST -> {
                DiagLog.i(tag, "NEGOTIATE: sending WIFI_REQUEST to prok-" + peerShort + " over BLE (encrypted)")
                push("request sent")
                control.sendControl(peerShort, Wire.wifiRequest(PORT)) { ok -> if (!ok) main.post { fail("could not deliver WIFI_REQUEST") } }
                return true
            }
            else -> { DiagLog.i(tag, "request ignored: " + fsm.describe()); return false }
        }
    }

    /** A control body for us arrived (already decrypted and verified by the node). */
    fun onControl(peerShort: String, body: ByteArray) {
        val c = Wire.parseControl(body) ?: run { DiagLog.w(tag, "malformed control from prok-" + peerShort); return }
        main.post {
            when (c) {
                is Wire.Control.WifiRequest -> {
                    DiagLog.i(tag, "NEGOTIATE: WIFI_REQUEST from prok-" + peerShort)
                    when (fsm.requestReceived(peerShort, identity.shortIdHex, now())) {
                        LinkState.Action.START_HOTSPOT -> { push("hosting for prok-" + peerShort); startHotspot() }
                        else -> DiagLog.i(tag, "request from prok-" + peerShort + " ignored: " + fsm.describe())
                    }
                }
                is Wire.Control.WifiOffer -> {
                    DiagLog.i(tag, "NEGOTIATE: WIFI_OFFER from prok-" + peerShort + " ssid=" + c.ssid + " ips=" + c.ips + " port=" + c.port)
                    when (fsm.offerReceived(peerShort, now())) {
                        LinkState.Action.JOIN_NETWORK -> { pendingOffer = c; push("joining " + c.ssid); joinNetwork(c) }
                        else -> DiagLog.i(tag, "offer ignored: " + fsm.describe())
                    }
                }
                Wire.Control.WifiCancel -> { DiagLog.i(tag, "peer cancelled the Wi-Fi link"); teardown("cancelled by peer") }
            }
        }
    }

    // ---- host side ---------------------------------------------------------------------------------

    private fun startHotspot() {
        try {
            wifi.startLocalOnlyHotspot(object : WifiManager.LocalOnlyHotspotCallback() {
                override fun onStarted(res: WifiManager.LocalOnlyHotspotReservation) {
                    main.post {
                        reservation = res
                        val (ssid, pass) = credentials(res)
                        if (ssid == null) { fail("hotspot started but no SSID readable"); return@post }
                        // The AP interface can take a moment to get its address: poll a few times.
                        var tries = 0
                        main.post(object : Runnable {
                            override fun run() {
                                if (fsm.state != LinkState.State.HOSTING) return
                                val ips = hostAddresses()
                                if (ips.isEmpty() && tries++ < 10) { main.postDelayed(this, 500); return }
                                DiagLog.i(tag, "hotspot UP ssid=" + ssid + " ips=" + ips)
                                if (ips.isEmpty()) { fail("hotspot has no IPv4 address after 5s"); return }
                                if (!startServer()) { fail("could not open server socket"); return }
                                if (fsm.hotspotUp(now()) == LinkState.Action.SEND_OFFER) {
                                    val peer = fsm.peer!!
                                    push("offer sent to prok-" + peer)
                                    control.sendControl(peer, Wire.wifiOffer(ssid, pass ?: "", PORT, ips)) { ok -> if (!ok) main.post { fail("could not deliver WIFI_OFFER") } }
                                }
                            }
                        })
                    }
                }
                override fun onFailed(reason: Int) { main.post { fail("hotspot failed, reason " + reason + hotspotHint(reason)) } }
                override fun onStopped() { main.post { DiagLog.w(tag, "hotspot stopped by the system"); reservation = null; if (fsm.isUp || fsm.isBusy) fail("hotspot stopped") } }
            }, main)
        } catch (e: SecurityException) {
            fail("hotspot needs the Nearby devices / Location permission: " + e.message)
        } catch (e: Exception) {
            fail("startLocalOnlyHotspot threw " + e)
        }
    }

    private fun hotspotHint(reason: Int) = when (reason) {
        WifiManager.LocalOnlyHotspotCallback.ERROR_NO_CHANNEL -> " (no channel)"
        WifiManager.LocalOnlyHotspotCallback.ERROR_GENERIC -> " (generic; is Wi-Fi on and Location enabled?)"
        WifiManager.LocalOnlyHotspotCallback.ERROR_INCOMPATIBLE_MODE -> " (incompatible mode: tethering already active?)"
        WifiManager.LocalOnlyHotspotCallback.ERROR_TETHERING_DISALLOWED -> " (tethering disallowed by policy)"
        else -> ""
    }

    @Suppress("DEPRECATION")
    private fun credentials(res: WifiManager.LocalOnlyHotspotReservation): Pair<String?, String?> {
        return try {
            if (Build.VERSION.SDK_INT >= 30) {
                val c = res.softApConfiguration
                (c.ssid ?: c.wifiSsid?.toString()?.trim('"')) to c.passphrase
            } else {
                val c: WifiConfiguration? = res.wifiConfiguration
                c?.SSID?.trim('"') to c?.preSharedKey
            }
        } catch (e: Exception) { null to null }
    }

    /** IPv4 addresses of this phone that a hotspot client could reach, most likely first. */
    private fun hostAddresses(): List<String> {
        val out = ArrayList<Pair<Int, String>>()
        try {
            for (nif in NetworkInterface.getNetworkInterfaces()) {
                if (!nif.isUp || nif.isLoopback) continue
                for (addr in nif.inetAddresses) {
                    if (addr !is java.net.Inet4Address || !addr.isSiteLocalAddress) continue
                    val n = nif.name.lowercase()
                    val score = (if (n.startsWith("ap") || n.startsWith("swlan") || n.contains("wlan1")) 0 else if (n.startsWith("wlan")) 1 else 2) + (if (addr.hostAddress?.endsWith(".1") == true) 0 else 3)
                    out.add(score to addr.hostAddress!!)
                }
            }
        } catch (e: Exception) { DiagLog.w(tag, "interfaces: " + e) }
        return out.sortedBy { it.first }.map { it.second }.distinct().take(4)
    }

    private fun startServer(): Boolean {
        if (serverSocket != null) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress(PORT))
            serverSocket = ss
            io.execute { acceptLoop(ss) }
            true
        } catch (e: Exception) { DiagLog.e(tag, "server socket", e); false }
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (isRunning && !ss.isClosed) {
            val s = try { ss.accept() } catch (e: Exception) { break }
            DiagLog.i(tag, "client connected from " + s.inetAddress.hostAddress)
            main.post { fsm.clientConnected(now()); push("client connected, handshake") }
            val l = Link(s, isHost = true)
            if (l.handshake()) main.post { linkUp(l) } else { l.close(); main.post { fail("handshake failed (host)") } }
        }
    }

    // ---- client side -------------------------------------------------------------------------------

    private fun joinNetwork(offer: Wire.Control.WifiOffer) {
        if (Build.VERSION.SDK_INT >= 29) {
            val spec = WifiNetworkSpecifier.Builder().setSsid(offer.ssid).apply { if (offer.pass.isNotEmpty()) setWpa2Passphrase(offer.pass) }.build()
            val req = NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .setNetworkSpecifier(spec)
                .build()
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    DiagLog.i(tag, "joined " + offer.ssid + " (network " + network + ")")
                    main.post { if (fsm.networkAvailable(now()) == LinkState.Action.OPEN_SOCKET) { push("connecting to " + offer.ips[0] + ":" + offer.port); io.execute { openClient(network, offer) } } }
                }
                override fun onUnavailable() { main.post { fail("Wi-Fi network unavailable (dialog declined, wrong password, or hotspot not visible)") } }
                override fun onLost(network: Network) { main.post { if (fsm.isUp || fsm.isBusy) fail("Wi-Fi network lost") } }
            }
            netCallback = cb
            try {
                DiagLog.i(tag, "requesting network " + offer.ssid + " - Android shows a 'connect to device' dialog: tap CONNECT")
                cm.requestNetwork(req, cb, STEP_TIMEOUT_MS.toInt())
            } catch (e: SecurityException) { fail("requestNetwork needs permission: " + e.message) } catch (e: Exception) { fail("requestNetwork threw " + e) }
        } else {
            joinLegacy(offer)
        }
    }

    @Suppress("DEPRECATION")
    private fun joinLegacy(offer: Wire.Control.WifiOffer) {
        try {
            val conf = WifiConfiguration()
            conf.SSID = "\"" + offer.ssid + "\""
            conf.preSharedKey = "\"" + offer.pass + "\""
            val id = wifi.addNetwork(conf)
            if (id < 0) { fail("addNetwork failed"); return }
            wifi.disconnect(); wifi.enableNetwork(id, true); wifi.reconnect()
            DiagLog.i(tag, "legacy join of " + offer.ssid + " requested (Android " + Build.VERSION.RELEASE + ")")
            var waited = 0L
            main.post(object : Runnable {
                override fun run() {
                    val cur = wifi.connectionInfo?.ssid?.trim('"')
                    if (cur == offer.ssid && wifi.connectionInfo.ipAddress != 0) {
                        if (fsm.networkAvailable(now()) == LinkState.Action.OPEN_SOCKET) { push("connecting (legacy)"); io.execute { openClient(null, offer) } }
                    } else if (waited > STEP_TIMEOUT_MS) fail("legacy join timed out")
                    else { waited += 1000; main.postDelayed(this, 1000) }
                }
            })
        } catch (e: Exception) { fail("legacy join threw " + e) }
    }

    private fun openClient(network: Network?, offer: Wire.Control.WifiOffer) {
        var lastErr = ""
        for (ip in offer.ips) {
            try {
                val s = if (network != null) network.socketFactory.createSocket() else Socket()
                s.connect(InetSocketAddress(InetAddress.getByName(ip), offer.port), 8000)
                DiagLog.i(tag, "TCP connected to " + ip + ":" + offer.port)
                val l = Link(s, isHost = false)
                if (l.handshake()) { main.post { linkUp(l) }; return }
                l.close(); lastErr = "handshake failed"
            } catch (e: Exception) { lastErr = ip + ": " + e.message; DiagLog.w(tag, "connect " + ip + " failed: " + e) }
        }
        main.post { fail("could not connect to host (" + lastErr + ")") }
    }

    // ---- link ---------------------------------------------------------------------------------------

    private fun linkUp(l: Link) {
        val peerShort = l.peerRecord!!.shortId
        val expected = fsm.peer
        if (expected != null && expected != peerShort) { DiagLog.w(tag, "handshake with prok-" + peerShort + " but negotiating with prok-" + expected + " - rejected"); l.close(); fail("wrong peer on link"); return }
        link?.close()
        link = l
        fsm.handshakeOk(peerShort, now())
        listener?.onIdentity(name, l.peerRecord!!.idHex, l.peerRecord!!.pub, l.peerRecord!!.name)
        DiagLog.i(tag, "LINK UP with prok-" + peerShort + " (" + (if (l.isHost) "host" else "client") + ", authenticated by signature)")
        push("UP with prok-" + peerShort)
        io.execute { l.readLoop() }
    }

    private fun fail(reason: String) {
        DiagLog.w(tag, "link failed: " + reason)
        if (fsm.fail(reason, now()) == LinkState.Action.TEARDOWN) teardown(reason) else push(reason)
    }

    private fun teardown(reason: String) {
        link?.close(); link = null
        pendingOffer = null
        netCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }; netCallback = null
        try { serverSocket?.close() } catch (_: Exception) {}; serverSocket = null
        try { reservation?.close() } catch (_: Exception) {}; reservation = null
        if (fsm.state != LinkState.State.DOWN && fsm.state != LinkState.State.IDLE) fsm.fail(reason, now())
        push("down: " + reason)
    }

    override fun sendBatch(peerShort: String, frames: List<Frame>, onEach: (Int, DeliveryResult, String) -> Boolean, onDone: () -> Unit) {
        val l = link
        if (l == null || !fsm.isUp || fsm.peer != peerShort || !l.isOpen) {
            if (frames.isNotEmpty()) onEach(0, DeliveryResult.TRANSPORT_FAILED, "no Wi-Fi link to prok-" + peerShort)
            onDone(); return
        }
        sendExec.execute {
            for ((i, f) in frames.withIndex()) {
                val (res, detail) = l.sendAndWait(f)
                val cont = try { onEach(i, res, detail) } catch (e: Exception) { false }
                if (res == DeliveryResult.TRANSPORT_FAILED) { main.post { fail(detail) }; break }
                if (!cont) break
            }
            onDone()
        }
    }

    /** One authenticated TCP connection. */
    private inner class Link(private val socket: Socket, val isHost: Boolean) {
        private val input = DataInputStream(socket.getInputStream().buffered())
        private val output = DataOutputStream(socket.getOutputStream().buffered())
        var peerRecord: Wire.IdentityRecord? = null
        @Volatile var isOpen = true
        private val receiptLock = Object()
        private var awaitingMsg: ByteArray? = null
        private var receiptStatus = -1

        init { socket.soTimeout = 0; socket.tcpNoDelay = true; socket.keepAlive = true }

        private fun writeFrame(type: Int, payload: ByteArray) {
            synchronized(output) {
                output.writeInt(1 + payload.size); output.writeByte(type); output.write(payload); output.flush()
            }
            sent.addAndGet((5 + payload.size).toLong())
        }

        private fun readFrame(): Pair<Int, ByteArray>? {
            val len = try { input.readInt() } catch (e: Exception) { return null }
            if (len < 1 || len > Wire.MAX_FRAME) return null
            val type = input.readUnsignedByte()
            val p = ByteArray(len - 1); input.readFully(p)
            received.addAndGet((4 + len).toLong())
            return type to p
        }

        fun handshake(): Boolean {
            return try {
                socket.soTimeout = 15_000
                val myNonce = Crypto.randomBytes(Wire.NONCE_LEN)
                writeFrame(Wire.FRAME_HELLO, Wire.hello(identity.idBytes, identity.pubBytes, identity.displayName, myNonce))
                val (t1, p1) = readFrame() ?: return false
                if (t1 != Wire.FRAME_HELLO) return false
                val hello = Wire.parseHello(p1) ?: run { DiagLog.w(tag, "HELLO rejected (bad record)"); return false }
                writeFrame(Wire.FRAME_AUTH, identity.sign(Wire.authData(identity.idBytes, hello.record.id, myNonce, hello.nonce)))
                val (t2, p2) = readFrame() ?: return false
                if (t2 != Wire.FRAME_AUTH) return false
                val ok = Crypto.verify(hello.record.pub, Wire.authData(hello.record.id, identity.idBytes, hello.nonce, myNonce), p2)
                if (!ok) { DiagLog.w(tag, "AUTH signature from prok-" + hello.record.shortId + " INVALID"); return false }
                peerRecord = hello.record
                socket.soTimeout = 0
                true
            } catch (e: Exception) { DiagLog.w(tag, "handshake error: " + e); false }
        }

        fun readLoop() {
            try {
                while (isOpen) {
                    val (type, p) = readFrame() ?: break
                    when (type) {
                        Wire.FRAME_PACKET -> {
                            val pkt = Packet.decode(p)
                            val code = if (pkt == null) Routing.RECEIPT_REJECTED else try { listener?.onFrame(name, peerRecord?.shortId, p) ?: Routing.RECEIPT_REJECTED } catch (e: Exception) { DiagLog.e(tag, "frame handler", e); Routing.RECEIPT_REJECTED }
                            writeFrame(Wire.FRAME_RECEIPT, Wire.receiptPayload(code, pkt?.msgId ?: ByteArray(8)))
                        }
                        Wire.FRAME_RECEIPT -> {
                            val r = Wire.parseReceipt(p) ?: continue
                            synchronized(receiptLock) {
                                if (awaitingMsg != null && r.msgId.contentEquals(awaitingMsg)) { receiptStatus = r.status; receiptLock.notifyAll() }
                            }
                        }
                        else -> {}
                    }
                }
            } catch (e: Exception) { DiagLog.w(tag, "read loop ended: " + e) }
            val wasOpen = isOpen
            close()
            if (wasOpen) main.post { if (link === this) fail("connection closed") }
        }

        fun sendAndWait(f: Frame): Pair<DeliveryResult, String> {
            synchronized(receiptLock) { awaitingMsg = f.msgId; receiptStatus = -1 }
            try { writeFrame(Wire.FRAME_PACKET, f.bytes) } catch (e: Exception) { return DeliveryResult.TRANSPORT_FAILED to ("write failed: " + e.message) }
            val deadline = System.currentTimeMillis() + RECEIPT_TIMEOUT_MS
            synchronized(receiptLock) {
                while (receiptStatus < 0 && isOpen) {
                    val left = deadline - System.currentTimeMillis()
                    if (left <= 0) break
                    receiptLock.wait(left)
                }
                val st = receiptStatus; awaitingMsg = null
                if (st < 0) return (if (isOpen) DeliveryResult.NO_RECEIPT else DeliveryResult.TRANSPORT_FAILED) to "no receipt over Wi-Fi within " + (RECEIPT_TIMEOUT_MS / 1000) + "s"
                return Routing.resultFor(st) to ("Wi-Fi receipt " + st)
            }
        }

        fun close() {
            isOpen = false
            try { socket.close() } catch (_: Exception) {}
            synchronized(receiptLock) { receiptLock.notifyAll() }
        }
    }

    companion object {
        const val PORT = 47741
        const val STEP_TIMEOUT_MS = 45_000L
        const val RECEIPT_TIMEOUT_MS = 15_000L
    }
}
