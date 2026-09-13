package net.prok.proknet.ble

import android.os.Handler
import android.os.Looper
import net.prok.proknet.core.DeliveryResult
import net.prok.proknet.core.DiagLog
import net.prok.proknet.core.Dir
import net.prok.proknet.core.Identity
import net.prok.proknet.core.MessageStore
import net.prok.proknet.core.MsgStatus
import net.prok.proknet.core.Packet
import net.prok.proknet.core.Routing
import net.prok.proknet.core.StoredMessage
import net.prok.proknet.core.hexToBytes

/**
 * STORE -> CARRY -> FORWARD executor. All DECISIONS live in core/Routing.kt
 * (pure, unit-tested); this class only reads the store, calls the planner,
 * drives BleSender and writes the transition back.
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
    /** Keep the CPU awake for the few seconds of a delivery attempt when the screen is off. */
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
        val dest = if (destFullId != null && destFullId.length == Packet.ID_LEN * 2) destFullId.hexToBytes() else Packet.destFromShort(destShortId)
        val pkt = Packet.text(identity.idBytes, dest, text)
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
        if (!running || !peer.hasId) return
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
        val cutoff = System.currentTimeMillis() - Routing.QUEUE_TTL_MS
        val victims = store.expireOlderThan(cutoff)
        for (v in victims) DiagLog.w(tag, "EXPIRED " + v.direction + " msg=" + v.msgId + " for prok-" + v.destShort + " (older than " + (Routing.QUEUE_TTL_MS / 3600_000) + "h)")
        if (victims.isNotEmpty()) onChanged()
    }

    private fun item(m: StoredMessage) = Routing.Item(m.msgId, m.direction == Dir.CARRY, m.destShort, m.ttl, m.hops, m.nextAttempt, m.attempts)

    fun pump(reason: String) {
        if (!running) return
        if (inFlight != null) return
        val sender = senderProvider() ?: return
        if (sender.isBusy) return
        val inRange = peersProvider().filter { it.inRange && it.hasId }
        if (inRange.isEmpty()) return
        val now = System.currentTimeMillis()
        val carrying = store.carrying()
        val pending = store.pending()
        if (carrying.isEmpty() && pending.isEmpty()) return
        val byId = HashMap<String, StoredMessage>()
        for (m in carrying) byId[m.msgId + "/carry"] = m
        for (m in pending) byId[m.msgId + "/out"] = m
        val plan = Routing.plan(carrying.map { item(it) }, pending.map { item(it) }, inRange.map { Routing.PeerView(it.shortId, it.rssi) }, now)
        if (plan == null) {
            if (reason == "enqueue" || reason == "manual" || reason == "peer reappeared") {
                val waiting = pending.count { it.nextAttempt > now } + carrying.count { it.nextAttempt > now }
                if (waiting > 0) DiagLog.i(tag, "pump(" + reason + "): " + waiting + " message(s) still in backoff")
            }
            return
        }
        val row = byId[plan.item.msgId + (if (plan.item.isCarry) "/carry" else "/out")] ?: return
        val peer = inRange.firstOrNull { it.shortId == plan.targetShort } ?: return
        attempt(row, peer, sender, reason, plan.kind)
    }

    private fun attempt(m: StoredMessage, peer: Peer, sender: BleSender, reason: String, kind: Routing.Kind) {
        inFlight = m.msgId
        try { wakeLock?.acquire(BleConstants.SEND_TIMEOUT_MS * 2 + 5000) } catch (e: Exception) { DiagLog.w(tag, "wakelock: " + e) }
        val isCarry = m.direction == Dir.CARRY
        store.setStatus(m.msgId, if (isCarry) MsgStatus.FORWARDING else MsgStatus.SENDING, bumpAttempts = true, direction = m.direction)
        onChanged()
        val attemptNo = m.attempts + 1
        val pkt: Packet = try {
            Routing.outgoingPacket(
                originId = if (isCarry) m.originId.hexToBytes() else identity.idBytes,
                destId = Routing.destBytes(m.destId, m.peerId),
                msgId = m.msgId.hexToBytes(), timestamp = m.timestamp, text = m.text,
                ttl = m.ttl, hops = m.hops, isCarry = isCarry, myId = identity.idBytes,
            )
        } catch (e: Exception) {
            DiagLog.e(tag, "cannot build packet for msg=" + m.msgId + " (corrupt row) - marking failed", e)
            store.setStatus(m.msgId, MsgStatus.FAILED, "corrupt row: " + e.message, direction = m.direction)
            inFlight = null
            try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Exception) {}
            onChanged()
            return
        }
        when (kind) {
            Routing.Kind.FORWARD -> DiagLog.i(tag, "FORWARDING msg=" + m.msgId + " from prok-" + m.peerId + " to its destination " + peer.label + " (hop " + pkt.hops + "/" + pkt.ttl + ", lastHop=me, attempt " + attemptNo + ", trigger: " + reason + ")")
            Routing.Kind.HANDOFF -> DiagLog.i(tag, "HANDING OFF msg=" + m.msgId + " for prok-" + m.destShort + " to relay " + peer.label + " (destination not in range, attempt " + attemptNo + ", trigger: " + reason + ")")
            Routing.Kind.DIRECT -> DiagLog.i(tag, "ATTEMPT " + attemptNo + " msg=" + m.msgId + " -> " + peer.label + " direct (trigger: " + reason + ")")
        }
        sender.send(peer, pkt.msgId, pkt.encode()) { result, detail ->
            main.post { onResult(m, attemptNo, peer, kind, result, detail) }
        }
    }

    private fun onResult(m: StoredMessage, attemptNo: Int, peer: Peer, kind: Routing.Kind, result: DeliveryResult, detail: String) {
        inFlight = null
        try { if (wakeLock?.isHeld == true) wakeLock.release() } catch (_: Exception) {}
        val t = Routing.applyResult(kind, result, attemptNo, peer.shortId)
        val nextAt = if (t.backoffMs > 0) System.currentTimeMillis() + t.backoffMs else 0L
        store.setStatus(m.msgId, t.status, detail, nextAt, direction = m.direction, via = t.via)
        val line = "msg=" + m.msgId + ": " + t.log
        when (t.level) { 'E' -> DiagLog.e(tag, line); 'W' -> DiagLog.w(tag, line); else -> DiagLog.i(tag, line) }
        onChanged()
        main.postDelayed({ pump("after attempt") }, 500)
    }
}
