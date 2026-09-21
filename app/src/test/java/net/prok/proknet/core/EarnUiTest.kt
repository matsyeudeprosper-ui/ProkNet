package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.15.2: the Gagner screen.
 *
 * The old screen put a price policy, a bundle form, a fee note, two switches and a relay
 * panel in front of the button that actually does something. These tests hold the new one
 * to the rule it was rebuilt around: **one state, one number, one button**, and anything
 * an expert might want folded away where a first-time user never meets it.
 */
class EarnUiTest {

    // ================= one state =================

    @Test
    fun the_screen_has_exactly_four_states_and_reads_each_one_plainly() {
        assertEquals(EarnUi.Stage.NO_SOURCE, EarnUi.stage(sellerOn = false, hasSource = false, hasCustomer = false))
        assertEquals(EarnUi.Stage.READY, EarnUi.stage(sellerOn = false, hasSource = true, hasCustomer = false))
        assertEquals(EarnUi.Stage.WAITING, EarnUi.stage(sellerOn = true, hasSource = true, hasCustomer = false))
        assertEquals(EarnUi.Stage.SERVING, EarnUi.stage(sellerOn = true, hasSource = true, hasCustomer = true))
        // sharing already means a source exists, whatever the upstream probe says this second
        assertEquals(EarnUi.Stage.WAITING, EarnUi.stage(sellerOn = true, hasSource = false, hasCustomer = false))
    }

    @Test
    fun a_phone_with_nothing_to_share_says_why_and_offers_no_button() {
        val h = EarnUi.hero(EarnUi.Stage.NO_SOURCE, "", "", "", "Le Bluetooth est éteint.")
        assertEquals("", h.button)
        assertTrue(h.title.contains("Pas encore"))
        assertEquals("Le Bluetooth est éteint.", h.subtitle)
        // and without a specific blocker it still explains itself rather than going blank
        val vague = EarnUi.hero(EarnUi.Stage.NO_SOURCE, "", "", "", "")
        assertTrue(vague.subtitle.contains("Wi-Fi"))
        assertFalse(vague.subtitle.isEmpty())
    }

    @Test
    fun the_resting_state_is_an_offer_not_a_form() {
        val h = EarnUi.hero(EarnUi.Stage.READY, "Wi-Fi Freebox", "Environ 3 CFA par personne", "", "")
        assertEquals("Partagez votre Internet", h.title)
        assertEquals("Commencer", h.button)
        assertFalse(h.destructive)
        assertTrue("the money reason belongs in the first sentence", h.subtitle.contains("Gagnez de l'argent"))
        // the source and the estimate are one quiet line, not two cards
        assertEquals("Wi-Fi Freebox · Environ 3 CFA par personne", h.footnote)
        assertFalse("no live figures before anything is shared", EarnUi.showsLiveStats(EarnUi.Stage.READY))
    }

    @Test
    fun sharing_says_what_is_happening_and_stopping_is_never_alarming() {
        val waiting = EarnUi.hero(EarnUi.Stage.WAITING, "Wi-Fi Freebox", "", "", "")
        assertEquals("Vous partagez", waiting.title)
        assertEquals("Arrêter le partage", waiting.button)
        assertTrue(waiting.destructive)
        assertTrue(waiting.subtitle.contains("payé"))
        assertEquals("one sentence", 1, waiting.subtitle.count { it == '.' })
        assertTrue(EarnUi.showsLiveStats(EarnUi.Stage.WAITING))

        val serving = EarnUi.hero(EarnUi.Stage.SERVING, "Wi-Fi Freebox", "", "Prosper utilise votre Internet", "")
        assertEquals("Quelqu'un utilise votre Internet", serving.title)
        assertEquals("Prosper utilise votre Internet", serving.subtitle)
        assertEquals(waiting.button, serving.button)
        assertTrue(EarnUi.showsLiveStats(EarnUi.Stage.SERVING))
        // even with no name we say something rather than leaving it blank
        assertTrue(EarnUi.hero(EarnUi.Stage.SERVING, "", "", "", "").subtitle.isNotEmpty())
    }

    @Test
    fun every_state_gives_at_most_one_button() {
        for (stage in EarnUi.Stage.values()) {
            val h = EarnUi.hero(stage, "Wi-Fi", "3 CFA", "Prosper", "")
            assertTrue(h.title.isNotEmpty())
            assertTrue(h.subtitle.isNotEmpty())
            assertTrue("one sentence, not a paragraph", h.subtitle.count { it == '.' } <= 1)
        }
    }

    // ================= one set of figures =================

