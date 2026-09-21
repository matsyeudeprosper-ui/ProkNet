package net.prok.proknet.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.16.2: what the phone believes when a payment object arrives through the Brain rather
 * than over Bluetooth.
 *
 * Two questions decide it, and neither answer may come from the server:
 *
 * 1. Which bytes are the other phone's signed object?
 * 2. Which public key is allowed to verify them?
 *
 * The second is the interesting one. A node id is the first bytes of the hash of its public
 * key, so a key that derives to the id inside the message is the right key by construction.
 * That is what makes it safe to take a key from an untrusted carrier at all — and what must
 * never be relaxed, because a key the Brain chose would let the Brain sign anything.
 */
class BrainPayloadTest {

    private val kp = Crypto.generateKeyPair()
    private val pub = Crypto.publicBytes(kp.public)
    private val id = Crypto.deriveId(pub).toHex()

    private val impostor = Crypto.publicBytes(Crypto.generateKeyPair().public)

    // ---- whose key --------------------------------------------------------------------

    @Test fun a_key_that_hashes_to_the_identity_is_accepted_from_an_untrusted_carrier() {
        assertArrayEquals("the id is the hash of the key, so this needs no directory",
            pub, BrainPayload.pubFor(id, pub.toHex(), null))
    }

    @Test fun a_key_for_a_different_identity_is_refused_even_though_it_is_a_valid_key() {
        assertNull("this is the substitution the Brain would attempt",
            BrainPayload.pubFor(id, impostor.toHex(), null))
    }

    @Test fun a_substituted_key_does_not_override_the_one_we_already_hold() {
        assertArrayEquals("a phone that has met this peer keeps what it learned in person",
            pub, BrainPayload.pubFor(id, impostor.toHex(), pub))
    }

    @Test fun rubbish_where_a_key_should_be_falls_back_instead_of_crashing() {
        assertArrayEquals(pub, BrainPayload.pubFor(id, "zzzz", pub))
        assertArrayEquals(pub, BrainPayload.pubFor(id, "abc", pub))
        assertNull(BrainPayload.pubFor(id, "zzzz", null))
        assertNull(BrainPayload.pubFor(id, "", null))
        assertNull(BrainPayload.pubFor(id, null, null))
    }

    @Test fun an_upper_case_key_is_the_same_key() {
        assertArrayEquals(pub, BrainPayload.pubFor(id, pub.toHex().uppercase(), null))
    }

    // ---- which bytes -------------------------------------------------------------------

    @Test fun an_array_of_objects_is_read_item_by_item() {
        val text = "{\"expectations\": [{\"payment_id\":\"a1\",\"line\":\"pay1.exp|x\",\"buyer_pub\":\"aabb\"}," +
            "{\"payment_id\":\"a2\",\"line\":\"pay1.exp|y\",\"buyer_pub\":\"ccdd\"}]}"
        val got = BrainPayload.objects(text, "expectations")
        assertEquals(2, got.size)
        assertEquals("pay1.exp|x", got[0]["line"])
        assertEquals("ccdd", got[1]["buyer_pub"])
    }

    @Test fun a_single_object_is_read_too() {
        val text = "{\"destination\": {\"line\":\"pay1.dest|x\",\"version\":3,\"seller_pub\":\"aabb\"}}"
        val got = BrainPayload.objects(text, "destination")
        assertEquals(1, got.size)
        assertEquals("pay1.dest|x", got[0]["line"])
        assertEquals("aabb", got[0]["seller_pub"])
    }

    @Test fun an_empty_or_null_answer_is_no_objects_rather_than_an_error() {
        assertTrue(BrainPayload.objects("{\"destination\": null}", "destination").isEmpty())
        assertTrue(BrainPayload.objects("{\"expectations\": []}", "expectations").isEmpty())
        assertTrue(BrainPayload.objects("{}", "expectations").isEmpty())
        assertTrue(BrainPayload.objects("", "expectations").isEmpty())
        assertTrue(BrainPayload.objects("not json at all", "expectations").isEmpty())
        assertTrue("a truncated answer must not be read as a valid one",
            BrainPayload.objects("{\"expectations\": [{\"line\":\"x\"", "expectations").isEmpty())
    }

    @Test fun a_quote_inside_a_line_survives_the_round_trip() {
        val awkward = "pay1.exp|he said \"ok\"|and a backslash \\ too"
        val text = "{\"receipts\": [{\"line\":\"" + BrainPayload.escape(awkward) + "\"}]}"
        assertEquals(awkward, BrainPayload.objects(text, "receipts")[0]["line"])
    }

    @Test fun asking_for_a_key_that_is_not_there_returns_nothing() {
        val text = "{\"expectations\": [{\"line\":\"x\"}]}"
        assertNull(BrainPayload.objects(text, "expectations")[0]["buyer_pub"])
        assertTrue(BrainPayload.objects(text, "receipts").isEmpty())
    }

    // ---- single values ------------------------------------------------------------------

    @Test fun numbers_strings_and_booleans_all_read_back() {
        val text = "{\"unresolvedCentimes\": 2500, \"reply\":\"ACCEPTED\", \"ok\": true}"
        assertEquals("2500", BrainPayload.field(text, "unresolvedCentimes"))
        assertEquals("ACCEPTED", BrainPayload.field(text, "reply"))
        assertEquals("true", BrainPayload.field(text, "ok"))
        assertEquals("", BrainPayload.field(text, "missing"))
    }

    @Test fun a_zero_debt_reads_as_zero_and_not_as_missing() {
        // "" would become 0 as well, but only by accident; a server saying nothing and a
        // server saying nothing is owed must not be the same answer by luck
        assertEquals("0", BrainPayload.field("{\"unresolvedCentimes\": 0}", "unresolvedCentimes"))
        assertEquals("", BrainPayload.field("{\"other\": 0}", "unresolvedCentimes"))
    }
}
