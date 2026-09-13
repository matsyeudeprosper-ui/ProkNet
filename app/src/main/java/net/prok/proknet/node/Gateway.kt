package net.prok.proknet.node

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Handler
import android.os.Looper
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.Tunnel

/**
 * Internet gateway on the PROVIDER phone (v0.6). Accepts one tunnel session
 * from the peer authenticated on the Wi-Fi link and makes the real outbound
 * connections on this phone's upstream network (mobile data or home Wi-Fi),
 * never on the ProkNet hotspot.
 *
 *   OPEN_TCP(id, host, port)  -> Socket bound to the upstream Network -> TCP_OPEN_OK / ERROR
 *   TCP_DATA(id)              -> socket write (per-stream writer thread)   socket read -> TCP_DATA(id)
 *   TCP_CLOSE(id)             -> shutdownOutput; EOF from the socket -> TCP_CLOSE(id)
 *   DNS_REQUEST(id, query)    -> UDP to the upstream's DNS server -> DNS_RESPONSE(id)
 *   KEEPALIVE                 -> echoed; UPSTREAM_STATE sent when the upstream changes
 */
class Gateway(private val context: Context, private val identity: Identity, private val hooks: Hooks) {
    interface Hooks {
        fun send(type: Int, streamId: Int, data: ByteArray = ByteArray(0)): Boolean
        fun linkPeerFullId(): String?     // full ID of the peer authenticated on the Wi-Fi link
        fun onChanged()
    }

