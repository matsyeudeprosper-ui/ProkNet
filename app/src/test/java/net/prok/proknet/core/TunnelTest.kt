package net.prok.proknet.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Tunnel framing, stream lifecycle, accounting, upstream selection and session payloads. */
class TunnelTest {

    private fun id(seed: Int) = ByteArray(16) { i -> ((seed * 31 + i) and 0xFF).toByte() }

    @Test
    fun frames_round_trip_and_reject_garbage() {
        val data = ByteArray(1000) { it.toByte() }
        val f = Tunnel.decode(Tunnel.encode(Tunnel.T_TCP_DATA, 42, data))!!
        assertEquals(Tunnel.T_TCP_DATA, f.type); assertEquals(42, f.streamId); assertArrayEquals(data, f.data)
        val k = Tunnel.decode(Tunnel.encode(Tunnel.T_KEEPALIVE, 0, Tunnel.keepalive(7)))!!
        assertEquals(7, Tunnel.parseKeepalive(k.data))
        assertNull(Tunnel.decode(null)); assertNull(Tunnel.decode(ByteArray(4)))
        assertNull(Tunnel.decode(byteArrayOf(0, 0, 0, 0, 1)))                 // type 0
        assertNull(Tunnel.decode(byteArrayOf(99, 0, 0, 0, 1)))                // unknown type
        assertNull(Tunnel.decode(ByteArray(Tunnel.HEADER + Tunnel.MAX_DATA + 1).also { it[0] = 6 }))
        var threw = false
        try { Tunnel.encode(Tunnel.T_TCP_DATA, 1, ByteArray(Tunnel.MAX_DATA + 1)) } catch (e: IllegalArgumentException) { threw = true }
        assertTrue(threw)
        val rnd = java.util.Random(11)
        repeat(300) { Tunnel.decode(ByteArray(rnd.nextInt(60)).also { rnd.nextBytes(it) }) }
        assertEquals("OPEN_TCP", Tunnel.typeName(Tunnel.T_OPEN_TCP))
    }

    @Test
    fun session_open_error_and_upstream_payloads() {
        val s = Tunnel.parseSessionStart(Tunnel.sessionStart(id(1)))!!
        assertEquals(Tunnel.VERSION, s.version); assertArrayEquals(id(1), s.buyerId)
        val ok = Tunnel.parseSessionOk(Tunnel.sessionOk(id(2), Tunnel.UP_CELLULAR, true))!!
        assertArrayEquals(id(2), ok.providerId); assertEquals(Tunnel.UP_CELLULAR, ok.upstreamType); assertTrue(ok.validated)
        val o = Tunnel.parseOpenTcp(Tunnel.openTcp("example.com", 443))!!
        assertEquals("example.com", o.host); assertEquals(443, o.port)
        assertNull(Tunnel.parseOpenTcp(Tunnel.openTcp("example.com", 443).copyOfRange(0, 6)))
        assertNull(Tunnel.parseOpenTcp(byteArrayOf(0, 0, 1, 65)))             // port 0
        assertNull(Tunnel.parseOpenTcp(null))
        val e = Tunnel.parseError(Tunnel.error(Tunnel.ERR_CONNECT_FAILED, "refused"))!!
        assertEquals(Tunnel.ERR_CONNECT_FAILED, e.code); assertEquals("refused", e.message)
        val u = Tunnel.parseUpstream(Tunnel.upstreamState(true, Tunnel.UP_WIFI, false))!!
        assertTrue(u.available); assertEquals(Tunnel.UP_WIFI, u.type); assertFalse(u.validated)
        assertNull(Tunnel.parseSessionStart(ByteArray(5))); assertNull(Tunnel.parseSessionOk(ByteArray(10))); assertNull(Tunnel.parseUpstream(ByteArray(1)))
    }

