package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.9.13, the TCP listener lifecycle.
 *
 * The v0.9.12 phone run built the whole topology and still moved no byte:
 *
 * ```
 * seller  wlan0 = 192.168.1.13            (still on the Freebox)
 * seller  p2p-wlan0-25 = 192.168.49.1     GROUP_OWNER, clients 1
 * buyer   p2p0 = 192.168.49.124           CLIENT, groupOwner 192.168.49.1
 * seller 09:08:43  group owner listening on :47742
 * buyer  09:10:1x  192.168.49.124 -> 192.168.49.1:47742  x6, all timed out
 * seller           TCP accepted:          never
 * ```
 *
 * These tests are the lifecycle itself, not helpers: the owner listening
 * long before the client exists, an endpoint replaced by another one, an old
 * accept loop coming back late, which socket belongs to which network, and a
 * second session after the first one ended.
 */
class P2pEndpointTest {

    private val ownerIface = "p2p-wlan0-25"
    private val ownerAddr = "192.168.49.1"
    private val buyerIface = "p2p0"
    private val buyerAddr = "192.168.49.124"

    private fun owner(iface: String = ownerIface, addr: String = ownerAddr, net: String = "101") =
        P2pEndpoint.Observed(P2pPlan.Role.GROUP_OWNER, iface, addr, net)

    private fun listenerFor(e: P2pEndpoint.Endpoint, accepting: Boolean = true) =
        P2pEndpoint.Listener(e.generation, e.interfaceName, e.localAddress, P2pPlan.PORT, e.networkIdentity, accepting)

    // ---- 1. the owner listens long before anybody joins --------------------------------------------

    @Test
    fun the_owner_forms_first_and_the_listener_is_still_valid_when_the_client_joins_much_later() {
        // 09:08:43, the group forms and the endpoint is read from Android
        val e1 = P2pEndpoint.adopt(null, owner())
        assertEquals(1, e1.generation)
        assertTrue(e1.usable)
        val l = listenerFor(e1)
        assertEquals(P2pEndpoint.Verdict.VALID, P2pEndpoint.validate(e1, l))

        // 09:10:15, ninety seconds later, the buyer joins. Android reports the SAME endpoint.
        val e2 = P2pEndpoint.adopt(e1, owner())
        assertEquals("an unchanged endpoint must keep its generation", e1.generation, e2.generation)
        assertTrue("nothing changed, so nothing may be rebuilt", e1 === e2)
        assertEquals(P2pEndpoint.Verdict.VALID, P2pEndpoint.validate(e2, l))
        assertTrue(P2pEndpoint.ok(P2pEndpoint.validate(e2, l)))

        // and the listener is on the P2P address, not on 0.0.0.0, which is what really failed
        val onAllInterfaces = P2pEndpoint.Listener(e1.generation, e1.interfaceName, "0.0.0.0", P2pPlan.PORT, e1.networkIdentity, true)
        assertEquals(P2pEndpoint.Verdict.WRONG_ADDRESS, P2pEndpoint.validate(e1, onAllInterfaces))
    }

    @Test
    fun a_server_object_is_not_proof_that_a_usable_server_exists() {
        val e = P2pEndpoint.adopt(null, owner())
        // it exists, it is simply not this endpoint's listener any more, each for its own reason
        assertEquals(P2pEndpoint.Verdict.NO_LISTENER, P2pEndpoint.validate(e, null))
        assertEquals(P2pEndpoint.Verdict.STALE_GENERATION, P2pEndpoint.validate(e, listenerFor(e).copy(generation = e.generation - 1)))
        assertEquals(P2pEndpoint.Verdict.WRONG_INTERFACE, P2pEndpoint.validate(e, listenerFor(e).copy(interfaceName = "p2p-wlan0-9")))
        assertEquals(P2pEndpoint.Verdict.WRONG_NETWORK, P2pEndpoint.validate(e, listenerFor(e).copy(networkIdentity = "212")))
        assertEquals(P2pEndpoint.Verdict.NOT_ACCEPTING, P2pEndpoint.validate(e, listenerFor(e, accepting = false)))
        // and with no endpoint at all there is nothing to be valid against
        assertEquals(P2pEndpoint.Verdict.NO_ENDPOINT, P2pEndpoint.validate(null, listenerFor(e)))
        val halfRead = P2pEndpoint.Endpoint(1, P2pPlan.Role.GROUP_OWNER, ownerIface, "", "")
        assertFalse(halfRead.usable)
        assertEquals(P2pEndpoint.Verdict.NO_ENDPOINT, P2pEndpoint.validate(halfRead, listenerFor(e)))
        for (v in P2pEndpoint.Verdict.values()) assertTrue(P2pEndpoint.verdictText(v).isNotEmpty())
    }

