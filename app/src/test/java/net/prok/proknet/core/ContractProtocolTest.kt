package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.14.1: the message path that failed on the phones.
 *
 * v0.14.0's unit tests proved `Contract.encode`/`decode` and never touched the
 * envelope, so a valid 84-byte budget contract was rejected as "malformed
 * proposal" by `Tunnel.parseSigned(f.data, Market.Contract.LEN)` — after the two
 * phones had already built a Bluetooth link, run the signed handshake and passed
 * a 512 KB probe both ways. These tests walk the real functions production uses:
 * build, sign, frame, parse, decode, verify, admit, accept.
 */
class ContractProtocolTest {
    private val now = 1_700_000_000_000L

    private class Party {
        private val kp = Crypto.generateKeyPair()
        val pub: ByteArray = Crypto.publicBytes(kp.public)
        val id: ByteArray = Crypto.deriveId(pub)
        fun sign(d: ByteArray): ByteArray = Crypto.sign(kp.private, d)
    }

    private val buyer = Party()
    private val seller = Party()

    private fun budgetContract(
        budget: Long = 5_000, rate: Int = 300, bytes: Long = -1, fee: Int = 5, ts: Long = now, policy: Int = 1,
    ): Market.Contract {
        val ceiling = if (bytes >= 0) bytes else Pricing.bytesForBudget(budget, rate)
        return Market.Contract(ByteArray(8) { 7 }, buyer.id, seller.id, Pricing.advertisedPriceCfa(rate), 0,
            minOf(Market.MAX_MB_PER_SESSION.toLong(), (ceiling + Market.MB - 1) / Market.MB).toInt(), fee, ts,
            Market.PRICING_VERSION_BUDGET, rate, budget, ceiling, 0, policy, 1)
    }

    private fun v1Contract(price: Int = 5, maxMb: Int = 10): Market.Contract =
        Market.Contract(ByteArray(8) { 7 }, buyer.id, seller.id, price, 0, maxMb, 5, now)

    /** Exactly what the buyer puts on the wire. */
    private fun envelope(c: Market.Contract): ByteArray = Tunnel.signed(c.encode(), buyer.sign(Market.contractSignData(c)))

    /**
     * The seller's decision, through the same function `Gateway.onProposal` calls.
     * Not an imitation of it: `Market.admitProposal` IS the production boundary.
     */
    private fun admit(data: ByteArray?, floor: Int = 0, used: Set<String> = emptySet(), at: Long = now): Market.Admission {
        val c = Market.decodeSignedContract(data)?.contract
        return Market.admitProposal(data, seller.id, buyer.id, buyer.pub,
            c?.pricePerMb ?: 0, 0, c?.maxMb ?: 0, 5, at, used, floor)
    }

    private fun sellerAccepts(data: ByteArray?, floor: Int = 0, used: Set<String> = emptySet(), at: Long = now): String? =
        admit(data, floor, used, at).reason

    // ================= the regression =================

    @Test
    fun the_v0_14_0_regression_a_budget_contract_now_survives_the_envelope() {
        val c = budgetContract()
        val wire = envelope(c)
        // the exact framing the old parser could not read
        assertEquals(Market.Contract.LEN_V2, c.encode().size)
        assertTrue("a budget contract is longer than a v1 one", c.encode().size > Market.Contract.LEN)
        assertEquals(Market.Framing.OK, Market.framingOf(wire))
        // the old call: parsing at the v1 length is exactly how v0.14.0 threw this away
        assertNull("this is the v0.14.0 bug, reproduced", Tunnel.parseSigned(wire, Market.Contract.LEN))
        // the new path reads it, and the signature verifies over exactly those bytes
        val sb = Market.decodeSignedContract(wire)!!
        assertTrue(sb.contract.sameTermsAs(c))
        assertTrue(Crypto.verify(buyer.pub, Market.contractSignData(sb.contract), sb.sig))
        val a = Market.admitProposal(wire, seller.id, buyer.id, buyer.pub, c.pricePerMb, 0, c.maxMb, 5, now, emptySet(), 0)
        assertTrue("and the seller accepts it: " + a.reason, a.ok)
        assertEquals(Market.PRICING_VERSION_BUDGET, a.declaredVersion)
        assertTrue(a.decoded && a.signatureOk)
        assertTrue(a.contract!!.budgetSession)
    }