    @Test
    fun stream_table_ids_limits_and_cleanup() {
        val t = Tunnel.StreamTable(max = 3)
        val i1 = t.nextId(); val s1 = t.open(i1, "a", 80, 1000)!!
        val i2 = t.nextId(); assertTrue(i2 != i1); t.open(i2, "b", 443, 1000)
        val i3 = t.nextId(); t.open(i3, "c", 22, 1000)
        assertNull("limit", t.open(t.nextId(), "d", 1, 1000))
        assertNull("duplicate id", t.open(i1, "x", 1, 1000))
        assertEquals(3, t.count())
        assertTrue(t.close(i2)!!.open.not())
        assertEquals(2, t.count()); assertNull(t.get(i2))
        // multiple concurrent streams keep independent state
        s1.bytesIn += 10; t.get(i3)!!.bytesIn += 20
        assertEquals(10L, t.get(i1)!!.bytesIn); assertEquals(20L, t.get(i3)!!.bytesIn)
        // idle cleanup returns the dead streams so sockets can be closed
        s1.lastActivity = 1000; t.get(i3)!!.lastActivity = 900_000
        val dead = t.expire(now = 1000 + Tunnel.STREAM_IDLE_MS + 1)
        assertEquals(listOf(i1), dead.map { it.id }); assertEquals(1, t.count())
        // disconnect closes everything
        val all = t.closeAll(); assertEquals(1, all.size); assertEquals(0, t.count()); assertFalse(all[0].open)
    }

    @Test
    fun byte_accounting_and_session_end() {
        val acc = Tunnel.Accounting("bbbbbbbb", "buyer", 1000)
        acc.bytesUp += 300; acc.bytesDown += 5000; acc.streamsOpened = 2; acc.dnsQueries = 3
        assertEquals(0L, acc.endedAt)
        acc.end("link lost", 61_000)
        acc.end("ignored second", 99_000)
        assertEquals(61_000L, acc.endedAt); assertEquals("link lost", acc.disconnectReason)
        assertEquals(60_000L, acc.durationMs)
        val s = acc.summary()
        assertTrue(s.contains("up 300 B")); assertTrue(s.contains("down 5000 B")); assertTrue(s.contains("streams 2")); assertTrue(s.contains("link lost"))
    }

    @Test
    fun provider_upstream_selection_never_picks_the_proknet_link() {
        val hotspot = Tunnel.NetView("hotspot", internet = false, validated = false, cellular = false, wifi = true, isProkNetLink = true)
        val cell = Tunnel.NetView("cell", internet = true, validated = true, cellular = true, wifi = false, isProkNetLink = false)
        val home = Tunnel.NetView("home", internet = true, validated = true, cellular = false, wifi = true, isProkNetLink = false)
        val captive = Tunnel.NetView("captive", internet = true, validated = false, cellular = false, wifi = true, isProkNetLink = false)
        val linkWithInternetFlag = Tunnel.NetView("specifier", internet = true, validated = true, cellular = false, wifi = true, isProkNetLink = true)
        assertNull(Tunnel.chooseUpstream(listOf(hotspot)))
        assertNull(Tunnel.chooseUpstream(listOf(hotspot, linkWithInternetFlag)))
        assertEquals("cell", Tunnel.chooseUpstream(listOf(hotspot, cell))!!.id)
        assertEquals("home", Tunnel.chooseUpstream(listOf(cell, home, hotspot))!!.id)     // validated Wi-Fi preferred
        assertEquals("cell", Tunnel.chooseUpstream(listOf(captive, cell))!!.id)           // validated beats unvalidated
        assertEquals("captive", Tunnel.chooseUpstream(listOf(captive, hotspot))!!.id)     // last resort
        assertEquals(Tunnel.UP_CELLULAR, Tunnel.upstreamType(cell)); assertEquals(Tunnel.UP_WIFI, Tunnel.upstreamType(home)); assertEquals(Tunnel.UP_NONE, Tunnel.upstreamType(null))
        assertNotNull(Tunnel.upstreamName(Tunnel.UP_CELLULAR))
    }
}
