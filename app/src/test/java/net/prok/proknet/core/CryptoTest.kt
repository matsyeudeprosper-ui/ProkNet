package net.prok.proknet.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests of identity keys, signatures and the end-to-end envelope. */
class CryptoTest {

    private val a = Crypto.generateKeyPair()
    private val b = Crypto.generateKeyPair()
    private val c = Crypto.generateKeyPair()
    private val aPub = Crypto.publicBytes(a.public)
    private val bPub = Crypto.publicBytes(b.public)
    private val cPub = Crypto.publicBytes(c.public)

    @Test
    fun identity_is_derived_from_the_public_key_and_is_stable() {
        val id1 = Crypto.deriveId(aPub)
        val id2 = Crypto.deriveId(Crypto.publicBytes(Crypto.publicKeyFrom(aPub)))
        assertEquals(16, id1.size)
        assertArrayEquals(id1, id2)
        assertFalse(id1.contentEquals(Crypto.deriveId(bPub)))
        // persistence model: private key round-trips through PKCS#8 bytes and yields the same public key / ID
        val privBytes = Crypto.privateBytes(a.private)
        val restored = Crypto.privateKeyFrom(privBytes)
        val sig = Crypto.sign(restored, "x".toByteArray())
        assertTrue(Crypto.verify(aPub, "x".toByteArray(), sig))
        assertEquals(64, aPub.size)
        assertEquals(8, Crypto.fingerprint(aPub).split(" ").size)
    }

    @Test
    fun public_key_bytes_round_trip_and_reject_garbage() {
        val back = Crypto.publicBytes(Crypto.publicKeyFrom(aPub))
        assertArrayEquals(aPub, back)
        var threw = false
        try { Crypto.publicKeyFrom(ByteArray(64) { 7 }) } catch (e: Exception) { threw = true }
        assertTrue("an off-curve point must be rejected", threw)
        assertFalse(Crypto.verify(ByteArray(64) { 7 }, "x".toByteArray(), ByteArray(70)))
    }

    @Test
    fun sign_and_verify_and_reject_wrong_signer_or_altered_data() {
        val data = "ProkNet".toByteArray()
        val sig = Crypto.sign(a.private, data)
        assertTrue(Crypto.verify(aPub, data, sig))
        assertFalse(Crypto.verify(bPub, data, sig))
        assertFalse(Crypto.verify(aPub, "ProkNes".toByteArray(), sig))
        assertFalse(Crypto.verify(aPub, data, sig.copyOf().also { it[10] = (it[10] + 1).toByte() }))
    }

    @Test
    fun seal_and_open_succeed_for_the_destination_only() {
        val aad = "header".toByteArray()
        val env = Crypto.seal(cPub, aad, "secret for C".toByteArray())
        assertEquals("secret for C", String(Crypto.open(c.private, cPub, aad, env)!!))
        // wrong destination (the relay B, or the sender A) cannot decrypt
        assertNull(Crypto.open(b.private, bPub, aad, env))
        assertNull(Crypto.open(a.private, aPub, aad, env))
        // two seals of the same text differ (fresh ephemeral key + nonce)
        val env2 = Crypto.seal(cPub, aad, "secret for C".toByteArray())
        assertFalse(env.contentEquals(env2))
        assertEquals(Crypto.ENVELOPE_OVERHEAD + "secret for C".length, env.size)
    }

    @Test
    fun tampered_ciphertext_or_header_fails_to_open() {
        val aad = "origin|dest|msgId|ts|type".toByteArray()
        val env = Crypto.seal(cPub, aad, "do not touch".toByteArray())
        for (i in listOf(0, 40, Crypto.PUB_LEN, Crypto.PUB_LEN + 5, Crypto.PUB_LEN + Crypto.NONCE_LEN + 2, env.size - 1)) {
            val t = env.copyOf(); t[i] = (t[i].toInt() xor 0x01).toByte()
            assertNull("byte " + i + " flipped must fail", Crypto.open(c.private, cPub, aad, t))
        }
        assertNull(Crypto.open(c.private, cPub, "origin|dest|msgId|ts|typX".toByteArray(), env))
        assertNull(Crypto.open(c.private, cPub, aad, env.copyOfRange(0, env.size - 1)))
        assertNull(Crypto.open(c.private, cPub, aad, ByteArray(10)))
        assertNull(Crypto.open(c.private, cPub, aad, null))
    }