    // ---- 2. endpoint A dies, endpoint B is created --------------------------------------------------

    @Test
    fun a_client_can_only_connect_to_the_endpoint_that_exists_now() {
        val a = P2pEndpoint.adopt(null, owner(iface = "p2p-wlan0-25", net = "101"))
        val listenerA = listenerFor(a)
        assertEquals(P2pEndpoint.Verdict.VALID, P2pEndpoint.validate(a, listenerA))

        // the group is destroyed and recreated: new interface, new android network
        val b = P2pEndpoint.adopt(a, owner(iface = "p2p-wlan0-26", net = "118"))
        assertNotEquals(a.generation, b.generation)
        assertEquals(2, b.generation)
        assertTrue(P2pEndpoint.changeReason(a, owner(iface = "p2p-wlan0-26", net = "118")).contains("p2p-wlan0-26"))
        // A's listener is worthless against B, and B's listener is the only valid one
        assertEquals(P2pEndpoint.Verdict.STALE_GENERATION, P2pEndpoint.validate(b, listenerA))
        assertEquals(P2pEndpoint.Verdict.VALID, P2pEndpoint.validate(b, listenerFor(b)))

        // the same address on a new group lifecycle is still a new endpoint, because the network changed
        val c = P2pEndpoint.adopt(b, owner(iface = "p2p-wlan0-26", net = "140"))
        assertEquals(3, c.generation)
        assertEquals(P2pEndpoint.Verdict.STALE_GENERATION, P2pEndpoint.validate(c, listenerFor(b)))

        // a role change is material too: an owner that becomes a client owns no listener
        val asClient = P2pEndpoint.adopt(c, P2pEndpoint.Observed(P2pPlan.Role.CLIENT, buyerIface, buyerAddr, "141"))
        assertEquals(4, asClient.generation)
        assertEquals(P2pEndpoint.Verdict.STALE_GENERATION, P2pEndpoint.validate(asClient, listenerFor(c)))
    }

    // ---- 3. an old accept loop comes back late ------------------------------------------------------

    @Test
    fun a_stale_accept_loop_may_never_feed_a_newer_group_lifecycle() {
        val a = P2pEndpoint.adopt(null, owner())
        assertTrue(P2pEndpoint.acceptAllowed(a.generation, a))
        val b = P2pEndpoint.adopt(a, owner(iface = "p2p-wlan0-26", net = "118"))
        // the loop born with A returns now, after the group was rebuilt
        assertFalse("a socket from generation 1 must be refused under generation 2", P2pEndpoint.acceptAllowed(a.generation, b))
        assertTrue(P2pEndpoint.acceptAllowed(b.generation, b))
        // and nothing is accepted into a torn down endpoint
        assertFalse(P2pEndpoint.acceptAllowed(b.generation, null))
        assertFalse(P2pEndpoint.acceptAllowed(1, P2pEndpoint.Endpoint(1, P2pPlan.Role.GROUP_OWNER, ownerIface, "", "")))
    }

    // ---- 4. which socket belongs to which network ---------------------------------------------------

