package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.18.0: a paid session is admitted on the Brain's answer about the buyer's credit.
 * The Brain answering "no" is final; the Brain not answering is not a "no".
 */
class HoldGateTest {

    private fun contract(rate: Int, budget: Long): Market.Contract {
        val session = ByteArray(8) { 1 }; val buyer = ByteArray(16) { 2 }; val seller = ByteArray(16) { 3 }
        return Market.Contract(session, buyer, seller, (rate + 99) / 100, 0, 10, 5, 1_700_000_000_000L,
            version = Market.PRICING_VERSION_BUDGET, rateCentimesPerMb = rate, buyerBudgetCentimes = budget,
            maxBillableBytes = if (rate > 0) budget * Market.MB / rate else 0, pricingMode = 1)
    }

    @Test fun a_free_session_needs_no_hold() {
        assertFalse(HoldGate.needsHold(contract(0, 0)))
        assertTrue(HoldGate.needsHold(contract(300, 5_000)))
        val d = HoldGate.decide(false, HoldGate.Answer.refused("insufficient_credit", ""))
        assertTrue("a free session is admitted whatever the credit says", d.admit)
    }

    @Test fun granted_admits_and_remembers_the_hold() {
        val d = HoldGate.decide(true, HoldGate.Answer.granted("hold-123456"))
        assertTrue(d.admit)
        assertEquals("hold-123456", d.holdId)
        assertEquals("", d.rejectMessage)
    }

    @Test fun refused_rejects_with_a_sentence_the_buyer_can_act_on() {
        for ((reason, word) in listOf("insufficient_credit" to "rechargez", "hold_exists" to "déjà", "too_many_holds" to "réessayez")) {
            val d = HoldGate.decide(true, HoldGate.Answer.refused(reason, "server words"))
            assertFalse(reason, d.admit)
            assertTrue(reason + ": " + d.rejectMessage, d.rejectMessage.contains(word))
            assertFalse("never a server slug on screen", d.rejectMessage.contains("_"))
        }
        val other = HoldGate.decide(true, HoldGate.Answer.refused("something_new", "Une phrase du Brain."))
        assertFalse(other.admit)
        assertEquals("Une phrase du Brain.", other.rejectMessage)
        val silent = HoldGate.decide(true, HoldGate.Answer.refused("something_new", ""))
        assertTrue(silent.rejectMessage.length > 10)
    }

    @Test fun no_answer_is_a_refusal_with_a_sentence_and_never_a_silent_bypass() {
        // Mike's rule: a paid session needs a CONFIRMED hold. No Brain, no paid session.
        for (a in listOf(HoldGate.Answer.unreachable, HoldGate.Answer.notConfigured)) {
            val d = HoldGate.decide(true, a)
            assertFalse(a.kind.name, d.admit)
            assertEquals("", d.holdId)
            assertTrue(a.kind.name, d.rejectMessage.contains("gratuite"))
            assertFalse("never the old bypass", d.note.contains("trust rule"))
        }
        // and a FREE session is untouched by any of it
        assertTrue(HoldGate.decide(false, HoldGate.Answer.unreachable).admit)
    }
}
