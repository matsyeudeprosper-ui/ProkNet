package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.14.1: the whole budget session, from the first tap to the ledger.
 *
 * `Gateway` and `TunnelClient` cannot be built in a JVM test: they need an
 * Android Context and a main Looper, and this project has no Robolectric and no
 * AndroidX. So the two phones are modelled here as the message sequence they
 * actually exchange, and every decision in that sequence is taken by the same
 * `Market` function the phones call. Nothing about contract framing, admission,
 * checkpoint validation, cost or settlement is reimplemented for the test.
 *
 * This is the layer v0.14.0 never tested, which is why a one-line length
 * assumption reached the phones.
 */
class BudgetSessionTest {
    private val now = 1_700_000_000_000L
    private val fee = 5

    private class Phone {
        private val kp = Crypto.generateKeyPair()
        val pub: ByteArray = Crypto.publicBytes(kp.public)
        val id: ByteArray = Crypto.deriveId(pub)
        fun sign(d: ByteArray): ByteArray = Crypto.sign(kp.private, d)
    }

    /** Stands in for the ledger table. `insertLedger` is an id-keyed insert, so this is its behaviour. */
    private class Ledger {
        val entries = ArrayList<Market.Entry>()
        private val ids = HashSet<String>()
        fun insert(e: Market.Entry): Boolean = if (ids.add(e.id)) { entries.add(e); true } else false
        fun total(): Long = entries.sumOf { it.amountCentimes }
    }

