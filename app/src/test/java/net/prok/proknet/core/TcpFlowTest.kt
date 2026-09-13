package net.prok.proknet.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The user-space TCP endpoint, driven like a local app would: SYN, data,
 * ACKs, FIN, RST, window limits and retransmission. On the JVM.
 */
class TcpFlowTest {

    private val app = Tcpip.ipToInt("10.8.0.2")
    private val site = Tcpip.ipToInt("93.184.216.34")
    private var appSeq = 1000L

    private fun flow() = TcpFlow(app, 40000, site, 443, streamId = 1, ourIsn = 5_000_000L, mss = 1360)

    /** Build a segment as the app's kernel would send it and feed it to the flow. */
    private fun fromApp(f: TcpFlow, flags: Int, payload: ByteArray = ByteArray(0), ack: Long = f.ourSequence, window: Int = 65535, now: Long = 0, seq: Long = appSeq): List<TcpFlow.Action> {
        val pkt = Tcpip.buildTcp(app, site, 40000, 443, seq, ack, flags, window, payload)
        val ip = Tcpip.parseIp4(pkt)!!
        val t = Tcpip.parseTcp(ip.payload)!!
        val out = f.onSegment(t, now)
        appSeq = (seq + payload.size + (if (flags and Tcpip.TCP_SYN != 0 || flags and Tcpip.TCP_FIN != 0) 1 else 0)) and 0xFFFFFFFFL
        return out
    }

    private fun tunSegments(actions: List<TcpFlow.Action>): List<Tcpip.Tcp> =
        actions.filterIsInstance<TcpFlow.Action.ToTun>().map { a -> val ip = Tcpip.parseIp4(a.packet)!!; assertTrue(Tcpip.transportChecksumOk(a.packet, ip)); Tcpip.parseTcp(ip.payload)!! }

    private fun handshake(f: TcpFlow): List<TcpFlow.Action> {
        val syn = fromApp(f, Tcpip.TCP_SYN, ack = 0)
        val synAck = tunSegments(syn).single()
        assertTrue(synAck.syn && synAck.isAck)
        assertEquals(1360, synAck.mss)
        assertEquals(5_000_000L, synAck.seq); assertEquals(1001L, synAck.ack)
        assertTrue(syn.any { it is TcpFlow.Action.OpenStream && it.host == "93.184.216.34" && it.port == 443 })
        assertEquals(TcpFlow.State.SYN_RCVD, f.state)
        val ack = fromApp(f, Tcpip.TCP_ACK, ack = 5_000_001L)
        assertEquals(TcpFlow.State.ESTABLISHED, f.state)
        return syn + ack
    }

    @Test
    fun handshake_then_app_data_is_forwarded_after_stream_opens_and_acked() {
        val f = flow()
        handshake(f)
        // app sends before the provider connected: buffered, but ACKed immediately
        val out1 = fromApp(f, Tcpip.TCP_PSH or Tcpip.TCP_ACK, "hello".toByteArray())
        assertTrue(out1.none { it is TcpFlow.Action.StreamData })
        val ack1 = tunSegments(out1).single(); assertTrue(ack1.isAck); assertEquals(1006L, ack1.ack)
        // provider connected: buffered data goes out
        val opened = f.onStreamOpened()
        assertArrayEquals("hello".toByteArray(), (opened.single() as TcpFlow.Action.StreamData).bytes)
        // now data is forwarded directly
        val out2 = fromApp(f, Tcpip.TCP_PSH or Tcpip.TCP_ACK, " world".toByteArray())
        assertArrayEquals(" world".toByteArray(), out2.filterIsInstance<TcpFlow.Action.StreamData>().single().bytes)
        assertEquals(1012L, f.theirSequence)
        // a retransmitted (old) segment is re-ACKed, not forwarded twice
        val dup = fromApp(f, Tcpip.TCP_PSH or Tcpip.TCP_ACK, " world".toByteArray(), seq = 1006L)
        assertTrue(dup.none { it is TcpFlow.Action.StreamData })
        assertEquals(1012L, tunSegments(dup).single().ack)
        appSeq = 1012L
    }

    @Test
    fun provider_data_becomes_segments_respecting_mss_and_window_and_acks_free_the_window() {
        val f = flow(); handshake(f); f.onStreamOpened()
        val big = ByteArray(5000) { it.toByte() }
        val out = f.onStreamData(big, now = 10)
        val segs = tunSegments(out)
        assertEquals(4, segs.size)                                   // 1360*3 + 920
        assertEquals(5_000_001L, segs[0].seq); assertEquals(1360, segs[0].payload.size)
        assertEquals(5_000_001L + 4080, segs[3].seq); assertEquals(920, segs[3].payload.size)
        assertArrayEquals(big, segs.flatMap { it.payload.toList() }.toByteArray())
        assertEquals(5000L, f.inflight)
        // small window: only what fits goes out until the app ACKs
        val f2 = flow(); appSeq = 1000; handshake(f2); f2.onStreamOpened()
        val w = fromApp(f2, Tcpip.TCP_ACK, window = 2000)              // app advertises 2000 bytes
        assertTrue(tunSegments(w).isEmpty())
        val out2 = f2.onStreamData(ByteArray(5000), now = 10)
        assertEquals(1, tunSegments(out2).size)                       // 1360 fits, 2720 would not
        val ack = fromApp(f2, Tcpip.TCP_ACK, ack = 5_000_001L + 1360, window = 2000)
        assertEquals(1, tunSegments(ack).size)                       // next segment released
        assertEquals(1360L, f2.inflight)
    }

