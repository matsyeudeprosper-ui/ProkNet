package net.prok.proknet.core

/** Outcome of one delivery attempt, as seen by the sender (from the receipt). */
enum class DeliveryResult {
    DELIVERED,        // receipt ACCEPTED: the destination stored the message (final)
    DUPLICATE,        // receipt DUPLICATE: peer already had it -> counts as success for this step
    RELAYED,          // receipt ACCEPTED_RELAY: a relay took custody (NOT final delivery)
    REJECTED,         // receipt REJECTED: peer refused the packet
    NO_RECEIPT,       // write acked but receipt missing/mismatched -> retry later
    TRANSPORT_FAILED, // could not connect / write -> retry later
}

/**
 * All routing DECISIONS of ProkNet, as pure functions. No Android, no BLE, no
 * database: the BLE layer feeds them facts and executes what they return.
 * This is what the JVM unit tests exercise.
 *
 * Milestone 2C1 rules:
 *  - direct delivery whenever the destination is in range
 *  - otherwise ONE handoff to one relay, then the origin is done (handed_off)
 *  - a relay forwards only to the exact destination, never to another relay
 *  - a relay refuses packets that already passed a relay, or whose TTL is spent
 *  - (origin, message ID) identifies a message everywhere
 */
object Routing {
    const val MAX_ATTEMPTS = 50
    const val BACKOFF_BASE_MS = 5_000L
    const val BACKOFF_MAX_MS = 60_000L
    const val QUEUE_TTL_MS = 48L * 3600_000L

    // ---- receive side ------------------------------------------------------------------------

    enum class Receive { FINAL, RELAY, DUPLICATE, REJECT_MALFORMED, REJECT_TTL, REJECT_ALREADY_RELAYED }

    /**
     * What to do with a packet that just arrived.
     * [alreadyKnown]: the store already has this (origin, message ID) in any direction.
     */
    fun decideReceive(pkt: Packet?, myId: ByteArray, alreadyKnown: Boolean): Receive {
        if (pkt == null) return Receive.REJECT_MALFORMED
        if (pkt.isFor(myId)) return if (alreadyKnown) Receive.DUPLICATE else Receive.FINAL
        if (pkt.hops + 1 > pkt.ttl) return Receive.REJECT_TTL
        if (pkt.hops >= 1) return Receive.REJECT_ALREADY_RELAYED
        return if (alreadyKnown) Receive.DUPLICATE else Receive.RELAY
    }

    /** Receipt code the receiver answers for a decision. */
    fun receiptFor(r: Receive): Int = when (r) {
        Receive.FINAL -> RECEIPT_ACCEPTED
        Receive.RELAY -> RECEIPT_ACCEPTED_RELAY
        Receive.DUPLICATE -> RECEIPT_DUPLICATE
        else -> RECEIPT_REJECTED
    }

    const val RECEIPT_REJECTED = 0
    const val RECEIPT_ACCEPTED = 1
    const val RECEIPT_DUPLICATE = 2
    const val RECEIPT_ACCEPTED_RELAY = 3

    fun resultFor(receiptStatus: Int): DeliveryResult = when (receiptStatus) {
        RECEIPT_ACCEPTED -> DeliveryResult.DELIVERED
        RECEIPT_DUPLICATE -> DeliveryResult.DUPLICATE
        RECEIPT_ACCEPTED_RELAY -> DeliveryResult.RELAYED
        else -> DeliveryResult.REJECTED
    }

    // ---- send side: what to attempt next ---------------------------------------------------

    class PeerView(val shortId: String, val rssi: Int)

    /** A waiting message, as the planner needs to see it. */
    class Item(
        val msgId: String,
        val isCarry: Boolean,
        val destShort: String,
        val ttl: Int,
        val hops: Int,
        val nextAttempt: Long,
        val attempts: Int,
    )

    enum class Kind { DIRECT, HANDOFF, FORWARD }

    class Plan(val item: Item, val targetShort: String, val kind: Kind)

    /**
     * Pick the next attempt. Carried packets first (they are not ours), then own
     * messages: direct if the destination is present, else one handoff to the
     * strongest other peer. Returns null when nothing can be attempted now.
     */
    fun plan(carrying: List<Item>, pending: List<Item>, inRange: List<PeerView>, now: Long): Plan? {
        if (inRange.isEmpty()) return null
        val present = inRange.associateBy { it.shortId }
        for (c in carrying) {
            if (c.nextAttempt > now) continue
            if (present.containsKey(c.destShort)) return Plan(c, c.destShort, Kind.FORWARD)
            // a relay never offers a carried packet to anyone but its destination
        }
        for (m in pending) {
            if (m.nextAttempt > now) continue
            if (present.containsKey(m.destShort)) return Plan(m, m.destShort, Kind.DIRECT)
            if (m.ttl <= 0) continue
            val relay = inRange.filter { it.shortId != m.destShort }.maxByOrNull { it.rssi } ?: continue
            return Plan(m, relay.shortId, Kind.HANDOFF)
        }
        return null
    }

