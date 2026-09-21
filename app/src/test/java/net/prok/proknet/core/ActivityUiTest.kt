package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.15.2: the Activité screen.
 *
 * Activité answers *what happened*. The Wallet answers *what money needs attention*. The
 * old screen tried to do both and did neither well, so these tests hold the split.
 */
class ActivityUiTest {
    private val now = 1_700_000_000_000L
    private val today = Wallet.startOfDay(now)

    private fun row(kind: ActivityUi.Kind, amount: Long, settled: Boolean = false, free: Boolean = false,
                    peer: String = "ab12cd34", time: String = "14:32"): ActivityUi.Row {
        val seller = kind == ActivityUi.Kind.SHARED
        return ActivityUi.Row("sess", kind, ActivityUi.title(kind),
            ActivityUi.subtitle(WalletUi.shortName(peer), time),
            ActivityUi.amountWord(amount, seller), seller,
            ActivityUi.chip(settled, free, seller), ActivityUi.tone(settled, free))
    }

    @Test
    fun a_row_says_what_happened_with_whom_and_how_much() {
        val bought = row(ActivityUi.Kind.BOUGHT, 1_200)
        assertEquals("Internet utilisé", bought.title)
        assertEquals("Prok AB12 · 14:32", bought.subtitle)
        assertEquals("-" + Market.cfa(1_200), bought.amount)
        assertFalse(bought.positive)
        assertEquals("En attente", bought.chip)

        val shared = row(ActivityUi.Kind.SHARED, 1_100, settled = true)
        assertEquals("Internet partagé", shared.title)
        assertEquals("+" + Market.cfa(1_100), shared.amount)
        assertTrue(shared.positive)
        assertEquals("Reçu ✓", shared.chip)
        assertEquals(WalletUi.Tone.GOOD, shared.tone)
    }

    @Test
    fun a_free_session_says_gratuit_rather_than_showing_a_zero() {
        // "0 CFA" reads like something went wrong; free Internet is a feature
        val free = row(ActivityUi.Kind.BOUGHT, 0, free = true)
        assertEquals("Gratuit", free.amount)
        assertEquals("Gratuit", free.chip)
        assertEquals(WalletUi.Tone.NEUTRAL, free.tone)
        assertEquals("Gratuit", ActivityUi.amountWord(0, earning = true))
        assertEquals("Gratuit", ActivityUi.amountWord(-5, earning = false))
    }

    @Test
    fun the_words_match_the_wallet_so_the_two_screens_never_disagree() {
        assertEquals(WalletUi.chip(Settlement.Status.CONFIRMED, true), ActivityUi.chip(settled = true, free = false, iAmSeller = true))
        assertEquals(WalletUi.chip(Settlement.Status.CONFIRMED, false), ActivityUi.chip(settled = true, free = false, iAmSeller = false))
        assertEquals(WalletUi.chip(Settlement.Status.PENDING, false), ActivityUi.chip(settled = false, free = false, iAmSeller = false))
    }

    @Test
    fun history_is_grouped_by_day_newest_first() {
        val rows = listOf(
            (today - 5_000) to row(ActivityUi.Kind.BOUGHT, 400),
            (today + 3_600_000) to row(ActivityUi.Kind.SHARED, 900),
            (today + 60_000) to row(ActivityUi.Kind.BOUGHT, 300))
        val groups = ActivityUi.group(rows, now)
        assertEquals(2, groups.size)
        assertEquals("Aujourd'hui", groups[0].label)
        assertEquals(2, groups[0].rows.size)
        assertEquals("Hier", groups[1].label)
        // and the same labels the Wallet uses, so the two histories read alike
        assertEquals(WalletUi.dayLabel(today + 60_000, now), groups[0].label)
    }

    @Test
    fun an_empty_history_is_an_invitation_not_a_blank_panel() {
        assertTrue(ActivityUi.group(emptyList(), now).isEmpty())
        assertTrue(ActivityUi.EMPTY_TITLE.isNotEmpty())
        assertTrue(ActivityUi.EMPTY_BODY.contains("apparaîtront ici"))
        assertFalse(ActivityUi.EMPTY_BODY.lowercase().contains("session"))
    }

    @Test
    fun nothing_on_the_activity_screen_is_technical() {
        val texts = ArrayList<String>()
        for (k in ActivityUi.Kind.values()) texts.add(ActivityUi.title(k))
        val r = row(ActivityUi.Kind.SHARED, 1_100, settled = true, peer = "24e480e6a1b2c3d4")
        texts += listOf(r.title, r.subtitle, r.amount, r.chip)
        texts += listOf(ActivityUi.EMPTY_TITLE, ActivityUi.EMPTY_BODY, ActivityUi.ACCOUNT_TITLE)
        for (t in texts) {
            assertFalse(t + " leaks a raw identity", t.contains("24e480e6a1b2c3d4"))
            assertFalse(t + " mentions megabytes", Regex("(^|[^A-Za-z])(Mo|MB)([^A-Za-z]|$)").containsMatchIn(t))
            assertFalse(t + " leaks an enum", t.contains("BOUGHT") || t.contains("SHARED") || t.contains("CONFIRMED"))
            assertFalse(t + " shows a session id", t.contains("sess"))
        }
        assertEquals("Prok 24E4", WalletUi.shortName("24e480e6a1b2c3d4"))
    }
}
