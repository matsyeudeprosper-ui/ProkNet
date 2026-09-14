package net.prok.proknet.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression for the v0.9.0 three-phone hardware test. The radio topology
 * worked (relay held its upstream client link and its own hotspot at once,
 * STA+AP concurrency true, both links WIFI UP), but no byte ever flowed:
 * the relay introduced both sides ONCE, when its second link came up, and
 * the buyer pressed CONNECT minutes later. The buyer then waited 20 s for an
 * introduction that had already happened and gave up; the relay's counters
 * stayed at 0 B / 0 B.
 *
 * v0.9.1 makes it a request/answer handshake. These tests drive the pure
 * decisions with the exact shape of that failure.
 */
class RelayHandshakeTest {
    private val A = "0f7d57b3"   // buyer, as on the phones
    private val B = "24e480e6"   // relay
    private val C = "e7a472b6"   // seller

    @Test
    fun a_buyer_that_asks_late_is_introduced_although_the_session_already_exists() {
        // the relay's two links came up and a session was created (introductions sent once)
        assertEquals(Relay.LinkChange.START, Relay.onLinks(relayMode = true, sessionUp = null, sessionDown = null, up = C, down = A))
        val s = Relay.Session("432805bb", C, A, 0)
        s.introductionsDown = 1; s.introductionsUp = 1

        // minutes pass; nothing changes, so v0.9.0 did nothing more
        assertEquals(Relay.LinkChange.KEEP, Relay.onLinks(true, s.upPeer, s.downPeer, C, A))

        // NOW the buyer taps CONNECT and asks. The answer depends on the current state only.
        assertEquals(Relay.Answer.INTRODUCE, Relay.onIntroRequest(relayMode = true, downPeer = A, upPeer = C, sellerSelling = true, from = A))
        // and asking again is just as good (idempotent)
        assertEquals(Relay.Answer.INTRODUCE, Relay.onIntroRequest(true, A, C, true, A))

        // the buyer's own ladder: ask until introduced, then start the contract
        assertEquals(Relay.BuyerStep.ASK, Relay.buyerStep(introduced = false, refused = false, attempts = 0))
        assertEquals(Relay.BuyerStep.ASK, Relay.buyerStep(false, false, 4))
        assertEquals(Relay.BuyerStep.START_CONTRACT, Relay.buyerStep(introduced = true, refused = false, attempts = 3))
    }

    @Test
    fun the_relay_answers_even_when_it_has_no_seller_so_the_buyer_fails_fast() {
        assertEquals(Relay.Answer.NO_UPSTREAM, Relay.onIntroRequest(true, A, null, false, A))          // no upstream link
        assertEquals(Relay.Answer.NO_UPSTREAM, Relay.onIntroRequest(true, A, C, sellerSelling = false, from = A)) // linked, not selling
        assertEquals(Relay.Answer.NOT_MY_BUYER, Relay.onIntroRequest(true, "other000", C, true, A))
        assertEquals(Relay.Answer.NOT_MY_BUYER, Relay.onIntroRequest(true, null, C, true, A))
        assertEquals(Relay.Answer.NOT_A_RELAY, Relay.onIntroRequest(relayMode = false, downPeer = A, upPeer = C, sellerSelling = true, from = A))
        // refused -> stop now with a clear reason, never a silent 20 s wait
        assertEquals(Relay.BuyerStep.NO_SELLER, Relay.buyerStep(introduced = false, refused = true, attempts = 1))
        // unanswered -> give up after the last attempt
        assertEquals(Relay.BuyerStep.GIVE_UP, Relay.buyerStep(false, false, Relay.INTRO_ATTEMPTS))
        assertTrue(Relay.INTRO_ATTEMPTS >= 3 && Relay.INTRO_RETRY_MS in 1000..5000)
    }

