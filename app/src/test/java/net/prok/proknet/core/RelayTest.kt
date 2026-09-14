package net.prok.proknet.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The relay envelope: only the two ends can read it, the relay only forwards. */
class RelayTest {
    private val a = Crypto.generateKeyPair(); private val aPub = Crypto.publicBytes(a.public); private val aId = Crypto.deriveId(aPub)
    private val b = Crypto.generateKeyPair(); private val bPub = Crypto.publicBytes(b.public)
    private val c = Crypto.generateKeyPair(); private val cPub = Crypto.publicBytes(c.public); private val cId = Crypto.deriveId(cPub)
    private val aShort = aId.toHex().substring(0, 8)

    @Test
    fun buyer_and_seller_derive_the_same_key_and_the_relay_cannot_read_the_frame() {
        val kA = Crypto.agree(a.private, cPub, Relay.keyInfo(aId, cId))
        val kC = Crypto.agree(c.private, aPub, Relay.keyInfo(cId, aId))
        assertArrayEquals(kA, kC)
        assertEquals(32, kA.size)
        val frame = Tunnel.encode(Tunnel.T_OPEN_TCP, 7, Tunnel.openTcp("example.com", 443))
        val sealed = Relay.seal(kA, aShort, frame)
        assertEquals(aShort, Relay.peek(sealed)!!.originShort)
        assertArrayEquals(frame, Relay.open(kC, sealed))
        // the relay B: same info, its own key -> nothing
        val kB = Crypto.agree(b.private, cPub, Relay.keyInfo(aId, cId))
        assertNull(Relay.open(kB, sealed))
        // tampering: one byte of ciphertext, or the origin in the clear header
        val t1 = sealed.copyOf(); t1[t1.size - 1] = (t1[t1.size - 1].toInt() xor 1).toByte(); assertNull(Relay.open(kC, t1))
        val t2 = sealed.copyOf(); t2[2] = (t2[2].toInt() xor 1).toByte(); assertNull(Relay.open(kC, t2))
        assertNull(Relay.open(kC, sealed.copyOf(Relay.HEADER + 3)))
        assertNull(Relay.peek(ByteArray(0))); assertNull(Relay.open(kC, null))
        // two seals of the same frame differ (fresh nonce), both open
        val again = Relay.seal(kA, aShort, frame)
        assertFalse(sealed.contentEquals(again)); assertArrayEquals(frame, Relay.open(kC, again))
        // the sealed frame fits a link frame even for the largest tunnel frame
        val big = Relay.seal(kA, aShort, Tunnel.encode(Tunnel.T_TCP_DATA, 1, ByteArray(Tunnel.MAX_DATA)))
        assertTrue(1 + big.size <= Wire.MAX_FRAME)
    }

    @Test
    fun introductions_carry_self_certifying_records() {
        val rec = Wire.identityRecord(cId, cPub, "Seller C")
        val bytes = Relay.info(Relay.ROLE_UPSTREAM_SELLER, 5, Market.flags(true, false, true, Tunnel.UP_CELLULAR), rec)
        val i = Relay.parseInfo(bytes)!!
        assertEquals(Relay.ROLE_UPSTREAM_SELLER, i.role); assertEquals(5, i.pricePerMb); assertTrue(i.flags and Market.FLAG_SELL != 0)
        assertEquals("Seller C", i.record!!.name); assertArrayEquals(cId, i.record!!.id)
        // a record whose id does not match its key is refused: a relay cannot invent a seller
        val forged = Wire.identityRecord(aId, cPub, "Fake")
        assertNull(Relay.parseInfo(Relay.info(Relay.ROLE_UPSTREAM_SELLER, 5, 0, forged)))
        // PEER_GONE needs no record; the other roles do
        assertNotNull(Relay.parseInfo(Relay.info(Relay.ROLE_PEER_GONE, 0, 0, null)))
        assertNull(Relay.parseInfo(Relay.info(Relay.ROLE_DOWNSTREAM_BUYER, 0, 0, null)))
        assertNull(Relay.parseInfo(byteArrayOf(9, 1, 0, 0, 0)))
        assertNull(Relay.parseInfo(null))
    }

    @Test
    fun the_relay_forwards_to_the_other_side_and_counts() {
        assertEquals(Relay.Side.UP, Relay.forwardTo(Relay.Side.DOWN))
        assertEquals(Relay.Side.DOWN, Relay.forwardTo(Relay.Side.UP))
        val s = Relay.Session("r1", "cccc0000", "aaaa0000", 1000)
        s.count(Relay.Side.UP, 100); s.count(Relay.Side.UP, 50); s.count(Relay.Side.DOWN, 7)
        assertEquals(150L, s.bytesToUp); assertEquals(2L, s.framesToUp); assertEquals(7L, s.bytesToDown); assertEquals(1L, s.framesToDown)
        s.end("buyer link closed", 61_000)
        assertEquals(60_000L, s.durationMs)
        assertTrue(s.summary().contains("ended: buyer link closed"))
    }
}
