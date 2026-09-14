package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wi-Fi link state machine: negotiation, handshake, loss, retry - no hardware needed. */
class LinkStateTest {

    @Test
    fun initiator_happy_path() {
        val l = LinkState()
        assertEquals(LinkState.Action.SEND_REQUEST, l.request("bbbbbbbb", 0))
        assertEquals(LinkState.State.REQUESTING, l.state)
        assertEquals(LinkState.Action.NONE, l.request("cccccccc", 1))          // busy: ignored
        assertEquals(LinkState.Action.NONE, l.offerReceived("cccccccc", 2))    // offer from someone else: ignored
        assertEquals(LinkState.Action.JOIN_NETWORK, l.offerReceived("bbbbbbbb", 3))
        assertEquals(LinkState.Action.OPEN_SOCKET, l.networkAvailable(4))
        assertEquals(LinkState.Action.NONE, l.handshakeOk("bbbbbbbb", 5))
        assertTrue(l.isUp)
        assertEquals("bbbbbbbb", l.peer)
        assertEquals(LinkState.Action.NONE, l.request("bbbbbbbb", 6))         // already up with that peer
    }

    @Test
    fun host_happy_path_and_tie_break() {
        val h = LinkState()
        assertEquals(LinkState.Action.START_HOTSPOT, h.requestReceived("aaaaaaaa", "bbbbbbbb", 0))
        assertEquals(LinkState.Action.SEND_OFFER, h.hotspotUp(1))
        assertEquals(LinkState.Action.NONE, h.clientConnected(2))
        assertEquals(LinkState.State.HANDSHAKE, h.state)
        h.handshakeOk("aaaaaaaa", 3)
        assertTrue(h.isUp)
        // both requested at once: the LOWER id hosts
        val low = LinkState(); low.request("zzzzzzzz", 0)
        assertEquals(LinkState.Action.START_HOTSPOT, low.requestReceived("zzzzzzzz", "aaaaaaaa", 1))
        val high = LinkState(); high.request("aaaaaaaa", 0)
        assertEquals(LinkState.Action.NONE, high.requestReceived("aaaaaaaa", "zzzzzzzz", 1))
        assertEquals(LinkState.State.REQUESTING, high.state)
    }

    @Test
    fun loss_teardown_and_retry_backoff() {
        val l = LinkState()
        l.request("bbbbbbbb", 0); l.offerReceived("bbbbbbbb", 1); l.networkAvailable(2); l.handshakeOk("bbbbbbbb", 3)
        assertEquals(LinkState.Action.TEARDOWN, l.fail("socket closed", 10))
        assertEquals(LinkState.State.DOWN, l.state)
        assertEquals(0, l.failures)                       // a link that was UP and dropped is not a failed attempt
        assertTrue(l.canRetry(10))
        // failed attempts back off: 5 s, 10 s, 20 s ... capped at 60 s
        l.request("bbbbbbbb", 11); l.fail("no offer", 12)
        assertEquals(1, l.failures); assertEquals(5_000L, l.retryDelayMs())
        assertFalse(l.canRetry(13)); assertTrue(l.canRetry(12 + 5_000))
        l.request("bbbbbbbb", 20_000); l.fail("timeout", 20_001)
        assertEquals(10_000L, l.retryDelayMs())
        repeat(6) { l.request("bbbbbbbb", 200_000L + it * 100_000L); l.fail("x", 200_001L + it * 100_000L) }
        assertEquals(60_000L, l.retryDelayMs())
        // success resets the budget
        l.request("bbbbbbbb", 900_000); l.offerReceived("bbbbbbbb", 900_001); l.networkAvailable(900_002); l.handshakeOk("bbbbbbbb", 900_003)
        assertEquals(0, l.failures)
    }

