package net.prok.proknet.node

import android.os.Handler
import android.os.Looper
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.TcpFlow
import net.prok.proknet.core.Tcpip
import net.prok.proknet.core.Tunnel

/**
 * BUYER side of the Internet tunnel (v0.6). Owns the session with the
 * provider, the user-space TCP flows behind the VPN TUN, DNS relaying and
 * the in-app Internet test. Frames go out through the authenticated Wi-Fi
 * link; packets go in and out through the VPN service.
 *
 * States: DISCONNECTED -> CONNECTING -> TUNNEL UP -> INTERNET OK | INTERNET LOST -> DISCONNECTED
 */
class TunnelClient(private val identity: Identity, private val hooks: Hooks) {
    interface Hooks {
        fun send(type: Int, streamId: Int, data: ByteArray = ByteArray(0)): Boolean
        fun linkPeer(): String?          // short id of the peer authenticated on the Wi-Fi link
        fun linkPeerFullId(): String?
        fun onSessionUp()                // start the VPN now
        fun onChanged()
    }

    private val tag = "TUNNEL"
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private val rnd = SecureRandom()

    @Volatile var state = "DISCONNECTED"; private set
    @Volatile var lastError = ""; private set
    @Volatile var providerShort: String? = null; private set
    @Volatile var session: Tunnel.Accounting? = null; private set
    @Volatile var upstreamType = Tunnel.UP_NONE; private set
    @Volatile var upstreamValidated = false; private set
    @Volatile var vpnUp = false
    val history = ArrayList<Tunnel.Accounting>()

    /** Set by the VPN service: how to write an IPv4 packet to the TUN. */
    @Volatile var tunWriter: ((ByteArray) -> Unit)? = null

    private val flows = ConcurrentHashMap<String, TcpFlow>()      // key "srcPort>dst:port"
    private val flowsById = ConcurrentHashMap<Int, TcpFlow>()
    private val dnsPending = ConcurrentHashMap<Int, Triple<Int, Int, Int>>() // id -> (srcIp, srcPort, dstIp)
    private val testStreams = ConcurrentHashMap<Int, LocalBridge>()
    private var nextStreamId = 1
    private var keepaliveSeq = 0
    private var lastKeepaliveSent = 0L
    @Volatile private var lastKeepaliveReply = 0L
    private var sessionStartedAt = 0L
    private var droppedUdpLogged = HashSet<Int>()
    @Volatile var dnsCount = 0; private set

    private val ticker = object : Runnable {
        override fun run() {
            if (session == null) return
            val now = System.currentTimeMillis()
            for (f in flows.values.toList()) synchronized(f) { execute(f, f.onTick(now)) }
            if (now - lastKeepaliveReply > Tunnel.KEEPALIVE_TIMEOUT_MS) { fail("provider not answering keepalives"); return }
            if (now - lastKeepaliveSent >= Tunnel.KEEPALIVE_MS) { lastKeepaliveSent = now; hooks.send(Tunnel.T_KEEPALIVE, 0, Tunnel.keepalive(++keepaliveSeq)) }
            main.postDelayed(this, 1000)
        }
    }

    // ---- session -------------------------------------------------------------------------------

    fun start(): Boolean {
        val peer = hooks.linkPeer()
        if (peer == null) { setState("DISCONNECTED", "no authenticated Wi-Fi link"); return false }
        if (session != null) return true
        providerShort = peer
        setState("CONNECTING", "SESSION_START -> prok-" + peer)
        sessionStartedAt = System.currentTimeMillis()
        lastKeepaliveReply = sessionStartedAt
        if (!hooks.send(Tunnel.T_SESSION_START, 0, Tunnel.sessionStart(identity.idBytes))) { setState("DISCONNECTED", "cannot write to link"); return false }
        main.postDelayed({ if (session == null && state == "CONNECTING") fail("no SESSION_OK from provider within 15s") }, 15_000)
        return true
    }

    fun stop(reason: String) {
        if (session == null && state == "DISCONNECTED") return
        hooks.send(Tunnel.T_SESSION_END, 0, reason.toByteArray(Charsets.UTF_8))
        endSession(reason)
    }

    fun onLinkClosed(peerShort: String, reason: String) { if (providerShort == peerShort) fail("Wi-Fi link closed: " + reason) }

