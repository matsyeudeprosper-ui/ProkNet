package net.prok.proknet.node

import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import net.prok.proknet.core.BrainPayload
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.HoldGate
import net.prok.proknet.core.Identity
import net.prok.proknet.core.LedgerView
import net.prok.proknet.core.Msisdn
import net.prok.proknet.core.SignedApi

/**
 * v0.18.0: the phone's side of the Prok ledger.
 *
 * Its own failure domain, like the other Brain subsystems: if the ledger cannot be reached,
 * local Internet, settlements and payments carry on. Everything here is a signed request
 * to `/v1/ledger/...`; nothing is believed that the server did not answer.
 *
 * What it does NOT do: send money. A withdrawal request is a row in the treasurer's queue;
 * a person sends the transfer by hand and the app records what happened.
 *
 * All calls block on the network and must run on an IO thread. [view] is the last wallet
 * the Brain answered with, kept for the screens; it is refreshed on every Brain sweep.
 */
class LedgerSync(
    private val identity: Identity,
    private val brainUrl: () -> String,
    private val onChanged: () -> Unit = {},
) {
    private val tag = "LEDGER"
    private val running = AtomicBoolean(false)

    @Volatile var view: LedgerView.View? = null
        private set
    @Volatile var lastError = ""
        private set
    @Volatile var lastOk = 0L
        private set

    val configured: Boolean get() = brainUrl().isNotEmpty()

    // ---- the sweep ----------------------------------------------------------------------------

    /** Refresh the wallet. Called on the Brain timer; never overlaps itself. */
    fun run() {
        if (!configured) return
        if (!running.compareAndSet(false, true)) return
        try {
            refresh()
        } finally {
            running.set(false)
        }
    }

    fun refresh(): LedgerView.View? {
        if (!configured) return null
        return try {
            val (code, text) = get("/v1/ledger/wallet")
            if (code == 200) {
                val v = LedgerView.parse(text, System.currentTimeMillis())
                if (v != null) {
                    val before = view
                    view = v; lastOk = System.currentTimeMillis(); lastError = ""
                    if (before == null || changed(before, v)) onChanged()
                }
                v
            } else {
                lastError = "wallet: HTTP " + code; null
            }
        } catch (e: Exception) {
            lastError = e.message ?: e.javaClass.simpleName
            DiagLog.w(tag, "wallet: " + lastError)
            null
        }
    }

    private fun changed(a: LedgerView.View, b: LedgerView.View): Boolean =
        a.creditCentimes != b.creditCentimes || a.earnedCentimes != b.earnedCentimes || a.withdrawableCentimes != b.withdrawableCentimes ||
            a.withdrawal?.state != b.withdrawal?.state || a.withdrawal?.id != b.withdrawal?.id || a.treasury != b.treasury || a.paymentsLive != b.paymentsLive

    // ---- holds (seller side) ----------------------------------------------------------------------

    /**
     * Reserve the buyer's credit before admitting a paid session. Bounded: the link's read
     * thread is waiting on this through an executor, and a buyer should not stare at a
     * screen for longer than a few seconds.
     */
    fun hold(customerId: String, amountCentimes: Long): HoldGate.Answer {
        if (!configured) return HoldGate.Answer.notConfigured
        return try {
            val (code, text) = post("/v1/ledger/hold", json("customer_id" to customerId, "amount" to amountCentimes), timeoutMs = HOLD_TIMEOUT_MS)
            when {
                code == 200 -> HoldGate.Answer.granted(BrainPayload.field(text, "hold_id"))
                code in 400..499 -> HoldGate.Answer.refused(BrainPayload.field(text, "reason"), BrainPayload.field(text, "error"))
                else -> HoldGate.Answer.unreachable
            }
        } catch (e: Exception) {
            DiagLog.w(tag, "hold: " + (e.message ?: e.javaClass.simpleName))
            HoldGate.Answer.unreachable
        }
    }

    fun holdStarted(holdId: String, sessionHex: String) = fire("/v1/ledger/hold/started", json("hold_id" to holdId, "session_hex" to sessionHex))
    fun holdKeepalive(holdId: String) = fire("/v1/ledger/hold/keepalive", json("hold_id" to holdId))
    fun holdRelease(holdId: String) = fire("/v1/ledger/hold/release", json("hold_id" to holdId))

    private fun fire(path: String, body: ByteArray) {
        if (!configured) return
        try { post(path, body) } catch (e: Exception) { DiagLog.w(tag, path + ": " + (e.message ?: e.javaClass.simpleName)) }
    }

    // ---- withdrawals (payee side) --------------------------------------------------------------------

    class Outcome(val ok: Boolean, val message: String, val reason: String = "")

    fun requestWithdrawal(rail: String, msisdn: String, amountCentimes: Long): Outcome {
        if (!configured) return Outcome(false, "Réseau Prok non configuré")
        if (Msisdn.digits(msisdn).isEmpty()) return Outcome(false, "Numéro invalide : 9 chiffres attendus")
        return call("/v1/ledger/withdraw", json("rail" to rail, "msisdn" to Msisdn.digits(msisdn), "amount" to amountCentimes), "Retrait demandé")
    }

    fun cancelWithdrawal(id: String): Outcome = call("/v1/ledger/withdraw/cancel", json("withdrawal_id" to id), "Retrait annulé")

    // ---- the treasurer ------------------------------------------------------------------------------

    fun queue(): LedgerView.Queue? {
        if (!configured) return null
        return try {
            val (code, text) = get("/v1/ledger/treasury/queue")
            if (code == 200) LedgerView.parseQueue(text) else { lastError = "queue: HTTP " + code; null }
        } catch (e: Exception) { lastError = e.message ?: "queue failed"; null }
    }

    fun treasuryAction(withdrawalId: String, action: String, memo: String = "", evidence: String = ""): Outcome =
        call("/v1/ledger/treasury/withdrawal",
            json("withdrawal_id" to withdrawalId, "action" to action, "memo" to memo, "evidence" to evidence), LedgerView.actionLabel(action) + " : fait")

    fun observeCredit(rail: String, senderHash: String, amountCentimes: Long, smsHash: String, source: String): Outcome =
        call("/v1/ledger/treasury/topup", json("rail" to rail, "sender_hash" to senderHash, "amount" to amountCentimes, "sms_hash" to smsHash, "source" to source), "reçu")

    fun observeDebit(rail: String, counterpartyHash: String, amountCentimes: Long, smsHash: String): Outcome =
        call("/v1/ledger/treasury/debit", json("rail" to rail, "counterparty_hash" to counterpartyHash, "amount" to amountCentimes, "sms_hash" to smsHash), "envoi")

    fun testCredit(targetId: String, amountCentimes: Long, memo: String): Outcome =
        call("/v1/ledger/treasury/test_credit", json("target" to targetId, "amount" to amountCentimes, "memo" to memo), "Crédit test posté")

    fun balanceCheck(rail: String, typedCentimes: Long): Outcome =
        call("/v1/ledger/treasury/balance", json("rail" to rail, "typed" to typedCentimes), "Solde enregistré")

    fun reviewList(): List<Map<String, String>> {
        if (!configured) return emptyList()
        return try {
            val (code, text) = get("/v1/ledger/treasury/review")
            if (code == 200) LedgerView.objects(text, "items").map { o ->
                mapOf("topup_id" to BrainPayload.field(o, "topup_id"), "rail" to BrainPayload.field(o, "rail"), "amount" to BrainPayload.field(o, "amount"),
                    "state" to BrainPayload.field(o, "state"), "customer_id" to BrainPayload.field(o, "customer_id"), "claim_ref" to BrainPayload.field(o, "claim_ref"),
                    "stale" to BrainPayload.field(o, "stale"))
            } else emptyList()
        } catch (e: Exception) { emptyList() }
    }

    fun review(topupId: String, confirm: Boolean, memo: String): Outcome =
        call("/v1/ledger/treasury/review", json("topup_id" to topupId, "confirm" to confirm, "memo" to memo), if (confirm) "Crédité" else "Rejeté")

    // ---- the customer -----------------------------------------------------------------------------

    fun intent(rail: String, amountCentimes: Long): Pair<Outcome, String> {
        if (!configured) return Outcome(false, "Réseau Prok non configuré") to ""
        return try {
            val (code, text) = post("/v1/ledger/intent", json("rail" to rail, "amount" to amountCentimes))
            if (code == 200) Outcome(true, "ok") to text else Outcome(false, BrainPayload.field(text, "error").ifEmpty { "HTTP " + code }) to ""
        } catch (e: Exception) { Outcome(false, e.message ?: "échec") to "" }
    }

    // ---- plumbing ---------------------------------------------------------------------------------

    private fun call(path: String, body: ByteArray, okMessage: String): Outcome {
        if (!configured) return Outcome(false, "Réseau Prok non configuré")
        return try {
            val (code, text) = post(path, body)
            if (code == 200) {
                refresh()
                Outcome(true, okMessage)
            } else {
                Outcome(false, BrainPayload.field(text, "error").ifEmpty { "HTTP " + code }, BrainPayload.field(text, "reason"))
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
        // the signature covers method + canonical target, exactly as PaymentSync signs
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
        val v = view
        return "ledger: " + (if (!configured) "off" else if (v == null) "no wallet yet" + (if (lastError.isNotEmpty()) " (" + lastError + ")" else "")
            else "credit " + v.creditCentimes + "c, earned " + v.earnedCentimes + "c, withdrawable " + v.withdrawableCentimes + "c" +
                (v.withdrawal?.let { ", withdrawal " + it.state } ?: "") + (if (v.treasury) ", TREASURY" else "") +
                (if (v.paymentsLive) ", payments LIVE" else ", payments disabled"))
    }

    companion object {
        /** A buyer is waiting on this; the link thread is waiting on this. Short. */
        const val HOLD_TIMEOUT_MS = 6_000
    }
}