    @Test
    fun the_live_figures_count_people_not_clients() {
        val stats = EarnUi.liveStats(1, "2,4 Mo", "11 CFA")
        assertEquals(3, stats.size)
        assertEquals("1", stats[0].value)
        assertEquals("Personne connectée", stats[0].label)
        assertEquals("11 CFA", stats[2].value)
        assertEquals("Gagné", stats[2].label)
        // plural when there really are several
        assertEquals("Personnes connectées", EarnUi.liveStats(3, "x", "y")[0].label)
        // and nobody connected is still singular, not "0 clients"
        assertEquals("Personne connectée", EarnUi.liveStats(0, "x", "y")[0].label)
    }

    @Test
    fun earnings_lead_with_today_and_link_once_to_the_wallet() {
        val e = EarnUi.earnings(6_400, 3_700, 12)
        assertEquals("Gagné aujourd'hui", e.todayLabel)
        assertEquals(Market.cfa(6_400), e.today)
        assertEquals("À recevoir · " + Market.cfa(3_700), e.receivable)
        assertEquals("12 partages au total", e.history)
        assertEquals("Voir le Wallet", e.link)
        // nothing owed yet: the line disappears rather than showing a zero
        assertEquals("", EarnUi.earnings(0, 0, 0).receivable)
        assertEquals("", EarnUi.earnings(0, 0, 0).history)
        assertEquals("1 partage au total", EarnUi.earnings(0, 0, 1).history)
    }

    // ================= everything else, folded away =================

    @Test
    fun the_settings_summary_shows_the_state_without_opening_anything() {
        assertEquals("Équilibré", EarnUi.settingsSummary(Pricing.SellerPolicy.BALANCED, false, false, false))
        assertEquals("Moins cher · Alertes activées · Carte partagée · Relais activé",
            EarnUi.settingsSummary(Pricing.SellerPolicy.CHEAPER, true, true, true))
        assertEquals("Gagner plus · Alertes activées",
            EarnUi.settingsSummary(Pricing.SellerPolicy.EARN_MORE, true, false, false))
    }

    @Test
    fun each_price_choice_explains_itself_in_one_line() {
        val words = Pricing.SellerPolicy.values().map { EarnUi.policyWord(it) }
        val hints = Pricing.SellerPolicy.values().map { EarnUi.policyHint(it) }
        assertEquals("the three choices must be distinguishable", 3, words.toSet().size)
        assertEquals(3, hints.toSet().size)
        for (h in hints) {
            assertTrue(h.isNotEmpty())
            assertTrue("one line, not a paragraph", h.count { it == '.' } <= 1)
            assertFalse("a person does not think in megabytes", Regex("(^|[^A-Za-z])(Mo|MB)([^A-Za-z]|$)").containsMatchIn(h))
        }
        assertTrue(EarnUi.policyHint(Pricing.SellerPolicy.EARN_MORE).contains("gagnez plus"))
    }

    @Test
    fun a_waiting_neighbour_outranks_everything_but_not_while_already_sharing() {
        assertTrue(EarnUi.showDemand(waiting = 2, sellerOn = false))
        assertFalse("nobody needs the prompt once they are sharing", EarnUi.showDemand(waiting = 2, sellerOn = true))
        assertFalse(EarnUi.showDemand(waiting = 0, sellerOn = false))
    }

    // ================= the screen stays readable =================

    @Test
    fun nothing_on_the_gagner_screen_is_technical() {
        val texts = ArrayList<String>()
        for (stage in EarnUi.Stage.values()) {
            val h = EarnUi.hero(stage, "Wi-Fi Freebox", "Environ 3 CFA par personne", "Prosper", "")
            texts += listOf(h.title, h.subtitle, h.button, h.footnote)
        }
        texts += EarnUi.liveStats(2, "2,4 Mo", "11 CFA").flatMap { listOf(it.label, it.value) }
        val e = EarnUi.earnings(6_400, 3_700, 12)
        texts += listOf(e.today, e.todayLabel, e.receivable, e.history, e.link)
        texts += listOf(EarnUi.SETTINGS_TITLE, EarnUi.settingsSummary(Pricing.SellerPolicy.BALANCED, true, true, true))
        for (t in texts) {
            assertFalse(t + " leaks an enum", t.contains("BALANCED") || t.contains("SERVING") || t.contains("READY"))
            assertFalse(t + " uses an accounting word", t.lowercase().contains("client"))
            assertFalse(t + " mentions a protocol", t.contains("Bluetooth L2CAP") || t.contains("BLE") || t.contains("VPN"))
            val letters = t.filter { it.isLetter() }
            assertFalse(t + " shouts", letters.length > 4 && letters == letters.uppercase())
        }
    }
}
