package net.prok.proknet.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.19.0: the prices and words Prok Market shows come from the one file the server also
 * reads, and the readers turn the Brain's answers into rows without inventing anything.
 */
class MarketViewTest {

    private fun fixture(name: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, "server/tests/fixtures/" + name)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("server/tests/fixtures/" + name + " not found from " + File(".").absolutePath)
    }

    private fun fixtureLines(): List<List<String>> = fixture("market_prices.txt").readLines(Charsets.UTF_8)
        .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.map { it.split("|") }

    // ================= the shared table =================

    @Test fun every_price_equals_the_fixture_the_server_wrote() {
        val expected = LinkedHashMap<String, MarketView.Price>()
        for (p in fixtureLines()) {
            if (p[0] == "INVOICE_STATE" || p[0] == "LISTING_STATE") continue
            val extras = (if (p.size > 3) p[3] else "").split(";").filter { it.isNotEmpty() }.associate { kv -> val (k, v) = kv.split("=", limit = 2); k to v }
            expected[p[0]] = MarketView.Price(p[1].toLong(), p[2].toInt(), extras)
        }
        assertEquals(expected.keys.toList(), MarketView.PRICES.keys.toList())
        for ((k, e) in expected) {
            val got = MarketView.PRICES.getValue(k)
            assertEquals(k + " centimes", e.centimes, got.centimes)
            assertEquals(k + " days", e.durationDays, got.durationDays)
            assertEquals(k + " extras", e.extras, got.extras)
        }
        // and the launch numbers of §10.3, in words
        assertEquals(10_000L, MarketView.PRICES.getValue("POST").centimes)
        assertEquals(30, MarketView.PRICES.getValue("POST").durationDays)
        assertEquals(5, MarketView.PRICES.getValue("POST").extra("photos"))
        assertEquals(20_000L, MarketView.PRICES.getValue("BOOST").centimes)
        assertEquals(7, MarketView.PRICES.getValue("BOOST").durationDays)
        assertEquals(40_000L, MarketView.PRICES.getValue("PACKAGE5").centimes)
        assertEquals(90, MarketView.PRICES.getValue("PACKAGE5").durationDays)
        assertEquals(5, MarketView.PRICES.getValue("PACKAGE5").extra("slots"))
        assertEquals(200_000L, MarketView.PRICES.getValue("STOREFRONT").centimes)
        assertEquals(30, MarketView.PRICES.getValue("STOREFRONT").extra("slots"))
        assertEquals(100, MarketView.PRICES.getValue("VOUCHER").extra("limit"))
        assertEquals(10_000, MarketView.PRICES.getValue("VOUCHER").extra("face_value"))
    }

    @Test fun the_state_texts_are_exactly_the_fixture() {
        val inv = fixtureLines().filter { it[0] == "INVOICE_STATE" }.associate { it[1] to it[2] }
        val lst = fixtureLines().filter { it[0] == "LISTING_STATE" }.associate { it[1] to it[2] }
        assertEquals(inv, MarketView.INVOICE_TEXT)
        assertEquals(lst, MarketView.LISTING_TEXT)
        assertEquals("En attente de confirmation", MarketView.invoiceText("OPEN"))
        assertEquals("Payé", MarketView.invoiceText("PAID"))
        assertEquals("Expiré", MarketView.invoiceText("EXPIRED"))
        assertEquals("Remboursement en cours", MarketView.invoiceText("REFUND_REQUESTED"))
        assertEquals("Remboursé", MarketView.invoiceText("REFUNDED"))
        assertEquals("En attente de modération", MarketView.listingText("AWAITING_REVIEW"))
        assertEquals("Publiée", MarketView.listingText("PUBLISHED"))
        // an unknown state is never shown as paid or published
        assertEquals(MarketView.UNKNOWN_TEXT, MarketView.invoiceText("SETTLED_BY_MAGIC"))
        assertEquals(MarketView.UNKNOWN_TEXT, MarketView.listingText("LIVE_FOREVER"))
        for ((s, t) in MarketView.INVOICE_TEXT) if (s != "PAID") assertFalse(s, t == "Payé")
        for ((s, t) in MarketView.LISTING_TEXT) if (s != "PUBLISHED") assertFalse(s, t == "Publiée")
    }

    // ================= words =================

    @Test fun the_pay_instruction_is_word_for_word_the_servers() {
        // this string is what server/brain/market.py Market.pay_instruction(10000, "PK-7F3A2B", "MTN") returns
        assertEquals("Payer 100 F : envoyez exactement 100 F au numéro Prok MTN MoMo avec la référence PK-7F3A2B dans le motif.",
            MarketView.payInstruction(10_000L, "PK-7F3A2B", "MTN"))
        assertEquals("Payer 200 F : envoyez exactement 200 F au numéro Prok avec la référence PK-000001 dans le motif.",
            MarketView.payInstruction(20_000L, "PK-000001"))
        // with the treasury number known, it is named
        val withNumber = MarketView.payInstruction(40_000L, "PK-ABCDEF", "AIRTEL", "05 512 34 56")
        assertTrue(withNumber, withNumber.startsWith("Payer 400 F : envoyez exactement 400 F au numéro 05 512 34 56 Airtel Money avec la référence PK-ABCDEF"))
    }

    @Test fun prices_are_shown_in_whole_francs_with_thousands_separated() {
        assertEquals("100 F", MarketView.fcfa(10_000L))
        assertEquals("2 500 F", MarketView.fcfa(250_000L))
        assertEquals("1 250 000 F", MarketView.fcfa(125_000_000L))
        assertEquals("0 F", MarketView.fcfa(0L))
        assertEquals("centimes are never shown on a listing", "0 F", MarketView.fcfa(99L))
        assertEquals("-100 F", MarketView.fcfa(-10_000L))
        assertTrue(MarketView.offerLine("POST").endsWith("100 F"))
        assertTrue(MarketView.offerLine("STOREFRONT").contains("30 annonces"))
        assertTrue(MarketView.offerLine("VOUCHER").endsWith("0 F"))
    }

    @Test fun the_safety_guide_says_the_four_things() {
        val g = MarketView.safetyGuideText()
        assertTrue(g.contains("lieu public"))
        assertTrue(g.contains("Vérifiez l'article"))
        assertTrue(g.contains("directement au vendeur"))
        assertTrue(g.contains("ne garde jamais l'argent"))
        assertEquals(MarketView.SAFETY_GUIDE.size, g.lines().size)
    }

    // ================= reading the Brain =================

    private val listingJson = """{"id": "L1", "seller_id": "aaaa", "title": "Vélo VTT 26 \"pouces\"", "description": "Bon état.\nFreins neufs.", "category": "OTHER",
        "price_centimes": 2500000, "condition": "GOOD", "city": "Brazzaville", "neighbourhood": "Bacongo", "approx_lat": 4.28, "approx_lon": 15.26,
        "pickup_options": "Marché Total", "photos": [1, 2], "photo_urls": ["/v1/market/photo?listing=L1&n=1", "/v1/market/photo?listing=L1&n=2"],
        "state": "PUBLISHED", "state_text": "Publiée", "created_at": 1700000000000, "published_at": 1700003600000, "expires_at": 1702595600000,
        "boosted": true, "boost_zone": "Bacongo", "boosted_until": 1700608400000, "distance_km": 1.4,
        "seller": {"published_count": 3, "member_since": 1690000000000, "verified": true, "trusted": false, "storefront": false}}"""

    @Test fun a_listing_is_read_with_escapes_arrays_and_the_seller_history() {
        val l = MarketView.parseListing(listingJson)!!
        assertEquals("L1", l.id)
        assertEquals("Vélo VTT 26 \"pouces\"", l.title)
        assertEquals("Bon état.\nFreins neufs.", l.description)
        assertEquals("Marché Total", l.pickupOptions)
        assertEquals(2_500_000L, l.priceCentimes)
        assertEquals("25 000 F", l.priceText)      // 2 500 000 centimes
        assertEquals(listOf("/v1/market/photo?listing=L1&n=1", "/v1/market/photo?listing=L1&n=2"), l.photoUrls)
        assertEquals("PUBLISHED", l.state)
        assertEquals("Publiée", l.stateText)
        assertEquals("1.4", l.distanceKm)
        assertEquals(3, l.sellerPublished)
        assertEquals(1690000000000L, l.sellerSince)
        assertTrue(l.sellerVerified)
        assertEquals(29L, l.daysLeft(1700003600000L + 86_400_000L))
        assertFalse("already boosted", l.canBoost)
        assertTrue(l.canRenew && l.canWithdraw && l.canEdit)
    }

    @Test fun a_boosted_row_is_labelled_sponsorise_and_an_organic_one_is_not() {
        val boosted = MarketView.parseListing(listingJson)!!
        assertTrue(boosted.boosted)
        assertEquals("Sponsorisé · Vélo VTT 26 \"pouces\"", boosted.rowTitle)
        val organic = MarketView.parseListing(listingJson.replace("\"boosted\": true", "\"boosted\": false"))!!
        assertFalse(organic.boosted)
        assertEquals("Vélo VTT 26 \"pouces\"", organic.rowTitle)
        assertFalse(organic.rowTitle.contains(MarketView.SPONSORED_LABEL))
        assertTrue(organic.canBoost)
    }

    @Test fun a_search_page_keeps_every_item_even_with_nested_arrays_inside() {
        val second = listingJson.replace("\"id\": \"L1\"", "\"id\": \"L2\"").replace("\"boosted\": true", "\"boosted\": false").replace("Vélo", "Frigo")
        val page = MarketView.parseSearch("""{"items": [$listingJson, $second], "total": 2, "offset": 0, "limit": 20, "categories": ["PHONES", "OTHER"]}""")!!
        assertEquals(2, page.total)
        assertEquals(listOf("L1", "L2"), page.items.map { it.id })
        assertTrue(page.items[0].boosted)
        assertFalse(page.items[1].boosted)
        assertTrue(page.items[1].title.startsWith("Frigo"))
        assertEquals(0, MarketView.parseSearch("""{"items": [], "total": 0}""")!!.items.size)
        assertNull(MarketView.parseSearch("""{"error": "nope"}"""))
    }

    @Test fun an_invoice_is_read_and_its_line_never_promises() {
        val json = """{"ok": true, "listing": {"id": "L1", "title": "x", "state": "AWAITING_PAYMENT", "photos": [], "photo_urls": [], "boosted": false},
            "invoice": {"id": "I1", "reference": "PK-7F3A2B", "service": "POST", "listing_id": "L1", "amount": 10000, "rail": "MTN", "zone": "",
            "state": "OPEN", "text": "En attente de confirmation", "created_at": 1700000000000, "expires_at": 1700086400000, "paid_at": 0,
            "instruction": "Payer 100 F : envoyez exactement 100 F au numéro Prok MTN MoMo avec la référence PK-7F3A2B dans le motif."}, "paid_with": "INVOICE"}"""
        val inv = MarketView.invoiceOf(json)!!
        assertEquals("PK-7F3A2B", inv.reference)
        assertEquals(10_000L, inv.amountCentimes)
        assertTrue(inv.open)
        assertEquals(MarketView.payInstruction(10_000L, "PK-7F3A2B", "MTN"), inv.instruction)
        assertEquals("En attente de confirmation · 100 F · réf. PK-7F3A2B · expire dans 23 h", MarketView.invoiceLine(inv, 1700000000000L + 3_600_000L))
        val listing = MarketView.listingOf(json)!!
        assertEquals("L1", listing.id)
        assertEquals("En attente de paiement", listing.stateText)
        assertTrue(listing.needsPayment)
        val paid = MarketView.parseInvoice(json.substringAfter("\"invoice\": ").replace("\"state\": \"OPEN\"", "\"state\": \"PAID\""))!!
        assertTrue(paid.paid)
        assertEquals("Payé · 100 F · réf. PK-7F3A2B", MarketView.invoiceLine(paid, 0L))
        assertNull("a package or voucher answer has no invoice", MarketView.invoiceOf("""{"ok": true, "listing": {"id": "L1"}}"""))
    }

    @Test fun seller_status_thread_and_inbox_are_read() {
        val s = MarketView.parseSellerStatus("""{"registered": true, "verified": true, "trusted": false, "adult_attested": true, "blocked": false, "has_phone": true,
            "member_since": 1, "voucher_available": false, "packages": [{"id": "P1", "kind": "PACKAGE5", "slots_total": 5, "slots_used": 2, "slots_free": 3, "valid_until": 9, "catalog": false}],
            "slots_free": 3, "storefront": false, "prices": {"version": 1, "effective_from": 0, "prices": {"POST": {"centimes": 10000}}}, "published_count": 4}""")!!
        assertTrue(s.verified); assertFalse(s.voucherAvailable)
        assertEquals(3, s.slotsFree)
        assertEquals("Forfait : 3 publications restantes sur 5", s.packages[0].line)
        assertEquals(4, s.publishedCount)
        val t = MarketView.parseThread("""{"listing_id": "L1", "other": "bbbb", "blocked": false, "messages": [
            {"id": 1, "from_id": "bbbb", "to_id": "aaaa", "body": "Bonjour", "offer_centimes": 0, "at": 5, "read": true},
            {"id": 2, "from_id": "aaaa", "to_id": "bbbb", "body": "Je propose", "offer_centimes": 2000000, "at": 6, "read": false}]}""")!!
        assertEquals(2, t.messages.size)
        assertEquals("Bonjour", t.messages[0].line("aaaa"))
        assertEquals("Moi : Offre 20 000 F - Je propose", t.messages[1].line("aaaa"))
        val inbox = MarketView.parseInbox("""{"threads": [{"listing_id": "L1", "title": "Vélo", "other": "bbbb", "last_at": 6, "unread": 1, "last_body": "Je propose"}]}""")
        assertEquals(1, inbox.size)
        assertEquals("Vélo", inbox[0].title)
        assertEquals(1, inbox[0].unread)
    }

    @Test fun rubbish_is_not_a_listing() {
        assertNull(MarketView.parseListing(""))
        assertNull(MarketView.parseListing("""{"error": "not public"}"""))
        assertNotNull(MarketView.parseListing("""{"id": "x", "title": "t", "photos": [], "photo_urls": [], "state": "DRAFT"}"""))
    }
}
