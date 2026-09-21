package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.16.1: the four messages that have to cross between two phones, and the check each
 * side makes before it believes one.
 *
 * v0.16.0 had the models and no wire, so the flow could not complete. These tests walk the
 * whole loop: the seller says where it is paid, the buyer says what it will pay, the seller
 * agrees to watch, the operator's money arrives, and the buyer's debt clears — verifying at
 * every hop, because the carrier proving who sent a message is not the same as the content
 * being signed by the right party.
 */
class PayWireTest {
    private val now = 1_700_000_000_000L

    private class Party {
        private val kp = Crypto.generateKeyPair()
        val pub: ByteArray = Crypto.publicBytes(kp.public)
        val id: String = Crypto.deriveId(pub).toHex()
        fun sign(d: ByteArray): ByteArray = Crypto.sign(kp.private, d)
    }

    private val buyer = Party()
    private val buyer2 = Party()
    private val seller = Party()
    private val stranger = Party()

    private val msisdn = "066123456"
    private fun destHash() = PaymentExpectation.destinationHash(Settlement.Rail.MTN_MOMO, msisdn)

    private fun claim(version: Int = 1, who: Party = seller, number: String = msisdn) =
        DestinationClaim.Claim(who.id, Settlement.Rail.MTN_MOMO, number, version, now)

    private fun expectation(
        b: Party = buyer, amount: Long = 5_000, ids: List<String> = listOf("s1"),
        dest: String = destHash(), at: Long = now, window: Long = PaymentExpectation.DEFAULT_WINDOW_MS,
    ) = PaymentExpectation.Expectation(
        PaymentExpectation.idFor(b.id, seller.id, amount, at), b.id, seller.id,
        Settlement.Rail.MTN_MOMO, dest, amount, at, at, at + window, ids)

    private fun signedExpectation(e: PaymentExpectation.Expectation, by: Party = buyer) =
        PayWire.parseExpectation(PayWire.expectation(e, by.sign(PayWire.expectationSignData(e))))!!

    private fun decide(
        s: PayWire.SignedExpectation, pub: ByteArray? = buyer.pub, dest: String = destHash(),
        outstanding: Map<String, Long> = mapOf("s1" to 5_000L), busy: Boolean = false,
        ready: Boolean = true, at: Long = now + 1_000,
    ) = PayWire.sellerDecision(s, pub, seller.id, dest, outstanding, busy, ready, at)

    // ================= 1. the seller says where it is paid =================

    @Test
    fun the_destination_survives_the_wire_and_the_buyer_verifies_the_seller_signed_it() {
        val c = claim()
        val line = PayWire.destinationClaim(c, seller.sign(c.signData()))
        assertEquals(PayWire.T_DESTINATION_CLAIM, PayWire.typeOf(line))
        val back = PayWire.parseDestinationClaim(line)!!
        assertTrue(DestinationClaim.verify(back.claim, seller.pub, back.sig))
        assertEquals(c.hash(), back.claim.hash())
        assertEquals(c.normalized, back.claim.normalized)
    }

    @Test
    fun a_buyer_cannot_alter_the_number_it_was_given() {
        val c = claim()
        val sig = seller.sign(c.signData())
        // the buyer swaps in its own number and keeps the seller's signature
        val forged = DestinationClaim.Claim(seller.id, Settlement.Rail.MTN_MOMO, "066999999", 1, now)
        assertFalse(DestinationClaim.verify(forged, seller.pub, sig))
        // or changes the rail
        assertFalse(DestinationClaim.verify(
            DestinationClaim.Claim(seller.id, Settlement.Rail.AIRTEL_MONEY, msisdn, 1, now), seller.pub, sig))
        // or claims to be the seller
        assertFalse(DestinationClaim.verify(claim(who = stranger), seller.pub, sig))
    }

    @Test
    fun an_old_destination_replayed_is_ignored_and_a_newer_one_wins() {
        val v1 = claim(1)
        val v2 = claim(2, number = "066999999")
        assertTrue(DestinationClaim.mayReplace(null, v1))
        assertTrue(DestinationClaim.mayReplace(v1, v2))
        assertFalse("a replayed old claim must not move the money back", DestinationClaim.mayReplace(v2, v1))
    }

    @Test
    fun during_cooling_the_old_number_stays_active_so_there_is_never_a_gap() {
        val v1 = claim(1)
        val v2 = claim(2, number = "066999999")
        assertTrue("the first number works at once", DestinationClaim.usable(v1, null, now))
        assertFalse(DestinationClaim.usable(v2, v1, now))
        assertTrue(DestinationClaim.usable(v2, v1, now + DestinationClaim.CHANGE_COOLING_MS))
        // and a payment created during cooling binds to whichever was active then
        assertNotEquals(v1.hash(), v2.hash())
    }