    @Test
    fun step_timeout_fails_the_attempt() {
        val l = LinkState()
        l.request("bbbbbbbb", 0)
        assertEquals(LinkState.Action.NONE, l.tick(20_000, 45_000))
        assertEquals(LinkState.Action.TEARDOWN, l.tick(50_000, 45_000))
        assertEquals(LinkState.State.DOWN, l.state)
        assertTrue(l.describe().contains("timeout"))
        // an UP link never times out
        val u = LinkState(); u.request("b", 0); u.offerReceived("b", 1); u.networkAvailable(2); u.handshakeOk("b", 3)
        assertEquals(LinkState.Action.NONE, u.tick(999_999, 45_000))

        // v0.9.5: an explicit refusal must not punish the user with the silent-failure backoff
        val refused = LinkState()
        refused.request("aaaa0000", 0); refused.fail("the provider could not start its Wi-Fi hotspot", 1_000)
        assertTrue(refused.retryDelayMs() > 0)
        refused.forgetFailures()
        assertEquals(0L, refused.retryDelayMs())
        assertTrue(refused.canRetry(1_100))

        // v0.9.4: a phone asked to host always gives an answer. This is the bug the OnePlus hit:
        // the seller held a link from an earlier test, so it ignored every request in silence and the
        // buyer only saw "searching..." until its own 60 s timeout.
        val host = LinkState()
        assertEquals(LinkState.HostAnswer.HOST, host.hostAnswer("aaaa0000", "ffff0000", linkInUse = false))
        host.requestReceived("aaaa0000", "ffff0000", 0); host.hotspotUp(1); host.clientConnected(2); host.handshakeOk("aaaa0000", 3)
        assertTrue(host.isUp)
        // the same peer asks again: its side is gone
        assertEquals(LinkState.HostAnswer.DROP_STALE_THEN_HOST, host.hostAnswer("aaaa0000", "ffff0000", linkInUse = false))
        // somebody else asks while the link is idle: serve the newcomer
        assertEquals(LinkState.HostAnswer.DROP_STALE_THEN_HOST, host.hostAnswer("bbbb0000", "ffff0000", linkInUse = false))
        // ... but not while a customer is really being served
        assertEquals(LinkState.HostAnswer.REFUSE_BUSY, host.hostAnswer("bbbb0000", "ffff0000", linkInUse = true))
        // mid-negotiation with someone else: refuse, and the requester is told
        val busy = LinkState(); busy.requestReceived("aaaa0000", "ffff0000", 0)
        assertEquals(LinkState.HostAnswer.REFUSE_BUSY, busy.hostAnswer("bbbb0000", "ffff0000", linkInUse = false))
        assertEquals(LinkState.HostAnswer.DROP_STALE_THEN_HOST, busy.hostAnswer("aaaa0000", "ffff0000", linkInUse = false))
        // both asked at once: the lower ID hosts
        val tie = LinkState(); tie.request("aaaa0000", 0)
        assertEquals(LinkState.HostAnswer.IGNORE_TIE_BREAK, tie.hostAnswer("aaaa0000", "ffff0000", linkInUse = false))
        assertEquals(LinkState.HostAnswer.HOST, LinkState().also { it.request("ffff0000", 0) }.hostAnswer("ffff0000", "aaaa0000", linkInUse = false))
        // a phone doing nothing always hosts
        assertEquals(LinkState.HostAnswer.HOST, LinkState().hostAnswer("aaaa0000", "ffff0000", linkInUse = true))

        // v0.9.3: each step has its own patience; only the two that wait for a human keep the long one
        val s = LinkState()
        assertEquals(60_000L, s.stepTimeoutMs(LinkState.State.REQUESTING))
        assertEquals(45_000L, s.stepTimeoutMs(LinkState.State.HOSTING))
        assertEquals(120_000L, s.stepTimeoutMs(LinkState.State.OFFERING))
        assertEquals(120_000L, s.stepTimeoutMs(LinkState.State.JOINING))
        assertEquals(30_000L, s.stepTimeoutMs(LinkState.State.HANDSHAKE))
        s.request("aaaa0000", 0)
        assertEquals(LinkState.Action.NONE, s.tick(59_000))
        assertEquals(LinkState.Action.TEARDOWN, s.tick(61_000))
        assertTrue(s.lastError, s.lastError.contains("REQUESTING"))
        assertTrue(u.isUp)
    }
}
