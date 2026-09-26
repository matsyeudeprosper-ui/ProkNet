package net.prok.proknet.core

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.18.0: the words a provider's screen may use about a withdrawal, and the numbers it
 * shows, come from the ledger and from one shared table - never from a guess.
 */
class LedgerViewTest {

    private fun fixture(name: String): File {
        var dir: File? = File(".").absoluteFile
        while (dir != null) {
            val f = File(dir, "server/tests/fixtures/" + name)
            if (f.exists()) return f
            dir = dir.parentFile
        }
        throw AssertionError("server/tests/fixtures/" + name + " not found from " + File(".").absolutePath)
    }

    private val wallet = """{"credit": 123400, "held": 20000, "earned": 95000, "earned_lifetime": 145000, "withdrawable": 95000,
        "withdraw_min": 50000, "withdrawal": {"id": "w1", "amount": 50000, "rail": "MTN", "state": "SENT", "text": "Envoi en cours",
        "requested_at": 1700000000000, "sent_at": 1700003600000, "paid_at": 0, "paid_evidence": "", "memo": "", "amber": false,
        "payee_id": "bb", "msisdn_hash": "h", "msisdn_tail": "3456"}, "hold": {"hold_id": "h1", "amount": 20000, "state": "IN_SESSION",
        "seller_id": "cc"}, "treasury": false, "payments_live": false}"""

    // ================= the shared table =================

    @Test fun the_state_texts_are_exactly_the_fixture_the_server_wrote() {
        val lines = fixture("withdrawal_states.txt").readLines(Charsets.UTF_8)
            .map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        val expected = lines.associate { val p = it.split("|", limit = 2); p[0] to p[1] }
        assertEquals(expected, LedgerView.WITHDRAWAL_TEXT)
    }

    @Test fun paye_is_said_only_for_a_paid_state_and_an_unknown_state_is_cautious() {
        for ((state, text) in LedgerView.WITHDRAWAL_TEXT)
            if (state != "PAID") assertFalse(state + " must not read as paid", text.contains("Payé"))
        assertEquals("Payé", LedgerView.statusText("PAID"))
        assertEquals("Envoi en cours", LedgerView.statusText("SENT"))
        assertEquals("Retrait demandé", LedgerView.statusText("REQUESTED"))
        assertEquals("Retrait demandé", LedgerView.statusText("APPROVED"))
        // a state a newer Brain invents is shown as the cautious one, never as anything stronger
        assertEquals(LedgerView.UNKNOWN_TEXT, LedgerView.statusText("SETTLED_BY_MAGIC"))
        assertFalse(LedgerView.UNKNOWN_TEXT.contains("Payé"))
    }

    // ================= parsing =================

    @Test fun a_wallet_answer_is_read_into_numbers_and_one_status_line() {
        val v = LedgerView.parse(wallet, 1_700_007_200_000L)!!
        assertEquals(123_400L, v.creditCentimes)
        assertEquals(20_000L, v.heldCentimes)
        assertEquals(95_000L, v.withdrawableCentimes)
        assertEquals(50_000L, v.withdrawMinCentimes)
        val w = v.withdrawal
        assertNotNull(w)
        assertEquals("Envoi en cours", w!!.text)
        assertEquals(50_000L, w.amountCentimes)
        assertEquals("3456", w.msisdnTail)
        assertTrue(v.hasOpenWithdrawal)
        assertFalse("one open withdrawal at a time", v.canWithdraw)
        val h = v.hold
        assertEquals("h1", h!!.id)
        assertEquals("IN_SESSION", h.state)
        assertFalse(v.treasury); assertFalse(v.paymentsLive)
    }

    @Test fun null_withdrawal_and_hold_are_read_as_absent_not_as_zeroes() {
        val v = LedgerView.parse("""{"credit": 0, "held": 0, "earned": 70000, "earned_lifetime": 70000, "withdrawable": 70000,
            "withdraw_min": 50000, "withdrawal": null, "hold": null, "treasury": true, "payments_live": false}""", 1L)!!
        assertNull(v.withdrawal); assertNull(v.hold)
        assertTrue(v.treasury)
        assertTrue(v.canWithdraw)
        assertEquals("Retirable maintenant : " + Market.cfa(70_000L), v.withdrawHint)
    }

    @Test fun retirer_is_offered_only_when_it_can_succeed() {
        val below = LedgerView.parse("""{"credit": 0, "held": 0, "earned": 49900, "earned_lifetime": 49900, "withdrawable": 49900,
            "withdraw_min": 50000, "withdrawal": null, "hold": null, "treasury": false, "payments_live": false}""", 1L)!!
        assertFalse(below.canWithdraw)
        assertTrue(below.withdrawHint.startsWith("Retrait possible à partir de"))
        val paid = LedgerView.parse("""{"credit": 0, "held": 0, "earned": 60000, "earned_lifetime": 110000, "withdrawable": 60000,
            "withdraw_min": 50000, "withdrawal": {"id": "w0", "amount": 50000, "rail": "MTN", "state": "PAID", "text": "Payé",
            "requested_at": 1, "sent_at": 2, "paid_at": 3, "paid_evidence": "treasurer:MP1", "memo": "", "amber": false,
            "payee_id": "bb", "msisdn_hash": "h", "msisdn_tail": "3456"}, "hold": null, "treasury": false, "payments_live": false}""", 1L)!!
        assertFalse("a paid withdrawal is not open", paid.hasOpenWithdrawal)
        assertTrue(paid.canWithdraw)
        assertEquals("Payé", paid.withdrawal!!.text)
    }

