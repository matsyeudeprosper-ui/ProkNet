package net.prok.proknet.core

import java.util.ArrayDeque

/**
 * User-space TCP endpoint for ONE flow between a local app (behind the VPN
 * TUN) and a remote host reached through the tunnel (v0.6). Pure Kotlin,
 * no Android, unit-tested.
 *
 * We play the remote server towards the local app: answer its SYN, ACK its
 * data and forward the payload as tunnel stream data; turn stream data from
 * the provider into segments with our own sequence numbers. The TUN link is
 * lossless, so the stack is deliberately small: literal window (no scaling),
 * one retransmission timer, no SACK, no congestion control.
 *
 * Every call returns the list of [Action]s the Android layer must execute.
 */
class TcpFlow(
    val srcIp: Int, val srcPort: Int, val dstIp: Int, val dstPort: Int,
    val streamId: Int,
    private val ourIsn: Long,
    private val mss: Int = 1360,
) {
    enum class State { NEW, SYN_RCVD, ESTABLISHED, FIN_WAIT_1, FIN_WAIT_2, CLOSE_WAIT, LAST_ACK, CLOSED }

    sealed class Action {
        class ToTun(val packet: ByteArray) : Action()           // write this IPv4 packet to the TUN
        class OpenStream(val host: String, val port: Int) : Action()
        class StreamData(val bytes: ByteArray) : Action()       // forward app payload to the provider
        object CloseStream : Action()                          // app finished sending
        class Closed(val reason: String) : Action()            // flow is gone; remove it
    }

    var state = State.NEW; private set
    private var theirSeq = 0L        // next byte we expect from the app
    private var ourSeq = ourIsn      // next byte we will send
    private var theirAck = ourIsn    // highest byte the app acknowledged
    private var theirWindow = 65535
    private var streamOpen = false
    private var remoteFinPending = false
    private var finSent = false
    private var ourFinSeq = -1L
    private val appBuffered = ArrayDeque<ByteArray>()      // app data before the stream is open
    private val pendingOut = ArrayDeque<ByteArray>()       // provider data not yet sent (window)
    private val unacked = ArrayDeque<Pair<Long, ByteArray>>() // segments sent, not yet acked
    private var lastProgress = 0L
    var lastActivity = 0L; private set
    val ourSequence get() = ourSeq
    val theirSequence get() = theirSeq
    val inflight: Long get() = (ourSeq - theirAck) and 0xFFFFFFFFL

    private fun seqAdd(a: Long, n: Long) = (a + n) and 0xFFFFFFFFL
    private fun seqLe(a: Long, b: Long) = ((b - a).toInt()) >= 0
    private fun seqLt(a: Long, b: Long) = ((b - a).toInt()) > 0

    private fun segment(flags: Int, payload: ByteArray = ByteArray(0), mssOpt: Int = 0, seq: Long = ourSeq): ByteArray =
        Tcpip.buildTcp(dstIp, srcIp, dstPort, srcPort, seq, theirSeq, flags, 65535, payload, mssOpt)

    private fun rst(): ByteArray = Tcpip.buildTcp(dstIp, srcIp, dstPort, srcPort, ourSeq, theirSeq, Tcpip.TCP_RST or Tcpip.TCP_ACK, 0, ByteArray(0))

    /** A segment from the local app arrived. */
    fun onSegment(t: Tcpip.Tcp, now: Long): List<Action> {
        lastActivity = now
        val out = ArrayList<Action>()
        if (t.rst) { state = State.CLOSED; out.add(Action.CloseStream); out.add(Action.Closed("app reset")); return out }
        when (state) {
            State.NEW -> {
                if (!t.syn) { out.add(Action.ToTun(rst())); state = State.CLOSED; out.add(Action.Closed("no SYN")); return out }
                theirSeq = seqAdd(t.seq, 1)
                theirWindow = t.window
                state = State.SYN_RCVD
                out.add(Action.OpenStream(Tcpip.ipToString(dstIp), dstPort))
                out.add(Action.ToTun(segment(Tcpip.TCP_SYN or Tcpip.TCP_ACK, mssOpt = mss)))
                ourSeq = seqAdd(ourSeq, 1)
                lastProgress = now
                return out
            }
            State.SYN_RCVD -> {
                if (t.syn) { out.add(Action.ToTun(segment(Tcpip.TCP_SYN or Tcpip.TCP_ACK, mssOpt = mss, seq = ourIsn))); return out } // retransmitted SYN
                if (t.isAck && t.ack == ourSeq) { state = State.ESTABLISHED; theirAck = t.ack; theirWindow = t.window }
                else return out
                // fall through: the ACK may carry data
            }
            State.CLOSED -> return out
            else -> {}
        }
        // ESTABLISHED / FIN_WAIT / CLOSE_WAIT / LAST_ACK
        if (t.isAck) {
            if (seqLt(theirAck, t.ack) && seqLe(t.ack, ourSeq)) {
                theirAck = t.ack; lastProgress = now
                while (unacked.isNotEmpty() && seqLe(seqAdd(unacked.first().first, unacked.first().second.size.toLong()), theirAck)) unacked.removeFirst()
            }
            theirWindow = t.window
            if (finSent && ourFinSeq >= 0 && seqLe(seqAdd(ourFinSeq, 1), t.ack)) {
                if (state == State.FIN_WAIT_1) state = State.FIN_WAIT_2
                if (state == State.LAST_ACK) { state = State.CLOSED; out.add(Action.Closed("closed")); return out }
            }
        }
        if (t.payload.isNotEmpty()) {
            if (t.seq == theirSeq) {
                theirSeq = seqAdd(theirSeq, t.payload.size.toLong())
                if (streamOpen) out.add(Action.StreamData(t.payload)) else appBuffered.add(t.payload)
                out.add(Action.ToTun(segment(Tcpip.TCP_ACK)))
            } else {
                out.add(Action.ToTun(segment(Tcpip.TCP_ACK))) // old or out-of-order: re-ACK what we expect
                return out
            }
        }
        if (t.fin && t.seq == theirSeq) {
            theirSeq = seqAdd(theirSeq, 1)
            out.add(Action.ToTun(segment(Tcpip.TCP_ACK)))
            out.add(Action.CloseStream)
            when (state) {
                State.ESTABLISHED -> state = State.CLOSE_WAIT
                State.FIN_WAIT_1, State.FIN_WAIT_2 -> { state = State.CLOSED; out.add(Action.Closed("closed")) }
                else -> {}
            }
            if (state == State.CLOSE_WAIT && remoteFinPending) out.addAll(sendFinIfPossible())
        }
        out.addAll(pump())
        return out
    }

    /** The provider connected the stream: flush what the app already sent. */
    fun onStreamOpened(): List<Action> {
        streamOpen = true
        val out = ArrayList<Action>()
        while (appBuffered.isNotEmpty()) out.add(Action.StreamData(appBuffered.removeFirst()))
        return out
    }

    /** The provider could not connect: reset the app's connection. */
    fun onStreamFailed(reason: String): List<Action> {
        state = State.CLOSED
        return listOf(Action.ToTun(rst()), Action.Closed("stream failed: " + reason))
    }

    /** Data from the provider for the app: queued, sent within the app's window. */
    fun onStreamData(bytes: ByteArray, now: Long): List<Action> {
        lastActivity = now
        if (state == State.CLOSED) return emptyList()
        var i = 0
        while (i < bytes.size) { val n = minOf(mss, bytes.size - i); pendingOut.add(bytes.copyOfRange(i, i + n)); i += n }
        return pump()
    }

    /** The provider side finished (remote closed): send FIN once all data went out. */
    fun onStreamClosed(): List<Action> {
        remoteFinPending = true
        return sendFinIfPossible()
    }

    private fun sendFinIfPossible(): List<Action> {
        if (finSent || pendingOut.isNotEmpty() || state == State.CLOSED || state == State.NEW || state == State.SYN_RCVD) return emptyList()
        finSent = true
        ourFinSeq = ourSeq
        val pkt = segment(Tcpip.TCP_FIN or Tcpip.TCP_ACK)
        ourSeq = seqAdd(ourSeq, 1)
        state = if (state == State.CLOSE_WAIT) State.LAST_ACK else State.FIN_WAIT_1
        return listOf(Action.ToTun(pkt))
    }

    /** Send queued provider data while the app's window allows. */
    private fun pump(): List<Action> {
        val out = ArrayList<Action>()
        while (pendingOut.isNotEmpty() && state != State.CLOSED) {
            val seg = pendingOut.first()
            if (inflight + seg.size > theirWindow.coerceAtLeast(mss)) break
            pendingOut.removeFirst()
            val pkt = segment(Tcpip.TCP_PSH or Tcpip.TCP_ACK, seg)
            unacked.add(ourSeq to seg)
            ourSeq = seqAdd(ourSeq, seg.size.toLong())
            out.add(Action.ToTun(pkt))
        }
        if (pendingOut.isEmpty() && remoteFinPending && !finSent) out.addAll(sendFinIfPossible())
        return out
    }

    /** Periodic: retransmit unacknowledged data after [rtoMs] without progress; give up after [maxIdleMs]. */
    fun onTick(now: Long, rtoMs: Long = 1000, maxIdleMs: Long = Tunnel.STREAM_IDLE_MS): List<Action> {
        if (state == State.CLOSED) return emptyList()
        if (now - lastActivity > maxIdleMs) { state = State.CLOSED; return listOf(Action.ToTun(rst()), Action.CloseStream, Action.Closed("idle")) }
        if (unacked.isEmpty() || now - lastProgress < rtoMs) return emptyList()
        lastProgress = now
        val out = ArrayList<Action>()
        for ((seq, seg) in unacked) out.add(Action.ToTun(segment(Tcpip.TCP_PSH or Tcpip.TCP_ACK, seg, seq = seq)))
        if (finSent && ourFinSeq >= 0 && state != State.CLOSED) out.add(Action.ToTun(segment(Tcpip.TCP_FIN or Tcpip.TCP_ACK, seq = ourFinSeq)))
        return out
    }

    /** Force-close (link lost, VPN stopping): reset the app side. */
    fun abort(reason: String): List<Action> {
        if (state == State.CLOSED) return emptyList()
        state = State.CLOSED
        return listOf(Action.ToTun(rst()), Action.Closed(reason))
    }
}