    @Test
    fun a_v1_proposal_is_still_accepted_exactly_as_before() {
        val c = v1Contract()
        val wire = envelope(c)
        assertEquals(Market.Contract.LEN, c.encode().size)
        assertEquals(Market.Framing.OK, Market.framingOf(wire))
        val sb = Market.decodeSignedContract(wire)!!
        assertEquals(Market.PRICING_VERSION, sb.contract.version)
        assertFalse(sb.contract.budgetSession)
        assertTrue(sb.contract.sameTermsAs(c))
        // the old parser and the new one agree on a v1 body, byte for byte
        val old = Tunnel.parseSigned(wire, Market.Contract.LEN)!!
        assertTrue(old.body.contentEquals(sb.contract.encode()))
        assertTrue(old.sig.contentEquals(sb.sig))
        assertNull(sellerAccepts(wire))
        // and it still bills the way it always did
        assertEquals(Market.sessionCost(3 * Market.MB, 5, 0), sb.contract.costFor(3 * Market.MB))
    }

    @Test
    fun the_whole_round_trip_ends_with_both_signatures_over_the_same_bytes() {
        val c = budgetContract()
        val wire = envelope(c)
        val sb = Market.decodeSignedContract(wire)!!
        assertNull(sellerAccepts(wire))
        // the seller answers with the HASH, which is version-independent and fixed at 32 bytes
        val sellerSig = seller.sign(Market.contractSignData(sb.contract))
        val accept = Tunnel.signed(sb.contract.hash(), sellerSig)
        val back = Tunnel.parseSigned(accept, 32)!!
        assertTrue("the buyer recognises its own contract", back.body.contentEquals(c.hash()))
        assertTrue("and the seller signed the same bytes the buyer did",
            Crypto.verify(seller.pub, Market.contractSignData(c), back.sig))
        // SESSION_START carries that same hash
        val start = Tunnel.sessionStart(buyer.id, c.hash())
        assertTrue(Tunnel.parseSessionStart(start)!!.contractHash.contentEquals(c.hash()))
    }

    // ================= malformed inputs =================

    @Test
    fun no_contract_may_be_parsed_with_another_versions_layout() {
        // a v1-length body claiming version 2: v0.14.0 would have decoded this as a budget
        // contract full of zeros. The length must match the declared version.
        val fake = v1Contract().encode().copyOf()
        fake[0] = Market.PRICING_VERSION_BUDGET.toByte()
        assertNull("a 62-byte body claiming v2 is not a budget contract", Market.Contract.decode(fake))
        assertTrue("and no envelope around it can be read either",
            Market.framingOf(Tunnel.signed(fake, ByteArray(64) { 1 })) != Market.Framing.OK)
        // a v2-length body claiming version 1
        val fake2 = budgetContract().encode().copyOf()
        fake2[0] = Market.PRICING_VERSION.toByte()
        assertNull("an 84-byte body claiming v1 is not a legacy contract", Market.Contract.decode(fake2))
        // an unsupported future version is refused cleanly, not guessed at
        val future = budgetContract().encode().copyOf()
        future[0] = 9
        assertEquals(-1, Market.Contract.bodyLenFor(9))
        assertNull(Market.Contract.decode(future))
        assertEquals(Market.Framing.UNSUPPORTED_VERSION, Market.framingOf(Tunnel.signed(future, ByteArray(64) { 1 })))
    }

