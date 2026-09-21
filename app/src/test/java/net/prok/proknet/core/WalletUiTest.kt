package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.15.1: every state the Wallet can be in, and the three promises it makes.
 *
 * One obvious action. No technical clutter. Honest money words.
 */
class WalletUiTest {
    private val now = 1_700_000_000_000L
    private val me = "24e480e6" + "a".repeat(24)
    private val seller = "bb17cd22" + "b".repeat(24)
    private val seller2 = "cc99ef00" + "c".repeat(24)

    private var n = 0
    private fun ob(buyer: String, sellerId: String, gross: Long,
                   status: Settlement.Status = Settlement.Status.PENDING, at: Long = now): Settlement.Obligation {
        val s = Market.split(gross, 5); n++
        return Settlement.Obligation("set" + n + "a".repeat(29), "sess" + n, buyer, sellerId, "cp" + n,
            s.gross, s.sellerNet, s.fee, at, at + Settlement.DEFAULT_TTL_MS, status)
    }

    private fun view(list: List<Settlement.Obligation>) = Wallet.view(list, me, now)

    /** The French megabyte as a standalone unit, or the English one. */
    private val megabyteUnit = Regex("(^|[^A-Za-z])(Mo|MB)([^A-Za-z]|$)")

    // ================= one obvious action =================

    @Test
    fun a_new_user_is_told_what_will_appear_here_not_shown_an_empty_button() {
        val a = WalletUi.primaryAction(emptyList(), me, view(emptyList()), hasReceivingMethod = false, isSeller = false)
        assertEquals(WalletUi.ActionKind.NOTHING_YET, a.kind)
        assertEquals("", a.button)
        assertTrue(a.detail.contains("apparaîtront ici"))
        assertEquals(WalletUi.Lead.NOTHING, WalletUi.overview(view(emptyList())).lead)
    }

    @Test
    fun a_buyer_owing_one_seller_gets_one_pay_button() {
        val list = listOf(ob(me, seller, 3_700))
        val a = WalletUi.primaryAction(list, me, view(list), false, false)
        assertEquals(WalletUi.ActionKind.PAY, a.kind)
        assertEquals("Paiement à effectuer", a.title)
        assertTrue(a.detail.contains("Prok BB17"))
        assertTrue(a.button.startsWith("Payer "))
        assertEquals(seller, a.counterpartyId)
        assertEquals(3_700, a.amountCentimes)
        assertEquals("one creditor means no secondary link", "", a.secondary)
        assertEquals(WalletUi.Lead.TO_PAY, WalletUi.overview(view(list)).lead)
    }

    @Test
    fun a_buyer_owing_several_sellers_is_shown_the_largest_first() {
        val list = listOf(ob(me, seller, 1_000), ob(me, seller2, 4_000), ob(me, seller, 500))
        val a = WalletUi.primaryAction(list, me, view(list), false, false)
        assertEquals(seller2, a.counterpartyId)
        assertEquals(4_000, a.amountCentimes)
        assertEquals("Voir les autres paiements", a.secondary)
    }

    @Test
    fun three_small_sessions_are_presented_as_one_grouped_payment() {
        val list = listOf(ob(me, seller, 300), ob(me, seller, 700), ob(me, seller, 500))
        val a = WalletUi.primaryAction(list, me, view(list), false, false)
        assertEquals(1_500, a.amountCentimes)
        assertTrue("the grouping must be explained", a.detail.contains("3 sessions"))

        val sheet = WalletUi.paymentSheet(Wallet.payableTo(list, me, seller), seller, "MTN Mobile Money")
        assertTrue(sheet.title.contains("15"))
        assertEquals(3, sheet.sessions.size)
        assertTrue(sheet.grouping.contains("regroupées"))
        assertEquals("Prok BB17", sheet.toValue)
        assertEquals(1_500, sheet.sessions.sumOf { it.second.filter { c -> c.isDigit() }.toLong() })
    }

    @Test
    fun a_seller_waiting_for_money_is_not_shown_a_pay_button() {
        val list = listOf(ob(seller, me, 5_200))
        val a = WalletUi.primaryAction(list, me, view(list), hasReceivingMethod = true, isSeller = true)
        assertEquals(WalletUi.ActionKind.AWAITING_PAYMENT, a.kind)
        assertEquals("", a.button)
        assertEquals("Voir les paiements", a.secondary)
        assertEquals(WalletUi.Lead.TO_RECEIVE, WalletUi.overview(view(list)).lead)
    }

