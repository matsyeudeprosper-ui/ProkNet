package net.prok.proknet.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Fragmentation, reassembly and end-to-end integrity of large payloads, on the JVM. */
class TransferTest {

    private val a = Crypto.generateKeyPair()
    private val c = Crypto.generateKeyPair()
    private val b = Crypto.generateKeyPair()
    private val aPub = Crypto.publicBytes(a.public)
    private val cPub = Crypto.publicBytes(c.public)
    private val bPub = Crypto.publicBytes(b.public)

    private fun blobFor(data: ByteArray, name: String, tid: ByteArray, type: Int = Transfer.BLOB_FILE): Pair<ByteArray, ByteArray> {
        val origin = Crypto.deriveId(aPub); val dest = Crypto.deriveId(cPub)
        val header = Packet(origin, dest, tid, 777L, ByteArray(0), Packet.TYPE_CHUNK)
        val signed = Signed.build(a.private, header.aad(), Signed.KIND_BLOB, Transfer.blobBody(type, name, data))
        return Crypto.seal(cPub, header.aad(), signed) to header.aad()
    }

    @Test
    fun chunking_round_trip_in_order() {
        val data = ByteArray(10_000) { (it * 7).toByte() }
        val tid = Packet.newMsgId()
        val (blob, aad) = blobFor(data, "test.bin", tid)
        val count = Transfer.chunkCount(blob.size)
        assertEquals((blob.size + 399) / 400, count)
        val asm = Transfer.Assembler(count, blob.size, Transfer.MemoryStorage(blob.size))
        for (i in 0 until count) {
            val payload = Transfer.chunkPayload(tid, i, blob)
            assertTrue(payload.size <= Packet.MAX_PAYLOAD_BYTES)
            // each chunk travels inside a v4 CHUNK packet
            val pkt = Packet(Crypto.deriveId(aPub), Crypto.deriveId(cPub), tid, 777L, payload, Packet.TYPE_CHUNK).stamped(Crypto.deriveId(aPub))
            val decoded = Packet.decode(pkt.encode())!!
            val chunk = Transfer.parseChunk(decoded.payload)!!
            assertTrue(asm.add(chunk))
            assertEquals((i + 1) * 100 / count, asm.progressPercent())
        }
        assertTrue(asm.isComplete)
        assertArrayEquals(blob, asm.bytes())
        val opened = Signed.parse(Crypto.open(c.private, cPub, aad, asm.bytes()))!!
        assertTrue(Signed.verify(aPub, aad, opened))
        val body = Transfer.parseBody(opened.body)!!
        assertEquals("test.bin", body.name)
        assertEquals(Transfer.BLOB_FILE, body.type)
        assertTrue(body.integrityOk)
        assertArrayEquals(data, body.data)
    }

    @Test
    fun out_of_order_duplicate_and_missing_chunks() {
        val data = ByteArray(2_345) { it.toByte() }
        val tid = Packet.newMsgId()
        val (blob, _) = blobFor(data, "x", tid)
        val count = Transfer.chunkCount(blob.size)
        val asm = Transfer.Assembler(count, blob.size, Transfer.MemoryStorage(blob.size))
        val order = (0 until count).shuffled(java.util.Random(1))
        for (i in order) if (i != 2) assertTrue(asm.add(Transfer.parseChunk(Transfer.chunkPayload(tid, i, blob))!!))
        assertFalse(asm.isComplete)
        assertEquals(listOf(2), asm.missing())
        // duplicate is ignored
        assertFalse(asm.add(Transfer.parseChunk(Transfer.chunkPayload(tid, 0, blob))!!))
        // chunk from another transfer geometry is refused
        val other = Transfer.Chunk(tid, 1, count + 1, blob.size + 400, ByteArray(400))
        assertFalse(asm.add(other))
        assertTrue(asm.add(Transfer.parseChunk(Transfer.chunkPayload(tid, 2, blob))!!))
        assertTrue(asm.isComplete)
        assertArrayEquals(blob, asm.bytes())
        // received mask survives a "restart"
        val resumed = Transfer.Assembler(count, blob.size, Transfer.MemoryStorage(blob.size), asm.received)
        assertTrue(resumed.isComplete)
    }