    @Test fun a_refund_is_offered_only_to_a_bound_number_with_nothing_open() {
        val base = """{"credit": 70000, "held": 0, "earned": 0, "earned_lifetime": 0, "withdrawable": 0, "withdraw_min": 50000,
            "refund_min": 50000, "refundable": 70000, "bound_rails": ["MTN"], "withdrawal": null, "hold": null, "treasury": false, "payments_live": true}"""
        val v = LedgerView.parse(base, 1L)!!
        assertEquals(listOf("MTN"), v.boundRails)
        assertTrue(v.canRefund)
        val nobody = LedgerView.parse(base.replace("[\"MTN\"]", "[]").replace("\"refundable\": 70000", "\"refundable\": 0"), 1L)!!
        assertFalse("no top-up from a number, no refund", nobody.canRefund)
        val holding = LedgerView.parse(base.replace("\"hold\": null", "\"hold\": {\"hold_id\": \"h\", \"amount\": 100, \"state\": \"IN_SESSION\", \"seller_id\": \"s\"}"), 1L)!!
        assertFalse("not while a session holds the credit", holding.canRefund)
    }

    @Test fun the_reconciliation_lines_say_doubt_and_block_in_plain_words() {
        val ok = """{"rails": {"MTN": {"expected": 50000, "typed": 50000, "typed_at": 1, "delta": 0, "doubt": false, "check_stale": false, "committed": 0, "unmatched_debits": 0, "unmatched_debits_centimes": 0},
            "AIRTEL": {"expected": 0, "typed": null, "typed_at": 0, "delta": null, "doubt": false, "check_stale": true, "committed": 0, "unmatched_debits": 0, "unmatched_debits_centimes": 0}},
            "liabilities": 50000, "float": 50000, "shortfall": 0, "test_credit_issued": 0, "alert": false, "approvals_blocked": false, "payments_live": true}"""
        val lines = LedgerView.reconcileLines(ok)
        assertTrue(lines.startsWith("Rapprochement OK"))
        assertTrue(lines.contains("AIRTEL") && lines.contains("jamais saisi"))
        val bad = ok.replace("\"typed\": 50000", "\"typed\": 45000").replace("\"delta\": 0", "\"delta\": -5000").replace("\"doubt\": false, \"check_stale\": false", "\"doubt\": true, \"check_stale\": false").replace("\"alert\": false", "\"alert\": true")
        val l2 = LedgerView.reconcileLines(bad)
        assertTrue(l2.startsWith("ALERTE"))
        assertTrue(l2.contains("DOUTE"))
    }

    @Test fun rubbish_is_not_a_view() {
        assertNull(LedgerView.parse("", 1L))
        assertNull(LedgerView.parse("{\"error\": \"nope\"}", 1L))
    }

    // ================= the treasurer's screen =================

    @Test fun the_queue_says_how_many_manual_sends_remain_and_never_offers_to_resend() {
        val q = LedgerView.parseQueue("""{"rows": [
            {"id": "a", "payee_id": "p1", "rail": "MTN", "msisdn": "066123456", "amount": 50000, "state": "REQUESTED", "requested_at": 1, "sent_at": 0, "amber": false, "memo": ""},
            {"id": "b", "payee_id": "p2", "rail": "AIRTEL", "msisdn": "055987654", "amount": 60000, "state": "APPROVED", "requested_at": 1, "sent_at": 0, "amber": false, "memo": ""},
            {"id": "c", "payee_id": "p3", "rail": "MTN", "msisdn": "066000000", "amount": 70000, "state": "SENT", "requested_at": 1, "sent_at": 5, "amber": true, "memo": ""}],
            "summary": {"manual_sends_pending": 2, "manual_sends_pending_centimes": 110000, "sentence": "2 retraits en attente = 2 envois manuels",
            "sent_unconfirmed": 1, "needs_attention": 0, "paid_total_centimes": 0, "topups_unassigned": 0, "float_mtn": 0, "float_airtel": 0,
            "liabilities": 180000, "shortfall": 180000, "payments_live": false}}""")!!
        assertEquals(3, q.rows.size)
        assertEquals(2, q.summary.manualSendsPending)
        assertEquals("2 retraits en attente = 2 envois manuels", q.summary.sentence)
        assertEquals(q.summary.sentence, LedgerView.manualSendsLine(2))
        assertEquals("1 retrait en attente = 1 envoi manuel", LedgerView.manualSendsLine(1))
        assertEquals(listOf("approve", "deny"), q.rows[0].actions)
        assertEquals(listOf("sent", "deny"), q.rows[1].actions)
        assertEquals(listOf("paid", "unsent"), q.rows[2].actions)
        assertFalse("a SENT row is never offered Marquer envoyé", q.rows[2].actions.contains("sent"))
        assertTrue(q.rows[2].amber)
        assertEquals("066123456", q.rows[0].msisdn)
        assertEquals("Marquer envoyé", LedgerView.actionLabel("sent"))
    }
}
