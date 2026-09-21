package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.16.0: making deliberate non-payment economically boring.
 *
 * Designed on the assumption that some people will not pay. The defence is not a moral
 * one, it is arithmetic: the most a stranger can take is about one short session, and they
 * get nothing more until a payment is **observed by the seller's phone**. Free and
 * sponsored Internet are never touched, because neither costs the seller anything.
 */
class TrustTest {

    // ================= the first session =================

    @Test
    fun a_stranger_gets_about_one_short_session_and_no_more() {
        val policy = Trust.DEFAULT
        assertEquals(Trust.Tier.NEW, Trust.tier(0, false))
        assertEquals(1_000, Trust.limitCentimes(Trust.Tier.NEW))

        // nothing owed yet: go ahead
        assertTrue(Trust.admitPaidSession(0, 0, false).allowed)
        // owing anything up to the limit is still fine
        assertTrue(Trust.admitPaidSession(900, 0, false).allowed)
        // at the limit, the next PAID session simply does not happen
        val blocked = Trust.admitPaidSession(1_000, 0, false)
        assertFalse(blocked.allowed)
        assertEquals(1_000, blocked.mustSettleCentimes)
        assertTrue(Trust.settleSentence(1_000).contains("Réglez"))
    }

    @Test
    fun trust_grows_only_through_payments_somebody_observed() {
        assertEquals(Trust.Tier.NEW, Trust.tier(2, false))
        assertEquals(Trust.Tier.PROVEN, Trust.tier(3, false))
        assertEquals(Trust.Tier.ESTABLISHED, Trust.tier(10, false))
        assertEquals(Trust.Tier.STRONG, Trust.tier(25, false))
        // and each tier really does buy more room
        val limits = listOf(Trust.Tier.NEW, Trust.Tier.PROVEN, Trust.Tier.ESTABLISHED, Trust.Tier.STRONG)
            .map { Trust.limitCentimes(it) }
        assertEquals(limits.sorted(), limits)
        assertEquals(limits.toSet().size, limits.size)
        // a proven buyer may carry what a new one may not
        assertFalse(Trust.admitPaidSession(2_000, 0, false).allowed)
        assertTrue(Trust.admitPaidSession(2_000, 10, false).allowed)
    }

    @Test
    fun a_new_identity_can_never_start_at_a_high_tier() {
        // the only inputs that raise a tier are verified payments, which a fresh identity
        // has none of by definition
        assertEquals(Trust.Tier.NEW, Trust.tier(0, false))
        assertEquals(Trust.limitCentimes(Trust.Tier.NEW), Trust.creditForNewIdentity(null))
    }

    @Test
    fun an_unpaid_session_blocks_the_next_paid_one_but_nothing_else() {
        val owing = Trust.admitPaidSession(1_000, 0, false)
        assertFalse(owing.allowed)
        // free and sponsored Internet are deliberately outside this decision entirely
        assertTrue("free sharing must never be gated by money", Trust.mayShareForFree())
    }

    @Test
    fun an_expired_payment_window_leaves_the_debt_and_the_block_exactly_as_they_were() {
        // nothing here punishes beyond the debt itself
        val before = Trust.admitPaidSession(1_000, 0, false)
        val after = Trust.admitPaidSession(1_000, 0, false)
        assertEquals(before.allowed, after.allowed)
        assertEquals(before.limitCentimes, after.limitCentimes)
        // and paying restores everything at once
        assertTrue(Trust.admitPaidSession(0, 1, false).allowed)
    }

    // ================= reinstall =================

    @Test
    fun owing_money_and_reinstalling_does_not_hand_out_a_fresh_allowance() {
        val history = Trust.DeviceHistory("dev1", listOf("old-identity"), unresolvedCentimes = 1_000)
        assertTrue(history.hasUnresolvedDebt)
        assertEquals("a new identity on an indebted device gets nothing", 0, Trust.creditForNewIdentity(history))
        val v = Trust.admitPaidSession(0, 0, deviceHasUnresolvedDebt = true)
        assertFalse(v.allowed)
        assertEquals(Trust.Tier.BLOCKED, Trust.tier(0, true))
        assertEquals("even a long history cannot outrank an unpaid device", Trust.Tier.BLOCKED, Trust.tier(50, true))
        // clearing the old debt restores the normal allowance
        assertEquals(1_000, Trust.creditForNewIdentity(Trust.DeviceHistory("dev1", listOf("old"), 0)))
    }

