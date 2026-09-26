package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.15.0: the obligation wallet and the credit limit.
 *
 * The wallet never shows a balance, because Prok holds nobody's money. It shows what is
 * owed and what has actually been paid, and "paid" means verified.
 */
class WalletTest {
    private val now = 1_700_000_000_000L
    private val me = "aa".repeat(16)
    private val them = "bb".repeat(16)
    private val other = "cc".repeat(16)

    private var n = 0
    private fun ob(buyer: String, seller: String, gross: Long, status: Settlement.Status = Settlement.Status.PENDING,
                   at: Long = now): Settlement.Obligation {
        val split = Market.split(gross, 5)
        n++
        return Settlement.Obligation("id" + n, "sess" + n, buyer, seller, "cp" + n,
            split.gross, split.sellerNet, split.fee, at, at + Settlement.DEFAULT_TTL_MS, status)
    }

    // ================= what the wallet says =================

    @Test
    fun the_wallet_shows_obligations_and_never_a_balance() {
        val list = listOf(
            ob(me, them, 1_200),                                        // I owe
            ob(me, other, 300, Settlement.Status.CONFIRMED),            // I paid today
            ob(them, me, 3_700),                                        // owed to me
            ob(other, me, 800, Settlement.Status.CONFIRMED),            // received today
        )
        val v = Wallet.view(list, me, now, directPay = true)
        assertEquals(1_200, v.toPayCentimes)
        assertEquals(300, v.paidTodayCentimes)
        assertEquals(Market.split(3_700, 5).sellerNet, v.toReceiveCentimes)
        assertEquals(Market.split(800, 5).sellerNet, v.receivedTodayCentimes)
        assertTrue(v.owesSomething && v.isOwedSomething)
        assertEquals(2, v.pendingCount)
        assertEquals(0, v.disputedCount)
        // what a seller earned today includes what has not been paid yet, and says so
        assertEquals(Market.split(3_700, 5).sellerNet + Market.split(800, 5).sellerNet, v.earnedTodayCentimes)
    }

    @Test
    fun somebody_elses_obligations_are_not_mine() {
        val v = Wallet.view(listOf(ob(them, other, 5_000), ob(other, them, 900)), me, now, directPay = true)
        assertEquals(0, v.toPayCentimes)
        assertEquals(0, v.toReceiveCentimes)
        assertEquals(0, v.pendingCount)
    }

    @Test
    fun a_disputed_obligation_is_counted_apart_and_never_billed() {
        val v = Wallet.view(listOf(ob(me, them, 4_000, Settlement.Status.DISPUTED), ob(me, them, 500)), me, now, directPay = true)
        assertEquals("a disputed figure must not be added to what I owe", 500, v.toPayCentimes)
        assertEquals(1, v.disputedCount)
    }

    @Test
    fun failed_and_expired_obligations_stop_being_owed() {
        val v = Wallet.view(listOf(
            ob(me, them, 900, Settlement.Status.FAILED),
            ob(me, them, 700, Settlement.Status.EXPIRED),
            ob(me, them, 400)), me, now, directPay = true)
        assertEquals(400, v.toPayCentimes)
    }

    // ================= netting =================

    @Test
    fun small_sessions_with_one_seller_add_up_into_one_payment() {
        // nobody should authorise a Mobile Money payment for 3 CFA
        val list = listOf(ob(me, them, 300), ob(me, them, 700), ob(me, them, 500), ob(me, other, 900))
        assertEquals(1_500, Wallet.netOwedTo(list, me, them))
        assertEquals(900, Wallet.netOwedTo(list, me, other))
        assertEquals(2_400, Wallet.totalOwed(list, me))
        assertEquals("one payment clears all three", 3, Wallet.payableTo(list, me, them).size)
        // once one is confirmed it drops out of the net
        val paid = list.mapIndexed { i, o -> if (i == 0) o.with(status = Settlement.Status.CONFIRMED) else o }
        assertEquals(1_200, Wallet.netOwedTo(paid, me, them))
    }

    // ================= the credit limit =================

    @Test
    fun a_small_debt_does_not_interrupt_one_tap_internet() {
        val v = SettlementPolicy.admit(free = false, costClass = Coverage.Kind.COMMERCIAL,
            outstandingCentimes = 1_200, sessionEstimateCentimes = 5_000,
            disputedCount = 0, confirmedPayments = 0, railAvailable = true)
        assertEquals(SettlementPolicy.Decision.ALLOW_SESSION, v.decision)
        assertTrue(v.mayStart)
        // but the app may quietly mention it
        assertFalse(SettlementPolicy.shouldOfferToSettle(1_200))
        assertTrue(SettlementPolicy.shouldOfferToSettle(3_000))
        assertTrue(SettlementPolicy.reminderSentence(3_000).contains("à payer"))
    }

