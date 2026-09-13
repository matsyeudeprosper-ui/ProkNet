package net.prok.proknet.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Marketplace: offers, ranking, money math, contracts, checkpoints, ledger. Pure JVM. */
class MarketTest {

    private val buyerKp = Crypto.generateKeyPair(); private val buyerPub = Crypto.publicBytes(buyerKp.public); private val buyerId = Crypto.deriveId(buyerPub)
    private val sellerKp = Crypto.generateKeyPair(); private val sellerPub = Crypto.publicBytes(sellerKp.public); private val sellerId = Crypto.deriveId(sellerPub)
    private val otherKp = Crypto.generateKeyPair(); private val otherId = Crypto.deriveId(Crypto.publicBytes(otherKp.public))
    private val sid = ByteArray(8) { (it + 1).toByte() }
    private fun contract(price: Int = 5, min: Int = 0, maxMb: Int = 0, fee: Int = 5, ts: Long = 1_700_000_000_000L) = Market.Contract(sid, buyerId, sellerId, price, min, maxMb, fee, ts)

    @Test
    fun offer_flags_and_scan_encoding_round_trip() {
        val f = Market.flags(sell = true, relay = true, validated = true, upstreamType = Tunnel.UP_CELLULAR)
        val o = Market.Offer("24e480e6", 5, f, -55, 0)
        assertTrue(o.selling); assertTrue(o.relaying); assertTrue(o.validated); assertEquals(Tunnel.UP_CELLULAR, o.upstreamType)
        assertEquals(Tunnel.UP_WIFI, Market.upstreamOf(Market.flags(false, false, false, Tunnel.UP_WIFI)))
        assertTrue(o.describe().contains("5 CFA/MB")); assertTrue(o.describe().contains("mobile data")); assertTrue(o.describe().contains("good"))
        assertEquals("weak", Market.signalWord(-85)); assertEquals("poor", Market.signalWord(-95))
    }

    @Test
    fun ranking_is_deterministic_and_prefers_validated_cheap_strong() {
        val sell = Market.flags(true, false, true, Tunnel.UP_CELLULAR)
        val unval = Market.flags(true, false, false, Tunnel.UP_CELLULAR)
        val off = Market.flags(false, false, true, Tunnel.UP_CELLULAR)
        val a = Market.Offer("aaaa0000", 5, sell, -55, 0)     // validated, cheap, strong
        val b = Market.Offer("bbbb0000", 5, sell, -85, 0)     // same but weak signal
        val c = Market.Offer("cccc0000", 20, sell, -50, 0)    // pricier
        val d = Market.Offer("dddd0000", 1, unval, -45, 0)    // cheapest but not validated
        val e = Market.Offer("eeee0000", 1, off, -40, 0)      // not selling
        val ranked = Market.rank(listOf(e, d, c, b, a))
        assertEquals(listOf("aaaa0000", "bbbb0000", "cccc0000", "dddd0000", "eeee0000"), ranked.map { it.sellerShort })
        assertEquals(-1, Market.score(e))
        assertEquals(ranked, Market.rank(ranked.reversed()))   // same input, same order
        // exact tie breaks on ID
        val t1 = Market.Offer("zzzz0000", 5, sell, -55, 0); val t2 = Market.Offer("yyyy0000", 5, sell, -55, 0)
        assertEquals(listOf("yyyy0000", "zzzz0000"), Market.rank(listOf(t1, t2)).map { it.sellerShort })
    }