    @Test
    fun a_seller_with_nowhere_to_be_paid_is_asked_to_set_that_up_first() {
        val a = WalletUi.primaryAction(emptyList(), me, view(emptyList()), hasReceivingMethod = false, isSeller = true)
        assertEquals(WalletUi.ActionKind.SET_UP_RECEIVING, a.kind)
        assertEquals("Configurer", a.button)
        // but a debt still outranks it: money I owe is more urgent than money I might earn
        val owing = listOf(ob(me, seller, 900))
        assertEquals(WalletUi.ActionKind.PAY,
            WalletUi.primaryAction(owing, me, view(owing), false, true).kind)
    }

    @Test
    fun once_everything_is_settled_the_screen_says_so_and_offers_nothing() {
        val list = listOf(ob(me, seller, 1_200, Settlement.Status.CONFIRMED),
            ob(seller, me, 800, Settlement.Status.CONFIRMED))
        val a = WalletUi.primaryAction(list, me, view(list), true, true)
        assertEquals(WalletUi.ActionKind.ALL_CLEAR, a.kind)
        assertEquals("Tout est à jour", a.title)
        assertEquals("", a.button)
    }

    // ================= no technical clutter =================

    @Test
    fun nothing_on_the_wallet_screen_exposes_engineering() {
        val list = listOf(
            ob(me, seller, 1_200),
            ob(seller, me, 800, Settlement.Status.PAYMENT_SEEN),
            ob(me, seller2, 400, Settlement.Status.DISPUTED),
            ob(me, seller, 300, Settlement.Status.FAILED),
            ob(seller2, me, 600, Settlement.Status.EXPIRED),
            ob(seller, me, 900, Settlement.Status.CONFIRMED))
        val w = view(list)
        val texts = ArrayList<String>()
        val o = WalletUi.overview(w)
        texts += listOf(o.toPay, o.toReceive, o.earnedToday, WalletUi.CUSTODY_NOTE, WalletUi.PRIVACY_NOTE)
        val a = WalletUi.primaryAction(list, me, w, true, true)
        texts += listOf(a.title, a.detail, a.button, a.secondary)
        for (g in WalletUi.history(list, me, now)) {
            texts += g.label
            for (r in g.rows) texts += listOf(r.title, r.counterparty, r.amount, r.chip)
        }
        val r = WalletUi.receiving(PaymentRails.Destination(Settlement.Rail.MTN_MOMO, "242060123456"))
        texts += listOf(r.title, r.detail, r.button)

        for (t in texts) {
            assertFalse(t + " leaks a status enum", t.contains("PENDING") || t.contains("PAYMENT_SEEN") ||
                t.contains("CONFIRMED") || t.contains("DISPUTED") || t.contains("EXPIRED") || t.contains("FAILED"))
            assertFalse(t + " leaks a raw identity", t.contains(me) || t.contains(seller))
            assertFalse(t + " leaks a settlement id", t.contains("set1") || t.contains("cp1"))
            // "Mo" as a UNIT, not as the start of a word: "MTN Mobile Money" is fine
            assertFalse(t + " mentions megabytes", megabyteUnit.containsMatchIn(t))
            assertFalse(t + " calls itself a balance", t.lowercase().contains("solde"))
        }
    }

    @Test
    fun an_identity_is_shown_as_something_a_person_can_recognise() {
        assertEquals("Prok 24E4", WalletUi.shortName(me))
        assertEquals("Prok 24E4", WalletUi.shortName("prok-" + me))
        assertEquals("Prok BB17", WalletUi.shortName(seller))
        assertEquals("Prok ?", WalletUi.shortName("ab"))
        // the full one survives, for the advanced section only
        assertTrue(WalletUi.fullName(me).startsWith("prok-"))
        assertTrue(WalletUi.fullName(me).contains(me))
    }

    @Test
    fun the_technical_detail_exists_but_only_where_it_is_asked_for() {
        val o = ob(me, seller, 1_200)
        val d = WalletUi.detail(o, me, 5_000, "21 septembre · 14:32")
        // the visible part is plain
        assertEquals("Internet", d.title)
        assertEquals("Prok BB17", d.withValue)
        assertEquals("En attente de paiement", d.statusValue)
        assertTrue(d.action.startsWith("Payer"))
        assertEquals(listOf("Budget maximum", "Utilisé", "Frais Prok", "Le vendeur reçoit"), d.lines.map { it.first })
        for ((_, v) in d.lines) assertFalse(v.contains("Mo"))
        // the engineering is present, and only here
        val keys = d.advanced.map { it.first }
        assertTrue(keys.contains("Identifiant de règlement"))
        assertTrue(keys.contains("Preuve d'usage"))
        assertEquals(o.settlementId, d.advanced.first { it.first == "Identifiant de règlement" }.second)
        // the fee is in the detail, not in the headline figures
        assertEquals(Market.cfa(o.prokFeeCentimes), d.lines.first { it.first == "Frais Prok" }.second)
    }

