package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.17.10: the defect that made the network look empty even when it was not.
 *
 * `NetworkBrainSync` declared POLL_MS = 5 s and IDLE_POLL_MS = 30 s with comments saying
 * exactly what they were for. Neither was used anywhere, so everything ran on the
 * service's flat fifteen-minute sweep - against a server presence window of **120
 * seconds**.
 *
 * A provider was therefore visible for two minutes in every fifteen. Every inspection of
 * the Brain found a stale presence, and that was blamed in turn on the app being closed,
 * the zone expiring, and the foreground-service type. All three were real and all three
 * were fixed; this one sat underneath them and would have kept the network broken on its
 * own.
 */
class SyncCadenceTest {

    // ================= the property the defect broke =================

    @Test fun any_state_that_publishes_a_presence_keeps_it_alive() {
        // THE test. A cadence that talks to the Brain less often than it expires a
        // presence is a provider nobody can find.
        for (w in listOf(SyncCadence.Why.PROVIDER, SyncCadence.Why.BUYER_WAITING))
            assertTrue(w.name + " must keep a presence alive",
                SyncCadence.keepsPresenceAlive(w))
    }

    @Test fun the_provider_cadence_leaves_room_for_lost_requests() {
        // not once per window - that leaves no margin at all. Three heartbeats may be
        // lost to a bad moment of signal before this phone falls out of the network.
        assertTrue(SyncCadence.PROVIDER_MS * 4 <= SyncCadence.PRESENCE_TTL_MS)
    }

    @Test fun the_old_fifteen_minute_sweep_would_fail_this() {
        // build 77's actual behaviour, recomputed here so a later simplification back to
        // a flat interval fails in this file rather than in Congo
        val build77 = 15 * 60_000L
        assertTrue("fifteen minutes against a two-minute window", build77 > SyncCadence.PRESENCE_TTL_MS)
        assertTrue("and the fix is comfortably inside it", SyncCadence.PROVIDER_MS < SyncCadence.PRESENCE_TTL_MS)
    }

    // ================= who gets which cadence =================

    @Test fun a_waiting_buyer_is_answered_in_seconds_not_minutes() {
        assertEquals(SyncCadence.Why.BUYER_WAITING,
            SyncCadence.why(buyerWaiting = true, providerWilling = false, sharing = false))
        assertEquals(SyncCadence.BUYER_WAITING_MS,
            SyncCadence.nextDelayMs(buyerWaiting = true, providerWilling = false, sharing = false))
        // somebody is watching the screen; a quarter of an hour is not an answer
        assertTrue(SyncCadence.BUYER_WAITING_MS <= 10_000L)
    }

    @Test fun an_idle_willing_provider_stays_visible() {
        // the v0.17.3 promise: opted in, not sharing, still findable
        assertEquals(SyncCadence.Why.PROVIDER,
            SyncCadence.why(buyerWaiting = false, providerWilling = true, sharing = false))
        assertEquals(SyncCadence.PROVIDER_MS,
            SyncCadence.nextDelayMs(buyerWaiting = false, providerWilling = true, sharing = false))
    }

    @Test fun a_sharing_phone_is_on_the_provider_cadence_too() {
        assertEquals(SyncCadence.Why.PROVIDER,
            SyncCadence.why(buyerWaiting = false, providerWilling = false, sharing = true))
    }

    @Test fun a_phone_doing_nothing_is_left_alone() {
        // battery is spent only while somebody is waiting or offering
        assertEquals(SyncCadence.Why.QUIET,
            SyncCadence.why(buyerWaiting = false, providerWilling = false, sharing = false))
        assertEquals(SyncCadence.QUIET_MS,
            SyncCadence.nextDelayMs(buyerWaiting = false, providerWilling = false, sharing = false))
        assertTrue("and that is a long sleep", SyncCadence.QUIET_MS >= 10 * 60_000L)
    }

    @Test fun waiting_for_an_answer_outranks_offering_one() {
        // a phone that is both must answer the person staring at the screen
        assertEquals(SyncCadence.Why.BUYER_WAITING,
            SyncCadence.why(buyerWaiting = true, providerWilling = true, sharing = true))
    }

    @Test fun every_state_says_what_it_is_doing() {
        for (w in SyncCadence.Why.values()) {
            assertTrue(w.name, SyncCadence.diag(w).isNotEmpty())
            assertTrue(w.name + " must have a positive delay", SyncCadence.delayFor(w) > 0)
        }
    }

    // ================= the numbers that were already written down =================

    @Test fun the_cadences_are_the_ones_the_comments_always_promised() {
        // NetworkBrainSync declared these and nothing used them. They are the same
        // numbers, finally running.
        assertEquals(5_000L, SyncCadence.BUYER_WAITING_MS)
        assertEquals(30_000L, SyncCadence.PROVIDER_MS)
    }
}
