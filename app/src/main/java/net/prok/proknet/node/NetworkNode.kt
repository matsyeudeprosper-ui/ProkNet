package net.prok.proknet.node

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import net.prok.proknet.ble.Peer
import net.prok.proknet.ble.ProkNetNode
import net.prok.proknet.core.CoverageModel
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Market
import net.prok.proknet.core.NetRequest
import net.prok.proknet.core.ControlRetry
import net.prok.proknet.core.ProviderActivation
import net.prok.proknet.core.ProviderInbox
import net.prok.proknet.core.RequestGossip
import net.prok.proknet.core.SyncProtocol
import net.prok.proknet.core.Tunnel
import net.prok.proknet.core.Wire
import net.prok.proknet.core.toHex

/**
 * v0.13: the phone's part of the ProkNet network brain.
 *
 * - Requests: this phone's own signed requests and the ones it carries for
 *   others ([RequestGossip]), forwarded once to each peer over the existing
 *   encrypted BLE control channel, persisted, swept.
 * - Provider activation: a carried request meets this phone's eligibility
 *   ([ProviderActivation]); an opportunity becomes a notification through
 *   [opportunityHook] (the service), rate-limited.
 * - The brain: one signed, batched, idempotent sync ([SyncProtocol]) when a
 *   server is configured, opportunistic, with backoff. Nothing here is
 *   required for the direct local path.
 */
class NetworkNode(private val context: Context, private val node: ProkNetNode, private val cover: CoverageEngine) : ProkNetNode.Listener {
    private val tag = "NET"
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val prefs = context.getSharedPreferences("proknet_network", Context.MODE_PRIVATE)
    private val file = File(context.filesDir, "requests.v1.txt")

    @Volatile var state: RequestGossip.State = RequestGossip.State()
        private set
    /** v0.13.3: the one source of truth behind the notification AND the Gagner card. */
    @Volatile var inbox: ProviderInbox.State = ProviderInbox.State()

        private set

    /**
     * v0.17.1: the network's own events, for Activité. Durable, deduplicated, and not in
     * the SQLite database - this is coordination history, not signed evidence, and
     * `RequestGossip` and `ProviderInbox` already keep their state this way.
     */
    @Volatile var history: net.prok.proknet.core.NetworkHistory.State =
        net.prok.proknet.core.NetworkHistory.State()
    private val historyFile = File(context.filesDir, "network.history.v1.txt")

    fun saveHistory() {
        io.execute {
            try { historyFile.writeText(net.prok.proknet.core.NetworkHistory.encode(history)) }
            catch (e: Exception) { DiagLog.w(tag, "history: " + e.message) }
        }
    }

    private fun loadHistory() {
        try {
            if (historyFile.exists())
                history = net.prok.proknet.core.NetworkHistory.decode(historyFile.readText())
        } catch (e: Exception) { DiagLog.w(tag, "history: " + e.message) }
    }
    @Volatile private var retry = ControlRetry.State()
    private val inboxFile = File(context.filesDir, "opportunities.v1.txt")
    /** Show one aggregated alert; the hook answers whether it could be shown. */
    @Volatile var alertHook: ((ProviderInbox.Alert) -> Boolean)? = null
    /** Nothing is waiting any more: take the alert away. */
    @Volatile var clearAlertHook: (() -> Unit)? = null
    @Volatile var inboxChanged: (() -> Unit)? = null
    @Volatile var cancelHook: ((String) -> Unit)? = null
    @Volatile var lastRefusal = ""
        private set
    @Volatile var lastOpportunityAt = 0L
        private set
    @Volatile var lastForwardTo = ""
        private set

    // ---- the two opt-ins and the server ------------------------------------------------------------------

    var notifyOptIn: Boolean
        get() = prefs.getBoolean("notify", false)
        set(v) { prefs.edit().putBoolean("notify", v).apply(); DiagLog.i(tag, "request notifications " + (if (v) "ON" else "OFF")); syncSoon("provider preference changed") }
    var shareCoverage: Boolean
        get() = prefs.getBoolean("share_coverage", false)
        set(v) { prefs.edit().putBoolean("share_coverage", v).apply(); DiagLog.i(tag, "shared coverage " + (if (v) "ON" else "OFF")); syncSoon("coverage preference changed") }
    var brainUrl: String
        get() = prefs.getString("brain_url", "")!!.trim().trimEnd('/')
        set(v) { prefs.edit().putString("brain_url", v.trim()).apply(); backoffMs = MIN_BACKOFF_MS; nextAllowedSync = 0L; DiagLog.i(tag, "brain url " + (if (v.isBlank()) "cleared" else "set")) }

    companion object {
        const val MIN_BACKOFF_MS = 60_000L
        const val MAX_BACKOFF_MS = 30 * 60_000L
        const val PERIODIC_MS = 15 * 60_000L
    }

    init {
        load()
        loadInbox()
        node.addListener(this)
        node.onNetRequest = { peer, body -> onControl(peer, body) }
        // a rebuilt radio is a real reason for every parked send to try again
        node.bleGenerationChanged = { gen -> main.post { retry = ControlRetry.onBleGenerationChanged(retry, gen); forwardTo(node.peers(), "BLE generation " + gen) } }
        // v0.13.3: opportunities outlive the process. Rebuild the inbox from what is still carried.
        main.post { rebuildInbox("process started") }
    }

