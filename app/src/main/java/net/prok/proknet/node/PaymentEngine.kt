package net.prok.proknet.node

import net.prok.proknet.core.Crypto
import net.prok.proknet.core.DestinationClaim
import net.prok.proknet.core.DeviceReceipt
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.Market
import net.prok.proknet.core.MessageStore
import net.prok.proknet.core.PaymentExpectation
import net.prok.proknet.core.ReceiptParser
import net.prok.proknet.core.Settlement
import net.prok.proknet.core.Trust
import net.prok.proknet.core.Wallet
import net.prok.proknet.core.toHex

/**
 * v0.16.0: the state machine that decides whether a debt was paid.
 *
 * Neither the buyer nor the seller decides. The buyer cannot, because they are the one who
 * benefits from the wrong answer. The seller cannot, because a button they press is worth
 * nothing to a buyer who has already handed over cash. So the decision is made here, from
 * a message the operator sent to the seller's own phone:
 *
 *     PENDING_PAYMENT -> candidate arrives -> parser says CREDIT
 *                     -> exactly one live expectation fits
 *                     -> DEVICE_VERIFIED -> obligations cleared -> PAID
 *
 * Anything less than "exactly one" means nothing is cleared. The debt survives, the buyer
 * may try again, and nobody has lost anything except a few minutes.
 */