    private fun fail(reason: String) { lastError = reason; DiagLog.w(tag, "session failed: " + reason); endSession(reason) }

    private fun endSession(reason: String) {
        main.removeCallbacks(ticker)
        for (f in flows.values.toList()) synchronized(f) { execute(f, f.abort(reason)) }
        flows.clear(); flowsById.clear(); dnsPending.clear()
        testStreams.values.forEach { it.close() }; testStreams.clear()
        session?.let { it.end(reason); history.add(0, it); if (history.size > 20) history.removeAt(history.size - 1); DiagLog.i(tag, "SESSION END: " + it.summary()) }
        session = null
        setState("DISCONNECTED", reason)
    }

    private fun setState(s: String, why: String) {
        if (state != s) DiagLog.i(tag, "STATE " + s + " - " + why)
        state = s
        hooks.onChanged()
    }

    // ---- frames from the provider -----------------------------------------------------------------

    fun onFrame(peerShort: String, f: Tunnel.Frame) {
        if (peerShort != providerShort) return
        when (f.type) {
            Tunnel.T_SESSION_OK -> {
                val ok = Tunnel.parseSessionOk(f.data) ?: return
                val expected = hooks.linkPeerFullId()
                if (expected == null || ok.providerId.joinToString("") { "%02x".format(it) } != expected) { fail("SESSION_OK from an identity that is not the link peer"); return }
                if (session != null) return
                session = Tunnel.Accounting(peerShort, "buyer", sessionStartedAt)
                upstreamType = ok.upstreamType; upstreamValidated = ok.validated
                lastKeepaliveReply = System.currentTimeMillis()
                DiagLog.i(tag, "SESSION OK: provider prok-" + peerShort + " upstream " + Tunnel.upstreamName(ok.upstreamType) + (if (ok.validated) " (validated)" else " (not validated)"))
                setState("TUNNEL UP", "session accepted, starting VPN")
                main.post(ticker)
                hooks.onSessionUp()
            }
            Tunnel.T_SESSION_END -> fail("provider ended session: " + String(f.data, Charsets.UTF_8))
            Tunnel.T_ERROR -> {
                val e = Tunnel.parseError(f.data)
                if (f.streamId == 0) { fail("provider error: " + (e?.message ?: "?")) }
                else {
                    flowsById[f.streamId]?.let { fl -> synchronized(fl) { execute(fl, fl.onStreamFailed(e?.message ?: "error")) } }
                    testStreams[f.streamId]?.fail(e?.message ?: "error")
                    if (dnsPending.remove(f.streamId) != null) DiagLog.w(tag, "dns query " + f.streamId + " failed at provider: " + e?.message)
                }
            }
            Tunnel.T_KEEPALIVE -> { lastKeepaliveReply = System.currentTimeMillis() }
            Tunnel.T_UPSTREAM_STATE -> {
                val u = Tunnel.parseUpstream(f.data) ?: return
                upstreamType = u.type; upstreamValidated = u.validated
                if (!u.available) setState("INTERNET LOST", "provider lost its upstream") else if (state == "INTERNET LOST") setState("TUNNEL UP", "provider upstream back")
            }
            Tunnel.T_TCP_OPEN_OK -> {
                lastKeepaliveReply = System.currentTimeMillis()
                flowsById[f.streamId]?.let { fl -> synchronized(fl) { execute(fl, fl.onStreamOpened()) }; markInternetOk("stream " + f.streamId + " open") }
                testStreams[f.streamId]?.opened()
            }
            Tunnel.T_TCP_DATA -> {
                lastKeepaliveReply = System.currentTimeMillis()
                session?.let { it.bytesDown += f.data.size }
                flowsById[f.streamId]?.let { fl -> synchronized(fl) { execute(fl, fl.onStreamData(f.data, System.currentTimeMillis())) } }
                testStreams[f.streamId]?.data(f.data)
            }
            Tunnel.T_TCP_CLOSE -> {
                flowsById[f.streamId]?.let { fl -> synchronized(fl) { execute(fl, fl.onStreamClosed()) } }
                testStreams[f.streamId]?.remoteClosed()
            }
            Tunnel.T_DNS_RESPONSE -> {
                lastKeepaliveReply = System.currentTimeMillis()
                session?.let { it.bytesDown += f.data.size }
                val p = dnsPending.remove(f.streamId) ?: return
                markInternetOk("dns answered")
                tunWriter?.invoke(Tcpip.buildUdp(p.third, p.first, 53, p.second, f.data, ipId = rnd.nextInt(65535)))
            }
            else -> {}
        }
    }