    /** One run of the real message sequence between two phones. */
    private inner class Link(val budget: Long = 5_000, val rate: Int = 300, val sellerFloor: Int = 0) {
        val buyer = Phone()
        val seller = Phone()
        val ledger = Ledger()
        val used = HashSet<String>()

        // seller state, exactly the fields Gateway keeps
        var sellerContract: Market.Contract? = null
        var lastIssued: Market.Checkpoint? = null
        var lastSigned: Market.Checkpoint? = null
        var sellerReject = ""

        // buyer state, exactly the fields TunnelClient keeps
        var buyerContract: Market.Contract? = null
        var lastAccepted: Market.Checkpoint? = null
        var buyerUp = 0L
        var buyerDown = 0L
        var buyerError = ""

        fun proposal(sessionId: Byte = 1, at: Long = now, budgetOverride: Long = -1, rateOverride: Int = -1): ByteArray {
            val b = if (budgetOverride >= 0) budgetOverride else budget
            val r = if (rateOverride >= 0) rateOverride else rate
            val ceiling = Pricing.bytesForBudget(b, r)
            val c = Market.Contract(ByteArray(8) { sessionId }, buyer.id, seller.id, Pricing.advertisedPriceCfa(r), 0,
                minOf(Market.MAX_MB_PER_SESSION.toLong(), (ceiling + Market.MB - 1) / Market.MB).toInt(), fee, at,
                Market.PRICING_VERSION_BUDGET, r, b, ceiling, 0, 1, 1)
            return Tunnel.signed(c.encode(), buyer.sign(Market.contractSignData(c)))
        }

        /** T_CONTRACT_PROPOSE arrives at the seller. Returns the frame the seller sends back. */
        fun propose(wire: ByteArray, at: Long = now): Pair<Int, ByteArray> {
            val c0 = Market.decodeSignedContract(wire)?.contract
            val a = Market.admitProposal(wire, seller.id, buyer.id, buyer.pub,
                c0?.pricePerMb ?: 0, 0, c0?.maxMb ?: 0, fee, at, used, sellerFloor)
            val why = a.reason
            if (why != null) {
                sellerReject = why
                // Gateway keeps no contract after a rejection
                sellerContract = null; lastIssued = null; lastSigned = null
                return Tunnel.T_CONTRACT_REJECT to why.toByteArray()
            }
            val c = a.contract!!
            sellerContract = c; lastIssued = null; lastSigned = null; sellerReject = ""
            used.add(c.sessionHex)
            return Tunnel.T_CONTRACT_ACCEPT to Tunnel.signed(c.hash(), seller.sign(Market.contractSignData(c)))
        }

        /** The buyer's side of the answer. Null = agreed; else the reason it failed. */
        fun onAnswer(answer: Pair<Int, ByteArray>, proposed: Market.Contract): String? {
            if (answer.first == Tunnel.T_CONTRACT_REJECT) {
                buyerError = String(answer.second)
                // v0.14.1: nothing economic survives a rejection
                buyerContract = null; lastAccepted = null
                return buyerError
            }
            val sb = Tunnel.parseSigned(answer.second, 32) ?: return "malformed accept"
            if (!sb.body.contentEquals(proposed.hash())) return "seller accepted a different contract"
            if (!Crypto.verify(seller.pub, Market.contractSignData(proposed), sb.sig)) return "bad seller signature"
            buyerContract = proposed; buyerError = ""
            return null
        }

        fun agree(sessionId: Byte = 1, at: Long = now): String? {
            val wire = proposal(sessionId, at)
            val proposed = Market.decodeSignedContract(wire)!!.contract
            return onAnswer(propose(wire, at), proposed)
        }

        /** The buyer opens the session; the seller checks the hash, as it does on the phone. */
        fun sessionStart(): Boolean {
            val c = buyerContract ?: return false
            val start = Tunnel.sessionStart(buyer.id, c.hash())
            val req = Tunnel.parseSessionStart(start) ?: return false
            val s = sellerContract ?: return false
            return req.contractHash != null && req.contractHash.contentEquals(s.hash()) && req.buyerId.contentEquals(s.buyerId)
        }

        /** Traffic flows, the seller issues a checkpoint, the buyer validates and countersigns it. */
        fun checkpoint(up: Long, down: Long, at: Long = now, final: Boolean = false): String? {
            val c = sellerContract ?: return "no contract"
            buyerUp = up; buyerDown = down
            val cp = Market.nextCheckpoint(c, lastIssued?.seq ?: 0, up, down, at, final)
            lastIssued = cp
            val wire = Tunnel.signed(cp.encode(), seller.sign(Market.checkpointSignData(cp)))
            // buyer side
            val sb = Tunnel.parseSigned(wire, Market.Checkpoint.LEN) ?: return "malformed checkpoint"
            val got = Market.Checkpoint.decode(sb.body) ?: return "malformed checkpoint"
            if (!Crypto.verify(seller.pub, Market.checkpointSignData(got), sb.sig)) return "bad seller signature"
            val why = Market.validateCheckpoint(got, buyerContract!!, lastAccepted, buyerUp, buyerDown)
            if (why != null) return why
            lastAccepted = got
            lastSigned = got
            return null
        }

        /** Both phones close the session and book what is owed. */
        fun settle(at: Long = now): Long {
            val c = sellerContract ?: return 0
            val cost = Market.finalCost(c, lastSigned)
            for (e in Market.sessionEntries(c, cost, at)) ledger.insert(e)
            sellerContract = null; buyerContract = null; lastIssued = null; lastSigned = null; lastAccepted = null
            buyerUp = 0; buyerDown = 0
            return cost
        }
    }

    // ================= the happy path =================

    @Test
    fun a_budget_session_runs_from_the_proposal_to_the_ledger() {
        val link = Link(budget = 5_000, rate = 300)
        assertNull("the contract must be agreed", link.agree())
        assertNotNull(link.sellerContract)
        assertTrue(link.sellerContract!!.budgetSession)
        assertTrue("the seller must accept the session the buyer opens", link.sessionStart())

        // three checkpoints as traffic grows
        assertNull(link.checkpoint(300_000, 700_000))
        assertNull(link.checkpoint(600_000, 1_600_000))
        assertNull(link.checkpoint(900_000, 2_200_000, final = true))

        val used = 900_000L + 2_200_000L
        val expected = link.sellerContract!!.costFor(used)
        val cost = link.settle()
        assertEquals("the session costs exactly what the terms give for those bytes", expected, cost)
        assertTrue("and far less than the budget", cost in 1..4_999)

        // the buyer owes the gross, the seller owes Prok the fee, and they add up
        val split = Market.split(cost, fee)
        assertEquals(2, link.ledger.entries.size)
        assertEquals(cost + split.fee, link.ledger.total())
        assertEquals(split.gross, link.ledger.entries[0].amountCentimes)
        assertEquals(split.fee, link.ledger.entries[1].amountCentimes)
        assertEquals(split.gross, split.fee + split.sellerNet)
    }