    @Test
    fun the_local_sockets_are_on_p2p_and_the_provider_upstream_stays_on_wifi() {
        // only a p2p interface may carry the local ProkNet link
        assertTrue(P2pPlan.isP2pIface("p2p-wlan0-25"))
        assertTrue(P2pPlan.isP2pIface("p2p0"))
        assertFalse(P2pPlan.isP2pIface("wlan0"))
        assertFalse(P2pPlan.isP2pIface(null))

        // the seller listener is on its own P2P address, the buyer dials from its own
        val seller = P2pEndpoint.adopt(null, owner())
        assertEquals(P2pEndpoint.Verdict.VALID, P2pEndpoint.validate(seller, listenerFor(seller)))
        val buyer = P2pEndpoint.adopt(null, P2pEndpoint.Observed(P2pPlan.Role.CLIENT, buyerIface, buyerAddr, "77"))
        val line = P2pEndpoint.dialLine(1, P2pPlan.DIAL_ATTEMPTS, buyer.localAddress, ownerAddr, P2pPlan.PORT, buyer.interfaceName, buyer.networkIdentity, true)
        assertTrue(line.contains("192.168.49.124 -> 192.168.49.1:47742"))
        assertTrue(line.contains("socket bound to P2P network=true"))
        assertTrue(P2pEndpoint.dialLine(2, 6, "", ownerAddr, P2pPlan.PORT, "", "", false).contains("socket bound to P2P network=false"))

        // and the provider's Internet still goes out of the home Wi-Fi: a p2p interface is a LOCAL link,
        // never an upstream, which is what keeps the Freebox connection out of this entirely
        assertTrue(P2pPlan.isLocalLinkIface("p2p-wlan0-25"))
        assertTrue(P2pPlan.isLocalLinkIface("p2p0"))
        assertFalse("wlan0 is the seller upstream and must stay usable as one", P2pPlan.isLocalLinkIface("wlan0"))
    }

    // ---- 5. a second session after the first one ended ----------------------------------------------

    @Test
    fun a_second_session_builds_a_new_endpoint_and_a_new_listener() {
        val first = P2pEndpoint.adopt(null, owner())
        val firstListener = listenerFor(first)
        assertEquals(P2pEndpoint.Verdict.VALID, P2pEndpoint.validate(first, firstListener))

        // the session ends: the group goes away, so there is no endpoint at all
        assertEquals(P2pEndpoint.Verdict.NO_ENDPOINT, P2pEndpoint.validate(null, firstListener))
        assertFalse(P2pEndpoint.acceptAllowed(first.generation, null))

        // the seller shares again: a new group, a new generation, a new listener
        val second = P2pEndpoint.adopt(first, owner(iface = "p2p-wlan0-27", net = "133"))
        assertEquals(2, second.generation)
        assertEquals(P2pEndpoint.Verdict.STALE_GENERATION, P2pEndpoint.validate(second, firstListener))
        assertEquals(P2pEndpoint.Verdict.VALID, P2pEndpoint.validate(second, listenerFor(second)))
        assertTrue(P2pEndpoint.changeReason(null, owner()).contains("first endpoint"))
        assertEquals("nothing changed", P2pEndpoint.changeReason(second, owner(iface = "p2p-wlan0-27", net = "133")))
    }

    // ---- the buyer stops waiting, with a reason -----------------------------------------------------

    @Test
    fun a_formed_group_with_no_transport_ends_the_purchase_instead_of_spinning() {
        // no group yet: keep waiting, the join ladder owns that time
        assertEquals(P2pPlan.TransportStep.WAIT, P2pPlan.transportStep(groupFormed = false, linkUp = false, msSinceGroup = 0))
        // the group is formed and the dial window is running
        assertEquals(P2pPlan.TransportStep.WAIT, P2pPlan.transportStep(true, false, 10_000))
        assertEquals(P2pPlan.TransportStep.WAIT, P2pPlan.transportStep(true, false, P2pPlan.TRANSPORT_GIVE_UP_MS - 1))
        // the six dial attempts are over and nothing answered: fail, do not spin
        assertEquals(P2pPlan.TransportStep.FAIL_NO_TRANSPORT, P2pPlan.transportStep(true, false, P2pPlan.TRANSPORT_GIVE_UP_MS))
        // the link came up: nothing to decide
        assertEquals(P2pPlan.TransportStep.DONE, P2pPlan.transportStep(true, true, 10 * P2pPlan.TRANSPORT_GIVE_UP_MS))

        // the window must outlast the whole dial ladder, or we would give up while it is still trying
        val dialWindow = P2pPlan.DIAL_ATTEMPTS * (P2pPlan.DIAL_TIMEOUT_MS + P2pPlan.DIAL_GAP_MS)
        assertTrue("the give up time must cover the dial window", P2pPlan.TRANSPORT_GIVE_UP_MS > dialWindow)

        // and the customer is told in French what happened
        assertEquals("Connexion locale créée, mais le fournisseur ne répond pas.", ProductState.lostHint(P2pPlan.TRANSPORT_FAIL_REASON))
    }
}
