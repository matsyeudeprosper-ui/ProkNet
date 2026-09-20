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
import net.prok.proknet.core.Crypto
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.Market
import net.prok.proknet.core.MessageStore
import net.prok.proknet.core.StoredSession
import net.prok.proknet.core.TcpFlow
import net.prok.proknet.core.Tcpip
import net.prok.proknet.core.Settlement
import net.prok.proknet.core.Teardown
import net.prok.proknet.core.Tunnel
import net.prok.proknet.core.hexToBytes

/**
 * BUYER side (v0.6 tunnel + v0.7 marketplace). Proposes a signed contract,
 * starts the session under it, runs the user-space TCP flows behind the VPN,
 * verifies and countersigns the seller's usage checkpoints, keeps its own
 * counters, and books the ledger when the session ends.
 *
 * States: DISCONNECTED -> AGREEING -> CONNECTING -> TUNNEL UP -> INTERNET OK | INTERNET LOST -> DISCONNECTED
 */
class TunnelClient(private val identity: Identity, private val hooks: Hooks) {
    interface Hooks {
        fun send(type: Int, streamId: Int, data: ByteArray = ByteArray(0)): Boolean
        fun linkPeer(): String?
        fun linkPeerFullId(): String?
        fun peerPub(peerShort: String): ByteArray?
        fun store(): MessageStore
        fun feePct(): Int
        fun onSessionUp()
        /** v0.9.2: this buy attempt is over and failed; the node clears what it was trying to do. */
        fun onAttemptFailed(reason: String)
        fun onChanged()
    }

    companion object {
        /** v0.14.2: the longest the phone waits for the seller's final signed figure. */
        const val GRACEFUL_STOP_MS = 4_000L
    }

    private val tag = "TUNNEL"
    private val main = Handler(Looper.getMainLooper())
    private val pool = Executors.newCachedThreadPool()
    private val rnd = SecureRandom()

    @Volatile var state = "DISCONNECTED"; private set
    @Volatile var lastError = ""; private set

    // ---- v0.14.2: graceful shutdown, decided by core/Teardown ----
    @Volatile private var teardown = Teardown.State()
    /** True between Stop and the last signed figure. New app traffic is refused in this window. */
    val stopping: Boolean get() = teardown.settling
    /** PASS / waiting / timeout / unavailable / "-", for the diagnostic. */
    val finalCheckpoint: String get() = Teardown.word(teardown.final)
    /** The node closes the VPN and the transport only when this fires. */
    @Volatile var onStopComplete: ((String) -> Unit)? = null
    /** v0.9.17: a new purchase starts from a clean screen, not from the last one's failure. */
    fun clearError() { lastError = "" }
    @Volatile var providerShort: String? = null; private set
    @Volatile var session: Tunnel.Accounting? = null; private set
    @Volatile var upstreamType = Tunnel.UP_NONE; private set
    @Volatile var upstreamValidated = false; private set
    @Volatile var vpnUp = false
    val history = ArrayList<Tunnel.Accounting>()

    // ---- v0.7 marketplace ----
    @Volatile var contract: Market.Contract? = null; private set
    @Volatile var lastAccepted: Market.Checkpoint? = null; private set
    @Volatile var disputed: String = ""; private set
    @Volatile var totalSpentCentimes = 0L; private set
    private var advertisedPrice = 0
    private var reproposed = false

    @Volatile var tunWriter: ((ByteArray) -> Unit)? = null

