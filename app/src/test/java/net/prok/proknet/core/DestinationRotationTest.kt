package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.16.4: a seller changes where they are paid, and nobody's money goes astray.
 *
 * Three things had to be true at once and were not:
 *
 * 1. The claim a buyer is handed must be the one the seller is **watching**, not the
 *    newest one it has configured. `sendDestinationTo` sent the newest, so during the ten
 *    minutes after a change the buyer built an expectation against a number the seller had
 *    not started watching - and the seller refused its own buyer's payment.
 * 2. A buyer that already holds a claim must keep asking for newer ones. It only ever
 *    asked when it had none, so a seller could change their number and the buyer would go
 *    on paying the old one until the debt was settled.
 * 3. An expectation already created must stay pinned to the destination it names. The
 *    seller compared against one hash, so when cooling ended it began refusing
 *    expectations it had itself asked for minutes earlier.
 *
 * The timeline below is the one from the spec, run through the same pure functions the
 * phone runs. `server/tests/fixtures/crosslang.json` holds the same cases for the server.
 */
class DestinationRotationTest {

    private class Party {
        private val kp = Crypto.generateKeyPair()
        val pub: ByteArray = Crypto.publicBytes(kp.public)
        val id: String = Crypto.deriveId(pub).toHex()
        fun sign(d: ByteArray): ByteArray = Crypto.sign(kp.private, d)
    }

    private val buyer = Party()
    private val seller = Party()

    private val cooling = DestinationClaim.CHANGE_COOLING_MS
    private val t0 = 1_700_000_000_000L
    private val t1 = t0 + 60_000            // the seller changes their number

    private val numberA = "066111111"
    private val numberB = "055222222"
    private val numberC = "066999999"       // same operator, different number

    private fun claim(version: Int, rail: Settlement.Rail, msisdn: String, at: Long) =
        DestinationClaim.Claim(seller.id, rail, msisdn, version, at)

    private val v1 = claim(1, Settlement.Rail.MTN_MOMO, numberA, t0)
    private val v2Airtel = claim(2, Settlement.Rail.AIRTEL_MONEY, numberB, t1)
    private val v2Mtn = claim(2, Settlement.Rail.MTN_MOMO, numberC, t1)

    /** What the seller would hand a buyer that asks at [now]. */
    private fun handedOut(claims: List<DestinationClaim.Claim>, now: Long) =
        DestinationClaim.active(claims, now)

    private fun expectationAgainst(c: DestinationClaim.Claim, at: Long, amount: Long = 5_000L) =
        PaymentExpectation.Expectation(
            PaymentExpectation.idFor(buyer.id, seller.id, amount, at), buyer.id, seller.id,
            c.rail, c.hash(), amount, at, at, at + PaymentExpectation.DEFAULT_WINDOW_MS,
            listOf("s1"))

    private fun signed(e: PaymentExpectation.Expectation) =
        PayWire.parseExpectation(PayWire.expectation(e, buyer.sign(PayWire.expectationSignData(e))))!!

    private fun sellerAnswer(
        e: PaymentExpectation.Expectation, claims: List<DestinationClaim.Claim>, now: Long,
    ) = PayWire.sellerDecision(
        signed(e), buyer.pub, seller.id,
        DestinationClaim.acceptableHashes(claims, now, e.createdAt),
        mapOf("s1" to 5_000L), false, true, now)

    // ================= MTN -> Airtel, the whole timeline =================

    @Test fun t0_the_seller_has_one_number_and_it_is_the_one_handed_out() {
        val claims = listOf(v1)
        assertEquals(1, handedOut(claims, t0 + 1)!!.version)
        assertEquals(numberA, handedOut(claims, t0 + 1)!!.normalized)
    }

    @Test fun during_cooling_the_buyer_is_still_given_the_old_number() {
        val claims = listOf(v1, v2Airtel)
        val given = handedOut(claims, t1 + 1_000)
        assertNotNull(given)
        assertEquals("the seller is not watching Airtel yet", 1, given!!.version)
        assertEquals(Settlement.Rail.MTN_MOMO, given.rail)
        assertEquals(numberA, given.normalized)
    }

    @Test fun a_payment_started_during_cooling_is_accepted_by_the_seller() {
        val claims = listOf(v1, v2Airtel)
        val at = t1 + 1_000
        val e = expectationAgainst(handedOut(claims, at)!!, at)
        assertEquals("the seller must accept the destination it just handed out",
            PayWire.Reply.ACCEPTED, sellerAnswer(e, claims, at))
        assertEquals(Settlement.Rail.MTN_MOMO, e.rail)
        assertEquals(v1.hash(), e.destinationHash)
    }

    @Test fun before_the_fix_the_seller_would_have_refused_its_own_buyer() {
        // the newest claim, which is what sendDestinationTo used to send
        val claims = listOf(v1, v2Airtel)
        val at = t1 + 1_000
        val e = expectationAgainst(v2Airtel, at)
        assertEquals("an expectation against a number the seller is not yet watching",
            PayWire.Reply.UNKNOWN_DESTINATION, sellerAnswer(e, claims, at))
    }

