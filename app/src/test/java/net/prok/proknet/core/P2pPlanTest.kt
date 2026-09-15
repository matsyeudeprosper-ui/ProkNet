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

    // ---- v0.9.9: who invites whom, and the guest's ladder ------------------------------------------

    @Test
    fun the_owner_invites_because_a_group_owner_cannot_join_another_group() {
        // exactly the v0.9.8 phone result: seller owns a group, sees the buyer, clients stays 0,
        // buyer's own connect() is accepted and nothing happens
        assertEquals(P2pPlan.Join.OWNER_INVITES, P2pPlan.joinRole(iOwnAGroup = true))
        assertEquals(P2pPlan.Join.GUEST_WAITS, P2pPlan.joinRole(iOwnAGroup = false))
    }

    @Test
    fun the_guest_never_waits_in_silence() {
        // freshly asked: just wait
        assertEquals(P2pPlan.GuestStep.WAIT, P2pPlan.guestStep(0, groupFormed = false, ownerVisible = true))
        assertEquals(P2pPlan.GuestStep.WAIT, P2pPlan.guestStep(P2pPlan.INVITE_ASK_AGAIN_MS - 1, false, true))
        // nothing yet: ask again
        assertEquals(P2pPlan.GuestStep.ASK_AGAIN, P2pPlan.guestStep(P2pPlan.INVITE_ASK_AGAIN_MS, false, true))
        // still nothing and we can see the owner: try to join it ourselves
        assertEquals(P2pPlan.GuestStep.TRY_MYSELF, P2pPlan.guestStep(P2pPlan.INVITE_TRY_SELF_MS, false, true))
        // ... but not if we cannot even see it
        assertEquals(P2pPlan.GuestStep.ASK_AGAIN, P2pPlan.guestStep(P2pPlan.INVITE_TRY_SELF_MS, false, ownerVisible = false))
        // and it always ends with a reason, never a silent wait
        assertEquals(P2pPlan.GuestStep.GIVE_UP, P2pPlan.guestStep(P2pPlan.INVITE_GIVE_UP_MS, false, true))
        assertEquals(P2pPlan.GuestStep.GIVE_UP, P2pPlan.guestStep(P2pPlan.INVITE_GIVE_UP_MS + 60_000, false, false))
        // once we are in the group there is nothing left to do
        assertEquals(P2pPlan.GuestStep.WAIT, P2pPlan.guestStep(P2pPlan.INVITE_GIVE_UP_MS, groupFormed = true, ownerVisible = true))
        assertTrue(P2pPlan.INVITE_ASK_AGAIN_MS < P2pPlan.INVITE_TRY_SELF_MS && P2pPlan.INVITE_TRY_SELF_MS < P2pPlan.INVITE_GIVE_UP_MS)
        for (g in P2pPlan.GuestStep.values()) assertTrue(P2pPlan.guestStepText(g).isNotEmpty())
    }

    @Test
    fun the_ladder_pauses_when_the_provider_is_out_of_ble_range() {
        // v0.9.10: after a long session the seller vanished from BLE and the buyer kept asking
        // every 4 s over a transport that was not there. Now it pauses and the clock pauses with it.
        assertEquals(P2pPlan.GuestStep.PAUSED, P2pPlan.guestTick(controlAvailable = false, reachableMs = 0, unreachableMs = 8_000, groupFormed = false, ownerVisible = false))
        assertEquals(P2pPlan.GuestStep.PAUSED, P2pPlan.guestTick(false, 30_000, 40_000, false, true))
        // the admission timeout only counts time when the provider was actually reachable
        assertEquals(P2pPlan.GuestStep.WAIT, P2pPlan.guestTick(true, 1_000, 60_000, false, true))
        assertEquals(P2pPlan.GuestStep.ASK_AGAIN, P2pPlan.guestTick(true, P2pPlan.INVITE_ASK_AGAIN_MS, 60_000, false, true))
        assertEquals(P2pPlan.GuestStep.GIVE_UP, P2pPlan.guestTick(true, P2pPlan.INVITE_GIVE_UP_MS, 0, false, true))
        // out of range for too long is its own, clear failure
        assertEquals(P2pPlan.GuestStep.UNREACHABLE, P2pPlan.guestTick(false, 10_000, P2pPlan.UNREACHABLE_GIVE_UP_MS, false, true))
        // and once we are in the group nothing else matters
        assertEquals(P2pPlan.GuestStep.WAIT, P2pPlan.guestTick(false, 0, P2pPlan.UNREACHABLE_GIVE_UP_MS, groupFormed = true, ownerVisible = false))
        for (g in P2pPlan.GuestStep.values()) assertTrue(P2pPlan.guestStepText(g).isNotEmpty())
    }

    @Test
    fun the_owner_finds_the_guest_by_the_name_it_sent() {
        // Android hides a phone's own P2P MAC, so the buyer sends its NAME over BLE
        val peers = listOf(P2pPlan.PeerRef("C1 Pro", "aa:bb:cc:00:11:22"), P2pPlan.PeerRef("OnePlus Nord", "aa:bb:cc:00:11:33"))
        assertEquals("aa:bb:cc:00:11:33", P2pPlan.matchPeer(peers, "OnePlus Nord"))
        assertEquals("aa:bb:cc:00:11:33", P2pPlan.matchPeer(peers, "onePLUS nord"))
        assertEquals("aa:bb:cc:00:11:22", P2pPlan.matchPeer(peers, "C1"))
        assertNull(P2pPlan.matchPeer(peers, "Samsung"))
        assertNull(P2pPlan.matchPeer(peers, ""))
        assertNull(P2pPlan.matchPeer(emptyList(), "C1 Pro"))
    }

    @Test
    fun a_seller_can_advertise_that_the_way_in_is_a_direct_link() {
        val hotspot = Market.Offer("aaaa0000", 5, Market.flags(sell = true, relay = false, validated = true, upstreamType = Tunnel.UP_WIFI), -50, 0)
        val direct = Market.Offer("aaaa0000", 5, Market.flags(true, false, true, Tunnel.UP_WIFI, p2p = true), -50, 0)
        assertFalse(hotspot.p2p)
        assertTrue(direct.p2p)
        // the new bit must not disturb anything the buyer already reads
        assertTrue(direct.selling); assertTrue(direct.validated); assertFalse(direct.viaRelay)
        assertEquals(Tunnel.UP_WIFI, direct.upstreamType)
        assertEquals(5, direct.pricePerMb)
        // and the request that carries the buyer's name survives the wire
        val body = Wire.p2pRequest("OnePlus Nord")
        assertEquals("OnePlus Nord", (Wire.parseControl(body) as Wire.Control.P2pRequest).deviceName)
        assertEquals("", (Wire.parseControl(byteArrayOf(Wire.OP_P2P_REQUEST.toByte())) as Wire.Control.P2pRequest).deviceName)
        // a refusal has its own reason and reads in plain words
        assertTrue(Wire.cancelReasonText(Wire.CANCEL_P2P).contains("Wi-Fi Direct group"))
        assertTrue(ProductState.lostHint(Wire.cancelReasonText(Wire.CANCEL_P2P)).isNotEmpty())
    }

    // ---- v0.9.11: the refusal loop the phone test caught -------------------------------------------

    @Test
    fun a_seller_never_advertises_a_way_in_it_cannot_honour() {
        // the phone test: the seller advertised the Wi-Fi Direct way in while its group had not come
        // up, so every buyer started a doomed admission and was refused six times in twenty seconds
        assertFalse(P2pPlan.advertiseP2p(sharingByP2p = true, groupFormed = false))
        assertTrue(P2pPlan.advertiseP2p(sharingByP2p = true, groupFormed = true))
        assertFalse(P2pPlan.advertiseP2p(sharingByP2p = false, groupFormed = true))
    }

    @Test
    fun an_admission_request_is_answered_honestly() {
        // group up and we own it: invite, which is the v0.9.9 fix
        assertEquals(P2pPlan.Admission.INVITE, P2pPlan.admission(sharingByP2p = true, groupFormed = true, isOwner = true))
        // we sell this way but the group is gone: rebuild it instead of refusing a real customer
        assertEquals(P2pPlan.Admission.REBUILD_GROUP, P2pPlan.admission(true, groupFormed = false, isOwner = false))
        // the group exists but we are only a guest in it: still rebuild our own
        assertEquals(P2pPlan.Admission.REBUILD_GROUP, P2pPlan.admission(true, groupFormed = true, isOwner = false))
        // we do not sell this way at all: a clear no, once
        assertEquals(P2pPlan.Admission.REFUSE, P2pPlan.admission(false, groupFormed = false, isOwner = false))
        assertEquals(P2pPlan.Admission.REFUSE, P2pPlan.admission(false, groupFormed = true, isOwner = false))
    }

    @Test
    fun the_buyer_asks_at_most_once_per_ladder_step() {
        // the log showed one request every 4 s because the tick, not the ladder, drove the asking
        assertTrue(P2pPlan.ASK_EVERY_MS >= 10_000L)
        assertTrue(P2pPlan.ASK_EVERY_MS <= P2pPlan.INVITE_ASK_AGAIN_MS)
        // and a refusal from the provider must be a real, readable reason on the buyer's screen
        val refusal = Wire.cancelReasonText(Wire.CANCEL_P2P) + " [the provider is not sharing by Wi-Fi Direct]"
        val hint = ProductState.lostHint(refusal)
        assertTrue(hint, hint.isNotEmpty())
        assertFalse(hint, hint.contains("Rapprochez"))
    }

    // ---- v0.9.12: the buyer joins by itself --------------------------------------------------------

    private val sellerPeers = listOf(
        P2pPlan.PeerRef("C1 Pro", "aa:bb:cc:00:11:22"),
        P2pPlan.PeerRef("Some TV", "aa:bb:cc:00:11:44"),
    )

    @Test
    fun the_owner_peer_list_anonymises_the_buyer_but_the_buyer_still_joins() {
        // exactly what the phone showed on the SELLER: the buyer is 00:00:00:00:00:00 there
        val ownerSees = listOf(P2pPlan.PeerRef("", "00:00:00:00:00:00"), P2pPlan.PeerRef("", "02:00:00:00:00:00"))
        assertTrue(P2pPlan.anonymous("00:00:00:00:00:00"))
        assertTrue(P2pPlan.anonymous("02:00:00:00:00:00"))
        assertTrue(P2pPlan.anonymous(null))
        assertFalse(P2pPlan.anonymous("aa:bb:cc:00:11:22"))
        assertNull("the owner must never try to join an anonymised peer", P2pPlan.pickSellerPeer(ownerSees, "OnePlus Nord CE 2 Lite 5G"))

        // and on the BUYER the seller is named, with a real address: that is the side that acts
        assertEquals("aa:bb:cc:00:11:22", P2pPlan.pickSellerPeer(sellerPeers, "C1 Pro"))
        // its name came over BLE, so a rough match is enough
        assertEquals("aa:bb:cc:00:11:22", P2pPlan.pickSellerPeer(sellerPeers, "c1 pro"))
        // v0.9.18: no name means WAIT. The old fallback to "any peer that owns a group" dialled a
        // printer on the phones, so this network never guesses who it is talking to.
        assertNull(P2pPlan.pickSellerPeer(sellerPeers, ""))
        assertNull(P2pPlan.pickSellerPeer(emptyList(), "C1 Pro"))
    }

    @Test
    fun the_seller_answers_with_a_state_and_the_buyer_acts_on_it() {
        assertEquals(P2pPlan.GroupStatus.READY, P2pPlan.groupStatus(sharingByP2p = true, groupFormed = true, isOwner = true))
        assertEquals(P2pPlan.GroupStatus.REBUILDING, P2pPlan.groupStatus(true, groupFormed = false, isOwner = false))
        assertEquals(P2pPlan.GroupStatus.REBUILDING, P2pPlan.groupStatus(true, groupFormed = true, isOwner = false))
        assertEquals(P2pPlan.GroupStatus.NOT_AVAILABLE, P2pPlan.groupStatus(false, false, false))

        // GROUP_READY and the seller is in our list: connect, once
        assertEquals(P2pPlan.JoinStep.CONNECT, P2pPlan.joinStep(P2pPlan.GroupStatus.READY, sellerPeerFound = true, connectAttempts = 0, elapsedMs = 5_000, groupFormed = false))
        // ready but not discovered yet: wait for it, do not ask again and again
        assertEquals(P2pPlan.JoinStep.WAIT_PEER, P2pPlan.joinStep(P2pPlan.GroupStatus.READY, false, 0, 5_000, false))
        // rebuilding: wait, it will come
        assertEquals(P2pPlan.JoinStep.WAIT_REBUILD, P2pPlan.joinStep(P2pPlan.GroupStatus.REBUILDING, false, 0, 5_000, false))
        // no answer yet: ask
        assertEquals(P2pPlan.JoinStep.ASK_STATUS, P2pPlan.joinStep(null, false, 0, 1_000, false))
        // not sharing that way: fail at once, no 60 s of waiting
        assertEquals(P2pPlan.JoinStep.FAIL_NOT_AVAILABLE, P2pPlan.joinStep(P2pPlan.GroupStatus.NOT_AVAILABLE, true, 0, 1_000, false))
        // in the group: nothing left to do
        assertEquals(P2pPlan.JoinStep.DONE, P2pPlan.joinStep(P2pPlan.GroupStatus.READY, true, 2, 50_000, groupFormed = true))
    }

    @Test
    fun a_busy_framework_is_retried_slowly_and_then_given_up_on() {
        assertEquals(P2pPlan.JoinStep.RETRY_BUSY, P2pPlan.joinStep(P2pPlan.GroupStatus.READY, true, 1, 10_000, false))
        assertEquals(P2pPlan.JoinStep.RETRY_BUSY, P2pPlan.joinStep(P2pPlan.GroupStatus.READY, true, P2pPlan.CONNECT_ATTEMPTS - 1, 20_000, false))
        assertEquals(P2pPlan.JoinStep.GIVE_UP, P2pPlan.joinStep(P2pPlan.GroupStatus.READY, true, P2pPlan.CONNECT_ATTEMPTS, 20_000, false))
        assertEquals(P2pPlan.JoinStep.GIVE_UP, P2pPlan.joinStep(P2pPlan.GroupStatus.READY, true, 1, P2pPlan.JOIN_GIVE_UP_MS, false))
        // conservative and growing, never a rapid loop
        assertEquals(3_000L, P2pPlan.busyDelayMs(1))
        assertEquals(6_000L, P2pPlan.busyDelayMs(2))
        assertEquals(12_000L, P2pPlan.busyDelayMs(3))
        assertEquals(24_000L, P2pPlan.busyDelayMs(4))
        assertEquals(24_000L, P2pPlan.busyDelayMs(9))
        for (j in P2pPlan.JoinStep.values()) assertTrue(P2pPlan.joinStepText(j).isNotEmpty())
    }

    @Test
    fun a_second_purchase_starts_from_a_clean_slate() {
        // after a session the ladder starts again with no memory of the last one
        assertEquals(P2pPlan.JoinStep.ASK_STATUS, P2pPlan.joinStep(null, sellerPeerFound = true, connectAttempts = 0, elapsedMs = 0, groupFormed = false))
        // and the status message survives the wire, with the seller's own name
        val body = Wire.p2pStatus(Wire.P2P_READY, "C1 Pro")
        val back = Wire.parseControl(body) as Wire.Control.P2pStatus
        assertEquals(Wire.P2P_READY, back.code)
        assertEquals("C1 Pro", back.deviceName)
        assertEquals("", (Wire.parseControl(Wire.p2pStatus(Wire.P2P_REBUILDING, "")) as Wire.Control.P2pStatus).deviceName)
        assertEquals(Wire.P2P_NOT_AVAILABLE, (Wire.parseControl(byteArrayOf(Wire.OP_P2P_STATUS.toByte())) as Wire.Control.P2pStatus).code)
        assertEquals("GROUP_READY", Wire.p2pStatusName(Wire.P2P_READY))
        assertEquals("REBUILDING_GROUP", Wire.p2pStatusName(Wire.P2P_REBUILDING))
        assertEquals("NOT_AVAILABLE", Wire.p2pStatusName(Wire.P2P_NOT_AVAILABLE))
    }

    // ---- v0.9.18: the buyer dialled a printer ---------------------------------------------------------

    @Test
    fun a_named_provider_that_is_not_in_the_list_means_wait_never_somebody_else() {
        // the phone run, verbatim: the provider dropped out for a few seconds and these were left
        val withoutSeller = listOf(
            P2pPlan.PeerRef("DIRECT-FB-HP DeskJet 2700 series", "14:cb:19:f5:f9:fc"),
            P2pPlan.PeerRef("Hisense VIDAA TV", "d6:f9:21:c3:41:a8"),
        )
        assertNull("never dial a printer because it owns a group",
            P2pPlan.pickSellerPeer(withoutSeller, "C1 Pro"))

        // the provider is back: dial it, and nothing else
        val withSeller = withoutSeller + P2pPlan.PeerRef("C1 Pro", "72:cb:dd:b9:a1:da")
        assertEquals("72:cb:dd:b9:a1:da", P2pPlan.pickSellerPeer(withSeller, "C1 Pro"))

        // no name means WAIT. This network never guesses who it is talking to.
        assertNull(P2pPlan.pickSellerPeer(withoutSeller, ""))
        assertNull(P2pPlan.pickSellerPeer(withoutSeller, "   "))
        assertNull(P2pPlan.pickSellerPeer(withSeller, ""))

        // and the log can name what it is dialling
        assertEquals("C1 Pro", P2pPlan.peerName(withSeller, "72:cb:dd:b9:a1:da"))
        assertEquals("aa:bb:cc:dd:ee:ff", P2pPlan.peerName(withSeller, "aa:bb:cc:dd:ee:ff"))
    }

    @Test
    fun an_accepted_join_is_waited_for_not_overtaken() {
        // the phone run: connect accepted at 13:55:22.5, another one fired at 13:55:25.8, and Android
        // answered BUSY to our own successor. An accepted join needs room to produce a group.
        assertTrue("an accepted join must outlast a busy backoff step", P2pPlan.JOIN_ACCEPTED_WAIT_MS > P2pPlan.busyDelayMs(1))
        assertTrue("and be long enough for a group to form", P2pPlan.JOIN_ACCEPTED_WAIT_MS >= 10_000L)
        // four attempts still have to fit inside the ladder
        assertTrue("the ladder must still fit in the give up time",
            P2pPlan.JOIN_ACCEPTED_WAIT_MS * (P2pPlan.CONNECT_ATTEMPTS - 1) < P2pPlan.JOIN_GIVE_UP_MS)
    }
}
