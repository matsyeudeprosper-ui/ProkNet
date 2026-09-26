package net.prok.proknet.node

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import net.prok.proknet.core.BrainPayload
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.LedgerView
import net.prok.proknet.core.MarketView
import net.prok.proknet.core.SignedApi

/**
 * v0.19.0: the phone's side of Prok Market.
 *
 * Browsing is public: search, a listing and its photos are plain GETs with no signature,
 * so a phone without a payment account (or with a fresh identity) can look. Everything
 * that names the caller - selling, paying, chatting, reporting - is a signed request to
 * `/v1/market/...`, exactly as [LedgerSync] signs its calls.
 *
 * What it does NOT do: move money. An invoice is a reference the seller puts in a Mobile
 * Money transfer they make themselves; a treasurer confirms it on the Brain; this class
 * only asks what the Brain now says.
 *
 * All calls block on the network and must run on an IO thread.
 */
class MarketSync(
    private val identity: Identity,
    private val brainUrl: () -> String,
) {
    private val tag = "MARKET"

    @Volatile var lastError = ""
        private set

    val configured: Boolean get() = brainUrl().isNotEmpty()
    val myId: String get() = identity.idHex

    class Outcome(val ok: Boolean, val message: String, val text: String = "", val reason: String = "")

    // ---- public (unsigned) -------------------------------------------------------------------------

    fun search(q: String = "", category: String = "", minPriceCentimes: Long = 0, maxPriceCentimes: Long = 0, neighbourhood: String = "",
               lat: Double = 0.0, lon: Double = 0.0, limit: Int = 20, offset: Int = 0): MarketView.SearchPage? {
        if (!configured) return null
        val params = ArrayList<String>()
        if (q.isNotEmpty()) params += "q=" + enc(q)
        if (category.isNotEmpty()) params += "category=" + enc(category)
        if (minPriceCentimes > 0) params += "min_price=" + minPriceCentimes
        if (maxPriceCentimes > 0) params += "max_price=" + maxPriceCentimes
        if (neighbourhood.isNotEmpty()) params += "neighbourhood=" + enc(neighbourhood)
        if (lat != 0.0 && lon != 0.0) { params += "lat=" + lat; params += "lon=" + lon }
        params += "limit=" + limit; params += "offset=" + offset
        return try {
            val (code, text) = plainGet("/v1/market/search?" + params.joinToString("&"))
            if (code == 200) MarketView.parseSearch(text) else { lastError = "search: HTTP " + code; null }
        } catch (e: Exception) { fail("search", e); null }
    }

    fun listing(id: String): MarketView.Listing? {
        if (!configured) return null
        return try {
            // signed when we have an identity, so a seller sees their own non-public listing too
            val (code, text) = get("/v1/market/listing?id=" + enc(id))
            if (code == 200) MarketView.parseListing(text) else { lastError = "listing: HTTP " + code; null }
        } catch (e: Exception) { fail("listing", e); null }
    }

    /** The JPEG bytes of a public photo, or null. [url] is the `photo_urls` entry the Brain gave. */
    fun photo(url: String): ByteArray? {
        if (!configured) return null
        return try {
            val c = URL(brainUrl() + url).openConnection() as HttpURLConnection
            c.connectTimeout = 15_000; c.readTimeout = 20_000
            val code = c.responseCode
            if (code == 200) c.inputStream.use { it.readBytes() } else null
        } catch (e: Exception) { null }
    }

    fun prices(): String? {
        if (!configured) return null
        return try { val (code, text) = plainGet("/v1/market/prices"); if (code == 200) text else null } catch (e: Exception) { null }
    }

    // ---- the seller ------------------------------------------------------------------------------------

    fun me(): MarketView.SellerStatus? {
        if (!configured) return null
        return try {
            val (code, text) = get("/v1/market/me")
            if (code == 200) MarketView.parseSellerStatus(text) else { lastError = "me: HTTP " + code; null }
        } catch (e: Exception) { fail("me", e); null }
    }

    fun registerSeller(phone: String, adultAttested: Boolean, businessProof: String = ""): Outcome =
        call("/v1/market/seller", json("phone" to phone, "adult_attested" to adultAttested, "business_proof" to businessProof), "Profil vendeur enregistré")

    /**
     * The form. The answer carries the listing and, when it is paid by invoice, the invoice
     * with its reference and instruction. A refusal (prohibited content, a phone number in
     * the text) comes back as a message the seller can read.
     */
    fun submitListing(title: String, description: String, category: String, priceCentimes: Long, condition: String,
                      neighbourhood: String, pickupOptions: String, lat: Double, lon: Double, payWith: String, rail: String): Outcome =
        call("/v1/market/listing", json("title" to title, "description" to description, "category" to category, "price_centimes" to priceCentimes,
            "condition" to condition, "neighbourhood" to neighbourhood, "pickup_options" to pickupOptions, "lat" to lat, "lon" to lon,
            "pay_with" to payWith, "rail" to rail), "Annonce enregistrée")

    fun editListing(id: String, title: String, description: String, category: String, priceCentimes: Long, condition: String,
                    neighbourhood: String, pickupOptions: String): Outcome =
        call("/v1/market/listing/edit", json("id" to id, "title" to title, "description" to description, "category" to category,
            "price_centimes" to priceCentimes, "condition" to condition, "neighbourhood" to neighbourhood, "pickup_options" to pickupOptions), "Annonce modifiée")

    fun withdraw(id: String): Outcome = call("/v1/market/listing/withdraw", json("id" to id), "Annonce retirée")
    fun payListing(id: String, rail: String): Outcome = call("/v1/market/listing/pay", json("id" to id, "rail" to rail), "Facture créée")
    fun renew(id: String, rail: String): Outcome = call("/v1/market/listing/renew", json("id" to id, "rail" to rail), "Facture de renouvellement créée")
    fun boost(id: String, zone: String, rail: String): Outcome = call("/v1/market/listing/boost", json("id" to id, "zone" to zone, "rail" to rail), "Facture de mise en avant créée")
    fun buyPackage(kind: String, rail: String): Outcome = call("/v1/market/package", json("kind" to kind, "rail" to rail), "Facture créée")
    fun cancelInvoice(id: String): Outcome = call("/v1/market/invoice/cancel", json("id" to id), "Facture annulée")

    /** Raw JPEG bytes as the body, `Content-Type: image/jpeg`; the signature covers the bytes. */
    fun uploadPhoto(listingId: String, jpeg: ByteArray): Outcome {
        if (!configured) return Outcome(false, "Réseau Prok non configuré")
        return try {
            val (code, text) = send("POST", "/v1/market/photo?listing=" + enc(listingId), jpeg, 30_000, "image/jpeg")
            if (code == 200) Outcome(true, "Photo envoyée", text) else Outcome(false, BrainPayload.field(text, "error").ifEmpty { "HTTP " + code }, text, BrainPayload.field(text, "reason"))
        } catch (e: Exception) { Outcome(false, "Réseau Prok injoignable : " + (e.message ?: e.javaClass.simpleName)) }
    }

    fun myListings(): List<MarketView.Listing>? {
        if (!configured) return null
        return try {
            val (code, text) = get("/v1/market/my/listings")
            if (code == 200) MarketView.parseListings(text) else { lastError = "listings: HTTP " + code; null }
        } catch (e: Exception) { fail("my/listings", e); null }
    }

    fun myInvoices(): List<MarketView.Invoice>? {
        if (!configured) return null
        return try {
            val (code, text) = get("/v1/market/my/invoices")
            if (code == 200) MarketView.parseInvoices(text) else { lastError = "invoices: HTTP " + code; null }
        } catch (e: Exception) { fail("my/invoices", e); null }
    }

    fun invoice(id: String): MarketView.Invoice? {
        if (!configured) return null
        return try {
            val (code, text) = get("/v1/market/invoice?id=" + enc(id))
            if (code == 200) MarketView.parseInvoice(text) else null
        } catch (e: Exception) { fail("invoice", e); null }
    }

    // ---- chat, report, block -------------------------------------------------------------------------

    fun sendMessage(listingId: String, to: String, body: String, offerCentimes: Long = 0): Outcome =
        call("/v1/market/message", json("listing" to listingId, "to" to to, "body" to body, "offer_centimes" to offerCentimes), "Envoyé")

    fun thread(listingId: String, other: String, since: Long = 0): MarketView.Thread? {
        if (!configured) return null
        return try {
            val (code, text) = get("/v1/market/thread?listing=" + enc(listingId) + "&other=" + enc(other) + "&since=" + since)
            if (code == 200) MarketView.parseThread(text) else null
        } catch (e: Exception) { fail("thread", e); null }
    }

    fun inbox(): List<MarketView.InboxRow>? {
        if (!configured) return null
        return try {
            val (code, text) = get("/v1/market/inbox")
            if (code == 200) MarketView.parseInbox(text) else null
        } catch (e: Exception) { fail("inbox", e); null }
    }

    fun block(user: String, on: Boolean): Outcome = call("/v1/market/block", json("user" to user, "on" to on), if (on) "Personne bloquée" else "Personne débloquée")

    fun report(listingId: String, kind: String, text: String, userId: String = ""): Outcome =
        call("/v1/market/report", json("listing" to listingId, "user" to userId, "kind" to kind, "text" to text), "Signalement envoyé")

    // ---- v0.19.0 operator + treasury (the Brain checks the role; the phone only carries the request) --

    fun operatorQueue(): List<MarketView.Listing>? = getOr("/v1/market/operator/queue")?.let { MarketView.parseListings(it) }
    /** Raw open-reports JSON: id, listing_id, user_id, kind, text, created_at. */
    fun operatorReports(): String? = getOr("/v1/market/operator/reports")
    fun review(listingId: String, approve: Boolean, note: String): Outcome =
        call("/v1/market/operator/review", json("listing" to listingId, "approve" to approve, "note" to note), if (approve) "Annonce publiée" else "Annonce refusée")
    fun resolveReport(reportId: String, action: String): Outcome =
        call("/v1/market/operator/report", json("report" to reportId, "action" to action), "Signalement traité : " + action)
    fun setTrusted(sellerId: String, trusted: Boolean): Outcome =
        call("/v1/market/operator/trust", json("seller" to sellerId, "trusted" to trusted), if (trusted) "Vendeur de confiance" else "Confiance retirée")
    fun blockSeller(sellerId: String, blocked: Boolean): Outcome =
        call("/v1/market/operator/block_seller", json("seller" to sellerId, "blocked" to blocked), if (blocked) "Vendeur bloqué" else "Vendeur débloqué")

    /** Treasury: the invoices in one state (OPEN by default) with references, for matching a statement line. */
    fun treasuryInvoices(state: String = "OPEN"): List<MarketView.Invoice>? =
        getOr("/v1/market/treasury/invoices?state=" + enc(state))?.let { LedgerView.objects(it, "invoices").mapNotNull { o -> MarketView.parseInvoice(o) } }
    fun treasuryConfirm(invoiceId: String, operatorTxnId: String, amountSeenCentimes: Long, payerHash: String, rail: String, evidence: String): Outcome =
        call("/v1/market/treasury/confirm", json("invoice" to invoiceId, "operator_txn_id" to operatorTxnId, "amount_seen" to amountSeenCentimes,
            "payer_hash" to payerHash, "rail" to rail, "evidence" to evidence), "Facture payée : le service démarre")
    /** Paste one operator message: the Brain looks for a reference + exact amount; it never guesses. */
    fun treasuryMatch(text: String, amountCentimes: Long, operatorTxnId: String, rail: String): Outcome =
        call("/v1/market/treasury/match", json("text" to text, "amount" to amountCentimes, "operator_txn_id" to operatorTxnId, "rail" to rail), "Message analysé")
    fun treasuryRefund(invoiceId: String, evidence: String): Outcome =
        call("/v1/market/treasury/refund", json("invoice" to invoiceId, "evidence" to evidence), "Remboursement enregistré")

    private fun getOr(path: String): String? {
        if (!configured) return null
        return try {
            val (code, text) = get(path)
            if (code == 200) text else { lastError = BrainPayload.field(text, "error").ifEmpty { "HTTP " + code }; null }
        } catch (e: Exception) { fail(path, e); null }
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    private fun call(path: String, body: ByteArray, okMessage: String): Outcome {
        if (!configured) return Outcome(false, "Réseau Prok non configuré")
        return try {
            val (code, text) = post(path, body)
            if (code == 200) Outcome(true, okMessage, text)
            else Outcome(false, BrainPayload.field(text, "error").ifEmpty { "HTTP " + code }, text, BrainPayload.field(text, "reason"))
        } catch (e: Exception) {
            Outcome(false, "Réseau Prok injoignable : " + (e.message ?: e.javaClass.simpleName))
        }
    }

    private fun fail(what: String, e: Exception) {
        lastError = what + ": " + (e.message ?: e.javaClass.simpleName)
        DiagLog.w(tag, lastError)
    }

    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")

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

    private fun post(path: String, body: ByteArray, timeoutMs: Int = 20_000): Pair<Int, String> = send("POST", path, body, timeoutMs, "application/json; charset=utf-8")
    private fun get(path: String): Pair<Int, String> = send("GET", path, ByteArray(0), 20_000, "")

    private fun send(method: String, path: String, body: ByteArray, timeoutMs: Int, contentType: String): Pair<Int, String> {
        val c = URL(brainUrl() + path).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = minOf(15_000, timeoutMs); c.readTimeout = timeoutMs
        // the signature covers method + canonical target, exactly as LedgerSync signs
        val headers = SignedApi.sign(body, identity, System.currentTimeMillis(), method = method, path = path)
        for ((k, v) in headers.asMap()) c.setRequestProperty(k, v)
        if (method == "POST") {
            c.doOutput = true
            c.setRequestProperty("Content-Type", contentType)
            c.outputStream.use { it.write(body) }
        }
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        return code to text
    }

    /** No signature at all: what anybody, with or without a Prok identity, may read. */
    private fun plainGet(path: String): Pair<Int, String> {
        val c = URL(brainUrl() + path).openConnection() as HttpURLConnection
        c.requestMethod = "GET"
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        val code = c.responseCode
        val text = (if (code in 200..299) c.inputStream else c.errorStream)?.bufferedReader(Charsets.UTF_8)?.readText() ?: ""
        return code to text
    }
}