    /** After a restart the card must come back, without alerting everything all over again. */
    private fun rebuildInbox(why: String) {
        val now = System.currentTimeMillis()
        var changed = false
        for (r in RequestGossip.carried(state, now)) {
            if (ProviderActivation.refusal(eligibility(), r, now) != null) continue
            val before = inbox.items[r.id]
            inbox = ProviderInbox.offer(inbox, r, ProviderInbox.Source.LOCAL, now)
            // it was already known before the restart: do not alert it again
            if (before == null && inbox.items[r.id]?.notifiedAt == 0L) inbox = ProviderInbox.noted(inbox, listOf(r.id), now)
            changed = true
        }
        inbox = ProviderInbox.sweep(inbox, now)
        if (changed || inbox.items.isNotEmpty()) DiagLog.i(tag, "provider inbox (" + why + "): " + ProviderInbox.active(inbox, now).size + " active opportunity(ies)")
        saveInbox(); inboxChanged?.invoke()
    }

    // ---- own requests ------------------------------------------------------------------------------------

    fun mine(id: String): NetRequest.Request? = state.requests[id]?.takeIf { id in state.mine }

    /** This phone asks the network. Signed, stored, handed to every peer in range, queued for the brain. */
    /**
     * This phone needs Internet and nothing local answered.
     *
     * v0.17.0 also tells the Brain, from here rather than from the screen, because this is
     * already the one place that means "ask the network". The buyer presses GET INTERNET
     * once and ProkNet decides: a usable local source, then a locally visible provider,
     * then this. There is no second button.
     *
     * The demand id is the request id, so the two views of one need cannot drift apart and
     * a retry after a lost connection is the same demand rather than a second provider
     * being woken.
     */
    fun originate(id: String, zone: String): NetRequest.Request {
        val now = System.currentTimeMillis()
        val r = NetRequest.sign(NetRequest.oneTap(id, node.identity.shortIdHex, node.identity.pubBytes.toHex(), now, zone), node.identity)
        synchronized(this) { state = RequestGossip.originate(state, r) }
        DiagLog.i(tag, "REQUEST " + r.id + " created in " + zone + ", expires in " + ((r.expiresAt - now) / 60_000) + " min: asking the phones around")
        save(); forwardTo(node.peers(), "new request"); syncSoon("request created")
        // v0.17.2: only now, with the signed request stored, does the Brain hear about it
        askTheNetwork(id)
        return r
    }

    /** This phone ends its request: a signed tombstone replaces it and travels the same way. */
    /**
     * v0.17.0: ask the control plane too, off the main thread and best-effort.
     *
     * A failure here costs nothing: the local gossip request has already been created and
     * carried, and the Brain's copy is simply absent until the next sync picks it up.
     */
    /**
     * v0.17.1: the transport reached a milestone worth telling the Brain about.
     *
     * Off the main thread and best-effort: a status upload that fails must never end a
     * working session, so this can only ever log. Also the point where a local connection
     * makes the network's coordination obsolete - the Brain is not a reservation system,
     * and whichever provider actually worked is the one that counts.
     */
    fun onLocalLink(e: net.prok.proknet.core.NetworkAccess.LinkEvent) {
        if (!node.networkSync.configured) return
        io.execute {
            try {
                when (e) {
                    net.prok.proknet.core.NetworkAccess.LinkEvent.INTERNET_UP ->
                        node.networkSync.localConnectionWon(node.networkSync.myActivation)
                    else -> {
                        val act = node.networkSync.myActivation
                        if (act.isNotEmpty()) node.networkSync.report(act, e)
                    }
                }
            } catch (ex: Exception) { DiagLog.w(tag, "status: " + ex.message) }
        }
    }

    private fun askTheNetwork(id: String) {
        if (!node.networkSync.configured) return
        val budget = node.buyBudgetCentimes
        // v0.17.2: read the signed request from the store BEFORE going asynchronous, so
        // the demand can never reach the Brain before the object it refers to exists here
        val line = state.requests[id]?.let { NetRequest.encodeLine(it) } ?: return
        io.execute {
            try {
                node.networkSync.createDemand(
                    id, budget,
                    // a buyer with nothing to spend is asking for free help, and must
                    // never be quietly matched to somebody who charges
                    if (budget <= 0) "FREE" else "COMMERCIAL",
                    requestLine = line)
            } catch (e: Exception) { DiagLog.w(tag, "the network did not take the request: " + e.message) }
        }
    }

    fun end(id: String, terminal: NetRequest.State) {
        val now = System.currentTimeMillis()
        // and close the Brain's copy, so a provider stops seeing a request nobody waits on
        if (node.networkSync.demandId == id) io.execute {
            try { node.networkSync.cancelDemand() }
            catch (e: Exception) { DiagLog.w(tag, "cancel: " + e.message) }
        }
        val next = synchronized(this) { RequestGossip.end(state, id, terminal, now, node.identity)?.also { state = it } } ?: return
        if (next.requests[id]?.tombstone == true) DiagLog.i(tag, "REQUEST " + id + " " + terminal + ": tombstone generation " + next.requests[id]!!.generation)
        save(); forwardTo(node.peers(), "tombstone"); cancelHook?.invoke(id); syncSoon("request ended")
    }

    // ---- carrying -----------------------------------------------------------------------------------------

    private fun onControl(peerShort: String, body: ByteArray) {
        val r = NetRequest.decode(body) ?: run { DiagLog.w(tag, "request from prok-" + peerShort + " did not decode"); return }
        main.post { handle(r, peerShort, local = true) }
    }

    private fun handle(r: NetRequest.Request, from: String, local: Boolean): RequestGossip.Receipt {
        val now = System.currentTimeMillis()
        val why = synchronized(this) { val (st, w) = RequestGossip.receive(state, r, now, from); state = st; w }
        DiagLog.i(tag, "REQUEST " + r.id + " gen " + r.generation + " " + r.state + " from " + (if (local) "prok-" + from else from) + ": " + why)
        if (why == RequestGossip.Receipt.NEW || why == RequestGossip.Receipt.NEWER_GENERATION) {
            save()
            if (r.tombstone) {
                // v0.13.3: the card and the notification go together, from the one source of truth
                if (inbox.items.containsKey(r.id)) { inbox = ProviderInbox.remove(inbox, r.id); saveInbox(); inboxChanged?.invoke() }
                if (ProviderInbox.active(inbox, now).isEmpty()) clearAlertHook?.invoke()
                cancelHook?.invoke(r.id)
            } else if (r.id !in state.mine) considerActivation(r, local)
            forwardTo(node.peers(), "carry")
            if (local) syncSoon("request carried")
        }
        return why
    }

