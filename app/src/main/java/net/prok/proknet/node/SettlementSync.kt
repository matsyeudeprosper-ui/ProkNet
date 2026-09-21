package net.prok.proknet.node

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import net.prok.proknet.core.Crypto
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Evidence
import net.prok.proknet.core.Identity
import net.prok.proknet.core.MessageStore
import net.prok.proknet.core.Settlement
import net.prok.proknet.core.SignedApi
import net.prok.proknet.core.toHex

/**
 * v0.15.3: sends the signed settlement evidence to the server, eventually.
 *
 * **Eventually** is the important word. ProkNet is offline-first: two phones must be able
 * to find each other, agree a price, share Internet and settle with nobody else involved.
 * The server is a witness, not a participant. So a finished session always produces a
 * local obligation, and reaching the server is a separate, patient, bounded job that can
 * fail all week without anybody noticing.
 *
 * The rule the whole design rests on: **no verifiable evidence, no server settlement.**
 * There is no path here that asks the server to believe a number. If the evidence is
 * missing the submission simply never happens, and the phone still shows the local
 * obligation honestly, marked as not yet verified.
 */
class SettlementSync(
    private val identity: Identity,
    private val store: MessageStore,
    private val brainUrl: () -> String,
) {
    private val tag = "SETTLE"
    private val running = AtomicBoolean(false)

    /** Set by the node so a failure can be shown in the technical details. */
    @Volatile var lastError = ""
        private set
    @Volatile var lastOk = 0L
        private set

    val configured: Boolean get() = brainUrl().isNotEmpty()

    /**
     * Queue a finished session. Called right after the obligation is booked, whether or
     * not anything is reachable.
     */
    fun enqueue(o: Settlement.Obligation) {
        store.enqueueSync(o.settlementId, o.sessionHex)
        DiagLog.i(tag, "queued " + o.settlementId.substring(0, 12) + " for server verification")
    }

    /**
     * Try everything that is due. Safe to call often: it is bounded by the backoff, and
     * it returns immediately when a run is already in flight.
     */
    fun runDue(now: Long = System.currentTimeMillis()): Int {
        if (!configured) return 0
        if (!running.compareAndSet(false, true)) return 0
        var sent = 0
        try {
            for (row in store.pendingSync()) {
                if (!Evidence.mayTry(row.attempts, row.lastAttempt, now)) continue
                if (submit(row, now)) sent++
            }
        } catch (e: Exception) {
            DiagLog.w(tag, "sync run: " + (e.message ?: e.javaClass.simpleName))
        } finally { running.set(false) }
        return sent
    }

    /** True when the settlement reached a final answer, good or bad. */
    private fun submit(row: MessageStore.SyncRow, now: Long): Boolean {
        val pkg = packageFor(row.sessionHex)
        if (pkg == null) {
            // nothing to prove, and nothing will appear later: stop asking
            store.markSync(row.settlementId, Evidence.Sync.NOT_APPLICABLE, row.attempts, now, "no evidence")
            return false
        }
        val attempts = row.attempts + 1
        return try {
            // the body is built ONCE; the signature covers exactly these bytes and
            // exactly these bytes are written to the connection
            val body = pkg.body()
            // a retry is a new request over the same evidence: new time, new nonce, new
            // signature, same deterministic settlement id
            val headers = SignedApi.sign(body, identity, now)
            val (code, text) = post(brainUrl() + "/v1/settlements", body, headers.asMap())
            val verdict = Evidence.interpret(code, text)
            when (verdict) {
                Evidence.Sync.REPORTED -> {
                    store.markSync(row.settlementId, verdict, attempts, now)
                    lastOk = now; lastError = ""
                    DiagLog.i(tag, "verified by the server: " + row.settlementId.substring(0, 12))
                    true
                }
                Evidence.Sync.DISPUTED -> {
                    store.markSync(row.settlementId, verdict, attempts, now, "the two phones disagree")
                    store.settlement(row.settlementId)?.let {
                        store.saveSettlement(it.with(status = Settlement.Status.DISPUTED,
                            note = "les deux téléphones ne sont pas d'accord"))
                    }
                    DiagLog.w(tag, "DISPUTED by the server: " + row.settlementId.substring(0, 12))
                    true
                }
                Evidence.Sync.REFUSED -> {
                    store.markSync(row.settlementId, verdict, attempts, now, text.take(140))
                    lastError = text.take(140)
                    DiagLog.e(tag, "server refused the evidence: " + lastError)
                    true
                }
                else -> {
                    store.markSync(row.settlementId, Evidence.Sync.PENDING, attempts, now, "HTTP " + code)
                    lastError = "HTTP " + code
                    DiagLog.w(tag, "not verified yet (HTTP " + code + "), next try in " +
                        (Evidence.backoffMs(attempts) / 1000) + " s")
                    false
                }
            }
        } catch (e: Exception) {
            val why = e.message ?: e.javaClass.simpleName
            store.markSync(row.settlementId, Evidence.Sync.PENDING, attempts, now, why.take(140))
            lastError = why.take(140)
            DiagLog.w(tag, "server unreachable: " + why + " (next try in " + (Evidence.backoffMs(attempts) / 1000) + " s)")
            false
        }
    }

    /**
     * Build the evidence from the database, not from anything still in memory. This is
     * what lets a settlement survive a restart: the session ended days ago and every byte
     * it signed is still on disk.
     */
    fun packageFor(sessionHex: String): Evidence.Package? {
        val ss = store.session(sessionHex) ?: return null
        val cp = store.finalCheckpoint(sessionHex)
        val peerPub = store.peerKey(ss.peerShort)?.pub
        val r = Evidence.build(
            contractBytes = ss.contract,
            contractBuyerSig = ss.buyerSig,
            contractSellerSig = ss.sellerSig,
            checkpointBytes = cp?.body,
            checkpointSellerSig = cp?.sellerSig,
            checkpointBuyerSig = cp?.buyerSig,
            myPub = identity.pubBytes,
            peerPub = peerPub,
            iAmSeller = ss.role == "seller")
        if (!r.ok) DiagLog.i(tag, "no evidence for session " + sessionHex.take(8) + ": " + r.missing)
        return r.pkg
    }

    private fun post(url: String, body: ByteArray, headers: Map<String, String>): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15_000; c.readTimeout = 20_000; c.doOutput = true
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        c.outputStream.use { it.write(body) }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)
            ?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        return code to text
    }

    /** One line for the technical details. Never for a consumer card. */
    fun describe(): String {
        val (pending, reported, disputed) = store.syncCounts()
        return "  server verification: " + (if (!configured) "not configured (local mode)" else
            reported.toString() + " verified, " + pending + " pending, " + disputed + " disputed") +
            (if (lastError.isEmpty()) "" else "\n  last error: " + lastError)
    }
}
