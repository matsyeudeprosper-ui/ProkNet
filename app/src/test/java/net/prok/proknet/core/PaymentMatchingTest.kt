package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.16.0: clearing a debt from a payment nobody gave us a reference for.
 *
 * The product rule these tests defend: **the way people pay does not change.** Cash to a
 * kiosk, the seller's ordinary number, walk away. So the match is made from the seller, the
 * rail, the exact amount and a short window, and from a message the seller's own phone saw.
 *
 * Not one test here supplies a transaction reference, because in real life nobody will.
 */
class PaymentMatchingTest {
    private val now = 1_700_000_000_000L
    private val buyerA = "aa".repeat(16)
    private val buyerB = "bb".repeat(16)
    private val seller = "cc".repeat(16)
    private val msisdn = "066123456"
    private val destHash = PaymentExpectation.destinationHash(Settlement.Rail.MTN_MOMO, msisdn)

    private fun expect(buyer: String = buyerA, cents: Long = 5_000, at: Long = now,
                       ids: List<String> = listOf("s1")): PaymentExpectation.Expectation =
        PaymentExpectation.create(buyer, seller, Settlement.Rail.MTN_MOMO, destHash, cents, ids,
            emptyList(), at).expectation!!

    private fun sms(text: String, at: Long = now + 60_000,
                    source: DeviceReceipt.Source = DeviceReceipt.Source.DIRECT_SMS,
                    pkg: String = "com.android.mms"): DeviceReceipt.Candidate =
        DeviceReceipt.Candidate(source, pkg, "MTN", text, at)

    private fun matchOf(text: String, active: List<PaymentExpectation.Expectation>,
                        at: Long = now + 60_000, expected: Long = 0): PaymentExpectation.Outcome {
        val parsed = ReceiptParser.parse(text, expected)
        return PaymentExpectation.match(parsed.amountCentimes, seller, Settlement.Rail.MTN_MOMO,
            destHash, at, active, parsed)
    }

    // ================= the ordinary case =================

    @Test
    fun a_kiosk_payment_clears_the_debt_with_no_reference_at_all() {
        val e = expect(cents = 5_000)
        val out = matchOf("Vous avez reçu 50 FCFA", listOf(e))
        assertEquals(PaymentExpectation.Match.ONE, out.match)
        assertEquals(e.paymentId, out.expectation!!.paymentId)

        val parsed = ReceiptParser.parse("Vous avez reçu 50 FCFA", 5_000)
        val r = DeviceReceipt.receiptFor(e, sms("Vous avez reçu 50 FCFA"), parsed, now + 60_000)!!
        assertTrue(r.valid)
        assertEquals("", r.reference)
        assertEquals(5_000, r.observedCentimes)
        assertEquals(e.includedSettlementIds, r.matchedSettlementIds)
        assertEquals(DeviceReceipt.Confidence.DEVICE_SMS_VERIFIED, r.confidence)
        assertTrue(DeviceReceipt.clearsDebt(r.confidence))
    }

    @Test
    fun the_amount_must_be_exact() {
        val e = expect(cents = 5_000)
        assertEquals("40 is not 50", PaymentExpectation.Match.NONE, matchOf("Vous avez reçu 40 FCFA", listOf(e)).match)
        assertEquals(PaymentExpectation.Match.NONE, matchOf("Vous avez reçu 60 FCFA", listOf(e)).match)
        // a kiosk sends what it is told, so "close enough" means somebody else's payment
        assertEquals(PaymentExpectation.Match.NONE, matchOf("Vous avez reçu 49 FCFA", listOf(e)).match)
    }

    @Test
    fun a_receipt_outside_the_window_does_not_match_automatically() {
        val e = expect(cents = 5_000)
        val late = now + PaymentExpectation.DEFAULT_WINDOW_MS + 60_000
        assertFalse(e.active(late))
        assertEquals(PaymentExpectation.Match.NONE, matchOf("Vous avez reçu 50 FCFA", listOf(e), at = late).match)
        // the debt is untouched; the buyer may simply try again
        assertTrue(e.expired(late))
        assertEquals(PaymentExpectation.State.EXPIRED, PaymentExpectation.sweep(listOf(e), late).first().state)
    }

    @Test
    fun two_amounts_are_told_apart_by_the_amount_alone() {
        val fifty = expect(buyer = buyerA, cents = 5_000, ids = listOf("s-a"))
        val sixty = expect(buyer = buyerB, cents = 6_000, ids = listOf("s-b"))
        val active = listOf(fifty, sixty)
        assertEquals(buyerB, matchOf("Vous avez reçu 60 FCFA", active).expectation!!.buyerId)
        assertEquals(buyerA, matchOf("Vous avez reçu 50 FCFA", active).expectation!!.buyerId)
    }

    // ================= ambiguity is prevented, not resolved =================

