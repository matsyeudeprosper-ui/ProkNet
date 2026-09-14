package net.prok.proknet.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Identity records, Wi-Fi control messages and the Wi-Fi handshake, on the JVM. */
class WireTest {

    private val a = Crypto.generateKeyPair(); private val aPub = Crypto.publicBytes(a.public); private val aId = Crypto.deriveId(aPub)
    private val b = Crypto.generateKeyPair(); private val bPub = Crypto.publicBytes(b.public); private val bId = Crypto.deriveId(bPub)

    @Test
    fun identity_record_round_trips_and_is_self_authenticating() {
        val rec = Wire.identityRecord(aId, aPub, "Mike's phone")
        val p = Wire.parseIdentityRecord(rec)!!
        assertArrayEquals(aId, p.id); assertArrayEquals(aPub, p.pub); assertEquals("Mike's phone", p.name)
        assertEquals(aId.toHex().substring(0, 8), p.shortId)
        // an ID that is not derived from the key is refused: nobody can claim another phone's ID
        assertNull(Wire.parseIdentityRecord(Wire.identityRecord(bId, aPub, "liar")))
        assertNull(Wire.parseIdentityRecord(rec.copyOfRange(0, rec.size - 2)))
        assertNull(Wire.parseIdentityRecord(null)); assertNull(Wire.parseIdentityRecord(ByteArray(3)))
        assertNull(Wire.parseIdentityRecord(rec.copyOf().also { it[0] = 1 }))
        // long names are truncated, not rejected
        assertNotNull(Wire.parseIdentityRecord(Wire.identityRecord(aId, aPub, "x".repeat(300))))
    }

    @Test
    fun wifi_control_messages_round_trip_and_reject_garbage() {
        val req = Wire.parseControl(Wire.wifiRequest(47741)) as Wire.Control.WifiRequest
        assertEquals(47741, req.port)
        val offer = Wire.parseControl(Wire.wifiOffer("AndroidShare_1234", "s3cretpass", 47741, listOf("192.168.43.1", "10.0.0.1"))) as Wire.Control.WifiOffer
        assertEquals("AndroidShare_1234", offer.ssid); assertEquals("s3cretpass", offer.pass); assertEquals(47741, offer.port)
        assertEquals(listOf("192.168.43.1", "10.0.0.1"), offer.ips)
        assertEquals(Wire.SEC_UNKNOWN, offer.security); assertFalse(offer.hidden)
        val o2 = Wire.parseControl(Wire.wifiOffer("S", "p", 5, listOf("10.0.0.1"), Wire.SEC_WPA3, true)) as Wire.Control.WifiOffer
        assertEquals(Wire.SEC_WPA3, o2.security); assertTrue(o2.hidden)
        // a v0.5.0 offer without the trailing fields still parses
        val old = Wire.wifiOffer("S", "p", 5, listOf("10.0.0.1")).let { it.copyOfRange(0, it.size - 2) }
        val o3 = Wire.parseControl(old) as Wire.Control.WifiOffer
        assertEquals("S", o3.ssid); assertEquals(Wire.SEC_UNKNOWN, o3.security)
        // an offer without IPs is still valid: the client can learn the host from DHCP
        assertNotNull(Wire.parseControl(Wire.wifiOffer("S", "p", 5, emptyList())))
        assertEquals(listOf(Wire.SEC_WPA2), Wire.joinAttempts(Wire.SEC_WPA2))
        assertEquals(listOf(Wire.SEC_WPA3), Wire.joinAttempts(Wire.SEC_WPA3))
        assertEquals(listOf(Wire.SEC_WPA2, Wire.SEC_WPA3), Wire.joinAttempts(Wire.SEC_TRANSITION))
        assertEquals(listOf(Wire.SEC_WPA2, Wire.SEC_WPA3), Wire.joinAttempts(Wire.SEC_UNKNOWN))
        assertTrue(Wire.parseControl(Wire.wifiCancel()) is Wire.Control.WifiCancel)
        // v0.9.4: a cancel says why, and a cancel from an older build (no reason byte) still parses
        assertEquals(Wire.CANCEL_BUSY, (Wire.parseControl(Wire.wifiCancel(Wire.CANCEL_BUSY)) as Wire.Control.WifiCancel).reason)
        assertEquals(Wire.CANCEL_NO_HOTSPOT, (Wire.parseControl(Wire.wifiCancel(Wire.CANCEL_NO_HOTSPOT)) as Wire.Control.WifiCancel).reason)
        assertEquals(Wire.CANCEL_GENERIC, (Wire.parseControl(byteArrayOf(Wire.OP_WIFI_CANCEL.toByte())) as Wire.Control.WifiCancel).reason)
        assertTrue(Wire.cancelReasonText(Wire.CANCEL_BUSY).contains("already serving"))
        assertTrue(Wire.cancelReasonText(Wire.CANCEL_NO_HOTSPOT).contains("hotspot"))
        assertNull(Wire.parseControl(null)); assertNull(Wire.parseControl(ByteArray(0))); assertNull(Wire.parseControl(byteArrayOf(9)))
        assertNull(Wire.parseControl(Wire.wifiOffer("xy", "", 1, listOf("1.2.3.4")).copyOfRange(0, 3)))
        val rnd = java.util.Random(5)
        repeat(300) { Wire.parseControl(ByteArray(rnd.nextInt(80)).also { rnd.nextBytes(it) }) }
    }

    @Test
    fun wifi_handshake_authenticates_both_sides_and_binds_nonces() {
        val na = Crypto.randomBytes(16); val nb = Crypto.randomBytes(16)
        val helloA = Wire.parseHello(Wire.hello(aId, aPub, "A", na))!!
        val helloB = Wire.parseHello(Wire.hello(bId, bPub, "B", nb))!!
        assertArrayEquals(na, helloA.nonce); assertEquals("B", helloB.record.name)
        // A signs (A, B, na, nb); B verifies with A's key using the same order from its own view
        val sigA = Crypto.sign(a.private, Wire.authData(aId, bId, na, nb))
        assertTrue(Crypto.verify(helloA.record.pub, Wire.authData(helloA.record.id, bId, helloA.nonce, nb), sigA))
        // replay with a different nonce fails; a different key fails
        assertFalse(Crypto.verify(aPub, Wire.authData(aId, bId, Crypto.randomBytes(16), nb), sigA))
        assertFalse(Crypto.verify(bPub, Wire.authData(aId, bId, na, nb), sigA))
        // an attacker presenting B's record cannot produce B's AUTH
        val forged = Crypto.sign(a.private, Wire.authData(bId, aId, nb, na))
        assertFalse(Crypto.verify(bPub, Wire.authData(bId, aId, nb, na), forged))
        assertNull(Wire.parseHello(ByteArray(10)))
    }

    @Test
    fun tcp_receipt_frames_round_trip() {
        val msgId = Packet.newMsgId()
        val r = Wire.parseReceipt(Wire.receiptPayload(Routing.RECEIPT_ACCEPTED, msgId))!!
        assertEquals(Routing.RECEIPT_ACCEPTED, r.status); assertArrayEquals(msgId, r.msgId)
        assertNull(Wire.parseReceipt(ByteArray(3)))
        val f = Wire.frame(Wire.FRAME_PACKET, byteArrayOf(1, 2, 3))
        assertEquals(Wire.FRAME_PACKET, f[0].toInt()); assertEquals(4, f.size)
    }
}