    /**
     * The whole first-frame sequence with both links already up and the
     * session already formed: the late buyer asks, both sides are introduced,
     * and the buyer's CONTRACT_PROPOSE reaches the seller's gateway.
     */
    @Test
    fun after_a_late_introduction_the_contract_proposal_is_forwarded_to_the_seller() {
        val buyer = Crypto.generateKeyPair(); val buyerPub = Crypto.publicBytes(buyer.public); val buyerId = Crypto.deriveId(buyerPub)
        val seller = Crypto.generateKeyPair(); val sellerPub = Crypto.publicBytes(seller.public); val sellerId = Crypto.deriveId(sellerPub)
        val relayKp = Crypto.generateKeyPair()
        val buyerShort = buyerId.toHex().substring(0, 8); val sellerShort = sellerId.toHex().substring(0, 8)
        val s = Relay.Session("432805bb", sellerShort, buyerShort, 0)
        s.introductionsDown = 1; s.introductionsUp = 1                       // the unsolicited pair, long ago

        // 1. buyer asks (carries its own record so the relay can introduce it even with a stale copy)
        val request = Relay.info(Relay.ROLE_INTRO_REQUEST, 0, 0, Wire.identityRecord(buyerId, buyerPub, "Buyer A"))
        val parsedRequest = Relay.parseInfo(request)!!
        assertEquals(Relay.ROLE_INTRO_REQUEST, parsedRequest.role)
        assertEquals(buyerShort, parsedRequest.record!!.shortId)
        assertEquals(Relay.Answer.INTRODUCE, Relay.onIntroRequest(true, s.downPeer, s.upPeer, true, buyerShort))

        // 2. the relay introduces both sides again
        val toBuyer = Relay.parseInfo(Relay.info(Relay.ROLE_UPSTREAM_SELLER, 5, Market.flags(true, false, true, Tunnel.UP_CELLULAR), Wire.identityRecord(sellerId, sellerPub, "Seller C")))!!
        val toSeller = Relay.parseInfo(Relay.info(Relay.ROLE_DOWNSTREAM_BUYER, 0, 0, Wire.identityRecord(buyerId, buyerPub, "Buyer A")))!!
        s.introductionsDown++; s.introductionsUp++
        assertEquals(5, toBuyer.pricePerMb); assertEquals(sellerShort, toBuyer.record!!.shortId); assertEquals(buyerShort, toSeller.record!!.shortId)

        // 3. both sides acknowledge, so the relay knows the applications processed it
        val ackFromBuyer = Relay.parseInfo(Relay.info(Relay.ROLE_INTRO_ACK, 0, 0, Wire.identityRecord(sellerId, sellerPub, "Seller C")))!!
        val ackFromSeller = Relay.parseInfo(Relay.info(Relay.ROLE_INTRO_ACK, 0, 0, Wire.identityRecord(buyerId, buyerPub, "Buyer A")))!!
        if (ackFromBuyer.record!!.shortId == s.upPeer) s.ackedDown = true
        if (ackFromSeller.record!!.shortId == s.downPeer) s.ackedUp = true
        assertTrue(s.ackedDown && s.ackedUp)

        // 4. the buyer proposes the contract, sealed for the seller only
        val key = Crypto.agree(buyer.private, sellerPub, Relay.keyInfo(buyerId, sellerId))
        val contract = Market.Contract(Crypto.randomBytes(8), buyerId, sellerId, 5, 0, 0, 5, 1_700_000_000_000L)
        val sig = Crypto.sign(buyer.private, Market.contractSignData(contract))
        val sealed = Relay.seal(key, buyerShort, Tunnel.encode(Tunnel.T_CONTRACT_PROPOSE, 0, Tunnel.signed(contract.encode(), sig)))

        // 5. the relay forwards it upstream without being able to read it
        assertEquals(Relay.Side.UP, Relay.forward(Relay.Side.DOWN, buyerShort, s.downPeer, s.upPeer))
        assertNull(Relay.open(Crypto.agree(relayKp.private, sellerPub, Relay.keyInfo(buyerId, sellerId)), sealed))
        s.count(Relay.Side.UP, sealed.size)
        assertEquals(1L, s.framesToUp); assertTrue(s.bytesToUp > 60)

        // 6. the seller opens it, routes it as a buyer->seller frame and verifies the signature
        val sellerKey = Crypto.agree(seller.private, buyerPub, Relay.keyInfo(sellerId, buyerId))
        val frame = Tunnel.decode(Relay.open(sellerKey, sealed))!!
        assertEquals(buyerShort, Relay.peek(sealed)!!.originShort)
        assertEquals(Tunnel.Side.GATEWAY, Tunnel.route(frame.type, providing = true))
        val body = Tunnel.parseSigned(frame.data, Market.Contract.LEN)!!
        val got = Market.Contract.decode(body.body)!!
        assertTrue(got.sameTermsAs(contract))
        assertTrue(Crypto.verify(buyerPub, Market.contractSignData(got), body.sig))
        assertEquals(buyerShort, got.buyerShort)     // the seller bills the buyer, not the relay
    }

