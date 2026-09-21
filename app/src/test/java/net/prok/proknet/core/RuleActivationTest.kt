package net.prok.proknet.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.16.3: the point of signed rules, proved on a message the shipped parser cannot read.
 *
 * Everything else about this feature can pass while it is useless. The bounds can be
 * right, the signature checks can be right, the storage can be right - and the parser can
 * still ignore the new words, in which case an operator rewording would silently stop
 * clearing debts until somebody shipped an APK.
 *
 * So this walks the whole thing with one synthetic message:
 *
 *     the built-in parser does NOT recognise it
 *  -> the publisher signs a configuration that adds the wording
 *  -> the phone verifies and activates it
 *  -> the SAME message now reads as a credit for the right amount
 *  -> a restart keeps it
 *  -> a tampered one is refused and the good one stays in force
 */
class RuleActivationTest {

    /** Not in any built-in list. MTN and Airtel really do reword things like this. */
    private val message = "Fonds arrives : 73 XAF de MTN. Merci."
    private val expected = 73L * 100

    private val kp = Crypto.generateKeyPair()
    private val key = Crypto.publicBytes(kp.public).toHex()
    private val now = 1_758_400_000_000L

    @After fun clean() = ReceiptRules.resetForTest()

    private val newTerms = mapOf(
        "credit" to (ReceiptParser.DEFAULT.credit + "fonds arrives"),
        "currency" to (ReceiptParser.DEFAULT.currency + "XAF"))

    private fun published(version: Int, terms: Map<String, List<String>>, validFrom: Long = now): String {
        val sig = Crypto.sign(kp.private, ReceiptRules.canonical(version, validFrom, terms)).toHex()
        val body = ReceiptRules.CATEGORIES.joinToString(",") { k ->
            "\"" + k + "\":[" + (terms[k] ?: emptyList()).joinToString(",") { "\"" + it + "\"" } + "]"
        }
        return "{\"rules\":{\"version\":$version,\"validFrom\":$validFrom,\"terms\":{$body},\"signature\":\"$sig\"}}"
    }

    // ---- the state of the world before any configuration --------------------------------

    @Test fun the_built_in_parser_does_not_recognise_the_new_wording() {
        val p = ReceiptParser.parse(message, expected, ReceiptParser.DEFAULT)
        assertTrue("if this already parsed, the test would prove nothing", !p.usable)
        assertEquals(ReceiptParser.Verdict.NOT_A_CREDIT, p.verdict)
    }

    @Test fun and_it_does_not_recognise_it_through_the_rules_in_force_either() {
        assertTrue(!ReceiptParser.parse(message, expected, ReceiptRules.current()).usable)
    }

    // ---- the whole loop ------------------------------------------------------------------

    @Test fun a_signed_configuration_teaches_the_parser_the_new_wording() {
        val got = ReceiptRules.accept(published(2, newTerms), key, minVersion = 0)
        assertNotNull("the publisher signed it, so the phone must take it", got)

        val p = ReceiptParser.parse(message, expected, got!!.first.toRules())
        assertEquals("the same message, now understood", ReceiptParser.Verdict.CREDIT, p.verdict)
        assertEquals(expected, p.amountCentimes)
        assertTrue("and confidently enough to clear a debt", p.usable)
    }

    @Test fun the_old_wording_keeps_working_after_the_update() {
        // adding words must never take any away, or an update would break every seller
        // whose operator did not change anything
        val rules = ReceiptRules.accept(published(2, newTerms), key, 0)!!.first.toRules()
        val old = "Vous avez recu 73 FCFA de MTN."
        assertEquals(ReceiptParser.Verdict.CREDIT, ReceiptParser.parse(old, expected, rules).verdict)
    }

