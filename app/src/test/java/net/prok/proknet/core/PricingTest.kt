package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.14: the buyer chooses a budget in CFA, the seller chooses to earn, and
 * ProkNet does the arithmetic. These tests pin the arithmetic: a commercial
 * session may never exceed the budget, and may never leave the seller out of
 * pocket. Losing money is possible only when someone explicitly pays for it.
 */
class PricingTest {
    private val budget50 = 5_000L          // 50 CFA
    private val home = Pricing.Source(Pricing.SourceKind.AUTHORIZED_HOME_WIFI, declaredCostCentimesPerMb = 0)
    /** 500 CFA for 1 GB = 50 centimes per MB. */
    private val mobile = Pricing.Source(Pricing.SourceKind.MOBILE_DATA, Pricing.mobileCostPerMb(50_000, 1_000))
    private val free = Pricing.Source(Pricing.SourceKind.FREE_PUBLIC)

    // ---- the money identity ------------------------------------------------------------------------

    @Test
    fun a_commercial_quote_never_exceeds_the_budget_and_always_pays_the_seller() {
        for (src in listOf(home, mobile)) for (pol in Pricing.SellerPolicy.values()) for (b in listOf(2_500L, 5_000L, 10_000L)) {
            val q = Pricing.quote(b, src, pol)
            assertTrue(src.kind.name + "/" + pol + "/" + b + ": " + q.reason, q.admissible)
            // the ceiling is real
            assertTrue("charge must fit the budget", q.expectedGross <= b)
            assertTrue("the byte ceiling must cost at most the budget", Pricing.chargeFor(q.maxBillableBytes, q.rateCentimesPerMb) <= b)
            // the seller is never out of pocket
            assertTrue("seller must profit", q.expectedSellerProfit >= 0)
            assertTrue(q.sellerProfitable)
            // and the accounting identity holds exactly
            assertEquals("buyer charge = fee + source cost + seller profit",
                q.expectedGross, q.expectedFee + q.expectedSourceCost + q.expectedSellerProfit)
            assertEquals(q.expectedGross - q.expectedFee, q.expectedSellerNet)
            // nothing is negative
            for (v in listOf(q.expectedGross, q.expectedFee, q.expectedSellerNet, q.expectedSourceCost, q.expectedSellerProfit, q.maxBillableBytes)) assertTrue(v >= 0)
        }
    }

    @Test
    fun the_seller_floor_covers_the_source_cost_its_safety_and_a_real_earning() {
        val p = Pricing.DEFAULT
        // home Wi-Fi costs its owner nothing, so the floor is purely what the seller wants to earn
        assertEquals(0, Pricing.sourceCostPerMb(Pricing.SourceKind.AUTHORIZED_HOME_WIFI, 0))
        assertEquals(p.earning(Pricing.SellerPolicy.BALANCED), Pricing.sellerFloorPerMb(home, Pricing.SellerPolicy.BALANCED))
        // mobile data costs real money, so the floor is higher than the pure earning
        assertEquals(50, mobile.declaredCostCentimesPerMb)
        val mobileFloor = Pricing.sellerFloorPerMb(mobile, Pricing.SellerPolicy.BALANCED)
        assertTrue("the floor must cover the 50c source cost", mobileFloor > 50)
        assertTrue(mobileFloor > Pricing.sellerFloorPerMb(home, Pricing.SellerPolicy.BALANCED))
        // wanting to earn more raises the floor, wanting to be cheap lowers it
        assertTrue(Pricing.sellerFloorPerMb(home, Pricing.SellerPolicy.CHEAPER) < Pricing.sellerFloorPerMb(home, Pricing.SellerPolicy.BALANCED))
        assertTrue(Pricing.sellerFloorPerMb(home, Pricing.SellerPolicy.EARN_MORE) > Pricing.sellerFloorPerMb(home, Pricing.SellerPolicy.BALANCED))
        // and the buyer rate is always at least the floor, because the Prok fee comes out of it
        val q = Pricing.quote(budget50, mobile)
        assertTrue(q.rateCentimesPerMb >= mobileFloor)
        assertEquals(mobileFloor, q.sellerNetPerMb)
    }