    private fun markInternetOk(why: String) { if (state == "TUNNEL UP") setState("INTERNET OK", why) }

    // ---- packets from the VPN TUN -------------------------------------------------------------------

    fun onTunPacket(buf: ByteArray, len: Int) {
        val ip = Tcpip.parseIp4(buf, len) ?: return
        if (session == null) return
        when (ip.protocol) {
            Tcpip.PROTO_TCP -> {
                val t = Tcpip.parseTcp(ip.payload) ?: return
                val key = t.srcPort.toString() + ">" + Tcpip.ipToString(ip.dstIp) + ":" + t.dstPort
                var f = flows[key]
                if (f == null) {
                    if (!t.syn || t.isAck) return // stray segment for an unknown flow
                    val id = synchronized(this) { nextStreamId++ }
                    f = TcpFlow(ip.srcIp, t.srcPort, ip.dstIp, t.dstPort, id, ourIsn = (rnd.nextInt().toLong() and 0xFFFFFFFFL))
                    flows[key] = f; flowsById[id] = f
                    session?.let { it.streamsOpened++ }
                }
                synchronized(f) { execute(f, f.onSegment(t, System.currentTimeMillis())) }
            }
            Tcpip.PROTO_UDP -> {
                val u = Tcpip.parseUdp(ip.payload) ?: return
                if (u.dstPort == 53) {
                    val id = synchronized(this) { nextStreamId++ }
                    dnsPending[id] = Triple(ip.srcIp, u.srcPort, ip.dstIp)
                    dnsCount++
                    session?.let { it.dnsQueries++; it.bytesUp += u.payload.size }
                    if (!hooks.send(Tunnel.T_DNS_REQUEST, id, u.payload)) dnsPending.remove(id)
                    if (dnsPending.size > 200) dnsPending.keys.take(50).forEach { dnsPending.remove(it) }
                } else if (droppedUdpLogged.add(u.dstPort) && droppedUdpLogged.size < 12) {
                    DiagLog.w(tag, "UDP to port " + u.dstPort + " dropped: only DNS is tunnelled in v0.6 (QUIC falls back to TCP)")
                }
            }
            else -> {}
        }
    }

    private fun execute(f: TcpFlow, actions: List<TcpFlow.Action>) {
        for (a in actions) when (a) {
            is TcpFlow.Action.ToTun -> tunWriter?.invoke(a.packet)
            is TcpFlow.Action.OpenStream -> hooks.send(Tunnel.T_OPEN_TCP, f.streamId, Tunnel.openTcp(a.host, a.port))
            is TcpFlow.Action.StreamData -> { session?.let { it.bytesUp += a.bytes.size }; hooks.send(Tunnel.T_TCP_DATA, f.streamId, a.bytes) }
            is TcpFlow.Action.CloseStream -> hooks.send(Tunnel.T_TCP_CLOSE, f.streamId)
            is TcpFlow.Action.Closed -> {
                flowsById.remove(f.streamId)
                flows.entries.removeIf { it.value === f }
            }
        }
    }

    fun activeFlows(): Int = flows.size
    fun flowSummary(): String = flows.values.take(6).joinToString(", ") { Tcpip.ipToString(it.dstIp) + ":" + it.dstPort + "/" + it.state.name.lowercase() }

    // ---- in-app Internet test (through the tunnel, independent of the VPN) ---------------------------

    class TestResult(val ok: Boolean, val text: String)

