package net.prok.proknet.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.16.2: parser wording can be updated without a new APK — but only as **signed data**,
 * and only within bounds the phone enforces itself.
 *
 * These tests run the production [ReceiptRules.accept], with a test key supplied where the
 * shipped build has a pinned one. The point is that the code deciding whether to trust a
 * configuration in the field is the code under test here, not a parallel copy of it.
 *
 * The thing being protected is the part of the system that decides whether money arrived.
 * A configuration that can reach that decision is a configuration an attacker would like to
 * write, so every failure below must end with the phone keeping the rules it already had.
 */
class ReceiptRulesTest {

    private val kp = Crypto.generateKeyPair()
    private val key = Crypto.publicBytes(kp.public).toHex()
    private val other = Crypto.publicBytes(Crypto.generateKeyPair().public).toHex()
    private val now = 1_700_000_000_000L

    @After fun clean() = ReceiptRules.resetForTest()

    private fun terms(vararg p: Pair<String, List<String>>) = mapOf(*p)

    private fun published(
        version: Int,
        t: Map<String, List<String>>,
        validFrom: Long = now,
        signWith: java.security.PrivateKey = kp.private,
    ): String {
        val sig = Crypto.sign(signWith, ReceiptRules.canonical(version, validFrom, t)).toHex()
        val body = ReceiptRules.CATEGORIES.joinToString(",") { k ->
            "\"" + k + "\":[" + (t[k] ?: emptyList()).joinToString(",") { "\"" + it + "\"" } + "]"
        }
        return "{\"version\":$version,\"validFrom\":$validFrom,\"terms\":{$body},\"signature\":\"$sig\"}"
    }

    // ---- the happy path ----------------------------------------------------------------

    @Test fun a_correctly_signed_configuration_is_accepted_and_reaches_the_parser() {
        val t = terms("credit" to listOf("vous avez recu", "recu de"), "currency" to listOf("FCFA", "XAF"))
        val got = ReceiptRules.accept(published(3, t), key, minVersion = 0)
        assertNotNull("a well-formed signed configuration must be accepted", got)
        assertEquals(3, got!!.first.version)
        assertEquals(listOf("vous avez recu", "recu de"), got.first.terms["credit"])

        // and it is the rules the parser would actually use
        val rules = got.first.toRules()
        assertEquals(listOf("vous avez recu", "recu de"), rules.credit)
    }

    @Test fun categories_the_publisher_left_out_keep_their_built_in_values() {
        val t = terms("credit" to listOf("vous avez recu"))
        val c = ReceiptRules.accept(published(2, t), key, minVersion = 0)!!.first
        val rules = c.toRules()
        assertEquals(listOf("vous avez recu"), rules.credit)
        assertEquals("an absent category must fall back, never become empty",
            ReceiptParser.DEFAULT.debit, rules.debit)
        assertEquals(ReceiptParser.DEFAULT.reject, rules.reject)
    }

    // ---- every way it must be refused ---------------------------------------------------

    @Test fun a_configuration_signed_by_anybody_else_is_refused() {
        val t = terms("credit" to listOf("vous avez recu"))
        val forged = published(3, t, signWith = Crypto.generateKeyPair().private)
        assertNull("only the pinned key may publish rules", ReceiptRules.accept(forged, key, 0))
    }

    @Test fun a_correctly_signed_configuration_verified_against_another_key_is_refused() {
        val t = terms("credit" to listOf("vous avez recu"))
        assertNull(ReceiptRules.accept(published(3, t), other, 0))
    }

    @Test fun editing_a_term_after_signing_is_refused() {
        val t = terms("credit" to listOf("vous avez recu"))
        val tampered = published(3, t).replace("vous avez recu", "vous avez perdu")
        assertNull("the signature covers the terms themselves", ReceiptRules.accept(tampered, key, 0))
    }

    @Test fun editing_the_version_after_signing_is_refused() {
        val t = terms("credit" to listOf("vous avez recu"))
        val tampered = published(3, t).replace("\"version\":3", "\"version\":9")
        assertNull(ReceiptRules.accept(tampered, key, 0))
    }

    @Test fun an_old_version_cannot_be_pushed_back_over_a_newer_one() {
        val t = terms("credit" to listOf("vous avez recu"))
        assertNull("rolling the phone back to old wording is a downgrade attack",
            ReceiptRules.accept(published(3, t), key, minVersion = 5))
        assertNull("the same version is not new either",
            ReceiptRules.accept(published(5, t), key, minVersion = 5))
    }