    @Test
    fun a_mobile_seller_is_never_pushed_below_its_own_bundle_cost() {
        // "J'ai payé 1 000 CFA pour 2 Go"
        assertEquals(50, Pricing.mobileCostPerMb(100_000, 2_000))
        // an expensive bundle: 1 000 CFA for 200 MB = 5 CFA per MB
        val expensive = Pricing.Source(Pricing.SourceKind.MOBILE_DATA, Pricing.mobileCostPerMb(100_000, 200))
        assertEquals(500, expensive.declaredCostCentimesPerMb)
        val q = Pricing.quote(budget50, expensive)
        assertTrue(q.admissible)
        assertTrue("the rate must exceed the 5 CFA/MB the data cost", q.rateCentimesPerMb > 500)
        assertTrue(q.expectedSellerProfit > 0)
        // a budget that cannot even buy one useful MB is refused rather than sold at a loss
        val tiny = Pricing.quote(300, expensive)      // 3 CFA
        assertFalse(tiny.admissible)
        assertTrue(tiny.reason, tiny.reason.contains("no economically valid offer"))
        assertEquals(0, tiny.maxBillableBytes)
        // and a seller whose bundle cost is unknown is protected by a conservative assumption, never by zero
        val unknown = Pricing.Source(Pricing.SourceKind.MOBILE_DATA, declaredCostCentimesPerMb = -1)
        assertEquals(Pricing.DEFAULT.unknownMobileCostCentimesPerMb, Pricing.sourceCostPerMb(unknown.kind, unknown.declaredCostCentimesPerMb))
        assertTrue(Pricing.quote(budget50, unknown).expectedSellerProfit >= 0)
    }

    @Test
    fun free_internet_stays_free_whatever_the_buyer_was_ready_to_spend() {
        val q = Pricing.quote(budget50, free)
        assertTrue(q.admissible)
        assertTrue(q.free)
        assertEquals("a 50 CFA budget means 'up to 50', never 'take 50'", 0, q.rateCentimesPerMb)
        assertEquals(0L, q.expectedGross)
        assertEquals(0L, q.expectedFee)
        assertTrue("free Internet is not rationed by money", q.maxBillableBytes > 100L * Pricing.MB)
        assertEquals(0L, Pricing.settle(500L * Pricing.MB, q).gross)
        // even a zero budget gets free Internet
        assertTrue(Pricing.quote(0, free).admissible)
    }

    // ---- budgets and bytes -------------------------------------------------------------------------------

    @Test
    fun the_byte_ceiling_follows_the_budget_and_the_rate_the_right_way_round() {
        val small = Pricing.quote(2_500L, home)
        val big = Pricing.quote(10_000L, home)
        assertTrue("a bigger budget buys more", big.maxBillableBytes > small.maxBillableBytes)
        assertEquals("the rate does not depend on the budget", small.rateCentimesPerMb, big.rateCentimesPerMb)
        // a dearer source buys less with the same money
        val cheapSeller = Pricing.quote(budget50, home, Pricing.SellerPolicy.CHEAPER)
        val dearSeller = Pricing.quote(budget50, home, Pricing.SellerPolicy.EARN_MORE)
        assertTrue(dearSeller.rateCentimesPerMb > cheapSeller.rateCentimesPerMb)
        assertTrue("a higher rate must buy fewer bytes", dearSeller.maxBillableBytes < cheapSeller.maxBillableBytes)
        // bytesForBudget is exact and never negative
        assertEquals(0L, Pricing.bytesForBudget(0, 200))
        assertEquals(Pricing.MB, Pricing.bytesForBudget(200, 200))
        assertEquals(5 * Pricing.MB, Pricing.bytesForBudget(1_000, 200))
    }

    @Test
    fun the_buyer_pays_for_what_was_used_not_for_the_budget() {
        val q = Pricing.quote(budget50, home)
        // stopping after 1 MB costs one MB, not 50 CFA
        val early = Pricing.settle(Pricing.MB, q)
        assertEquals(q.rateCentimesPerMb.toLong(), early.gross)
        assertTrue(early.gross < budget50)
        assertEquals(early.gross - early.fee, early.sellerNet)
        assertTrue(early.sellerProfit >= 0)
        // using nothing costs nothing
        assertEquals(0L, Pricing.settle(0, q).gross)
        // and using more than the ceiling is capped at the ceiling, never above the budget
        val over = Pricing.settle(q.maxBillableBytes * 10, q)
        assertEquals(q.maxBillableBytes, over.bytes)
        assertTrue("the budget is a hard ceiling", over.gross <= budget50)
    }

