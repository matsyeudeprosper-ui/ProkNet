package net.prok.proknet.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the v0.7.0 hardware failure: the seller's frame router sent
 * the very first CONTRACT_PROPOSE to its own buyer client because no buyer
 * was registered yet, so the proposal was never answered and the buyer timed
 * out after 15 s. The route must depend on frame direction and role only.
 */
class TunnelRoutingTest {

    /** The v0.7.0 rule, kept here so the test documents exactly what was wrong. */
    private fun v070Route(type: Int, providing: Boolean, buyerShort: String?, peerShort: String): Tunnel.Side =
        if (providing && (type == Tunnel.T_SESSION_START || buyerShort == peerShort)) Tunnel.Side.GATEWAY else Tunnel.Side.CLIENT

    @Test
    fun first_contract_proposal_reaches_the_seller_gateway_before_any_buyer_is_registered() {
        // seller providing, no buyer yet, authenticated link peer "a1b2c3d4"
        assertEquals("v0.7.0 misrouted it", Tunnel.Side.CLIENT, v070Route(Tunnel.T_CONTRACT_PROPOSE, providing = true, buyerShort = null, peerShort = "a1b2c3d4"))
        assertEquals(Tunnel.Side.GATEWAY, Tunnel.route(Tunnel.T_CONTRACT_PROPOSE, providing = true))
        assertEquals(Tunnel.Side.GATEWAY, Tunnel.route(Tunnel.T_SESSION_START, providing = true))
        assertEquals(Tunnel.Side.GATEWAY, Tunnel.route(Tunnel.T_USAGE_ACK, providing = true))
    }

    @Test
    fun every_marketplace_frame_is_routed_by_direction_on_both_roles() {
        val toSeller = listOf(Tunnel.T_CONTRACT_PROPOSE, Tunnel.T_SESSION_START, Tunnel.T_OPEN_TCP, Tunnel.T_DNS_REQUEST, Tunnel.T_USAGE_ACK)
        val toBuyer = listOf(Tunnel.T_CONTRACT_ACCEPT, Tunnel.T_CONTRACT_REJECT, Tunnel.T_SESSION_OK, Tunnel.T_TCP_OPEN_OK, Tunnel.T_DNS_RESPONSE, Tunnel.T_UPSTREAM_STATE, Tunnel.T_USAGE_CHECKPOINT)
        val both = listOf(Tunnel.T_SESSION_END, Tunnel.T_TCP_DATA, Tunnel.T_TCP_CLOSE, Tunnel.T_ERROR, Tunnel.T_KEEPALIVE)
        for (t in toSeller) { assertEquals(Tunnel.typeName(t), Tunnel.Side.GATEWAY, Tunnel.route(t, true)); assertEquals(Tunnel.typeName(t), Tunnel.Side.MISDIRECTED, Tunnel.route(t, false)) }
        for (t in toBuyer) { assertEquals(Tunnel.typeName(t), Tunnel.Side.CLIENT, Tunnel.route(t, false)); assertEquals(Tunnel.typeName(t), Tunnel.Side.MISDIRECTED, Tunnel.route(t, true)) }
        for (t in both) { assertEquals(Tunnel.typeName(t), Tunnel.Side.GATEWAY, Tunnel.route(t, true)); assertEquals(Tunnel.typeName(t), Tunnel.Side.CLIENT, Tunnel.route(t, false)) }
        // every defined type is classified
        for (t in 1..Tunnel.T_LAST) assertTrue(Tunnel.typeName(t), t in toSeller || t in toBuyer || t in both)
        assertEquals(Tunnel.T_LAST, toSeller.size + toBuyer.size + both.size)
    }

