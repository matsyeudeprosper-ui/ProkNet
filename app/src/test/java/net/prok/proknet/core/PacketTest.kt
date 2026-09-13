package net.prok.proknet.core

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Pure-JVM tests of the wire format (v4 with v1-v3 decoding). No Android, no Bluetooth. */
class PacketTest {

    private fun id(seed: Int) = ByteArray(Packet.ID_LEN) { i -> ((seed * 31 + i) and 0xFF).toByte() }
    private fun msgId(seed: Int) = ByteArray(Packet.MSG_ID_LEN) { i -> ((seed * 7 + i) and 0xFF).toByte() }
    private val a = id(1)
    private val b = id(2)
    private val c = id(3)

    @Test
    fun encode_decode_round_trip_keeps_every_field() {
        val p = Packet(a, c, msgId(9), 1_700_000_000_123L, "héllo wörld ✓", ttl = 3, hops = 0, lastHopId = a)
        val bytes = p.encode()
        assertEquals(Packet.HEADER_LEN + "héllo wörld ✓".toByteArray(Charsets.UTF_8).size, bytes.size)
        val d = Packet.decode(bytes)
        assertNotNull(d)
        d!!
        assertArrayEquals(a, d.originId)
        assertArrayEquals(c, d.destId)
        assertArrayEquals(a, d.lastHopId)
        assertArrayEquals(msgId(9), d.msgId)
        assertEquals(1_700_000_000_123L, d.timestamp)
        assertEquals("héllo wörld ✓", d.text)
        assertEquals(3, d.ttl)
        assertEquals(0, d.hops)
        assertEquals(Packet.TYPE_TEXT, d.type)
        assertEquals(4, d.wireVersion)
        assertFalse(d.legacy)
        assertFalse(d.isEncrypted)
    }

    @Test
    fun envelope_and_chunk_types_carry_opaque_payloads() {
        val payload = ByteArray(300) { (it * 13).toByte() }
        for (type in listOf(Packet.TYPE_ENVELOPE, Packet.TYPE_CHUNK)) {
            val p = Packet(a, c, msgId(2), 5L, payload, type)
            val d = Packet.decode(p.encode())!!
            assertEquals(type, d.type)
            assertArrayEquals(payload, d.payload)
            assertEquals(type == Packet.TYPE_ENVELOPE, d.isEncrypted)
            assertEquals(type == Packet.TYPE_CHUNK, d.isChunk)
        }
        // aad covers exactly origin, dest, msgId, ts, type and NOT lastHop/hops/ttl
        val p1 = Packet(a, c, msgId(2), 5L, payload, Packet.TYPE_ENVELOPE, ttl = 3, hops = 0, lastHopId = a)
        val p2 = Packet(a, c, msgId(2), 5L, payload, Packet.TYPE_ENVELOPE, ttl = 7, hops = 2, lastHopId = b)
        assertArrayEquals(p1.aad(), p2.aad())
        assertFalse(p1.aad().contentEquals(Packet(a, c, msgId(3), 5L, payload, Packet.TYPE_ENVELOPE).aad()))
        assertFalse(p1.aad().contentEquals(Packet(a, c, msgId(2), 5L, payload, Packet.TYPE_CHUNK).aad()))
    }

    @Test
    fun max_payload_fits_in_512_bytes_and_longer_is_refused() {
        val ok = Packet(a, c, msgId(1), 0, "x".repeat(Packet.MAX_PAYLOAD_BYTES))
        assertEquals(Packet.MAX_PACKET_BYTES, ok.encode().size)
        assertNotNull(Packet.decode(ok.encode()))
        val tooLong = Packet(a, c, msgId(1), 0, "x".repeat(Packet.MAX_PAYLOAD_BYTES + 1))
        var threw = false
        try { tooLong.encode() } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
    }

    @Test
    fun origin_destination_and_message_id_never_change_through_a_relay() {
        val original = Packet.text(a, c, "carry me")
        val onAirFromA = original.stamped(a)                    // A transmits (hops 0)
        val storedAtB = Packet.decode(onAirFromA.encode())!!
        val onAirFromB = storedAtB.stamped(b, extraHops = 1)    // B forwards (hops 1)
        val receivedAtC = Packet.decode(onAirFromB.encode())!!
        assertArrayEquals(original.originId, receivedAtC.originId)
        assertArrayEquals(original.destId, receivedAtC.destId)
        assertArrayEquals(original.msgId, receivedAtC.msgId)
        assertEquals(original.timestamp, receivedAtC.timestamp)
        assertArrayEquals(original.payload, receivedAtC.payload)
    }

    @Test
    fun last_hop_changes_A_to_B_to_C_and_hops_increment() {
        val original = Packet.text(a, c, "hop")
        assertFalse(original.hasLastHop)
        val fromA = Packet.decode(original.stamped(a).encode())!!
        assertArrayEquals(a, fromA.lastHopId)
        assertEquals(0, fromA.hops)
        val fromB = Packet.decode(fromA.stamped(b, extraHops = 1).encode())!!
        assertArrayEquals(b, fromB.lastHopId)
        assertEquals(1, fromB.hops)
        assertArrayEquals(a, fromB.originId)
        assertEquals(fromB.originShort, a.toHex().substring(0, 8))
        assertEquals(fromB.lastHopShort, b.toHex().substring(0, 8))
    }

