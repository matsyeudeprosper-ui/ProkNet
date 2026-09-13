package net.prok.proknet.core

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Wi-Fi link's frame I/O and handshake, exercised over real loopback
 * sockets on the JVM: exactly the path the tunnel uses on the phones.
 */
class LinkIoTest {

    private class TestSigner(val name: String) : Signer {
        val kp = Crypto.generateKeyPair()
        override val pubBytes: ByteArray = Crypto.publicBytes(kp.public)
        override val idBytes: ByteArray = Crypto.deriveId(pubBytes)
        override val displayName: String get() = name
        override fun sign(data: ByteArray): ByteArray = Crypto.sign(kp.private, data)
        val short get() = idBytes.toHex().substring(0, 8)
    }

    /** Two connected loopback sockets wrapped in LinkIo, like host and client after TCP connect. */
    private fun pair(): Pair<LinkIo, LinkIo> {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val client = Socket(InetAddress.getLoopbackAddress(), server.localPort)
        val accepted = server.accept()
        server.close()
        return LinkIo(accepted.getInputStream(), accepted.getOutputStream(), "host") to LinkIo(client.getInputStream(), client.getOutputStream(), "client")
    }

    @Test
    fun handshake_over_loopback_verifies_both_identities() {
        val (host, client) = pair()
        val a = TestSigner("A"); val b = TestSigner("B")
        var hostResult: Handshake.Result? = null
        val t = Thread { hostResult = Handshake.perform(host, b) }.apply { start() }
        val clientResult = Handshake.perform(client, a)
        t.join(5000)
        assertNotNull(clientResult.peer); assertEquals("ok", clientResult.why)
        assertEquals(b.short, clientResult.peer!!.shortId); assertEquals("B", clientResult.peer!!.name)
        assertNotNull(hostResult!!.peer); assertEquals(a.short, hostResult!!.peer!!.shortId)
        host.close("done"); client.close("done")
    }

    @Test
    fun handshake_rejects_a_forged_record_and_a_wrong_signature() {
        val (host, client) = pair()
        val a = TestSigner("A"); val b = TestSigner("B")
        // an impostor presents B's record but cannot sign as B
        val impostor = object : Signer {
            override val idBytes = b.idBytes; override val pubBytes = b.pubBytes; override val displayName = "B"
            override fun sign(data: ByteArray) = Crypto.sign(a.kp.private, data)
        }
        var hostResult: Handshake.Result? = null
        val t = Thread { hostResult = Handshake.perform(host, impostor) }.apply { start() }
        val clientResult = Handshake.perform(client, a)
        t.join(5000)
        assertNull(clientResult.peer); assertTrue(clientResult.why, clientResult.why.contains("INVALID"))
        host.close("x"); client.close("x")
    }

    @Test
    fun session_start_sent_immediately_after_link_up_arrives_as_a_tunnel_frame() {
        val (host, client) = pair()
        val a = TestSigner("A"); val b = TestSigner("B")
        val t = Thread { Handshake.perform(host, b) }.apply { start() }
        assertNotNull(Handshake.perform(client, a).peer)
        t.join(5000)
        // host side: reader dispatches tunnel frames; client side: writer thread
        val got = LinkedBlockingQueue<Tunnel.Frame>()
        val closed = CountDownLatch(1)
        host.startReader({ type, p -> if (type == Wire.FRAME_TUNNEL) got.offer(Tunnel.decode(p)!!) }, { closed.countDown() })
        host.startWriter { }
        client.startWriter { }
        client.startReader({ _, _ -> }, { })
        // exactly what TunnelClient.start() does the instant the link is UP, from a thread that must not block
        assertTrue(client.enqueue(Wire.FRAME_TUNNEL, Tunnel.encode(Tunnel.T_SESSION_START, 0, Tunnel.sessionStart(a.idBytes)), block = false))
        val f = got.poll(5, TimeUnit.SECONDS)
        assertNotNull("SESSION_START must arrive", f)
        assertEquals(Tunnel.T_SESSION_START, f!!.type); assertEquals(0, f.streamId)
        assertArrayEquals(a.idBytes, Tunnel.parseSessionStart(f.data)!!.buyerId)
        assertTrue(host.isOpen && client.isOpen)          // sending on a fresh link must not tear it down
        assertEquals(1L, closed.count)                     // host reader still running
        client.close("done")                               // peer goes away ...
        assertTrue(closed.await(5, TimeUnit.SECONDS))     // ... and the host owner is told
        host.close("done")
    }

