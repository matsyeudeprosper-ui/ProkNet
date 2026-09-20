package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.14.2: stopping, as a machine.
 *
 * The hardware failure this comes from was pure ordering: the final control message was
 * sent and the Bluetooth link closed in the next statement, so the transport reported a
 * failure our own shutdown had caused. `TunnelClient` and `Gateway` drive these exact
 * functions, so what is asserted here is what the phones do.
 */
class TeardownTest {

    private fun live() = Teardown.running()

    @Test
    fun a_normal_stop_waits_for_the_closing_figure_then_lets_everything_close() {
        val s = live()
        assertEquals(Teardown.Phase.RUNNING, s.phase)
        assertFalse("nothing may close while a session is live", s.mayClose)

        val stopping = Teardown.begin(s, "stopped by user", canReachPeer = true)
        assertEquals(Teardown.Phase.SETTLING, stopping.phase)
        assertEquals(Teardown.Final.WAITING, stopping.final)
        assertFalse("the transport must stay up while the figure is outstanding", stopping.mayClose)

        val done = Teardown.onFinalSigned(stopping)
        assertEquals(Teardown.Phase.IDLE, done.phase)
        assertEquals(Teardown.Final.PASS, done.final)
        assertTrue("only now may the link be closed", done.mayClose)
        assertTrue("a stop the user asked for is not an error", Teardown.isClean(done))
    }

    @Test
    fun stopping_twice_settles_once() {
        // the user taps Stop twice, or the screen and the system lifecycle both clean up
        val first = Teardown.begin(live(), "stopped by user", true)
        val second = Teardown.begin(first, "stopped by user", true)
        assertTrue("the second stop must change nothing", first === second)
        assertEquals(first.token, second.token)
        // and the settlement still happens exactly once
        val done = Teardown.onFinalSigned(second)
        assertEquals(Teardown.Final.PASS, done.final)
        val again = Teardown.onFinalSigned(done)
        assertTrue("a second closing figure cannot re-settle", done === again)
        // a stop when there was nothing running is also harmless
        val idle = Teardown.begin(Teardown.State(), "stopped by user", true)
        assertEquals(Teardown.Phase.IDLE, idle.phase)
        assertEquals(Teardown.Final.NONE, idle.final)
    }

    @Test
    fun an_old_timer_cannot_end_the_session_that_came_after_it() {
        // this is the stale-callback rule: session A arms a timer, A ends, B starts,
        // A's timer fires. B must not notice.
        val a = Teardown.begin(live(), "stopped by user", true)
        val staleToken = a.token
        val aDone = Teardown.onFinalSigned(a)
        val b = Teardown.begin(Teardown.running(aDone), "stopped by user", true)
        assertNotEquals("a new stop carries a new token", staleToken, b.token)

        val afterStale = Teardown.onTimeout(b, staleToken)
        assertTrue("the old timer must be ignored", b === afterStale)
        assertEquals(Teardown.Phase.SETTLING, afterStale.phase)

        // b's own timer still works
        val afterOwn = Teardown.onTimeout(b, b.token)
        assertEquals(Teardown.Phase.IDLE, afterOwn.phase)
        assertEquals(Teardown.Final.TIMEOUT, afterOwn.final)
        assertTrue(afterOwn.mayClose)
        // and a callback from a finished session is never accepted
        assertFalse(Teardown.accepts(afterOwn, staleToken))
        assertFalse("nothing belongs to an idle session", Teardown.accepts(afterOwn, afterOwn.token))
        assertTrue(Teardown.accepts(b, b.token))
    }

    @Test
    fun a_peer_that_has_already_gone_does_not_hold_the_phone_open() {
        // the link is down before Stop: there is nobody to sign a closing figure with
        val noLink = Teardown.begin(live(), "stopped by user", canReachPeer = false)
        assertEquals(Teardown.Phase.IDLE, noLink.phase)
        assertEquals(Teardown.Final.UNAVAILABLE, noLink.final)
        assertTrue("finish locally rather than wait for a peer that is gone", noLink.mayClose)

        // or the link dies during the graceful window
        val stopping = Teardown.begin(live(), "stopped by user", true)
        val gone = Teardown.onLinkGone(stopping, "Bluetooth off")
        assertEquals(Teardown.Phase.IDLE, gone.phase)
        assertEquals(Teardown.Final.UNAVAILABLE, gone.final)
        assertTrue("a link closing during our own stop is not a fault", Teardown.isClean(gone))
    }

    @Test
    fun a_link_that_dies_while_running_is_a_real_failure() {
        // the other half of the rule: we did NOT ask for this one
        val broke = Teardown.onLinkGone(live(), "connection closed")
        assertEquals(Teardown.Phase.IDLE, broke.phase)
        assertEquals("connection closed", broke.reason)
        // and nothing happens to a session already finished
        val idle = Teardown.State()
        assertTrue(Teardown.onLinkGone(idle, "whatever") === idle)
    }

    @Test
    fun the_words_the_diagnostic_prints_are_all_distinct() {
        val seen = Teardown.Final.values().map { Teardown.word(it) }
        assertEquals(seen.size, seen.toSet().size)
        assertEquals("PASS", Teardown.word(Teardown.Final.PASS))
        assertEquals("timeout", Teardown.word(Teardown.Final.TIMEOUT))
        assertEquals("unavailable", Teardown.word(Teardown.Final.UNAVAILABLE))
    }
}
