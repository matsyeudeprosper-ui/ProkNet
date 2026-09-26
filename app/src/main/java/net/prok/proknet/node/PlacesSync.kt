package net.prok.proknet.node

import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import net.prok.proknet.core.BrainPayload
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.LedgerView
import net.prok.proknet.core.PlacesView
import net.prok.proknet.core.SignedApi

/**
 * v0.19.0: the phone's side of the free Internet finder.
 *
 * Its own failure domain: with no Brain the cached city index still opens and says when it
 * was refreshed. The index is fetched with `if_newer=<last_update we hold>` so an unchanged
 * city costs one small answer; the raw JSON is kept in the app's files dir with the time it
 * was refreshed, and [PlacesView] reads it the same way on and off line.
 *
 * Contributions are signed requests: a coarse sighting (cell + radio hash + whether Android
 * validated Internet - never an SSID, never a coordinate), a check, a report, a claim, an
 * appeal. Every answer that carries the scout offer is passed back so the screen can say
 * "pas d'offre scout payée" when the server says so.
 *
 * All calls block on the network and must run on an IO thread.
 */
class PlacesSync(
    private val identity: Identity,
    private val brainUrl: () -> String,
    private val filesDir: File,
    val city: String = "Brazzaville",
) {
    private val tag = "PLACES"
    private val indexFile get() = File(filesDir, "places.index." + city.lowercase() + ".v1.json")
    private val metaFile get() = File(filesDir, "places.index." + city.lowercase() + ".v1.meta")

    val configured: Boolean get() = brainUrl().isNotEmpty()

    @Volatile var lastError = ""
        private set

    class Cached(val text: String, val refreshedAt: Long, val lastUpdate: Long)

    /** What the phone holds, however old; null when the city has never been fetched. */
    fun cached(): Cached? {
        return try {
            if (!indexFile.exists()) return null
            val meta = if (metaFile.exists()) metaFile.readText(Charsets.UTF_8).trim().split('|') else emptyList()
            Cached(indexFile.readText(Charsets.UTF_8), meta.getOrNull(0)?.toLongOrNull() ?: 0L, meta.getOrNull(1)?.toLongOrNull() ?: 0L)
        } catch (e: Exception) {
            DiagLog.w(tag, "cache read: " + (e.message ?: e.javaClass.simpleName)); null
        }
    }

    /**
     * Fetch the index if the Brain has something newer. Returns true when the cache is now
     * current (fresh or confirmed unchanged), false when nothing could be learned.
     */
    fun refresh(): Boolean {
        if (!configured) { lastError = "Réseau Prok non configuré"; return false }
        val have = cached()
        return try {
            val q = "?city=" + URLEncoder.encode(city, "UTF-8") + (if (have != null && have.lastUpdate > 0) "&if_newer=" + have.lastUpdate else "")
            val (code, text) = get("/v1/places/index" + q)
            if (code != 200) { lastError = "index: HTTP " + code; return false }
            val now = System.currentTimeMillis()
            if (BrainPayload.field(text, "unchanged") == "true" && have != null) {
                writeMeta(now, have.lastUpdate)
            } else {
                val idx = PlacesView.parseIndex(text) ?: run { lastError = "index: illisible"; return false }
                indexFile.writeText(text, Charsets.UTF_8)
                writeMeta(now, idx.lastUpdate)
            }
            lastError = ""
            true
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            DiagLog.w(tag, "index: " + lastError)
            false
        }
    }

    private fun writeMeta(refreshedAt: Long, lastUpdate: Long) {
        metaFile.writeText(refreshedAt.toString() + "|" + lastUpdate, Charsets.UTF_8)
    }

    // ---- contributions --------------------------------------------------------------------------

    class Outcome(val ok: Boolean, val message: String, val offer: PlacesView.Offer? = null, val reason: String = "", val raw: String = "")

    /** The scout offer as the server states it right now; null when unreachable. */
    fun offer(): PlacesView.Offer? {
        if (!configured) return null
        return try { val (code, text) = get("/v1/places/offer"); if (code == 200) PlacesView.parseOffer(text) else null } catch (e: Exception) { null }
    }

    fun rewards(): String? {
        if (!configured) return null
        return try { val (code, text) = get("/v1/places/rewards"); if (code == 200) text else null } catch (e: Exception) { null }
    }

    /** A coarse sighting from the phone's current cell. Never creates a venue; the server dedupes. */
    fun sighting(cell: String, radioHash: String, kind: String, signal: Int, validatedInternet: Boolean): Outcome =
        call("/v1/places/sighting", json("city" to city, "cell" to cell, "radio_hash" to radioHash, "kind" to kind, "signal" to signal,
            "validated_internet" to validatedInternet), "Observation envoyée")

    /** DEVICE = this phone connected and Android validated Internet; VISITOR = the person's word. */
    fun check(venueId: String, ok: Boolean, kind: String, note: String = ""): Outcome =
        call("/v1/places/check", json("venue_id" to venueId, "ok" to ok, "kind" to kind, "note" to note), if (ok) "Merci, contrôle enregistré" else "Merci, échec enregistré")

    fun report(venueId: String, kind: String, text: String): Outcome =
        call("/v1/places/report", json("venue_id" to venueId, "kind" to kind, "text" to text), "Signalement enregistré")

    fun claim(venueId: String, kind: String, text: String): Outcome =
        call("/v1/places/claim", json("venue_id" to venueId, "kind" to kind, "text" to text), "Demande enregistrée")

    fun appeal(rewardId: String, text: String): Outcome =
        call("/v1/places/appeal", json("reward_id" to rewardId, "text" to text), "Contestation enregistrée")

    // ---- v0.19.0 operator queue (the Brain checks the role; the phone only carries the request) ------

    /** Raw queue JSON (venues awaiting review, claims, reports, appeals), or null when unreachable/refused. */
    fun opsQueue(): String? {
        if (!configured) return null
        return try {
            val (code, text) = get("/v1/places/ops/queue")
            if (code == 200) text else { lastError = BrainPayload.field(text, "error").ifEmpty { "HTTP " + code }; null }
        } catch (e: Exception) { lastError = "queue: " + (e.message ?: e.javaClass.simpleName); null }
    }

    fun opsCheck(venueId: String, ok: Boolean, note: String): Outcome =
        call("/v1/places/ops/check", json("venue_id" to venueId, "ok" to ok, "note" to note), if (ok) "Vérifié : fonctionne" else "Vérifié : indisponible")
    fun opsPublish(venueId: String): Outcome = call("/v1/places/ops/publish", json("venue_id" to venueId), "Lieu publié")
    fun opsHide(venueId: String, reason: String): Outcome = call("/v1/places/ops/hide", json("venue_id" to venueId, "reason" to reason, "review" to true), "Lieu masqué")
    fun opsRemove(venueId: String, reason: String): Outcome = call("/v1/places/ops/remove", json("venue_id" to venueId, "reason" to reason), "Lieu retiré")
    fun opsClaim(claimId: String, accept: Boolean, note: String): Outcome =
        call("/v1/places/ops/claim", json("claim_id" to claimId, "accept" to accept, "note" to note), if (accept) "Demande acceptée" else "Demande refusée")
    fun opsReport(reportId: String, confirm: Boolean, note: String): Outcome =
        call("/v1/places/ops/report", json("report_id" to reportId, "confirm" to confirm, "note" to note), if (confirm) "Signalement confirmé" else "Signalement écarté")
    fun opsReward(rewardId: String, earn: Boolean, reason: String): Outcome =
        call("/v1/places/ops/reward", json("reward_id" to rewardId, "earn" to earn, "reason" to reason), if (earn) "Récompense accordée" else "Récompense refusée")

    // ---- plumbing ---------------------------------------------------------------------------------

    private fun call(path: String, body: ByteArray, okMessage: String): Outcome {
        if (!configured) return Outcome(false, "Réseau Prok non configuré")
        return try {
            val (code, text) = post(path, body)
            if (code == 200) {
                val offer = LedgerView.obj(text, "offer")?.let { PlacesView.parseOffer(it) }
                Outcome(true, okMessage, offer, raw = text)
            } else {
                Outcome(false, BrainPayload.field(text, "error").ifEmpty { "HTTP " + code }, reason = BrainPayload.field(text, "reason"), raw = text)
            }
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
        // signed like every other Brain call; the public GETs simply ignore the headers
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

    fun describe(): String {
        val c = cached()
        return "places: " + (if (!configured) "off" else "on") + ", cache " +
            (if (c == null) "empty" else "refreshed " + PlacesView.ageText(System.currentTimeMillis() - c.refreshedAt) + ", last_update " + c.lastUpdate) +
            (if (lastError.isNotEmpty()) " (" + lastError + ")" else "")
    }
}