    @Test
    fun three_budget_sessions_in_a_row_all_work() {
        // the standard the activation path had to meet in v0.13.3, now for money
        val link = Link(budget = 5_000, rate = 300)
        var booked = 0
        for (i in 1..3) {
            assertNull("session " + i + " must agree", link.agree(sessionId = i.toByte(), at = now + i * 1000))
            assertTrue("session " + i + " must open", link.sessionStart())
            assertNull("session " + i + " checkpoint", link.checkpoint(100_000L * i, 400_000L * i, at = now + i * 1000, final = true))
            val cost = link.settle(now + i * 1000)
            assertTrue("session " + i + " must cost something", cost > 0)
            booked += 2
            assertEquals("each session books its own entries", booked, link.ledger.entries.size)
            // nothing may survive into the next tap
            assertNull(link.sellerContract); assertNull(link.buyerContract)
            assertNull(link.lastSigned); assertNull(link.lastAccepted)
            assertEquals("", link.sellerReject)
        }
        assertEquals(3, link.used.size)
    }

    // ================= failure leaves nothing behind =================

    @Test
    fun a_rejected_contract_leaves_both_phones_clean_for_the_next_tap() {
        // the seller's floor is above the offered rate, so the first attempt is refused
        val link = Link(budget = 5_000, rate = 200, sellerFloor = 400)
        val why = link.agree()
        assertEquals("seller floor not met", why)
        assertNull("the seller keeps no contract", link.sellerContract)
        assertNull("and the buyer keeps none either", link.buyerContract)
        assertTrue("the session id was never consumed", link.used.isEmpty())
        assertEquals("seller floor not met", link.buyerError)

        // the buyer retries at a price that works, with a new session id: it must simply succeed
        val wire = link.proposal(sessionId = 2, rateOverride = 500)
        val proposed = Market.decodeSignedContract(wire)!!.contract
        assertNull("the retry must not be poisoned by the refusal", link.onAnswer(link.propose(wire), proposed))
        assertNotNull(link.sellerContract)
        assertTrue(link.sessionStart())
        assertNull(link.checkpoint(500_000, 500_000, final = true))
        assertTrue(link.settle() > 0)
    }

    @Test
    fun the_v0_14_0_hardware_failure_cannot_come_back() {
        // exactly the sequence the phones ran: probe passed, then the proposal was thrown away
        val link = Link(budget = 5_000, rate = 300)
        val wire = link.proposal()
        assertEquals("the body really is the v2 size", Market.Contract.LEN_V2, Market.decodeSignedContract(wire)!!.contract.encode().size)
        assertNull("parsed at the v1 length, this is what v0.14.0 threw away", Tunnel.parseSigned(wire, Market.Contract.LEN))
        assertNull("v0.14.0 answered 'malformed proposal' here", link.agree())
        assertEquals("", link.sellerReject)
        assertEquals("", link.buyerError)
    }

    // ================= the ceiling and the money =================