    @Test
    fun retransmission_after_timeout_without_ack_progress() {
        val f = flow(); handshake(f); f.onStreamOpened()
        val first = tunSegments(f.onStreamData("data".toByteArray(), now = 100)).single()
        assertTrue(f.onTick(now = 600).isEmpty())                    // too early
        val re = tunSegments(f.onTick(now = 1200)).single()          // > RTO 1 s, no ack: resend same seq
        assertEquals(first.seq, re.seq); assertArrayEquals("data".toByteArray(), re.payload)
        fromApp(f, Tcpip.TCP_ACK, ack = 5_000_001L + 4, now = 1300)
        assertTrue(f.onTick(now = 3000).isEmpty())                   // acked: nothing to resend
        assertEquals(0L, f.inflight)
    }

    @Test
    fun app_fin_closes_stream_and_provider_close_sends_fin() {
        val f = flow(); handshake(f); f.onStreamOpened()
        val out = fromApp(f, Tcpip.TCP_FIN or Tcpip.TCP_ACK)
        assertTrue(out.any { it is TcpFlow.Action.CloseStream })
        assertEquals(TcpFlow.State.CLOSE_WAIT, f.state)
        assertEquals(1002L, tunSegments(out).single().ack)          // FIN consumed one sequence number
        // provider finishes: our FIN goes out, app ACKs it -> closed
        val fin = tunSegments(f.onStreamClosed()).single()
        assertTrue(fin.fin); assertEquals(TcpFlow.State.LAST_ACK, f.state)
        val done = fromApp(f, Tcpip.TCP_ACK, ack = fin.seq + 1)
        assertTrue(done.any { it is TcpFlow.Action.Closed }); assertEquals(TcpFlow.State.CLOSED, f.state)
    }

    @Test
    fun provider_closes_first_then_app_fin_completes_and_rst_aborts() {
        val f = flow(); handshake(f); f.onStreamOpened()
        f.onStreamData("bye".toByteArray(), now = 5)
        val finOut = f.onStreamClosed()
        assertTrue("FIN waits for data to be acked? no: FIN follows data immediately", tunSegments(finOut).single().fin)
        assertEquals(TcpFlow.State.FIN_WAIT_1, f.state)
        fromApp(f, Tcpip.TCP_ACK, ack = 5_000_001L + 3 + 1)
        assertEquals(TcpFlow.State.FIN_WAIT_2, f.state)
        val end = fromApp(f, Tcpip.TCP_FIN or Tcpip.TCP_ACK)
        assertTrue(end.any { it is TcpFlow.Action.Closed }); assertEquals(TcpFlow.State.CLOSED, f.state)
        // RST from the app closes everything at once
        val g = flow(); appSeq = 1000; handshake(g)
        val r = fromApp(g, Tcpip.TCP_RST)
        assertTrue(r.any { it is TcpFlow.Action.CloseStream }); assertTrue(r.any { it is TcpFlow.Action.Closed })
        // stream failure resets the app
        val h = flow(); appSeq = 1000; handshake(h)
        val fail = h.onStreamFailed("refused")
        assertTrue(tunSegments(fail).single().rst); assertEquals(TcpFlow.State.CLOSED, h.state)
        // idle flows are reset and removed
        val i = flow(); appSeq = 1000; handshake(i); i.onStreamOpened()
        val idle = i.onTick(now = Tunnel.STREAM_IDLE_MS + 10)
        assertTrue(tunSegments(idle).single().rst); assertTrue(idle.any { it is TcpFlow.Action.Closed })
        assertFalse(i.onTick(now = Tunnel.STREAM_IDLE_MS + 20).isNotEmpty())
    }

    @Test
    fun sequence_numbers_wrap_correctly() {
        val f = TcpFlow(app, 1, site, 80, 9, ourIsn = 0xFFFFFFF0L)
        appSeq = 0xFFFFFFFEL
        val syn = fromApp(f, Tcpip.TCP_SYN, ack = 0)
        assertEquals(0xFFFFFFFFL, tunSegments(syn).single().ack)
        fromApp(f, Tcpip.TCP_ACK, ack = 0xFFFFFFF1L)
        assertEquals(TcpFlow.State.ESTABLISHED, f.state)
        f.onStreamOpened()
        val segs = tunSegments(f.onStreamData(ByteArray(100), now = 1))
        assertEquals(0xFFFFFFF1L, segs.single().seq)
        assertEquals((0xFFFFFFF1L + 100) and 0xFFFFFFFFL, f.ourSequence)
        val acked = fromApp(f, Tcpip.TCP_ACK, ack = (0xFFFFFFF1L + 100) and 0xFFFFFFFFL, seq = 0xFFFFFFFFL)
        assertTrue(tunSegments(acked).isEmpty()); assertEquals(0L, f.inflight)
    }
}