    /**
     * v0.13.2: "could this phone become a seller right now?" While SELL is off the
     * seller gateway is not running and its upstream is null, so the phone's real
     * current Internet is the evidence; once SELL is on the gateway is authoritative.
     */
    /**
     * v0.17.0: tell the control plane what this phone can honestly offer, and let it feed
     * the activation inbox that has existed since v0.13.3.
     *
     * Nothing new is invented for the provider experience. A Brain activation becomes an
     * `ProviderInbox` opportunity exactly like a locally gossiped one, so the notification,
     * the Gagner card and PARTAGER all keep working the way they already do.
     */
    private fun wireControlPlane() {
        // v0.17.3: the whole decision lives in the pure ProviderPresence, so the test
        // and the phone run the same function. Build 70 built it inline here as
        // `sharingEnabled = e.optIn && node.sellOn`, which meant only a phone ALREADY
        // sharing was ever visible to the Brain - so an idle opted-in provider could
        // never be offered a buyer, and the distant two-phone loop could not start.
        //
        // The provider is still the only party that can see its own upstream and radio,
        // so capability remains its claim - and a claim never becomes an obligation:
        // every v0.16 check still runs locally before it shares for money.
        node.networkSync.presenceHook = {
            net.prok.proknet.core.ProviderPresence.of(
                zone = cover.zone(),
                eligibility = eligibility(),
                currentlySharing = node.sellOn,
                // the gateway accepts one session at a time, so load is 0 or 1. The
                // server clamps these anyway: a provider cannot claim impossible capacity.
                activeSessions = if (node.gateway.session != null) 1 else 0,
                mayOfferPaidSharing = node.mayOfferPaidSharing(),
                // v0.17.4: what this phone is OFFERING, from the same source the pricing
                // engine will quote on. Build 71 inferred FREE from being unable to take
                // money, which advertised a seller with no Mobile Money number as a gift.
                intent = net.prok.proknet.core.ProviderPresence.intentOf(node.mySource()))
        }

        node.networkSync.onJobs = { jobs ->
            val now = System.currentTimeMillis()
            for (j in jobs) {
                // v0.17.2: the job carries the buyer's own signed request, already
                // verified by NetworkBrainSync. v0.17.1 looked the request up in the
                // gossip store instead - which two phones far apart have no reason to
                // have exchanged, so the activation arrived and the provider saw nothing.
                val r = j.request
                synchronized(this) {
                    // fold it into the normal request store too, so everything downstream
                    // - eligibility, the card, the tombstone on cancel - behaves as it
                    // always has for a locally gossiped request
                    val (st, _) = net.prok.proknet.core.RequestGossip.receive(state, r, now, "brain")
                    state = st
                    inbox = net.prok.proknet.core.ProviderInbox.offerFromBrain(
                        inbox, r, j.activationId, now)
                }
            }
            if (jobs.isNotEmpty()) {
                save(); saveInbox()
                rebuildInbox("brain jobs"); alertNow()
            }
        }

        // v0.17.3: every successful poll, whether or not the list changed. The stuck-tap
        // case is by definition a job that comes back unchanged.
        node.networkSync.onJobsSeen = { jobs -> reconcileBrainAcks(jobs) }

        node.networkSync.onDemand = { status ->
            DiagLog.i(tag, "my Internet request: " + status)
            main.post { inboxChanged?.invoke() }
        }
    }

    fun currentUpstream(): net.prok.proknet.core.Tunnel.NetView? = if (node.sellOn) node.gateway.upstream else Upstream.now(context)

    fun eligibility(): ProviderActivation.Eligibility {
        val up = currentUpstream()
        return ProviderActivation.eligibility(
            optIn = notifyOptIn,
            upstreamType = net.prok.proknet.core.Tunnel.upstreamType(up),
            upstreamValidated = up?.validated == true,
            bulkSupported = node.bulk.supported, bluetoothOn = node.bulk.isBluetoothOn,
            alreadySharing = node.sellOn, busy = node.gateway.session != null,
            sellPriceCentimesPerMb = node.sellPrice * 100)
    }

    private fun considerActivation(r: NetRequest.Request, local: Boolean) {
        val now = System.currentTimeMillis()
        val refusal = ProviderActivation.refusal(eligibility(), r, now)
        if (refusal != null) {
            lastRefusal = refusal.name + " for " + r.id
            DiagLog.i(tag, "not activating for " + r.id + ": " + refusal)
            // a request this phone cannot serve must not sit in the inbox pretending it can
            if (inbox.items.containsKey(r.id)) { inbox = ProviderInbox.remove(inbox, r.id); saveInbox(); inboxChanged?.invoke() }
            return
        }
        inbox = ProviderInbox.offer(inbox, r, if (local) ProviderInbox.Source.LOCAL else ProviderInbox.Source.BRAIN, now)
        DiagLog.i(tag, "PROVIDER ACTIVATION opportunity for " + r.id + " (" + (if (local) "seen over BLE" else "from the brain, zone " + r.zone) + "); inbox " + ProviderInbox.active(inbox, now).size)
        saveInbox(); inboxChanged?.invoke()
        alertNow()
    }