class PaymentEngine(
    private val identity: Identity,
    private val store: MessageStore,
    private val onChanged: () -> Unit,
) {
    private val tag = "PAYMENT"

    /** Live expectations. Small, short-lived, and rebuilt from the store on start. */
    private val expectations = java.util.concurrent.ConcurrentHashMap<String, PaymentExpectation.Expectation>()

    /** Message hashes already acted on, so the same message twice is one receipt. */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Set by the node when notification access or SMS reading is actually available. */
    @Volatile var detectionAvailable = false

    @Volatile var lastCandidate = ""
        private set
    @Volatile var lastOutcome = ""
        private set

    // ---- the seller's destination -----------------------------------------------------------------

    /**
     * The seller records where it wants to be paid and signs it. Only the seller can do
     * this, and a change needs a higher version than whatever is already known.
     */
    fun claimDestination(rail: Settlement.Rail, msisdn: String, now: Long): DestinationClaim.Claim? {
        val current = store.destinationClaim(identity.idHex)
        val claim = DestinationClaim.Claim(identity.idHex, rail, msisdn,
            DestinationClaim.nextVersion(current), now)
        if (!claim.valid) return null
        if (!DestinationClaim.mayReplace(current, claim)) return null
        val sig = identity.sign(claim.signData())
        store.saveDestinationClaim(claim, sig)
        DiagLog.i(tag, "payment destination v" + claim.version + " claimed for " + rail +
            " (" + claim.masked() + ")" + (if (current != null) ", usable in a few minutes" else ""))
        onChanged()
        return claim
    }

    fun myDestination(): DestinationClaim.Claim? = store.destinationClaim(identity.idHex)

    fun sellerReadiness(): Trust.SellerReadiness =
        Trust.sellerReadiness(myDestination() != null, detectionAvailable)

    // ---- the buyer starts a payment ----------------------------------------------------------------

    /**
     * The buyer taps PAYER. This creates the window we will match a receipt against; it
     * does not assert that anything has been paid.
     */
    fun beginPayment(sellerId: String, now: Long = System.currentTimeMillis()): PaymentExpectation.Creation {
        val dest = store.destinationClaim(sellerId)
            ?: return PaymentExpectation.Creation(null, PaymentExpectation.Refusal.NO_DESTINATION,
                "Le fournisseur n'a pas encore indiqué où être payé.")
        val due = Wallet.payableTo(store.settlements(), identity.idHex, sellerId)
        val total = due.sumOf { it.buyerOwes }
        val c = PaymentExpectation.create(identity.idHex, sellerId, dest.rail, dest.hash(), total,
            due.map { it.settlementId }, active(now), now)
        if (c.ok) {
            val e = c.expectation!!
            store.saveExpectation(e)
            expectations[e.paymentId] = e
            DiagLog.i(tag, "expecting " + Market.cfa(total) + " to " + dest.masked() +
                " within " + (PaymentExpectation.DEFAULT_WINDOW_MS / 60_000) + " min")
            onChanged()
        }
        return c
    }

    fun active(now: Long = System.currentTimeMillis()): List<PaymentExpectation.Expectation> {
        sweep(now)
        return expectations.values.filter { it.active(now) }
    }

    /** Close anything whose window has passed. The debt is untouched. */
    fun sweep(now: Long) {
        for (e in expectations.values.toList()) {
            if (e.expired(now)) {
                val done = e.with(PaymentExpectation.State.EXPIRED)
                expectations[e.paymentId] = done
                store.saveExpectation(done)
                DiagLog.i(tag, "payment window closed with nothing received: " + Market.cfa(e.amountCentimes))
            }
        }
    }

    fun restore() {
        for (e in store.expectations()) expectations[e.paymentId] = e
    }

    // ---- a candidate message arrives ----------------------------------------------------------------

    /**
     * The only entry point for payment evidence. Every source funnels here, and everything
     * that is not a source is refused by construction: there is no method that takes a
     * screenshot, clipboard text, a typed sentence, or anything the buyer supplied.
     */
    fun onCandidate(c: DeviceReceipt.Candidate, defaultSmsPackage: String, now: Long = System.currentTimeMillis()): Boolean {
        val why = DeviceReceipt.refusalReason(c, defaultSmsPackage)
        if (!DeviceReceipt.eligible(c, defaultSmsPackage)) {
            lastOutcome = "refused: " + why
            DiagLog.i(tag, "candidate refused: " + why)
            return false
        }
        val hash = c.evidenceHash()
        if (seen.putIfAbsent(hash, now) != null) {
            lastOutcome = "already handled"
            return false
        }

        val live = active(now)
        if (live.isEmpty()) {
            // nothing outstanding: do not look any closer at the seller's private messages
            lastOutcome = "no payment expected"
            return false
        }

        // the expected amount is a hint to the parser, never a way to invent one
        val hint = live.map { it.amountCentimes }.distinct().singleOrNull() ?: 0
        val parsed = ReceiptParser.parse(c.text, hint)
        lastCandidate = "source " + c.source + ", " + parsed.verdict + " (" + parsed.confidence + ")"

        val outcome = PaymentExpectation.match(parsed.amountCentimes, live.first().sellerId,
            live.first().rail, "", c.receivedAt, live, parsed)
        lastOutcome = outcome.match.toString() + ": " + outcome.reason
        if (outcome.match != PaymentExpectation.Match.ONE) {
            DiagLog.i(tag, "no automatic match: " + outcome.reason)
            return false
        }

        val e = outcome.expectation!!
        val receipt = DeviceReceipt.receiptFor(e, c, parsed, c.receivedAt) ?: return false
        val sig = identity.sign(receipt.signData())
        store.saveReceipt(receipt, sig)
        clear(e, receipt)
        return true
    }

    /**
     * Mark the obligations this payment covered as paid, and close the expectation. The
     * receipt is what will travel to the buyer; the buyer never has to be present.
     */
    private fun clear(e: PaymentExpectation.Expectation, r: DeviceReceipt.Receipt) {
        var cleared = 0
        for (id in r.matchedSettlementIds) {
            val o = store.settlement(id) ?: continue
            if (o.status == Settlement.Status.CONFIRMED) continue
            store.saveSettlement(Settlement.applyPayment(o, Settlement.Status.CONFIRMED,
                o.rail, r.reference.ifEmpty { "device:" + r.messageEvidenceHash.take(12) }, r.observedAt))
            cleared++
        }
        val done = e.with(PaymentExpectation.State.MATCHED)
        expectations[e.paymentId] = done
        store.saveExpectation(done)
        DiagLog.i(tag, "PAYMENT RECEIVED: " + Market.cfa(r.observedCentimes) + " " +
            DeviceReceipt.word(r.confidence) + ", " + cleared + " session(s) cleared")
        onChanged()
    }

    /**
     * A receipt arriving from the seller, over the tunnel or the brain. Idempotent: the
     * buyer may be handed the same one several times as it propagates.
     */
    fun onReceiptFromSeller(r: DeviceReceipt.Receipt, sellerPub: ByteArray, sig: ByteArray): Boolean {
        if (!DeviceReceipt.verify(r, sellerPub, sig)) {
            DiagLog.w(tag, "a payment receipt did not verify; ignored")
            return false
        }
        if (r.buyerId != identity.idHex) return false
        if (store.hasReceipt(r.paymentId)) return true          // already applied
        store.saveReceipt(r, sig)
        var cleared = 0
        for (id in r.matchedSettlementIds) {
            val o = store.settlement(id) ?: continue
            if (o.status == Settlement.Status.CONFIRMED) continue
            store.saveSettlement(Settlement.applyPayment(o, Settlement.Status.CONFIRMED, o.rail,
                r.reference.ifEmpty { "device:" + r.messageEvidenceHash.take(12) }, r.observedAt))
            cleared++
        }
        DiagLog.i(tag, "payment confirmed by the provider: " + Market.cfa(r.observedCentimes) +
            ", " + cleared + " session(s) cleared")
        onChanged()
        return true
    }

    // ---- trust -----------------------------------------------------------------------------------

    /** How many payments this phone has had cleared by observed evidence. Nothing else counts. */
    fun verifiedPayments(): Int = store.receiptCount(identity.idHex)

    fun describe(): String {
        val d = myDestination()
        return "  payment: " + (if (d == null) "no destination set" else d.rail.name + " " + d.masked() + " v" + d.version) +
            " | detection " + (if (detectionAvailable) "on" else "off") +
            " | expectations " + expectations.values.count { it.state == PaymentExpectation.State.WAITING } +
            " | verified payments " + verifiedPayments() +
            (if (lastCandidate.isEmpty()) "" else "\n  last candidate: " + lastCandidate) +
            (if (lastOutcome.isEmpty()) "" else "\n  last outcome: " + lastOutcome)
    }
}