    // ================= honest money words =================

    @Test
    fun paid_is_only_said_when_a_payment_was_verified() {
        assertEquals("À vérifier", WalletUi.chip(Settlement.Status.PAYMENT_SEEN, false))
        assertEquals("En attente", WalletUi.chip(Settlement.Status.PAYMENT_INITIATED, false))
        assertEquals("Payé ✓", WalletUi.chip(Settlement.Status.CONFIRMED, false))
        assertEquals("Reçu ✓", WalletUi.chip(Settlement.Status.CONFIRMED, true))
        assertEquals("Contesté", WalletUi.chip(Settlement.Status.DISPUTED, false))
        // the sentence after a manual reference must not imply payment
        val s = WalletUi.referenceAccepted("ABC123")
        assertTrue(s.contains("En attente de vérification"))
        assertFalse(s.contains("Payé"))
        assertFalse(s.contains("Reçu"))
    }

    @Test
    fun every_status_reads_as_french_and_carries_a_tone() {
        for (st in Settlement.Status.values()) {
            for (seller in listOf(true, false)) {
                val c = WalletUi.chip(st, seller)
                assertTrue(c.isNotEmpty())
                assertFalse(c, c.any { it in 'A'..'Z' && c.count { ch -> ch in 'A'..'Z' } > 2 })
            }
            assertTrue(WalletUi.statusSentence(st, false).isNotEmpty())
        }
        assertEquals(WalletUi.Tone.GOOD, WalletUi.tone(Settlement.Status.CONFIRMED))
        assertEquals(WalletUi.Tone.ATTENTION, WalletUi.tone(Settlement.Status.DISPUTED))
        assertEquals(WalletUi.Tone.NEUTRAL, WalletUi.tone(Settlement.Status.PENDING))
    }

    @Test
    fun the_note_under_the_figures_stops_anyone_reading_them_as_a_balance() {
        assertTrue(WalletUi.CUSTODY_NOTE.contains("ne détient pas"))
        assertFalse(WalletUi.CUSTODY_NOTE.lowercase().contains("solde"))
        assertTrue(WalletUi.PRIVACY_NOTE.contains("jamais diffusé"))
        // one sentence, not a paragraph
        assertTrue(WalletUi.PRIVACY_NOTE.count { it == '.' } <= 1)
    }

    // ================= history =================

    @Test
    fun history_is_grouped_by_day_and_signed_by_direction() {
        val today = Wallet.startOfDay(now)
        val list = listOf(
            ob(seller, me, 1_200, Settlement.Status.CONFIRMED, at = today + 3_600_000),
            ob(me, seller, 1_300, at = today + 1_800_000),
            ob(seller2, me, 2_500, at = today - 5_000))
        val groups = WalletUi.history(list, me, now)
        assertEquals(2, groups.size)
        assertEquals("Aujourd'hui", groups[0].label)
        assertEquals("Hier", groups[1].label)
        assertEquals(2, groups[0].rows.size)

        val earning = groups[0].rows.first { it.positive }
        assertEquals("Internet partagé", earning.title)
        assertTrue(earning.amount.startsWith("+"))
        assertEquals("Reçu ✓", earning.chip)

        val spend = groups[0].rows.first { !it.positive }
        assertEquals("Internet", spend.title)
        assertTrue(spend.amount.startsWith("-"))
        assertEquals("En attente", spend.chip)
    }

    @Test
    fun the_wallet_shows_money_events_only_and_never_somebody_elses() {
        val list = listOf(ob(seller, seller2, 9_000), ob(me, seller, 400))
        val groups = WalletUi.history(list, me, now)
        assertEquals(1, groups.size)
        assertEquals(1, groups[0].rows.size)
        assertEquals("Internet", groups[0].rows[0].title)
    }

    @Test
    fun receiving_reads_clearly_whether_or_not_it_is_configured() {
        val none = WalletUi.receiving(null)
        assertFalse(none.configured)
        assertEquals("Configurer", none.button)
        assertTrue(none.detail.contains("Mobile Money"))

        val mtn = WalletUi.receiving(PaymentRails.Destination(Settlement.Rail.MTN_MOMO, "242060123456"))
        assertTrue(mtn.configured)
        assertEquals("MTN Mobile Money", mtn.title)
        assertEquals("•••••456", mtn.detail)
        assertEquals("Modifier", mtn.button)
        assertFalse("the full number must never appear", mtn.detail.contains("242060"))

        assertEquals("Airtel Money",
            WalletUi.receiving(PaymentRails.Destination(Settlement.Rail.AIRTEL_MONEY, "242050000123")).title)
    }
}
