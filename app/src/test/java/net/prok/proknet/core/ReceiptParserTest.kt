package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.16.0: reading operator messages nobody has shown us.
 *
 * We have no real MTN or Airtel samples and are not waiting for any, so the parser is
 * tested against a deliberately wide synthetic corpus: different verbs, different currency
 * spellings, accents present and missing, thousands separators, English and French, extra
 * promotional lines. If the parser only worked on one sentence it would be a template, and
 * a template quietly stops clearing debts the day an operator rewords anything.
 *
 * The bias being enforced throughout: **a false negative costs a retry, a false positive
 * gives away Internet for free.**
 */
class ReceiptParserTest {

    private fun cfa(units: Long) = units * 100

    private fun credit(text: String, expect: Long, expected: Long = 0) {
        val p = ReceiptParser.parse(text, expected)
        assertEquals(text + " -> " + p.reason, ReceiptParser.Verdict.CREDIT, p.verdict)
        assertEquals(text, cfa(expect), p.amountCentimes)
        assertTrue(text + " confidence " + p.confidence, p.usable)
    }

    private fun notCredit(text: String, expected: Long = 0) {
        val p = ReceiptParser.parse(text, expected)
        assertNotEquals(text + " must not clear a debt", ReceiptParser.Verdict.CREDIT, p.verdict)
        assertFalse(text, p.usable)
    }

    private fun ambiguous(text: String, expected: Long = 0) {
        val p = ReceiptParser.parse(text, expected)
        assertEquals(text + " -> " + p.reason, ReceiptParser.Verdict.AMBIGUOUS, p.verdict)
        assertFalse(p.usable)
    }

    // ================= it really is a credit =================

    @Test
    fun many_ways_of_saying_money_arrived() {
        credit("Vous avez reçu 50 FCFA", 50)
        credit("Vous avez recu 50 CFA", 50)
        credit("50 CFA ont été crédités sur votre compte", 50)
        credit("Credit de 50 XAF", 50)
        credit("Received 50 CFA", 50)
        credit("Versement reçu : 50 FCFA", 50)
        credit("Transfert reçu de 50 F CFA", 50)
        credit("Money received: 50 CFA", 50)
        credit("Vous avez recu un versement de 50 francs CFA", 50)
        credit("Deposit of 50 XAF confirmed", 50)
    }

    @Test
    fun accents_case_and_odd_spacing_change_nothing() {
        // the same message an operator might send four slightly different ways
        for (t in listOf(
            "Vous avez reçu 1 250 FCFA",
            "VOUS AVEZ RECU 1250 FCFA",
            "vous avez reçu 1.250 fcfa",
            "Vous avez  reçu 1 250 FCFA")) {
            credit(t, 1_250)
        }
    }

    @Test
    fun thousands_separators_are_read_correctly() {
        credit("Vous avez reçu 1 250 FCFA", 1_250)
        credit("Vous avez reçu 1,250 FCFA", 1_250)
        credit("Vous avez reçu 1.250 FCFA", 1_250)
        credit("Vous avez reçu 12 500 FCFA", 12_500)
        // and a genuine fraction is not a thousands group
        val p = ReceiptParser.parse("Vous avez reçu 50,75 FCFA")
        assertEquals(ReceiptParser.Verdict.CREDIT, p.verdict)
        assertEquals(5_075, p.amountCentimes)
    }

    // ================= it is not a credit =================

    @Test
    fun money_leaving_never_clears_a_debt() {
        notCredit("Vous avez envoyé 50 CFA à Jean")
        notCredit("You have sent 50 CFA")
        notCredit("Retrait de 50 CFA effectué")
        notCredit("Votre compte a été débité de 50 CFA")
        notCredit("Paiement de 50 CFA effectué chez MARCHE")
        notCredit("Transfert envoyé : 50 FCFA")
        notCredit("Withdrawal of 50 XAF")
    }

    @Test
    fun a_balance_notice_is_not_a_payment() {
        notCredit("Votre solde est de 50 CFA")
        notCredit("Your balance is 50 CFA")
        notCredit("Solde disponible : 1 450 FCFA")
    }

    @Test
    fun security_and_marketing_messages_are_refused_outright() {
        notCredit("Votre code OTP est 5012. Ne partagez ce code avec personne.")
        notCredit("Code de verification 500123")
        notCredit("Votre mot de passe temporaire est 5012")
        notCredit("PROMO: rechargez 50 CFA et recevez 100 CFA de bonus")
        notCredit("Nouveau forfait : 50 CFA par jour")
    }