    @Test fun with_no_key_pinned_no_configuration_is_ever_accepted() {
        val t = terms("credit" to listOf("vous avez recu"))
        assertNull("an empty pinned key means built-in rules only",
            ReceiptRules.accept(published(3, t), "", 0))
    }

    @Test fun the_shipped_build_pins_no_key_so_it_runs_on_built_in_rules_alone() {
        val t = terms("credit" to listOf("vous avez recu"))
        assertEquals("", ReceiptRules.PINNED_CONFIG_KEY)
        assertNull("no key ceremony has happened, so nothing remote may be trusted yet",
            ReceiptRules.accept(published(3, t)))
        assertEquals(ReceiptParser.DEFAULT.credit, ReceiptRules.current().credit)
    }

    // ---- bounds: a compromised publisher still cannot send anything it likes --------------

    @Test fun too_many_terms_in_one_category_is_refused_whole() {
        val many = (1..ReceiptRules.MAX_TERMS_PER_CATEGORY + 1).map { "t$it" }
        val t = terms("credit" to many)
        assertNull(ReceiptRules.accept(published(3, t), key, 0))
    }

    @Test fun an_over_long_term_is_refused_whole() {
        val t = terms("credit" to listOf("x".repeat(ReceiptRules.MAX_TERM_LENGTH + 1)))
        assertNull(ReceiptRules.accept(published(3, t), key, 0))
    }

    @Test fun an_empty_term_is_refused_because_it_would_match_everything() {
        assertTrue(ReceiptRules.acceptable(1, now, terms("credit" to listOf("a"))))
        assertTrue("an empty term matches every message, including debits",
            !ReceiptRules.acceptable(1, now, terms("credit" to listOf(""))))
    }

    @Test fun control_characters_in_a_term_are_refused() {
        assertTrue(!ReceiptRules.acceptable(1, now, terms("credit" to listOf("re\u0001cu"))))
        assertTrue(!ReceiptRules.acceptable(1, now, terms("credit" to listOf("re\ncu"))))
        assertTrue(!ReceiptRules.acceptable(1, now, terms("credit" to listOf("re\u007Fcu"))))
    }

    @Test fun an_unknown_category_is_refused_rather_than_ignored() {
        assertTrue("a category we do not understand may be a category we would execute",
            !ReceiptRules.acceptable(1, now, mapOf("script" to listOf("rm"))))
    }

    @Test fun version_zero_and_a_negative_valid_from_are_refused() {
        assertTrue(!ReceiptRules.acceptable(0, now, terms("credit" to listOf("a"))))
        assertTrue(!ReceiptRules.acceptable(1, -1, terms("credit" to listOf("a"))))
    }

    @Test fun a_configuration_larger_than_the_cap_is_refused() {
        val big = (1..ReceiptRules.MAX_TERMS_PER_CATEGORY).map { "x".repeat(ReceiptRules.MAX_TERM_LENGTH) }
        val t = ReceiptRules.CATEGORIES.associateWith { big }
        assertTrue("six full categories must exceed the byte cap",
            ReceiptRules.canonical(1, now, t).size > ReceiptRules.MAX_CONFIG_BYTES)
        assertTrue(!ReceiptRules.acceptable(1, now, t))
    }

    // ---- the bytes both sides sign --------------------------------------------------------

    @Test fun the_canonical_form_is_domain_separated_and_order_independent() {
        val a = ReceiptRules.canonical(1, now, terms(
            "credit" to listOf("a"), "currency" to listOf("FCFA")))
        val b = ReceiptRules.canonical(1, now, terms(
            "currency" to listOf("FCFA"), "credit" to listOf("a")))
        assertEquals("the same content must produce the same bytes whatever the map order",
            String(a), String(b))
        assertTrue(String(a).startsWith(ReceiptRules.DOMAIN + "|"))
    }

    @Test fun a_signature_for_one_version_does_not_transfer_to_another() {
        val t = terms("credit" to listOf("a"))
        val sig = Crypto.sign(kp.private, ReceiptRules.canonical(3, now, t)).toHex()
        assertTrue(ReceiptRules.verify(3, now, t, sig, key))
        assertTrue("the version is inside the signed bytes",
            !ReceiptRules.verify(4, now, t, sig, key))
        assertTrue("so is validFrom", !ReceiptRules.verify(3, now + 1, t, sig, key))
    }

    @Test fun a_malformed_signature_is_a_refusal_not_a_crash() {
        val t = terms("credit" to listOf("a"))
        assertTrue(!ReceiptRules.verify(3, now, t, "not-hex", key))
        assertTrue(!ReceiptRules.verify(3, now, t, "", key))
    }
}
