package net.prok.proknet.core

import net.prok.proknet.core.ProductState.Buyer
import net.prok.proknet.core.ProductState.Seller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Engine states -> user words (French since v0.9.2), the only translation layer the screens use. */
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
        assertEquals("Vous êtes en ligne", ProductState.buyerTitle(Buyer.ONLINE))
        assertEquals("Sécurisation de la connexion…", ProductState.buyerTitle(Buyer.SECURING))
        for (b in Buyer.values()) { val t = ProductState.buyerTitle(b); assertFalse(t, t.contains("Wi-Fi") || t.contains("VPN") || t.contains("tunnel", true)) }
        assertTrue(with(ProductState) { Buyer.SECURING.busy }); assertFalse(with(ProductState) { Buyer.ONLINE.busy }); assertTrue(with(ProductState) { Buyer.ONLINE.active })
        assertTrue(ProductState.buyerHint(Buyer.CONNECTING, "JOINING (tap CONNECT in the Android dialog)", false).contains("CONNECTER"))
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
    fun the_whole_consumer_wording_is_french() {
        val all = Buyer.values().map { ProductState.buyerTitle(it) } + Seller.values().map { ProductState.sellerTitle(it) } +
            Seller.values().map { ProductState.sellerHint(it) } + Coverage.ZoneStatus.values().map { ProductState.coverageWord(it) } +
            listOf(ProductState.lostHint(""), ProductState.lostHint("no contract answer"), ProductState.lostHint("Wi-Fi link closed"),
                ProductState.paymentWord(Market.ST_PENDING, true), ProductState.paymentWord(Market.ST_SETTLED, false), ProductState.signalWord(-55), ProductState.upstreamWord(1))
        val english = listOf("Not ", "Finding", "Connecting", "online", "lost", "sharing", "Waiting", "Someone", "Turn on", "Available to",
            "Try again", "Move closer", "Paid", "To pay", "To receive", "Good signal", "Mobile data", "available", "arranged")
        for (w in all) for (e in english) assertFalse(w + " still contains \"" + e + "\"", w.contains(e))
        assertTrue(all.none { it.isEmpty() && false })
    }

    @Test
    fun words_for_numbers() {
        // French units and decimal comma, whatever the phone's locale is
        assertEquals("57 CFA", ProductState.cfaShort(5735)); assertEquals("0,5 CFA", ProductState.cfaShort(50)); assertEquals("0 CFA", ProductState.cfaShort(0)); assertEquals("1 CFA", ProductState.cfaShort(100))
        assertEquals("57,35 CFA", ProductState.cfaExact(5735))
        assertEquals("11,5 Mo", ProductState.data(11_470_000)); assertEquals("512 Ko", ProductState.data(512_000)); assertEquals("1,20 Go", ProductState.data(1_200_000_000)); assertEquals("300 o", ProductState.data(300))
        assertEquals("45 s", ProductState.duration(45_000)); assertEquals("3 min", ProductState.duration(185_000)); assertEquals("1 h 5 min", ProductState.duration(3_900_000))
        assertEquals("Bon signal", ProductState.signalWord(-55)); assertEquals("Signal faible", ProductState.signalWord(-85))
        assertEquals("Donn\u00e9es mobiles", ProductState.upstreamWord(Tunnel.UP_CELLULAR))
        assertEquals("5 CFA / Mo", ProductState.priceLine(5)); assertEquals("Minimum : 0 CFA", ProductState.minimumLine(0))
        assertEquals("Limite : illimit\u00e9e", ProductState.limitLine(0)); assertEquals("Limite : 200 Mo", ProductState.limitLine(200))
    }

    @Test
    fun a_protocol_failure_does_not_tell_the_user_to_walk() {
        val build = "Impossible d'\u00e9tablir la connexion. R\u00e9essayez."
        val closer = "Rapprochez-vous du fournisseur et r\u00e9essayez"
        // v0.9.1: the relay never introduced its seller although the link was perfect
        assertEquals(build, ProductState.lostHint("the relay has no Internet seller right now"))
        assertEquals(build, ProductState.lostHint("session refused by the seller"))
        // v0.9.3: "nobody answered" is a different problem from "it broke", and it says what to check
        for (e in listOf("the relay did not answer the introduction request", "no contract answer within 15s", "no SESSION_OK from seller within 15s")) {
            val h = ProductState.lostHint(e)
            assertTrue(e + " -> " + h, h.contains("n'a pas répondu"))
            assertFalse(e + " -> " + h, h.contains("Rapprochez"))
        }
        // v0.9.3: the three real setup failures each say what to check, and none of them says "walk"
        val noAnswer = ProductState.lostHint("the provider did not answer within 60s (its Wi-Fi or Location may be off, or the app is not open)")
        assertTrue(noAnswer, noAnswer.contains("Wi-Fi") && noAnswer.contains("localisation"))
        val busy = ProductState.lostHint(Wire.cancelReasonText(Wire.CANCEL_BUSY))
        assertTrue(busy, busy.contains("occup\u00e9"))
        val noHotspot = ProductState.lostHint(Wire.cancelReasonText(Wire.CANCEL_NO_HOTSPOT))
        assertTrue(noHotspot, noHotspot.contains("point d'acc\u00e8s"))
        val notJoined = ProductState.lostHint("the Wi-Fi network was not joined (the Android dialog was not approved?)")
        assertTrue(notJoined, notJoined.contains("CONNECTER"))
        for (w in listOf(noAnswer, noHotspot, notJoined, busy)) assertFalse(w, w.contains("Rapprochez"))
        // a real radio failure still says what helps
        assertEquals(closer, ProductState.lostHint("Wi-Fi link closed: connection closed"))
        assertEquals(closer, ProductState.buyerHint(Buyer.LOST, "DOWN", false, "Wi-Fi network lost"))
        assertEquals("R\u00e9essayez", ProductState.lostHint(""))
        assertEquals("R\u00e9essayez", ProductState.buyerHint(Buyer.LOST, "IDLE", false))
    }

    @Test
    fun coverage_words_hide_zone_colours() {
        assertEquals("Internet disponible", ProductState.coverageWord(Coverage.ZoneStatus.GREEN))
        assertEquals("Internet peut \u00eatre organis\u00e9", ProductState.coverageWord(Coverage.ZoneStatus.YELLOW))
        assertEquals("Aucune connexion disponible pour l'instant", ProductState.coverageWord(Coverage.ZoneStatus.RED))
        assertEquals(Coverage.ZoneStatus.GREEN, ProductState.coverageNow(1, 0))
        assertEquals(Coverage.ZoneStatus.YELLOW, ProductState.coverageNow(0, 2))
        assertEquals(Coverage.ZoneStatus.RED, ProductState.coverageNow(0, 0))
        for (z in Coverage.ZoneStatus.values()) { val w = ProductState.coverageWord(z); assertFalse(w, w.contains("GREEN") || w.contains("YELLOW") || w.contains("RED") || w.contains("relais")) }
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
        assertEquals("\u00c0 payer", ProductState.paymentWord(Market.ST_PENDING, true)); assertEquals("\u00c0 recevoir", ProductState.paymentWord(Market.ST_PENDING, false))
        assertEquals("Pay\u00e9", ProductState.paymentWord(Market.ST_SETTLED, true)); assertEquals("Contest\u00e9", ProductState.paymentWord(Market.ST_DISPUTED, false))
    }
}
