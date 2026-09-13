package net.prok.proknet.ble

import android.os.Handler
import android.os.Looper
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Identity
import net.prok.proknet.core.MessageStore
import net.prok.proknet.core.MsgStatus
import net.prok.proknet.core.Packet
import net.prok.proknet.core.StoredMessage
import net.prok.proknet.core.hexToBytes

/**
 * Milestone 2A: store-and-forward for this phone's OWN outgoing messages.
 *
 *   enqueue()  -> row in SQLite with status PENDING (survives restarts)
 *   pump()     -> picks the oldest PENDING message whose peer is in range and
 *                 whose backoff has elapsed, marks it SENDING, hands it to BleSender
 *   result     -> DELIVERED / DUPLICATE  => delivered
 *                 REJECTED               => failed (no retry)
 *                 TRANSPORT / NO_RECEIPT => back to PENDING with exponential backoff
 *
 * pump() is triggered by: a new message, the scanner seeing a peer, a 10 s tick,
 * and the end of every attempt. One attempt in flight at a time.
 */
class DeliveryQueue(
    private val store: MessageStore,
    private val identity: Identity,
    private val senderProvider: () -> BleSender?,
    private val peersProvider: () -> List<Peer>,
    private val onChanged: () -> Unit,
) {
    private val tag = "QUEUE"
    private val main = Handler(Looper.getMainLooper())
    private var running = false
    private var inFlight: String? = null
    private val ticker = object : Runnable {
        override fun run() {
            if (!running) return
            expire()
            pump("tick")
            main.postDelayed(this, BleConstants.QUEUE_TICK_MS)
        }
    }

    fun start() {
        if (running) return
        running = true
        val recovered = store.recoverInterrupted()
        if (recovered > 0) DiagLog.w(tag, recovered.toString() + " message(s) were mid-send at last shutdown, back to pending")
        val n = store.pendingCount()
        DiagLog.i(tag, "queue started, pending=" + n)
        expire()
        main.postDelayed(ticker, 2000)
    }

    fun stop() {
        running = false
        main.removeCallbacks(ticker)
        DiagLog.i(tag, "queue stopped (pending messages are kept)")
    }

    fun pendingCount(): Long = store.pendingCount()
    fun inFlightMsg(): String? = inFlight

    fun enqueue(peerShortId: String, peerLabel: String, text: String) {
        val pkt = Packet.text(identity, text)
        store.insert(StoredMessage(0, pkt.msgIdHex, "out", peerShortId, peerLabel, text, pkt.timestamp, MsgStatus.PENDING))
        DiagLog.i(tag, "ENQUEUED msg=" + pkt.msgIdHex + " for " + peerLabel + " (" + text.length + " chars), pending=" + store.pendingCount())
        onChanged()
        pump("enqueue")
    }

    /** Called by the node whenever the scanner's peer list changes. */
    fun onPeersChanged(peers: List<Peer>) {
        if (!running) return
        if (peers.any { it.inRange }) pump("peer seen")
    }

    /** A peer that was out of range is visible again: its messages skip their backoff. */
    fun onPeerAppeared(peer: Peer) {
        if (!running) return
        val waiting = store.retryNowFor(peer.shortId)
        if (waiting > 0) {
            DiagLog.i(tag, "peer " + peer.label + " reappeared, " + waiting + " pending message(s) for it -> retry now")
            pump("peer reappeared")
        }
    }

    fun retryAllNow() {
        store.retryAllNow()
        DiagLog.i(tag, "manual retry: backoff cleared for all pending")
        pump("manual")
    }

    private fun expire() {
        val cutoff = System.currentTimeMillis() - BleConstants.QUEUE_TTL_MS
        val victims = store.expireOlderThan(cutoff)
        for (v in victims) DiagLog.w(tag, "EXPIRED msg=" + v.msgId + " for " + v.peerName + " (older than " + (BleConstants.QUEUE_TTL_MS / 3600_000) + "h)")
        if (victims.isNotEmpty()) onChanged()
    }

    fun pump(reason: String) {
        if (!running) return
        if (inFlight != null) return
        val sender = senderProvider() ?: return
        if (sender.isBusy) return
        val peers = peersProvider().filter { it.inRange }.associateBy { it.shortId }
        if (peers.isEmpty()) return
        val now = System.currentTimeMillis()
        val pending = store.pending()
        if (pending.isEmpty()) return
        val candidate = pending.firstOrNull { peers.containsKey(it.peerId) && it.nextAttempt <= now }
        if (candidate == null) {
            val waiting = pending.count { peers.containsKey(it.peerId) }
            if (waiting > 0 && reason != "tick" && reason != "peer seen" && reason != "after attempt") DiagLog.i(tag, "pump(" + reason + "): " + waiting + " message(s) for visible peers still in backoff")
            return
        }
        val peer = peers[candidate.peerId]!!
        attempt(candidate, peer, sender, reason)
    }

    private fun attempt(m: StoredMessage, peer: Peer, sender: BleSender, reason: String) {
        inFlight = m.msgId
        store.setStatus(m.msgId, MsgStatus.SENDING, bumpAttempts = true)
        onChanged()
        val attemptNo = m.attempts + 1
        DiagLog.i(tag, "ATTEMPT " + attemptNo + " msg=" + m.msgId + " -> " + peer.label + " (trigger: " + reason + ")")
        val pkt = Packet(identity.idBytes, m.msgId.hexToBytes(), m.timestamp, m.text)
        sender.send(peer, pkt.msgId, pkt.encode()) { result, detail ->
            main.post { onResult(m, attemptNo, peer, result, detail) }
        }
    }

    private fun onResult(m: StoredMessage, attemptNo: Int, peer: Peer, result: DeliveryResult, detail: String) {
        inFlight = null
        when (result) {
            DeliveryResult.DELIVERED, DeliveryResult.DUPLICATE -> {
                store.setStatus(m.msgId, MsgStatus.DELIVERED, detail)
                DiagLog.i(tag, "DELIVERED msg=" + m.msgId + " to " + peer.label + " after " + attemptNo + " attempt(s)" +
                    (if (result == DeliveryResult.DUPLICATE) " (peer already had it)" else ""))
            }
            DeliveryResult.REJECTED -> {
                store.setStatus(m.msgId, MsgStatus.FAILED, detail)
                DiagLog.e(tag, "FAILED msg=" + m.msgId + ": rejected by " + peer.label + " - will not retry")
            }
            DeliveryResult.TRANSPORT_FAILED, DeliveryResult.NO_RECEIPT -> {
                if (attemptNo >= BleConstants.MAX_ATTEMPTS) {
                    store.setStatus(m.msgId, MsgStatus.FAILED, "gave up after " + attemptNo + " attempts: " + detail)
                    DiagLog.e(tag, "FAILED msg=" + m.msgId + ": gave up after " + attemptNo + " attempts (" + detail + ")")
                } else {
                    val backoff = minOf(BleConstants.BACKOFF_MAX_MS, BleConstants.BACKOFF_BASE_MS shl minOf(attemptNo - 1, 6))
                    store.setStatus(m.msgId, MsgStatus.PENDING, detail, System.currentTimeMillis() + backoff)
                    DiagLog.w(tag, "RETRY LATER msg=" + m.msgId + " (" + result + ": " + detail + "), next try in " + (backoff / 1000) + "s or when peer reappears")
                }
            }
        }
        onChanged()
        main.postDelayed({ pump("after attempt") }, 500)
    }
}
