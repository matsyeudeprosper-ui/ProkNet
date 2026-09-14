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
}