    @Test
    fun the_buyer_refuses_a_checkpoint_beyond_the_signed_ceiling() {
        val link = Link(budget = 5_000, rate = 300)
        assertNull(link.agree())
        val ceiling = link.sellerContract!!.maxBytes
        assertTrue(ceiling > 0)
        // right up to the ceiling is fine
        assertNull(link.checkpoint(ceiling / 2, ceiling - ceiling / 2))
        // past it is not, however honestly it is signed and however much really flowed
        val c = link.sellerContract!!
        val over = Market.nextCheckpoint(c, link.lastIssued!!.seq, ceiling, ceiling, now, true)
        assertEquals("over the agreed maximum", Market.validateCheckpoint(over, c, link.lastAccepted, ceiling, ceiling))
        // and the charge is capped at the budget either way
        assertTrue(c.costFor(ceiling + 1_000_000) <= 5_000)
    }

    @Test
    fun stopping_early_pays_for_what_was_used_not_for_the_budget() {
        val link = Link(budget = 10_000, rate = 300)
        assertNull(link.agree())
        assertNull(link.checkpoint(50_000, 150_000, final = true))
        val cost = link.settle()
        assertTrue("a short session must cost something", cost > 0)
        assertTrue("but nothing like the 10 000 centime budget", cost < 1_000)
        assertEquals(Market.split(cost, fee).gross, link.ledger.entries.firstOrNull()?.amountCentimes ?: 0L)
    }

    @Test
    fun a_session_that_moved_no_data_owes_nothing() {
        val link = Link(budget = 5_000, rate = 300)
        assertNull(link.agree())
        assertEquals(0L, link.settle())
        assertTrue("nothing is booked for a session nobody used", link.ledger.entries.isEmpty())
    }

    @Test
    fun the_same_session_can_never_be_booked_twice() {
        val link = Link(budget = 5_000, rate = 300)
        assertNull(link.agree())
        assertNull(link.checkpoint(400_000, 900_000, final = true))
        val c = link.sellerContract!!
        val cost = Market.finalCost(c, link.lastSigned)
        val entries = Market.sessionEntries(c, cost, now)
        for (e in entries) assertTrue("the first booking succeeds", link.ledger.insert(e))
        // both phones settle, the app restarts, the same close runs again: the ledger must not grow
        for (e in Market.sessionEntries(c, cost, now + 60_000)) assertFalse("the second booking is refused", link.ledger.insert(e))
        assertEquals(2, link.ledger.entries.size)
        assertEquals(cost + Market.split(cost, fee).fee, link.ledger.total())
    }

    // ================= free =================

    @Test
    fun a_free_session_runs_end_to_end_and_costs_nothing() {
        val link = Link(budget = 5_000, rate = 300)
        val free = Market.Contract(ByteArray(8) { 9 }, link.buyer.id, link.seller.id, 0, 0, 0, fee, now,
            Market.PRICING_VERSION_BUDGET, 0, 0, 0, 0, 1, 1)
        val wire = Tunnel.signed(free.encode(), link.buyer.sign(Market.contractSignData(free)))
        assertNull("a free contract is agreed, not 'malformed'", link.onAnswer(link.propose(wire), free))
        assertTrue(link.sessionStart())
        assertNull(link.checkpoint(5_000_000, 20_000_000, final = true))
        assertEquals("free stays free however much is used", 0L, link.settle())
        assertTrue(link.ledger.entries.isEmpty())
    }

    // ================= a v1 session still works =================

    @Test
    fun a_legacy_v1_session_is_unchanged_by_all_of_this() {
        val link = Link()
        val c = Market.Contract(ByteArray(8) { 3 }, link.buyer.id, link.seller.id, 5, 0, 20, fee, now)
        val wire = Tunnel.signed(c.encode(), link.buyer.sign(Market.contractSignData(c)))
        assertNull(link.onAnswer(link.propose(wire), c))
        assertFalse(link.sellerContract!!.budgetSession)
        assertTrue(link.sessionStart())
        assertNull(link.checkpoint(1_000_000, 2_000_000, final = true))
        val cost = link.settle()
        assertEquals(Market.sessionCost(3_000_000, 5, 0), cost)
        assertEquals(2, link.ledger.entries.size)
    }
}
