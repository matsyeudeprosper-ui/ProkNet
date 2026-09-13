package net.prok.proknet.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** IPv4/TCP/UDP building, parsing and checksums, on the JVM. */
class TcpipTest {

    private val a = Tcpip.ipToInt("10.8.0.2")
    private val b = Tcpip.ipToInt("93.184.216.34")

    @Test
    fun ip_conversion_round_trips() {
        assertEquals("10.8.0.2", Tcpip.ipToString(a))
        assertEquals("255.255.255.255", Tcpip.ipToString(Tcpip.ipToInt("255.255.255.255")))
        assertEquals("0.0.0.0", Tcpip.ipToString(0))
    }

    @Test
    fun rfc1071_checksum_known_vector() {
        // Classic example: 0x4500 0x0073 0x0000 0x4000 0x4011 0x0000(cs) 0xc0a8 0x0001 0xc0a8 0x00c7 -> checksum 0xb861
        val hdr = byteArrayOf(0x45, 0x00, 0x00, 0x73, 0x00, 0x00, 0x40, 0x00, 0x40, 0x11, 0x00, 0x00,
            0xc0.toByte(), 0xa8.toByte(), 0x00, 0x01, 0xc0.toByte(), 0xa8.toByte(), 0x00, 0xc7.toByte())
        assertEquals(0xb861, Tcpip.checksum(hdr, 0, 20, 0))
        hdr[10] = 0xb8.toByte(); hdr[11] = 0x61
        assertTrue(Tcpip.ipChecksumOk(hdr))
    }

    @Test
    fun tcp_segment_builds_and_parses_with_valid_checksums() {
        val payload = "GET / HTTP/1.0\r\n\r\n".toByteArray()
        val pkt = Tcpip.buildTcp(a, b, 51000, 443, 0xFFFFFFF0L, 12345L, Tcpip.TCP_PSH or Tcpip.TCP_ACK, 65535, payload, mssOption = 1360, ipId = 7)
        val ip = Tcpip.parseIp4(pkt)!!
        assertEquals(a, ip.srcIp); assertEquals(b, ip.dstIp); assertEquals(Tcpip.PROTO_TCP, ip.protocol)
        assertEquals(pkt.size, ip.totalLen); assertEquals(7, ip.id)
        assertTrue(Tcpip.ipChecksumOk(pkt))
        assertTrue(Tcpip.transportChecksumOk(pkt, ip))
        val t = Tcpip.parseTcp(ip.payload)!!
        assertEquals(51000, t.srcPort); assertEquals(443, t.dstPort)
        assertEquals(0xFFFFFFF0L, t.seq); assertEquals(12345L, t.ack)
        assertTrue(t.isAck); assertFalse(t.syn); assertEquals(1360, t.mss)
        assertArrayEquals(payload, t.payload)
        // corrupt one payload byte: transport checksum fails, IP checksum still fine
        val bad = pkt.copyOf(); bad[bad.size - 3] = 'X'.code.toByte()
        assertFalse(Tcpip.transportChecksumOk(bad, Tcpip.parseIp4(bad)!!))
        assertTrue(Tcpip.ipChecksumOk(bad))
    }

    @Test
    fun udp_datagram_builds_and_parses() {
        val q = ByteArray(29) { (it * 3).toByte() }
        val pkt = Tcpip.buildUdp(a, Tcpip.ipToInt("10.8.0.1"), 40000, 53, q)
        val ip = Tcpip.parseIp4(pkt)!!
        assertEquals(Tcpip.PROTO_UDP, ip.protocol)
        assertTrue(Tcpip.transportChecksumOk(pkt, ip))
        val u = Tcpip.parseUdp(ip.payload)!!
        assertEquals(40000, u.srcPort); assertEquals(53, u.dstPort); assertArrayEquals(q, u.payload)
        // odd-length payload exercises the trailing-byte checksum path
        val pkt2 = Tcpip.buildUdp(a, b, 1, 2, ByteArray(7) { 1 })
        assertTrue(Tcpip.transportChecksumOk(pkt2, Tcpip.parseIp4(pkt2)!!))
    }

    @Test
    fun malformed_packets_return_null_without_throwing() {
        assertNull(Tcpip.parseIp4(ByteArray(0)))
        assertNull(Tcpip.parseIp4(ByteArray(19)))
        assertNull(Tcpip.parseIp4(ByteArray(40).also { it[0] = 0x60 }))              // IPv6
        assertNull(Tcpip.parseIp4(ByteArray(40).also { it[0] = 0x45; it[2] = 0; it[3] = 10 })) // total < header
        val frag = Tcpip.buildTcp(a, b, 1, 2, 0, 0, Tcpip.TCP_ACK, 100, ByteArray(0)).also { it[7] = 0x05 }
        assertNull(Tcpip.parseIp4(frag))                                                // fragment offset set
        assertNull(Tcpip.parseTcp(ByteArray(10)))
        assertNull(Tcpip.parseTcp(ByteArray(20).also { it[12] = 0xF0.toByte() }))    // data offset beyond packet
        assertNull(Tcpip.parseUdp(ByteArray(5)))
        assertNull(Tcpip.parseUdp(ByteArray(8).also { it[5] = 50 }))                   // length beyond packet
        val rnd = java.util.Random(9)
        repeat(500) {
            val g = ByteArray(rnd.nextInt(100)).also { rnd.nextBytes(it) }
            val ip = Tcpip.parseIp4(g)
            if (ip != null) { Tcpip.parseTcp(ip.payload); Tcpip.parseUdp(ip.payload) }
        }
        assertNotNull(Tcpip.parseTcp(ByteArray(20).also { it[12] = 0x50 }))
    }
}