    @Test
    fun a_closed_downstream_link_clears_the_session_and_the_same_buyer_can_come_back() {
        var s: Relay.Session? = Relay.Session("s1", C, A, 0)
        // the buyer's link drops
        assertEquals(Relay.LinkChange.END, Relay.onLinks(true, s!!.upPeer, s.downPeer, C, null))
        s = null
        // nothing to forward while there is no session
        assertNull(Relay.forward(Relay.Side.DOWN, A, null, null))
        assertEquals(Relay.Answer.NOT_MY_BUYER, Relay.onIntroRequest(true, null, C, true, A))
        // the same buyer links again -> a fresh session, and it can be introduced immediately
        assertEquals(Relay.LinkChange.START, Relay.onLinks(true, null, null, C, A))
        s = Relay.Session("s2", C, A, 0)
        assertEquals(Relay.Answer.INTRODUCE, Relay.onIntroRequest(true, A, C, true, A))
        assertEquals(Relay.Side.UP, Relay.forward(Relay.Side.DOWN, A, s.downPeer, s.upPeer))
        // a stale link on the relay must not block that: the same peer asking again means its side is gone
        val fsm = LinkState()
        fsm.request(A, 0); fsm.offerReceived(A, 1); fsm.networkAvailable(2); fsm.handshakeOk(A, 3)
        assertTrue(fsm.isUp)
        assertTrue(fsm.staleLinkRequest(A))
        assertFalse(fsm.staleLinkRequest("someone0"))
        // and the upstream changing to another seller restarts the session rather than freezing it
        assertEquals(Relay.LinkChange.RESTART, Relay.onLinks(true, C, A, "other000", A))
        assertEquals(Relay.LinkChange.END, Relay.onLinks(relayMode = false, sessionUp = C, sessionDown = A, up = C, down = A))
        assertEquals(Relay.LinkChange.IDLE, Relay.onLinks(true, null, null, C, null))
    }

    @Test
    fun frames_from_anyone_else_are_dropped_and_roles_are_well_formed() {
        assertNull(Relay.forward(Relay.Side.DOWN, "stranger", A, C))
        assertNull(Relay.forward(Relay.Side.UP, "stranger", A, C))
        assertNull(Relay.forward(Relay.Side.UP, A, A, C))            // the buyer cannot speak on the seller's link
        assertEquals(Relay.Side.DOWN, Relay.forward(Relay.Side.UP, C, A, C))
        // records: required for the four roles that carry an identity, absent for the two that do not
        for (role in Relay.ROLES_WITH_RECORD) assertNull(Relay.roleName(role), Relay.parseInfo(Relay.info(role, 0, 0, null)))
        assertNotNull(Relay.parseInfo(Relay.info(Relay.ROLE_PEER_GONE, 0, 0, null)))
        assertNotNull(Relay.parseInfo(Relay.info(Relay.ROLE_NO_UPSTREAM, 0, 0, null)))
        for (role in 1..Relay.ROLE_LAST) assertFalse(Relay.roleName(role).startsWith("role "))
        assertNull(Relay.parseInfo(byteArrayOf(Relay.VERSION.toByte(), (Relay.ROLE_LAST + 1).toByte(), 0, 0, 0)))
    }
}