    @Test
    fun truncated_padded_and_unsigned_envelopes_are_all_refused() {
        val wire = envelope(budgetContract())
        assertEquals(Market.Framing.EMPTY, Market.framingOf(null))
        assertEquals(Market.Framing.EMPTY, Market.framingOf(ByteArray(0)))
        // one byte short, and one byte long
        assertFalse(Market.framingOf(wire.copyOf(wire.size - 1)) == Market.Framing.OK)
        assertEquals(Market.Framing.LENGTH_MISMATCH, Market.framingOf(wire + byteArrayOf(0)))
        assertNull(Market.decodeSignedContract(wire + byteArrayOf(0)))
        // truncated inside the body
        assertEquals(Market.Framing.TRUNCATED, Market.framingOf(wire.copyOf(20)))
        // a zero-length signature: an unsigned contract is not a contract
        val noSig = budgetContract().encode() + byteArrayOf(0, 0)
        assertEquals(Market.Framing.NO_SIGNATURE, Market.framingOf(noSig))
        assertNull(Market.decodeSignedContract(noSig))
        // a wrong signature passes framing and fails verification, which is where it should fail
        val badSig = Tunnel.signed(budgetContract().encode(), ByteArray(70) { 3 })
        assertEquals(Market.Framing.OK, Market.framingOf(badSig))
        assertEquals("bad buyer signature", sellerAccepts(badSig))
        // and the seller can say exactly where it stopped, which is what COPY NETWORK shows
        val a = admit(badSig)
        assertTrue("it got far enough to decode", a.decoded)
        assertFalse("and stopped at the signature", a.signatureOk)
        assertEquals(Market.PRICING_VERSION_BUDGET, a.declaredVersion)
    }

    @Test
    fun corrupting_any_economic_field_breaks_the_signature() {
        val c = budgetContract()
        val good = envelope(c)
        assertNull(sellerAccepts(good))
        // flip the rate, the budget and the byte ceiling in turn: each is signed, so each must fail
        for (variant in listOf(
            budgetContract(rate = 301), budgetContract(budget = 9_000),
            budgetContract(bytes = Pricing.bytesForBudget(5_000, 300) * 2))) {
            val forged = Tunnel.signed(variant.encode(), buyer.sign(Market.contractSignData(c)))
            assertEquals("a corrupted field must not verify", "bad buyer signature", sellerAccepts(forged))
        }
        // a budget contract whose ceiling costs more than the budget is refused even when honestly signed
        val over = budgetContract(bytes = Pricing.bytesForBudget(5_000, 300) * 3)
        assertTrue("costFor hides this, which is why the check uses uncappedCostFor",
            over.costFor(over.maxBillableBytes) <= over.buyerBudgetCentimes)
        assertTrue(over.uncappedCostFor(over.maxBillableBytes) > over.buyerBudgetCentimes)
        assertEquals("budget ceiling invalid", sellerAccepts(envelope(over)))
    }

    // ================= economics at acceptance =================

    @Test
    fun the_seller_judges_the_floor_it_has_now_not_the_one_it_advertised() {
        // buyer signs 2 CFA/MB, the seller's floor is 3 CFA/MB: refused
        val cheap = envelope(budgetContract(rate = 200))
        assertEquals("seller floor not met", sellerAccepts(cheap, floor = 300))
        // the same contract when the floor really is lower: accepted
        assertNull(sellerAccepts(cheap, floor = 150))
        // the source changed under the seller (home Wi-Fi -> mobile data) while the buyer was setting up
        val homeFloor = Pricing.sellerFloorPerMb(Pricing.Source(Pricing.SourceKind.AUTHORIZED_HOME_WIFI, 0), Pricing.SellerPolicy.BALANCED)
        val mobileFloor = Pricing.sellerFloorPerMb(Pricing.Source(Pricing.SourceKind.MOBILE_DATA, 500), Pricing.SellerPolicy.BALANCED)
        assertTrue("mobile data must cost more to share", mobileFloor > homeFloor)
        val atHomePrice = envelope(budgetContract(rate = Pricing.rateForFloor(homeFloor)))
        assertNull("fine while it was still on Wi-Fi", sellerAccepts(atHomePrice, floor = homeFloor))
        assertEquals("but refused once it is paying for mobile data", "seller floor not met", sellerAccepts(atHomePrice, floor = mobileFloor))
    }

    @Test
    fun a_stale_or_replayed_proposal_is_refused() {
        val c = budgetContract(ts = now - 20 * 60_000)
        assertEquals("start time too far from now", sellerAccepts(envelope(c), at = now))
        // a session id already used cannot be replayed
        val fresh = budgetContract()
        assertNull(sellerAccepts(envelope(fresh)))
        assertEquals("session id already used", sellerAccepts(envelope(fresh), used = setOf(fresh.sessionHex)))
    }

