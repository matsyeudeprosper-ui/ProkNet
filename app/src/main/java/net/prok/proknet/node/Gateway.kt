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
import net.prok.proknet.core.Crypto
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.LinkIo
import net.prok.proknet.core.Market
import net.prok.proknet.core.MessageStore
import net.prok.proknet.core.StoredSession
import net.prok.proknet.core.Tunnel
import net.prok.proknet.core.toHex

/**
 * Internet gateway on the SELLER phone (v0.6 tunnel + v0.7 marketplace).
 * Accepts one session from the peer authenticated on the Wi-Fi link, only
 * under a signed contract with exactly the seller's current terms, makes the
 * real outbound connections on this phone's upstream network (never the
 * ProkNet hotspot), issues signed usage checkpoints, and books the ledger
 * entries when the session ends.
 */
class Gateway(private val context: Context, private val identity: Identity, private val hooks: Hooks) {
    interface Hooks {
        fun send(type: Int, streamId: Int, data: ByteArray = ByteArray(0)): Boolean
        /** Full id of an authenticated peer (the link peer, or v0.9 a buyer sealed end to end through a relay). */
        fun peerFullId(peerShort: String): String?
        fun peerPub(peerShort: String): ByteArray?
        fun store(): MessageStore
        /** Current seller terms: [pricePerMb, minPriceCfa, maxMb, feePct]. */
        fun terms(): IntArray
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
    @Volatile var state = "SELL OFF"
        private set
    @Volatile var lastError = ""
        private set
    val history = ArrayList<Tunnel.Accounting>()
    private var upstreamCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var upstreamView: Tunnel.NetView? = null
    @Volatile private var upstreamNet: Network? = null

    // ---- v0.7 marketplace state ----
    @Volatile var contract: Market.Contract? = null
        private set
    @Volatile var lastIssued: Market.Checkpoint? = null
        private set
    @Volatile var lastSigned: Market.Checkpoint? = null   // countersigned by the buyer
        private set
    private var lastCheckpointAt = 0L
    private var lastCheckpointBytes = 0L
    @Volatile var totalEarnedCentimes = 0L
        private set
    @Volatile var totalSoldBytes = 0L
        private set

    private val ticker = object : Runnable {
        override fun run() {
            if (!providing) return
            for (dead in streams.expire(System.currentTimeMillis())) { DiagLog.i(tag, "stream " + dead.id + " idle, closed"); closeStream(dead.id, notify = true) }
            refreshUpstream("tick")
            checkpointIfDue(false)
            main.postDelayed(this, 10_000)
        }
    }

    // ---- upstream -------------------------------------------------------------------------------

