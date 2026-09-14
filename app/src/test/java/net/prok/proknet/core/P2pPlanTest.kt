package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.7 experiment, decision side. Phone evidence: a seller connected to a
 * home router cannot create a LocalOnlyHotspot, so no buyer can reach it;
 * the same seller on mobile data works. Wi-Fi Direct is method B, and these
 * are the rules it is driven by. Method A is untouched.
 */
class P2pPlanTest {

    @Test
    fun the_group_owner_listens_and_the_client_dials_whichever_way_android_decides() {
        assertEquals(P2pPlan.Role.NONE, P2pPlan.role(groupFormed = false, isGroupOwner = true))
        assertEquals(P2pPlan.Role.GROUP_OWNER, P2pPlan.role(true, true))
        assertEquals(P2pPlan.Role.CLIENT, P2pPlan.role(true, false))

        // we asked to be the client, but if Android made us the owner we listen instead
        assertTrue(P2pPlan.isHost(P2pPlan.Role.GROUP_OWNER))
        assertFalse(P2pPlan.isHost(P2pPlan.Role.CLIENT))
        assertNull(P2pPlan.socketTarget(P2pPlan.Role.GROUP_OWNER, "192.168.49.1"))
        assertEquals("192.168.49.1", P2pPlan.socketTarget(P2pPlan.Role.CLIENT, "192.168.49.1"))
        assertNull(P2pPlan.socketTarget(P2pPlan.Role.CLIENT, "0.0.0.0"))
        assertNull(P2pPlan.socketTarget(P2pPlan.Role.CLIENT, null))
        assertNull(P2pPlan.socketTarget(P2pPlan.Role.NONE, "192.168.49.1"))
        // its own port, so method A can keep listening on its own
        assertFalse(P2pPlan.PORT == 47741)
    }

    @Test
    fun it_is_only_worth_trying_when_the_hardware_and_wifi_are_there() {
        assertEquals(P2pPlan.Ready.OK, P2pPlan.ready(supported = true, wifiEnabled = true, p2pEnabled = true))
        assertEquals(P2pPlan.Ready.NO_HARDWARE, P2pPlan.ready(false, true, true))
        assertEquals(P2pPlan.Ready.WIFI_OFF, P2pPlan.ready(true, false, true))
        assertEquals(P2pPlan.Ready.P2P_DISABLED, P2pPlan.ready(true, true, false))
        for (r in P2pPlan.Ready.values()) assertTrue(P2pPlan.readyText(r).isNotEmpty())
    }

    @Test
    fun keeping_the_home_wifi_is_half_the_result() {
        // the whole point: a group that kills the seller's own Wi-Fi is useless for reselling it
        assertEquals(P2pPlan.Verdict.GROUP_BUT_STA_LOST, P2pPlan.verdict(groupFormed = true, staKept = false, linkAuthenticated = true))
        assertEquals(P2pPlan.Verdict.NO_GROUP, P2pPlan.verdict(false, true, false))
        assertEquals(P2pPlan.Verdict.LINK_FAILED, P2pPlan.verdict(true, true, false))
        assertEquals(P2pPlan.Verdict.LINK_UP_STA_KEPT, P2pPlan.verdict(true, true, true))
        assertTrue(P2pPlan.verdictText(P2pPlan.Verdict.GROUP_BUT_STA_LOST).contains("useless"))
        for (v in P2pPlan.Verdict.values()) assertTrue(P2pPlan.verdictText(v).isNotEmpty())
    }

    @Test
    fun a_p2p_interface_can_never_be_an_upstream() {
        // a customer's traffic must go out of the seller's real Internet, never back into a local link
        assertTrue(P2pPlan.isLocalLinkIface("p2p-wlan0-0"))
        assertTrue(P2pPlan.isLocalLinkIface("p2p0"))
        assertTrue(P2pPlan.isLocalLinkIface("ap0"))
        assertTrue(P2pPlan.isLocalLinkIface("swlan0"))
        assertTrue(P2pPlan.isLocalLinkIface("wlan1-softap"))
        assertFalse(P2pPlan.isLocalLinkIface("wlan0"))
        assertFalse(P2pPlan.isLocalLinkIface("rmnet_data0"))
        assertFalse(P2pPlan.isLocalLinkIface(null))
        // and the pure upstream chooser still refuses anything marked as a local ProkNet link
        val local = Tunnel.NetView("p2p-wlan0-0", internet = false, validated = false, cellular = false, wifi = true, isProkNetLink = true)
        val home = Tunnel.NetView("wlan0", internet = true, validated = true, cellular = false, wifi = true, isProkNetLink = false)
        assertEquals("wlan0", Tunnel.chooseUpstream(listOf(local, home))?.id)
        assertNull(Tunnel.chooseUpstream(listOf(local)))
    }

