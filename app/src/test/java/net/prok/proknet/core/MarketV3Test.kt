package net.prok.proknet.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.18.0: contract version 3 - the usable-Internet rule and the relay in the signature.
 * One charging rule on three sides; the fixture was written by the Brain's Python.
 */
class MarketV3Test {

    private val buyer = ByteArray(16) { 2 }
    private val seller = ByteArray(16) { 3 }
    private val relay = ByteArray(16) { 4 }

    private fun v3(rate: Int = 300, budget: Long = 5_000, relayId: ByteArray = ByteArray(16)) = Market.Contract(
        ByteArray(8) { 1 }, buyer, seller, (rate + 99) / 100, 0, 10, 5, 1_700_000_000_000L,
        version = Market.PRICING_VERSION_USABLE, rateCentimesPerMb = rate, buyerBudgetCentimes = budget,
        maxBillableBytes = if (rate > 0) budget * Market.MB / rate else 0, pricingMode = 1, relayId = relayId)

    private fun v2(rate: Int = 300, budget: Long = 5_000) = Market.Contract(
        ByteArray(8) { 1 }, buyer, seller, (rate + 99) / 100, 0, 10, 5, 1_700_000_000_000L,
        version = Market.PRICING_VERSION_BUDGET, rateCentimesPerMb = rate, buyerBudgetCentimes = budget,
        maxBillableBytes = budget * Market.MB / rate, pricingMode = 1)

    private fun fixture(): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, "server/tests/fixtures/charging_v3.json")
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("server/tests/fixtures/charging_v3.json not found - run: cd server && python -m tests.write_charging_fixture")
    }

    // ================= the rule =================

    @Test fun nothing_down_means_nothing_owed_under_v3_and_everything_from_the_first_byte_down() {
        val c = v3()
        assertEquals(0L, c.costFor(2 * Market.MB, 0))
        assertTrue(c.costFor(2 * Market.MB, 1) > 0)
        assertEquals(c.costFor(2 * Market.MB + 1), c.costFor(2 * Market.MB, 1))
        // and v2 still charges up-only, exactly as before
        assertTrue(v2().costFor(2 * Market.MB, 0) > 0)
    }

    @Test fun every_fixture_case_costs_here_what_python_wrote() {
        val text = fixture().readText(Charsets.UTF_8)
        val cases = Regex("\\{[^{}]*\\}").findAll(text.substring(text.indexOf("\"cases\""))).map { it.value }.toList()
        assertTrue(cases.size >= 10)
        for (case in cases) {
            fun n(k: String) = BrainPayload.field(case, k).toLong()
            val label = BrainPayload.field(case, "label")
            val rate = n("rate").toInt(); val budget = n("budget"); val ceiling = n("ceiling")
            val c = Market.Contract(ByteArray(8) { 1 }, buyer, seller, (rate + 99) / 100, 0, 10, 5, 1_700_000_000_000L,
                version = n("version").toInt(), rateCentimesPerMb = rate, buyerBudgetCentimes = budget,
                maxBillableBytes = ceiling, pricingMode = 1)
            assertEquals(label, n("cost"), c.costFor(n("up"), n("down")))
        }
        assertEquals("the phone's MB must be the Brain's MB", BrainPayload.field(text, "mb").toLong(), Market.MB)
    }

    @Test fun checkpoints_and_settlement_use_the_same_rule() {
        val c = v3()
        val cp = Market.nextCheckpoint(c, 0, 3 * Market.MB, 0, 1_700_000_001_000L, true)
        assertEquals(0L, cp.costCentimes)
        assertNull(Market.validateCheckpoint(cp, c, null, 3 * Market.MB, 0))
        assertEquals(0L, Market.finalCost(c, cp))
        assertNull("a zero session creates no obligation", Settlement.fromSession(c, cp, 1_700_000_002_000L))
        val cp2 = Market.nextCheckpoint(c, 1, 3 * Market.MB, 1, 1_700_000_003_000L, true)
        assertTrue(cp2.costCentimes > 0)
        assertNotNull(Settlement.fromSession(c, cp2, 1_700_000_004_000L))
        // a checkpoint priced by the OLD rule is refused by a v3 buyer
        val wrong = Market.Checkpoint(c.sessionId, 1, 3 * Market.MB, 0, c.costFor(3 * Market.MB), 1_700_000_001_000L, true)
        assertEquals("cost does not match the agreed terms", Market.validateCheckpoint(wrong, c, null, 3 * Market.MB, 0))
    }

    // ================= the wire =================

    @Test fun v3_round_trips_with_its_relay_and_the_length_follows_the_version() {
        val c = v3(relayId = relay)
        val bytes = c.encode()
        assertEquals(Market.Contract.LEN_V3, bytes.size)
        assertEquals(3, bytes[0].toInt())
        val d = Market.Contract.decode(bytes)!!
        assertTrue(d.viaRelay)
        assertTrue(d.relayId.contentEquals(relay))
        assertTrue(d.sameTermsAs(c))
        assertEquals(Market.Contract.LEN_V2, v2().encode().size)
        assertNull("a v3 body at v2 length is not a contract", Market.Contract.decode(bytes.copyOf(Market.Contract.LEN_V2)))
    }

    @Test fun the_relay_may_not_be_a_party() {
        assertFalse(v3(relayId = buyer).valid())
        assertFalse(v3(relayId = seller).valid())
        assertTrue(v3(relayId = relay).valid())
        assertTrue(v3().valid())
        assertFalse(v3().viaRelay)
    }

    // ================= the seller's admission =================

    @Test fun the_relay_in_the_signature_must_be_the_relay_that_carried_it() {
        val now = 1_700_000_000_000L
        val direct = v3()
        val relayed = v3(relayId = relay)
        // direct link: a relay named is refused; none named is fine
        assertNull(Market.acceptableProposal(direct, seller, buyer, 3, 0, 10, 5, now, emptySet(), 0, relayPeerId = null))
        assertEquals("a relay is named on a direct link", Market.acceptableProposal(relayed, seller, buyer, 3, 0, 10, 5, now, emptySet(), 0, relayPeerId = null))
        // via relay: the named relay must be that relay
        assertNull(Market.acceptableProposal(relayed, seller, buyer, 3, 0, 10, 5, now, emptySet(), 0, relayPeerId = relay))
        assertEquals("relay id is not the relay that carried this", Market.acceptableProposal(relayed, seller, buyer, 3, 0, 10, 5, now, emptySet(), 0, relayPeerId = ByteArray(16) { 9 }))
        assertEquals("relay id is not the relay that carried this", Market.acceptableProposal(direct, seller, buyer, 3, 0, 10, 5, now, emptySet(), 0, relayPeerId = relay))
        // an old v2 contract cannot be relayed and paid: the relay would go unpaid
        assertEquals("a relayed paid session needs a version 3 contract", Market.acceptableProposal(v2(), seller, buyer, 3, 0, 10, 5, now, emptySet(), 0, relayPeerId = relay))
    }
}
