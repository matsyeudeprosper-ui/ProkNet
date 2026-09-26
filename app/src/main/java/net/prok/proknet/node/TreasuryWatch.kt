package net.prok.proknet.node

import net.prok.proknet.core.Crypto
import net.prok.proknet.core.DeviceReceipt
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Msisdn
import net.prok.proknet.core.ReceiptParser
import net.prok.proknet.core.toHex

/**
 * v0.18.0: what the treasury phone does with an operator message about Prok's wallet.
 *
 * Money in ("vous avez reçu 503 F de 066123456") is reported to the Brain as an observed
 * top-up; money out ("vous avez envoyé 5 000 F à 055987654") as an observed debit that may
 * confirm a withdrawal the treasurer sent by hand. The Brain decides what either means and
 * credits nobody while `PROK_PAYMENTS_LIVE` is off. Anything this phone cannot read with
 * confidence is kept HERE, on Prok's own phone, for the treasurer to look at - the text of
 * a message never leaves the phone; only an amount, a hash of the other party's number and
 * a hash of the message itself do.
 *
 * Runs only under a treasury identity ([ReceiptCapture.treasuryActive]); a seller's phone
 * never constructs one of these with a live gate.
 */
class TreasuryWatch(private val ledger: LedgerSync, private val active: () -> Boolean) {

    private val tag = "TREASURY"

    /** A message the parser could not act on. Shown to the treasurer, resolved by hand. */
    class Pending(val smsHash: String, val text: String, val reason: String, val railGuess: String, val at: Long)

    fun onCandidate(c: DeviceReceipt.Candidate) {
        if (!active()) return
        val smsHash = Crypto.sha256((c.source.name + "|" + c.sender + "|" + c.text + "|" + c.receivedAt).toByteArray(Charsets.UTF_8)).toHex()
        synchronized(seen) { if (!seen.add(smsHash)) return; if (seen.size > 500) seen.remove(seen.first()) }
        val p = ReceiptParser.parseTreasury(c.text)
        val rail = railOf(c.sender, c.text, p.counterpartyDigits)
        if (!p.usable) {
            DiagLog.i(tag, "message kept for review: " + p.reason)
            synchronized(pending) { pending.add(Pending(smsHash, c.text, p.reason, rail, c.receivedAt)); while (pending.size > 50) pending.removeAt(0) }
            return
        }
        val h = Msisdn.hash(p.counterpartyDigits)
        val out = if (p.direction == ReceiptParser.Direction.CREDIT) ledger.observeCredit(rail, h, p.amountCentimes, smsHash, "sms")
                  else ledger.observeDebit(rail, h, p.amountCentimes, smsHash)
        DiagLog.i(tag, (if (p.direction == ReceiptParser.Direction.CREDIT) "credit " else "debit ") + p.amountCentimes + "c via " + rail + ": " + out.message)
        if (!out.ok) synchronized(pending) { pending.add(Pending(smsHash, c.text, "Brain: " + out.message, rail, c.receivedAt)) }
    }

    /**
     * Which of Prok's two wallets this message is about. The sender name says it when the
     * source is a real SMS; the text usually says it; failing both, the Congolese numbering
     * plan is the last hint (MTN 06x, Airtel 05x) and is marked as a guess by being last.
     */
    fun railOf(sender: String, text: String, counterparty: String): String {
        val s = (sender + " " + text).lowercase()
        if (s.contains("airtel")) return "AIRTEL"
        if (s.contains("mtn") || s.contains("momo") || s.contains("mobile money")) return "MTN"
        return if (counterparty.startsWith("05")) "AIRTEL" else "MTN"
    }

    companion object {
        private val seen = LinkedHashSet<String>()
        private val pending = ArrayList<Pending>()
        fun pendingReview(): List<Pending> = synchronized(pending) { ArrayList(pending) }
        fun dismiss(smsHash: String) { synchronized(pending) { pending.removeAll { it.smsHash == smsHash } } }
    }
}