    @Test
    fun method_a_stays_the_named_default() {
        assertTrue(P2pPlan.methodName(P2pPlan.Method.HOTSPOT).contains("A"))
        assertTrue(P2pPlan.methodName(P2pPlan.Method.WIFI_DIRECT).contains("experimental"))

        // an adopted link goes straight to the handshake and is refused while a hotspot link is busy
        val idle = LinkState()
        assertEquals(LinkState.Action.OPEN_SOCKET, idle.adopt(asHost = true, now = 0))
        assertEquals(LinkState.State.HANDSHAKE, idle.state)
        assertNull("the peer is learned from the handshake", idle.peer)
        assertEquals(LinkState.Action.NONE, idle.handshakeOk("aaaa0000", 1).let { LinkState.Action.NONE })
        assertTrue(idle.isUp)
        assertEquals("aaaa0000", idle.peer)

        val busy = LinkState()
        busy.request("bbbb0000", 0)
        assertEquals("method A in progress is never interrupted", LinkState.Action.NONE, busy.adopt(false, 1))
        assertEquals(LinkState.State.REQUESTING, busy.state)
    }

    // ---- v0.9.8: the cleanup a phone test caught missing ------------------------------------------

    /** Walk the whole cleanup the way the Android layer does, one confirmed step at a time. */
    private fun clean(l: P2pPlan.Life): List<P2pPlan.Step> {
        val steps = ArrayList<P2pPlan.Step>()
        var guard = 0
        while (l.step != P2pPlan.Step.DONE && guard++ < 10) { steps.add(l.step); l.done(l.step) }
        return steps
    }

    private fun sellWithGroupAndServer(l: P2pPlan.Life) {
        l.start(P2pPlan.Want.SELL); clean(l)
        l.onGroup(true, true, "ssid=DIRECT-56-OnePlus-Nord iface=p2p0")
        l.onSocket("listening on :47742")
        l.onPeers(2)
    }

    @Test
    fun sell_then_stop_then_buy_leaves_nothing_behind() {
        val l = P2pPlan.Life()
        sellWithGroupAndServer(l)
        assertEquals(P2pPlan.Stage.GROUP_OWNER, l.stage)
        assertEquals(P2pPlan.Role.GROUP_OWNER, l.role)
        assertFalse("a live group is not clean", l.view().clean)

        // STOP: every step in order, and IDLE only at the end
        assertEquals(P2pPlan.Step.CANCEL_CONNECT, l.stop())
        assertEquals(listOf(P2pPlan.Step.CANCEL_CONNECT, P2pPlan.Step.STOP_DISCOVERY, P2pPlan.Step.CLOSE_SOCKETS, P2pPlan.Step.REMOVE_GROUP), clean(l))
        assertEquals(P2pPlan.Stage.IDLE, l.stage)
        assertTrue("STOP must leave no group, no socket, no role: " + l.view().describe(), l.view().clean)

        // BUY afterwards: clean again first, then discovery, and still nothing stale
        l.start(P2pPlan.Want.BUY); clean(l)
        assertEquals(P2pPlan.Stage.DISCOVERING, l.stage)
        assertTrue(l.view().describe(), l.view().clean)
        assertEquals("", l.groupInfo)
        assertEquals("", l.socketInfo)
    }

    @Test
    fun buy_then_stop_then_sell_leaves_nothing_behind() {
        val l = P2pPlan.Life()
        l.start(P2pPlan.Want.BUY); clean(l)
        assertEquals(P2pPlan.Stage.DISCOVERING, l.stage)
        l.onPeers(3)
        l.onGroup(true, false, "ssid=DIRECT-56-OnePlus-Nord iface=p2p0")
        l.onSocket("connected 192.168.49.5 -> 192.168.49.1:47742")
        assertEquals(P2pPlan.Stage.CLIENT, l.stage)

        l.stop(); clean(l)
        assertEquals(P2pPlan.Stage.IDLE, l.stage)
        assertTrue(l.view().clean)

        l.start(P2pPlan.Want.SELL); clean(l)
        assertEquals(P2pPlan.Stage.CREATING_GROUP, l.stage)
        assertTrue(l.view().clean)
        assertEquals(P2pPlan.Role.NONE, l.role)
    }