    @Test
    fun isFor_matches_full_id_and_short_id_form_only() {
        val p = Packet(a, c, msgId(1), 0, "t")
        assertTrue(p.isFor(c))
        assertFalse(p.isFor(b))
        val shortForm = Packet(a, Packet.destFromShort(c.toHex().substring(0, 8)), msgId(1), 0, "t")
        assertTrue(shortForm.isFor(c))
        assertFalse(shortForm.isFor(b))
        val cPrime = c.copyOf().also { it[15] = (it[15] + 1).toByte() }
        assertFalse(p.isFor(cPrime))
    }

    @Test
    fun malformed_and_truncated_packets_decode_to_null_without_throwing() {
        val good = Packet(a, c, msgId(1), 0, "hello world").encode()
        assertNull(Packet.decode(null))
        assertNull(Packet.decode(ByteArray(0)))
        assertNull(Packet.decode(byteArrayOf(0x50, 0x4B)))
        assertNull(Packet.decode(ByteArray(600)))
        assertNull(Packet.decode(good.copyOfRange(0, Packet.HEADER_LEN - 1)))
        assertNull(Packet.decode(good.copyOfRange(0, good.size - 3)))
        assertNull(Packet.decode(good + byteArrayOf(1, 2, 3)))
        assertNull(Packet.decode(good.copyOf().also { it[0] = 0x00 }))
        assertNull(Packet.decode(good.copyOf().also { it[2] = 9 }))
        assertNull(Packet.decode(good.copyOf().also { it[3] = 7 }))
        for (n in 0 until good.size) assertNull("truncated to " + n, Packet.decode(good.copyOfRange(0, n)))
        val rnd = java.util.Random(42)
        repeat(500) {
            val g = ByteArray(2 + rnd.nextInt(598)).also { rnd.nextBytes(it) }
            g[0] = 0x50; g[1] = 0x4B
            Packet.decode(g)
        }
    }

    @Test
    fun v3_packets_still_decode_with_last_hop() {
        val text = "from v0.4.1".toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(Packet.HEADER_LEN_V3 + text.size)
        buf.put(Packet.MAGIC); buf.put(3); buf.put(1)
        buf.put(a); buf.put(c); buf.put(b); buf.put(msgId(4)); buf.putLong(1234L); buf.put(3); buf.put(1)
        buf.putShort(text.size.toShort()); buf.put(text)
        val d = Packet.decode(buf.array())!!
        assertEquals(3, d.wireVersion)
        assertArrayEquals(a, d.originId); assertArrayEquals(c, d.destId); assertArrayEquals(b, d.lastHopId)
        assertEquals(1, d.hops); assertEquals("from v0.4.1", d.text); assertTrue(d.isFor(c))
    }

    @Test
    fun v2_packets_still_decode_with_unknown_last_hop() {
        val text = "from v0.4".toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(Packet.HEADER_LEN_V2 + text.size)
        buf.put(Packet.MAGIC); buf.put(2); buf.put(1)
        buf.put(a); buf.put(c); buf.put(msgId(4)); buf.putLong(1234L); buf.put(3); buf.put(0)
        buf.putShort(text.size.toShort()); buf.put(text)
        val d = Packet.decode(buf.array())!!
        assertEquals(2, d.wireVersion)
        assertArrayEquals(a, d.originId)
        assertArrayEquals(c, d.destId)
        assertFalse(d.hasLastHop)
        assertEquals("from v0.4", d.text)
        assertTrue(d.isFor(c)); assertFalse(d.isFor(b))
    }

    @Test
    fun v1_packets_still_decode_as_addressed_to_the_receiver() {
        val text = "from v0.1".toByteArray(Charsets.UTF_8)
        val buf = ByteBuffer.allocate(Packet.HEADER_LEN_V1 + text.size)
        buf.put(Packet.MAGIC); buf.put(1); buf.put(1)
        buf.put(a); buf.put(msgId(5)); buf.putLong(99L)
        buf.putShort(text.size.toShort()); buf.put(text)
        val d = Packet.decode(buf.array())!!
        assertEquals(1, d.wireVersion)
        assertTrue(d.legacy)
        assertTrue(d.isFor(b)); assertTrue(d.isFor(c))
        assertEquals(0, d.ttl)
        assertArrayEquals(a, d.originId)
        assertEquals("from v0.1", d.text)
    }

    @Test
    fun legacy_destination_padding_matches_the_receiver_by_short_id() {
        val padded = Routing.legacyDestHex(c.toHex().substring(0, 8))
        assertEquals(32, padded.length)
        val p = Packet(a, padded.hexToBytes(), msgId(1), 0, "old row")
        assertTrue(p.isFor(c))
        assertFalse(p.isFor(b))
        assertArrayEquals(padded.hexToBytes(), Routing.destBytes(padded, "ignored"))
        assertArrayEquals(Packet.destFromShort(c.toHex().substring(0, 8)), Routing.destBytes("", c.toHex().substring(0, 8)))
    }
}
