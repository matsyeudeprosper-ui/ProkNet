package net.prok.proknet.core

import net.prok.proknet.core.ProductState.Buyer
import net.prok.proknet.core.ProductState.Seller
import net.prok.proknet.core.ProductState.active
import net.prok.proknet.core.ProductState.busy
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
        assertEquals("Démarrage d'Internet…", ProductState.buyerTitle(Buyer.SECURING))
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
        val build = "Impossible de se connecter à ce fournisseur. Réessayez."
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
        // v0.9.5: each Android hotspot error names the ONE thing the provider must change
        val loc = ProductState.lostHint("the provider could not start its Wi-Fi hotspot [Location services are off on the provider]")
        assertTrue(loc, loc.contains("localisation"))
        val teth = ProductState.lostHint("the provider could not start its Wi-Fi hotspot [reason 2 (incompatible mode: the Android hotspot / tethering is already active)]")
        assertTrue(teth, teth.contains("partage de connexion"))
        val chan = ProductState.lostHint("the provider could not start its Wi-Fi hotspot [reason 1 (no channel: disconnect Wi-Fi or use mobile data)]")
        assertTrue(chan, chan.contains("donn\u00e9es mobiles"))
        val perm = ProductState.lostHint("the provider could not start its Wi-Fi hotspot [the provider is missing the Nearby devices / Location permission]")
        assertTrue(perm, perm.contains("proximit\u00e9"))
        for (w in listOf(noAnswer, noHotspot, notJoined, busy, loc, teth, chan, perm)) assertFalse(w, w.contains("Rapprochez"))
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

    // ---- v0.9.17: a new purchase starts from a clean screen -----------------------------------------

    @Test
    fun a_purchase_is_judged_by_the_transport_it_actually_uses() {
        // the phone run: the hotspot transport still held this from an attempt minutes earlier,
        // and the screen said "Connexion perdue" the instant SE CONNECTER was pressed
        val stale = "DOWN (initiator with prok-24e480e6) - could not reach the host (10.168.138.1)"
        assertEquals(ProductState.Buyer.LOST,
            ProductState.buyer(wanted = true, wifiPhase = stale, wifiUp = false, tunnel = "DISCONNECTED", vpnUp = false, lastError = ""))

        // a Wi-Fi Direct purchase reads the Wi-Fi Direct link instead, and starts by searching
        val finding = P2pPlan.buyPhase(P2pPlan.Stage.DISCOVERING, groupFormed = false, planeUsable = false, linked = false)
        assertEquals("FINDING", finding)
        assertEquals(ProductState.Buyer.FINDING, ProductState.buyer(true, finding, false, "DISCONNECTED", false, ""))

        // joined the group, then a usable data plane: connecting, not lost
        val joined = P2pPlan.buyPhase(P2pPlan.Stage.CLIENT, groupFormed = true, planeUsable = false, linked = false)
        assertEquals(ProductState.Buyer.CONNECTING, ProductState.buyer(true, joined, false, "DISCONNECTED", false, ""))
        val usable = P2pPlan.buyPhase(P2pPlan.Stage.CLIENT, groupFormed = true, planeUsable = true, linked = false)
        assertEquals(ProductState.Buyer.CONNECTING, ProductState.buyer(true, usable, false, "DISCONNECTED", false, ""))

        // a real failure of THIS attempt still says so
        val failed = P2pPlan.buyPhase(P2pPlan.Stage.FAILED, groupFormed = false, planeUsable = false, linked = false)
        assertEquals(ProductState.Buyer.LOST, ProductState.buyer(true, failed, false, "DISCONNECTED", false, ""))

        // and the signed link is the next step, never a loss
        val linked = P2pPlan.buyPhase(P2pPlan.Stage.CLIENT, groupFormed = true, planeUsable = true, linked = true)
        assertEquals("AUTH", linked)
        assertEquals(ProductState.Buyer.SECURING, ProductState.buyer(true, linked, true, "DISCONNECTED", false, ""))
    }

    // ---- v0.11: the proven two-phone Bluetooth path, in the consumer's four words ------------------------

    @Test
    fun the_bluetooth_purchase_reads_connecting_checking_starting_connected() {
        // BulkPlan.buyPhase words for REQUESTED / CONNECTING / AUTH / UP, exactly as the node reports them
        assertEquals(Buyer.FINDING, ProductState.buyer(true, "FINDING", false, "DISCONNECTED", false, ""))
        assertEquals(Buyer.CONNECTING, ProductState.buyer(true, "JOINING the Bluetooth channel", false, "DISCONNECTED", false, ""))
        assertEquals(Buyer.CONNECTING, ProductState.buyer(true, "AUTH", false, "DISCONNECTED", false, ""))
        // link authenticated, the quick check running
        assertEquals(Buyer.CHECKING, ProductState.buyer(true, "AUTH", true, "DISCONNECTED", false, "", checking = true))
        // check passed, the contract is being agreed, then the tunnel, then the VPN
        assertEquals(Buyer.SECURING, ProductState.buyer(true, "AUTH", true, "AGREEING", false, "", checking = false))
        assertEquals(Buyer.STARTING, ProductState.buyer(true, "AUTH", true, "CONNECTING", false, ""))
        assertEquals(Buyer.STARTING, ProductState.buyer(true, "AUTH", true, "TUNNEL UP", false, ""))
        assertEquals(Buyer.ONLINE, ProductState.buyer(true, "AUTH", true, "TUNNEL UP", true, ""))
        assertEquals(Buyer.ONLINE, ProductState.buyer(true, "AUTH", true, "INTERNET OK", true, ""))
        // the user reads four things and nothing technical
        assertEquals("Connexion\u2026", ProductState.buyerTitle(Buyer.CONNECTING))
        assertEquals("V\u00e9rification de la connexion\u2026", ProductState.buyerTitle(Buyer.CHECKING))
        assertEquals("D\u00e9marrage d'Internet\u2026", ProductState.buyerTitle(Buyer.SECURING))
        assertEquals("D\u00e9marrage d'Internet\u2026", ProductState.buyerTitle(Buyer.STARTING))
        for (b in Buyer.values()) {
            val t = ProductState.buyerTitle(b) + ProductState.buyerHint(b, "AUTH", false, "")
            for (w in listOf("L2CAP", "PSM", "BULK", "GATT", "probe", "256")) assertFalse(w, t.contains(w))
        }
        assertTrue(Buyer.CHECKING.busy && Buyer.CHECKING.active)
        // the VPN step: Android asks once, the hint says what to press
        assertTrue(ProductState.buyerHint(Buyer.STARTING, "AUTH", vpnConsentPending = true).contains("OK"))
        assertEquals("", ProductState.buyerHint(Buyer.STARTING, "AUTH", vpnConsentPending = false))
    }

    @Test
    fun technical_failures_become_one_plain_sentence() {
        assertEquals("La connexion \u00e0 proximit\u00e9 est trop faible. Rapprochez les t\u00e9l\u00e9phones et r\u00e9essayez.",
            ProductState.lostHint(BulkPlan.PROBE_FAIL_REASON + " (PARTIAL, buyer -> seller timed out)"))
        assertEquals("Le Bluetooth est \u00e9teint. Activez-le pour vous connecter.", ProductState.lostHint("Bluetooth is off"))
        assertEquals("Le fournisseur a perdu son Internet.", ProductState.lostHint("seller lost its upstream"))
        assertEquals("Impossible de se connecter \u00e0 ce fournisseur. R\u00e9essayez.", ProductState.lostHint("something nobody mapped"))
        assertTrue(ProductState.lostHint("Bluetooth bulk: the Bluetooth channel could not be connected").contains("Bluetooth"))
        // and the LOST state carries the sentence
        assertEquals(Buyer.LOST, ProductState.buyer(false, "DOWN (x)", false, "DISCONNECTED", false, BulkPlan.PROBE_FAIL_REASON))
    }

    @Test
    fun the_consumer_never_chooses_a_transport() {
        // a Bluetooth-capable provider on home Wi-Fi: CONNECT does not demand Wi-Fi on the customer
        assertFalse(ProductState.buyerNeedsWifi(offerBulkBt = true, offerUpstreamType = Tunnel.UP_WIFI))
        // a mobile-data provider: the hotspot path, which needs Wi-Fi on the customer
        assertTrue(ProductState.buyerNeedsWifi(offerBulkBt = true, offerUpstreamType = Tunnel.UP_CELLULAR))
        assertTrue(ProductState.buyerNeedsWifi(offerBulkBt = false, offerUpstreamType = Tunnel.UP_WIFI))
        // the provider's hotspot warnings (Wi-Fi on, Location on, network refused) only for the hotspot path
        assertTrue(ProductState.sellerNeedsHotspotWarnings(BulkPlan.SellerAccessPath.HOTSPOT))
        assertFalse(ProductState.sellerNeedsHotspotWarnings(BulkPlan.SellerAccessPath.BLUETOOTH_BULK))
        assertFalse(ProductState.sellerNeedsHotspotWarnings(BulkPlan.SellerAccessPath.NONE))
        // the source line: the SSID, a tick, no protocol
        assertEquals("Source : Wi-Fi (Freebox) \u2705", ProductState.sellerSourceLine(BulkPlan.SellerAccessPath.BLUETOOTH_BULK, Tunnel.UP_WIFI, "Freebox"))
        assertEquals("Source : Donn\u00e9es mobiles \u2705", ProductState.sellerSourceLine(BulkPlan.SellerAccessPath.HOTSPOT, Tunnel.UP_CELLULAR, null))
        val none = ProductState.sellerSourceLine(BulkPlan.SellerAccessPath.NONE, Tunnel.UP_WIFI, "Freebox")
        assertTrue(none.contains("Bluetooth"))
        for (s in listOf(none, ProductState.sellerSourceLine(BulkPlan.SellerAccessPath.BLUETOOTH_BULK, Tunnel.UP_WIFI, "x")))
            for (w in listOf("L2CAP", "PSM", "bulk", "GATT")) assertFalse(w, s.contains(w))
    }
}
