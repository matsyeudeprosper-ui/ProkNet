package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.14, the state transition that failed on hardware.
 *
 * v0.9.13 on real phones:
 *
 * ```
 * seller  wlan0 = 192.168.1.13                still on the Freebox
 * seller  p2p-wlan0-26 = 192.168.49.1         GROUP_OWNER, network 158
 * seller  listener 192.168.49.1:47742         generation 1, accepting true
 * seller  CLIENT COUNT 0 -> 1                 listener check says VALID
 * buyer   p2p0 = 192.168.49.124               android network none
 * buyer   socket bound to P2P network=false
 * buyer   DIAL 1..6 -> 192.168.49.1:47742     all six timed out
 * seller  TCP accepted                        never
 * ```
 *
 * Everything matched and nothing was reachable, so "valid" was measuring the
 * wrong thing: that listener was built while the group was EMPTY. These
 * tests hold the lifecycle to the transition itself.
 */
class P2pDataPlaneTest {

    private val ownerIface = "p2p-wlan0-26"
    private val ownerAddr = "192.168.49.1"
    private val buyerIface = "p2p0"
    private val buyerAddr = "192.168.49.124"

    private fun ownerSeen(iface: String = ownerIface, net: String = "158") =
        P2pEndpoint.Observed(P2pPlan.Role.GROUP_OWNER, iface, ownerAddr, net)

    private fun clientSeen(net: String = "") =
        P2pEndpoint.Observed(P2pPlan.Role.CLIENT, buyerIface, buyerAddr, net)

    private fun listenerFor(p: P2pDataPlane.Plane, accepting: Boolean = true) =
        P2pDataPlane.Listener(p, p.localAddress, P2pPlan.PORT, accepting, p.binding)

    // ---- 1. the empty group, then a client, on the real timeline ------------------------------------

    @Test
    fun a_listener_built_in_the_empty_group_phase_is_not_the_listener_of_a_live_membership() {
        // 09:08:43 the group forms, no client yet
        val empty = P2pDataPlane.advance(P2pDataPlane.NONE, ownerSeen(), clientCount = 0, groupFormed = true)
        assertEquals(1, empty.groupGeneration)
        assertEquals("an empty group has no membership", 0, empty.membershipGeneration)
        assertTrue(empty.endpointReady)
        assertFalse("nothing has joined, so no socket can be expected to work", empty.usable)
        // the seller may already hold a listener from this phase: v0.9.13 did exactly that
        val emptyListener = listenerFor(empty)
        assertEquals(P2pDataPlane.Verdict.NO_MEMBER, P2pDataPlane.validate(empty, emptyListener))

        // 09:10:15 the buyer joins. Same interface, same address, same Android network.
        val live = P2pDataPlane.advance(empty, ownerSeen(), clientCount = 1, groupFormed = true)
        assertEquals("the group did not change", 1, live.groupGeneration)
        assertEquals("but the membership did", 1, live.membershipGeneration)
        assertTrue(live.usable)
        // THE regression: the old listener matched on every field and was carried forward
        assertEquals(ownerAddr, emptyListener.boundAddress)
        assertEquals(live.interfaceName, emptyListener.plane.interfaceName)
        assertEquals(live.networkIdentity, emptyListener.plane.networkIdentity)
        assertEquals(P2pDataPlane.Verdict.STALE_MEMBERSHIP, P2pDataPlane.validate(live, emptyListener))
        assertFalse(P2pDataPlane.ok(P2pDataPlane.validate(live, emptyListener)))

        // re-armed for the live membership, it is valid, and only then
        val armed = listenerFor(live)
        assertEquals(P2pDataPlane.Verdict.VALID, P2pDataPlane.validate(live, armed))
        assertEquals(1, armed.plane.membershipGeneration)

        // and an unchanged observation afterwards rebuilds nothing at all
        val again = P2pDataPlane.advance(live, ownerSeen(), clientCount = 1, groupFormed = true)
        assertTrue("nothing changed, so it must be the same plane", again === live)
        assertEquals(P2pDataPlane.Verdict.VALID, P2pDataPlane.validate(again, armed))
    }