    /**
     * The first-frame sequence end to end, with the real pure pieces the phones
     * use (routing, contract, signatures, hash) and only the sockets simulated.
     */
    @Test
    fun proposal_accept_and_session_start_sequence_on_a_fresh_link() {
        val buyer = Crypto.generateKeyPair(); val buyerPub = Crypto.publicBytes(buyer.public); val buyerId = Crypto.deriveId(buyerPub)
        val seller = Crypto.generateKeyPair(); val sellerPub = Crypto.publicBytes(seller.public); val sellerId = Crypto.deriveId(sellerPub)
        val now = 1_700_000_000_000L
        val sellerTerms = intArrayOf(5, 0, 0, 5)
        val sellerProviding = true
        var sellerBuyerRegistered: String? = null          // gateway.buyerShort: null until SESSION_OK
        var sellerContract: Market.Contract? = null
        var buyerContract: Market.Contract? = null
        var sellerSession = false

        // 1. buyer proposes at the advertised price (what TunnelClient.start does)
        val proposal = Market.Contract(Crypto.randomBytes(8), buyerId, sellerId, 5, 0, 0, 5, now)
        val buyerSig = Crypto.sign(buyer.private, Market.contractSignData(proposal))
        val frame1 = Tunnel.decode(Tunnel.encode(Tunnel.T_CONTRACT_PROPOSE, 0, Tunnel.signed(proposal.encode(), buyerSig)))!!

        // 2. seller side: the router must hand it to the gateway although no buyer is registered
        assertNull(sellerBuyerRegistered)
        assertEquals(Tunnel.Side.GATEWAY, Tunnel.route(frame1.type, sellerProviding))
        // 3. gateway validates and signs (what Gateway.onProposal does)
        val sb = Tunnel.parseSigned(frame1.data, Market.Contract.LEN)!!
        val c = Market.Contract.decode(sb.body)!!
        assertTrue(Crypto.verify(buyerPub, Market.contractSignData(c), sb.sig))
        assertNull(Market.acceptableProposal(c, sellerId, buyerId, sellerTerms[0], sellerTerms[1], sellerTerms[2], sellerTerms[3], now, emptySet()))
        val sellerSig = Crypto.sign(seller.private, Market.contractSignData(c))
        sellerContract = c
        val frame2 = Tunnel.decode(Tunnel.encode(Tunnel.T_CONTRACT_ACCEPT, 0, Tunnel.signed(c.hash(), sellerSig)))!!

        // 4. buyer side: accept goes to the client, buyer verifies and stores the contract
        assertEquals(Tunnel.Side.CLIENT, Tunnel.route(frame2.type, providing = false))
        val ab = Tunnel.parseSigned(frame2.data, 32)!!
        assertArrayEquals(proposal.hash(), ab.body)
        assertTrue(Crypto.verify(sellerPub, Market.contractSignData(proposal), ab.sig))
        buyerContract = proposal

        // 5. SESSION_START under the contract hash reaches the gateway and matches
        val frame3 = Tunnel.decode(Tunnel.encode(Tunnel.T_SESSION_START, 0, Tunnel.sessionStart(buyerId, buyerContract!!.hash())))!!
        assertEquals(Tunnel.Side.GATEWAY, Tunnel.route(frame3.type, sellerProviding))
        val start = Tunnel.parseSessionStart(frame3.data)!!
        assertArrayEquals(buyerId, start.buyerId)
        assertNotNull(start.contractHash)
        assertArrayEquals(sellerContract!!.hash(), start.contractHash)
        sellerSession = true; sellerBuyerRegistered = c.buyerShort
        assertTrue(sellerSession)
        assertEquals(buyerContract!!.sessionHex, sellerContract!!.sessionHex)

        // 6. and from then on the checkpoint direction is right too
        val cp = Market.nextCheckpoint(sellerContract!!, 0, 1000, 9000, now + 30_000, false)
        assertEquals(Tunnel.Side.CLIENT, Tunnel.route(Tunnel.T_USAGE_CHECKPOINT, providing = false))
        assertNull(Market.validateCheckpoint(cp, buyerContract!!, null, 1000, 9000))
        assertEquals(Tunnel.Side.GATEWAY, Tunnel.route(Tunnel.T_USAGE_ACK, providing = true))
        assertFalse(Tunnel.route(Tunnel.T_USAGE_CHECKPOINT, providing = true) == Tunnel.Side.GATEWAY)
    }
}
