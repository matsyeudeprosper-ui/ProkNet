package net.prok.proknet.core

import net.prok.proknet.core.ProductState.Buyer
import net.prok.proknet.core.ProductState.Seller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Engine states -> user states, the only translation layer the screens use. */
class ProductStateTest {

    @Test
    fun buyer_setup_progresses_through_human_states() {
        assertEquals(Buyer.IDLE, ProductState.buyer(false, "IDLE", false, "DISCONNECTED", false, ""))
        assertEquals(Buyer.FINDING, ProductState.buyer(true, "REQUESTING", false, "DISCONNECTED", false, ""))
        assertEquals(Buyer.FINDING, ProductState.buyer(true, "OFFERED", false, "DISCONNECTED", false, ""))
        assertEquals(Buyer.CONNECTING, ProductState.buyer(true, "JOINING (tap CONNECT in the Android dialog)", false, "DISCONNECTED", false, ""))
        assertEquals(Buyer.CONNECTING, ProductState.buyer(true, "TCP", false, "DISCONNECTED", false, ""))
        assertEquals(Buyer.CONNECTING, ProductState.buyer(true, "AUTH", false, "DISCONNECTED", false, ""))
        assertEquals(Buyer.SECURING, ProductState.buyer(true, "WIFI UP", true, "DISCONNECTED", false, ""))
        assertEquals(Buyer.SECURING, ProductState.buyer(true, "WIFI UP", true, "AGREEING", false, ""))
        assertEquals(Buyer.STARTING, ProductState.buyer(true, "WIFI UP", true, "CONNECTING", false, ""))
        assertEquals(Buyer.STARTING, ProductState.buyer(true, "WIFI UP", true, "TUNNEL UP", false, ""))
        assertEquals(Buyer.ONLINE, ProductState.buyer(true, "WIFI UP", true, "TUNNEL UP", true, ""))
        assertEquals(Buyer.ONLINE, ProductState.buyer(true, "WIFI UP", true, "INTERNET OK", true, ""))
        assertEquals(Buyer.LOST, ProductState.buyer(true, "WIFI UP", true, "INTERNET LOST", true, ""))
        assertEquals(Buyer.LOST, ProductState.buyer(true, "DOWN: timeout", false, "DISCONNECTED", false, ""))
        assertEquals(Buyer.LOST, ProductState.buyer(false, "DOWN: x", false, "DISCONNECTED", false, "Wi-Fi link closed"))
        assertEquals(Buyer.LOST, ProductState.buyer(true, "WIFI UP", true, "DISCONNECTED", false, "seller ended session"))
        assertEquals("You're online", ProductState.buyerTitle(Buyer.ONLINE))
        assertEquals("Securing connection…", ProductState.buyerTitle(Buyer.SECURING))
        assertTrue(with(ProductState) { Buyer.SECURING.busy }); assertFalse(with(ProductState) { Buyer.ONLINE.busy }); assertTrue(with(ProductState) { Buyer.ONLINE.active })
        assertTrue(ProductState.buyerHint(Buyer.CONNECTING, "JOINING (tap CONNECT in the Android dialog)", false).contains("CONNECT"))
        assertTrue(ProductState.buyerHint(Buyer.STARTING, "WIFI UP", true).contains("OK"))
        assertEquals("", ProductState.buyerHint(Buyer.ONLINE, "WIFI UP", false))
    }

    @Test
    fun seller_states_hide_engine_words() {
        assertEquals(Seller.OFF, ProductState.seller(false, "SELL OFF"))
        assertEquals(Seller.NO_INTERNET, ProductState.seller(true, "NO UPSTREAM"))
        assertEquals(Seller.AVAILABLE, ProductState.seller(true, "PROVIDER READY"))
        assertEquals(Seller.SERVING, ProductState.seller(true, "CONTRACT AGREED"))
        assertEquals(Seller.SERVING, ProductState.seller(true, "TUNNEL UP (selling)"))
        assertEquals(Seller.LOST, ProductState.seller(true, "INTERNET LOST"))
        for (s in Seller.values()) { val t = ProductState.sellerTitle(s) + ProductState.sellerHint(s); assertFalse(t, t.contains("upstream", true) || t.contains("gateway", true) || t.contains("provider ready", true)) }
    }