    @Test
    fun a_buyer_may_only_dial_once_the_transport_is_ready_for_its_membership() {
        val empty = P2pDataPlane.advance(P2pDataPlane.NONE, clientSeen(), clientCount = 0, groupFormed = false)
        assertFalse(empty.usable)
        // a client that has not joined anything dials nobody
        assertEquals(P2pDataPlane.DialRole.WAIT, P2pDataPlane.dialStep(empty, ownerAddr, peerReady = true, waitedMs = 0, alreadyDialled = false))

        // it joins: a client is a member of the group it joined, at once
        val joined = P2pDataPlane.advance(empty, clientSeen(), clientCount = 0, groupFormed = true)
        assertTrue(joined.hasMember)
        assertTrue(joined.usable)
        assertEquals(1, joined.membershipGeneration)

        // the provider has not answered yet: wait, do not dial blindly
        assertEquals(P2pDataPlane.DialRole.WAIT, P2pDataPlane.dialStep(joined, ownerAddr, peerReady = false, waitedMs = 0, alreadyDialled = false))
        // TRANSPORT_READY arrives: dial now
        assertEquals(P2pDataPlane.DialRole.DIAL, P2pDataPlane.dialStep(joined, ownerAddr, peerReady = true, waitedMs = 0, alreadyDialled = false))
        // no answer at all (BLE lost, or the developer lab): dial once the bounded wait is over
        assertEquals(P2pDataPlane.DialRole.DIAL, P2pDataPlane.dialStep(joined, ownerAddr, peerReady = false, waitedMs = P2pDataPlane.READY_WAIT_MS, alreadyDialled = false))
        // never twice for the same generation, and never without an address
        assertEquals(P2pDataPlane.DialRole.WAIT, P2pDataPlane.dialStep(joined, ownerAddr, peerReady = true, waitedMs = 0, alreadyDialled = true))
        assertEquals(P2pDataPlane.DialRole.WAIT, P2pDataPlane.dialStep(joined, "", peerReady = true, waitedMs = 0, alreadyDialled = false))
    }

    // ---- 2 and 3. how a socket is tied to the link --------------------------------------------------

    @Test
    fun the_buyer_binds_to_its_local_p2p_address_when_android_exposes_no_network() {
        // the OnePlus in the real run: an address, no Network object
        val buyer = P2pDataPlane.advance(P2pDataPlane.NONE, clientSeen(net = ""), clientCount = 0, groupFormed = true)
        assertEquals(buyerAddr, buyer.localAddress)
        assertFalse(clientSeen(net = "").hasNetwork)
        assertEquals(P2pEndpoint.Binding.LOCAL_ADDRESS, buyer.binding)
        assertTrue(P2pEndpoint.usable(buyer.binding))
        assertEquals("LOCAL_ADDRESS 192.168.49.124", P2pEndpoint.bindingText(buyer.binding, buyer.localAddress))

        // with a Network, that comes first
        val withNetwork = P2pDataPlane.advance(P2pDataPlane.NONE, clientSeen(net = "212"), clientCount = 0, groupFormed = true)
        assertEquals(P2pEndpoint.Binding.ANDROID_NETWORK, withNetwork.binding)
        assertEquals("ANDROID_NETWORK", P2pEndpoint.bindingText(withNetwork.binding))
        assertEquals(P2pEndpoint.Binding.ANDROID_NETWORK, P2pEndpoint.bindingFor(hasNetwork = true, localAddress = ""))

        // and with neither, a socket must NOT be opened on a guess
        assertEquals(P2pEndpoint.Binding.NONE, P2pEndpoint.bindingFor(hasNetwork = false, localAddress = ""))
        assertFalse(P2pEndpoint.usable(P2pEndpoint.Binding.NONE))
        assertTrue(P2pEndpoint.NO_BINDING_ERROR.isNotEmpty())

        // the line both phones are read on
        val line = P2pEndpoint.dialLine(1, P2pPlan.DIAL_ATTEMPTS, buyerAddr, ownerAddr, P2pPlan.PORT, buyerIface, "", buyer.binding)
        assertTrue(line.contains("192.168.49.124 -> 192.168.49.1:47742"))
        assertTrue(line.contains("binding LOCAL_ADDRESS 192.168.49.124"))
    }

