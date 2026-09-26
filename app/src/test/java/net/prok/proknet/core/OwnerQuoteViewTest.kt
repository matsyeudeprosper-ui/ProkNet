package net.prok.proknet.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.19.0: the owner's quote against the SHARED fixture the server writes
 * (server/tests/fixtures/quote_examples.json, by tests/write_quote_fixture.py). The contract's
 * §10 numbers - 0.25 FCFA per decimal MB, 80/10/10 direct, 64/20/10/6 with one relay - are
 * computed by both halves from the same examples, so a drift on either side fails here.
 */
class OwnerQuoteViewTest {

    private fun fixture(name: String): String {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, "server/tests/fixtures/" + name)
            if (f.isFile) return f.readText(Charsets.UTF_8)
            dir = dir.parentFile
        }
        throw AssertionError("server/tests/fixtures/" + name + " not found from " + File(".").absolutePath)
    }

    @Test
    fun examplesMatchTheServerFixtureToTheCentime() {
        val fx = fixture("quote_examples.json")
        val rate = LedgerView.num(fx, "rate_centimes_per_mb")
        assertEquals(25L, rate)                                  // 0.25 FCFA per decimal MB
        assertEquals(1_000_000L, LedgerView.num(fx, "mb")); assertEquals(1_000_000L, OwnerQuoteView.MB)
        val direct = LedgerView.obj(fx, "split_direct")!!; val relay = LedgerView.obj(fx, "split_one_relay")!!
        val examples = LedgerView.objects(fx, "examples")
        assertTrue(examples.size >= 4)
        for (e in examples) {
            val bytes = LedgerView.num(e, "bytes")
            val total = OwnerQuoteView.totalFor(rate, bytes)
            assertEquals(BrainPayload.field(e, "label"), LedgerView.num(e, "customer_total"), total)
            assertEquals(LedgerView.num(e, "fcfa"), total / 100)
            assertEquals(LedgerView.num(e, "direct_source"), OwnerQuoteView.share(total, LedgerView.num(direct, "source").toInt()))
            assertEquals(LedgerView.num(e, "direct_platform"), OwnerQuoteView.share(total, LedgerView.num(direct, "platform").toInt()))
            assertEquals(LedgerView.num(e, "direct_reserve"), OwnerQuoteView.share(total, LedgerView.num(direct, "reserve").toInt()))
            assertEquals(LedgerView.num(e, "relay_source"), OwnerQuoteView.share(total, LedgerView.num(relay, "source").toInt()))
            assertEquals(LedgerView.num(e, "relay_relay"), OwnerQuoteView.share(total, LedgerView.num(relay, "relay").toInt()))
            assertEquals(LedgerView.num(e, "relay_platform"), OwnerQuoteView.share(total, LedgerView.num(relay, "platform").toInt()))
            assertEquals(LedgerView.num(e, "relay_reserve"), OwnerQuoteView.share(total, LedgerView.num(relay, "reserve").toInt()))
            // the split is complete: nothing falls between the accounts
            assertEquals(total, LedgerView.num(e, "direct_source") + LedgerView.num(e, "direct_platform") + LedgerView.num(e, "direct_reserve"))
            assertEquals(total, LedgerView.num(e, "relay_source") + LedgerView.num(e, "relay_relay") + LedgerView.num(e, "relay_platform") + LedgerView.num(e, "relay_reserve"))
        }
    }

    private val paid = """{"quote_id":"q1","signature":"abcd","config_version":"2026-09-26","source_kind":"APPROVED_PAID","label":"Wi-Fi maison","paying_party":"CUSTOMER",
        "rate_centimes_per_mb":25,"allowance_bytes":100000000,"gross_total":2500,"customer_total":2500,"sponsor_total":0,"all_in_total":2500,
        "source_share":2000,"relay_share":0,"platform_share":250,"reserve_share":250,"movement_fee":0,"sponsor_campaign_id":"","issued_at":1000,"expires_at":901000,
        "per_100mb_customer":2500,"per_100mb_source":2000,"per_100mb_relay":0,"per_100mb_platform":250,"per_100mb_reserve":250,
        "examples":[{"label":"20 Mo","example_bytes":20000000,"example_total":500},{"label":"1 Go","example_bytes":1000000000,"example_total":25000}],
        "comparison":{"verdict":"CHEAPER","bundle_label":"Forfait 1 Go","bundle_price_centimes":30000,"prok_total_centimes":25000,"as_of":"2026-09-26","prorated":false,"sentence":"Pour 1 Go : ProkNet 250 CFA, forfait 300 CFA (26/09/2026)."},
        "nobody_uses_it":""}"""
    private val free = paid.replace("\"paying_party\":\"CUSTOMER\"", "\"paying_party\":\"NONE\"").replace("\"source_kind\":\"APPROVED_PAID\"", "\"source_kind\":\"FREE\"")
    private val sponsored = paid.replace("\"paying_party\":\"CUSTOMER\"", "\"paying_party\":\"SPONSOR\"").replace("\"customer_total\":2500", "\"customer_total\":0")
        .replace("\"sponsor_total\":0", "\"sponsor_total\":2500").replace("\"sponsor_campaign_id\":\"\"", "\"sponsor_campaign_id\":\"camp1\"")

    @Test
    fun theOwnerReadsPriceShareRelayAndProkInOneSentence() {
        val q = OwnerQuoteView.parse(paid)!!
        assertEquals("q1", q.quoteId); assertFalse(q.free); assertFalse(q.sponsored); assertFalse(q.relayPresent)
        val h = q.headline()
        assertTrue(h, h.contains("le client paie 25.00 CFA pour 100 Mo"))
        assertTrue(h, h.contains("vous gagnez 20.00 CFA")); assertTrue(h, h.contains("Prok 5.00 CFA"))
        assertTrue(q.examplesLine().contains("1 Go = 250.00 CFA"))
        assertTrue(q.splitLine(), q.splitLine().contains("Sur 100 Mo") && q.splitLine().contains("total client : 25.00 CFA"))
        assertEquals("", q.movementLine())
        assertTrue(q.expiryLine(2000).startsWith("Offre valable encore 15 min"))
        assertTrue(q.expiryLine(1_000_000).startsWith("Offre expirée")); assertTrue(q.expired(901000)); assertFalse(q.expired(900999))
        assertTrue(q.verdictLine().contains("forfait 300 CFA"))
        assertTrue(q.nobodyLine().contains("rien n'est facturé"))
        assertEquals(2, q.examples.size); assertEquals(25000L, q.examples[1].totalCentimes)
    }

    @Test
    fun freeAndSponsoredQuotesSayWhoPays() {
        val f = OwnerQuoteView.parse(free)!!
        assertTrue(f.free); assertTrue(f.headline().startsWith("Gratuit")); assertEquals("", f.splitLine()); assertEquals("", f.expiryLine(0))
        val s = OwnerQuoteView.parse(sponsored)!!
        assertTrue(s.sponsored); assertTrue(s.headline(), s.headline().contains("le client ne paie rien (sponsorisé)"))
        assertTrue(s.examplesLine().contains("0.00 CFA (sponsorisé)")); assertTrue(s.splitLine().contains("payé par le sponsor : 25.00 CFA"))
        assertNull(OwnerQuoteView.parse("{\"error\":\"switch off\"}")); assertNull(OwnerQuoteView.parse(""))
    }

    @Test
    fun theDecisionCarriesTheSignedQuoteAndNothingTyped() {
        val q = OwnerQuoteView.parse(paid)!!
        val a = OwnerQuoteView.decide(q, accept = true)
        val pairs = a.pairs().toMap()
        assertEquals("q1", pairs["quote_id"]); assertEquals("abcd", pairs["signature"]); assertEquals("ACCEPT", pairs["decision"].toString()); assertEquals("OWNER", pairs["role"])
        assertEquals("DECLINE", OwnerQuoteView.decide(q, accept = false).pairs().toMap()["decision"].toString())
        assertFalse(pairs.keys.any { it.contains("rate") || it.contains("price") || it.contains("tarif") })
    }

    @Test
    fun theChecksBlockInTheRightOrder() {
        val none = OwnerQuoteView.Checks(false, "none", false, false, null)
        assertFalse(none.canRequestQuote); assertTrue(none.blockingReason().startsWith("Connectez"))
        val notValidated = OwnerQuoteView.Checks(true, "Wi-Fi, not validated", false, true, OwnerQuoteView.Choice.EARN)
        assertTrue(notValidated.blockingReason().contains("non validé"))
        val notAttested = OwnerQuoteView.Checks(true, "Wi-Fi, validated", true, false, OwnerQuoteView.Choice.EARN)
        assertTrue(notAttested.blockingReason().contains("attestation"))
        val noChoice = OwnerQuoteView.Checks(true, "Wi-Fi, validated", true, true, null)
        assertTrue(noChoice.blockingReason().contains("Choisissez"))
        val ok = OwnerQuoteView.Checks(true, "Wi-Fi, validated", true, true, OwnerQuoteView.Choice.SPONSORED)
        assertTrue(ok.canRequestQuote); assertEquals("", ok.blockingReason())
        assertEquals("FREE", OwnerQuoteView.sourceKindFor(OwnerQuoteView.Choice.FREE)); assertEquals("APPROVED_PAID", OwnerQuoteView.sourceKindFor(OwnerQuoteView.Choice.EARN))
        assertTrue(ok.checksLines().contains("aucune promesse de vitesse"))
        assertTrue(OwnerQuoteView.ATTESTATION_SENTENCE.contains("fournisseur"))
    }
}