    // ================= 2. the buyer says what it will pay =================

    @Test
    fun an_expectation_survives_the_wire_and_the_seller_verifies_the_buyer_signed_it() {
        val e = expectation()
        val s = signedExpectation(e)
        assertTrue(PayWire.verifyExpectation(s, buyer.pub))
        assertEquals(e.paymentId, s.expectation.paymentId)
        assertEquals(e.amountCentimes, s.expectation.amountCentimes)
        assertEquals(e.includedSettlementIds, s.expectation.includedSettlementIds)
        assertEquals(PayWire.Reply.ACCEPTED, decide(s))
    }

    @Test
    fun a_stranger_cannot_make_the_seller_watch_for_anything() {
        // signed by somebody who is not the buyer it names
        val forged = PayWire.parseExpectation(
            PayWire.expectation(expectation(), stranger.sign(PayWire.expectationSignData(expectation()))))!!
        assertFalse(PayWire.verifyExpectation(forged, buyer.pub))
        assertEquals(PayWire.Reply.BAD_SIGNATURE, decide(forged))
        // and a key that is not the named buyer's is refused too
        assertEquals(PayWire.Reply.BAD_SIGNATURE, decide(signedExpectation(expectation()), pub = stranger.pub))
    }

    @Test
    fun an_expectation_for_another_seller_is_not_ours() {
        val other = PaymentExpectation.Expectation("p1", buyer.id, stranger.id,
            Settlement.Rail.MTN_MOMO, destHash(), 5_000, now, now, now + 600_000, listOf("s1"))
        assertEquals(PayWire.Reply.NOT_FOR_ME, decide(signedExpectation(other)))
    }

    @Test
    fun an_expectation_naming_the_wrong_destination_is_refused() {
        val wrong = expectation(dest = PaymentExpectation.destinationHash(Settlement.Rail.MTN_MOMO, "066000000"))
        assertEquals(PayWire.Reply.UNKNOWN_DESTINATION, decide(signedExpectation(wrong)))
        // and a seller with no destination cannot accept anything
        assertEquals(PayWire.Reply.UNKNOWN_DESTINATION, decide(signedExpectation(expectation()), dest = ""))
    }

    @Test
    fun the_amount_must_equal_what_those_sessions_really_owe() {
        // the seller derives this from its OWN records, never from the buyer's word
        assertEquals(PayWire.Reply.BAD_AMOUNT,
            decide(signedExpectation(expectation(amount = 9_900)), outstanding = mapOf("s1" to 5_000L)))
        // a settlement the seller has never heard of
        assertEquals(PayWire.Reply.BAD_AMOUNT,
            decide(signedExpectation(expectation(ids = listOf("unknown"))), outstanding = mapOf("s1" to 5_000L)))
        // an already paid session owes nothing, so it cannot pad an expectation
        assertEquals(PayWire.Reply.BAD_AMOUNT,
            decide(signedExpectation(expectation(ids = listOf("s1", "s2"))),
                outstanding = mapOf("s1" to 5_000L, "s2" to 0L)))
        // several sessions that really do add up are fine
        assertEquals(PayWire.Reply.ACCEPTED,
            decide(signedExpectation(expectation(amount = 1_500, ids = listOf("a", "b", "c"))),
                outstanding = mapOf("a" to 300L, "b" to 700L, "c" to 500L)))
    }

    @Test
    fun an_expired_or_absurdly_long_window_is_refused() {
        assertEquals(PayWire.Reply.EXPIRED,
            decide(signedExpectation(expectation()), at = now + PaymentExpectation.DEFAULT_WINDOW_MS + 1))
        // a buyer asking the seller to watch for a week is not acceptable either
        assertEquals(PayWire.Reply.EXPIRED,
            decide(signedExpectation(expectation(window = 7L * 24 * 3600 * 1000))))
    }

    @Test
    fun a_seller_that_cannot_verify_payments_says_so_instead_of_accepting() {
        assertEquals(PayWire.Reply.NOT_READY, decide(signedExpectation(expectation()), ready = false))
        assertFalse(PayWire.ready(PayWire.Reply.NOT_READY))
        assertTrue(PayWire.replyLine(PayWire.Reply.NOT_READY).isNotEmpty())
    }

    // ================= 3. the same-amount lock is the seller's =================