    /**
     * v0.13.3: one alert for everything waiting, each opportunity alerted once.
     * A different buyer is never suppressed because another request was recent:
     * the old global two-minute cooldown lost real demand on the phones.
     */
    /**
     * v0.17.2: an acceptance that never reached the Brain, retried.
     *
     * The provider tapped PARTAGER, the acceptance was persisted, and the request failed -
     * no signal, or the process died on the way. The Brain then still shows OFFERED, and
     * the buyer is waiting on somebody who has in fact already agreed. So whenever a job
     * comes back still OFFERED while the inbox says accepted, send it again. The server
     * route is idempotent, and the provider is never asked to tap twice.
     *
     * If the Brain says the activation is genuinely finished, the opportunity is dropped
     * so Gagner does not sit on a dead job for ever. A running SESSION is never touched:
     * the control plane expiring is not a reason to stop somebody's Internet.
     */
    private fun reconcileBrainAcks(jobs: List<net.prok.proknet.node.NetworkBrainSync.Job>) {
        // the decision itself is pure and lives in BrainAck, so the test and the phone
        // run the same function. The SERVER's job state is the authority on whether it
        // has our acceptance; the persisted brainAcked flag is only this phone's memory
        // of what that authority last said.
        if (!net.prok.proknet.core.BrainAck.anyWork(
                inbox, jobs.map { Triple(it.activationId, it.demandId, it.state) })) return
        io.execute {
            var changed = false
            for (j in jobs) {
                val o = synchronized(this) { inbox.items[j.demandId] }
                when (net.prok.proknet.core.BrainAck.step(o, j.activationId, j.state)) {
                    net.prok.proknet.core.BrainAck.Step.NOTHING -> {}
                    net.prok.proknet.core.BrainAck.Step.MARK_ACKED -> {
                        // the Brain already has it. Record that, so later polls do not
                        // keep re-sending an answer that is settled.
                        synchronized(this) { inbox = ProviderInbox.brainAcked(inbox, j.demandId, true) }
                        changed = true
                    }
                    net.prok.proknet.core.BrainAck.Step.RESEND -> {
                        // still OFFERED: whatever this phone believed, the Brain does not
                        // have it. This is THE recovery case, and build 70 could not
                        // reach it - pollJobs returned early because the job list had not
                        // changed, which is exactly the shape a stuck acceptance has.
                        if (o != null && o.brainAcked) {
                            synchronized(this) { inbox = ProviderInbox.brainAcked(inbox, j.demandId, false) }
                            changed = true
                        }
                        DiagLog.i(tag, "re-sending an acceptance the network never received")
                        val r = try { node.networkSync.answer(j.activationId, true) }
                            catch (e: Exception) {
                                DiagLog.w(tag, "retry: " + e.message)
                                net.prok.proknet.core.BrainAnswer.unreachable()
                            }
                        when (net.prok.proknet.core.BrainAck.outcome(r, node.gateway.session != null)) {
                            net.prok.proknet.core.BrainAck.Outcome.ACKED -> {
                                synchronized(this) { inbox = ProviderInbox.brainAcked(inbox, j.demandId, true) }
                                changed = true
                            }
                            // the network being down is not a refusal. Keep the card, the
                            // acceptance, the activation id; keep selling; try again next
                            // poll. Build 70 deleted the opportunity here.
                            net.prok.proknet.core.BrainAck.Outcome.KEEP_AND_RETRY ->
                                DiagLog.i(tag, net.prok.proknet.core.BrainAnswer.describe(r))
                            net.prok.proknet.core.BrainAck.Outcome.DROP_CARD -> {
                                // the activation is genuinely over, so the stale card
                                // goes - and only the card. A live session is somebody
                                // really using the Internet, and bookkeeping never cuts
                                // that off; BrainAck.outcome refuses to drop one.
                                synchronized(this) { inbox = ProviderInbox.remove(inbox, j.demandId) }
                                changed = true
                            }
                        }
                    }
                }
            }
            if (changed) { saveInbox(); main.post { inboxChanged?.invoke() } }
        }
    }

    private fun alertNow() {
        val now = System.currentTimeMillis()
        val a = ProviderInbox.alert(inbox, now) ?: return
        val shown = try { alertHook?.invoke(a) ?: false } catch (e: Exception) { DiagLog.w(tag, "alert: " + e); false }
        inbox = if (shown) ProviderInbox.noted(inbox, a.requestIds, now).also { lastOpportunityAt = now }
            else ProviderInbox.suppressed(inbox, "notification not shown (permission denied or Android refused)")
        if (!shown) DiagLog.w(tag, "the alert could not be shown; the request stays visible in Gagner")
        saveInbox()
    }