    private val flows = ConcurrentHashMap<String, TcpFlow>()
    private val flowsById = ConcurrentHashMap<Int, TcpFlow>()
    private val dnsPending = ConcurrentHashMap<Int, Triple<Int, Int, Int>>()
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
            contract?.let { c -> val s = session; if (s != null && s.bytesUp + s.bytesDown > c.maxBytes) { DiagLog.w(tag, "agreed maximum reached on my side"); stop("max MB reached") ; return } }
            main.postDelayed(this, 1000)
        }
    }

    // ---- session: contract first, then the tunnel ------------------------------------------------

    /** Start buying from the link peer at the price it advertised. */
    /**
     * v0.14: the buyer's budget is the contract's ceiling. The rate is the price
     * the seller advertised, in centimes, so billing is exact and the session can
     * never cost more than the person agreed to spend.
     */
    fun startBudget(q: net.prok.proknet.core.Pricing.Quote, sellerPolicy: Int): Boolean {
        val peer = hooks.linkPeer(); val peerFull = hooks.linkPeerFullId()
        if (peer == null || peerFull == null) { setState("DISCONNECTED", "no authenticated Wi-Fi link"); return false }
        if (session != null || contract != null) return true
        if (!q.admissible) { setState("DISCONNECTED", q.reason); lastError = q.reason; return false }
        providerShort = peer
        lastError = ""
        advertisedPrice = net.prok.proknet.core.Pricing.advertisedPriceCfa(q.rateCentimesPerMb)
        reproposed = false
        budgetQuote = q
        val maxMb = minOf(Market.MAX_MB_PER_SESSION.toLong(), (q.maxBillableBytes + Market.MB - 1) / Market.MB).toInt()
        return propose(Market.Contract(Crypto.randomBytes(8), identity.idBytes, peerFull.hexToBytes(), advertisedPrice, 0, maxMb,
            hooks.feePct(), System.currentTimeMillis(), Market.PRICING_VERSION_BUDGET,
            q.rateCentimesPerMb, q.budgetCentimes, q.maxBillableBytes, q.sourceCostPerMb, sellerPolicy, 1))
    }

    /** The quote this session was agreed on, for the budget wording. */
    @Volatile var budgetQuote: net.prok.proknet.core.Pricing.Quote? = null
        internal set

    fun start(advertisedPricePerMb: Int, minPrice: Int = 0, maxMb: Int = 0): Boolean {
        val peer = hooks.linkPeer(); val peerFull = hooks.linkPeerFullId()
        if (peer == null || peerFull == null) { setState("DISCONNECTED", "no authenticated Wi-Fi link"); return false }
        if (session != null || contract != null) return true
        if (!Market.validPrice(advertisedPricePerMb)) { setState("DISCONNECTED", "invalid price"); return false }
        providerShort = peer
        lastError = ""
        advertisedPrice = advertisedPricePerMb
        reproposed = false
        return propose(Market.Contract(Crypto.randomBytes(8), identity.idBytes, peerFull.hexToBytes(), advertisedPricePerMb, minPrice, maxMb, hooks.feePct(), System.currentTimeMillis()))
    }

    private fun propose(c: Market.Contract): Boolean {
        if (!c.valid()) { setState("DISCONNECTED", "contract invalid"); return false }
        val sig = identity.sign(Market.contractSignData(c))
        pendingProposal = c to sig
        setState("AGREEING", if (c.budgetSession)
            "proposing a budget session: " + Market.cfa(c.buyerBudgetCentimes) + " max, " + Market.cfa(c.rateCentimesPerMb.toLong()) + "/MB internal, " +
                Market.mb(c.maxBillableBytes) + " ceiling, fee " + c.feePct + "% -> prok-" + c.sellerShort
            else "proposing " + c.pricePerMb + " CFA/MB, min " + c.minPriceCfa + ", max " + (if (c.maxMb == 0) "unlimited" else c.maxMb.toString() + " MB") + ", fee " + c.feePct + "% -> prok-" + c.sellerShort)
        if (!hooks.send(Tunnel.T_CONTRACT_PROPOSE, 0, Tunnel.signed(c.encode(), sig))) { setState("DISCONNECTED", "cannot write to link"); return false }
        main.postDelayed({ if (contract == null && state == "AGREEING") fail("no contract answer within 15s") }, 15_000)
        return true
    }
    private var pendingProposal: Pair<Market.Contract, ByteArray>? = null

    /**
     * v0.14.2: stopping is two steps, not one.
     *
     * v0.14.1 sent SESSION_END and tore the session down in the same breath, so the
     * final checkpoint the seller issues in reply arrived at a buyer that had already
     * forgotten the contract. A session stopped before the 30 s periodic checkpoint
     * therefore settled at zero, which is both wrong for the seller and an obvious way
     * to get short sessions for nothing.
     *
     * Now: STOPPING -> tell the seller -> it issues the final checkpoint -> we verify and
     * countersign it -> only then does anything close. Bounded, so a peer that has already
     * walked away cannot hold the phone open.
     */
    fun stop(reason: String) {
        if (teardown.settling) { DiagLog.i(tag, "stop already in progress, ignored: " + reason); return }
        val live = session != null || contract != null || state != "DISCONNECTED"
        if (!live) { teardown = Teardown.begin(Teardown.State(), reason, false); notifyStopped(reason); return }
        if (teardown.phase != Teardown.Phase.RUNNING) teardown = Teardown.running(teardown)
        // ask the seller for the closing figure; if the link is gone there is nobody to ask
        val sent = hooks.send(Tunnel.T_SESSION_END, 0, reason.toByteArray(Charsets.UTF_8))
        teardown = Teardown.begin(teardown, reason, sent)
        if (!teardown.settling) { finishStop(reason, "the link was already gone"); return }
        setState("STOPPING", reason)
        val token = teardown.token
        main.postDelayed({
            val next = Teardown.onTimeout(teardown, token)
            if (next !== teardown) { teardown = next; finishStop(reason, "no final checkpoint within " + (GRACEFUL_STOP_MS / 1000) + "s") }
        }, GRACEFUL_STOP_MS)
    }

    /** The graceful window is over, one way or another. Runs exactly once per stop. */
    private fun finishStop(reason: String, why: String) {
        if (endingNow) return
        endingNow = true
        DiagLog.i(tag, "SESSION END: user stopped (" + why + ", final checkpoint " + finalCheckpoint + ")")
        endSession(reason)
        endingNow = false
        notifyStopped(reason)
    }

    /** Teardown may be reached from a timer, a checkpoint and a closing link at once. */
    @Volatile private var endingNow = false

    private fun notifyStopped(reason: String) {
        try { onStopComplete?.invoke(reason) } catch (e: Exception) { DiagLog.w(tag, "stop callback: " + e) }
    }

    fun onLinkClosed(peerShort: String, reason: String) {
        if (providerShort != peerShort) return
        // v0.14.2: the link going away DURING our own graceful stop is the expected end of
        // it, not a fault. Finish quietly rather than writing an error the user never caused.
        if (teardown.settling) { teardown = Teardown.onLinkGone(teardown, reason); finishStop(reason, "the link closed while stopping"); return }
        fail("Wi-Fi link closed: " + reason)
    }

    private fun fail(reason: String) {
        // v0.14.2: we are already closing on purpose; do not turn that into an error
        if (teardown.settling) { teardown = Teardown.onLinkGone(teardown, reason); finishStop(reason, "failed while stopping: " + reason); return }
        lastError = reason
        // v0.14.1: nothing economic may survive into the next attempt
        pendingProposal = null
        budgetQuote = null
        DiagLog.w(tag, "session failed: " + reason)
        endSession(reason)
        hooks.onAttemptFailed(reason)
    }

    private fun endSession(reason: String) {
        if (teardown.settling) teardown = Teardown.onLinkGone(teardown, reason)
        main.removeCallbacks(ticker)
        for (f in flows.values.toList()) synchronized(f) { execute(f, f.abort(reason)) }
        flows.clear(); flowsById.clear(); dnsPending.clear()
        testStreams.values.forEach { it.close() }; testStreams.clear()
        session?.let { it.end(reason); history.add(0, it); if (history.size > 20) history.removeAt(history.size - 1); DiagLog.i(tag, "SESSION END: " + it.summary()) }
        contract?.let { finalizeContract(it, reason) }
        session = null; contract = null; lastAccepted = null; pendingProposal = null
        setState("DISCONNECTED", reason)
    }

    /** Money: the same rule as the seller, from the same signed checkpoint. */
    private fun finalizeContract(c: Market.Contract, reason: String) {
        val store = hooks.store()
        val fin = Market.finalCost(c, lastAccepted)
        val s = session
        store.updateSession(c.sessionHex, status = "ended", endTs = System.currentTimeMillis(), bytesUp = s?.bytesUp ?: 0, bytesDown = s?.bytesDown ?: 0,
            lastSeq = lastAccepted?.seq ?: 0, lastCheckpoint = lastAccepted?.encode(), finalCentimes = fin, reason = reason + (if (disputed.isNotEmpty()) " (disputed: " + disputed + ")" else ""))
        var booked = 0
        for (e in Market.sessionEntries(c, fin, System.currentTimeMillis())) if (store.insertLedger(e)) booked++
        totalSpentCentimes += fin
        // v0.15.0: the obligation, derived from the signed session and nothing else
        Settlement.fromSession(c, lastAccepted, System.currentTimeMillis())?.let { o ->
            val fresh = store.insertSettlementIfNew(o)
            DiagLog.i(tag, "OBLIGATION " + o.settlementId.substring(0, 12) + " " + (if (fresh) "created" else "already known") +
                ": I owe " + Market.cfa(o.buyerOwes) + " to prok-" + o.sellerId.substring(0, 8) +
                " (seller " + Market.cfa(o.sellerNetCentimes) + " + fee " + Market.cfa(o.prokFeeCentimes) + ")")
        }
        DiagLog.i(tag, "SETTLEMENT (buyer view) session " + c.sessionHex.substring(0, 8) + ": signed usage " + Market.mb(lastAccepted?.billable ?: 0) + " -> " + Market.cfa(fin) +
            " (" + (lastAccepted?.let { "checkpoint #" + it.seq } ?: "no signed checkpoint: minimum only") + "), my own count " + Market.mb((s?.bytesUp ?: 0) + (s?.bytesDown ?: 0)) +
            ", ledger entries booked " + booked + (if (disputed.isNotEmpty()) ", DISPUTED: " + disputed else ""))
    }

    private fun setState(s: String, why: String) {
        if (state != s) DiagLog.i(tag, "STATE " + s + " - " + why)
        state = s
        hooks.onChanged()
    }

    // ---- frames from the seller ---------------------------------------------------------------------

    fun onFrame(peerShort: String, f: Tunnel.Frame) {
        if (peerShort != providerShort) return
        when (f.type) {
            Tunnel.T_CONTRACT_ACCEPT -> onAccept(peerShort, f)
            Tunnel.T_CONTRACT_REJECT -> onReject(String(f.data, Charsets.UTF_8))
            Tunnel.T_SESSION_OK -> {
                val ok = Tunnel.parseSessionOk(f.data) ?: return
                val expected = hooks.linkPeerFullId()
                if (expected == null || ok.providerId.joinToString("") { "%02x".format(it) } != expected) { fail("SESSION_OK from an identity that is not the link peer"); return }
                if (session != null) return
                session = Tunnel.Accounting(peerShort, "buyer", sessionStartedAt)
                upstreamType = ok.upstreamType; upstreamValidated = ok.validated
                lastKeepaliveReply = System.currentTimeMillis()
                contract?.let { hooks.store().updateSession(it.sessionHex, status = "active") }
                DiagLog.i(tag, "SESSION OK: seller prok-" + peerShort + " upstream " + Tunnel.upstreamName(ok.upstreamType) + (if (ok.validated) " (validated)" else " (not validated)"))
                markRunning(); setState("TUNNEL UP", "session accepted, starting VPN")
                main.post(ticker)
                hooks.onSessionUp()
            }
            Tunnel.T_SESSION_END -> onSellerEnded(String(f.data, Charsets.UTF_8))
            Tunnel.T_ERROR -> {
                val e = Tunnel.parseError(f.data)
                if (f.streamId == 0) { fail("seller error: " + (e?.message ?: "?")) }
                else {
                    flowsById[f.streamId]?.let { fl -> synchronized(fl) { execute(fl, fl.onStreamFailed(e?.message ?: "error")) } }
                    testStreams[f.streamId]?.fail(e?.message ?: "error")
                    if (dnsPending.remove(f.streamId) != null) DiagLog.w(tag, "dns query " + f.streamId + " failed at seller: " + e?.message)
                }
            }
            Tunnel.T_KEEPALIVE -> { lastKeepaliveReply = System.currentTimeMillis() }
            Tunnel.T_UPSTREAM_STATE -> {
                val u = Tunnel.parseUpstream(f.data) ?: return
                upstreamType = u.type; upstreamValidated = u.validated
                if (!u.available) setState("INTERNET LOST", "seller lost its upstream") else if (state == "INTERNET LOST") setState("TUNNEL UP", "seller upstream back")
            }
            Tunnel.T_USAGE_CHECKPOINT -> onCheckpoint(peerShort, f)
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

    private fun onAccept(peerShort: String, f: Tunnel.Frame) {
        val pp = pendingProposal ?: return
        val sb = Tunnel.parseSigned(f.data, 32) ?: run { fail("malformed CONTRACT_ACCEPT"); return }
        if (!sb.body.contentEquals(pp.first.hash())) { fail("seller accepted a different contract"); return }
        val pub = hooks.peerPub(peerShort) ?: run { fail("seller key unknown"); return }
        if (!Crypto.verify(pub, Market.contractSignData(pp.first), sb.sig)) { fail("seller signature on the contract INVALID"); return }
        val c = pp.first
        contract = c; lastAccepted = null; disputed = ""
        hooks.store().insertSession(StoredSession(c.sessionHex, "buyer", c.encode(), pp.second, sb.sig, "agreed", c.startTs, 0, 0, 0, 0, null, 0, "", peerShort))
        DiagLog.i(tag, "CONTRACT AGREED with prok-" + peerShort + ": session " + c.sessionHex.substring(0, 8) + ", " + c.pricePerMb + " CFA/MB, min " + c.minPriceCfa + " CFA, max " +
            (if (c.maxMb == 0) "unlimited" else c.maxMb.toString() + " MB") + ", fee " + c.feePct + "% (both signatures stored)")
        setState("CONNECTING", "SESSION_START under contract -> prok-" + peerShort)
        sessionStartedAt = System.currentTimeMillis()
        lastKeepaliveReply = sessionStartedAt
        if (!hooks.send(Tunnel.T_SESSION_START, 0, Tunnel.sessionStart(identity.idBytes, c.hash()))) { fail("cannot write to link"); return }
        main.postDelayed({ if (session == null && state == "CONNECTING") fail("no SESSION_OK from seller within 15s") }, 15_000)
    }

    /** The seller's real terms may differ in min/max/fee (not visible in the scan); re-propose once if the PRICE is what was advertised. */
    /** v0.14.1: the last contract rejection, verbatim from the seller, for the diagnostic. */
    @Volatile var lastContractReject = ""
        private set

    private fun onReject(msg: String) {
        lastContractReject = msg
        if (msg.startsWith("terms:") && !reproposed) {
            val p = msg.removePrefix("terms:").split(",").mapNotNull { it.trim().toIntOrNull() }
            if (p.size == 4 && p[0] == advertisedPrice && Market.validMinPrice(p[1]) && Market.validMaxMb(p[2]) && Market.validFee(p[3])) {
                reproposed = true
                val peerFull = hooks.linkPeerFullId() ?: run { fail("no link"); return }
                DiagLog.i(tag, "seller terms: min " + p[1] + " CFA, max " + p[2] + " MB, fee " + p[3] + "% at the advertised " + p[0] + " CFA/MB - re-proposing")
                propose(Market.Contract(Crypto.randomBytes(8), identity.idBytes, peerFull.hexToBytes(), p[0], p[1], p[2], p[3], System.currentTimeMillis()))
                return
            }
            fail("seller wants " + (p.getOrNull(0) ?: "?") + " CFA/MB but advertised " + advertisedPrice + " - refused")
            return
        }
        fail("contract rejected: " + msg)
    }

    private fun onCheckpoint(peerShort: String, f: Tunnel.Frame) {
        val c = contract ?: return
        val s = session ?: return
        val sb = Tunnel.parseSigned(f.data, Market.Checkpoint.LEN) ?: run { hooks.send(Tunnel.T_ERROR, f.streamId, Tunnel.error(Tunnel.ERR_BAD_FRAME, "malformed checkpoint")); return }
        val cp = Market.Checkpoint.decode(sb.body) ?: run { hooks.send(Tunnel.T_ERROR, f.streamId, Tunnel.error(Tunnel.ERR_BAD_FRAME, "malformed checkpoint")); return }
        val pub = hooks.peerPub(peerShort) ?: return
        if (!Crypto.verify(pub, Market.checkpointSignData(cp), sb.sig)) { DiagLog.w(tag, "CHECKPOINT #" + cp.seq + ": seller signature INVALID - ignored"); return }
        val why = Market.validateCheckpoint(cp, c, lastAccepted, s.bytesUp, s.bytesDown)
        if (why != null) {
            if (!why.startsWith("duplicate")) { disputed = "#" + cp.seq + ": " + why; DiagLog.w(tag, "CHECKPOINT #" + cp.seq + " REJECTED: " + why + " (my count up " + s.bytesUp + " down " + s.bytesDown + ")") }
            hooks.send(Tunnel.T_ERROR, cp.seq, Tunnel.error(Tunnel.ERR_BAD_FRAME, why)); return
        }
        val mySig = identity.sign(Market.checkpointSignData(cp))
        hooks.store().insertCheckpoint(c.sessionHex, cp.seq, cp.encode(), sb.sig, mySig, System.currentTimeMillis())
        hooks.store().updateSession(c.sessionHex, bytesUp = cp.bytesUp, bytesDown = cp.bytesDown, lastSeq = cp.seq, lastCheckpoint = cp.encode())
        lastAccepted = cp
        hooks.send(Tunnel.T_USAGE_ACK, cp.seq, Tunnel.signed(cp.encode(), mySig))
        DiagLog.i(tag, "CHECKPOINT #" + cp.seq + (if (cp.final) " (final)" else "") + " verified and countersigned: " + Market.mb(cp.billable) + " = " + Market.cfa(cp.costCentimes) +
            " (my own count " + Market.mb(s.bytesUp + s.bytesDown) + ")")
        hooks.onChanged()
        // v0.14.2: this is what the graceful stop was waiting for. Settle on it now
        // rather than on whatever the last periodic checkpoint happened to be.
        if (cp.final && teardown.settling) {
            teardown = Teardown.onFinalSigned(teardown)
            finishStop(teardown.reason.ifEmpty { "stopped by user" }, "final checkpoint #" + cp.seq + " countersigned")
        }
    }

    /**
     * v0.15.0: the seller ended the session.
     *
     * By the time this arrives the seller has already issued its closing checkpoint and
     * we have countersigned it, so the money is settled on the same figure it would have
     * been had we pressed Stop ourselves. What is left is to end cleanly: close the VPN,
     * and tell the user the provider stopped sharing rather than report a fault they did
     * not cause and cannot fix.
     */
    private fun onSellerEnded(msg: String) {
        val provider = msg.contains(Tunnel.END_PROVIDER_STOPPED)
        if (teardown.settling) {
            // we were stopping too; the seller simply got there first
            teardown = Teardown.onFinalSigned(teardown)
            finishStop(msg, "the provider ended the session first"); return
        }
        teardown = Teardown.begin(Teardown.running(teardown), msg, false, Teardown.Cause.PEER_STOP)
        lastError = if (provider) Tunnel.END_PROVIDER_STOPPED else "seller ended session: " + msg
        DiagLog.i(tag, "SESSION END: the provider ended the session (" + msg + ")")
        endSession(msg)
        // this is what closes the VPN and clears the buy attempt
        hooks.onAttemptFailed(lastError)
    }

    private fun markInternetOk(why: String) { if (state == "TUNNEL UP") setState("INTERNET OK", why) }

    /** v0.14.2: a session is up and owes a closing figure when it ends. */
    internal fun markRunning() { teardown = Teardown.running(teardown) }

    /** Live figures for the UI. */
    fun runningCost(): Long { val c = contract ?: return 0; val s = session ?: return 0; return c.costFor(s.bytesUp + s.bytesDown) }
    fun agreedCost(): Long { val c = contract ?: return 0; return Market.finalCost(c, lastAccepted) }

    // ---- packets from the VPN TUN (unchanged from v0.6) ------------------------------------------

    fun onTunPacket(buf: ByteArray, len: Int) {
        val ip = Tcpip.parseIp4(buf, len) ?: return
        if (session == null) return
        // v0.14.2: once the user has pressed Stop the byte count must stop moving, or the
        // final checkpoint would be signed against a figure that is still changing
        if (stopping) return
        when (ip.protocol) {
            Tcpip.PROTO_TCP -> {
                val t = Tcpip.parseTcp(ip.payload) ?: return
                val key = t.srcPort.toString() + ">" + Tcpip.ipToString(ip.dstIp) + ":" + t.dstPort
                var f = flows[key]
                if (f == null) {
                    if (!t.syn || t.isAck) return
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
                    DiagLog.w(tag, "UDP to port " + u.dstPort + " dropped: only DNS is tunnelled (QUIC falls back to TCP)")
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
            is TcpFlow.Action.Closed -> { flowsById.remove(f.streamId); flows.entries.removeIf { it.value === f } }
        }
    }

    fun activeFlows(): Int = flows.size
    fun flowSummary(): String = flows.values.take(6).joinToString(", ") { Tcpip.ipToString(it.dstIp) + ":" + it.dstPort + "/" + it.state.name.lowercase() }

    // ---- in-app Internet test (unchanged) -----------------------------------------------------------

    class TestResult(val ok: Boolean, val text: String)

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
                if (!bridge.awaitOpen(12_000)) throw Exception("seller could not connect to " + host + ":443 (" + bridge.error + ")")
                sb.append("stream open in ").append(System.currentTimeMillis() - t0).append(" ms via seller\n")
                val plain = Socket()
                plain.connect(InetSocketAddress("127.0.0.1", port), 3000)
                bridge.attach(plain)
                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(plain, host, 443, true) as SSLSocket
                ssl.soTimeout = 15_000
                ssl.startHandshake()
                sb.append("TLS ").append(ssl.session.protocol).append(" ").append(ssl.session.cipherSuite).append(" in ").append(System.currentTimeMillis() - t0).append(" ms\n")
                val out = ssl.outputStream
                out.write(("GET / HTTP/1.1\r\nHost: " + host + "\r\nUser-Agent: ProkNet/0.7\r\nConnection: close\r\n\r\n").toByteArray()); out.flush()
                val input = ssl.inputStream
                val head = ByteArray(4096); var n = 0
                while (n < head.size) { val r = input.read(head, n, head.size - n); if (r < 0) break; n += r; if (String(head, 0, n, Charsets.ISO_8859_1).contains("\r\n\r\n")) break }
                val status = String(head, 0, n, Charsets.ISO_8859_1).lineSequence().firstOrNull() ?: "?"
                var total = n.toLong()
                val rest = ByteArray(8192)
                while (total < 200_000) { val r = input.read(rest); if (r < 0) break; total += r }
                sb.append("HTTP: ").append(status).append("\n").append(total).append(" bytes in ").append(System.currentTimeMillis() - t0).append(" ms")
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