    @Test
    fun the_seller_is_the_authority_on_two_buyers_owing_the_same_amount() {
        val a = signedExpectation(expectation(b = buyer, amount = 5_000))
        assertEquals(PayWire.Reply.ACCEPTED, decide(a))
        // the second buyer, same amount, while the first window is live
        val b = PayWire.parseExpectation(PayWire.expectation(
            expectation(b = buyer2, amount = 5_000, ids = listOf("s1")),
            buyer2.sign(PayWire.expectationSignData(expectation(b = buyer2, amount = 5_000, ids = listOf("s1"))))))!!
        assertEquals(PayWire.Reply.BUSY_SAME_AMOUNT, decide(b, pub = buyer2.pub, busy = true))
        assertTrue(PayWire.replyLine(PayWire.Reply.BUSY_SAME_AMOUNT).contains("Réessayez"))
        // a different amount is not blocked
        assertEquals(PayWire.Reply.ACCEPTED,
            decide(signedExpectation(expectation(amount = 6_000)), outstanding = mapOf("s1" to 6_000L), busy = false))
    }

    @Test
    fun only_an_acceptance_means_the_seller_is_watching() {
        assertTrue(PayWire.ready(PayWire.Reply.ACCEPTED))
        for (r in PayWire.Reply.values().filter { it != PayWire.Reply.ACCEPTED }) {
            assertFalse(r.name, PayWire.ready(r))
            assertTrue(PayWire.replyLine(r).isNotEmpty())
        }
        val line = PayWire.expectationReply("p1", PayWire.Reply.BUSY_SAME_AMOUNT)
        val back = PayWire.parseExpectationReply(line)!!
        assertEquals("p1", back.paymentId)
        assertEquals(PayWire.Reply.BUSY_SAME_AMOUNT, back.reply)
    }

    @Test
    fun ending_a_window_is_signed_so_nobody_else_can_close_it() {
        val e = expectation()
        val sig = buyer.sign(PayWire.expectationEndSignData(e.paymentId, e.buyerId, "paid"))
        val back = PayWire.parseExpectationEnd(PayWire.expectationEnd(e.paymentId, e.buyerId, "paid", sig))!!
        assertTrue(PayWire.verifyEnd(back, buyer.pub))
        assertFalse("another phone must not be able to free the amount", PayWire.verifyEnd(back, stranger.pub))
    }

    // ================= 4. the money arrived =================

    private fun receiptFor(e: PaymentExpectation.Expectation, text: String = "Vous avez reçu 50 FCFA"):
        Pair<DeviceReceipt.Receipt, ByteArray> {
        val c = DeviceReceipt.Candidate(DeviceReceipt.Source.DIRECT_SMS, "com.android.mms", "MTN", text, now + 60_000)
        val r = DeviceReceipt.receiptFor(e, c, ReceiptParser.parse(text, e.amountCentimes), now + 60_000)!!
        return r to seller.sign(r.signData())
    }

    @Test
    fun a_receipt_survives_the_wire_and_the_buyer_accepts_only_its_own() {
        val e = expectation()
        val (r, sig) = receiptFor(e)
        val back = PayWire.parseReceipt(PayWire.receipt(r, sig))!!
        assertTrue(DeviceReceipt.verify(back.receipt, seller.pub, back.sig))
        assertTrue(PayWire.buyerAcceptsReceipt(back, seller.pub, buyer.id, e))
        // not somebody else's debt
        assertFalse(PayWire.buyerAcceptsReceipt(back, seller.pub, buyer2.id, e))
        // not signed by the seller it names
        assertFalse(PayWire.buyerAcceptsReceipt(back, stranger.pub, buyer.id, e))
        // and never without the expectation that authorised it
        assertFalse(PayWire.buyerAcceptsReceipt(back, seller.pub, buyer.id, null))
    }

    @Test
    fun a_receipt_may_only_clear_the_sessions_its_expectation_named() {
        // this is what stops one receipt being shifted onto a different debt later
        val e = expectation(amount = 1_000, ids = listOf("a", "b"))
        val c = DeviceReceipt.Candidate(DeviceReceipt.Source.DIRECT_SMS, "com.android.mms", "MTN",
            "Vous avez reçu 10 FCFA", now + 60_000)
        val r = DeviceReceipt.receiptFor(e, c, ReceiptParser.parse("Vous avez reçu 10 FCFA", 1_000), now + 60_000)!!
        val good = PayWire.parseReceipt(PayWire.receipt(r, seller.sign(r.signData())))!!
        assertTrue(PayWire.buyerAcceptsReceipt(good, seller.pub, buyer.id, e))

        // the seller tries to sweep in a third session that was never in the expectation
        val greedy = DeviceReceipt.Receipt(r.paymentId, r.sellerId, r.buyerId, r.rail, r.destinationHash,
            r.expectedCentimes, r.observedCentimes, r.observedAt, r.source, r.sourcePackage,
            r.messageEvidenceHash, r.parserVersion, r.confidence, listOf("a", "b", "c"))
        val signedGreedy = PayWire.SignedReceipt(greedy, seller.sign(greedy.signData()))
        assertFalse("a receipt cannot reach a session the buyer never authorised",
            PayWire.buyerAcceptsReceipt(signedGreedy, seller.pub, buyer.id, e))
    }

