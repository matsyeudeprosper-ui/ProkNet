package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.15.0: the signed CFA obligation.
 *
 * Every figure here has to come from something both phones signed. The two properties
 * that matter most: one session can only ever produce one obligation, and the buyer's
 * "à payer" is the seller's "à recevoir" plus the Prok fee, to the centime, derived
 * independently on each phone.
 */
class SettlementTest {
    private val now = 1_700_000_000_000L
    private val buyer = ByteArray(16) { 1 }
    private val seller = ByteArray(16) { 2 }

    private fun contract(rate: Int = 300, budget: Long = 5_000, fee: Int = 5, version: Int = Market.PRICING_VERSION_BUDGET, mode: Int = 1): Market.Contract {
        val ceiling = Pricing.bytesForBudget(budget, rate)
        return Market.Contract(ByteArray(8) { 7 }, buyer, seller, Pricing.advertisedPriceCfa(rate), 0,
            minOf(Market.MAX_MB_PER_SESSION.toLong(), (ceiling + Market.MB - 1) / Market.MB).toInt(), fee, now,
            version, rate, budget, ceiling, 0, 1, mode)
    }

    private fun checkpoint(c: Market.Contract, bytes: Long, seq: Int = 1, final: Boolean = true): Market.Checkpoint =
        Market.nextCheckpoint(c, seq - 1, bytes / 2, bytes - bytes / 2, now, final)

    // ================= derivation =================

    @Test
    fun an_obligation_comes_only_from_a_signed_session() {
        val c = contract()
        val cp = checkpoint(c, 3 * Market.MB)
        val o = Settlement.fromSession(c, cp, now)
        assertNotNull(o)
        assertTrue(o!!.valid)
        assertEquals(Settlement.Status.PENDING, o.status)
        assertEquals(c.sessionHex, o.sessionHex)
        assertEquals(cp.costCentimes, o.grossCentimes)
        assertTrue("a real session owes something", o.grossCentimes > 0)
        // the accounting identity, which is the whole point
        assertTrue(o.balanced)
        assertEquals(o.grossCentimes, o.sellerNetCentimes + o.prokFeeCentimes)
        assertEquals(Market.split(cp.costCentimes, 5).sellerNet, o.sellerNetCentimes)
        assertEquals(Market.split(cp.costCentimes, 5).fee, o.prokFeeCentimes)
    }

    @Test
    fun nothing_is_owed_when_nothing_was_signed() {
        val c = contract()
        assertNull("no checkpoint means no obligation, ever", Settlement.fromSession(c, null, now))
        // and a checkpoint from a different session cannot be borrowed
        val other = Market.Contract(ByteArray(8) { 9 }, buyer, seller, 3, 0, 16, 5, now,
            Market.PRICING_VERSION_BUDGET, 300, 5_000, Pricing.bytesForBudget(5_000, 300), 0, 1, 1)
        assertNull(Settlement.fromSession(c, checkpoint(other, 3 * Market.MB), now))
    }

    @Test
    fun free_sponsored_and_legacy_sessions_create_no_payable_obligation() {
        // free: rate 0. Nothing is owed however much was used.
        val free = Market.Contract(ByteArray(8) { 7 }, buyer, seller, 0, 0, 0, 5, now,
            Market.PRICING_VERSION_BUDGET, 0, 0, 0, 0, 1, 1)
        assertNull(Settlement.fromSession(free, checkpoint(free, 50 * Market.MB), now))
        // a legacy v1 session is out of scope for real money in v0.15
        val v1 = Market.Contract(ByteArray(8) { 7 }, buyer, seller, 5, 0, 20, 5, now)
        assertNull(Settlement.fromSession(v1, checkpoint(v1, 3 * Market.MB), now))
        // a v2 contract that is not a budget session either
        val notBudget = contract(mode = 0)
        assertNull(Settlement.fromSession(notBudget, checkpoint(notBudget, 3 * Market.MB), now))
    }

    @Test
    fun an_obligation_can_never_exceed_the_signed_budget() {
        val c = contract(budget = 5_000, rate = 300)
        val o = Settlement.fromSession(c, checkpoint(c, c.maxBytes), now)!!
        assertTrue("the budget is a ceiling on the money too", o.grossCentimes <= 5_000)
        assertTrue(o.valid)
    }