    @Test
    fun money_does_not_drift_over_millions_of_bytes() {
        val q = Pricing.quote(100_000L, home)      // 1 000 CFA
        // charging in one go and charging in a thousand steps must agree to the centime
        var stepped = 0L
        val step = q.maxBillableBytes / 1000
        for (i in 1..1000) stepped = Pricing.chargeFor(step * i, q.rateCentimesPerMb)
        val once = Pricing.chargeFor(step * 1000, q.rateCentimesPerMb)
        assertEquals("cumulative billing is exact, not incremental", once, stepped)
        // and the rounding of a single byte never invents money
        assertEquals(0L, Pricing.chargeFor(1, 1))
        assertEquals(q.rateCentimesPerMb.toLong(), Pricing.chargeFor(Pricing.MB, q.rateCentimesPerMb))
    }

    // ---- who pays -----------------------------------------------------------------------------------------

    @Test
    fun sponsored_and_subsidised_sessions_say_exactly_who_pays() {
        // sponsored: the buyer pays nothing, the sponsor's budget is the ceiling, the seller still earns
        val s = Pricing.quote(0, home, costClass = Coverage.Kind.SPONSORED, sponsorBudgetCentimes = 5_000)
        assertTrue(s.reason, s.admissible)
        assertEquals(Pricing.Payer.SPONSOR, s.payer)
        assertEquals(0L, s.budgetCentimes)
        assertTrue("the seller is still paid", s.expectedSellerNet > 0)
        assertEquals(s.expectedGross, s.subsidyCentimes)
        assertFalse(Pricing.quote(0, home, costClass = Coverage.Kind.SPONSORED, sponsorBudgetCentimes = 0).admissible)

        // growth subsidy: allowed only inside an explicit budget
        val g = Pricing.quote(1_000, home, costClass = Coverage.Kind.GROWTH_SUBSIDY, sponsorBudgetCentimes = 10_000)
        assertTrue(g.admissible)
        assertEquals(Pricing.Payer.PROK, g.payer)
        // a normal commercial session is never silently subsidised
        val c = Pricing.quote(budget50, home)
        assertEquals(Pricing.Payer.BUYER, c.payer)
        assertEquals(0L, c.subsidyCentimes)
        assertEquals(Coverage.Kind.COMMERCIAL, c.costClass)
    }

    @Test
    fun a_source_that_may_not_be_resold_is_never_priced() {
        val q = Pricing.quote(budget50, Pricing.Source(Pricing.SourceKind.UNKNOWN))
        assertFalse(q.admissible)
        assertTrue(q.reason.contains("may not be resold"))
        assertEquals(0L, q.maxBillableBytes)
        assertFalse(Pricing.sellable(Pricing.SourceKind.UNKNOWN))
        assertTrue(Pricing.sellable(Pricing.SourceKind.MOBILE_DATA))
        // and a budget below the minimum session is refused
        assertFalse(Pricing.quote(50, home).admissible)
    }

    // ---- the words a person reads --------------------------------------------------------------------------