    @Test
    fun bytes_to_cfa_is_exact_integer_math_with_half_up_rounding() {
        assertEquals(500L, Market.costCentimes(1_000_000, 5))           // 1 MB at 5 CFA = 5.00 CFA
        assertEquals(250L, Market.costCentimes(500_000, 5))             // 2.50 CFA
        assertEquals(0L, Market.costCentimes(0, 5))
        assertEquals(1L, Market.costCentimes(1_000, 5))                 // 0.005 CFA -> 0.5 centime -> rounds up to 1
        assertEquals(0L, Market.costCentimes(999, 5))                   // 0.4995 centime -> 0
        assertEquals(5_000_000L, Market.costCentimes(10_000_000_000L, 5))     // 10 GB = 50,000 CFA
        assertEquals(0L, Market.costCentimes(123_456_789, 0))           // free
        assertEquals("5.00 CFA", Market.cfa(500)); assertEquals("0.07 CFA", Market.cfa(7)); assertEquals("-1.50 CFA", Market.cfa(-150))
        assertEquals(2000L, Market.sessionCost(100, 5, 20))             // minimum session price applies
        assertEquals(500L, Market.sessionCost(1_000_000, 5, 2))
        var threw = false
        try { Market.costCentimes(-1, 5) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw); threw = false
        try { Market.costCentimes(1, Market.MAX_PRICE_PER_MB + 1) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw); threw = false
        try { Market.costCentimes(Market.MAX_BILLABLE_BYTES + 1, 1) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun prok_fee_split_sums_exactly() {
        val s = Market.split(10_000, 5)                                 // 100 CFA
        assertEquals(500L, s.fee); assertEquals(9_500L, s.sellerNet); assertEquals(s.gross, s.fee + s.sellerNet)
        val odd = Market.split(1, 5)                                    // 1 centime: fee 0.05 -> 0, seller 1
        assertEquals(0L, odd.fee); assertEquals(1L, odd.sellerNet)
        val half = Market.split(10, 5)                                  // 0.5 -> 1 (half up)
        assertEquals(1L, half.fee); assertEquals(9L, half.sellerNet)
        assertEquals(0L, Market.split(12345, 0).fee)
        var threw = false
        try { Market.split(1, 51) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun contract_round_trips_and_rejects_malformed_values() {
        val c = contract(price = 5, min = 10, maxMb = 200, fee = 5)
        val d = Market.Contract.decode(c.encode())!!
        assertTrue(c.sameTermsAs(d)); assertArrayEquals(c.hash(), d.hash())
        assertEquals(5, d.pricePerMb); assertEquals(10, d.minPriceCfa); assertEquals(200, d.maxMb); assertEquals(5, d.feePct)
        assertEquals(200L * Market.MB, d.maxBytes); assertEquals(Long.MAX_VALUE, contract().maxBytes)
        assertNull(Market.Contract.decode(null)); assertNull(Market.Contract.decode(c.encode().copyOfRange(0, 61)))
        assertNull(Market.Contract.decode(c.encode().also { it[0] = 9 }))                        // wrong version
        assertFalse(Market.Contract(sid, buyerId, sellerId, -1, 0, 0, 5, 1).valid())            // negative price
        assertFalse(Market.Contract(sid, buyerId, sellerId, Market.MAX_PRICE_PER_MB + 1, 0, 0, 5, 1).valid())
        assertFalse(Market.Contract(sid, buyerId, sellerId, 5, 0, 0, 99, 1).valid())            // fee too high
        assertFalse(Market.Contract(sid, buyerId, buyerId, 5, 0, 0, 5, 1).valid())              // buyer == seller
        assertFalse(Market.Contract(sid, buyerId, sellerId, 5, 0, 0, 5, 0).valid())             // no start time
        // encoded negative numbers decode as invalid, never as huge prices
        val neg = c.encode().also { it[41] = 0xFF.toByte(); it[42] = 0xFF.toByte(); it[43] = 0xFF.toByte(); it[44] = 0xFF.toByte() }
        assertNull(Market.Contract.decode(neg))
    }

    @Test
    fun seller_accepts_only_its_own_terms_with_the_link_peer_and_fresh_session_ids() {
        val now = 1_700_000_000_000L
        val c = contract(price = 5, min = 0, maxMb = 0, fee = 5, ts = now)
        assertNull(Market.acceptableProposal(c, sellerId, buyerId, 5, 0, 0, 5, now, emptySet()))
        assertEquals("terms differ from my offer", Market.acceptableProposal(c, sellerId, buyerId, 6, 0, 0, 5, now, emptySet()))   // price locked
        assertEquals("terms differ from my offer", Market.acceptableProposal(c, sellerId, buyerId, 5, 0, 0, 10, now, emptySet()))  // fee locked
        assertEquals("seller id is not me", Market.acceptableProposal(c, otherId, buyerId, 5, 0, 0, 5, now, emptySet()))
        assertEquals("buyer id is not the authenticated link peer", Market.acceptableProposal(c, sellerId, otherId, 5, 0, 0, 5, now, emptySet()))
        assertEquals("session id already used", Market.acceptableProposal(c, sellerId, buyerId, 5, 0, 0, 5, now, setOf(c.sessionHex)))
        assertEquals("start time too far from now", Market.acceptableProposal(c, sellerId, buyerId, 5, 0, 0, 5, now + 3600_000L, emptySet()))
        // signatures: buyer signs, seller verifies; a different contract fails
        val sig = Crypto.sign(buyerKp.private, Market.contractSignData(c))
        assertTrue(Crypto.verify(buyerPub, Market.contractSignData(c), sig))
        assertFalse(Crypto.verify(buyerPub, Market.contractSignData(contract(price = 6, ts = now)), sig))
        assertFalse(Crypto.verify(sellerPub, Market.contractSignData(c), sig))
    }

    @Test
    fun checkpoints_are_sequenced_signed_and_checked_against_the_buyers_own_count() {
        val c = contract(price = 5, min = 2)
        val c1 = Market.nextCheckpoint(c, 0, 100_000, 900_000, 1000, final = false)
        assertEquals(1, c1.seq); assertEquals(500L, c1.costCentimes)                      // 1 MB = 5.00 CFA
        val d = Market.Checkpoint.decode(c1.encode())!!
        assertEquals(c1.billable, d.billable); assertEquals(c1.costCentimes, d.costCentimes)
        // seller signs, buyer verifies
        val sig = Crypto.sign(sellerKp.private, Market.checkpointSignData(c1))
        assertTrue(Crypto.verify(sellerPub, Market.checkpointSignData(d), sig))
        assertFalse(Crypto.verify(sellerPub, Market.checkpointSignData(Market.Checkpoint(sid, 1, 100_000, 900_001, 500, 1000, false)), sig))
        // buyer validation: within tolerance of its own counters
        assertNull(Market.validateCheckpoint(c1, c, null, 100_000, 890_000))
        assertNotNull(Market.validateCheckpoint(c1, c, null, 50_000, 400_000))               // seller claims far more than I saw
        // sequence rules
        val c2 = Market.nextCheckpoint(c, 1, 200_000, 1_800_000, 2000, final = false)
        assertNull(Market.validateCheckpoint(c2, c, c1, 200_000, 1_800_000))
        assertEquals("duplicate seq 1", Market.validateCheckpoint(c1, c, c1, 200_000, 1_800_000))
        assertTrue(Market.validateCheckpoint(c1, c, c2, 200_000, 1_800_000)!!.startsWith("out-of-order"))
        assertEquals("usage decreased", Market.validateCheckpoint(Market.nextCheckpoint(c, 2, 100, 100, 3000, false), c, c2, 300_000, 2_000_000))
        // wrong cost for the bytes (tampered) is rejected even with a valid shape
        val bad = Market.Checkpoint(sid, 3, 200_000, 1_800_000, 1, 3000, false)
        assertEquals("cost does not match the agreed terms", Market.validateCheckpoint(bad, c, c2, 300_000, 2_000_000))
        // wrong session
        assertEquals("wrong session", Market.validateCheckpoint(Market.Checkpoint(ByteArray(8), 3, 0, 0, 200, 1, false), c, c2, 0, 0))
        // nothing after final
        val fin = Market.nextCheckpoint(c, 2, 200_000, 1_800_000, 4000, final = true)
        assertNull(Market.validateCheckpoint(fin, c, c2, 200_000, 1_800_000))
        assertEquals("session already finalised", Market.validateCheckpoint(Market.nextCheckpoint(c, 3, 200_000, 1_800_000, 5000, false), c, fin, 200_000, 1_800_000))
        // over the agreed maximum
        val capped = contract(price = 5, maxMb = 1)
        assertEquals("over the agreed maximum", Market.validateCheckpoint(Market.nextCheckpoint(capped, 0, 600_000, 600_000, 1, false), capped, null, 600_000, 600_000))
        assertNull(Market.Checkpoint.decode(ByteArray(10))); assertNull(Market.Checkpoint.decode(c1.encode().also { it[8] = 0; it[9] = 0; it[10] = 0; it[11] = 0 })) // seq 0
    }

    @Test
    fun both_sides_reach_the_same_final_cost_and_ledger_from_the_signed_checkpoint() {
        val c = contract(price = 5, min = 2, fee = 5)
        val last = Market.nextCheckpoint(c, 4, 4_000_000, 16_000_000, 9000, final = true)   // 20 MB -> 100.00 CFA
        val buyerView = Market.finalCost(c, last); val sellerView = Market.finalCost(Market.Contract.decode(c.encode())!!, Market.Checkpoint.decode(last.encode()))
        assertEquals(10_000L, buyerView); assertEquals(buyerView, sellerView)
        assertEquals(200L, Market.finalCost(c, null))                                        // nothing signed: minimum price only
        val entries = Market.sessionEntries(c, buyerView, 9999)
        assertEquals(2, entries.size)
        assertEquals("prok-" + c.buyerShort, entries[0].payer); assertEquals("prok-" + c.sellerShort, entries[0].recipient); assertEquals(10_000L, entries[0].amountCentimes)
        assertEquals("prok-" + c.sellerShort, entries[1].payer); assertEquals(Market.PROK_ID, entries[1].recipient); assertEquals(500L, entries[1].amountCentimes)
        // same session booked twice yields the same ids (idempotent)
        assertEquals(entries.map { it.id }, Market.sessionEntries(c, buyerView, 12345).map { it.id })
        assertTrue(Market.sessionEntries(c, 0, 1).isEmpty())
        // balances: seller is owed 100 and owes 5
        val seller = "prok-" + c.sellerShort
        assertEquals(9_500L, Market.balance(entries, seller)); assertEquals(-10_000L, Market.balance(entries, "prok-" + c.buyerShort)); assertEquals(500L, Market.balance(entries, Market.PROK_ID))
    }

    @Test
    fun settlement_state_machine() {
        val e = Market.Entry("id1", "s", "a", "b", 100, "r", 1, Market.ST_PENDING)
        val paid = Market.transition(e, "paid", 10)!!
        assertEquals(Market.ST_PENDING, paid.status); assertEquals(10L, paid.paidAt)
        val settled = Market.transition(paid, "received", 20)!!
        assertEquals(Market.ST_SETTLED, settled.status)
        assertNull(Market.transition(settled, "paid", 30))                                   // nothing after settled
        assertNull(Market.transition(settled, "dispute", 30))
        val disputed = Market.transition(e, "dispute", 5)!!
        assertEquals(Market.ST_DISPUTED, disputed.status); assertNull(Market.transition(disputed, "received", 6))
        assertEquals(Market.ST_CANCELLED, Market.transition(e, "cancel", 7)!!.status)
        assertNull(Market.transition(paid, "cancel", 8))                                      // cannot cancel once money moved
        assertNull(Market.transition(e, "bogus", 9))
        assertEquals(0L, Market.balance(listOf(settled), "b"))                                // settled entries leave the balance
        assertEquals(100L, Market.balance(listOf(paid), "b"))
    }
}