    /**
     * PARTAGER, from the notification or from the Gagner card: the same path.
     * Everything is re-checked, because the alert may be minutes old.
     * Returns null when sharing started, or one plain sentence for the user.
     */
    fun acceptOpportunity(requestId: String?): String? {
        val now = System.currentTimeMillis()
        val id = requestId ?: ProviderInbox.active(inbox, now).firstOrNull { !it.accepted }?.requestId
        val r = id?.let { state.requests[it] }
        if (id == null || r == null) {
            DiagLog.w(tag, "PARTAGER for " + (requestId ?: "any") + ": nothing active")
            return "Cette demande n'est plus active."
        }
        val refusal = ProviderActivation.refusal(eligibility(), r, now)
        if (refusal != null) {
            DiagLog.w(tag, "PARTAGER refused for " + id + ": " + refusal)
            if (refusal == ProviderActivation.Refusal.REQUEST_NOT_OPEN) { inbox = ProviderInbox.remove(inbox, id); saveInbox(); inboxChanged?.invoke() }
            return ProviderInbox.refusalSentence(refusal)
        }
        DiagLog.i(tag, "PARTAGER accepted for " + id + ": starting the normal seller flow")
        val err = node.setSelling(true)
        if (err != null) { DiagLog.w(tag, "setSelling refused: " + err); return "Le partage n'a pas pu démarrer." }
        // persisted BEFORE anything is sent, so a dying process or a dead network can
        // never lose the fact that somebody pressed the button
        inbox = ProviderInbox.accept(inbox, id, now)
        saveInbox(); inboxChanged?.invoke()
        // v0.17.2: and tell the Brain, off the main thread. Without this the activation
        // stayed OFFERED for ever and the buyer waited on a provider that had in fact
        // already agreed - the central failure of the whole product flow.
        inbox.items[id]?.brainActivationId?.takeIf { it.isNotEmpty() }?.let { act ->
            io.execute {
                val r = try { node.networkSync.answer(act, true) }
                    catch (e: Exception) {
                        DiagLog.w(tag, "acceptance: " + e.message)
                        net.prok.proknet.core.BrainAnswer.unreachable()
                    }
                DiagLog.i(tag, net.prok.proknet.core.BrainAnswer.describe(r))
                // v0.17.3: only a real confirmation closes the retry. Anything else leaves
                // the acceptance on disk exactly as it is, and the next job poll tries
                // again - including after a restart, and without a second tap.
                if (r == net.prok.proknet.core.BrainAnswer.Result.ACCEPTED) {
                    synchronized(this) { inbox = ProviderInbox.brainAcked(inbox, id, true) }
                    saveInbox(); main.post { inboxChanged?.invoke() }
                }
            }
        }
        syncSoon("provider activated")
        return null
    }

    /** Sharing stopped: nothing from the last session may block the next request. */
    fun onSharingStopped() {
        val now = System.currentTimeMillis()
        val live = state.requests.filterValues { it.open && !it.expired(now) }.keys
        val before = inbox.items.size
        inbox = ProviderInbox.State(inbox.items.filterKeys { it in live }.mapValues { (_, o) -> o.copy(accepted = false, brainAcked = false) },
            inbox.lastSuppressed, inbox.lastNotifiedAt, inbox.notifications)
        if (before != inbox.items.size) DiagLog.i(tag, "sharing stopped: inbox now " + inbox.items.size + " opportunity(ies)")
        saveInbox(); inboxChanged?.invoke()
        if (ProviderInbox.active(inbox, now).isEmpty()) clearAlertHook?.invoke()
    }

    private var lastSeenIds: Set<String> = emptySet()
    override fun onPeers(peers: List<Peer>) {
        // a peer we had parked is back: give it one clean chance, then forward within the retry rules
        val ids = peers.filter { it.inRange && it.hasId }.map { it.shortId }.toSet()
        for (id in ids - lastSeenIds) retry = ControlRetry.onPeerReappeared(retry, id)
        lastSeenIds = ids
        forwardTo(peers, "peers changed")
    }
    override fun onMessagesChanged() {}
    override fun onStatus(status: String) {}

    /**
     * v0.13.3: hand every live generation to each peer once, and never more than
     * one attempt at a time per (request generation, peer). onPeers() fires
     * constantly; the phone log showed the same request re-sent on every tick,
     * which is a radio storm and gives a broken peer no time to repair itself.
     */
    private fun forwardTo(peers: List<Peer>, why: String) {
        val now = System.currentTimeMillis()
        val gen = node.bleGeneration()
        var any = false
        for (p in peers) {
            if (!p.inRange || !p.hasId || !node.hasKey(p.shortId)) continue
            for (r in RequestGossip.toForward(state, p.shortId, now)) {
                if (!ControlRetry.mayStart(retry, r.id, r.generation, p.shortId, now, gen)) continue
                retry = ControlRetry.started(retry, r.id, r.generation, p.shortId, now, gen)
                synchronized(this) { state = RequestGossip.markForwarded(state, r, p.shortId, now) }
                any = true
                val copy = NetRequest.forwarded(r)
                DiagLog.i(tag, "FORWARD " + r.id + " gen " + r.generation + " -> prok-" + p.shortId + " (" + why + ", hop " + copy.hops + ")")
                lastForwardTo = "prok-" + p.shortId + " (" + r.id + ", " + why + ")"
                node.sendControl(p.shortId, Wire.netRequest(NetRequest.encode(copy))) { ok ->
                    main.post {
                        if (ok) retry = ControlRetry.delivered(retry, r.id, r.generation, p.shortId, System.currentTimeMillis())
                        else {
                            val err = node.lastControlError(p.shortId)
                            retry = ControlRetry.failed(retry, r.id, r.generation, p.shortId, System.currentTimeMillis(), err)
                            synchronized(this) { state = RequestGossip.unmark(state, r, p.shortId) }
                            val note = ControlRetry.peerNote(retry.peers[p.shortId], System.currentTimeMillis())
                            DiagLog.w(tag, "forward of " + r.id + " to prok-" + p.shortId + " failed: " + err + (if (note.isEmpty()) "" else " - " + note))
                        }
                    }
                }
            }
        }
        if (any) save()
    }

    fun sweep() {
        val now = System.currentTimeMillis()
        val before = state.requests.size
        synchronized(this) { state = RequestGossip.sweep(state, now) }
        if (state.requests.size != before) save()
        // the inbox never keeps demand that expired, ended, or that this phone can no longer serve
        val live = state.requests.filterValues { it.open && !it.expired(now) }.keys
        val kept = ProviderInbox.sweep(inbox, now)
        val pruned = kept.copy(items = kept.items.filterKeys { it in live })
        if (pruned.items.size != inbox.items.size) {
            inbox = pruned; saveInbox(); inboxChanged?.invoke()
            if (ProviderInbox.active(inbox, now).isEmpty()) clearAlertHook?.invoke()
        }
        retry = ControlRetry.keepOnly(retry, state.requests.keys)
    }

    // ---- the brain -----------------------------------------------------------------------------------------------

