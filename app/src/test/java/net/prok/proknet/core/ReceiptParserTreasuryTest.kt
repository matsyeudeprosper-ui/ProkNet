package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.18.0: on the treasury phone an operator message must yield a direction, an amount
 * and the other party's number - or nothing. The corpus is SYNTHETIC: nobody has seen a
 * real MTN Congo or Airtel Congo message yet, which is the first pilot task (T85). These
 * cases hold the shape of the reader, not the operators' wording.
 */
class ReceiptParserTreasuryTest {

    private fun p(text: String) = ReceiptParser.parseTreasury(text)

    // ================= money in =================

    @Test fun money_in_gives_credit_amount_and_the_sender() {
        val r = p("Vous avez reçu 503 FCFA de 066123456. Nouveau solde: 12 503 FCFA. Ref: MP240925.1234.")
        assertEquals(ReceiptParser.Direction.CREDIT, r.direction)
        assertEquals(50_300L, r.amountCentimes)
        assertEquals("066123456", r.counterpartyDigits)
        assertTrue(r.reason, r.usable)
    }

    @Test fun the_number_is_read_however_the_operator_prints_it() {
        for (n in listOf("+242 06 612 34 56", "00242066123456", "06 61 23 45 6", "066123456"))
            assertEquals(n, "066123456", p("Vous avez recu 1 000 F de $n. Solde 5 000 F").counterpartyDigits)
    }

    @Test fun the_numbers_digits_never_compete_to_be_the_amount() {
        val r = p("Transfert recu: 500 F de 055987654")
        assertEquals(50_000L, r.amountCentimes)
        assertEquals("055987654", r.counterpartyDigits)
    }

    // ================= money out =================

    @Test fun money_out_gives_debit_amount_and_the_recipient() {
        val r = p("Vous avez envoyé 5 000 FCFA à 055987654. Frais: 100 FCFA. Nouveau solde: 41 900 FCFA. TxId 7788990011")
        assertEquals(r.reason, ReceiptParser.Direction.DEBIT, r.direction)
        assertEquals(r.reason, 500_000L, r.amountCentimes)
        assertEquals("055987654", r.counterpartyDigits)
        assertTrue(r.reason, r.usable)
    }

    // ================= nothing, honestly =================

    @Test fun without_a_counterparty_nothing_is_usable() {
        val r = p("Vous avez reçu 500 FCFA. Solde 5 000 FCFA")
        assertEquals(ReceiptParser.Direction.CREDIT, r.direction)
        assertEquals("", r.counterpartyDigits)
        assertFalse(r.usable)
    }

    @Test fun otp_promo_and_bundle_messages_are_not_money() {
        for (t in listOf("Votre code OTP est 123456, ne partagez pas", "PROMO: 1 Go pour 500 F ce soir", "Votre forfait expire demain"))
            assertEquals(t, ReceiptParser.Direction.NONE, p(t).direction)
    }

    @Test fun a_muddled_message_is_not_acted_on() {
        val r = p("Vous avez reçu 500 F et envoyé 500 F au 066123456 et 700 F")
        assertFalse(r.usable)
    }

    // ================= the seller path is untouched =================

    @Test fun the_seller_side_parser_still_returns_no_number() {
        val parsed = ReceiptParser.parse("Vous avez reçu 500 FCFA de 066123456. Solde 5 000 F")
        assertEquals(ReceiptParser.Verdict.CREDIT, parsed.verdict)
        assertFalse(parsed.reason.contains("066123456"))
        assertFalse(parsed.reference.contains("066123456"))
    }
}
