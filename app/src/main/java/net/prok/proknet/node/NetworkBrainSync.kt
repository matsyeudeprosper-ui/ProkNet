package net.prok.proknet.node

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import net.prok.proknet.core.BrainPayload
import net.prok.proknet.core.Coverage
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.NetworkAccess
import net.prok.proknet.core.SignedApi

/**
 * v0.17.0: the phone's half of the live control plane.
 *
 * Its own class, and its own failure domain. `PaymentSync` failing must not stop a buyer
 * finding Internet, and this failing must not stop a payment reaching the Brain or a local
 * session working - so nothing here shares a try/catch with them, and nothing here is on
 * the critical path of a session. If the Brain is unreachable, ProkNet behaves exactly as
 * it did in v0.16.5: local discovery, local activation, local Internet.
 *
 * v0.15.3 taught this the hard way and v0.16.2 partly forgot it: gating one subsystem
 * behind another's success means one server hiccup takes out three features. This runs on
 * its own, on its own triggers.
 *
 * What it does, all best-effort:
 *
 * - publishes a presence while this phone is genuinely willing and able to share;
 * - creates a demand when the buyer asked for Internet and nothing local answered;
 * - polls that demand, so the buyer's screen says something true;
 * - collects activation jobs offered to this phone and hands them to the existing inbox;
 * - sends the provider's answer, and the transport's result, back;
 * - refreshes this zone's colour.
 */
