package net.prok.proknet.node

import java.net.HttpURLConnection
import java.net.URL
import net.prok.proknet.core.BrainPayload
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.OwnerQuoteView
import net.prok.proknet.core.RelayOffersView
import net.prok.proknet.core.SignedApi

/**
 * v0.19.0: the phone's side of relay offers (/v1/jobs/...) and quotes (/v1/quotes/...).
 *
 * Its own failure domain, like [LedgerSync]: if the Brain cannot be reached, nothing here
 * blocks a local session. Everything is a signed request; nothing is believed that the
 * server did not answer. It does not decide anything: [RelayOffersView] and
 * [OwnerQuoteView] read the answers, the screens draw them.
 *
 * All calls block on the network and must run on an IO thread.
 */
class JobsSync(
    private val identity: Identity,
    private val brainUrl: () -> String,
) {
    private val tag = "JOBS"

    @Volatile var mine: RelayOffersView.Mine? = null
        private set
    @Volatile var lastError = ""
        private set

    val configured: Boolean get() = brainUrl().isNotEmpty()

    class Outcome(val ok: Boolean, val message: String, val reason: String = "", val text: String = "")

    // ---- the relay ------------------------------------------------------------------------------------

    fun refresh(): RelayOffersView.Mine? {
        if (!configured) return null
        return try {
            val (code, text) = get("/v1/jobs/mine")
            if (code == 200) {
                val m = RelayOffersView.parse(text, System.currentTimeMillis())
                if (m != null) { mine = m; lastError = "" }
                m
            } else { lastError = "offres: HTTP " + code; null }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            DiagLog.w(tag, "mine: " + lastError)
            null
        }
    }

    fun accept(offerId: String): Outcome = call("/v1/jobs/accept", json("offer_id" to offerId), "Offre acceptée")
    fun decline(offerId: String): Outcome = call("/v1/jobs/decline", json("offer_id" to offerId), "Offre refusée")
    fun cancel(offerId: String): Outcome = call("/v1/jobs/cancel", json("offer_id" to offerId), "Annulé")
    fun startBlock(offerId: String): Outcome = call("/v1/jobs/stay/start", json("offer_id" to offerId), "Bloc démarré")
    fun moveDeparted(offerId: String): Outcome = call("/v1/jobs/move/departed", json("offer_id" to offerId), "Départ enregistré")
    fun moveArrived(offerId: String): Outcome = call("/v1/jobs/move/arrived", json("offer_id" to offerId), "Arrivée enregistrée")
    fun moveReady(offerId: String): Outcome = call("/v1/jobs/move/ready", json("offer_id" to offerId), "Prêt déclaré — en attente du client")
    fun confirmReady(offerId: String): Outcome = call("/v1/jobs/move/confirm", json("offer_id" to offerId), "Relais confirmé")

    fun saveSettings(s: RelayOffersView.Settings): Outcome =
        call("/v1/jobs/settings", json("min_payout" to s.minPayoutCentimes, "battery_floor" to s.batteryFloorPct,
            "window_start" to s.windowStartMin, "window_end" to s.windowEndMin, "available" to s.available), "Réglages enregistrés")

    /**
     * One reachability probe: fetch the Brain's nonce and echo it. A correct, timely echo is
     * a successful probe on the running block; anything else counts as failed on the server.
     * Called on a timer while a block runs (the screen while open; the service should own it).
     */
    fun answerChallenge(offerId: String): Outcome {
        if (!configured) return Outcome(false, "Réseau Prok non configuré")
        return try {
            val (code, text) = get("/v1/jobs/stay/challenge?offer_id=" + offerId)
            if (code != 200) return Outcome(false, BrainPayload.field(text, "error").ifEmpty { "HTTP " + code }, BrainPayload.field(text, "reason"))
            val nonce = BrainPayload.field(text, "nonce")
            val (c2, t2) = post("/v1/jobs/stay/answer", json("offer_id" to offerId, "nonce" to nonce))
            if (c2 == 200) Outcome(true, "sonde ok", text = t2) else Outcome(false, BrainPayload.field(t2, "error").ifEmpty { "HTTP " + c2 }, BrainPayload.field(t2, "reason"))
        } catch (e: Exception) {
            Outcome(false, "Réseau Prok injoignable : " + (e.message ?: e.javaClass.simpleName))
        }
    }

    // ---- the owner ------------------------------------------------------------------------------------

    /**
     * Ask the Brain for a signed quote. The owner's inputs are what they ARE (free / paid /
     * sponsored, an optional campaign), never a tariff: the price and the split come back signed.
     */
    fun requestQuote(sourceKind: String, relayPresent: Boolean, sponsorCampaignId: String, allowanceBytes: Long, move: Boolean = false): Pair<Outcome, OwnerQuoteView.Quote?> {
        if (!configured) return Outcome(false, "Réseau Prok non configuré") to null
        return try {
            val (code, text) = post("/v1/quotes/quote", json("source_kind" to sourceKind, "source_min" to 0, "relay_present" to relayPresent,
                "sponsor_campaign_id" to sponsorCampaignId, "allowance_bytes" to allowanceBytes, "move" to move))
            if (code == 200) {
                val q = OwnerQuoteView.parse(text)
                if (q == null) Outcome(false, "Réponse illisible") to null else Outcome(true, "ok", text = text) to q
            } else Outcome(false, BrainPayload.field(text, "error").ifEmpty { "HTTP " + code }, BrainPayload.field(text, "reason")) to null
        } catch (e: Exception) {
            Outcome(false, "Réseau Prok injoignable : " + (e.message ?: e.javaClass.simpleName)) to null
        }
    }

    /** The owner's (or a customer's) decision on THAT quote: id, signature, yes/no. Nothing else. */
    fun decide(a: OwnerQuoteView.Acceptance): Outcome =
        call("/v1/quotes/accept", json(*a.pairs().toTypedArray()), if (a.decision == OwnerQuoteView.Decision.ACCEPT) "Offre acceptée" else "Offre refusée")

    fun compare(bytes: Long, totalCentimes: Long): String? {
        if (!configured) return null
        return try { val (code, text) = get("/v1/quotes/compare?bytes=" + bytes + "&total=" + totalCentimes); if (code == 200) text else null } catch (e: Exception) { null }
    }

    // ---- plumbing (LedgerSync's pattern) ------------------------------------------------------------------

    private fun call(path: String, body: ByteArray, okMessage: String): Outcome {
        if (!configured) return Outcome(false, "Réseau Prok non configuré")
        return try {
            val (code, text) = post(path, body)
            if (code == 200) Outcome(true, okMessage, text = text)
            else Outcome(false, BrainPayload.field(text, "error").ifEmpty { "HTTP " + code }, BrainPayload.field(text, "reason"))
        } catch (e: Exception) {
            Outcome(false, "Réseau Prok injoignable : " + (e.message ?: e.javaClass.simpleName))
        }
    }

    /** A flat JSON object. Strings are escaped; numbers and booleans are written as such. */
    private fun json(vararg pairs: Pair<String, Any?>): ByteArray {
        val sb = StringBuilder("{")
        var first = true
        for ((k, v) in pairs) {
            if (!first) sb.append(","); first = false
            sb.append('"').append(k).append("\":")
            when (v) {
                is Number, is Boolean -> sb.append(v.toString())
                else -> sb.append('"').append(BrainPayload.escape(v?.toString() ?: "")).append('"')
            }
        }
        return sb.append("}").toString().toByteArray(Charsets.UTF_8)
    }

    private fun post(path: String, body: ByteArray, timeoutMs: Int = 20_000): Pair<Int, String> = send("POST", path, body, timeoutMs)
    private fun get(path: String): Pair<Int, String> = send("GET", path, ByteArray(0), 20_000)

    private fun send(method: String, path: String, body: ByteArray, timeoutMs: Int): Pair<Int, String> {
        val c = URL(brainUrl() + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = minOf(15_000, timeoutMs); c.readTimeout = timeoutMs
        // the signature covers method + canonical target, exactly as LedgerSync signs
        val headers = SignedApi.sign(body, identity, System.currentTimeMillis(), method = method, path = path)
        for ((k, v) in headers.asMap()) c.setRequestProperty(k, v)
        if (method == "POST") {
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        return code to text
    }
}