    @Volatile var lastSyncAttempt = 0L; private set
    @Volatile var lastSyncOk = 0L; private set
    @Volatile var lastSyncError = ""; private set
    @Volatile var syncCount = 0; private set
    @Volatile var lastServerTime = 0L; private set
    @Volatile var lastDownload = ""; private set
    @Volatile private var backoffMs = MIN_BACKOFF_MS
    @Volatile private var nextAllowedSync = 0L
    private val syncing = AtomicBoolean(false)
    private val syncRunnable = Runnable { syncNow("scheduled") }

    val configured: Boolean get() = brainUrl.isNotEmpty()

    fun syncSoon(why: String) { if (!configured) return; main.removeCallbacks(syncRunnable); main.postDelayed(syncRunnable, 3_000) }

    fun syncNow(why: String) {
        if (!configured) { lastSyncError = "no server configured"; return }
        val now = System.currentTimeMillis()
        if (now < nextAllowedSync) { DiagLog.i(tag, "sync (" + why + ") deferred " + ((nextAllowedSync - now) / 1000) + " s after a failure"); return }
        if (!syncing.compareAndSet(false, true)) return
        io.execute { doSync(why) }
    }

    init {
        // v0.15.3: the node owns the settlement queue; NetworkNode owns the server address
        node.brainUrlProvider = { brainUrl }
        node.zoneProvider = { cover.zone() }
        loadHistory()
        wireControlPlane()
        // v0.16.0: anything booked before the queue existed, or abandoned by the old
        // twelve-attempt limit, is picked up again on every start
        try { node.settlementSync.backfill() } catch (e: Exception) { DiagLog.w(tag, "backfill: " + e.message) }
    }

    /**
     * v0.15.3: drain the settlement queue on the same timer as the brain sync. Bounded by
     * its own backoff, so an unreachable server costs one cheap check.
     */
    /**
     * v0.17.0: every Brain-facing subsystem, each in its own failure domain.
     *
     * This used to run only after a successful `/v1/sync`, which meant one hiccup in
     * coverage gossip also stopped settlements and payments reaching the server. v0.15.3
     * taught that lesson and v0.16.2 quietly re-introduced it. Each call is now its own
     * try/catch, and `runBrainSubsystems` is called whether or not the sync itself worked.
     */
    private fun runBrainSubsystems() {
        try { node.settlementSync.runDue() } catch (e: Exception) { DiagLog.w(tag, "settlement sync: " + e.message) }
        try { node.paymentSync.run() } catch (e: Exception) { DiagLog.w(tag, "payment sync: " + e.message) }
        try { node.networkSync.run() } catch (e: Exception) { DiagLog.w(tag, "network sync: " + e.message) }
    }

    private fun doSync(why: String) {
        val now = System.currentTimeMillis()
        lastSyncAttempt = now
        try {
            val up = buildUpload(now)
            val msg = SyncProtocol.signed(up, node.identity)
            DiagLog.i(tag, "SYNC (" + why + "): " + up.coverage.size + " coverage line(s), " + (if (up.availability != null) "availability, " else "") + up.requests.size + " request(s)")
            val text = post(brainUrl + "/v1/sync", msg)
            val d = SyncProtocol.parseDownload(text) ?: throw java.io.IOException("unreadable response")
            main.post { apply(d, up) }
            lastSyncOk = System.currentTimeMillis(); lastSyncError = ""; syncCount++; lastServerTime = d.serverTime
            backoffMs = MIN_BACKOFF_MS; nextAllowedSync = 0L
        } catch (e: Exception) {
            lastSyncError = (e.message ?: e.javaClass.simpleName)
            nextAllowedSync = System.currentTimeMillis() + backoffMs
            DiagLog.w(tag, "SYNC failed: " + lastSyncError + " (next try in " + (backoffMs / 1000) + " s)")
            backoffMs = minOf(backoffMs * 2, MAX_BACKOFF_MS)
        } finally {
            // outside the try, and outside the failure of the sync above: settlements,
            // payments and the control plane each stand on their own
            runBrainSubsystems()
            syncing.set(false)
        }
    }

    private fun buildUpload(now: Long): SyncProtocol.Upload {
        val zone = cover.zone()
        val coverage = if (shareCoverage) SyncProtocol.coverageLines(cover.state.sources.values, now) else emptyList()
        val availability = if (notifyOptIn) ProviderActivation.availability(eligibility(), zone, Tunnel.upstreamType(node.gateway.upstream)) else null
        return SyncProtocol.Upload(node.identity.idHex, node.identity.pubBytes.toHex(), now, zone, coverage, availability, RequestGossip.pendingUpload(state, now), emptyList())
    }

    private fun apply(d: SyncProtocol.Download, up: SyncProtocol.Upload) {
        cover.setShared(d.cells)
        synchronized(this) { state = RequestGossip.markUploaded(state, up.requests) }
        var fresh = 0
        for (r in d.requests) if (handle(r, "brain", local = false) == RequestGossip.Receipt.NEW) fresh++
        lastDownload = d.cells.size.toString() + " shared cell(s), " + d.requests.size + " request(s) (" + fresh + " new), " + d.jobs.size + " job(s), " + d.statuses.size + " status line(s)" +
            (if (d.advice.isNotEmpty()) "; " + d.advice.joinToString("; ") else "")
        DiagLog.i(tag, "SYNC ok: " + lastDownload)
        save()
    }

    private fun post(url: String, body: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = 20_000; c.doOutput = true
        c.setRequestProperty("Content-Type", "text/plain; charset=utf-8")
        c.setRequestProperty("X-Prok-Protocol", "prok-sync/" + SyncProtocol.VERSION)
        c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        if (code !in 200..299) throw java.io.IOException("HTTP " + code + " " + text.take(120).replace("\n", " "))
        return text
    }