    @Test
    fun words_for_numbers() {
        assertEquals("57 CFA", ProductState.cfaShort(5735)); assertEquals("0.5 CFA", ProductState.cfaShort(50)); assertEquals("0 CFA", ProductState.cfaShort(0)); assertEquals("1 CFA", ProductState.cfaShort(100))
        assertEquals("57.35 CFA", ProductState.cfaExact(5735))
        assertEquals("11.5 MB", ProductState.data(11_470_000)); assertEquals("512 KB", ProductState.data(512_000)); assertEquals("1.20 GB", ProductState.data(1_200_000_000)); assertEquals("300 B", ProductState.data(300))
        assertEquals("45 s", ProductState.duration(45_000)); assertEquals("3 min", ProductState.duration(185_000)); assertEquals("1 h 5 min", ProductState.duration(3_900_000))
        assertEquals("Good signal", ProductState.signalWord(-55)); assertEquals("Weak signal", ProductState.signalWord(-85))
        assertEquals("Mobile data", ProductState.upstreamWord(Tunnel.UP_CELLULAR))
        assertEquals("5 CFA / MB", ProductState.priceLine(5)); assertEquals("Minimum: 0 CFA", ProductState.minimumLine(0)); assertEquals("Limit: Unlimited", ProductState.limitLine(0)); assertEquals("Limit: 200 MB", ProductState.limitLine(200))
    }

    @Test
    fun coverage_words_hide_zone_colours() {
        assertEquals("Internet available", ProductState.coverageWord(Coverage.ZoneStatus.GREEN))
        assertEquals("Internet can be arranged", ProductState.coverageWord(Coverage.ZoneStatus.YELLOW))
        assertEquals("No connection available yet", ProductState.coverageWord(Coverage.ZoneStatus.RED))
        assertEquals(Coverage.ZoneStatus.GREEN, ProductState.coverageNow(1, 0))
        assertEquals(Coverage.ZoneStatus.YELLOW, ProductState.coverageNow(0, 2))
        assertEquals(Coverage.ZoneStatus.RED, ProductState.coverageNow(0, 0))
        for (z in Coverage.ZoneStatus.values()) { val w = ProductState.coverageWord(z); assertFalse(w, w.contains("GREEN") || w.contains("YELLOW") || w.contains("RED") || w.contains("relay")) }
    }

    @Test
    fun wallet_summary_and_payment_words() {
        val me = "prok-aaaa0000"; val other = "prok-bbbb0000"
        val entries = listOf(
            Market.Entry("1", "s1", me, other, 5735, "internet session", 1, Market.ST_PENDING),      // I bought: to pay
            Market.Entry("2", "s2", other, me, 3000, "internet session", 2, Market.ST_PENDING),      // I sold: to receive
            Market.Entry("3", "s2", me, Market.PROK_ID, 150, "network fee 5%", 2, Market.ST_PENDING), // my fee
            Market.Entry("4", "s3", other, me, 9999, "internet session", 3, Market.ST_SETTLED),      // already settled: excluded
        )
        val w = ProductState.wallet(entries, me)
        assertEquals(5735L, w.toPay); assertEquals(3000L, w.toReceive); assertEquals(150L, w.prokFees)
        assertEquals("To pay", ProductState.paymentWord(Market.ST_PENDING, true)); assertEquals("To receive", ProductState.paymentWord(Market.ST_PENDING, false))
        assertEquals("Paid", ProductState.paymentWord(Market.ST_SETTLED, true)); assertEquals("Disputed", ProductState.paymentWord(Market.ST_DISPUTED, false))
    }
}
