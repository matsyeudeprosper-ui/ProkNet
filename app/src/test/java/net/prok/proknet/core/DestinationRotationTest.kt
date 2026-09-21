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

    // ================= v0.16.5: the timestamp is signed =================

    /**
     * v0.16.4 made `createdAt` decide when a new destination goes live, on both the phone
     * and the Brain - while it was still outside the signature. Anything carrying a claim
     * could have moved the cooling window: ten minutes earlier and a buyer is sent to a
     * number the seller is not watching yet; ten minutes later and the seller keeps being
     * paid on a number it has abandoned.
     */
    @Test fun the_signed_bytes_contain_the_timestamp() {
        val signed = String(v1.signDataV2(), Charsets.UTF_8)
        assertTrue(signed.startsWith(DestinationClaim.DOMAIN_V2 + "|"))
        assertTrue("the field cooling depends on must be signed", signed.endsWith("|" + t0))
        // and the old bytes did not
        assertTrue(!String(v1.signData(), Charsets.UTF_8).endsWith("|" + t0))
    }

    @Test fun moving_the_timestamp_invalidates_the_claim() {
        val sig = seller.sign(v1.signDataV2())
        assertTrue(DestinationClaim.verify(v1, seller.pub, sig))
        for (shift in listOf(600_000L, -600_000L)) {
            val moved = claim(1, v1.rail, v1.msisdn, t0 + shift)
            assertTrue("a moved cooling window must break the claim",
                !DestinationClaim.verify(moved, seller.pub, sig))
        }
    }

    @Test fun the_wire_line_carries_the_timestamp_and_it_is_checked() {
        val sig = seller.sign(v1.signDataV2())
        val line = PayWire.destinationClaim(v1, sig)
        val back = PayWire.parseDestinationClaim(line)!!
        assertEquals(DestinationClaim.FORMAT_SIGNED_TIME, back.format)
        assertTrue(back.timeIsSigned)
        assertEquals(t0, back.claim.createdAt)
        assertTrue(DestinationClaim.verify(back.claim, seller.pub, back.sig, back.format))

        // edit only the timestamp on the wire, keep the signature
        val parts = line.split("|").toMutableList()
        assertEquals(t0.toString(), parts[6])
        parts[6] = (t0 + 600_000).toString()
        val tampered = PayWire.parseDestinationClaim(parts.joinToString("|"))!!
        assertTrue(!DestinationClaim.verify(tampered.claim, seller.pub, tampered.sig, tampered.format))
    }

    // ================= legacy claims, read-only =================

    @Test fun a_claim_signed_before_build_67_still_verifies() {
        // a seller who has not changed their number must not be asked to re-enter it
        val legacySig = seller.sign(v1.signData())
        assertTrue(DestinationClaim.verify(v1, seller.pub, legacySig, DestinationClaim.FORMAT_LEGACY))
        assertEquals(DestinationClaim.FORMAT_LEGACY,
            DestinationClaim.formatOf(v1, seller.pub, legacySig))
        assertTrue("its timestamp is used, but it is not a signed fact",
            !DestinationClaim.timeIsSigned(DestinationClaim.FORMAT_LEGACY))
    }

    @Test fun a_legacy_claim_survives_the_wire_and_is_labelled_as_legacy() {
        val legacySig = seller.sign(v1.signData())
        val line = PayWire.destinationClaim(v1, legacySig, DestinationClaim.FORMAT_LEGACY)
        assertTrue(line.contains("|" + DestinationClaim.WIRE_V1 + "|"))
        val back = PayWire.parseDestinationClaim(line)!!
        assertEquals(DestinationClaim.FORMAT_LEGACY, back.format)
        assertTrue(!back.timeIsSigned)
        assertTrue(DestinationClaim.verify(back.claim, seller.pub, back.sig, back.format))
    }

    @Test fun a_legacy_claim_relabelled_as_signed_time_is_refused() {
        // otherwise a carrier could have an unsigned timestamp treated as authenticated
        val legacySig = seller.sign(v1.signData())
        assertTrue(!DestinationClaim.verify(v1, seller.pub, legacySig,
            DestinationClaim.FORMAT_SIGNED_TIME))
    }

    @Test fun a_signed_time_claim_relabelled_as_legacy_is_refused() {
        val sig = seller.sign(v1.signDataV2())
        assertTrue(!DestinationClaim.verify(v1, seller.pub, sig, DestinationClaim.FORMAT_LEGACY))
    }

    @Test fun an_unknown_wire_prefix_is_not_a_claim() {
        val sig = seller.sign(v1.signDataV2())
        val line = PayWire.destinationClaim(v1, sig).replace("|dest2|", "|dest9|")
        assertEquals(null, PayWire.parseDestinationClaim(line))
    }

    @Test fun formatOf_says_zero_when_neither_format_verifies() {
        val stranger = Party()
        assertEquals(0, DestinationClaim.formatOf(v1, seller.pub, stranger.sign(v1.signDataV2())))
    }

    // ================= the transition =================

    @Test fun the_version_keeps_counting_across_the_format_change() {
        // a legacy claim at version 3, replaced by a v2 claim at version 4, not 1. The
        // signature format and the claim version are different things.
        val legacy = claim(3, Settlement.Rail.MTN_MOMO, numberA, t0)
        val next = claim(DestinationClaim.nextVersion(legacy), Settlement.Rail.AIRTEL_MONEY, numberB, t1)
        assertEquals(4, next.version)
        assertTrue(DestinationClaim.mayReplace(legacy, next))
        assertTrue("an older claim may not come back", !DestinationClaim.mayReplace(next, legacy))
    }

    @Test fun a_mixed_history_cools_exactly_like_any_other() {
        // the legacy claim's timestamp is not signed, but the rule that uses it is the
        // same rule, so an upgraded seller behaves like everybody else
        val legacy = claim(1, Settlement.Rail.MTN_MOMO, numberA, t0)
        val fresh = claim(2, Settlement.Rail.AIRTEL_MONEY, numberB, t1)
        val claims = listOf(legacy, fresh)
        assertEquals(1, DestinationClaim.active(claims, t1 + 1_000)!!.version)
        assertEquals(1, DestinationClaim.active(claims, t1 + cooling - 1)!!.version)
        assertEquals(2, DestinationClaim.active(claims, t1 + cooling)!!.version)
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
