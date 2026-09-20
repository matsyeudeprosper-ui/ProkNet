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
            stopped = false
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

        // ---- v0.14.2: stopping, driven by the same core/Teardown the phones drive ----
        var buyerStop = Teardown.State()
        var sellerStop = Teardown.State()
        var bulkClosed = false
        var lateCallbacksIgnored = 0
        /** The outcome of the last Stop, kept after the session state is cleared. */
        var lastStopFinal = Teardown.Final.NONE
        var stopped = false

        /**
         * The buyer presses Stop. The exact production sequence: say so over the LIVE
         * link, the seller issues the closing checkpoint for what was really used, the
         * buyer verifies and countersigns it, and only then does anything close.
         *
         * @param usedUp/usedDown what the session actually carried
         * @param linkUp false to model a peer that has already walked away
         */
        fun stop(usedUp: Long, usedDown: Long, at: Long = now, linkUp: Boolean = true): Long {
            if (stopped || buyerStop.settling) { lateCallbacksIgnored++; return 0 }   // a second Stop is not a second settlement
            stopped = true
            buyerStop = Teardown.begin(Teardown.running(buyerStop), "stopped by user", linkUp)
            if (buyerStop.settling) {
                // seller side: SESSION_END arrives, it finalises
                sellerStop = Teardown.begin(Teardown.running(sellerStop), "buyer ended", true)
                val why = checkpoint(usedUp, usedDown, at, final = true)
                if (why == null) {
                    sellerStop = Teardown.onFinalSigned(sellerStop)
                    buyerStop = Teardown.onFinalSigned(buyerStop)
                } else {
                    sellerStop = Teardown.onLinkGone(sellerStop, why)
                    buyerStop = Teardown.onLinkGone(buyerStop, why)
                }
            }
            // only now may the transport go
            check(buyerStop.mayClose) { "the link was closed before the session had settled" }
            lastStopFinal = buyerStop.final
            bulkClosed = true
            return settle(at)
        }

        /**
         * v0.15.0: the SELLER presses Stop. Same machine, other role.
         *
         * The seller issues the closing checkpoint, keeps the link up while the buyer
         * countersigns it, settles on that figure, and only then tells the buyer the
         * session is over. Before v0.15.0 this path settled on the PREVIOUS checkpoint
         * and cleared the contract, so a short session was free whenever the seller was
         * the one to stop.
         */
        fun sellerStop(usedUp: Long, usedDown: Long, at: Long = now, linkUp: Boolean = true): Long {
            if (stopped || sellerStop.settling) { lateCallbacksIgnored++; return 0 }
            stopped = true
            sellerStop = Teardown.begin(Teardown.running(sellerStop), "sharing stopped", linkUp, Teardown.Cause.LOCAL_STOP)
            if (sellerStop.settling) {
                val why = checkpoint(usedUp, usedDown, at, final = true)
                if (why == null) {
                    sellerStop = Teardown.onFinalSigned(sellerStop)
                    // only now does the buyer hear that the session ended
                    buyerStop = Teardown.begin(Teardown.running(buyerStop), Tunnel.END_PROVIDER_STOPPED, false, Teardown.Cause.PEER_STOP)
                    buyerEnded = Tunnel.END_PROVIDER_STOPPED
                } else {
                    sellerStop = Teardown.onLinkGone(sellerStop, why)
                    buyerStop = Teardown.onLinkGone(Teardown.running(buyerStop), why)
                }
            }
            check(sellerStop.mayClose) { "the link was closed before the seller had settled" }
            lastStopFinal = sellerStop.final
            bulkClosed = true
            return settle(at)
        }

        /** What the buyer was told when the seller ended it. */
        var buyerEnded = ""

        /** v0.15: what the session settled on, kept after the live state is cleared. */
        var settledContract: Market.Contract? = null
        var settledCheckpoint: Market.Checkpoint? = null

        /** A callback from a finished session arriving late must change nothing. */
        fun lateCallback(token: Int) {
            val next = Teardown.onTimeout(buyerStop, token)
            if (next === buyerStop) lateCallbacksIgnored++ else buyerStop = next
        }

        /** Both phones close the session and book what is owed. */
        fun settle(at: Long = now): Long {
            val c = sellerContract ?: return 0
            val cost = Market.finalCost(c, lastSigned)
            for (e in Market.sessionEntries(c, cost, at)) ledger.insert(e)
            settledContract = c; settledCheckpoint = lastSigned
            sellerContract = null; buyerContract = null; lastIssued = null; lastSigned = null; lastAccepted = null
            buyerUp = 0; buyerDown = 0
            buyerStop = Teardown.State(); sellerStop = Teardown.State()
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
    // ================= v0.14.2: stopping =================

    @Test
    fun a_session_stopped_before_the_first_periodic_checkpoint_still_pays() {
        // THE v0.14.1 hole. The seller only issued a checkpoint every 30 s, and the buyer
        // tore the session down in the same breath as SESSION_END, so anything shorter
        // settled at zero. Stopping early was a way to browse for nothing.
        for (seconds in listOf(5L, 15L, 29L, 31L)) {
            val link = Link(budget = 5_000, rate = 300)
            assertNull(link.agree())
            assertTrue(link.sessionStart())
            // a periodic checkpoint only happens once the interval has passed
            val periodic = seconds * 1000 >= Market.CHECKPOINT_INTERVAL_MS
            val up = 40_000L * seconds
            val down = 120_000L * seconds
            if (periodic) assertNull(link.checkpoint(up / 2, down / 2, at = now + Market.CHECKPOINT_INTERVAL_MS))
            else assertNull("no periodic checkpoint is due yet", link.lastSigned)

            val cost = link.stop(up, down, at = now + seconds * 1000)
            assertTrue("a " + seconds + " s session must not be free", cost > 0)
            assertTrue("and never more than the budget", cost <= 5_000)
            assertEquals("it must bill the closing figure, not the periodic one",
                link.ledger.entries.first().amountCentimes, Market.split(cost, 5).gross)
            assertTrue("the seller must earn something", Market.split(cost, 5).sellerNet > 0)
            assertEquals("and the fee must be the agreed share", Market.split(cost, 5).fee, link.ledger.entries[1].amountCentimes)
            assertTrue("the link may only close once the figure is signed", link.bulkClosed)
            assertEquals(Teardown.Final.PASS, link.lastStopFinal)
        }
    }

    @Test
    fun the_closing_figure_is_the_one_that_bills() {
        val link = Link(budget = 5_000, rate = 300)
        assertNull(link.agree())
        // a periodic checkpoint at 1 MB, then another 2 MB flows before Stop
        assertNull(link.checkpoint(400_000, 648_576))
        val periodic = link.lastSigned!!
        val cost = link.stop(1_400_000, 1_700_000, at = now + 40_000)
        assertTrue("the closing figure must cover the traffic after the last periodic one",
            cost > periodic.costCentimes)
        assertEquals(2, link.ledger.entries.size)
    }

    @Test
    fun pressing_stop_twice_settles_once() {
        val link = Link(budget = 5_000, rate = 300)
        assertNull(link.agree())
        val first = link.stop(500_000, 900_000)
        assertTrue(first > 0)
        val entries = link.ledger.entries.size
        val total = link.ledger.total()
        // the screen and the system lifecycle both clean up
        link.stop(500_000, 900_000)
        link.stop(500_000, 900_000)
        assertEquals("no second settlement", entries, link.ledger.entries.size)
        assertEquals("and no second charge", total, link.ledger.total())
    }

    @Test
    fun a_peer_that_disappears_during_the_stop_does_not_hang_the_phone() {
        // Bluetooth goes off while the closing figure is outstanding
        val link = Link(budget = 5_000, rate = 300)
        assertNull(link.agree())
        assertNull(link.checkpoint(300_000, 700_000))
        val signed = link.lastSigned!!.costCentimes
        val cost = link.stop(900_000, 2_000_000, linkUp = false)
        assertEquals("settle on the last figure both sides did sign", signed, cost)
        assertTrue("and finish locally rather than wait", link.bulkClosed)
        assertEquals(Teardown.Final.UNAVAILABLE, link.lastStopFinal)
        // nothing is invented for the traffic nobody signed for
        assertTrue(cost <= 5_000)
    }

    @Test
    fun a_late_callback_from_a_finished_session_is_ignored() {
        val link = Link(budget = 5_000, rate = 300)
        assertNull(link.agree())
        val token = Teardown.begin(Teardown.running(), "stopped by user", true).token
        assertTrue(link.stop(400_000, 800_000) > 0)
        val entries = link.ledger.entries.size
        link.lateCallback(token)          // an old timer fires after everything closed
        link.lateCallback(token + 99)
        assertEquals("a stale callback must not settle anything", entries, link.ledger.entries.size)
        assertTrue(link.lateCallbacksIgnored >= 2)
    }

    @Test
    fun stopping_one_session_lets_the_next_one_start_at_once() {
        // no app restart, no Bluetooth toggle: Stop then GET INTERNET again
        val link = Link(budget = 5_000, rate = 300)
        var booked = 0
        for (i in 1..3) {
            assertNull("session " + i + " must agree", link.agree(sessionId = i.toByte(), at = now + i * 1000))
            assertTrue("session " + i + " must open", link.sessionStart())
            val cost = link.stop(200_000L * i, 500_000L * i, at = now + i * 1000)
            assertTrue("session " + i + " must bill what it used", cost > 0)
            booked += 2
            assertEquals(booked, link.ledger.entries.size)
            // requirement 8: everything is clean for the next tap
            assertNull(link.sellerContract); assertNull(link.buyerContract)
            assertNull(link.lastSigned); assertNull(link.lastAccepted)
            assertEquals("", link.sellerReject); assertEquals("", link.buyerError)
            assertEquals(Teardown.Phase.IDLE, link.buyerStop.phase)
        }
        assertEquals(3, link.used.size)
    }

    @Test
    fun the_proven_v0_14_1_sequence_is_unchanged_by_the_new_shutdown() {
        // contract -> session -> checkpoints, exactly as the phones ran it, then a clean stop
        val link = Link(budget = 5_000, rate = 300)
        assertNull(link.agree())
        assertTrue(link.sellerContract!!.budgetSession)
        assertEquals(Market.PRICING_VERSION_BUDGET, link.sellerContract!!.version)
        assertTrue(link.sessionStart())
        assertNull(link.checkpoint(100_000, 300_000, at = now + 36_000))
        assertNull(link.checkpoint(200_000, 700_000, at = now + 66_000))
        assertNull(link.checkpoint(300_000, 1_100_000, at = now + 97_000))
        assertEquals(3, link.lastSigned!!.seq)
        val cost = link.stop(320_000, 1_200_000, at = now + 120_000)
        assertTrue(cost > 0 && cost <= 5_000)
        assertEquals("one gross entry and one fee entry", 2, link.ledger.entries.size)
    }

    // ================= v0.15.0 PART A: the seller stops =================

    @Test
    fun a_short_session_the_seller_ends_is_not_free_either() {
        // the seller-side twin of the v0.14.2 bug: the closing checkpoint was issued and
        // then settled past, on the PREVIOUS one, before the buyer could countersign
        for (seconds in listOf(5L, 15L, 29L, 31L)) {
            val link = Link(budget = 5_000, rate = 300)
            assertNull(link.agree())
            assertTrue(link.sessionStart())
            val periodic = seconds * 1000 >= Market.CHECKPOINT_INTERVAL_MS
            val up = 40_000L * seconds
            val down = 120_000L * seconds
            if (periodic) assertNull(link.checkpoint(up / 2, down / 2, at = now + Market.CHECKPOINT_INTERVAL_MS))

            val cost = link.sellerStop(up, down, at = now + seconds * 1000)
            assertTrue("a " + seconds + " s session the seller ended must not be free", cost > 0)
            assertTrue(cost <= 5_000)
            val split = Market.split(cost, 5)
            assertTrue("the seller must earn something", split.sellerNet > 0)
            assertEquals(split.gross, link.ledger.entries[0].amountCentimes)
            assertEquals(split.fee, link.ledger.entries[1].amountCentimes)
            assertEquals(Teardown.Final.PASS, link.lastStopFinal)
            assertEquals("and the buyer must be told, not left guessing",
                Tunnel.END_PROVIDER_STOPPED, link.buyerEnded)
        }
    }

    @Test
    fun it_costs_the_same_whoever_pressed_stop() {
        // THE invariant the money layer rests on: the role decides who sends which frame,
        // never what the session costs
        for (seconds in listOf(5L, 20L, 45L)) {
            val up = 60_000L * seconds
            val down = 140_000L * seconds
            val byBuyer = Link(budget = 5_000, rate = 300)
            assertNull(byBuyer.agree()); assertTrue(byBuyer.sessionStart())
            val buyerCost = byBuyer.stop(up, down, at = now + seconds * 1000)

            val bySeller = Link(budget = 5_000, rate = 300)
            assertNull(bySeller.agree()); assertTrue(bySeller.sessionStart())
            val sellerCost = bySeller.sellerStop(up, down, at = now + seconds * 1000)

            assertEquals("identical signed usage must settle identically", buyerCost, sellerCost)
            assertEquals(Market.split(buyerCost, 5).sellerNet, Market.split(sellerCost, 5).sellerNet)
            assertEquals(Market.split(buyerCost, 5).fee, Market.split(sellerCost, 5).fee)
            assertEquals(byBuyer.ledger.total(), bySeller.ledger.total())
        }
    }

    @Test
    fun the_seller_pressing_stop_twice_settles_once() {
        val link = Link(budget = 5_000, rate = 300)
        assertNull(link.agree())
        val first = link.sellerStop(400_000, 1_000_000)
        assertTrue(first > 0)
        val entries = link.ledger.entries.size
        val total = link.ledger.total()
        link.sellerStop(400_000, 1_000_000)
        link.sellerStop(400_000, 1_000_000)
        assertEquals(entries, link.ledger.entries.size)
        assertEquals(total, link.ledger.total())
    }

    @Test
    fun a_buyer_that_vanishes_before_the_ack_settles_on_what_was_already_signed() {
        val link = Link(budget = 5_000, rate = 300)
        assertNull(link.agree())
        assertNull(link.checkpoint(250_000, 550_000))
        val alreadySigned = link.lastSigned!!.costCentimes
        assertTrue(alreadySigned > 0)
        val cost = link.sellerStop(1_500_000, 2_500_000, linkUp = false)
        assertEquals("never invent usage nobody signed for", alreadySigned, cost)
        assertTrue("and never hang waiting", link.bulkClosed)
        assertEquals(Teardown.Final.UNAVAILABLE, link.lastStopFinal)
    }

    @Test
    fun a_seller_stop_with_nothing_signed_yet_and_no_link_owes_nothing() {
        // honest boundary: no checkpoint was ever countersigned and the buyer is gone
        val link = Link(budget = 5_000, rate = 300)
        assertNull(link.agree())
        assertEquals(0L, link.sellerStop(900_000, 1_100_000, linkUp = false))
        assertTrue("nothing may be booked for usage nobody signed for", link.ledger.entries.isEmpty())
        assertTrue(link.bulkClosed)
    }

    @Test
    fun the_seller_can_stop_one_session_and_serve_the_next_at_once() {
        val link = Link(budget = 5_000, rate = 300)
        var booked = 0
        for (i in 1..3) {
            assertNull(link.agree(sessionId = i.toByte(), at = now + i * 1000))
            assertTrue(link.sessionStart())
            val cost = link.sellerStop(150_000L * i, 450_000L * i, at = now + i * 1000)
            assertTrue("session " + i + " must bill what it used", cost > 0)
            booked += 2
            assertEquals(booked, link.ledger.entries.size)
            assertNull(link.sellerContract); assertNull(link.buyerContract)
            assertNull(link.lastSigned); assertNull(link.lastAccepted)
            assertEquals(Teardown.Phase.IDLE, link.sellerStop.phase)
        }
        assertEquals(3, link.used.size)
    }

    // ================= v0.15.0: what the session owes =================

    @Test
    fun a_finished_session_produces_one_obligation_both_phones_agree_on() {
        val link = Link(budget = 5_000, rate = 300)
        assertNull(link.agree())
        assertTrue(link.sessionStart())
        val cost = link.stop(400_000, 1_100_000, at = now + 8_000)
        assertTrue("a short session still owes something", cost > 0)

        // the buyer derives it from its copy of the signed session, the seller from its own
        val fromBuyer = Settlement.fromSession(link.settledContract!!, link.settledCheckpoint, now)!!
        val fromSeller = Settlement.fromSession(
            Market.Contract.decode(link.settledContract!!.encode())!!,
            Market.Checkpoint.decode(link.settledCheckpoint!!.encode()), now + 3_000)!!
        assertTrue("neither phone has to trust the other", Settlement.agree(fromBuyer, fromSeller))
        assertEquals(cost, fromBuyer.buyerOwes)
        assertEquals("buyer obligation is seller receivable plus the Prok fee",
            fromBuyer.buyerOwes, fromSeller.sellerReceivable + fromSeller.prokFeeCentimes)
        assertTrue(fromBuyer.balanced && fromBuyer.valid)
        assertEquals(Settlement.Status.PENDING, fromBuyer.status)
    }

    @Test
    fun it_owes_the_same_whoever_pressed_stop() {
        // the Part A invariant, carried all the way into the money layer
        val byBuyer = Link(budget = 5_000, rate = 300)
        assertNull(byBuyer.agree()); assertTrue(byBuyer.sessionStart())
        byBuyer.stop(500_000, 1_300_000, at = now + 9_000)
        val a = Settlement.fromSession(byBuyer.settledContract!!, byBuyer.settledCheckpoint, now)!!

        val bySeller = Link(budget = 5_000, rate = 300)
        assertNull(bySeller.agree()); assertTrue(bySeller.sessionStart())
        bySeller.sellerStop(500_000, 1_300_000, at = now + 9_000)
        val b = Settlement.fromSession(bySeller.settledContract!!, bySeller.settledCheckpoint, now)!!

        assertEquals("the same usage owes the same money whoever ended it", a.grossCentimes, b.grossCentimes)
        assertEquals(a.sellerNetCentimes, b.sellerNetCentimes)
        assertEquals(a.prokFeeCentimes, b.prokFeeCentimes)
    }

    @Test
    fun three_small_sessions_become_one_payment_and_then_the_limit_bites() {
        val me = "aa".repeat(16)
        val seller = "bb".repeat(16)
        val owed = ArrayList<Settlement.Obligation>()
        for (i in 1..3) {
            val link = Link(budget = 5_000, rate = 300)
            assertNull(link.agree(sessionId = i.toByte(), at = now + i * 1000))
            assertTrue(link.sessionStart())
            link.stop(300_000L * i, 900_000L * i, at = now + i * 1000)
            val o = Settlement.fromSession(link.settledContract!!, link.settledCheckpoint, now)!!
            // re-home it onto one buyer/seller pair so the netting is the thing under test
            owed.add(Settlement.Obligation(o.settlementId + i, o.sessionHex, me, seller, o.finalCheckpointHash,
                o.grossCentimes, o.sellerNetCentimes, o.prokFeeCentimes, o.createdAt, o.expiresAt))
        }
        val net = Wallet.netOwedTo(owed, me, seller)
        assertEquals("one payment clears all three", owed.sumOf { it.buyerOwes }, net)
        assertEquals(3, Wallet.payableTo(owed, me, seller).size)

        // under a low pilot limit, the next paid session is refused BEFORE any transport
        val tight = SettlementPolicy.Policy(creditLimitCentimes = net - 1, goodHistoryBonusCentimes = 0)
        val blocked = SettlementPolicy.admit(false, Coverage.Kind.COMMERCIAL, net, 5_000, 0, 0, true, tight)
        assertEquals(SettlementPolicy.Decision.REQUIRE_SETTLEMENT, blocked.decision)
        assertFalse(blocked.mayStart)
        // and a free source still works, whatever is owed
        assertTrue(SettlementPolicy.admit(true, Coverage.Kind.COMMERCIAL, net, 5_000, 0, 0, false, tight).mayStart)
    }

    @Test
    fun a_free_session_and_an_unsigned_one_owe_nothing() {
        val link = Link(budget = 5_000, rate = 300)
        val free = Market.Contract(ByteArray(8) { 9 }, link.buyer.id, link.seller.id, 0, 0, 0, fee, now,
            Market.PRICING_VERSION_BUDGET, 0, 0, 0, 0, 1, 1)
        val wire = Tunnel.signed(free.encode(), link.buyer.sign(Market.contractSignData(free)))
        assertNull(link.onAnswer(link.propose(wire), free))
        assertTrue(link.sessionStart())
        assertEquals(0L, link.stop(5_000_000, 20_000_000))
        assertNull("free Internet creates no obligation", Settlement.fromSession(free, link.settledCheckpoint, now))

        // and a session where the buyer vanished before signing anything
        val gone = Link(budget = 5_000, rate = 300)
        assertNull(gone.agree())
        assertEquals(0L, gone.sellerStop(900_000, 1_100_000, linkUp = false))
        assertNull("nobody may be billed for usage they never signed for",
            Settlement.fromSession(gone.settledContract!!, gone.settledCheckpoint, now))
    }

}
