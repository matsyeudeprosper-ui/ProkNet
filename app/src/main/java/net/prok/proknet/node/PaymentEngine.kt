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
    /**
     * v0.16.1: how a payment message reaches the other phone. Set by the node.
     * Returns false when the peer is not reachable right now, which is normal and never
     * an error: the buyer may be at a kiosk on the other side of town.
     */
    @Volatile var send: ((peerId: String, line: String) -> Boolean)? = null

    /** v0.16.1: the seller's view of what it has agreed to watch for. */
    private val accepted = java.util.concurrent.ConcurrentHashMap<String, PaymentExpectation.Expectation>()

    /** v0.16.1: what the seller said about each expectation the buyer sent. */
    private val replies = java.util.concurrent.ConcurrentHashMap<String, net.prok.proknet.core.PayWire.Reply>()

    /** Receipts the seller has signed but not yet managed to deliver. */
    private val undelivered = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val tag = "PAYMENT"

    /** Live expectations. Small, short-lived, and rebuilt from the store on start. */
    private val expectations = java.util.concurrent.ConcurrentHashMap<String, PaymentExpectation.Expectation>()

    /** Message hashes already acted on, so the same message twice is one receipt. */
    private val seen = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Set by the node when notification access or SMS reading is actually available. */
    @Volatile var detectionAvailable = false

    /**
     * v0.16.1: THE privacy gate.
     *
     * v0.16.0 promised that content was inspected only while a payment was expected, but
     * in practice a running node meant every default-SMS notification was read. This makes
     * the promise structural: the listener asks this first and returns before it touches
     * the title or the body. No expectation, no reading.
     */
    fun paymentExpected(now: Long = System.currentTimeMillis()): Boolean =
        accepted.values.any { it.active(now) }

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
                "Le fournisseur n'a pas encore indiqué où recevoir son paiement.")
        val due = Wallet.payableTo(store.settlements(), identity.idHex, sellerId)
        val total = due.sumOf { it.buyerOwes }
        val c = PaymentExpectation.create(identity.idHex, sellerId, dest.rail, dest.hash(), total,
            due.map { it.settlementId }, active(now), now)
        if (c.ok) {
            val e = c.expectation!!
            store.saveExpectation(e)
            expectations[e.paymentId] = e
            // v0.16.1: the seller cannot watch for a payment it has never heard of, so the
            // expectation is signed and sent before anybody is told to go to a kiosk
            val sig = identity.sign(net.prok.proknet.core.PayWire.expectationSignData(e))
            val delivered = send?.invoke(sellerId, net.prok.proknet.core.PayWire.expectation(e, sig)) ?: false
            DiagLog.i(tag, "expecting " + Market.cfa(total) + " to " + dest.masked() +
                " within " + (PaymentExpectation.DEFAULT_WINDOW_MS / 60_000) + " min" +
                (if (delivered) ", told the provider" else ", provider not reachable yet"))
            onChanged()
        }
        return c
    }

    /**
     * v0.16.1: what the buyer may honestly say on screen.
     *
     * Telling somebody to walk to a kiosk while the seller has no idea a payment is coming
     * would waste their trip, so the screen says "préparation" until the seller has
     * actually accepted.
     */
    fun windowState(paymentId: String): net.prok.proknet.core.PayWire.Reply? = replies[paymentId]

    fun windowReady(paymentId: String): Boolean =
        net.prok.proknet.core.PayWire.ready(replies[paymentId] ?: net.prok.proknet.core.PayWire.Reply.NOT_READY)

    /** Ask the seller again. Called when a peer reappears. */
    fun resendPendingExpectations(now: Long = System.currentTimeMillis()): Int {
        var n = 0
        for (e in expectations.values.filter { it.active(now) && !windowReady(it.paymentId) }) {
            val sig = identity.sign(net.prok.proknet.core.PayWire.expectationSignData(e))
            if (send?.invoke(e.sellerId, net.prok.proknet.core.PayWire.expectation(e, sig)) == true) n++
        }
        return n
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

    // ---- the seller receives an expectation ---------------------------------------------------------

    /**
     * v0.16.1: a buyer says what it is about to pay. The seller decides whether to watch
     * for it, using its OWN records rather than the buyer's word about what is owed.
     *
     * This is the door that stops any phone nearby making the seller monitor arbitrary
     * amounts, and it is also where the same-amount lock becomes authoritative: the seller
     * is the only phone that can see every buyer's window at once.
     */
    fun onExpectation(line: String, buyerPub: ByteArray?, now: Long = System.currentTimeMillis()): net.prok.proknet.core.PayWire.Reply {
        val s = net.prok.proknet.core.PayWire.parseExpectation(line)
            ?: return net.prok.proknet.core.PayWire.Reply.BAD_SIGNATURE
        val e = s.expectation
        val dest = activeDestination(now)
        // what those sessions still owe, from our own settlement records
        val outstanding = e.includedSettlementIds.associateWith { id ->
            store.settlement(id)?.let { if (Settlement.isOutstanding(it.status)) it.buyerOwes else 0L } ?: -1L
        }
        val busy = accepted.values.any {
            it.active(now) && it.amountCentimes == e.amountCentimes && it.rail == e.rail && it.buyerId != e.buyerId
        }
        val reply = net.prok.proknet.core.PayWire.sellerDecision(
            s, buyerPub, identity.idHex, dest?.hash() ?: "", outstanding, busy,
            sellerReadiness() == Trust.SellerReadiness.READY, now)
        if (reply == net.prok.proknet.core.PayWire.Reply.ACCEPTED) {
            accepted[e.paymentId] = e
            store.saveExpectation(e)
            DiagLog.i(tag, "watching for " + Market.cfa(e.amountCentimes) + " from prok-" +
                e.buyerId.take(8) + " until " + ((e.expiresAt - now) / 60_000) + " min from now")
        } else {
            DiagLog.i(tag, "expectation refused (" + reply + ") from prok-" + e.buyerId.take(8))
        }
        send?.invoke(e.buyerId, net.prok.proknet.core.PayWire.expectationReply(e.paymentId, reply))
        onChanged()
        return reply
    }

    /** The buyer learns whether the seller is actually watching. */
    fun onExpectationReply(line: String) {
        val r = net.prok.proknet.core.PayWire.parseExpectationReply(line) ?: return
        replies[r.paymentId] = r.reply
        DiagLog.i(tag, "payment window " + r.reply + " for " + r.paymentId.take(12))
        onChanged()
    }

    fun replyFor(paymentId: String): net.prok.proknet.core.PayWire.Reply? = replies[paymentId]

    /**
     * v0.16.1: this window is over, so the seller stops holding the same-amount lock. The
     * next buyer owing that amount may go at once instead of waiting out the full window.
     */
    fun endExpectation(e: PaymentExpectation.Expectation, reason: String) {
        val sig = identity.sign(net.prok.proknet.core.PayWire.expectationEndSignData(e.paymentId, e.buyerId, reason))
        send?.invoke(e.sellerId, net.prok.proknet.core.PayWire.expectationEnd(e.paymentId, e.buyerId, reason, sig))
    }

    fun onExpectationEnd(line: String, buyerPub: ByteArray?) {
        val p = net.prok.proknet.core.PayWire.parseExpectationEnd(line) ?: return
        if (buyerPub == null || !net.prok.proknet.core.PayWire.verifyEnd(p, buyerPub)) return
        val e = accepted.remove(p.paymentId) ?: return
        store.saveExpectation(e.with(PaymentExpectation.State.CANCELLED))
        DiagLog.i(tag, "buyer closed the payment window (" + p.reason + "); the amount is free again")
        onChanged()
    }

    // ---- the seller receives a destination claim -------------------------------------------------------

    /** The buyer learns where to send the cash, and verifies the seller signed it. */
    fun onDestinationClaim(line: String, sellerPub: ByteArray?): Boolean {
        val d = net.prok.proknet.core.PayWire.parseDestinationClaim(line) ?: return false
        if (sellerPub == null) return false
        if (!DestinationClaim.verify(d.claim, sellerPub, d.sig)) {
            DiagLog.w(tag, "a payment destination did not verify; ignored")
            return false
        }
        val current = store.destinationClaim(d.claim.sellerId)
        if (!DestinationClaim.mayReplace(current, d.claim)) return false
        store.saveDestinationClaim(d.claim, d.sig)
        DiagLog.i(tag, "payment destination received for prok-" + d.claim.sellerId.take(8) +
            ": " + d.claim.rail + " " + d.claim.masked() + " v" + d.claim.version)
        onChanged()
        return true
    }

    /** Every buyer with an outstanding debt to us, so they can be told where to pay. */
    fun myDebtors(): List<String> = store.settlements(200)
        .filter { it.sellerId == identity.idHex && Settlement.isOutstanding(it.status) }
        .map { it.buyerId }.distinct()

    /** Push the destination to everybody who owes us and can be reached right now. */
    fun publishDestination(): Int = myDebtors().count { sendDestinationTo(it) }

    /** Hand our signed destination to a buyer that owes us. */
    fun sendDestinationTo(buyerId: String): Boolean {
        val c = myDestination() ?: return false
        val sig = store.destinationSig(identity.idHex) ?: return false
        return send?.invoke(buyerId, net.prok.proknet.core.PayWire.destinationClaim(c, sig)) ?: false
    }

    /**
     * v0.16.1: the destination that is ACTIVE right now. During the cooling period after a
     * change that is still the OLD number, so there is never a window where neither works
     * and a payment already in flight still lands somewhere valid.
     */
    fun activeDestination(now: Long = System.currentTimeMillis()): DestinationClaim.Claim? {
        val newest = store.destinationClaim(identity.idHex) ?: return null
        val previous = store.previousDestinationClaim(identity.idHex, newest.version)
        if (previous == null) return newest
        return if (DestinationClaim.usable(newest, previous, now)) newest else previous
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

        // v0.16.1: the SELLER's accepted windows, not the buyer's local ones
        val live = accepted.values.filter { it.active(now) }
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
        // persisted BEFORE any attempt to deliver it, so a signed receipt can never be
        // lost because the buyer happened to be out of range at that second
        store.saveReceipt(receipt, sig)
        clear(e, receipt)
        deliver(receipt, sig)
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
        accepted.remove(e.paymentId)          // the amount is free for the next buyer at once
        store.saveExpectation(done)
        DiagLog.i(tag, "PAYMENT RECEIVED: " + Market.cfa(r.observedCentimes) + " " +
            DeviceReceipt.word(r.confidence) + ", " + cleared + " session(s) cleared")
        onChanged()
    }

    /**
     * v0.16.1: try to hand the receipt to the buyer now, and keep it if we cannot. The
     * buyer is usually at a kiosk by this point, so failing is the normal case.
     */
    fun deliver(r: DeviceReceipt.Receipt, sig: ByteArray) {
        val line = net.prok.proknet.core.PayWire.receipt(r, sig)
        if (send?.invoke(r.buyerId, line) == true) {
            undelivered.remove(r.paymentId)
            DiagLog.i(tag, "receipt delivered to prok-" + r.buyerId.take(8))
        } else {
            undelivered[r.paymentId] = line
            DiagLog.i(tag, "buyer not reachable; receipt kept for later")
        }
    }

    /** Called whenever a peer appears, and on start. Idempotent by design. */
    fun retryDelivery(): Int {
        var sent = 0
        for ((id, line) in undelivered.toList()) {
            val buyer = net.prok.proknet.core.PayWire.parseReceipt(line)?.receipt?.buyerId ?: continue
            if (send?.invoke(buyer, line) == true) { undelivered.remove(id); sent++ }
        }
        return sent
    }

    /** Rebuild the undelivered queue from disk, so a restart does not lose a receipt. */
    fun restoreUndelivered() {
        for ((r, sig) in store.receiptsFrom(identity.idHex)) {
            if (!store.receiptDelivered(r.paymentId))
                undelivered[r.paymentId] = net.prok.proknet.core.PayWire.receipt(r, sig)
        }
    }

    /** The buyer's side of a receipt arriving over the wire. */
    fun onReceiptLine(line: String, sellerPub: ByteArray?): Boolean {
        val s = net.prok.proknet.core.PayWire.parseReceipt(line) ?: return false
        val e = store.expectations(200).firstOrNull { it.paymentId == s.receipt.paymentId }
        if (!net.prok.proknet.core.PayWire.buyerAcceptsReceipt(s, sellerPub, identity.idHex, e)) {
            DiagLog.w(tag, "a payment receipt was refused: it does not match an expectation of mine")
            return false
        }
        return onReceiptFromSeller(s.receipt, sellerPub!!, s.sig)
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
        // the window is finished, so the seller may free the amount for the next buyer
        expectations[r.paymentId]?.let {
            expectations[r.paymentId] = it.with(PaymentExpectation.State.MATCHED)
            store.saveExpectation(expectations[r.paymentId]!!)
            endExpectation(it, "paid")
        }
        DiagLog.i(tag, "payment confirmed by the provider: " + Market.cfa(r.observedCentimes) +
            ", " + cleared + " session(s) cleared")
        onChanged()
        return true
    }

    // ---- trust -----------------------------------------------------------------------------------

    /**
     * v0.16.1: payments this phone made **as a buyer**, and nothing else.
     *
     * v0.16.0 counted receipts where this identity was the buyer OR the seller, so selling
     * Internet twenty times silently raised your own borrowing allowance. Earning money is
     * not evidence that you pay your debts.
     */
    fun buyerVerifiedPayments(): Int = store.buyerReceiptCount(identity.idHex)

    /** Kept separate, for seller reputation later. Never feeds buyer credit. */
    fun sellerVerifiedReceipts(): Int = store.sellerReceiptCount(identity.idHex)

    fun describe(): String {
        val d = myDestination()
        return "  payment: " + (if (d == null) "no destination set" else d.rail.name + " " + d.masked() + " v" + d.version) +
            " | detection " + (if (detectionAvailable) "on" else "off") +
            " | expectations " + expectations.values.count { it.state == PaymentExpectation.State.WAITING } +
            " | buyer payments " + buyerVerifiedPayments() + ", seller receipts " + sellerVerifiedReceipts() +
            " | accepted windows " + accepted.values.count { it.active(System.currentTimeMillis()) } +
            " | undelivered receipts " + undelivered.size +
            (if (lastCandidate.isEmpty()) "" else "\n  last candidate: " + lastCandidate) +
            (if (lastOutcome.isEmpty()) "" else "\n  last outcome: " + lastOutcome)
    }
}