    @Test
    fun signed_plaintext_authenticates_the_sender_inside_the_envelope() {
        val aad = "aad".toByteArray()
        val signed = Signed.build(a.private, aad, Signed.KIND_TEXT, "hello C".toByteArray())
        val env = Crypto.seal(cPub, aad, signed)
        val opened = Crypto.open(c.private, cPub, aad, env)
        val parsed = Signed.parse(opened)
        assertNotNull(parsed)
        parsed!!
        assertEquals(Signed.KIND_TEXT, parsed.kind)
        assertEquals("hello C", String(parsed.body))
        assertTrue("A signed it", Signed.verify(aPub, aad, parsed))
        assertFalse("B did not sign it", Signed.verify(bPub, aad, parsed))
        assertFalse("different header", Signed.verify(aPub, "aaX".toByteArray(), parsed))
        // a body altered after signing (would need the key to re-encrypt, but check the check)
        val forged = Signed.Parsed(parsed.sig, parsed.kind, "hello D".toByteArray())
        assertFalse(Signed.verify(aPub, aad, forged))
        assertNull(Signed.parse(null)); assertNull(Signed.parse(ByteArray(2))); assertNull(Signed.parse(byteArrayOf(0, 1, 2)))
    }

    @Test
    fun relay_sees_only_routing_metadata_and_cannot_read_or_alter_the_payload() {
        val origin = Crypto.deriveId(aPub); val dest = Crypto.deriveId(cPub); val relay = Crypto.deriveId(bPub)
        val msgId = Packet.newMsgId()
        val header = Packet(origin, dest, msgId, 1234L, ByteArray(0), Packet.TYPE_ENVELOPE)
        val signed = Signed.build(a.private, header.aad(), Signed.KIND_TEXT, "for C only".toByteArray())
        val payload = Crypto.seal(cPub, header.aad(), signed)
        val onAir = Packet(origin, dest, msgId, 1234L, payload, Packet.TYPE_ENVELOPE).stamped(origin)
        // B decodes the packet: routing fields are visible ...
        val atB = Packet.decode(onAir.encode())!!
        assertEquals(onAir.destShort, atB.destShort)
        assertEquals(onAir.originShort, atB.originShort)
        // ... but the payload is opaque to B
        assertNull(Crypto.open(b.private, bPub, atB.aad(), atB.payload))
        assertFalse(String(atB.payload, Charsets.ISO_8859_1).contains("for C only"))
        // B forwards: only lastHop and hops change, the ciphertext is byte-identical
        val fromB = atB.stamped(relay, extraHops = 1)
        assertArrayEquals(atB.payload, fromB.payload)
        assertEquals(1, fromB.hops)
        assertArrayEquals(relay, fromB.lastHopId)
        assertArrayEquals(atB.aad(), fromB.aad())
        val atC = Packet.decode(fromB.encode())!!
        val plain = Signed.parse(Crypto.open(c.private, cPub, atC.aad(), atC.payload))!!
        assertEquals("for C only", String(plain.body))
        assertTrue(Signed.verify(aPub, atC.aad(), plain))
        // if B tampers with an immutable header field, C's open fails (aad mismatch)
        val tampered = Packet(atB.originId, atB.destId, Packet.newMsgId(), atB.timestamp, atB.payload, atB.type, atB.ttl, 1, relay)
        assertNull(Crypto.open(c.private, cPub, tampered.aad(), tampered.payload))
        // if B swaps the origin to itself, the signature check exposes it even if it could re-encrypt (it cannot)
        val relabelled = Packet(relay, atB.destId, atB.msgId, atB.timestamp, atB.payload, atB.type, atB.ttl, 1, relay)
        assertNull(Crypto.open(c.private, cPub, relabelled.aad(), relabelled.payload))
        assertNotEquals(atB.originShort, relabelled.originShort)
    }
}
