package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.19.0: the rate on the air, in the offer and on the screen is CENTIMES per MB. The
 * launch target of 0.25 FCFA/MB cannot survive whole-franc rounding (it became 1 FCFA/MB,
 * four times the price, up to v0.18); a v3 advert carries the exact centimes.
 */
class RateTextTest {

    @Test fun a_quarter_franc_is_written_as_a_quarter_franc() {
        assertEquals("0,25 CFA/Mo", Market.rateTextFor(25))
        assertEquals("0,2 CFA/Mo", Market.rateTextFor(20))
        assertEquals("3 CFA/Mo", Market.rateTextFor(300))
        assertEquals("1,05 CFA/Mo", Market.rateTextFor(105))
        assertEquals("gratuit", Market.rateTextFor(0))
    }

    @Test fun an_offer_ranks_and_quotes_on_the_exact_rate_not_the_rounded_franc() {
        val cheap = Market.Offer("aaaaaaaa", 1, Market.FLAG_SELL or Market.FLAG_VALIDATED, -60, 1L, rateCentimesPerMb = 25)
        val dear = Market.Offer("bbbbbbbb", 1, Market.FLAG_SELL or Market.FLAG_VALIDATED, -60, 1L, rateCentimesPerMb = 100)
        assertEquals("both round up to 1 CFA/MB on the old field", cheap.pricePerMb, dear.pricePerMb)
        assertEquals(listOf(cheap, dear), Market.rank(listOf(dear, cheap)))
        assertEquals("0,25 CFA/Mo", cheap.rateText)
        // 200 MB at 0.25 CFA/MB is 50 CFA, to the centime, on the quote the buyer signs
        val q = Pricing.quoteForOffer(5_000, 25)
        assertTrue(q.admissible)
        assertEquals(25, q.rateCentimesPerMb)
        assertEquals(5_000L, q.budgetCentimes)
        assertTrue("50 CFA buys about 200 MB at 0.25", q.maxBillableBytes in 199_000_000L..200_000_000L)
    }

    @Test fun an_old_advert_is_read_as_whole_francs_times_100() {
        // the scanner does this: v2 u16 = CFA -> centimes
        val legacy = Market.Offer("cccccccc", 3, Market.FLAG_SELL, -70, 1L)
        assertEquals(300, legacy.rateCentimesPerMb)
        assertEquals("3 CFA/Mo", legacy.rateText)
    }
}