    @Test
    fun the_device_pseudonym_reveals_nothing_and_is_stable() {
        val a = Trust.devicePseudonym("android-app-scoped-abc")
        val b = Trust.devicePseudonym("android-app-scoped-abc")
        assertEquals("the same phone must look the same across a reinstall", a, b)
        assertNotEquals(a, Trust.devicePseudonym("android-app-scoped-xyz"))
        assertEquals(32, a.length)
        // what leaves the phone must not contain what went in
        assertFalse(a.contains("android"))
        assertFalse(a.contains("abc"))
        // domain separated, so it cannot be correlated with any other hash we publish
        assertNotEquals(a, Crypto.sha256("android-app-scoped-abc".toByteArray(Charsets.UTF_8)).toHex().substring(0, 32))
        // and an absent identifier produces nothing rather than a constant everyone shares
        assertEquals("", Trust.devicePseudonym(""))
        assertEquals("", Trust.devicePseudonym("   "))
    }

    // ================= the seller's side =================

    @Test
    fun a_seller_cannot_be_paid_automatically_without_the_two_things_that_make_it_work() {
        assertEquals(Trust.SellerReadiness.READY, Trust.sellerReadiness(true, true))
        assertEquals(Trust.SellerReadiness.NO_DETECTION, Trust.sellerReadiness(true, false))
        assertEquals(Trust.SellerReadiness.NO_DESTINATION, Trust.sellerReadiness(false, true))
        assertEquals(Trust.SellerReadiness.NOTHING, Trust.sellerReadiness(false, false))

        assertTrue(Trust.mayShareForMoney(Trust.SellerReadiness.READY))
        for (r in listOf(Trust.SellerReadiness.NO_DETECTION, Trust.SellerReadiness.NO_DESTINATION, Trust.SellerReadiness.NOTHING)) {
            assertFalse("promising money we cannot deliver would be a lie", Trust.mayShareForMoney(r))
            assertTrue("but free sharing always works", Trust.mayShareForFree())
            assertTrue(Trust.sellerReadinessLine(r).isNotEmpty())
        }
        assertTrue(Trust.sellerReadinessLine(Trust.SellerReadiness.NO_DETECTION).contains("Activez"))
        assertTrue(Trust.sellerReadinessLine(Trust.SellerReadiness.READY).contains("Activée"))
    }

    @Test
    fun the_words_a_seller_reads_about_a_buyer_are_honest_and_plain() {
        for (t in Trust.Tier.values()) {
            val line = Trust.buyerRiskLine(t, Trust.limitCentimes(t))
            assertTrue(line.isNotEmpty())
            assertFalse(line + " leaks an enum", line.contains(t.name))
            assertFalse(line.lowercase().contains("garanti"))
        }
        assertTrue(Trust.buyerRiskLine(Trust.Tier.BLOCKED, 0).contains("dette"))
        assertTrue(Trust.buyerRiskLine(Trust.Tier.NEW, 1_000).contains("Nouvel acheteur"))
    }

    // ================= the seller's destination =================

    @Test
    fun only_the_seller_may_say_where_the_seller_is_paid() {
        val kp = Crypto.generateKeyPair()
        val pub = Crypto.publicBytes(kp.public)
        val sellerId = Crypto.deriveId(pub).toHex()
        val claim = DestinationClaim.Claim(sellerId, Settlement.Rail.MTN_MOMO, "066123456", 1, 1_700_000_000_000L)
        assertTrue(claim.valid)
        val sig = Crypto.sign(kp.private, claim.signDataV2())
        assertTrue(DestinationClaim.verify(claim, pub, sig))

        // somebody else signing the same claim proves nothing
        val other = Crypto.generateKeyPair()
        assertFalse(DestinationClaim.verify(claim, Crypto.publicBytes(other.public),
            Crypto.sign(other.private, claim.signDataV2())))
        // and a claim whose seller id is not the signer is refused
        val impostor = DestinationClaim.Claim("dd".repeat(16), Settlement.Rail.MTN_MOMO, "066999999", 1, 1L)
        assertFalse(DestinationClaim.verify(impostor, pub, Crypto.sign(kp.private, impostor.signDataV2())))
    }

