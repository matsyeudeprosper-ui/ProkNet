package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * v0.18.0: the direct-to-seller (kiosk) route is OFF in the product. An obligation is
 * evidence the Brain settles from Prok credit; nobody is ever shown "à payer" or asked
 * to pay a person. The old accounting still exists behind the developer switch.
 */
class WalletDirectPayTest {

    private val me = "aa".repeat(16)
    private val seller = "bb".repeat(16)

    private fun obligation(): Settlement.Obligation {
        val session = ByteArray(8) { 1 }
        val c = Market.Contract(session, me.hexToBytes(), seller.hexToBytes(), 3, 0, 10, 5, 1_700_000_000_000L,
            version = Market.PRICING_VERSION_USABLE, rateCentimesPerMb = 300, buyerBudgetCentimes = 5_000,
            maxBillableBytes = 5_000 * Market.MB / 300, pricingMode = 1)
        val cp = Market.nextCheckpoint(c, 0, Market.MB, 2 * Market.MB, 1_700_000_001_000L, true)
        return Settlement.fromSession(c, cp, 1_700_000_002_000L)!!
    }

    @Test fun with_direct_pay_off_nothing_is_owed_to_a_person_but_the_evidence_is_still_pending() {
        val o = obligation()
        val w = Wallet.view(listOf(o), me, 1_700_000_003_000L)          // the product default
        assertEquals(0L, w.toPayCentimes)
        assertEquals(1, w.pendingCount)
        assertFalse(w.owesSomething)
        val ws = Wallet.view(listOf(o), seller, 1_700_000_003_000L)
        assertEquals(0L, ws.toReceiveCentimes)
        val a = WalletUi.primaryAction(listOf(o), me, w, hasReceivingMethod = false, isSeller = false)
        assertNotEquals("nobody is asked to pay a person", WalletUi.ActionKind.PAY, a.kind)
    }

    @Test fun the_developer_switch_restores_the_old_accounting_on_a_test_phone_only() {
        val o = obligation()
        val w = Wallet.view(listOf(o), me, 1_700_000_003_000L, directPay = true)
        assertEquals(o.buyerOwes, w.toPayCentimes)
        val a = WalletUi.primaryAction(listOf(o), me, w, hasReceivingMethod = false, isSeller = false, directPay = true)
        assertEquals(WalletUi.ActionKind.PAY, a.kind)
    }
}