    @Test
    fun the_local_link_is_p2p_and_the_provider_upstream_stays_wifi() {
        assertTrue(P2pPlan.isP2pIface("p2p-wlan0-26"))
        assertTrue(P2pPlan.isP2pIface("p2p0"))
        assertFalse(P2pPlan.isP2pIface("wlan0"))
        assertFalse(P2pPlan.isP2pIface(null))
        // a p2p interface is a LOCAL link and can never be chosen as an upstream
        assertTrue(P2pPlan.isLocalLinkIface("p2p-wlan0-26"))
        assertFalse("wlan0 is the provider upstream and must stay usable as one", P2pPlan.isLocalLinkIface("wlan0"))

        // the seller listener lives on the P2P address, never on every interface
        val live = P2pDataPlane.advance(P2pDataPlane.NONE, ownerSeen(), clientCount = 1, groupFormed = true)
        assertEquals(P2pDataPlane.Verdict.VALID, P2pDataPlane.validate(live, listenerFor(live)))
        val onAllInterfaces = listenerFor(live).copy(boundAddress = "0.0.0.0")
        assertEquals(P2pDataPlane.Verdict.WRONG_ADDRESS, P2pDataPlane.validate(live, onAllInterfaces))
    }

    // ---- 4. a second customer, later ----------------------------------------------------------------

    @Test
    fun a_second_customer_gets_its_own_membership_and_a_stale_loop_cannot_feed_it() {
        val first = P2pDataPlane.advance(
            P2pDataPlane.advance(P2pDataPlane.NONE, ownerSeen(), 0, true), ownerSeen(), clientCount = 1, groupFormed = true)
        assertEquals(1, first.membershipGeneration)
        val firstListener = listenerFor(first)
        assertEquals(P2pDataPlane.Verdict.VALID, P2pDataPlane.validate(first, firstListener))

        // the customer leaves: the group is still ours, but there is no live link any more
        val alone = P2pDataPlane.advance(first, ownerSeen(), clientCount = 0, groupFormed = true)
        assertFalse(alone.usable)
        assertEquals(P2pDataPlane.Verdict.NO_MEMBER, P2pDataPlane.validate(alone, firstListener))
        assertFalse(P2pDataPlane.acceptAllowed(first, alone))

        // a second customer joins the same group
        val second = P2pDataPlane.advance(alone, ownerSeen(), clientCount = 1, groupFormed = true)
        assertEquals("the group never changed", 1, second.groupGeneration)
        assertEquals("but this is a new membership", 2, second.membershipGeneration)
        assertEquals(P2pDataPlane.Verdict.STALE_MEMBERSHIP, P2pDataPlane.validate(second, firstListener))
        assertEquals(P2pDataPlane.Verdict.VALID, P2pDataPlane.validate(second, listenerFor(second)))

        // the loop of the first membership comes back late: refused
        assertFalse("a socket from membership 1 may never feed membership 2", P2pDataPlane.acceptAllowed(first, second))
        assertTrue(P2pDataPlane.acceptAllowed(second, second))

        // and the whole group being rebuilt starts the count again
        val rebuilt = P2pDataPlane.advance(second, ownerSeen(iface = "p2p-wlan0-27", net = "191"), clientCount = 1, groupFormed = true)
        assertEquals(2, rebuilt.groupGeneration)
        assertEquals(1, rebuilt.membershipGeneration)
        assertFalse(P2pDataPlane.acceptAllowed(second, rebuilt))
        assertEquals(P2pDataPlane.Verdict.STALE_GROUP, P2pDataPlane.validate(rebuilt, listenerFor(second)))
        assertTrue(P2pDataPlane.changeReason(second, ownerSeen(iface = "p2p-wlan0-27", net = "191")).contains("p2p-wlan0-27"))
    }

