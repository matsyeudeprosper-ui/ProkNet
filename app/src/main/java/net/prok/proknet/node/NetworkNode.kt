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
import net.prok.proknet.core.NetRequest
import net.prok.proknet.core.ProviderActivation
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
    private var limiter = ProviderActivation.Limiter()
    @Volatile var opportunityHook: ((ProviderActivation.Opportunity) -> Unit)? = null
    @Volatile var cancelHook: ((String) -> Unit)? = null
    @Volatile var lastRefusal = ""
        private set
    @Volatile var lastOpportunityAt = 0L
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
        node.addListener(this)
        node.onNetRequest = { peer, body -> onControl(peer, body) }
    }

    // ---- own requests ------------------------------------------------------------------------------------

    fun mine(id: String): NetRequest.Request? = state.requests[id]?.takeIf { id in state.mine }

    /** This phone asks the network. Signed, stored, handed to every peer in range, queued for the brain. */
    fun originate(id: String, zone: String): NetRequest.Request {
        val now = System.currentTimeMillis()
        val r = NetRequest.sign(NetRequest.oneTap(id, node.identity.shortIdHex, node.identity.pubBytes.toHex(), now, zone), node.identity)
        synchronized(this) { state = RequestGossip.originate(state, r) }
        DiagLog.i(tag, "REQUEST " + r.id + " created in " + zone + ", expires in " + ((r.expiresAt - now) / 60_000) + " min: asking the phones around")
        save(); forwardTo(node.peers(), "new request"); syncSoon("request created")
        return r
    }

    /** This phone ends its request: a signed tombstone replaces it and travels the same way. */
    fun end(id: String, terminal: NetRequest.State) {
        val now = System.currentTimeMillis()
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
            if (r.tombstone) cancelHook?.invoke(r.id)
            else if (r.id !in state.mine) considerActivation(r, local)
            forwardTo(node.peers(), "carry")
            if (local) syncSoon("request carried")
        }
        return why
    }

    fun eligibility(): ProviderActivation.Eligibility = ProviderActivation.Eligibility(
        optIn = notifyOptIn, upstreamValidated = node.gateway.upstream?.validated == true, accessPath = node.sellerAccessPath(),
        bluetoothOn = node.bulk.isBluetoothOn, alreadySharing = node.sellOn, busy = node.gateway.session != null,
        sellPriceCentimesPerMb = node.sellPrice * 100)

    private fun considerActivation(r: NetRequest.Request, local: Boolean) {
        val now = System.currentTimeMillis()
        val e = eligibility()
        val refusal = ProviderActivation.refusal(e, r, now)
        if (refusal != null) { lastRefusal = refusal.name + " for " + r.id; DiagLog.i(tag, "not activating for " + r.id + ": " + refusal); return }
        if (!ProviderActivation.allow(limiter, r.id, now)) { DiagLog.i(tag, "activation for " + r.id + " rate-limited"); return }
        limiter = ProviderActivation.noted(limiter, r.id, now)
        lastOpportunityAt = now
        val o = ProviderActivation.opportunity(e, r, now, local)!!
        DiagLog.i(tag, "PROVIDER ACTIVATION opportunity for " + r.id + " (" + (if (local) "seen over BLE" else "from the brain, zone " + r.zone) + ")")
        opportunityHook?.invoke(o)
    }

    override fun onPeers(peers: List<Peer>) { forwardTo(peers, "peers changed") }
    override fun onMessagesChanged() {}
    override fun onStatus(status: String) {}

    /** Hand every live generation a peer has not seen to it, once. */
    private fun forwardTo(peers: List<Peer>, why: String) {
        val now = System.currentTimeMillis()
        var any = false
        for (p in peers) {
            if (!p.inRange || !p.hasId || !node.hasKey(p.shortId)) continue
            for (r in RequestGossip.toForward(state, p.shortId, now)) {
                synchronized(this) { state = RequestGossip.markForwarded(state, r, p.shortId, now) }
                any = true
                val copy = NetRequest.forwarded(r)
                DiagLog.i(tag, "FORWARD " + r.id + " gen " + r.generation + " -> prok-" + p.shortId + " (" + why + ", hop " + copy.hops + ")")
                node.sendControl(p.shortId, Wire.netRequest(NetRequest.encode(copy))) { ok ->
                    if (!ok) { synchronized(this) { state = RequestGossip.unmark(state, r, p.shortId) }; DiagLog.w(tag, "forward of " + r.id + " to prok-" + p.shortId + " failed; will retry") }
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
        } finally { syncing.set(false) }
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

    private fun save() {
        val text = RequestGossip.encode(state)
        io.execute { try { file.writeText(text, Charsets.UTF_8) } catch (e: Exception) { DiagLog.w(tag, "requests save: " + e.message) } }
    }

    fun diag(): String {
        val now = System.currentTimeMillis()
        val sb = StringBuilder("network brain:\n")
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
        sb.append("  notifications: ").append(if (e.optIn) "ON" else "OFF").append(" | internet validated: ").append(e.upstreamValidated).append(" | path: ").append(e.accessPath)
            .append(" | bluetooth: ").append(e.bluetoothOn).append(" | sharing: ").append(e.alreadySharing).append(" | busy: ").append(e.busy).append(" | price: ").append(e.sellPriceCentimesPerMb).append("c\n")
        sb.append("  last opportunity: ").append(if (lastOpportunityAt == 0L) "-" else CoverageModel.ageWord(now - lastOpportunityAt)).append(" | last refusal: ").append(lastRefusal.ifEmpty { "-" }).append("\n")
        sb.append("  shared coverage: ").append(if (shareCoverage) "ON" else "OFF").append(" | shared cells known: ").append(cover.shared.size).append(" | last shared update: ").append(if (cover.lastSharedAt == 0L) "-" else CoverageModel.ageWord(now - cover.lastSharedAt)).append("\n")
        val s = state.stats
        sb.append("gossip:\n  received ").append(s.received).append(" | accepted ").append(s.accepted).append(" | forwards ").append(s.forwards).append(" | dedup drops ").append(s.dedupDrops)
            .append(" | ttl drops ").append(s.ttlDrops).append(" | hop drops ").append(s.hopDrops).append(" | bad signatures ").append(s.badSignature).append(" | seen ids ").append(state.requests.size).append("\n")
        return sb.toString()
    }
}