    /**
     * 1) DNS for [host] through the provider; 2) real TLS handshake + HTTP GET to [host]:443 through a
     * tunnel stream, bridged over a loopback socket so the platform TLS stack can be used unchanged.
     */
    fun internetTest(host: String, cb: (TestResult) -> Unit) {
        if (session == null) { cb(TestResult(false, "no session")); return }
        pool.execute {
            val sb = StringBuilder()
            var ok = false
            try {
                val t0 = System.currentTimeMillis()
                val id = synchronized(this) { nextStreamId++ }
                val bridge = LocalBridge(id, host, 443)
                testStreams[id] = bridge
                val port = bridge.listen()
                if (!hooks.send(Tunnel.T_OPEN_TCP, id, Tunnel.openTcp(host, 443))) throw Exception("cannot send OPEN_TCP")
                if (!bridge.awaitOpen(12_000)) throw Exception("provider could not connect to " + host + ":443 (" + bridge.error + ")")
                val tOpen = System.currentTimeMillis() - t0
                sb.append("stream open in ").append(tOpen).append(" ms via provider\n")
                val plain = Socket()
                plain.connect(InetSocketAddress("127.0.0.1", port), 3000)
                bridge.attach(plain)
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, 443, true) as SSLSocket
                ssl.soTimeout = 15_000
                ssl.startHandshake()
                val tTls = System.currentTimeMillis() - t0
                sb.append("TLS ").append(ssl.session.protocol).append(" ").append(ssl.session.cipherSuite).append(" in ").append(tTls).append(" ms\n")
                val out = ssl.outputStream
                out.write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: ProkNet/0.6\r\nConnection: close\r\n\r\n").toByteArray()); out.flush()
                val input = ssl.inputStream
                val head = ByteArray(4096); var n = 0
                while (n < head.size) { val r = input.read(head, n, head.size - n); if (r < 0) break; n += r; if (String(head, 0, n, Charsets.ISO_8859_1).contains("\r\n\r\n")) break }
                val status = String(head, 0, n, Charsets.ISO_8859_1).lineSequence().firstOrNull() ?: "?"
                var total = n.toLong()
                val rest = ByteArray(8192)
                while (total < 200_000) { val r = input.read(rest); if (r < 0) break; total += r }
                val tAll = System.currentTimeMillis() - t0
                sb.append("HTTP: ").append(status).append("\n").append(total).append(" bytes in ").append(tAll).append(" ms")
                ok = status.startsWith("HTTP/")
                try { ssl.close() } catch (_: Exception) {}
                if (ok) markInternetOk("https test")
            } catch (e: Exception) {
                sb.append("FAILED: ").append(e.message)
            }
            DiagLog.i(tag, "INTERNET TEST " + host + ": " + sb.toString().replace("\n", " | "))
            cb(TestResult(ok, sb.toString()))
        }
    }

    /** Bridges a tunnel stream to a loopback TCP socket so standard Java networking (TLS) can use it. */
    private inner class LocalBridge(val id: Int, val host: String, val port: Int) {
        private val server = ServerSocket()
        private val openLatch = CountDownLatch(1)
        @Volatile var error = ""
        @Volatile private var openedOk = false
        private val toLocal = LinkedBlockingQueue<ByteArray>()
        private var local: Socket? = null
        @Volatile private var closed = false
        private val EOF = ByteArray(0)

        fun listen(): Int { server.bind(InetSocketAddress("127.0.0.1", 0)); return server.localPort }
        fun opened() { openedOk = true; openLatch.countDown() }
        fun fail(msg: String) { error = msg; openLatch.countDown(); close() }
        fun awaitOpen(ms: Long): Boolean { openLatch.await(ms, TimeUnit.MILLISECONDS); return openedOk }
        fun data(b: ByteArray) { toLocal.offer(b) }
        fun remoteClosed() { toLocal.offer(EOF) }

        /** Accept the loopback client and pump both directions. */
        fun attach(client: Socket) {
            val s = server.accept(); local = s
            pool.execute {
                try { val out: OutputStream = s.getOutputStream(); while (!closed) { val b = toLocal.take(); if (b === EOF) break; out.write(b); out.flush() } } catch (_: Exception) {}
                try { s.shutdownOutput() } catch (_: Exception) {}
            }
            pool.execute {
                try { val input: InputStream = s.getInputStream(); val buf = ByteArray(Tunnel.MAX_DATA); while (!closed) { val n = input.read(buf); if (n < 0) break; session?.let { it.bytesUp += n }; hooks.send(Tunnel.T_TCP_DATA, id, buf.copyOf(n)) } } catch (_: Exception) {}
                hooks.send(Tunnel.T_TCP_CLOSE, id)
            }
        }

        fun close() {
            closed = true; toLocal.offer(EOF)
            try { local?.close() } catch (_: Exception) {}
            try { server.close() } catch (_: Exception) {}
            testStreams.remove(id)
        }
    }
}