    @Test
    fun a_free_session_is_a_coherent_contract_not_a_malformed_one() {
        // rate 0, no budget: legitimate, and it must survive the whole path
        val free = Market.Contract(ByteArray(8) { 7 }, buyer.id, seller.id, 0, 0, 0, 5, now,
            Market.PRICING_VERSION_BUDGET, 0, 0, 0, 0, 1, 1)
        assertTrue("free must be expressible", free.valid())
        val wire = envelope(free)
        assertEquals(Market.Framing.OK, Market.framingOf(wire))
        assertNull("a free session is not 'no budget ceiling'", sellerAccepts(wire, floor = 500))
        assertEquals(0L, free.costFor(500L * Market.MB))
        assertEquals(0L, free.costFor(Long.MAX_VALUE))
    }

    // ================= the ceiling is absolute =================

    @Test
    fun no_byte_count_can_push_a_session_past_its_signed_budget() {
        val c = budgetContract(budget = 5_000, rate = 300)
        val ceiling = c.maxBytes
        assertTrue(ceiling > 0)
        // exactly at, one under, one over, absurdly over
        assertTrue(c.costFor(ceiling) <= 5_000)
        assertTrue(c.costFor(ceiling - 1) <= c.costFor(ceiling))
        assertEquals(c.costFor(ceiling), c.costFor(ceiling + 1))
        assertEquals(c.costFor(ceiling), c.costFor(Long.MAX_VALUE))
        // a hostile byte count must not overflow into a negative or absurd charge
        for (b in listOf(Long.MAX_VALUE, Long.MAX_VALUE / 2, Market.MAX_BILLABLE_BYTES * 1000, -1L)) {
            val cost = c.costFor(b)
            assertTrue("charge " + cost + " for " + b, cost in 0..5_000)
        }
        // the checkpoint the seller issues obeys the same rule
        val cp = Market.nextCheckpoint(c, 0, Long.MAX_VALUE / 4, Long.MAX_VALUE / 4, now, true)
        assertTrue("a checkpoint can never exceed the budget", cp.costCentimes <= 5_000)
        assertTrue(cp.costCentimes >= 0)
    }

    @Test
    fun the_money_helpers_survive_their_maximum_values() {
        // nothing in the pricing engine may overflow at the largest values the contract allows
        val maxCharge = Pricing.chargeFor(Market.MAX_BILLABLE_BYTES, Market.MAX_PRICE_PER_MB)
        assertTrue(maxCharge > 0)
        assertEquals(maxCharge, Pricing.chargeFor(Long.MAX_VALUE, Market.MAX_PRICE_PER_MB))
        assertTrue(Pricing.bytesForBudget(Long.MAX_VALUE, 1) in 0..Market.MAX_BILLABLE_BYTES)
        assertEquals(0L, Pricing.bytesForBudget(-5, 300))
        assertEquals(0L, Pricing.chargeFor(-5, 300))
        // and a quote built from an absurd budget is clamped rather than wrapped
        val q = Pricing.quote(Long.MAX_VALUE, Pricing.Source(Pricing.SourceKind.AUTHORIZED_HOME_WIFI, 0))
        assertTrue(q.budgetCentimes in 0..Market.MAX_BUDGET_CENTIMES)
        assertTrue(q.maxBillableBytes in 0..Market.MAX_BILLABLE_BYTES)
        assertTrue(q.expectedGross >= 0 && q.expectedSellerProfit >= 0)
        // a contract cannot even be valid with a budget beyond the bound
        assertFalse(budgetContract(budget = Market.MAX_BUDGET_CENTIMES + 1).valid())
    }