    @Test fun a_debit_is_still_a_debit_after_the_update() {
        val rules = ReceiptRules.accept(published(2, newTerms), key, 0)!!.first.toRules()
        val debit = "Fonds arrives ? Non: vous avez envoye 73 XAF a MTN."
        assertTrue("money leaving must never read as money arriving",
            !ReceiptParser.parse(debit, expected, rules).usable)
    }

    // ---- across a restart -------------------------------------------------------------------

    @Test fun a_restart_keeps_the_configuration_it_had_accepted() {
        val (config, signature) = ReceiptRules.accept(published(2, newTerms), key, 0)!!
        ReceiptRules.resetForTest()          // the process died
        assertTrue("a restart must not silently fall back to the built-in rules",
            ReceiptRules.restore(config, signature, key))
        assertEquals(2, ReceiptRules.activeVersion())
        assertEquals(ReceiptParser.Verdict.CREDIT,
            ReceiptParser.parse(message, expected, ReceiptRules.current()).verdict)
    }

    @Test fun a_restart_with_nothing_stored_runs_the_built_in_rules() {
        assertTrue(!ReceiptRules.restore(null, ""))
        assertEquals(0, ReceiptRules.activeVersion())
        assertEquals(ReceiptParser.DEFAULT.credit, ReceiptRules.current().credit)
    }

    @Test fun a_stored_configuration_whose_signature_no_longer_holds_is_not_restored() {
        // the phone's own database is the easiest part of a phone to reach, so what comes
        // out of it is checked again rather than trusted because it was trusted once
        val (config, _) = ReceiptRules.accept(published(2, newTerms), key, 0)!!
        ReceiptRules.resetForTest()
        assertTrue(!ReceiptRules.restore(config, "00".repeat(70), key))
        assertEquals(0, ReceiptRules.activeVersion())
        assertEquals(ReceiptParser.DEFAULT.credit, ReceiptRules.current().credit)
    }

    @Test fun a_configuration_edited_in_storage_is_not_restored() {
        val (config, signature) = ReceiptRules.accept(published(2, newTerms), key, 0)!!
        ReceiptRules.resetForTest()
        val edited = ReceiptRules.Config(config.version, config.validFrom,
            config.terms + ("credit" to listOf("")))
        assertTrue(!ReceiptRules.restore(edited, signature, key))
        assertEquals(0, ReceiptRules.activeVersion())
    }

    // ---- a tampered update ----------------------------------------------------------------------

    @Test fun a_tampered_update_is_refused_and_the_good_one_stays_in_force() {
        assertNotNull(ReceiptRules.accept(published(2, newTerms), key, 0))
        // pretend it is installed
        val installed = ReceiptRules.accept(published(2, newTerms), key, 0)!!
        ReceiptRules.restore(installed.first, installed.second, key)
        assertEquals(2, ReceiptRules.activeVersion())

        val hostile = published(3, newTerms).replace("fonds arrives", "envoye")
        assertNull("a config edited after signing must not be taken",
            ReceiptRules.accept(hostile, key))
        assertEquals("the version in force must not move", 2, ReceiptRules.activeVersion())
        assertEquals("and the wording it taught must still work",
            ReceiptParser.Verdict.CREDIT,
            ReceiptParser.parse(message, expected, ReceiptRules.current()).verdict)
    }

    @Test fun a_configuration_signed_by_a_different_key_cannot_teach_the_parser_anything() {
        val impostor = Crypto.publicBytes(Crypto.generateKeyPair().public).toHex()
        assertNull(ReceiptRules.accept(published(2, newTerms), impostor, 0))
        assertEquals(0, ReceiptRules.activeVersion())
        assertTrue(!ReceiptParser.parse(message, expected, ReceiptRules.current()).usable)
    }

    @Test fun nothing_the_pinned_key_did_not_sign_reaches_the_parser() {
        // the shipped default: only the pinned key, and this test's key is not it
        assertNull(ReceiptRules.accept(published(2, newTerms)))
        assertEquals(ReceiptParser.DEFAULT.credit, ReceiptRules.current().credit)
    }
}