    @Test
    fun use_use_use_never_pay_stops_at_the_limit() {
        val v = SettlementPolicy.admit(false, Coverage.Kind.COMMERCIAL,
            outstandingCentimes = SettlementPolicy.DEFAULT_CREDIT_LIMIT_CENTIMES,
            sessionEstimateCentimes = 5_000, disputedCount = 0, confirmedPayments = 0, railAvailable = true)
        assertEquals(SettlementPolicy.Decision.REQUIRE_SETTLEMENT, v.decision)
        assertFalse("and it must stop BEFORE any Bluetooth setup", v.mayStart)
        assertEquals(SettlementPolicy.DEFAULT_CREDIT_LIMIT_CENTIMES, v.mustSettleCentimes)
        assertTrue(SettlementPolicy.settleSentence(v.mustSettleCentimes).contains("Réglez"))
        // a buyer who has actually paid before is trusted a little further
        val trusted = SettlementPolicy.admit(false, Coverage.Kind.COMMERCIAL,
            SettlementPolicy.DEFAULT_CREDIT_LIMIT_CENTIMES, 5_000, 0, confirmedPayments = 3, railAvailable = true)
        assertEquals(SettlementPolicy.Decision.ALLOW_SESSION, trusted.decision)
    }

    @Test
    fun free_internet_is_never_gated_by_money() {
        val v = SettlementPolicy.admit(free = true, costClass = Coverage.Kind.COMMERCIAL,
            outstandingCentimes = 1_000_000, sessionEstimateCentimes = 5_000,
            disputedCount = 5, confirmedPayments = 0, railAvailable = false)
        assertEquals(SettlementPolicy.Decision.FREE_SESSION_ALLOWED, v.decision)
        assertTrue(v.mayStart)
    }

    @Test
    fun a_sponsored_session_runs_under_its_own_payer_and_creates_no_buyer_debt() {
        for (k in listOf(Coverage.Kind.SPONSORED, Coverage.Kind.GROWTH_SUBSIDY)) {
            val v = SettlementPolicy.admit(false, k, outstandingCentimes = 999_999,
                sessionEstimateCentimes = 5_000, disputedCount = 0, confirmedPayments = 0, railAvailable = false)
            assertEquals(SettlementPolicy.Decision.SPONSORED_ALLOWED, v.decision)
            assertTrue(v.mayStart)
        }
    }

    @Test
    fun a_disputed_session_or_no_way_to_pay_blocks_rather_than_asks() {
        val disputed = SettlementPolicy.admit(false, Coverage.Kind.COMMERCIAL, 100, 5_000,
            disputedCount = 1, confirmedPayments = 0, railAvailable = true)
        assertEquals(SettlementPolicy.Decision.BLOCK_PAID_SESSION, disputed.decision)
        assertFalse(disputed.mayStart)
        // over the limit with nothing to pay with: asking them to settle would be useless
        val noRail = SettlementPolicy.admit(false, Coverage.Kind.COMMERCIAL, 99_999, 5_000, 0, 0, railAvailable = false)
        assertEquals(SettlementPolicy.Decision.BLOCK_PAID_SESSION, noRail.decision)
    }

    // ================= what the seller is told =================

    @Test
    fun a_seller_is_never_told_money_is_guaranteed() {
        for (s in Settlement.Status.values()) {
            val line = Wallet.sellerAssurance(s, 5_000)
            assertFalse("Prok holds no funds, so nothing may claim a guarantee: " + line,
                line.lowercase().contains("garanti"))
            assertTrue(line.isNotEmpty())
        }
        assertTrue(Wallet.sellerAssurance(Settlement.Status.PENDING, 5_000).contains("en attente"))
        assertTrue(Wallet.sellerAssurance(Settlement.Status.PENDING, 5_000).contains("crédit"))
        assertEquals("Reçu ✓", Wallet.sellerAssurance(Settlement.Status.CONFIRMED, 5_000))
    }

    @Test
    fun the_money_words_never_mention_megabytes_or_centimes() {
        val lines = listOf(
            Wallet.buyerSessionLine(1_234), Wallet.buyerSessionLine(0),
            Wallet.sellerSessionLine(1_100), Wallet.sellerSessionLine(0),
            Wallet.toPayLine(4_500), Wallet.toReceiveLine(900),
            Wallet.historyLine(ob(me, them, 1_300, Settlement.Status.CONFIRMED), me),
            Wallet.historyLine(ob(them, me, 1_300), me),
            SettlementPolicy.settleSentence(2_000), SettlementPolicy.reminderSentence(2_000))
        for (l in lines) {
            assertFalse(l + " must not mention megabytes", l.contains("Mo") || l.contains("MB"))
            assertFalse(l + " must not mention centimes", l.lowercase().contains("centime"))
            assertFalse(l + " must not call itself a balance", l.lowercase().contains("solde"))
        }
        assertTrue(Wallet.buyerSessionLine(0) == "Gratuit")
        assertTrue(Wallet.historyLine(ob(them, me, 1_300), me).startsWith("Internet partagé"))
        assertTrue(Wallet.historyLine(ob(me, them, 1_300, Settlement.Status.CONFIRMED), me).contains("Payé"))
    }

    @Test
    fun today_is_measured_from_the_start_of_the_local_day() {
        val start = Wallet.startOfDay(now)
        assertTrue(start <= now)
        assertTrue(now - start < 24L * 3600 * 1000)
        // something confirmed yesterday is not in today's figures
        val v = Wallet.view(listOf(ob(me, them, 900, Settlement.Status.CONFIRMED, at = start - 1)), me, now, directPay = true)
        assertEquals(0, v.paidTodayCentimes)
        val today = Wallet.view(listOf(ob(me, them, 900, Settlement.Status.CONFIRMED, at = start + 1)), me, now, directPay = true)
        assertEquals(900, today.paidTodayCentimes)
    }
}