    @Test
    fun the_group_going_away_forgets_the_endpoint_and_keeps_the_count() {
        val live = P2pDataPlane.advance(
            P2pDataPlane.advance(P2pDataPlane.NONE, ownerSeen(), 0, true), ownerSeen(), 1, true)
        val gone = P2pDataPlane.advance(live, ownerSeen(), clientCount = 0, groupFormed = false)
        assertFalse(gone.endpointReady)
        assertFalse(gone.usable)
        assertEquals(P2pPlan.Role.NONE, gone.role)
        assertEquals(P2pDataPlane.Verdict.NO_ENDPOINT, P2pDataPlane.validate(gone, listenerFor(live)))
        // sharing again gives a new group generation, never the old one
        val next = P2pDataPlane.advance(gone, ownerSeen(iface = "p2p-wlan0-28", net = "203"), clientCount = 0, groupFormed = true)
        assertEquals(2, next.groupGeneration)
        assertEquals(0, next.membershipGeneration)
        for (v in P2pDataPlane.Verdict.values()) assertTrue(P2pDataPlane.verdictText(v).isNotEmpty())
    }

    // ---- the purchase still ends with a sentence ----------------------------------------------------

    @Test
    fun a_formed_group_with_no_transport_ends_the_purchase_instead_of_spinning() {
        assertEquals(P2pPlan.TransportStep.WAIT, P2pPlan.transportStep(groupFormed = false, linkUp = false, msSinceGroup = 0))
        assertEquals(P2pPlan.TransportStep.WAIT, P2pPlan.transportStep(true, false, 10_000))
        assertEquals(P2pPlan.TransportStep.WAIT, P2pPlan.transportStep(true, false, P2pPlan.TRANSPORT_GIVE_UP_MS - 1))
        assertEquals(P2pPlan.TransportStep.FAIL_NO_TRANSPORT, P2pPlan.transportStep(true, false, P2pPlan.TRANSPORT_GIVE_UP_MS))
        assertEquals(P2pPlan.TransportStep.DONE, P2pPlan.transportStep(true, true, 10 * P2pPlan.TRANSPORT_GIVE_UP_MS))

        // the window must outlast the readiness wait AND the dial ladder that follows it
        val dialWindow = P2pPlan.DIAL_ATTEMPTS * (P2pPlan.DIAL_TIMEOUT_MS + P2pPlan.DIAL_GAP_MS)
        assertTrue(P2pPlan.TRANSPORT_GIVE_UP_MS >= P2pDataPlane.READY_WAIT_MS + dialWindow)
        assertEquals("Connexion locale créée, mais le fournisseur ne répond pas.", ProductState.lostHint(P2pPlan.TRANSPORT_FAIL_REASON))
    }

    // ---- the two new control messages ----------------------------------------------------------------

    @Test
    fun the_membership_and_the_transport_survive_the_wire() {
        val member = Wire.parseControl(Wire.p2pMember(buyerAddr, P2pPlan.PORT)) as Wire.Control.P2pMember
        assertEquals(buyerAddr, member.address)
        assertEquals(P2pPlan.PORT, member.port)

        val ready = Wire.parseControl(Wire.p2pTransport(2, ownerAddr, P2pPlan.PORT)) as Wire.Control.P2pTransport
        assertEquals(2, ready.membership)
        assertEquals(ownerAddr, ready.address)
        assertEquals(P2pPlan.PORT, ready.port)

        // an empty address is not a transport
        assertEquals(null, Wire.parseControl(Wire.p2pMember("", 47742)))
        // and the older messages still mean what they meant
        assertEquals("GROUP_READY", Wire.p2pStatusName(Wire.P2P_READY))
        assertEquals(Wire.P2P_READY, (Wire.parseControl(Wire.p2pStatus(Wire.P2P_READY, "C1 Pro")) as Wire.Control.P2pStatus).code)
        assertNotEquals(Wire.OP_P2P_MEMBER, Wire.OP_P2P_TRANSPORT)
    }