    @Test
    fun one_seller_may_not_have_two_live_expectations_for_the_same_amount() {
        // the honest alternative to charging somebody an extra franc to fingerprint them
        val first = expect(buyer = buyerA, cents = 5_000)
        val second = PaymentExpectation.create(buyerB, seller, Settlement.Rail.MTN_MOMO, destHash,
            5_000, listOf("s-b"), listOf(first), now + 1_000)
        assertFalse(second.ok)
        assertEquals(PaymentExpectation.Refusal.AMOUNT_BUSY, second.refusal)
        assertTrue(second.message.contains("Réessayez"))
        // different amounts are fine at the same time
        assertTrue(PaymentExpectation.create(buyerB, seller, Settlement.Rail.MTN_MOMO, destHash,
            6_000, listOf("s-b"), listOf(first), now + 1_000).ok)
        // and once the first window closes the second buyer may go
        val after = now + PaymentExpectation.DEFAULT_WINDOW_MS + 1
        assertTrue(PaymentExpectation.create(buyerB, seller, Settlement.Rail.MTN_MOMO, destHash,
            5_000, listOf("s-b"), listOf(first), after).ok)
    }

    @Test
    fun if_two_expectations_somehow_fit_nothing_is_guessed() {
        val a = expect(buyer = buyerA, cents = 5_000, ids = listOf("s-a"))
        val b = PaymentExpectation.Expectation(a.paymentId + "x", buyerB, seller,
            Settlement.Rail.MTN_MOMO, destHash, 5_000, now, now, now + 600_000, listOf("s-b"))
        val out = matchOf("Vous avez reçu 50 FCFA", listOf(a, b))
        assertEquals(PaymentExpectation.Match.AMBIGUOUS, out.match)
        assertNull("never pick one at random", out.expectation)
    }

    @Test
    fun a_message_the_parser_is_unsure_about_never_clears_anything() {
        val e = expect(cents = 5_000)
        for (text in listOf("Vous avez reçu 50 CFA 60 CFA", "Vous avez reçu un paiement", "Votre solde est 50 CFA")) {
            val out = matchOf(text, listOf(e))
            assertNotEquals(text, PaymentExpectation.Match.ONE, out.match)
        }
        // and a debit message is never a receipt however well it matches the amount
        assertNotEquals(PaymentExpectation.Match.ONE, matchOf("Vous avez envoyé 50 FCFA", listOf(e)).match)
    }

    @Test
    fun no_expectation_means_nothing_is_cleared_by_a_stray_payment() {
        val out = matchOf("Vous avez reçu 50 FCFA", emptyList())
        assertEquals(PaymentExpectation.Match.NONE, out.match)
    }

    // ================= where the message came from =================

    @Test
    fun only_the_default_sms_application_may_speak_for_the_operator() {
        val defaultSms = "com.android.mms"
        val good = sms("Vous avez reçu 50 FCFA", source = DeviceReceipt.Source.DEFAULT_SMS_NOTIFICATION, pkg = defaultSms)
        assertTrue(DeviceReceipt.eligible(good, defaultSms))

        // the obvious attack: any app can post "Vous avez reçu 50 CFA"
        val hostile = sms("Vous avez reçu 50 FCFA", source = DeviceReceipt.Source.DEFAULT_SMS_NOTIFICATION, pkg = "com.evil.app")
        assertFalse(DeviceReceipt.eligible(hostile, defaultSms))
        assertTrue(DeviceReceipt.refusalReason(hostile, defaultSms).contains("com.evil.app"))

        // and if we cannot tell which app is the default, we do not guess
        assertFalse(DeviceReceipt.eligible(good, ""))
    }

    @Test
    fun a_hidden_or_empty_notification_produces_no_match() {
        val blank = sms("", source = DeviceReceipt.Source.DEFAULT_SMS_NOTIFICATION)
        assertFalse(DeviceReceipt.eligible(blank, "com.android.mms"))
        assertTrue(DeviceReceipt.refusalReason(blank, "com.android.mms").contains("no readable content"))
    }

    @Test
    fun direct_sms_is_worth_more_than_a_notification_and_neither_is_the_operator() {
        assertEquals(DeviceReceipt.Confidence.DEVICE_SMS_VERIFIED,
            DeviceReceipt.confidenceFor(DeviceReceipt.Source.DIRECT_SMS))
        assertEquals(DeviceReceipt.Confidence.DEVICE_NOTIFICATION_VERIFIED,
            DeviceReceipt.confidenceFor(DeviceReceipt.Source.DEFAULT_SMS_NOTIFICATION))
        // both clear a debt in the pilot
        assertTrue(DeviceReceipt.clearsDebt(DeviceReceipt.Confidence.DEVICE_SMS_VERIFIED))
        assertTrue(DeviceReceipt.clearsDebt(DeviceReceipt.Confidence.DEVICE_NOTIFICATION_VERIFIED))
        // MTN has attested nothing, so nothing in this build may claim it did
        for (s in DeviceReceipt.Source.values())
            assertNotEquals("no source may produce an operator attestation",
                DeviceReceipt.Confidence.OPERATOR_VERIFIED, DeviceReceipt.confidenceFor(s))
    }