    @Test
    fun a_message_that_is_not_about_money_at_all_is_refused() {
        notCredit("Salut, tu es où ?")
        notCredit("")
        notCredit("50")
        notCredit("Votre rendez-vous est confirmé pour 14h30")
    }

    // ================= the hard one: several numbers =================

    @Test
    fun the_payment_is_chosen_over_the_running_balance() {
        // the case a naive parser gets wrong every time by taking the first or largest number
        credit("Vous avez reçu 50 CFA. Nouveau solde 1 450 CFA.", 50)
        credit("Vous avez reçu 50 CFA. Votre solde est maintenant de 1450 FCFA.", 50)
        credit("Credit de 200 XAF. Solde: 9 800 XAF", 200)
        // and the expected amount reinforces it rather than inventing it
        credit("Vous avez reçu 50 CFA. Nouveau solde 1 450 CFA.", 50, expected = cfa(50))
    }

    @Test
    fun phone_numbers_dates_and_transaction_ids_are_not_amounts() {
        credit("Vous avez reçu 50 FCFA de 242061234567", 50)
        credit("Vous avez reçu 50 FCFA. Ref: 987654321012", 50)
        credit("Vous avez reçu 75 FCFA le 21/09/2026 a 14:32", 75)
    }

    @Test
    fun the_expected_amount_can_never_conjure_a_number_that_is_not_there() {
        // we are waiting for 50 and the message says 40: this must not match
        val p = ReceiptParser.parse("Vous avez reçu 40 FCFA", cfa(50))
        assertNotEquals(cfa(50), p.amountCentimes)
        if (p.verdict == ReceiptParser.Verdict.CREDIT) assertEquals(cfa(40), p.amountCentimes)
    }

    @Test
    fun two_equally_plausible_amounts_are_ambiguous_rather_than_guessed() {
        ambiguous("Recu 50 CFA 60 CFA")
        ambiguous("Credit 50 XAF / 60 XAF")
    }

    @Test
    fun a_credit_word_with_no_number_is_ambiguous_not_a_payment() {
        ambiguous("Vous avez reçu un paiement")
        ambiguous("Credit effectue sur votre compte")
    }

    // ================= details =================

    @Test
    fun a_reference_is_taken_when_offered_and_never_required() {
        val with = ReceiptParser.parse("Vous avez reçu 50 FCFA. Ref: ABC123456", cfa(50))
        assertEquals(ReceiptParser.Verdict.CREDIT, with.verdict)
        assertEquals("ABC123456", with.reference)
        // and its absence changes nothing, which is the whole point of the design
        val without = ReceiptParser.parse("Vous avez reçu 50 FCFA", cfa(50))
        assertEquals(ReceiptParser.Verdict.CREDIT, without.verdict)
        assertEquals("", without.reference)
        assertTrue(without.usable)
        assertEquals(with.amountCentimes, without.amountCentimes)
    }

    @Test
    fun every_result_records_the_parser_version() {
        val p = ReceiptParser.parse("Vous avez reçu 50 FCFA", cfa(50))
        assertEquals(ReceiptParser.PARSER_VERSION, p.parserVersion)
        assertTrue(p.reason.isNotEmpty())
    }

    @Test
    fun the_dictionaries_are_data_so_wording_can_change_without_a_new_app() {
        // an operator invents a phrase we have never seen
        val odd = "Montant encaisse 50 FCFA"
        notCredit(odd)
        // adding the word is a configuration change, not a code change
        val rules = ReceiptParser.Rules(credit = ReceiptParser.DEFAULT.credit + "encaisse")
        val p = ReceiptParser.parse(odd, cfa(50), rules)
        assertEquals(ReceiptParser.Verdict.CREDIT, p.verdict)
        assertEquals(cfa(50), p.amountCentimes)
    }

    @Test
    fun normalisation_is_stable_and_lossless_for_matching_purposes() {
        assertEquals("vous avez recu 50 fcfa", ReceiptParser.normalize("Vous avez reçu 50 FCFA"))
        assertEquals("credit de 50 xaf", ReceiptParser.normalize("Crédit de  50   XAF"))
        assertEquals(ReceiptParser.normalize("RECU"), ReceiptParser.normalize("reçu"))
    }

    @Test
    fun amounts_are_extracted_with_their_positions() {
        val t = ReceiptParser.normalize("Vous avez reçu 50 CFA. Nouveau solde 1 450 CFA.")
        val found = ReceiptParser.amounts(t)
        assertTrue(found.size >= 2)
        assertTrue(found.any { it.centimes == cfa(50) })
        assertTrue(found.any { it.centimes == cfa(1_450) })
        // and they are ordered by where they appear, so proximity scoring means something
        assertTrue(found.first().at < found.last().at)
    }
}