    @Test
    fun three_budget_sessions_in_a_row_each_start_clean() {
        // the v0.13.3 standard, now with v2 contracts: nothing from one session blocks the next
        val used = HashSet<String>()
        for (i in 1..3) {
            val c = Market.Contract(ByteArray(8) { i.toByte() }, buyer.id, seller.id, 3, 0, 16, 5, now + i * 1000,
                Market.PRICING_VERSION_BUDGET, 300, 5_000, Pricing.bytesForBudget(5_000, 300), 0, 1, 1)
            val wire = envelope(c)
            assertEquals("session " + i + " must be readable", Market.Framing.OK, Market.framingOf(wire))
            assertNull("session " + i + " must be accepted", sellerAccepts(wire, used = used, at = now + i * 1000))
            // the buyer used a little and stopped: it pays for what it used, never the budget
            val spent = c.costFor(2 * Market.MB)
            assertTrue(spent > 0 && spent < 5_000)
            used.add(c.sessionHex)
        }
        assertEquals(3, used.size)
    }
    // ================= what the contract does NOT say =================

    @Test
    fun the_contract_does_not_sign_who_pays_and_nothing_pretends_it_does() {
        // A sponsored quote and an ordinary one produce the same signed bytes. This is a
        // real limit of contract version 2, pinned here so no screen or document may
        // claim a sponsored session is cryptographically agreed. Signing it needs a v3.
        val sponsored = Pricing.quote(0, Pricing.Source(Pricing.SourceKind.AUTHORIZED_HOME_WIFI, 0),
            costClass = Coverage.Kind.SPONSORED, sponsorBudgetCentimes = 5_000)
        assertTrue(sponsored.admissible)
        assertEquals(Pricing.Payer.SPONSOR, sponsored.payer)
        val asContract = Market.Contract(ByteArray(8) { 7 }, buyer.id, seller.id,
            Pricing.advertisedPriceCfa(sponsored.rateCentimesPerMb), 0,
            minOf(Market.MAX_MB_PER_SESSION.toLong(), (sponsored.maxBillableBytes + Market.MB - 1) / Market.MB).toInt(),
            5, now, Market.PRICING_VERSION_BUDGET, sponsored.rateCentimesPerMb, 5_000,
            sponsored.maxBillableBytes, sponsored.sourceCostPerMb, 1, 1)
        val plain = Market.Contract(ByteArray(8) { 7 }, buyer.id, seller.id, asContract.pricePerMb, 0,
            asContract.maxMb, 5, now, Market.PRICING_VERSION_BUDGET, asContract.rateCentimesPerMb,
            asContract.buyerBudgetCentimes, asContract.maxBillableBytes, asContract.sourceCostBasisCentimesPerMb, 1, 1)
        assertTrue("the wire cannot tell a sponsored session from a normal one", asContract.sameTermsAs(plain))
    }

    @Test
    fun the_signed_bytes_are_the_same_everywhere_they_are_used() {
        // encode, hash, sameTermsAs, the signature input and SESSION_START must all
        // agree, or a contract could be agreed in one place and denied in another
        val c = budgetContract()
        val again = Market.Contract.decode(c.encode())!!
        assertTrue(c.sameTermsAs(again))
        assertTrue(c.hash().contentEquals(again.hash()))
        assertTrue(Market.contractSignData(c).contentEquals(Market.contractSignData(again)))
        assertTrue(Market.contractSignData(c).endsWith(c.encode()))
        val wire = envelope(c)
        val fromWire = Market.decodeSignedContract(wire)!!.contract
        assertTrue(fromWire.hash().contentEquals(c.hash()))
        assertTrue(Tunnel.parseSessionStart(Tunnel.sessionStart(buyer.id, fromWire.hash()))!!.contractHash!!.contentEquals(c.hash()))
    }

    private fun ByteArray.endsWith(tail: ByteArray): Boolean =
        size >= tail.size && copyOfRange(size - tail.size, size).contentEquals(tail)

    // ================= the words a buyer reads =================

    @Test
    fun the_consumer_price_words_never_mention_a_megabyte() {
        for (p in listOf(0, 1, 99, 100, 200, 201, 300, 500, 501, 5_000, CoverageModel.PRICE_UNKNOWN)) {
            val w = CoverageModel.priceBandWord(p)
            assertFalse(w + " must not talk about megabytes", w.contains("Mo") || w.contains("MB"))
            assertTrue(w.isNotEmpty())
        }
        assertEquals("Gratuit", CoverageModel.priceBandWord(0))
        // the developer wording is kept on purpose, for Developer and old v1 sessions
        assertTrue(CoverageModel.priceWord(300).contains("par Mo"))
    }

}
