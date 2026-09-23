package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.17.6: the idle provider that went dark overnight.
 *
 * The twin of the v0.17.5 defect, and the more damaging of the two, because it broke the
 * exact promise v0.17.3 was built on: leave the app closed, and ProkNet will wake you
 * when somebody near you needs Internet.
 *
 * Two faults, stacked:
 *
 *  1. Location updates were requested in the ACTIVITY's `onForeground` and cancelled in
 *     `onBackground`. A fix is usable for thirty minutes, so a provider who put the phone
 *     in a pocket had no zone half an hour later - and no zone means no presence, which
 *     means the Brain cannot see them at all.
 *
 *  2. Updates asked for a minimum movement of 300 m. A phone sitting still in a house
 *     therefore received NO updates whatever, so the fix went stale even with the app
 *     open. The idle provider is by definition the phone that is not moving, so the one
 *     case that mattered was the one case that could not work.
 */
class ZoneWatchTest {

    private val now = 1_700_000_000_000L

    // ================= when a position is held at all =================

    @Test fun a_phone_that_is_neither_offering_nor_looking_is_not_tracked() {
        assertFalse(ZoneWatch.needed(optedInToShare = false, sharing = false, looking = false))
        assertEquals(ZoneWatch.Why.NOT_NEEDED,
            ZoneWatch.why(optedInToShare = false, sharing = false, looking = false))
        // we have no business knowing where somebody is who is not taking part
        assertTrue(ZoneWatch.diag(ZoneWatch.Why.NOT_NEEDED).contains("not tracking"))
    }

    @Test fun opting_in_to_be_woken_is_enough_on_its_own() {
        // THE case. A provider that is opted in but NOT sharing and NOT looking must be
        // tracked, because being discoverable while idle is the entire product promise.
        assertTrue(ZoneWatch.needed(optedInToShare = true, sharing = false, looking = false))
        assertEquals(ZoneWatch.Why.WILLING_TO_SHARE,
            ZoneWatch.why(optedInToShare = true, sharing = false, looking = false))
    }

    @Test fun sharing_and_looking_each_need_a_position_too() {
        assertTrue(ZoneWatch.needed(false, sharing = true, looking = false))
        assertTrue(ZoneWatch.needed(false, sharing = false, looking = true))
        assertEquals(ZoneWatch.Why.SHARING, ZoneWatch.why(false, sharing = true, looking = false))
        assertEquals(ZoneWatch.Why.LOOKING, ZoneWatch.why(false, sharing = false, looking = true))
        // sharing is the strongest reason and is reported first
        assertEquals(ZoneWatch.Why.SHARING, ZoneWatch.why(true, sharing = true, looking = true))
    }

    @Test fun being_on_screen_is_not_one_of_the_reasons() {
        // the bug, stated as a property: nothing in this decision is about the app being
        // visible. Every combination that needs a position needs it with the app closed.
        for (opted in listOf(true, false))
            for (sharing in listOf(true, false))
                for (looking in listOf(true, false))
                    assertEquals("opted=$opted sharing=$sharing looking=$looking",
                        opted || sharing || looking,
                        ZoneWatch.needed(opted, sharing, looking))
    }

    // ================= a stationary phone must stay fresh =================

    @Test fun a_phone_that_never_moves_still_refreshes() {
        // build 73 asked for 300 m of movement before delivering an update, so a provider
        // sitting at home received none at all and expired where it was most useful.
        assertEquals("time must be the only interval", 0f, ZoneWatch.MIN_DISTANCE_M, 0f)
        assertTrue("and it must arrive well inside the age limit",
            ZoneWatch.MIN_TIME_MS * 2 <= ZoneWatch.MAX_AGE_MS)
    }

    @Test fun a_fix_is_good_for_half_an_hour_and_not_a_minute_more() {
        assertTrue(ZoneWatch.fresh(now, now))
        assertTrue(ZoneWatch.fresh(now - ZoneWatch.MAX_AGE_MS, now))
        assertFalse(ZoneWatch.fresh(now - ZoneWatch.MAX_AGE_MS - 1, now))
        // never having had one is not freshness
        assertFalse(ZoneWatch.fresh(0, now))
    }

    @Test fun the_diagnostic_can_show_how_long_is_left() {
        assertEquals(30, ZoneWatch.minutesLeft(now, now))
        assertEquals(20, ZoneWatch.minutesLeft(now - 10 * 60_000L, now))
        assertEquals(0, ZoneWatch.minutesLeft(now - ZoneWatch.MAX_AGE_MS, now))
        assertEquals("expired is not negative", 0, ZoneWatch.minutesLeft(now - 99 * 60_000L, now))
        assertEquals(0, ZoneWatch.minutesLeft(0, now))
    }

    @Test fun every_reason_says_something_in_the_diagnostic() {
        for (w in ZoneWatch.Why.values()) assertTrue(w.name, ZoneWatch.diag(w).isNotEmpty())
    }

    // ================= why it matters, against the real gatekeeper =================

    @Test fun a_stale_fix_costs_the_provider_its_presence() {
        val e = ProviderActivation.eligibility(
            optIn = true, upstreamType = Tunnel.UP_WIFI, upstreamValidated = true,
            bulkSupported = true, bluetoothOn = true,
            alreadySharing = false, busy = false, sellPriceCentimesPerMb = 500)

        // with a fresh fix the phone has a zone and is discoverable
        val zone = if (ZoneWatch.fresh(now - 60_000L, now)) "z2431:337" else CoverageModel.NO_ZONE
        val awake = ProviderPresence.of(zone, e, false, 0, true, ProviderPresence.Intent.COMMERCIAL)
        assertNotNull(awake)
        assertTrue(awake!!.shouldPublish)

        // and once it expires - which on build 73 happened 30 minutes after the screen
        // went off - the very same phone publishes nothing at all
        val staleZone = if (ZoneWatch.fresh(now - 31 * 60_000L, now)) "z2431:337" else CoverageModel.NO_ZONE
        assertEquals(CoverageModel.NO_ZONE, staleZone)
        assertNull("no zone, no presence, no buyer",
            ProviderPresence.of(staleZone, e, false, 0, true, ProviderPresence.Intent.COMMERCIAL))
    }

    @Test fun the_overnight_idle_provider_now_survives() {
        // eight hours in a pocket, opted in, not sharing, not looking. The service holds
        // a fix every MIN_TIME_MS, so the newest one is always well inside MAX_AGE_MS.
        assertTrue(ZoneWatch.needed(optedInToShare = true, sharing = false, looking = false))
        var t = now
        val end = now + 8 * 3_600_000L
        var lastFix = now
        while (t < end) {
            t += ZoneWatch.MIN_TIME_MS
            lastFix = t                       // a stationary phone still gets one, since MIN_DISTANCE_M is 0
            assertTrue("went dark at " + ((t - now) / 60_000) + " min", ZoneWatch.fresh(lastFix, t))
        }
        assertTrue(ZoneWatch.fresh(lastFix, end))
    }
}