    private fun networks(): List<Pair<Network, Tunnel.NetView>> {
        val out = ArrayList<Pair<Network, Tunnel.NetView>>()
        try {
            for (n in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(n) ?: continue
                val lp = cm.getLinkProperties(n)
                val iface = lp?.interfaceName ?: ""
                val wifi = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                // v0.9.7: p2p interfaces join the list of local-only links that can never be an upstream
                val isProk = wifi && (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) || net.prok.proknet.core.P2pPlan.isLocalLinkIface(iface))
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
            !providing -> "SELL OFF"
            session != null && upstreamView == null -> "INTERNET LOST"
            session != null -> "TUNNEL UP (selling)"
            contract != null -> "CONTRACT AGREED"
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
        refreshUpstream("seller enabled")
        main.postDelayed(ticker, 10_000)
        val t = hooks.terms()
        DiagLog.i(tag, "SELL enabled: " + t[0] + " CFA/MB, min " + t[1] + " CFA, max " + (if (t[2] == 0) "unlimited" else t[2].toString() + " MB") + ", fee " + t[3] + "%, upstream " + upstreamDescription())
        updateState()
    }

    fun stop() {
        if (!providing) return
        endSession("seller disabled")
        contract = null
        providing = false
        upstreamCallback?.let { try { cm.unregisterNetworkCallback(it) } catch (_: Exception) {} }; upstreamCallback = null
        main.removeCallbacks(ticker)
        DiagLog.i(tag, "SELL disabled")
        updateState()
    }

    fun onLinkClosed(peerShort: String, reason: String) { if (buyerShort == peerShort || contract?.buyerShort == peerShort) endSession("link closed: " + reason) }

    private fun endSession(reason: String) {
        val s = session
        val c = contract
        if (s == null && c == null) return
        if (s != null) {
            // best-effort final checkpoint so the buyer can countersign the last figure
            checkpointIfDue(true)
            for (st in streams.closeAll()) closeSocket(st.id)
            s.end(reason)
            history.add(0, s); if (history.size > 20) history.removeAt(history.size - 1)
        }
        if (c != null) finalizeContract(c, reason)
        if (s != null) DiagLog.i(tag, "SESSION END: " + s.summary())
        session = null; buyerShort = null; contract = null; lastIssued = null; lastSigned = null
        updateState()
    }

    /** Money: final cost from the last mutually signed checkpoint; ledger entries booked on this side. */
    private fun finalizeContract(c: Market.Contract, reason: String) {
        val store = hooks.store()
        val fin = Market.finalCost(c, lastSigned)
        val s = session
        store.updateSession(c.sessionHex, status = "ended", endTs = System.currentTimeMillis(), bytesUp = s?.bytesUp ?: 0, bytesDown = s?.bytesDown ?: 0,
            lastSeq = lastSigned?.seq ?: 0, lastCheckpoint = lastSigned?.encode(), finalCentimes = fin, reason = reason)
        val entries = Market.sessionEntries(c, fin, System.currentTimeMillis())
        var booked = 0
        for (e in entries) if (store.insertLedger(e)) booked++
        val split = Market.split(fin, c.feePct)
        totalEarnedCentimes += split.sellerNet; totalSoldBytes += lastSigned?.billable ?: 0L
        DiagLog.i(tag, "SETTLEMENT (seller view) session " + c.sessionHex.substring(0, 8) + ": signed usage " + Market.mb(lastSigned?.billable ?: 0) + " -> " + Market.cfa(fin) +
            " (" + (lastSigned?.let { "checkpoint #" + it.seq } ?: "no signed checkpoint: minimum only") + "), fee " + Market.cfa(split.fee) + ", seller net " + Market.cfa(split.sellerNet) + ", ledger entries booked " + booked)
    }

    // ---- frames from the buyer -------------------------------------------------------------------

    fun onFrame(peerShort: String, f: Tunnel.Frame) {
        val s = session
        val preSession = f.type == Tunnel.T_SESSION_START || f.type == Tunnel.T_CONTRACT_PROPOSE
        if (!preSession && (s == null || buyerShort != peerShort)) {
            if (f.type != Tunnel.T_KEEPALIVE) hooks.send(Tunnel.T_ERROR, 0, Tunnel.error(Tunnel.ERR_SESSION_REFUSED, "no session"))
            return
        }
        when (f.type) {
            Tunnel.T_CONTRACT_PROPOSE -> onProposal(peerShort, f)
            Tunnel.T_SESSION_START -> onSessionStart(peerShort, f)
            Tunnel.T_SESSION_END -> endSession("buyer ended: " + String(f.data, Charsets.UTF_8))
            Tunnel.T_KEEPALIVE -> hooks.send(Tunnel.T_KEEPALIVE, 0, f.data)
            Tunnel.T_OPEN_TCP -> openTcp(f)
            Tunnel.T_TCP_DATA -> { s!!.bytesUp += f.data.size; streams.get(f.streamId)?.let { it.lastActivity = System.currentTimeMillis(); it.bytesIn += f.data.size; writers[f.streamId]?.offer(f.data) }; enforceMax() }
            Tunnel.T_TCP_CLOSE -> { val st = streams.get(f.streamId); if (st != null) { st.remoteClosed = true; writers[f.streamId]?.offer(EOF); if (st.localClosed) closeStream(f.streamId, notify = false) } }
            Tunnel.T_DNS_REQUEST -> dns(f)
            Tunnel.T_USAGE_ACK -> onUsageAck(peerShort, f)
            Tunnel.T_ERROR -> { val e = Tunnel.parseError(f.data); DiagLog.w(tag, "buyer error on stream " + f.streamId + ": " + (e?.message ?: "?")) }
            else -> DiagLog.w(tag, "unexpected " + Tunnel.typeName(f.type) + " from buyer")
        }
    }

    // ---- contract ------------------------------------------------------------------------------------

    private fun onProposal(peerShort: String, f: Tunnel.Frame) {
        if (!providing) { hooks.send(Tunnel.T_CONTRACT_REJECT, 0, "seller not enabled".toByteArray()); return }
        val sb = Tunnel.parseSigned(f.data, Market.Contract.LEN) ?: run { hooks.send(Tunnel.T_CONTRACT_REJECT, 0, "malformed proposal".toByteArray()); return }
        val c = Market.Contract.decode(sb.body) ?: run { hooks.send(Tunnel.T_CONTRACT_REJECT, 0, "malformed contract".toByteArray()); return }
        val linkPeer = hooks.peerFullId(peerShort)
        val buyerPub = hooks.peerPub(peerShort)
        if (linkPeer == null || buyerPub == null) { hooks.send(Tunnel.T_CONTRACT_REJECT, 0, "no authenticated link".toByteArray()); return }
        if (!Crypto.verify(buyerPub, Market.contractSignData(c), sb.sig)) { DiagLog.w(tag, "CONTRACT from prok-" + peerShort + ": buyer signature INVALID"); hooks.send(Tunnel.T_CONTRACT_REJECT, 0, "bad signature".toByteArray()); return }
        val t = hooks.terms()
        val why = Market.acceptableProposal(c, identity.idBytes, linkPeer.let { hex -> ByteArray(16) { i -> hex.substring(i * 2, i * 2 + 2).toInt(16).toByte() } },
            t[0], t[1], t[2], t[3], System.currentTimeMillis(), hooks.store().sessionIds())
        if (why != null) {
            // tell the buyer the real terms so it can re-propose once with them
            val msg = if (why == "terms differ from my offer") "terms:" + t[0] + "," + t[1] + "," + t[2] + "," + t[3] else why
            DiagLog.w(tag, "CONTRACT from prok-" + peerShort + " rejected: " + why)
            hooks.send(Tunnel.T_CONTRACT_REJECT, 0, msg.toByteArray()); return
        }
        if (session != null) endSession("replaced by a new contract")
        val sellerSig = identity.sign(Market.contractSignData(c))
        contract = c; lastIssued = null; lastSigned = null
        hooks.store().insertSession(StoredSession(c.sessionHex, "seller", c.encode(), sb.sig, sellerSig, "agreed", c.startTs, 0, 0, 0, 0, null, 0, "", peerShort))
        hooks.send(Tunnel.T_CONTRACT_ACCEPT, 0, Tunnel.signed(c.hash(), sellerSig))
        DiagLog.i(tag, "CONTRACT AGREED with prok-" + peerShort + ": session " + c.sessionHex.substring(0, 8) + ", " + c.pricePerMb + " CFA/MB, min " + c.minPriceCfa + " CFA, max " +
            (if (c.maxMb == 0) "unlimited" else c.maxMb.toString() + " MB") + ", fee " + c.feePct + "% (both signatures stored)")
        updateState()
    }

    private fun onSessionStart(peerShort: String, f: Tunnel.Frame) {
        val req = Tunnel.parseSessionStart(f.data)
        val linkPeer = hooks.peerFullId(peerShort)
        if (req == null || linkPeer == null || req.buyerId.toHex() != linkPeer) {
            DiagLog.w(tag, "SESSION_START refused: buyer id does not match the authenticated link peer")
            hooks.send(Tunnel.T_ERROR, 0, Tunnel.error(Tunnel.ERR_SESSION_REFUSED, "identity mismatch")); return
        }
        if (!providing) { hooks.send(Tunnel.T_ERROR, 0, Tunnel.error(Tunnel.ERR_SESSION_REFUSED, "seller not enabled")); return }
        val c = contract
        if (c == null || req.contractHash == null || !req.contractHash.contentEquals(c.hash()) || c.buyerShort != peerShort) {
            DiagLog.w(tag, "SESSION_START refused: no agreed contract for this hash")
            hooks.send(Tunnel.T_ERROR, 0, Tunnel.error(Tunnel.ERR_SESSION_REFUSED, "no agreed contract")); return
        }
        refreshUpstream("session start")
        val up = upstreamView
        if (up == null) { hooks.send(Tunnel.T_ERROR, 0, Tunnel.error(Tunnel.ERR_NO_UPSTREAM, "seller has no Internet")); DiagLog.w(tag, "SESSION_START refused: no upstream"); return }
        if (session != null) { for (st in streams.closeAll()) closeSocket(st.id) }
        session = Tunnel.Accounting(peerShort, "seller", System.currentTimeMillis())
        buyerShort = peerShort
        lastCheckpointAt = System.currentTimeMillis(); lastCheckpointBytes = 0
        hooks.store().updateSession(c.sessionHex, status = "active")
        hooks.send(Tunnel.T_SESSION_OK, 0, Tunnel.sessionOk(identity.idBytes, Tunnel.upstreamType(up), up.validated))
        DiagLog.i(tag, "SESSION OK for prok-" + peerShort + " via " + upstreamDescription() + " under contract " + c.sessionHex.substring(0, 8))
        updateState()
    }

    // ---- usage checkpoints ------------------------------------------------------------------------

    private fun checkpointIfDue(final: Boolean) {
        val c = contract ?: return
        val s = session ?: return
        val now = System.currentTimeMillis()
        val billable = s.bytesUp + s.bytesDown
        if (!final && now - lastCheckpointAt < Market.CHECKPOINT_INTERVAL_MS && billable - lastCheckpointBytes < Market.CHECKPOINT_INTERVAL_BYTES) return
        if (!final && billable == lastCheckpointBytes && lastIssued != null) return
        val cp = Market.nextCheckpoint(c, lastIssued?.seq ?: 0, s.bytesUp, s.bytesDown, now, final)
        val sig = identity.sign(Market.checkpointSignData(cp))
        hooks.store().insertCheckpoint(c.sessionHex, cp.seq, cp.encode(), sig, null, now)
        lastIssued = cp; lastCheckpointAt = now; lastCheckpointBytes = billable
        hooks.send(Tunnel.T_USAGE_CHECKPOINT, cp.seq, Tunnel.signed(cp.encode(), sig))
        DiagLog.i(tag, "CHECKPOINT #" + cp.seq + (if (final) " (final)" else "") + " issued: " + Market.mb(cp.billable) + " -> " + Market.cfa(cp.costCentimes))
        hooks.onChanged()
    }

    private fun onUsageAck(peerShort: String, f: Tunnel.Frame) {
        val c = contract ?: return
        val sb = Tunnel.parseSigned(f.data, Market.Checkpoint.LEN) ?: run { DiagLog.w(tag, "malformed USAGE_ACK"); return }
        val cp = Market.Checkpoint.decode(sb.body) ?: return
        val issued = lastIssued
        if (issued == null || !cp.encode().contentEquals(issued.encode())) { DiagLog.w(tag, "USAGE_ACK #" + cp.seq + " does not match the issued checkpoint #" + (issued?.seq ?: 0) + " - ignored"); return }
        if (lastSigned != null && cp.seq <= lastSigned!!.seq) { DiagLog.w(tag, "duplicate USAGE_ACK #" + cp.seq + " ignored"); return }
        val pub = hooks.peerPub(peerShort) ?: return
        if (!Crypto.verify(pub, Market.checkpointSignData(cp), sb.sig)) { DiagLog.w(tag, "USAGE_ACK #" + cp.seq + ": buyer signature INVALID"); return }
        lastSigned = cp
        hooks.store().setCheckpointBuyerSig(c.sessionHex, cp.seq, sb.sig)
        hooks.store().updateSession(c.sessionHex, bytesUp = cp.bytesUp, bytesDown = cp.bytesDown, lastSeq = cp.seq, lastCheckpoint = cp.encode())
        DiagLog.i(tag, "CHECKPOINT #" + cp.seq + " countersigned by buyer: " + Market.mb(cp.billable) + " = " + Market.cfa(cp.costCentimes) + " agreed")
        hooks.onChanged()
    }

    private fun enforceMax() {
        val c = contract ?: return
        val s = session ?: return
        if (s.bytesUp + s.bytesDown > c.maxBytes) {
            DiagLog.w(tag, "agreed maximum of " + c.maxMb + " MB reached: ending session")
            hooks.send(Tunnel.T_SESSION_END, 0, "max MB reached".toByteArray())
            main.post { endSession("max MB reached") }
        }
    }

    /** Live figures for the UI. */
    fun runningCost(): Long { val c = contract ?: return 0; val s = session ?: return 0; return Market.sessionCost(s.bytesUp + s.bytesDown, c.pricePerMb, c.minPriceCfa) }
    fun agreedCost(): Long { val c = contract ?: return 0; return Market.finalCost(c, lastSigned) }

    // ---- streams (unchanged from v0.6) ----------------------------------------------------------

    private fun openTcp(f: Tunnel.Frame) {
        val o = Tunnel.parseOpenTcp(f.data) ?: run { hooks.send(Tunnel.T_ERROR, f.streamId, Tunnel.error(Tunnel.ERR_BAD_FRAME, "bad OPEN_TCP")); return }
        val net = upstreamNet
        if (net == null) { hooks.send(Tunnel.T_ERROR, f.streamId, Tunnel.error(Tunnel.ERR_NO_UPSTREAM, "no upstream")); return }
        val st = streams.open(f.streamId, o.host, o.port, System.currentTimeMillis())
        if (st == null) { hooks.send(Tunnel.T_ERROR, f.streamId, Tunnel.error(Tunnel.ERR_STREAM_LIMIT, "too many streams")); return }
        session?.let { it.streamsOpened++ }
        val q = LinkedBlockingQueue<ByteArray>(); writers[f.streamId] = q
        pool.execute {
            val sock = try {
                val s = net.socketFactory.createSocket()
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(InetAddress.getByName(o.host), o.port), 10_000)
                s
            } catch (e: Exception) {
                DiagLog.w(tag, "stream " + f.streamId + " connect " + o.host + ":" + o.port + " failed: " + LinkIo.describe(e))
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
                if (n >= 8192) main.post { enforceMax() }
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