    private val tag = "GATEWAY"
    private val main = Handler(Looper.getMainLooper())
    private val cm: ConnectivityManager get() = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val pool = Executors.newCachedThreadPool()
    private val streams = Tunnel.StreamTable()
    private val sockets = ConcurrentHashMap<Int, Socket>()
    private val writers = ConcurrentHashMap<Int, LinkedBlockingQueue<ByteArray>>()
    @Volatile var providing = false
        private set
    @Volatile var session: Tunnel.Accounting? = null
        private set
    @Volatile var buyerShort: String? = null
        private set
    @Volatile var state = "PROVIDER OFF"
        private set
    @Volatile var lastError = ""
        private set
    val history = ArrayList<Tunnel.Accounting>()
    private var upstreamCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var upstreamView: Tunnel.NetView? = null
    @Volatile private var upstreamNet: Network? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (!providing) return
            for (dead in streams.expire(System.currentTimeMillis())) { DiagLog.i(tag, "stream " + dead.id + " idle, closed"); closeStream(dead.id, notify = true) }
            refreshUpstream("tick")
            main.postDelayed(this, 10_000)
        }
    }

    // ---- upstream -------------------------------------------------------------------------------

    /** Every network Android knows, as the pure selector needs to see them. */
    private fun networks(): List<Pair<Network, Tunnel.NetView>> {
        val out = ArrayList<Pair<Network, Tunnel.NetView>>()
        try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                val lp = cm.getLinkProperties(n)
                val iface = lp?.interfaceName ?: ""
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                // The ProkNet link: a Wi-Fi network without Internet, or the AP/specifier side. Never an upstream.
                val isProk = wifi && (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || iface.startsWith("ap") || iface.startsWith("swlan"))
                out.add(n to Tunnel.NetView(
                    n.toString() + "/" + iface, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED), caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR), wifi, isProk,
                ))
            }
        } catch (e: Exception) { DiagLog.w(tag, "networks: " + e) }
        return out
    }

    fun refreshUpstream(reason: String) {
        val nets = networks()
        val chosen = Tunnel.chooseUpstream(nets.map { it.second })
        val net = nets.firstOrNull { it.second === chosen }?.first
        val changed = (chosen?.id != upstreamView?.id) || (chosen?.validated != upstreamView?.validated)
        upstreamView = chosen; upstreamNet = net
        if (changed) {
            DiagLog.i(tag, "UPSTREAM " + (if (chosen == null) "NONE" else Tunnel.upstreamName(Tunnel.upstreamType(chosen)) + " " + chosen.id + (if (chosen.validated) " (validated Internet)" else " (NOT validated)")) +
                " [" + reason + "] all=" + nets.joinToString { it.second.id + (if (it.second.internet) ":inet" else "") + (if (it.second.validated) ":ok" else "") + (if (it.second.isProkNetLink) ":PROK" else "") })
            if (session != null) hooks.send(Tunnel.T_UPSTREAM_STATE, 0, Tunnel.upstreamState(chosen != null, Tunnel.upstreamType(chosen), chosen?.validated == true))
            updateState()
        }
    }

    val upstream: Tunnel.NetView? get() = upstreamView
    val upstreamReady: Boolean get() = upstreamView != null
    fun upstreamDescription(): String = upstreamView?.let { Tunnel.upstreamName(Tunnel.upstreamType(it)) + (if (it.validated) ", validated" else ", not validated") } ?: "none"

    private fun updateState() {
        state = when {
            !providing -> "PROVIDER OFF"
            session != null && upstreamView == null -> "INTERNET LOST"
            session != null -> "TUNNEL UP"
            upstreamView != null -> "PROVIDER READY"
            else -> "NO UPSTREAM"
        }
        hooks.onChanged()
    }

    // ---- lifecycle --------------------------------------------------------------------------------

    fun start() {
        if (providing) return
        providing = true
        try {
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) { main.post { refreshUpstream("network available") } }
                override fun onLost(network: Network) { main.post { refreshUpstream("network lost") } }
                override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { main.post { refreshUpstream("capabilities changed") } }
            }
            cm.registerNetworkCallback(NetworkRequest.Builder().addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET).build(), cb)
            upstreamCallback = cb
        } catch (e: Exception) { DiagLog.w(tag, "network callback: " + e) }
        refreshUpstream("provider enabled")
        main.postDelayed(ticker, 10_000)
        DiagLog.i(tag, "PROVIDER enabled: upstream " + upstreamDescription())
        updateState()
    }

    fun stop() {
        if (!providing) return
        endSession("provider disabled")
        providing = false
        upstreamCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }; upstreamCallback = null
        main.removeCallbacks(ticker)
        DiagLog.i(tag, "PROVIDER disabled")
        updateState()
    }

    /** The Wi-Fi link went down: everything dies with it. */
    fun onLinkClosed(peerShort: String, reason: String) { if (buyerShort == peerShort) endSession("link closed: " + reason) }

    private fun endSession(reason: String) {
        val s = session ?: return
        for (st in streams.closeAll()) closeSocket(st.id)
        s.end(reason)
        history.add(0, s); if (history.size > 20) history.removeAt(history.size - 1)
        DiagLog.i(tag, "SESSION END: " + s.summary())
        session = null; buyerShort = null
        updateState()
    }

    // ---- frames from the buyer -------------------------------------------------------------------

    fun onFrame(peerShort: String, f: Tunnel.Frame) {
        val s = session
        if (f.type != Tunnel.T_SESSION_START && (s == null || buyerShort != peerShort)) {
            if (f.type != Tunnel.T_KEEPALIVE) hooks.send(Tunnel.T_ERROR, 0, Tunnel.error(Tunnel.ERR_SESSION_REFUSED, "no session"))
            return
        }
        when (f.type) {
            Tunnel.T_SESSION_START -> onSessionStart(peerShort, f)
            Tunnel.T_SESSION_END -> endSession("buyer ended: " + String(f.data, Charsets.UTF_8))
            Tunnel.T_KEEPALIVE -> hooks.send(Tunnel.T_KEEPALIVE, 0, f.data)
            Tunnel.T_OPEN_TCP -> openTcp(f)
            Tunnel.T_TCP_DATA -> { s!!.bytesUp += f.data.size; streams.get(f.streamId)?.let { it.lastActivity = System.currentTimeMillis(); it.bytesIn += f.data.size; writers[f.streamId]?.offer(f.data) } }
            Tunnel.T_TCP_CLOSE -> { val st = streams.get(f.streamId); if (st != null) { st.remoteClosed = true; writers[f.streamId]?.offer(EOF); if (st.localClosed) closeStream(f.streamId, notify = false) } }
            Tunnel.T_DNS_REQUEST -> dns(f)
            else -> DiagLog.w(tag, "unexpected " + Tunnel.typeName(f.type) + " from buyer")
        }
    }

    private fun onSessionStart(peerShort: String, f: Tunnel.Frame) {
        val req = Tunnel.parseSessionStart(f.data)
        val linkPeer = hooks.linkPeerFullId()
        if (req == null || linkPeer == null || req.buyerId.let { it.joinToString("") { b -> "%02x".format(b) } } != linkPeer) {
            DiagLog.w(tag, "SESSION_START refused: buyer id does not match the authenticated link peer")
            hooks.send(Tunnel.T_ERROR, 0, Tunnel.error(Tunnel.ERR_SESSION_REFUSED, "identity mismatch")); return
        }
        if (!providing) { hooks.send(Tunnel.T_ERROR, 0, Tunnel.error(Tunnel.ERR_SESSION_REFUSED, "provider not enabled")); return }
        refreshUpstream("session start")
        val up = upstreamView
        if (up == null) { hooks.send(Tunnel.T_ERROR, 0, Tunnel.error(Tunnel.ERR_NO_UPSTREAM, "provider has no Internet")); DiagLog.w(tag, "SESSION_START refused: no upstream"); return }
        if (session != null) endSession("replaced by new session")
        session = Tunnel.Accounting(peerShort, "provider", System.currentTimeMillis())
        buyerShort = peerShort
        hooks.send(Tunnel.T_SESSION_OK, 0, Tunnel.sessionOk(identity.idBytes, Tunnel.upstreamType(up), up.validated))
        DiagLog.i(tag, "SESSION OK for prok-" + peerShort + " via " + upstreamDescription())
        updateState()
    }

    private fun openTcp(f: Tunnel.Frame) {
        val o = Tunnel.parseOpenTcp(f.data) ?: run { hooks.send(Tunnel.T_ERROR, f.streamId, Tunnel.error(Tunnel.ERR_BAD_FRAME, "bad OPEN_TCP")); return }
        val net = upstreamNet
        if (net == null) { hooks.send(Tunnel.T_ERROR, f.streamId, Tunnel.error(Tunnel.ERR_NO_UPSTREAM, "no upstream")); return }
        val st = streams.open(f.streamId, o.host, o.port, System.currentTimeMillis())
        if (st == null) { hooks.send(Tunnel.T_ERROR, f.streamId, Tunnel.error(Tunnel.ERR_STREAM_LIMIT, "too many streams")); return }
        session?.let { it.streamsOpened++ }
        val q = LinkBlockingQueue(); writers[f.streamId] = q
        pool.execute {
            val sock = try {
                val s = net.socketFactory.createSocket()   // bound to the upstream network: never the hotspot
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(InetAddress.getByName(o.host), o.port), 10_000)
                s
            } catch (e: Exception) {
                DiagLog.w(tag, "stream " + f.streamId + " connect " + o.host + ":" + o.port + " failed: " + e.message)
                hooks.send(Tunnel.T_ERROR, f.streamId, Tunnel.error(Tunnel.ERR_CONNECT_FAILED, e.message ?: "connect failed"))
                streams.close(f.streamId); writers.remove(f.streamId); return@execute
            }
            sockets[f.streamId] = sock
            if (!hooks.send(Tunnel.T_TCP_OPEN_OK, f.streamId)) { closeStream(f.streamId, notify = false); return@execute }
            if (streams.count() <= 3 || f.streamId % 20 == 0) DiagLog.i(tag, "stream " + f.streamId + " open to " + o.host + ":" + o.port + " (" + streams.count() + " active)")
            pool.execute { writerLoop(f.streamId, sock, q) }
            readerLoop(f.streamId, sock)
        }
    }

    private class LinkBlockingQueue : LinkedBlockingQueue<ByteArray>()
    private val EOF = ByteArray(0)

    private fun writerLoop(id: Int, sock: Socket, q: LinkedBlockingQueue<ByteArray>) {
        try {
            val out = sock.getOutputStream()
            while (true) {
                val b = q.take()
                if (b === EOF) { try { sock.shutdownOutput() } catch (_: Exception) {}; break }
                out.write(b); out.flush()
                streams.get(id)?.let { it.bytesOut += b.size }
            }
        } catch (e: Exception) { closeStream(id, notify = true) }
    }

    private fun readerLoop(id: Int, sock: Socket) {
        val buf = ByteArray(Tunnel.MAX_DATA)
        try {
            val input = sock.getInputStream()
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                val st = streams.get(id) ?: break
                st.lastActivity = System.currentTimeMillis()
                session?.let { it.bytesDown += n }
                if (!hooks.send(Tunnel.T_TCP_DATA, id, buf.copyOf(n))) break
            }
            val st = streams.get(id)
            if (st != null) { st.localClosed = true; hooks.send(Tunnel.T_TCP_CLOSE, id); if (st.remoteClosed) closeStream(id, notify = false) }
        } catch (e: Exception) {
            if (streams.get(id) != null) closeStream(id, notify = true)
        }
    }

    private fun closeStream(id: Int, notify: Boolean) {
        val st = streams.close(id)
        closeSocket(id)
        if (st != null && notify) hooks.send(Tunnel.T_TCP_CLOSE, id)
    }

    private fun closeSocket(id: Int) {
        sockets.remove(id)?.let { try { it.close() } catch (_: Exception) {} }
        writers.remove(id)?.offer(EOF)
    }

    private fun dns(f: Tunnel.Frame) {
        val net = upstreamNet ?: run { hooks.send(Tunnel.T_ERROR, f.streamId, Tunnel.error(Tunnel.ERR_NO_UPSTREAM, "no upstream")); return }
        session?.let { it.dnsQueries++; it.bytesUp += f.data.size }
        pool.execute {
            var ds: DatagramSocket? = null
            try {
                val servers = (cm.getLinkProperties(net)?.dnsServers ?: emptyList()).filterIsInstance<java.net.Inet4Address>().map { it.hostAddress!! }
                    .ifEmpty { listOf("8.8.8.8", "1.1.1.1") }
                ds = DatagramSocket()
                net.bindSocket(ds)
                ds.soTimeout = 5000
                for (srv in servers.take(2)) {
                    try {
                        ds.send(DatagramPacket(f.data, f.data.size, InetAddress.getByName(srv), 53))
                        val resp = DatagramPacket(ByteArray(4096), 4096)
                        ds.receive(resp)
                        val bytes = resp.data.copyOf(resp.length)
                        session?.let { it.bytesDown += bytes.size }
                        hooks.send(Tunnel.T_DNS_RESPONSE, f.streamId, bytes)
                        return@execute
                    } catch (e: Exception) { DiagLog.w(tag, "dns via " + srv + " failed: " + e.message) }
                }
                hooks.send(Tunnel.T_ERROR, f.streamId, Tunnel.error(Tunnel.ERR_CONNECT_FAILED, "dns timeout"))
            } catch (e: Exception) {
                hooks.send(Tunnel.T_ERROR, f.streamId, Tunnel.error(Tunnel.ERR_CONNECT_FAILED, "dns: " + e.message))
            } finally { try { ds?.close() } catch (_: Exception) {} }
        }
    }

    fun activeStreams(): Int = streams.count()
    fun streamSummary(): String = streams.ids().take(8).mapNotNull { streams.get(it) }.joinToString(", ") { it.host + ":" + it.port }
}