    // ---- persistence and diagnostics -------------------------------------------------------------------------------

    private fun load() {
        try { if (file.exists()) state = RequestGossip.decode(file.readText(Charsets.UTF_8)) } catch (e: Exception) { DiagLog.w(tag, "requests load: " + e.message) }
        DiagLog.i(tag, "requests loaded: " + state.requests.size + " (" + state.mine.size + " mine)")
    }

    private fun loadInbox() {
        try { if (inboxFile.exists()) inbox = ProviderInbox.decode(inboxFile.readText(Charsets.UTF_8)) } catch (e: Exception) { DiagLog.w(tag, "inbox load: " + e.message) }
    }

    private fun saveInbox() {
        val text = ProviderInbox.encode(inbox)
        io.execute { try { inboxFile.writeText(text, Charsets.UTF_8) } catch (e: Exception) { DiagLog.w(tag, "inbox save: " + e.message) } }
    }

    private fun save() {
        val text = RequestGossip.encode(state)
        io.execute { try { file.writeText(text, Charsets.UTF_8) } catch (e: Exception) { DiagLog.w(tag, "requests save: " + e.message) } }
    }

    /** v0.13.1: the five lines that answer "why did nothing happen?" without reading the rest. */
    private fun summary(now: Long): String {
        val peers = node.peers().filter { it.inRange && it.hasId }
        val lastSeen = node.peers().maxOfOrNull { it.lastSeen } ?: 0L
        val mine = state.requests.values.filter { it.id in state.mine }.maxByOrNull { it.updatedAt }
        val sb = StringBuilder()
        sb.append("Nearby ProkNet phones: ").append(peers.size)
            .append(if (peers.isEmpty()) "" else " (" + peers.joinToString(", ") { "prok-" + it.shortId } + ")").append("\n")
        sb.append("Last peer seen: ").append(if (lastSeen == 0L) "never" else ((now - lastSeen) / 1000).toString() + " s ago")
            .append(if (peers.isEmpty() && lastSeen > 0L) " (NOT IN RANGE now)" else "").append("\n")
        sb.append("Request state: ").append(mine?.let { it.state.name + " (" + it.id + ", " + (if (it.expired(now)) "expired" else ((it.expiresAt - now) / 60_000).toString() + " min left") + ")" } ?: "no request from this phone").append("\n")
        sb.append("Last request forwarded to: ").append(lastForwardTo.ifEmpty { "nobody yet" }).append("\n")
        sb.append("Provider opportunities: ").append(ProviderInbox.active(inbox, now).size)
            .append(" (local ").append(ProviderInbox.localCount(inbox, now)).append(", brain ").append(ProviderInbox.brainCount(inbox, now)).append(")")
            .append(ProviderInbox.oldest(inbox, now)?.let { ", oldest " + ((now - it.receivedAt) / 1000) + "s" } ?: "").append("\n")
        sb.append("Provider activation notification sent: ").append(if (lastOpportunityAt > 0) "YES, " + CoverageModel.ageWord(now - lastOpportunityAt) else "NO")
            .append(if (lastOpportunityAt > 0) "" else " - " + lastRefusal.ifEmpty { "no request reached this phone" }).append("\n")
        sb.append("This phone could share: ").append(Upstream.describe(currentUpstream())).append(" -> ").append(eligibility().accessPath)
            .append(if (node.sellOn) " (already sharing)" else "").append("\n\n")
        return sb.toString()
    }