    @Test fun after_cooling_the_new_number_is_handed_out_with_no_restart_and_no_resave() {
        val claims = listOf(v1, v2Airtel)
        val given = handedOut(claims, t1 + cooling)
        assertEquals(2, given!!.version)
        assertEquals(Settlement.Rail.AIRTEL_MONEY, given.rail)
        assertEquals(numberB, given.normalized)

        val at = t1 + cooling
        val e = expectationAgainst(given, at)
        assertEquals(PayWire.Reply.ACCEPTED, sellerAnswer(e, claims, at))
        assertEquals(Settlement.Rail.AIRTEL_MONEY, e.rail)
        assertEquals(v2Airtel.hash(), e.destinationHash)
    }

    @Test fun an_expectation_made_before_the_switch_stays_valid_after_it() {
        // the buyer was told MTN a minute before cooling ended and has walked to a kiosk
        val claims = listOf(v1, v2Airtel)
        val askedAt = t1 + cooling - 60_000
        val e = expectationAgainst(handedOut(claims, askedAt)!!, askedAt)
        assertEquals(v1.hash(), e.destinationHash)

        // the window closes while the buyer is still queueing
        val later = t1 + cooling + 60_000
        assertTrue("the expectation must still be live", e.active(later))
        assertEquals("refusing this would refuse a payment the seller itself asked for",
            PayWire.Reply.ACCEPTED, sellerAnswer(e, claims, later))
    }

    @Test fun a_brand_new_expectation_against_the_old_number_is_refused_after_the_switch() {
        // the pin is for expectations that already exist, not a second live destination
        val claims = listOf(v1, v2Airtel)
        val at = t1 + cooling + 60_000
        val e = expectationAgainst(v1, at)
        assertEquals(PayWire.Reply.UNKNOWN_DESTINATION, sellerAnswer(e, claims, at))
    }

    // ================= the same operator, a different number =================

    @Test fun changing_the_number_on_one_operator_behaves_identically() {
        val claims = listOf(v1, v2Mtn)
        assertEquals("inside cooling, the old number", numberA, handedOut(claims, t1 + 1_000)!!.normalized)
        assertEquals("after cooling, the new one", numberC, handedOut(claims, t1 + cooling)!!.normalized)

        val during = t1 + 1_000
        val e1 = expectationAgainst(handedOut(claims, during)!!, during)
        assertEquals(PayWire.Reply.ACCEPTED, sellerAnswer(e1, claims, during))

        val after = t1 + cooling
        val e2 = expectationAgainst(handedOut(claims, after)!!, after, amount = 6_000L)
        assertEquals(PayWire.Reply.ACCEPTED, PayWire.sellerDecision(
            signed(e2), buyer.pub, seller.id,
            DestinationClaim.acceptableHashes(claims, after, e2.createdAt),
            mapOf("s1" to 6_000L), false, true, after))
    }

    @Test fun a_rail_change_and_a_number_change_take_the_same_path() {
        // stated once, because special-casing the operator is how the two would drift
        for (v2 in listOf(v2Airtel, v2Mtn)) {
            val claims = listOf(v1, v2)
            assertEquals(1, handedOut(claims, t1 + cooling - 1)!!.version)
            assertEquals(2, handedOut(claims, t1 + cooling)!!.version)
        }
    }

    // ================= the two truths, kept apart =================

    @Test fun the_configured_number_and_the_one_being_paid_are_different_things() {
        val claims = listOf(v1, v2Airtel)
        val newestConfigured = claims.maxByOrNull { it.version }!!
        val activeForPayment = handedOut(claims, t1 + 1_000)!!
        assertEquals("the settings screen may say Airtel", 2, newestConfigured.version)
        assertEquals("while every payment still goes to MTN", 1, activeForPayment.version)
        assertTrue(newestConfigured.hash() != activeForPayment.hash())
    }

    @Test fun with_nothing_configured_there_is_nothing_to_hand_out() {
        assertEquals(null, DestinationClaim.active(emptyList(), t0))
        assertEquals(emptySet<String>(), DestinationClaim.acceptableHashes(emptyList(), t0, t0))
    }

    @Test fun normally_there_is_exactly_one_acceptable_destination() {
        assertEquals("a seller that has never changed anything accepts one number",
            1, DestinationClaim.acceptableHashes(listOf(v1), t0 + 5_000, t0 + 1_000).size)
        assertEquals("and long after a change, one again",
            1, DestinationClaim.acceptableHashes(
                listOf(v1, v2Airtel), t1 + cooling + 60_000, t1 + cooling + 30_000).size)
    }

    @Test fun a_third_party_number_is_never_acceptable() {
        val claims = listOf(v1, v2Airtel)
        val thief = DestinationClaim.Claim(seller.id, Settlement.Rail.MTN_MOMO, "066000000", 9, t1)
        assertTrue(thief.hash() !in DestinationClaim.acceptableHashes(claims, t1 + 1_000, t1))
        val at = t1 + 1_000
        assertEquals(PayWire.Reply.UNKNOWN_DESTINATION,
            sellerAnswer(expectationAgainst(thief, at), claims, at))
    }
}
