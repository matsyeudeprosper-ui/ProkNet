package net.prok.proknet.node

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import net.prok.proknet.core.BrainPayload
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.MessageStore
import net.prok.proknet.core.PayWire
import net.prok.proknet.core.ReceiptRules
import net.prok.proknet.core.SignedApi
import net.prok.proknet.core.Trust
import net.prok.proknet.core.toHex

/**
 * v0.16.2: the same signed payment objects, carried by the Brain when the phones are apart.
 *
 * The local path stays first-class. Two phones near each other exchange these directly and
 * never wait for a server. This exists for the case the product is actually built around:
 * the buyer finishes a session, walks away, and only decides to pay an hour later from the
 * other side of town, by which time the seller is somewhere else entirely.
 *
 * **Nothing here trusts the server.** Every object downloaded is the other phone's own
 * signed bytes, verified locally before anything is believed or acted on. The Brain can
 * withhold or delay a message; it cannot forge one, redirect a payment or invent a debt.
 *
 * Every object also carries a deterministic id, so arriving twice — once directly, once
 * from the Brain — is one logical object, one settlement and one trust increment.
 */
class PaymentSync(
    private val identity: Identity,
    private val store: MessageStore,
    private val payments: PaymentEngine,
    private val brainUrl: () -> String,
    private val devicePseudonym: () -> String,
) {
    private val tag = "PAYSYNC"
    private val running = AtomicBoolean(false)

    @Volatile var lastError = ""
        private set
    @Volatile var lastRun = 0L
        private set
    /** Derived by the SERVER from verified settlement state, never claimed by this phone. */
    @Volatile var deviceRisk: Trust.DeviceHistory? = null
        private set

    val configured: Boolean get() = brainUrl().isNotEmpty()

    /**
     * One pass. Safe to call often and cheap when nothing is outstanding: it returns
     * immediately without a server if there is nothing to push or pull.
     */
    fun run(now: Long = System.currentTimeMillis()) {
        if (!configured) return
        if (!running.compareAndSet(false, true)) return
        try {
            pushDestination()
            pullDestinations()
            pushExpectations()
            pullExpectations(now)
            pushReceipts()
            pullReceipts()
            pullReplies()
            refreshDeviceRisk()
            pullRules(now)
            lastRun = now
        } catch (e: Exception) {
            lastError = (e.message ?: e.javaClass.simpleName).take(140)
            DiagLog.w(tag, "payment sync: " + lastError)
        } finally { running.set(false) }
    }

    /**
     * The public key that goes with an identity, or null.
     *
     * A node id **is** the hash of its public key, so a key that derives to the id we are
     * looking at is the right key by construction — no directory, and no trust in whoever
     * handed it over. A Brain that substituted its own key would produce an id that is not
     * the one in the message, and everything below would refuse it.
     */
    private fun pubFor(idHex: String, offered: String?): ByteArray? {
        val known = store.peerKey(idHex.take(8))?.pub
        val pub = BrainPayload.pubFor(idHex, offered, known)
        if (pub == null && !offered.isNullOrEmpty())
            DiagLog.w(tag, "a key offered for prok-" + idHex.take(8) + " is not that identity; ignored")
        return pub
    }

    // ---- seller: publish where I am paid --------------------------------------------------------

    private fun pushDestination() {
        val c = payments.myDestination() ?: return
        val sig = store.destinationSig(identity.idHex) ?: return
        if (store.paySyncDone("dest:" + c.version)) return
        val body = json(
            "line" to PayWire.destinationClaim(c, sig),
            "seller_pub" to identity.pubBytes.toHex())
        if (post("/v1/pay/destination", body).first in 200..299) {
            store.markPaySynced("dest:" + c.version)
            DiagLog.i(tag, "payment destination v" + c.version + " published")
        }
    }

    /**
     * Buyer: collect the destination for a provider we owe but never received one from.
     *
     * Without this the Brain path cannot start at all. `beginPayment` refuses when there is
     * no destination, so a buyer who walked away before the seller published one would be
     * left owing money with no way to pay it.
     */
    private fun pullDestinations() {
        for (seller in payments.creditorsWithoutDestination()) {
            val (code, text) = get("/v1/pay/destination?seller=" + seller + "&rail=MTN_MOMO")
            if (code !in 200..299) continue
            val o = objectsFrom(text, "destination").firstOrNull() ?: continue
            val line = o["line"] ?: continue
            val claim = PayWire.parseDestinationClaim(line) ?: continue
            val pub = pubFor(claim.claim.sellerId, o["seller_pub"]) ?: continue
            if (payments.onDestinationClaim(line, pub))
                DiagLog.i(tag, "learned where to pay prok-" + seller.take(8) + " without meeting them")
        }
    }

    // ---- buyer: leave an expectation a distant seller can collect --------------------------------

    private fun pushExpectations() {
        for (e in payments.pendingForBrain()) {
            val key = "exp:" + e.paymentId
            if (store.paySyncDone(key)) continue
            val sig = identity.sign(PayWire.expectationSignData(e))
            val body = json(
                "line" to PayWire.expectation(e, sig),
                "buyer_pub" to identity.pubBytes.toHex(),
                "settlement_ids" to e.includedSettlementIds)
            val (code, _) = post("/v1/pay/expectation", body)
            if (code in 200..299) {
                store.markPaySynced(key)
                DiagLog.i(tag, "payment expectation left for a distant provider")
            }
        }
    }

    /** Seller: collect expectations left while we were away, and answer them. */
    private fun pullExpectations(now: Long) {
        val (code, text) = get("/v1/pay/expectations")
        if (code !in 200..299) return
        for (o in objectsFrom(text, "expectations")) {
            val line = o["line"] ?: continue
            val s = PayWire.parseExpectation(line) ?: continue
            val pub = pubFor(s.expectation.buyerId, o["buyer_pub"])
            // the seller's own decision, exactly as on the local path
            val reply = payments.onExpectation(line, pub, now)
            post("/v1/pay/reply", json("payment_id" to s.expectation.paymentId, "reply" to reply.name))
            DiagLog.i(tag, "answered a distant expectation: " + reply)
        }
    }

    /** Buyer: learn whether the seller accepted. */
    private fun pullReplies() {
        for (e in payments.pendingForBrain()) {
            if (payments.windowState(e.paymentId) != null) continue
            val (code, text) = get("/v1/pay/reply?payment=" + e.paymentId)
            if (code !in 200..299) continue
            val name = field(text, "reply")
            val reply = PayWire.Reply.values().firstOrNull { it.name == name } ?: continue
            payments.onExpectationReply(PayWire.expectationReply(e.paymentId, reply))
        }
    }

    // ---- seller: leave the receipt; buyer: collect it --------------------------------------------

    private fun pushReceipts() {
        for ((r, sig) in store.receiptsFrom(identity.idHex)) {
            val key = "receipt:" + r.paymentId
            if (store.paySyncDone(key)) continue
            val body = json(
                "line" to PayWire.receipt(r, sig),
                "seller_pub" to identity.pubBytes.toHex())
            if (post("/v1/pay/receipt", body).first in 200..299) {
                store.markPaySynced(key)
                DiagLog.i(tag, "receipt left for a distant buyer")
            }
        }
    }

    private fun pullReceipts() {
        val (code, text) = get("/v1/pay/receipts")
        if (code !in 200..299) return
        for (o in objectsFrom(text, "receipts")) {
            val line = o["line"] ?: continue
            val s = PayWire.parseReceipt(line) ?: continue
            val pub = pubFor(s.receipt.sellerId, o["seller_pub"])
            // verified locally against OUR OWN expectation; the server's word is not evidence
            if (payments.onReceiptLine(line, pub))
                post("/v1/pay/receipt/ack", json("payment_id" to s.receipt.paymentId))
        }
    }

    // ---- reinstall risk, derived by the server ----------------------------------------------------

    /**
     * The phone sends a domain-separated pseudonym and its identity. It does **not** send
     * whether it thinks it owes anything: the server derives that from its own verified
     * settlement records, because a phone that has just been reinstalled would otherwise
     * simply say no.
     */
    private fun refreshDeviceRisk() {
        val p = devicePseudonym()
        if (p.isEmpty()) return
        val (code, text) = post("/v1/device/risk", json("pseudonym" to p))
        if (code !in 200..299) return
        val unresolved = field(text, "unresolvedCentimes").toLongOrNull() ?: 0L
        val priors = field(text, "priorIdentityCount").toIntOrNull() ?: 0
        deviceRisk = Trust.DeviceHistory(p, List(priors) { "" }, unresolved)
        payments.deviceRiskUnresolved = unresolved
        if (unresolved > 0)
            DiagLog.w(tag, "this device still owes " + net.prok.proknet.core.Market.cfa(unresolved) +
                " under an earlier identity; paid sessions stay closed until it is settled")
    }

    // ---- signed parser rules ----------------------------------------------------------------------

    /**
     * Rules arrive as **signed data**, verified against a key pinned in the app. A
     * compromised Brain can withhold a config; it cannot forge one. If anything is wrong
     * we keep the rules we already trust, and detection never waits on this.
     */
    private fun pullRules(now: Long) {
        if (ReceiptRules.PINNED_CONFIG_KEY.isEmpty()) return
        val (code, text) = get("/v1/pay/rules")
        if (code !in 200..299) return
        if (ReceiptRules.acceptRemote(text, store, now))
            DiagLog.i(tag, "receipt rules updated from a signed configuration")
    }

    // ---- plumbing -----------------------------------------------------------------------------------

    private fun json(vararg pairs: Pair<String, Any>): ByteArray {
        val sb = StringBuilder("{")
        for ((i, p) in pairs.withIndex()) {
            if (i > 0) sb.append(',')
            sb.append('"').append(p.first).append("\":")
            when (val v = p.second) {
                is String -> sb.append('"').append(escape(v)).append('"')
                is List<*> -> sb.append(v.joinToString(",", "[", "]") { "\"" + escape(it.toString()) + "\"" })
                else -> sb.append(v)
            }
        }
        return sb.append('}').toString().toByteArray(Charsets.UTF_8)
    }

    private fun escape(s: String) = BrainPayload.escape(s)

    private fun field(text: String, key: String) = BrainPayload.field(text, key)

    private fun objectsFrom(text: String, key: String) = BrainPayload.objects(text, key)

    private fun post(path: String, body: ByteArray): Pair<Int, String> = send("POST", path, body)

    private fun get(path: String): Pair<Int, String> = send("GET", path, ByteArray(0))

    private fun send(method: String, path: String, body: ByteArray): Pair<Int, String> {
        val url = brainUrl() + path
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method
        c.connectTimeout = 15_000; c.readTimeout = 20_000
        // the signature covers the method and the path as well, so it cannot be moved to
        // another endpoint that happens to accept the same body
        val headers = SignedApi.sign(body, identity, System.currentTimeMillis(),
            method = method, path = path.substringBefore("?"))
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

    fun describe(): String = "  payment sync: " + (if (!configured) "not configured (local only)" else
        "last run " + (if (lastRun == 0L) "never" else ((System.currentTimeMillis() - lastRun) / 1000).toString() + " s ago")) +
        (deviceRisk?.let { " | device owes " + net.prok.proknet.core.Market.cfa(it.unresolvedCentimes) } ?: "") +
        (if (lastError.isEmpty()) "" else "\n  last error: " + lastError)
}