    fun diag(): String {
        val now = System.currentTimeMillis()
        val sb = StringBuilder(summary(now))
        sb.append("network brain:\n")
        sb.append("  server: ").append(if (configured) brainUrl else "not configured (direct/local mode)").append("\n")
        sb.append("  last sync attempt: ").append(if (lastSyncAttempt == 0L) "-" else CoverageModel.ageWord(now - lastSyncAttempt)).append(" | last success: ").append(if (lastSyncOk == 0L) "-" else CoverageModel.ageWord(now - lastSyncOk))
            .append(" | syncs: ").append(syncCount).append(" | error: ").append(lastSyncError.ifEmpty { "-" }).append("\n")
        sb.append("  last download: ").append(lastDownload.ifEmpty { "-" }).append("\n")
        sb.append("  pending upload: ").append(RequestGossip.pendingUpload(state, now).size).append("\n")
        sb.append("requests:\n")
        val all = state.requests.values.sortedByDescending { it.updatedAt }
        sb.append("  active: ").append(RequestGossip.active(state, now).size).append(" | carried: ").append(RequestGossip.carried(state, now).size)
            .append(" | mine: ").append(state.mine.size).append(" | expired: ").append(all.count { it.state == NetRequest.State.EXPIRED })
            .append(" | fulfilled: ").append(all.count { it.state == NetRequest.State.FULFILLED }).append(" | cancelled: ").append(all.count { it.state == NetRequest.State.CANCELLED }).append("\n")
        for (r in all.take(12)) sb.append("  ").append(r.id).append(" gen ").append(r.generation).append(" ").append(r.state).append(" from prok-").append(r.originShort)
            .append(if (r.id in state.mine) " (mine)" else "").append(" zone ").append(r.zone).append(" hops ").append(r.hops).append(" ").append(CoverageModel.ageWord(now - r.updatedAt))
            .append(if (r.expired(now)) " EXPIRED" else " expires in " + ((r.expiresAt - now) / 60_000) + " min").append("\n")
        sb.append("provider:\n")
        val e = eligibility()
        sb.append("  current phone Internet: ").append(Upstream.describe(currentUpstream())).append("\n")
        sb.append("  potential seller path: ").append(e.accessPath).append("\n")
        sb.append("  seller gateway running: ").append(if (node.sellOn) "YES" else "NO").append("\n")
        sb.append("  notifications: ").append(if (e.optIn) "ON" else "OFF").append(" | internet validated: ").append(e.upstreamValidated)
            .append(" | bluetooth: ").append(e.bluetoothOn).append(" | busy: ").append(e.busy).append(" | price: ").append(e.sellPriceCentimesPerMb).append("c\n")
        sb.append("  last opportunity: ").append(if (lastOpportunityAt == 0L) "-" else CoverageModel.ageWord(now - lastOpportunityAt)).append(" | last refusal: ").append(lastRefusal.ifEmpty { "-" }).append("\n")
        sb.append("provider opportunities:\n")
        sb.append("  active: ").append(ProviderInbox.active(inbox, now).size).append(" | local: ").append(ProviderInbox.localCount(inbox, now))
            .append(" | brain: ").append(ProviderInbox.brainCount(inbox, now))
            .append(ProviderInbox.oldest(inbox, now)?.let { " | oldest: " + ((now - it.receivedAt) / 1000) + "s" } ?: "").append("\n")
        for (o in ProviderInbox.active(inbox, now)) sb.append("  ").append(o.requestId).append(" from prok-").append(o.originShort)
            .append(" ").append(o.source).append(" received ").append(CoverageModel.ageWord(now - o.receivedAt))
            .append(if (o.notifiedAt > 0) ", alerted" else ", NOT alerted").append(if (o.accepted) ", ACCEPTED" else "").append("\n")
        sb.append("  notification: last shown ").append(if (inbox.lastNotifiedAt == 0L) "never" else CoverageModel.ageWord(now - inbox.lastNotifiedAt))
            .append(", total ").append(inbox.notifications).append(", suppressed reason: ").append(inbox.lastSuppressed.ifEmpty { "none" }).append("\n")
        sb.append("pricing:\n")
        sb.append("  buyer budget: ").append(Market.cfa(node.buyBudgetCentimes)).append(" | seller policy: ").append(node.sellerPolicy)
            .append(" | my source: ").append(node.mySource().kind).append(" | declared bundle cost: ")
            .append(if (node.sourceCostCentimesPerMb < 0) "not declared" else Market.cfa(node.sourceCostCentimesPerMb.toLong()) + "/MB").append("\n")
        sb.append("  my automatic rate: ").append(Market.cfa(node.autoRateCentimesPerMb().toLong())).append("/MB internal, advertised ")
            .append(net.prok.proknet.core.Pricing.advertisedPriceCfa(node.autoRateCentimesPerMb())).append(" CFA/MB | my floor: ")
            .append(Market.cfa(net.prok.proknet.core.Pricing.sellerFloorPerMb(node.mySource(), node.sellerPolicy).toLong())).append("/MB\n")
        node.buyQuote?.let { sb.append("  last buy quote: ").append(net.prok.proknet.core.Pricing.describe(it).replace("\n", "\n  ")).append("\n") }
        node.tunnel.contract?.let { c ->
            sb.append("  live contract: v").append(c.version).append(if (c.budgetSession) " BUDGET" else " legacy")
                .append(", budget ").append(Market.cfa(c.buyerBudgetCentimes)).append(", rate ").append(Market.cfa(c.rateCentimesPerMb.toLong()))
                .append("/MB, ceiling ").append(Market.mb(c.maxBytes)).append(", spent ").append(Market.cfa(node.tunnel.runningCost())).append("\n")
        }
        sb.append(node.payments.describe()).append("\n")
        sb.append(node.paymentSync.describe()).append("\n")
        sb.append(node.networkSync.describe()).append("\n")
        sb.append(node.settlementSync.describe()).append("\n")
        sb.append("session shutdown:\n")
            .append("  state: ").append(if (node.stoppingInternet) "STOPPING" else if (node.gateway.finalizing) "FINALIZING" else node.tunnel.state).append("\n")
            .append("  reason: ").append(node.tunnel.lastError.ifEmpty { "user stopped" }).append("\n")
            .append("  final checkpoint: ").append(node.tunnel.finalCheckpoint).append("\n")
            .append("  final settlement: ").append(net.prok.proknet.core.Market.cfa(node.tunnel.agreedCost())).append("\n")
            .append("  bulk close: ").append(if (node.bulk.lastCloseWasIntentional) "NORMAL" else node.bulk.state.phase.toString()).append("\n")
            .append("  stale callbacks ignored: ").append(node.bulk.staleCallbacksIgnored).append("\n")
        sb.append("contract:\n").append(node.gateway.contractDiag()).append("\n")
        node.tunnel.lastContractReject.takeIf { it.isNotEmpty() }?.let { sb.append("  buyer saw rejection: ").append(it).append("\n") }
        sb.append("control plane:\n  ").append(node.bleControlPlaneLine().replace("\n", "\n  ")).append("\n")
        sb.append("  forwarding: ").append(ControlRetry.describe(retry, now)).append("\n")
        for ((peer, h) in retry.peers) ControlRetry.peerNote(h, now).let { if (it.isNotEmpty()) sb.append("  prok-").append(peer).append(": ").append(it).append("\n") }
        sb.append("  shared coverage: ").append(if (shareCoverage) "ON" else "OFF").append(" | shared cells known: ").append(cover.shared.size).append(" | last shared update: ").append(if (cover.lastSharedAt == 0L) "-" else CoverageModel.ageWord(now - cover.lastSharedAt)).append("\n")
        val s = state.stats
        sb.append("gossip:\n  received ").append(s.received).append(" | accepted ").append(s.accepted).append(" | forwards ").append(s.forwards).append(" | dedup drops ").append(s.dedupDrops)
            .append(" | ttl drops ").append(s.ttlDrops).append(" | hop drops ").append(s.hopDrops).append(" | bad signatures ").append(s.badSignature).append(" | seen ids ").append(state.requests.size).append("\n")
        return sb.toString()
    }
}