    // ---- v0.9.15: the radio, and the measurement ----------------------------------------------------

    @Test
    fun discovery_belongs_to_admission_and_never_to_the_data_phase() {
        // a seller with an EMPTY group still has to be found: that admission path is proven, keep it
        val emptyGroup = P2pDataPlane.advance(P2pDataPlane.NONE, ownerSeen(), clientCount = 0, groupFormed = true)
        assertFalse(emptyGroup.hasMember)
        assertTrue(P2pPlan.discoveryWanted(P2pPlan.Want.SELL, emptyGroup.hasMember))
        // a buyer looking for the seller
        assertTrue(P2pPlan.discoveryWanted(P2pPlan.Want.BUY, hasLiveMember = false))

        // somebody joined: admission is over and the radio belongs to the data plane. A single radio
        // that scans the social channels is not on the group channel, and in the v0.9.14 run both
        // phones scanned every 30 s while every SYN in BOTH directions timed out.
        val live = P2pDataPlane.advance(emptyGroup, ownerSeen(), clientCount = 1, groupFormed = true)
        assertTrue(live.hasMember)
        assertFalse(P2pPlan.discoveryWanted(P2pPlan.Want.SELL, live.hasMember))
        // a client is a member the moment it joins
        val joined = P2pDataPlane.advance(P2pDataPlane.NONE, clientSeen(), clientCount = 0, groupFormed = true)
        assertTrue(joined.hasMember)
        assertFalse(P2pPlan.discoveryWanted(P2pPlan.Want.BUY, joined.hasMember))
        // idle: nothing to admit
        assertFalse(P2pPlan.discoveryWanted(P2pPlan.Want.NONE, false))
        assertFalse(P2pPlan.discoveryWanted(P2pPlan.Want.NONE, true))
    }

    @Test
    fun the_link_probe_says_what_six_timed_out_syns_cannot() {
        assertEquals(P2pPlan.LinkProof.NOT_RUN, P2pPlan.linkProof(sent = 0, unicastReplies = 0, broadcastReplies = 0, echoedHere = 0))
        // nothing crossed at all
        assertEquals(P2pPlan.LinkProof.NO_PACKET_CROSSED, P2pPlan.linkProof(5, 0, 0, 0))
        // THE v0.9.15 result, seen from the owner: their packets reach us, our answers never get back
        assertEquals(P2pPlan.LinkProof.ONE_WAY, P2pPlan.linkProof(5, 0, 0, 3))
        // broadcast crosses where unicast does not: the two phones cannot address each other
        assertEquals(P2pPlan.LinkProof.BROADCAST_ONLY, P2pPlan.linkProof(5, 0, 2, 0))
        // one unicast reply is enough to prove the link carries IP both ways
        assertEquals(P2pPlan.LinkProof.ALIVE, P2pPlan.linkProof(5, 1, 0, 0))
        assertEquals(P2pPlan.LinkProof.ALIVE, P2pPlan.linkProof(1, 1, 4, 7))
        for (p in P2pPlan.LinkProof.values()) assertTrue(P2pPlan.linkProofText(p).isNotEmpty())
        assertNotEquals(P2pPlan.PORT, P2pPlan.PROBE_PORT)
    }

    @Test
    fun the_probe_knows_where_to_broadcast() {
        assertEquals("192.168.49.255", P2pPlan.broadcastOf("192.168.49.1"))
        assertEquals("192.168.49.255", P2pPlan.broadcastOf("192.168.49.124"))
        assertEquals("", P2pPlan.broadcastOf(""))
        assertEquals("", P2pPlan.broadcastOf("nonsense"))
    }
}
