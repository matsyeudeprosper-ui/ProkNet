package net.prok.proknet.ble

import android.os.Handler
import android.os.Looper
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Dir
import net.prok.proknet.core.Identity
import net.prok.proknet.core.MessageStore
import net.prok.proknet.core.MsgStatus
import net.prok.proknet.core.Packet
import net.prok.proknet.core.StoredMessage
import net.prok.proknet.core.hexToBytes

/**
 * STORE -> CARRY -> FORWARD, smallest version.
 *
 * Own messages (direction OUT):
 *   pending --(destination in range)--> sending --> delivered        (final)
 *   pending --(destination NOT in range, some other peer is)--> sending --> handed_off   (2C1, one relay, NOT final)
 *   transport failure / no receipt -> pending with backoff; REJECTED -> failed; 48 h -> expired
 *
 * Carried packets (direction CARRY, milestone 2C1):
 *   carrying --(EXACT destination in range)--> forwarding --> forwarded (destination confirmed)
 *   never offered to anyone but the destination (no flooding), never re-handed to another relay
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
    private val powerManager: android.os.PowerManager? = null,
) {
    private val tag = "QUEUE"
    /** Milestone 2B: keep the CPU awake for the few seconds of a delivery attempt when the screen is off. */
    private val wakeLock: android.os.PowerManager.WakeLock? =
        powerManager?.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "ProkNet:delivery")?.apply { setReferenceCounted(false) }
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
        if (recovered > 0) DiagLog.w(tag, recovered.toString() + " message(s) were mid-attempt at last shutdown, back to waiting")
        DiagLog.i(tag, "queue started, pending=" + store.pendingCount() + " carrying=" + store.carryingCount())
        for (c in store.carrying()) DiagLog.i(tag, "CARRYING msg=" + c.msgId + " from prok-" + c.peerId + " for prok-" + c.destShort + " (hops " + c.hops + "/" + c.ttl + ")")
        expire()
        main.postDelayed(ticker, 2000)
    }

    fun stop() {
        running = false
        main.removeCallbacks(ticker)
        DiagLog.i(tag, "queue stopped (pending and carried messages are kept)")
    }

    fun pendingCount(): Long = store.pendingCount()
    fun carryingCount(): Long = store.carryingCount()
    fun inFlightMsg(): String? = inFlight

    /** Own message for [destShortId]. [destFullId] may be null for v1 peers (short-ID addressing then). */
    fun enqueue(destShortId: String, destFullId: String?, peerLabel: String, text: String) {
        val dest = if (destFullId != null) destFullId.hexToBytes() else Packet.destFromShort(destShortId)
        val pkt = Packet.text(identity, dest, text)
        store.insert(
            StoredMessage(
                0, pkt.msgIdHex, Dir.OUT, destShortId, peerLabel, text, pkt.timestamp, MsgStatus.PENDING,
                destId = pkt.destIdHex, originId = identity.idHex, ttl = pkt.ttl, hops = 0,
            )
        )
        DiagLog.i(tag, "ENQUEUED msg=" + pkt.msgIdHex + " for " + peerLabel + " (" + text.length + " chars, ttl " + pkt.ttl +
            (if (destFullId == null) ", short-ID addressing" else "") + "), pending=" + store.pendingCount())
        onChanged()
        pump("enqueue")
    }

    /** Called by the node whenever the scanner's peer list changes. */
    fun onPeersChanged(peers: List<Peer>) {
        if (!running) return
        if (peers.any { it.inRange }) pump("peer seen")
    }

    /** A peer that was out of range is visible again: anything addressed to it skips its backoff. */
    fun onPeerAppeared(peer: Peer) {
        if (!running) return
        val waiting = store.retryNowFor(peer.shortId)
        if (waiting > 0) {
            val carried = store.carrying().count { it.destShort == peer.shortId }
            if (carried > 0) DiagLog.i(tag, "DESTINATION SEEN: " + peer.label + " is in range, " + carried + " carried packet(s) for it -> forward now")
            if (waiting - carried > 0) DiagLog.i(tag, "peer " + peer.label + " reappeared, " + (waiting - carried) + " pending message(s) for it -> retry now")
            pump("peer reappeared")
        }
    }

    fun retryAllNow() {
        store.retryAllNow()
        DiagLog.i(tag, "manual retry: backoff cleared for all pending/carrying")
        pump("manual")
    }

    private fun expire() {
        val cutoff = System.currentTimeMillis() - BleConstants.QUEUE_TTL_MS
        val victims = store.expireOlderThan(cutoff)
        for (v in victims) DiagLog.w(tag, "EXPIRED " + v.direction + " msg=" + v.msgId + " for prok-" + v.destShort + " (older than " + (BleConstants.QUEUE_TTL_MS / 3600_000) + "h)")
        if (victims.isNotEmpty()) onChanged()
    }

    fun pump(reason: String) {
        if (!running) return
        if (inFlight != null) return
        val sender = senderProvider() ?: return
        if (sender.isBusy) return
        val inRange = peersProvider().filter { it.inRange }
        if (inRange.isEmpty()) return
        val byId = inRange.associateBy { it.shortId }
        val now = System.currentTimeMillis()

        // 1. Carried packets whose exact destination is here: forward (highest priority, they are not ours).
        for (c in store.carrying()) {
            if (c.nextAttempt > now) continue
            val dest = byId[c.destShort] ?: continue
            attempt(c, dest, sender, reason, kind = "FORWARD")
            return
        }
        // 2. Own pending messages: direct if the destination is here, else hand off to one relay.
        val pending = store.pending()
        for (m in pending) {
            if (m.nextAttempt > now) continue
            val dest = byId[m.destShort]
            if (dest != null) { attempt(m, dest, sender, reason, kind = "DIRECT"); return }
            if (m.ttl <= 0) continue
            val relay = inRange.filter { it.shortId != m.destShort }.maxByOrNull { it.rssi } ?: continue
            attempt(m, relay, sender, reason, kind = "HANDOFF")
            return
        }
        if (reason == "enqueue" || reason == "manual" || reason == "peer reappeared") {
            val waiting = pending.count { it.nextAttempt > now } + store.carrying().count { it.nextAttempt > now }
            if (waiting > 0) DiagLog.i(tag, "pump(" + reason + "): " + waiting + " message(s) still in backoff")
        }
    }

    private fun attempt(m: StoredMessage, peer: Peer, sender: BleSender, reason: String, kind: String) {
        inFlight = m.msgId
        try { wakeLock?.acquire(BleConstants.SEND_TIMEOUT_MS * 2 + 5000) } catch (e: Exception) { DiagLog.w(tag, "wakelock: " + e) }
        val isCarry = m.direction == Dir.CARRY
        store.setStatus(m.msgId, if (isCarry) MsgStatus.FORWARDING else MsgStatus.SENDING, bumpAttempts = true, direction = m.direction)
        onChanged()
        val attemptNo = m.attempts + 1
        // Guard for rows created before v0.4: no stored destination -> address by short ID.
        val destBytes = if (m.destId.length == Identity.ID_LEN * 2) m.destId.hexToBytes() else Packet.destFromShort(m.peerId)
        val pkt = if (isCarry) {
            Packet(m.originId.hexToBytes(), destBytes, m.msgId.hexToBytes(), m.timestamp, m.text, m.ttl, m.hops).nextHop()
        } else {
            Packet(identity.idBytes, destBytes, m.msgId.hexToBytes(), m.timestamp, m.text, if (m.ttl > 0) m.ttl else Packet.DEFAULT_TTL, 0)
        }
        when (kind) {
            "FORWARD" -> DiagLog.i(tag, "FORWARDING msg=" + m.msgId + " from prok-" + m.peerId + " to its destination " + peer.label + " (hop " + pkt.hops + "/" + pkt.ttl + ", attempt " + attemptNo + ", trigger: " + reason + ")")
            "HANDOFF" -> DiagLog.i(tag, "HANDING OFF msg=" + m.msgId + " for prok-" + m.destShort + " to relay " + peer.label + " (destination not in range, attempt " + attemptNo + ", trigger: " + reason + ")")
            else -> DiagLog.i(tag, "ATTEMPT " + attemptNo + " msg=" + m.msgId + " -> " + peer.label + " direct (trigger: " + reason + ")")
        }
        sender.send(peer, pkt.msgId, pkt.encode()) { result, detail ->
            main.post { onResult(m, attemptNo, peer, kind, result, detail) }
        }
    }

    private fun onResult(m: StoredMessage, attemptNo: Int, peer: Peer, kind: String, result: DeliveryResult, detail: String) {
        inFlight = null
        try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Exception) {}
        val isCarry = m.direction == Dir.CARRY
        val waitState = if (isCarry) MsgStatus.CARRYING else MsgStatus.PENDING
        when (result) {
            DeliveryResult.DELIVERED, DeliveryResult.DUPLICATE -> {
                if (isCarry) {
                    store.setStatus(m.msgId, MsgStatus.FORWARDED, detail, direction = Dir.CARRY)
                    DiagLog.i(tag, "FORWARDED msg=" + m.msgId + " from prok-" + m.peerId + " to destination " + peer.label + " after " + attemptNo + " attempt(s)" +
                        (if (result == DeliveryResult.DUPLICATE) " (destination already had it)" else "") + " - custody complete")
                } else if (kind == "HANDOFF" && result == DeliveryResult.DUPLICATE) {
                    // The relay already holds it (an earlier handoff whose receipt was lost).
                    store.setStatus(m.msgId, MsgStatus.HANDED_OFF, detail, via = peer.shortId)
                    DiagLog.i(tag, "HANDED OFF msg=" + m.msgId + " to relay " + peer.label + " (relay already had it) - NOT final delivery")
                } else {
                    store.setStatus(m.msgId, MsgStatus.DELIVERED, detail)
                    DiagLog.i(tag, "DELIVERED msg=" + m.msgId + " to " + peer.label + " after " + attemptNo + " attempt(s)" +
                        (if (result == DeliveryResult.DUPLICATE) " (peer already had it)" else "") + " - final")
                }
            }
            DeliveryResult.RELAYED -> {
                if (isCarry) {
                    // A relay must only forward to the destination; a RELAY receipt here means the peer is not it. Keep carrying.
                    store.setStatus(m.msgId, MsgStatus.CARRYING, "unexpected relay receipt from " + peer.label, System.currentTimeMillis() + BleConstants.BACKOFF_MAX_MS, direction = Dir.CARRY)
                    DiagLog.w(tag, "forward of msg=" + m.msgId + " got ACCEPTED_RELAY from " + peer.label + " - it is not the destination, still carrying")
                } else {
                    store.setStatus(m.msgId, MsgStatus.HANDED_OFF, detail, via = peer.shortId)
                    DiagLog.i(tag, "HANDED OFF msg=" + m.msgId + " for prok-" + m.destShort + " to relay " + peer.label + " - custody accepted, NOT final delivery")
                }
            }
            DeliveryResult.REJECTED -> {
                if (kind == "HANDOFF") {
                    val backoff = BleConstants.BACKOFF_MAX_MS
                    store.setStatus(m.msgId, MsgStatus.PENDING, "relay " + peer.label + " refused custody", System.currentTimeMillis() + backoff)
                    DiagLog.w(tag, "relay " + peer.label + " REFUSED custody of msg=" + m.msgId + ", still pending")
                } else {
                    store.setStatus(m.msgId, MsgStatus.FAILED, detail, direction = m.direction)
                    DiagLog.e(tag, "FAILED msg=" + m.msgId + ": rejected by " + peer.label + " - will not retry")
                }
            }
            DeliveryResult.TRANSPORT_FAILED, DeliveryResult.NO_RECEIPT -> {
                if (attemptNo >= BleConstants.MAX_ATTEMPTS) {
                    store.setStatus(m.msgId, MsgStatus.FAILED, "gave up after " + attemptNo + " attempts: " + detail, direction = m.direction)
                    DiagLog.e(tag, "FAILED msg=" + m.msgId + ": gave up after " + attemptNo + " attempts (" + detail + ")")
                } else {
                    val backoff = minOf(BleConstants.BACKOFF_MAX_MS, BleConstants.BACKOFF_BASE_MS shl minOf(attemptNo - 1, 6))
                    store.setStatus(m.msgId, waitState, detail, System.currentTimeMillis() + backoff, direction = m.direction)
                    DiagLog.w(tag, "RETRY LATER " + kind + " msg=" + m.msgId + " (" + result + ": " + detail + "), next try in " + (backoff / 1000) + "s or when the peer reappears")
                }
            }
        }
        onChanged()
        main.postDelayed({ pump("after attempt") }, 500)
    }
}