class NetworkBrainSync(
    private val identity: Identity,
    private val brainUrl: () -> String,
    /** The coarse zone this phone is in, or empty when location is unavailable. */
    private val zone: () -> String,
) {
    private val tag = "NETSYNC"
    private val running = AtomicBoolean(false)

    companion object {
        /** A heartbeat cadence well inside the server's 120 s presence window. */
        const val HEARTBEAT_MS = 45_000L

        /** How often a waiting buyer asks what happened. */
        const val POLL_MS = 5_000L

        /** How often a willing provider checks for work when nothing is going on. */
        const val IDLE_POLL_MS = 30_000L

        /** After a failure, back off rather than hammering. */
        const val MIN_BACKOFF_MS = 10_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
    }

    // ---- what the rest of the app reads -------------------------------------------------

    @Volatile var lastError = ""
        private set
    @Volatile var lastOk = 0L
        private set
    @Volatile var zoneStatus: Coverage.ZoneStatus = Coverage.ZoneStatus.RED
        private set
    @Volatile var reachable = false
        private set

    /** The demand this phone is waiting on, or empty. Survives a restart via the caller. */
    @Volatile var demandId = ""

    /** Activation jobs offered to this phone, newest first. Fed into `ProviderInbox`. */
    @Volatile var jobs: List<Job> = emptyList()
        private set

    /** The buyer-facing status word the Brain last reported for [demandId]. */
    @Volatile var demandStatus = ""
        private set

    class Job(val activationId: String, val demandId: String, val zone: String,
              val state: String, val createdAt: Long, val expiresAt: Long)

    // ---- hooks the node wires up ----------------------------------------------------------

    /** True while this phone genuinely could and would share. Asked, never assumed. */
    @Volatile var presenceHook: (() -> Presence?)? = null

    /** Called when the Brain reports a new status for our demand. */
    @Volatile var onDemand: ((String) -> Unit)? = null

    /** Called with jobs offered to this phone, so the existing inbox can show them. */
    @Volatile var onJobs: ((List<Job>) -> Unit)? = null

    class Presence(
        val zone: String,
        val upstreamAvailable: Boolean,
        val upstreamClass: String,
        val sharingEnabled: Boolean,
        val commercialReady: Boolean,
        val freeReady: Boolean,
        val sponsoredReady: Boolean,
        val currentLoad: Int,
        val maxBuyers: Int,
        val offerClass: String,
        /** Internal only. The buyer never sees a per-MB figure. */
        val priceHintInternal: Int,
    )

    private var backoffMs = MIN_BACKOFF_MS
    private var nextAllowed = 0L
    private var lastHeartbeat = 0L

    val configured: Boolean get() = brainUrl().isNotEmpty()

    /**
     * One pass. Cheap when there is nothing to do, and safe to call often.
     *
     * Called on service start, when the network comes back, when GET INTERNET is pressed,
     * when sharing is turned on or off, when a provider answers, when the transport moves,
     * and on a timer - never in a tight loop.
     */
    fun run(now: Long = System.currentTimeMillis()) {
        if (!configured) return
        if (now < nextAllowed) return
        if (!running.compareAndSet(false, true)) return
        try {
            heartbeat(now)
            reconcile()
            pollDemand()
            pollJobs()
            refreshZone()
            lastOk = now
            reachable = true
            lastError = ""
            backoffMs = MIN_BACKOFF_MS
            nextAllowed = 0
        } catch (e: Exception) {
            // the Brain being away is normal and must cost nothing: no session stops, no
            // payment stalls, and the local path is untouched
            lastError = (e.message ?: e.javaClass.simpleName).take(140)
            reachable = false
            nextAllowed = now + backoffMs
            backoffMs = minOf(backoffMs * 2, MAX_BACKOFF_MS)
            DiagLog.w(tag, "control plane unreachable: " + lastError +
                " (next try in " + (nextAllowed - now) / 1000 + " s)")
        } finally { running.set(false) }
    }

    // ---- provider: say what we can do ----------------------------------------------------

    private fun heartbeat(now: Long) {
        val p = presenceHook?.invoke()
        if (p == null || p.zone.isEmpty()) return
        if (!p.sharingEnabled || !p.upstreamAvailable) {
            // withdrawing at once is kinder than going stale: a buyer matched to a phone
            // that has stopped sharing wastes a real walk to a kiosk
            if (lastHeartbeat != 0L) {
                post("/v1/network/presence/stop", json())
                lastHeartbeat = 0L
            }
            return
        }
        if (now - lastHeartbeat < HEARTBEAT_MS) return
        val body = json(
            "zone" to p.zone,
            "upstreamAvailable" to p.upstreamAvailable,
            "upstreamClass" to p.upstreamClass,
            "sharingEnabled" to p.sharingEnabled,
            "commercialReady" to p.commercialReady,
            "freeReady" to p.freeReady,
            "sponsoredReady" to p.sponsoredReady,
            "currentLoad" to p.currentLoad,
            "maxBuyers" to p.maxBuyers,
            "offerClass" to p.offerClass,
            "priceHintInternal" to p.priceHintInternal)
        if (post("/v1/network/presence", body).first in 200..299) lastHeartbeat = now
    }

    /** Called when sharing is switched off, so the Brain stops offering us work. */
    fun withdraw() {
        if (!configured) return
        try {
            post("/v1/network/presence/stop", json())
            lastHeartbeat = 0L
        } catch (e: Exception) { DiagLog.w(tag, "withdraw: " + e.message) }
    }

    // ---- buyer: ask, and then ask what happened --------------------------------------------

    /**
     * Create the demand. The id is made on the phone so a retry is the same demand, not a
     * second one - the request a phone sends again the moment it regains signal must not
     * wake a second provider.
     */
    fun createDemand(id: String, budgetCentimes: Long, requestedClass: String,
                     need: String = "BROWSE"): String {
        if (!configured) return ""
        val z = zone()
        if (z.isEmpty()) return ""
        val body = json(
            "demandId" to id, "zone" to z,
            "budgetCentimes" to budgetCentimes,
            "requestedClass" to requestedClass,
            "connectivityNeed" to need)
        val (code, text) = post("/v1/network/demand", body)
        if (code !in 200..299) {
            DiagLog.w(tag, "request refused by the network: " + BrainPayload.field(text, "error"))
            return ""
        }
        demandId = BrainPayload.field(text, "demandId").ifEmpty { id }
        demandStatus = BrainPayload.field(text, "status")
        DiagLog.i(tag, "asked the network for Internet (" + demandStatus + ")")
        onDemand?.invoke(demandStatus)
        return demandId
    }

    /**
     * v0.17.1: after a restart, ask the Brain what we were waiting for.
     *
     * The demand id lives in memory, so a process death loses it - and a phone that comes
     * back saying "Recherche d'Internet…" with no demand, or worse creating a second one,
     * would be both wrong and rude to whichever provider it woke. The server is the
     * authority: `GET /v1/network/demand` with no id answers with this buyer's live
     * request, if it has one.
     *
     * Deliberately not a local database. One request round-trip on start is simpler than
     * a table, cannot disagree with the server, and if the Brain is unreachable the honest
     * answer is that we do not know - which is also the answer a stale local copy would
     * have been hiding.
     */
    fun reconcile() {
        if (!configured || demandId.isNotEmpty()) return
        try {
            val (code, text) = get("/v1/network/demand")
            if (code !in 200..299) return
            val id = BrainPayload.field(text, "demandId")
            val status = BrainPayload.field(text, "status")
            if (id.isEmpty() || status.isEmpty()) return
            demandId = id
            demandStatus = status
            DiagLog.i(tag, "picked my Internet request back up: " + status)
            onDemand?.invoke(status)
        } catch (e: Exception) {
            // no demand restored and none invented; the next run tries again
            DiagLog.w(tag, "could not ask about my request: " + e.message)
        }
    }

    private fun pollDemand() {
        if (demandId.isEmpty()) return
        val (code, text) = get("/v1/network/demand?id=" + demandId)
        if (code == 404) { clearDemand(); return }
        if (code !in 200..299) return
        val status = BrainPayload.field(text, "status")
        if (status.isEmpty() || status == demandStatus) return
        demandStatus = status
        DiagLog.i(tag, "request is now " + status)
        onDemand?.invoke(status)
        if (status == "CANCELLED" || status == "EXPIRED" || status == "FAILED") {
            // the buyer may ask again, but only by pressing the button: a demand must not
            // resurrect itself
            demandId = ""
        }
    }

    /**
     * v0.17.1: a local path came up, so the network coordination is obsolete.
     *
     * The Brain is not a reservation system. Fast usable Internet wins, whoever provided
     * it - if the Brain activated A and local discovery found B first, B is used and the
     * demand closes. Reporting the result first, then cancelling, so the matcher still
     * learns that its suggestion worked when it was the one that worked.
     *
     * Every step is best-effort. This runs after a session is already up, and nothing
     * here may fail in a way that touches it.
     */
    fun localConnectionWon(activationId: String = "") {
        if (!configured) return
        try {
            if (activationId.isNotEmpty())
                report(activationId, NetworkAccess.LinkEvent.INTERNET_UP)
            if (demandId.isNotEmpty()) {
                DiagLog.i(tag, "connected locally; withdrawing the network request")
                cancelDemand()
            }
        } catch (e: Exception) {
            // the session is up and stays up; the Brain's copy expires on its own
            DiagLog.w(tag, "could not withdraw the request: " + e.message)
        }
    }

    /** The activation this phone is currently working on, or empty. */
    val myActivation: String get() = jobs.firstOrNull {
        it.state == "ACCEPTED" || it.state == "LOCAL_LINK_SEEN"
    }?.activationId ?: ""

    /** The buyer nudges the matcher, used when a provider declined or went quiet. */
    fun nudge() {
        if (!configured || demandId.isEmpty()) return
        try { post("/v1/network/demand/poll", json("demandId" to demandId)) }
        catch (e: Exception) { DiagLog.w(tag, "nudge: " + e.message) }
    }

    fun cancelDemand() {
        val id = demandId
        if (id.isEmpty()) return
        clearDemand()
        if (!configured) return
        try {
            post("/v1/network/demand/cancel", json("demandId" to id))
            DiagLog.i(tag, "request cancelled")
        } catch (e: Exception) {
            // the local state is already cleared; the server's copy expires on its own
            DiagLog.w(tag, "cancel did not reach the network: " + e.message)
        }
    }

    private fun clearDemand() {
        demandId = ""
        demandStatus = ""
    }

    // ---- provider: collect work, answer it -------------------------------------------------

    private fun pollJobs() {
        val p = presenceHook?.invoke()
        if (p == null || !p.sharingEnabled) return
        val (code, text) = get("/v1/network/jobs")
        if (code !in 200..299) return
        val found = BrainPayload.objects(text, "jobs").mapNotNull { o ->
            val id = o["activationId"] ?: return@mapNotNull null
            Job(id, o["demandId"] ?: "", o["zone"] ?: "", o["state"] ?: "",
                (o["createdAt"] ?: "0").toLongOrNull() ?: 0L,
                (o["expiresAt"] ?: "0").toLongOrNull() ?: 0L)
        }
        if (found.map { it.activationId } == jobs.map { it.activationId }) return
        jobs = found
        if (found.isNotEmpty())
            DiagLog.i(tag, found.size.toString() + " nearby request(s) offered to this phone")
        onJobs?.invoke(found)
    }

    /**
     * The provider tapped PARTAGER, or declined.
     *
     * The caller persists the acceptance BEFORE calling this, so a phone that dies between
     * the tap and the reply comes back still knowing it agreed.
     */
    fun answer(activationId: String, accept: Boolean): Boolean {
        if (!configured || activationId.isEmpty()) return false
        val path = if (accept) "/v1/network/jobs/accept" else "/v1/network/jobs/decline"
        return try {
            val (code, _) = post(path, json("activationId" to activationId))
            if (code in 200..299) {
                DiagLog.i(tag, (if (accept) "accepted" else "declined") + " a nearby request")
                jobs = jobs.filter { it.activationId != activationId }
                true
            } else false
        } catch (e: Exception) {
            DiagLog.w(tag, "answer did not reach the network: " + e.message); false
        }
    }

    // ---- both sides: what actually happened --------------------------------------------------

    /**
     * Tell the Brain what the transport did. **Best-effort and advisory.**
     *
     * A session that works while this fails is still a working session, and nothing here
     * may end one. It exists so the matcher learns whether the phone it suggested was any
     * use, and for nothing else - it changes no signed accounting.
     */
    fun report(activationId: String, e: NetworkAccess.LinkEvent) {
        if (!configured || activationId.isEmpty()) return
        val result = NetworkAccess.reportFor(e)
        try {
            post("/v1/network/jobs/state", json("activationId" to activationId, "result" to result))
        } catch (ex: Exception) {
            DiagLog.w(tag, "status " + result + " not delivered: " + ex.message)
        }
    }

    // ---- the map ----------------------------------------------------------------------------

    private fun refreshZone() {
        val z = zone()
        if (z.isEmpty()) return
        val (code, text) = get("/v1/network/coverage?zone=" + z)
        if (code !in 200..299) return
        zoneStatus = when (BrainPayload.field(text, "state")) {
            "GREEN" -> Coverage.ZoneStatus.GREEN
            "YELLOW" -> Coverage.ZoneStatus.YELLOW
            else -> Coverage.ZoneStatus.RED
        }
    }

    // ---- plumbing -------------------------------------------------------------------------------

    private fun json(vararg pairs: Pair<String, Any>): ByteArray {
        val sb = StringBuilder("{")
        for ((i, p) in pairs.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append('"').append(p.first).append("\":")
            when (val v = p.second) {
                is String -> sb.append('"').append(BrainPayload.escape(v)).append('"')
                is Boolean -> sb.append(if (v) 1 else 0)
                else -> sb.append(v)
            }
        }
        return sb.append('}').toString().toByteArray(Charsets.UTF_8)
    }

    private fun post(path: String, body: ByteArray): Pair<Int, String> = send("POST", path, body)

    private fun get(path: String): Pair<Int, String> = send("GET", path, ByteArray(0))

    private fun send(method: String, path: String, body: ByteArray): Pair<Int, String> {
        val c = URL(brainUrl() + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 10_000; c.readTimeout = 15_000
        // the hardened v0.16.3 form: method and the whole canonical target, query included
        val headers = SignedApi.sign(body, identity, System.currentTimeMillis(),
            method = method, path = path)
        for ((k, v) in headers.asMap()) c.setRequestProperty(k, v)
        if (method == "POST") {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        return code to text
    }

    fun describe(): String = "  network brain: " + (if (!configured) "not configured (local only)" else
        (if (reachable) "reachable" else "unreachable") +
            ", zone " + zoneStatus +
            (if (demandId.isEmpty()) "" else ", my request " + demandStatus) +
            (if (jobs.isEmpty()) "" else ", " + jobs.size + " job(s) offered")) +
        (if (lastError.isEmpty()) "" else "\n  last error: " + lastError)
}