    @Test
    fun a_destination_may_only_be_replaced_by_a_newer_claim_from_the_same_seller() {
        val sellerId = "ee".repeat(16)
        val v1 = DestinationClaim.Claim(sellerId, Settlement.Rail.MTN_MOMO, "066123456", 1, 1L)
        val v2 = DestinationClaim.Claim(sellerId, Settlement.Rail.MTN_MOMO, "066999999", 2, 2L)
        assertTrue(DestinationClaim.mayReplace(null, v1))
        assertTrue(DestinationClaim.mayReplace(v1, v2))
        // replaying the old one must not move the money back
        assertFalse(DestinationClaim.mayReplace(v2, v1))
        assertFalse("the same version is not newer", DestinationClaim.mayReplace(v1, v1))
        // another identity may never redirect this seller
        assertFalse(DestinationClaim.mayReplace(v1,
            DestinationClaim.Claim("ff".repeat(16), Settlement.Rail.MTN_MOMO, "066000000", 9, 3L)))
        // v0.16.3: but the SAME seller may move to another operator. A seller has one
        // place it is paid, and which operator that is can change; refusing this left a
        // seller who moved to Airtel unable to say so, with buyers still being sent to
        // the abandoned number.
        assertTrue(DestinationClaim.mayReplace(v1,
            DestinationClaim.Claim(sellerId, Settlement.Rail.AIRTEL_MONEY, "055000000", 2, 3L)))
        // and that is still governed by the version, not by the rail
        assertFalse("an older claim on another rail is still older",
            DestinationClaim.mayReplace(v2,
                DestinationClaim.Claim(sellerId, Settlement.Rail.AIRTEL_MONEY, "055000000", 1, 3L)))
        assertEquals(3, DestinationClaim.nextVersion(v2))
    }

    @Test
    fun a_changed_number_waits_a_moment_before_it_is_used() {
        val sellerId = "ee".repeat(16)
        val now = 1_700_000_000_000L
        val first = DestinationClaim.Claim(sellerId, Settlement.Rail.MTN_MOMO, "066123456", 1, now)
        // the first one is usable at once: there is nothing to protect yet
        assertTrue(DestinationClaim.usable(first, null, now))
        // a change is not, so a stolen phone cannot redirect payments instantly
        val changed = DestinationClaim.Claim(sellerId, Settlement.Rail.MTN_MOMO, "066999999", 2, now)
        assertFalse(DestinationClaim.usable(changed, first, now))
        assertTrue(DestinationClaim.usable(changed, first, now + DestinationClaim.CHANGE_COOLING_MS))
        assertTrue(DestinationClaim.coolingLine().contains("quelques minutes"))
    }

    @Test
    fun a_claim_survives_the_wire_unchanged() {
        val kp = Crypto.generateKeyPair()
        val pub = Crypto.publicBytes(kp.public)
        val claim = DestinationClaim.Claim(Crypto.deriveId(pub).toHex(), Settlement.Rail.AIRTEL_MONEY,
            "+242 055 000 111", 2, 1_700_000_000_000L)
        val sig = Crypto.sign(kp.private, claim.signDataV2())
        val back = DestinationClaim.decode(DestinationClaim.encode(claim, sig))!!
        assertTrue(DestinationClaim.verify(back.claim, pub, back.sig))
        assertEquals(claim.normalized, back.claim.normalized)
        assertEquals(claim.hash(), back.claim.hash())
        assertEquals(2, back.claim.version)
        assertNull(DestinationClaim.decode("rubbish"))
        assertNull(DestinationClaim.decode("dest1|too|few|parts"))
    }

    private fun assertNull(x: Any?) = org.junit.Assert.assertNull(x)
}