    @Test
    fun a_receipt_for_a_different_amount_or_destination_is_refused() {
        val e = expectation()
        val (r, _) = receiptFor(e)
        val inflated = DeviceReceipt.Receipt(r.paymentId, r.sellerId, r.buyerId, r.rail, r.destinationHash,
            9_900, 9_900, r.observedAt, r.source, r.sourcePackage, r.messageEvidenceHash,
            r.parserVersion, r.confidence, r.matchedSettlementIds)
        assertFalse(PayWire.buyerAcceptsReceipt(
            PayWire.SignedReceipt(inflated, seller.sign(inflated.signData())), seller.pub, buyer.id, e))

        val elsewhere = DeviceReceipt.Receipt(r.paymentId, r.sellerId, r.buyerId, r.rail,
            PaymentExpectation.destinationHash(Settlement.Rail.MTN_MOMO, "066000000"),
            r.expectedCentimes, r.observedCentimes, r.observedAt, r.source, r.sourcePackage,
            r.messageEvidenceHash, r.parserVersion, r.confidence, r.matchedSettlementIds)
        assertFalse(PayWire.buyerAcceptsReceipt(
            PayWire.SignedReceipt(elsewhere, seller.sign(elsewhere.signData())), seller.pub, buyer.id, e))
    }

    @Test
    fun a_receipt_cannot_be_moved_onto_a_different_payment() {
        val e1 = expectation(amount = 5_000, ids = listOf("s1"))
        val e2 = expectation(amount = 5_000, ids = listOf("s1"), at = now + 1)
        assertNotEquals(e1.paymentId, e2.paymentId)
        val (r, sig) = receiptFor(e1)
        val back = PayWire.parseReceipt(PayWire.receipt(r, sig))!!
        assertTrue(PayWire.buyerAcceptsReceipt(back, seller.pub, buyer.id, e1))
        assertFalse("the receipt names payment one, not payment two",
            PayWire.buyerAcceptsReceipt(back, seller.pub, buyer.id, e2))
    }

    // ================= framing =================

    @Test
    fun rubbish_on_the_wire_is_refused_rather_than_half_parsed() {
        for (junk in listOf("", "hello", "pay1.exp", "pay1.exp|1|too|few",
                "pay1.receipt|99|x", "pay1.dest|nonsense")) {
            assertNull(junk, PayWire.parseExpectation(junk))
            assertNull(junk, PayWire.parseReceipt(junk))
            assertNull(junk, PayWire.parseExpectationReply(junk))
            assertNull(junk, PayWire.parseExpectationEnd(junk))
        }
        assertNull(PayWire.parseDestinationClaim("pay1.dest|rubbish"))
    }

    @Test
    fun each_message_type_is_distinguishable_on_the_wire() {
        val e = expectation()
        val (r, sig) = receiptFor(e)
        val lines = listOf(
            PayWire.destinationClaim(claim(), seller.sign(claim().signData())),
            PayWire.expectation(e, buyer.sign(PayWire.expectationSignData(e))),
            PayWire.expectationReply(e.paymentId, PayWire.Reply.ACCEPTED),
            PayWire.receipt(r, sig),
            PayWire.expectationEnd(e.paymentId, e.buyerId, "paid",
                buyer.sign(PayWire.expectationEndSignData(e.paymentId, e.buyerId, "paid"))))
        val types = lines.map { PayWire.typeOf(it) }
        assertEquals("every message must be told apart by its first field", 5, types.toSet().size)
        // and one type never parses as another
        assertNull(PayWire.parseExpectation(lines[3]))
        assertNull(PayWire.parseReceipt(lines[1]))
    }

    @Test
    fun a_signature_from_one_message_type_cannot_be_reused_on_another() {
        // domain separation, tested rather than assumed
        val e = expectation()
        val endSig = buyer.sign(PayWire.expectationEndSignData(e.paymentId, e.buyerId, "paid"))
        val forged = PayWire.parseExpectation(PayWire.expectation(e, endSig))!!
        assertFalse(PayWire.verifyExpectation(forged, buyer.pub))
    }
}