    @Test
    fun the_same_message_twice_is_one_receipt() {
        val a = sms("Vous avez reçu 50 FCFA", at = now + 10)
        val b = sms("Vous avez reçu 50 FCFA", at = now + 20)
        assertEquals("the same text from the same sender is the same evidence",
            a.evidenceHash(), b.evidenceHash())
        assertNotEquals(a.evidenceHash(), sms("Vous avez reçu 60 FCFA").evidenceHash())
    }

    @Test
    fun the_raw_message_never_becomes_part_of_the_receipt() {
        val text = "Vous avez reçu 50 FCFA de Jean Dupont, 066111222"
        val e = expect(cents = 5_000)
        val r = DeviceReceipt.receiptFor(e, sms(text), ReceiptParser.parse(text, 5_000), now + 60_000)!!
        val signed = String(r.signData(), Charsets.UTF_8)
        assertFalse("the message body must not travel", signed.contains("Jean Dupont"))
        assertFalse(signed.contains("066111222"))
        assertTrue("only its hash does", signed.contains(r.messageEvidenceHash))
        assertEquals(64, r.messageEvidenceHash.length)
    }

    // ================= the seller signs it, not the buyer =================

    @Test
    fun the_receipt_is_signed_by_the_party_who_observed_it() {
        val kp = Crypto.generateKeyPair()
        val sellerPub = Crypto.publicBytes(kp.public)
        val e = PaymentExpectation.Expectation("p1", buyerA, Crypto.deriveId(sellerPub).toHex(),
            Settlement.Rail.MTN_MOMO, destHash, 5_000, now, now, now + 600_000, listOf("s1"))
        val text = "Vous avez reçu 50 FCFA"
        val r = DeviceReceipt.receiptFor(e, sms(text), ReceiptParser.parse(text, 5_000), now + 60_000)!!
        val sig = Crypto.sign(kp.private, r.signData())
        assertTrue(DeviceReceipt.verify(r, sellerPub, sig))
        // the buyer cannot produce this object, which is the entire point
        val other = Crypto.generateKeyPair()
        assertFalse(DeviceReceipt.verify(r, Crypto.publicBytes(other.public), sig))
        // and nothing inside may be altered afterwards
        val inflated = DeviceReceipt.Receipt(r.paymentId, r.sellerId, r.buyerId, r.rail, r.destinationHash,
            9_900, 9_900, r.observedAt, r.source, r.sourcePackage, r.messageEvidenceHash,
            r.parserVersion, r.confidence, r.matchedSettlementIds)
        assertFalse(DeviceReceipt.verify(inflated, sellerPub, sig))
    }

    @Test
    fun a_receipt_whose_amount_does_not_match_the_expectation_is_never_built() {
        val e = expect(cents = 5_000)
        val text = "Vous avez reçu 40 FCFA"
        assertNull(DeviceReceipt.receiptFor(e, sms(text), ReceiptParser.parse(text), now + 60_000))
        // nor one from a message the parser would not act on
        assertNull(DeviceReceipt.receiptFor(e, sms("Votre solde est 50 CFA"),
            ReceiptParser.parse("Votre solde est 50 CFA"), now + 60_000))
    }

    // ================= what the buyer reads =================

    @Test
    fun the_buyer_is_told_what_to_do_and_never_offered_a_paid_button() {
        val text = PaymentExpectation.instruction(5_000, "MTN Mobile Money", PaymentExpectation.maskedNumber(msisdn))
        assertTrue(text.contains("Envoyez exactement"))
        assertTrue(text.contains("kiosque"))
        assertTrue(text.contains("automatiquement"))
        assertFalse("there is no such thing as the buyer declaring payment", text.contains("J'ai payé"))
        assertTrue(PaymentExpectation.waitingLine(5_000).contains("En attente"))
        // the number is readable aloud but still masked
        assertEquals("•• •• 34 56", PaymentExpectation.maskedNumber(msisdn))
        assertFalse(PaymentExpectation.maskedNumber(msisdn).contains("0661"))
    }

    @Test
    fun the_same_number_written_several_ways_is_one_destination() {
        val a = PaymentExpectation.destinationHash(Settlement.Rail.MTN_MOMO, "066123456")
        val b = PaymentExpectation.destinationHash(Settlement.Rail.MTN_MOMO, "+242 066 123 456")
        val c = PaymentExpectation.destinationHash(Settlement.Rail.MTN_MOMO, "242-066-123-456")
        assertEquals(a, b)
        assertEquals(a, c)
        assertNotEquals("a different rail is a different destination", a,
            PaymentExpectation.destinationHash(Settlement.Rail.AIRTEL_MONEY, "066123456"))
    }
}