    // ---- send side: what a result means ----------------------------------------------------

    class Transition(
        val status: String,       // MsgStatus.* value to store
        val backoffMs: Long,      // > 0 when the message goes back to waiting
        val via: String?,         // relay that took custody (handoff success)
        val log: String,          // one human line for the diagnostic log
        val level: Char,          // 'I', 'W' or 'E'
    )

    fun backoff(attemptNo: Int): Long =
        minOf(BACKOFF_MAX_MS, BACKOFF_BASE_MS shl minOf(maxOf(attemptNo - 1, 0), 6))

    /** Map the receipt of an attempt of [kind] to the message's next state. */
    fun applyResult(kind: Kind, result: DeliveryResult, attemptNo: Int, peerShort: String): Transition {
        val isCarry = kind == Kind.FORWARD
        val waiting = if (isCarry) MsgStatus.CARRYING else MsgStatus.PENDING
        return when (result) {
            DeliveryResult.DELIVERED, DeliveryResult.DUPLICATE -> when (kind) {
                Kind.FORWARD -> Transition(MsgStatus.FORWARDED, 0, null,
                    "FORWARDED to destination prok-" + peerShort + " after " + attemptNo + " attempt(s)" +
                        (if (result == DeliveryResult.DUPLICATE) " (destination already had it)" else "") + " - custody complete", 'I')
                Kind.HANDOFF -> if (result == DeliveryResult.DUPLICATE)
                    Transition(MsgStatus.HANDED_OFF, 0, peerShort, "HANDED OFF to relay prok-" + peerShort + " (relay already had it) - NOT final delivery", 'I')
                else
                    // A relay answered ACCEPTED: it says it IS the destination. Trust the receipt.
                    Transition(MsgStatus.DELIVERED, 0, null, "DELIVERED to prok-" + peerShort + " (handoff target was the destination) - final", 'I')
                Kind.DIRECT -> Transition(MsgStatus.DELIVERED, 0, null,
                    "DELIVERED to prok-" + peerShort + " after " + attemptNo + " attempt(s)" +
                        (if (result == DeliveryResult.DUPLICATE) " (peer already had it)" else "") + " - final", 'I')
            }
            DeliveryResult.RELAYED -> when (kind) {
                Kind.FORWARD -> Transition(MsgStatus.CARRYING, BACKOFF_MAX_MS, null,
                    "forward got ACCEPTED_RELAY from prok-" + peerShort + " - it is not the destination, still carrying", 'W')
                Kind.HANDOFF -> Transition(MsgStatus.HANDED_OFF, 0, peerShort,
                    "HANDED OFF to relay prok-" + peerShort + " - custody accepted, NOT final delivery", 'I')
                Kind.DIRECT -> Transition(MsgStatus.PENDING, BACKOFF_MAX_MS, null,
                    "direct send to prok-" + peerShort + " answered ACCEPTED_RELAY: it is not the destination we think it is, still pending", 'W')
            }
            DeliveryResult.REJECTED -> when (kind) {
                Kind.HANDOFF -> Transition(MsgStatus.PENDING, BACKOFF_MAX_MS, null,
                    "relay prok-" + peerShort + " REFUSED custody, still pending", 'W')
                else -> Transition(MsgStatus.FAILED, 0, null, "FAILED: rejected by prok-" + peerShort + " - will not retry", 'E')
            }
            DeliveryResult.TRANSPORT_FAILED, DeliveryResult.NO_RECEIPT ->
                if (attemptNo >= MAX_ATTEMPTS)
                    Transition(MsgStatus.FAILED, 0, null, "FAILED: gave up after " + attemptNo + " attempts (" + result + ")", 'E')
                else {
                    val b = backoff(attemptNo)
                    Transition(waiting, b, null, "RETRY LATER " + kind + " (" + result + "), next try in " + (b / 1000) + "s or when the peer reappears", 'W')
                }
        }
    }

    /** The bytes to transmit for a stored message: identity untouched, last hop = me, hops+1 when forwarding. */
    fun outgoingPacket(
        originId: ByteArray, destId: ByteArray, msgId: ByteArray, timestamp: Long, text: String,
        ttl: Int, hops: Int, isCarry: Boolean, myId: ByteArray,
    ): Packet {
        val base = Packet(originId, destId, msgId, timestamp, text, if (ttl > 0) ttl else Packet.DEFAULT_TTL, hops)
        return base.stamped(myId, if (isCarry) 1 else 0)
    }

    /** Destination bytes for a stored row: full ID when we have all 32 hex chars, else short-ID form. */
    fun destBytes(destIdHex: String, destShortHex: String): ByteArray =
        if (destIdHex.length == Packet.ID_LEN * 2) destIdHex.hexToBytes() else Packet.destFromShort(destShortHex)

    /** Padding used by the v0.2/v0.3 -> v0.4 migration: short ID + 24 zero hex chars. */
    fun legacyDestHex(peerShort: String): String = peerShort + "000000000000000000000000"
}