    @Test
    fun repeated_stop_is_harmless() {
        val l = P2pPlan.Life()
        sellWithGroupAndServer(l)
        l.stop(); clean(l)
        assertTrue(l.view().clean)
        repeat(3) {
            l.stop()
            assertEquals(P2pPlan.Stage.CLEANING, l.stage)
            clean(l)
            assertEquals(P2pPlan.Stage.IDLE, l.stage)
            assertTrue(l.view().clean)
        }
        // confirming a step that is not the current one changes nothing
        val before = l.view().describe()
        l.done(P2pPlan.Step.REMOVE_GROUP)
        assertEquals(before, l.view().describe())
    }

    @Test
    fun a_stale_group_is_removed_before_buy_and_only_then() {
        val l = P2pPlan.Life()
        sellWithGroupAndServer(l)                       // the state the phone was stuck in
        assertEquals("ssid=DIRECT-56-OnePlus-Nord iface=p2p0", l.groupInfo)

        l.start(P2pPlan.Want.BUY)
        // the group must survive until Android confirms REMOVE_GROUP, and not a step earlier
        l.done(P2pPlan.Step.CANCEL_CONNECT)
        assertTrue(l.groupFormed)
        l.done(P2pPlan.Step.STOP_DISCOVERY)
        assertTrue(l.groupFormed)
        l.done(P2pPlan.Step.CLOSE_SOCKETS)
        assertTrue("the group is not gone before removeGroup", l.groupFormed)
        l.done(P2pPlan.Step.REMOVE_GROUP)
        assertFalse(l.groupFormed)
        assertEquals("", l.groupInfo)
        assertEquals(P2pPlan.Role.NONE, l.role)
        assertEquals(P2pPlan.Stage.DISCOVERING, l.stage)

        // a late broadcast from the group we just removed must not resurrect it
        val l2 = P2pPlan.Life()
        sellWithGroupAndServer(l2)
        l2.start(P2pPlan.Want.BUY)
        l2.onGroup(true, true, "ssid=DIRECT-56-OnePlus-Nord")
        l2.onPeers(4)
        l2.onSocket("listening on :47742")
        clean(l2)
        assertTrue("a broadcast during cleanup must be ignored: " + l2.view().describe(), l2.view().clean)
    }

    @Test
    fun a_stale_listening_server_is_closed_before_buy() {
        val l = P2pPlan.Life()
        sellWithGroupAndServer(l)
        assertEquals("listening on :47742", l.socketInfo)

        l.start(P2pPlan.Want.BUY)
        l.done(P2pPlan.Step.CANCEL_CONNECT)
        assertEquals("the server is still open before its step", "listening on :47742", l.socketInfo)
        l.done(P2pPlan.Step.STOP_DISCOVERY)
        assertEquals(0, l.view().peers)
        assertEquals("listening on :47742", l.socketInfo)
        l.done(P2pPlan.Step.CLOSE_SOCKETS)
        assertEquals("", l.socketInfo)
        l.done(P2pPlan.Step.REMOVE_GROUP)
        assertEquals(P2pPlan.Stage.DISCOVERING, l.stage)
        assertTrue(l.view().clean)

        // and the order itself is the one the framework needs
        assertEquals(listOf(P2pPlan.Step.CANCEL_CONNECT, P2pPlan.Step.STOP_DISCOVERY, P2pPlan.Step.CLOSE_SOCKETS, P2pPlan.Step.REMOVE_GROUP, P2pPlan.Step.DONE), P2pPlan.CLEANUP_ORDER)
        assertEquals(P2pPlan.Step.DONE, P2pPlan.nextStep(P2pPlan.Step.REMOVE_GROUP))
        assertEquals(P2pPlan.Step.DONE, P2pPlan.nextStep(P2pPlan.Step.DONE))
    }
}