    // ================= idempotency =================

    @Test
    fun one_session_can_only_ever_produce_one_obligation() {
        val c = contract()
        val cp = checkpoint(c, 4 * Market.MB)
        val first = Settlement.fromSession(c, cp, now)!!
        val again = Settlement.fromSession(c, cp, now + 60_000)      // a restart, later
        assertEquals("the id must not depend on when it was derived", first.settlementId, again!!.settlementId)
        assertEquals(first.grossCentimes, again.grossCentimes)
        // a different usage figure is a DIFFERENT obligation, not a louder version of this one
        val more = Settlement.fromSession(c, checkpoint(c, 8 * Market.MB), now)!!
        assertNotEquals(first.settlementId, more.settlementId)
        // and the id is derivable from the signed facts alone, as the server does it
        assertEquals(first.settlementId,
            Settlement.idFor(c.sessionHex, c.hash().toHex(), Crypto.sha256(cp.encode()).toHex()))
    }

    @Test
    fun both_phones_derive_the_same_obligation_without_trusting_each_other() {
        val c = contract()
        val cp = checkpoint(c, 6 * Market.MB)
        // the buyer builds one from its copy, the seller from its own
        val fromBuyer = Settlement.fromSession(Market.Contract.decode(c.encode())!!, Market.Checkpoint.decode(cp.encode()), now)!!
        val fromSeller = Settlement.fromSession(c, cp, now + 5_000)!!
        assertTrue(Settlement.agree(fromBuyer, fromSeller))
        assertEquals("buyer obligation equals seller receivable plus the fee",
            fromBuyer.buyerOwes, fromSeller.sellerReceivable + fromSeller.prokFeeCentimes)
        assertEquals(fromBuyer.sellerNetCentimes, fromSeller.sellerReceivable)
        assertTrue(Settlement.reconcile(fromBuyer, fromSeller).status == Settlement.Status.PENDING)
    }

    @Test
    fun two_phones_reporting_different_figures_is_disputed_not_charged() {
        val c = contract()
        val small = Settlement.fromSession(c, checkpoint(c, 2 * Market.MB), now)!!
        val large = Settlement.fromSession(c, checkpoint(c, 9 * Market.MB), now)!!
        assertFalse(Settlement.agree(small, large))
        val r = Settlement.reconcile(small, large)
        assertEquals(Settlement.Status.DISPUTED, r.status)
        assertEquals("the larger figure must never be charged automatically", small.grossCentimes, r.grossCentimes)
        assertTrue(r.note.contains("vs"))
    }

    // ================= payment state =================

    @Test
    fun a_payment_event_applied_twice_changes_nothing_the_second_time() {
        val c = contract()
        val o = Settlement.fromSession(c, checkpoint(c, 3 * Market.MB), now)!!
        val paid = Settlement.applyPayment(o, Settlement.Status.CONFIRMED, Settlement.Rail.MOCK, "REF-1", now)
        assertEquals(Settlement.Status.CONFIRMED, paid.status)
        // the same webhook arrives again
        val twice = Settlement.applyPayment(paid, Settlement.Status.CONFIRMED, Settlement.Rail.MOCK, "REF-1", now)
        assertEquals(Settlement.Status.CONFIRMED, twice.status)
        assertEquals("REF-1", twice.paymentReference)
        assertEquals(paid.grossCentimes, twice.grossCentimes)
        // nothing may move a confirmed payment backwards
        assertEquals(Settlement.Status.CONFIRMED,
            Settlement.applyPayment(paid, Settlement.Status.FAILED, Settlement.Rail.MOCK, "REF-1", now).status)
        assertEquals(Settlement.Status.CONFIRMED,
            Settlement.applyPayment(paid, Settlement.Status.PENDING, Settlement.Rail.MOCK, "", now).status)
        // but a SECOND, different reference claiming to confirm the same obligation is suspicious
        assertEquals(Settlement.Status.DISPUTED,
            Settlement.applyPayment(paid, Settlement.Status.CONFIRMED, Settlement.Rail.MOCK, "REF-2", now).status)
    }