    @Test
    fun tunnel_frames_flow_both_ways_including_large_and_many() {
        val (host, client) = pair()
        val toHost = LinkedBlockingQueue<Tunnel.Frame>(); val toClient = LinkedBlockingQueue<Tunnel.Frame>()
        host.startReader({ t, p -> if (t == Wire.FRAME_TUNNEL) toHost.offer(Tunnel.decode(p)!!) }, { })
        client.startReader({ t, p -> if (t == Wire.FRAME_TUNNEL) toClient.offer(Tunnel.decode(p)!!) }, { })
        host.startWriter { }; client.startWriter { }
        val big = ByteArray(Tunnel.MAX_DATA) { it.toByte() }
        assertTrue(client.enqueue(Wire.FRAME_TUNNEL, Tunnel.encode(Tunnel.T_TCP_DATA, 7, big), block = true))
        val f = toHost.poll(5, TimeUnit.SECONDS)!!
        assertEquals(7, f.streamId); assertArrayEquals(big, f.data)
        assertTrue(host.enqueue(Wire.FRAME_TUNNEL, Tunnel.encode(Tunnel.T_TCP_OPEN_OK, 7), block = true))
        assertEquals(Tunnel.T_TCP_OPEN_OK, toClient.poll(5, TimeUnit.SECONDS)!!.type)
        // 500 frames in a burst keep order
        for (i in 0 until 500) assertTrue(client.enqueue(Wire.FRAME_TUNNEL, Tunnel.encode(Tunnel.T_TCP_DATA, i, byteArrayOf(i.toByte())), block = true))
        for (i in 0 until 500) { val g = toHost.poll(5, TimeUnit.SECONDS); assertNotNull("frame " + i, g); assertEquals(i, g!!.streamId) }
        assertTrue(client.bytesSent > 500L * 10); assertTrue(host.bytesReceived == client.bytesSent)
        host.close("done"); client.close("done")
    }

    @Test
    fun non_blocking_enqueue_drops_when_full_and_closed_link_refuses() {
        val (host, client) = pair()
        // no writer started on the client: the queue fills up
        var accepted = 0
        for (i in 0 until LinkIo.QUEUE_CAPACITY + 50) if (client.enqueue(Wire.FRAME_TUNNEL, Tunnel.encode(Tunnel.T_KEEPALIVE, 0, Tunnel.keepalive(i)), block = false)) accepted++
        assertEquals(LinkIo.QUEUE_CAPACITY, accepted)
        client.close("test")
        assertFalse(client.enqueue(Wire.FRAME_TUNNEL, ByteArray(6), block = false))
        assertFalse(client.enqueue(Wire.FRAME_TUNNEL, ByteArray(6), block = true))
        host.close("test")
    }

    @Test
    fun malformed_length_closes_the_link_with_a_reason_and_peer_close_is_reported() {
        val (host, client) = pair()
        val closed = LinkedBlockingQueue<String>()
        val frames = LinkedBlockingQueue<Int>()
        host.startReader({ t, _ -> frames.offer(t) }, { why -> closed.offer(why) })
        client.writeNow(Wire.FRAME_TUNNEL, Tunnel.encode(Tunnel.T_KEEPALIVE, 0, Tunnel.keepalive(1))) // a valid frame first
        assertEquals(Wire.FRAME_TUNNEL, frames.poll(5, TimeUnit.SECONDS))
        // now garbage straight into the socket: an oversized length prefix
        val field = LinkIo::class.java.getDeclaredField("output"); field.isAccessible = true
        val out = field.get(client) as java.io.DataOutputStream
        synchronized(out) { out.writeInt(Int.MAX_VALUE); out.flush() }
        val why = closed.poll(5, TimeUnit.SECONDS)
        assertNotNull(why); assertTrue(why!!, why.contains("bad frame length"))
        assertFalse(host.isOpen)
        client.close("done")
    }

    @Test
    fun describe_never_yields_just_null() {
        val d = LinkIo.describe(NullPointerException())
        assertTrue(d.startsWith("NullPointerException")); assertTrue(d.contains("["))
        assertTrue(LinkIo.describe(IllegalStateException("boom")).contains("boom"))
    }
}
