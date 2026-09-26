package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** v0.19.0: the phone mirrors the Brain's switches and never invents an "on". */
class FlagsViewTest {

    private val text = """{"city": "Brazzaville", "operator": false, "functions": {
        "public_map": {"enabled": true, "switch_on": true, "cohort": true, "missing_decisions": [], "off_sentence": ""},
        "market_browse": {"enabled": false, "switch_on": false, "cohort": true, "missing_decisions": [], "off_sentence": "Prok Market n'est pas encore ouvert dans cette ville."},
        "customer_paid_delivery": {"enabled": false, "switch_on": true, "cohort": false, "missing_decisions": ["payment_classification", "upstream_permission"], "off_sentence": "La livraison payante d'Internet n'est pas encore ouverte pour votre compte."}}}"""

    @Test fun the_answer_is_read_function_by_function() {
        val s = FlagsView.parse(text, 5L)!!
        assertTrue(s.enabled("public_map"))
        assertFalse(s.enabled("market_browse"))
        assertEquals("Prok Market n'est pas encore ouvert dans cette ville.", s.offSentence("market_browse"))
        assertEquals("décision manquante : payment_classification, upstream_permission", s.why("customer_paid_delivery"))
        assertEquals("interrupteur fermé", s.why("market_browse"))
        assertFalse("a function the answer does not mention is off", s.enabled("relay_payout"))
    }

    @Test fun no_brain_means_only_what_the_cache_supports() {
        val u = FlagsView.unknown("Brazzaville")
        assertTrue(u.enabled("public_map"))
        assertTrue(u.enabled("direct_free"))
        for (f in FlagsView.FUNCTIONS) if (f !in FlagsView.OFFLINE_OK) assertFalse(f, u.enabled(f))
        assertEquals(FlagsView.DEFAULT_OFF, u.offSentence("market_paid_publish"))
    }

    @Test fun rubbish_is_not_a_status() {
        assertEquals(null, FlagsView.parse("", 1L))
        assertEquals(null, FlagsView.parse("{\"error\": \"x\"}", 1L))
    }
}