    @Test
    fun a_typed_reference_is_a_claim_and_never_a_payment() {
        val c = contract()
        val o = Settlement.fromSession(c, checkpoint(c, 3 * Market.MB), now)!!
        val manual = PaymentRails.ManualPilotRail()
        val dest = PaymentRails.Destination(Settlement.Rail.MANUAL_PILOT, "242060000000")
        val init = manual.initiate(o, dest)
        assertTrue(init.ok)
        assertEquals(Settlement.Status.PAYMENT_INITIATED, init.status)
        assertTrue("the instruction must not reveal the whole number", init.instruction.contains("•"))
        // the buyer types what the operator told them
        val seen = Settlement.applyPayment(o, manual.check("MP240101.1234.A56789"), Settlement.Rail.MANUAL_PILOT, "MP240101.1234.A56789", now)
        assertEquals("a reference somebody typed is not proof", Settlement.Status.PAYMENT_SEEN, seen.status)
        assertFalse(Settlement.isPaid(seen.status))
        assertTrue("and it still counts as owed", Settlement.isOutstanding(seen.status))
    }

    @Test
    fun an_obligation_past_its_window_expires_rather_than_lingering() {
        val c = contract()
        val o = Settlement.fromSession(c, checkpoint(c, 3 * Market.MB), now, ttlMs = 1_000)!!
        assertFalse(o.expired(now))
        assertTrue(o.expired(now + 2_000))
        val later = Settlement.applyPayment(o, Settlement.Status.PAYMENT_SEEN, Settlement.Rail.MANUAL_PILOT, "REF", now + 2_000)
        assertEquals(Settlement.Status.EXPIRED, later.status)
        // but a genuine confirmation that arrives late is still honoured
        val confirmed = Settlement.applyPayment(o, Settlement.Status.CONFIRMED, Settlement.Rail.MOCK, "REF", now + 2_000)
        assertEquals(Settlement.Status.CONFIRMED, confirmed.status)
    }

    // ================= rails =================

    @Test
    fun the_operator_rails_refuse_to_pretend() {
        val c = contract()
        val o = Settlement.fromSession(c, checkpoint(c, 3 * Market.MB), now)!!
        val dest = PaymentRails.Destination(Settlement.Rail.MTN_MOMO, "242060000000")
        for (r in listOf(PaymentRails.mtn(), PaymentRails.airtel())) {
            assertFalse(r.displayName() + " has no credentials in this build", r.available())
            val i = r.initiate(o, dest)
            assertFalse("it must refuse rather than simulate success", i.ok)
            assertNotEquals(Settlement.Status.CONFIRMED, i.status)
            assertEquals(Settlement.Status.PENDING, r.check("anything"))
        }
    }

    @Test
    fun the_mock_rail_is_invisible_outside_developer_mode() {
        val c = contract()
        val o = Settlement.fromSession(c, checkpoint(c, 3 * Market.MB), now)!!
        val dest = PaymentRails.Destination(Settlement.Rail.MOCK, "242060000000")
        val consumer = PaymentRails.MockRail { false }
        assertFalse(consumer.available())
        assertFalse("a mock payment must never reach a real user", consumer.initiate(o, dest).ok)
        assertEquals(Settlement.Status.PENDING, consumer.check("MOCK-anything"))

        val dev = PaymentRails.MockRail { true }
        assertTrue(dev.available())
        val i = dev.initiate(o, dest)
        assertTrue(i.ok)
        assertEquals(Settlement.Status.CONFIRMED, dev.check(i.reference))
        // and it can rehearse the unhappy paths
        dev.failNext = true
        val bad = dev.initiate(o, dest)
        assertEquals(Settlement.Status.FAILED, dev.check(bad.reference))
    }

    @Test
    fun a_payment_destination_is_never_shown_in_full() {
        val d = PaymentRails.Destination(Settlement.Rail.MTN_MOMO, "242060123456", "Prosper")
        assertTrue(d.valid)
        assertEquals("•••••456", d.masked())
        assertFalse("the full number must not be in the masked form", d.masked().contains("242060"))
        assertFalse(PaymentRails.Destination(Settlement.Rail.NONE, "242060123456").valid)
        assertFalse(PaymentRails.Destination(Settlement.Rail.MTN_MOMO, "abc").valid)
    }
}