    @Test
    fun malformed_chunks_are_rejected_without_throwing() {
        val tid = Packet.newMsgId()
        val blob = ByteArray(1000) { 1 }
        val good = Transfer.chunkPayload(tid, 1, blob)
        assertNotNull(Transfer.parseChunk(good))
        assertNull(Transfer.parseChunk(null))
        assertNull(Transfer.parseChunk(ByteArray(5)))
        assertNull(Transfer.parseChunk(good.copyOfRange(0, good.size - 1)))        // wrong data length
        assertNull(Transfer.parseChunk(good.copyOf().also { it[8] = 9 }))            // index beyond count
        assertNull(Transfer.parseChunk(good.copyOf().also { it[12] = 9 }))           // count mismatch
        assertNull(Transfer.parseChunk(good.copyOf().also { it[16] = 0x7F }))        // total too large
        val rnd = java.util.Random(3)
        repeat(300) { Transfer.parseChunk(ByteArray(rnd.nextInt(450)).also { rnd.nextBytes(it) }) }
    }

    @Test
    fun corrupted_payload_fails_integrity_and_relay_cannot_open_blob() {
        val data = ByteArray(5_000) { (it % 251).toByte() }
        val tid = Packet.newMsgId()
        val (blob, aad) = blobFor(data, "doc.txt", tid, Transfer.BLOB_TEXT)
        // relay B cannot open the blob
        assertNull(Crypto.open(b.private, bPub, aad, blob))
        // one flipped byte anywhere in the reassembled blob -> open fails (GCM), so no false success
        val bad = blob.copyOf(); bad[blob.size / 2] = (bad[blob.size / 2].toInt() xor 1).toByte()
        assertNull(Crypto.open(c.private, cPub, aad, bad))
        // body-level sha256 catches a corrupted body even if someone could re-encrypt
        val body = Transfer.parseBody(Transfer.blobBody(Transfer.BLOB_TEXT, "n", data))!!
        assertTrue(body.integrityOk)
        val corrupt = Transfer.parseBody(Transfer.blobBody(Transfer.BLOB_TEXT, "n", data).also { it[it.size - 1] = 0 })!!
        assertFalse(corrupt.integrityOk)
        assertNull(Transfer.parseBody(ByteArray(3)))
    }

    @Test
    fun transport_selection_prefers_wifi_when_up_and_negotiates_only_for_big_payloads() {
        assertEquals(Routing.TRANSPORT_WIFI, Routing.chooseTransport(wifiUp = true, bleReachable = true))
        assertEquals(Routing.TRANSPORT_WIFI, Routing.chooseTransport(wifiUp = true, bleReachable = false))
        assertEquals(Routing.TRANSPORT_BLE, Routing.chooseTransport(wifiUp = false, bleReachable = true))
        assertNull(Routing.chooseTransport(wifiUp = false, bleReachable = false))
        assertTrue(Routing.wantWifi(100_000, wifiUp = false, bleReachable = true, wifiIdle = true))
        assertFalse(Routing.wantWifi(1_000, wifiUp = false, bleReachable = true, wifiIdle = true))
        assertFalse(Routing.wantWifi(100_000, wifiUp = true, bleReachable = true, wifiIdle = true))
        assertFalse(Routing.wantWifi(100_000, wifiUp = false, bleReachable = false, wifiIdle = true))
        assertFalse(Routing.wantWifi(100_000, wifiUp = false, bleReachable = true, wifiIdle = false))
    }

    @Test
    fun text_larger_than_one_packet_goes_through_the_blob_path_intact() {
        val text = "ProkNet ".repeat(300) // 2400 chars, far above the 439-byte payload budget
        val tid = Packet.newMsgId()
        val (blob, aad) = blobFor(text.toByteArray(Charsets.UTF_8), "", tid, Transfer.BLOB_TEXT)
        assertTrue(blob.size > Packet.MAX_PAYLOAD_BYTES)
        val count = Transfer.chunkCount(blob.size)
        val asm = Transfer.Assembler(count, blob.size, Transfer.MemoryStorage(blob.size))
        for (i in 0 until count) asm.add(Transfer.parseChunk(Transfer.chunkPayload(tid, i, blob))!!)
        val body = Transfer.parseBody(Signed.parse(Crypto.open(c.private, cPub, aad, asm.bytes()))!!.body)!!
        assertEquals(text, String(body.data, Charsets.UTF_8))
        assertEquals(Transfer.BLOB_TEXT, body.type)
    }
}