    @Test
    fun the_consumer_words_are_cfa_and_never_megabytes() {
        assertEquals("50 CFA", Pricing.cfa(5_000))
        assertEquals("Budget maximum : 50 CFA", Pricing.budgetLine(5_000))
        assertEquals("Vous avez dépensé 34 CFA sur votre budget de 50 CFA", Pricing.spentWord(3_400, 5_000))
        assertEquals("Il vous reste environ 10 CFA", Pricing.remainingWord(4_000, 5_000))
        assertEquals("Votre budget de 50 CFA est utilisé.", Pricing.exhaustedWord(5_000))
        assertEquals("Vous avez gagné 27 CFA", Pricing.earnedWord(2_700))
        assertFalse(Pricing.nearlyExhausted(1_000, 5_000))
        assertTrue(Pricing.nearlyExhausted(4_000, 5_000))
        // never a negative remainder
        assertEquals("Il vous reste environ 0 CFA", Pricing.remainingWord(9_000, 5_000))
        for (p in Pricing.SellerPolicy.values()) assertTrue(Pricing.sellerPolicyWord(p).isNotEmpty())
        // an estimate is shown only when it is real and bounded
        val est = Pricing.earningEstimate(Pricing.quote(budget50, home))
        assertNotNull(est)
        assertTrue(est!!, est.startsWith("Gain estimé : environ"))
        assertNull("nothing to promise on a free session", Pricing.earningEstimate(Pricing.quote(budget50, free)))
        assertNull(Pricing.earningEstimate(Pricing.quote(50, home)))
        // and no consumer sentence mentions megabytes or centimes
        for (s in listOf(Pricing.budgetLine(5_000), Pricing.spentWord(1, 5_000), Pricing.remainingWord(1, 5_000),
            Pricing.exhaustedWord(5_000), Pricing.earnedWord(1), est)) {
            assertFalse(s!!, s.contains("MB") || s.contains("Mo") || s.contains("centime"))
        }
    }

    // ---- the contract carries the ceiling ----------------------------------------------------------------------

    private fun ids(b: Byte) = ByteArray(16) { b }

    @Test
    fun a_budget_contract_signs_the_ceiling_and_bills_at_the_exact_rate() {
        val q = Pricing.quote(budget50, home)
        val c = Market.Contract(ByteArray(8) { 1 }, ids(2), ids(3), q.rateCentimesPerMb / 100, 0,
            (q.maxBillableBytes / Market.MB).toInt(), 5, 1_700_000_000_000L, Market.PRICING_VERSION_BUDGET,
            q.rateCentimesPerMb, q.budgetCentimes, q.maxBillableBytes, q.sourceCostPerMb, Pricing.SellerPolicy.BALANCED.ordinal, 1)
        assertTrue(c.valid())
        assertTrue(c.budgetSession)
        assertEquals(q.maxBillableBytes, c.maxBytes)
        // it bills at the centime rate, not at a rounded CFA price
        assertEquals(q.rateCentimesPerMb.toLong(), c.costFor(Market.MB))
        // and it can never bill more than the signed budget, whatever bytes are claimed
        assertTrue(c.costFor(c.maxBytes) <= budget50)
        assertEquals(c.costFor(c.maxBytes), c.costFor(c.maxBytes * 100))
        // it survives the wire exactly
        val back = Market.Contract.decode(c.encode())!!
        assertEquals(Market.Contract.LEN_V2, c.encode().size)
        assertEquals(q.rateCentimesPerMb, back.rateCentimesPerMb)
        assertEquals(q.budgetCentimes, back.buyerBudgetCentimes)
        assertEquals(q.maxBillableBytes, back.maxBillableBytes)
        assertEquals(Pricing.SellerPolicy.BALANCED.ordinal, back.sellerPolicy)
        assertTrue(back.sameTermsAs(c))
    }

    @Test
    fun old_contracts_still_decode_and_bill_exactly_as_before() {
        val v1 = Market.Contract(ByteArray(8) { 1 }, ids(2), ids(3), 5, 0, 10, 5, 1_700_000_000_000L)
        assertTrue(v1.valid())
        assertFalse(v1.budgetSession)
        assertEquals(Market.Contract.LEN, v1.encode().size)
        assertEquals("a v1 contract bills exactly as it always did",
            Market.sessionCost(3 * Market.MB, 5, 0), v1.costFor(3 * Market.MB))
        assertEquals(10L * Market.MB, v1.maxBytes)
        val back = Market.Contract.decode(v1.encode())!!
        assertEquals(5, back.pricePerMb)
        assertEquals(Market.PRICING_VERSION, back.version)
        // a v2 payload is not mistaken for a v1 one, and rubbish is still refused
        assertNull(Market.Contract.decode(ByteArray(Market.Contract.LEN - 1)))
        assertNull(Market.Contract.decode(ByteArray(Market.Contract.LEN_V2) { 9 }))
    }
}
