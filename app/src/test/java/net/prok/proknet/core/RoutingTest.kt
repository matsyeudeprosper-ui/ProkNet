package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-JVM tests of every routing decision. The three phones are simulated
 * with a tiny in-memory "store" that mirrors the SQLite unique index on
 * (message ID, direction, origin).
 */
class RoutingTest {

    private fun id(seed: Int) = ByteArray(Packet.ID_LEN) { i -> ((seed * 31 + i) and 0xFF).toByte() }
    private val A = id(1); private val B = id(2); private val C = id(3); private val D = id(4)
    private fun short(x: ByteArray) = x.toHex().substring(0, 8)

    /** Minimal model of one phone: what it has stored, keyed like the SQLite index. */
    private class Phone(val myId: ByteArray) {
        val rows = HashMap<String, String>() // "msgId/direction/origin" -> status
        val receivedFrom = ArrayList<Pair<String, String>>() // (origin, lastHop) for FINAL
        fun knows(p: Packet) = rows.keys.any { it.startsWith(p.msgIdHex + "/") && it.endsWith("/" + p.originShort) }
        /** What ProkNetNode.onPacketReceived does, minus BLE and SQLite. Returns the receipt code. */
        fun receive(bytes: ByteArray): Int {
            val p = Packet.decode(bytes)
            val d = Routing.decideReceive(p, myId, p != null && knows(p))
            if (p != null) when (d) {
                Routing.Receive.FINAL -> { rows[p.msgIdHex + "/in/" + p.originShort] = MsgStatus.RECEIVED; receivedFrom.add(p.originShort to p.lastHopShort) }
                Routing.Receive.RELAY -> rows[p.msgIdHex + "/carry/" + p.originShort] = MsgStatus.CARRYING
                else -> {}
            }
            return Routing.receiptFor(d)
        }
    }

    private fun item(p: Packet, isCarry: Boolean, next: Long = 0, attempts: Int = 0) =
        Routing.Item(p.msgIdHex, isCarry, p.destShort, p.ttl, p.hops, next, attempts)

    // ---- receive decisions -----------------------------------------------------------------

    @Test
    fun destination_accepts_final_and_relay_accepts_custody() {
        val p = Packet.text(A, C, "hi").stamped(A)
        assertEquals(Routing.Receive.FINAL, Routing.decideReceive(p, C, alreadyKnown = false))
        assertEquals(Routing.Receive.RELAY, Routing.decideReceive(p, B, alreadyKnown = false))
        assertEquals(Routing.RECEIPT_ACCEPTED, Routing.receiptFor(Routing.Receive.FINAL))
        assertEquals(Routing.RECEIPT_ACCEPTED_RELAY, Routing.receiptFor(Routing.Receive.RELAY))
    }

    @Test
    fun ttl_exhausted_is_rejected_for_relay_but_not_for_destination() {
        val spent = Packet(A, C, ByteArray(8) { 1 }, 0, "t", ttl = 1, hops = 1)
        assertEquals(Routing.Receive.REJECT_TTL, Routing.decideReceive(spent, B, false))
        assertEquals(Routing.Receive.FINAL, Routing.decideReceive(spent, C, false))
        val zero = Packet(A, C, ByteArray(8) { 1 }, 0, "t", ttl = 0, hops = 0)
        assertEquals(Routing.Receive.REJECT_TTL, Routing.decideReceive(zero, B, false))
        assertEquals(Routing.RECEIPT_REJECTED, Routing.receiptFor(Routing.Receive.REJECT_TTL))
    }

    @Test
    fun one_relay_limit_a_second_relay_refuses_custody() {
        val afterOneRelay = Packet(A, C, ByteArray(8) { 2 }, 0, "t", ttl = 3, hops = 1)
        assertEquals(Routing.Receive.REJECT_ALREADY_RELAYED, Routing.decideReceive(afterOneRelay, D, false))
        assertEquals(Routing.Receive.FINAL, Routing.decideReceive(afterOneRelay, C, false))
    }

    @Test
    fun malformed_packet_is_rejected_not_crashed() {
        assertEquals(Routing.Receive.REJECT_MALFORMED, Routing.decideReceive(null, C, false))
        assertEquals(Routing.RECEIPT_REJECTED, Routing.receiptFor(Routing.Receive.REJECT_MALFORMED))
        val phone = Phone(C)
        assertEquals(Routing.RECEIPT_REJECTED, phone.receive(ByteArray(0)))
        assertEquals(Routing.RECEIPT_REJECTED, phone.receive(byteArrayOf(0x50, 0x4B, 3, 1, 0)))
        assertTrue(phone.rows.isEmpty())
    }

    @Test
    fun duplicate_packet_is_not_delivered_twice() {
        val c = Phone(C)
        val p = Packet.text(A, C, "once").stamped(A).encode()
        assertEquals(Routing.RECEIPT_ACCEPTED, c.receive(p))
        assertEquals(Routing.RECEIPT_DUPLICATE, c.receive(p))
        assertEquals(Routing.RECEIPT_DUPLICATE, c.receive(p))
        assertEquals(1, c.receivedFrom.size)
        // the same message arriving later through a relay is also a duplicate
        val viaB = Packet.decode(p)!!.stamped(B, extraHops = 1).encode()
        assertEquals(Routing.RECEIPT_DUPLICATE, c.receive(viaB))
        assertEquals(1, c.receivedFrom.size)
        // and a relay that already carries it says duplicate too
        val b = Phone(B)
        assertEquals(Routing.RECEIPT_ACCEPTED_RELAY, b.receive(p))
        assertEquals(Routing.RECEIPT_DUPLICATE, b.receive(p))
        assertEquals(1, b.rows.size)
    }

    // ---- planner ------------------------------------------------------------------------------

    @Test
    fun direct_delivery_wins_when_destination_is_present() {
        val p = Packet.text(A, C, "direct")
        val pending = listOf(item(p, isCarry = false))
        val peers = listOf(Routing.PeerView(short(B), -40), Routing.PeerView(short(C), -90))
        val plan = Routing.plan(emptyList(), pending, peers, now = 1000)
        assertNotNull(plan)
        assertEquals(Routing.Kind.DIRECT, plan!!.kind)
        assertEquals(short(C), plan.targetShort)
    }

    @Test
    fun handoff_goes_to_strongest_other_peer_when_destination_absent() {
        val p = Packet.text(A, C, "relay")
        val pending = listOf(item(p, isCarry = false))
        val peers = listOf(Routing.PeerView(short(B), -70), Routing.PeerView(short(D), -50))
        val plan = Routing.plan(emptyList(), pending, peers, now = 1000)!!
        assertEquals(Routing.Kind.HANDOFF, plan.kind)
        assertEquals(short(D), plan.targetShort)
        // nobody around: nothing to do
        assertNull(Routing.plan(emptyList(), pending, emptyList(), 1000))
        // TTL 0 messages are never handed off
        val noTtl = Routing.Item(p.msgIdHex, false, p.destShort, 0, 0, 0, 0)
        assertNull(Routing.plan(emptyList(), listOf(noTtl), peers, 1000))
    }

    @Test
    fun relay_forwards_only_to_the_exact_destination() {
        val p = Packet.text(A, C, "carried")
        val carrying = listOf(item(p, isCarry = true))
        // A and D are around, C is not: the relay must do nothing
        assertNull(Routing.plan(carrying, emptyList(), listOf(Routing.PeerView(short(A), -30), Routing.PeerView(short(D), -30)), 1000))
        // C appears: forward, to C only
        val plan = Routing.plan(carrying, emptyList(), listOf(Routing.PeerView(short(D), -30), Routing.PeerView(short(C), -80)), 1000)!!
        assertEquals(Routing.Kind.FORWARD, plan.kind)
        assertEquals(short(C), plan.targetShort)
    }

    @Test
    fun carried_packets_go_before_own_messages_and_backoff_is_respected() {
        val own = Packet.text(A, C, "mine")
        val carried = Packet.text(D, C, "theirs")
        val peers = listOf(Routing.PeerView(short(C), -50))
        val plan = Routing.plan(listOf(item(carried, true)), listOf(item(own, false)), peers, 1000)!!
        assertEquals(Routing.Kind.FORWARD, plan.kind)
        assertEquals(carried.msgIdHex, plan.item.msgId)
        // carried one in backoff: own message goes
        val plan2 = Routing.plan(listOf(item(carried, true, next = 5000)), listOf(item(own, false)), peers, 1000)!!
        assertEquals(Routing.Kind.DIRECT, plan2.kind)
        assertEquals(own.msgIdHex, plan2.item.msgId)
        // both in backoff: nothing
        assertNull(Routing.plan(listOf(item(carried, true, next = 5000)), listOf(item(own, false, next = 5000)), peers, 1000))
    }

    // ---- result transitions -----------------------------------------------------------------

    @Test
    fun handoff_produces_handed_off_never_delivered() {
        val t = Routing.applyResult(Routing.Kind.HANDOFF, DeliveryResult.RELAYED, 1, short(B))
        assertEquals(MsgStatus.HANDED_OFF, t.status)
        assertEquals(short(B), t.via)
        assertNotEquals(MsgStatus.DELIVERED, t.status)
        val dup = Routing.applyResult(Routing.Kind.HANDOFF, DeliveryResult.DUPLICATE, 2, short(B))
        assertEquals(MsgStatus.HANDED_OFF, dup.status)
        val refused = Routing.applyResult(Routing.Kind.HANDOFF, DeliveryResult.REJECTED, 1, short(B))
        assertEquals(MsgStatus.PENDING, refused.status)
        assertTrue(refused.backoffMs > 0)
    }

    @Test
    fun direct_and_forward_results_map_to_final_states() {
        assertEquals(MsgStatus.DELIVERED, Routing.applyResult(Routing.Kind.DIRECT, DeliveryResult.DELIVERED, 1, short(C)).status)
        assertEquals(MsgStatus.DELIVERED, Routing.applyResult(Routing.Kind.DIRECT, DeliveryResult.DUPLICATE, 1, short(C)).status)
        assertEquals(MsgStatus.FORWARDED, Routing.applyResult(Routing.Kind.FORWARD, DeliveryResult.DELIVERED, 1, short(C)).status)
        assertEquals(MsgStatus.FORWARDED, Routing.applyResult(Routing.Kind.FORWARD, DeliveryResult.DUPLICATE, 1, short(C)).status)
        assertEquals(MsgStatus.FAILED, Routing.applyResult(Routing.Kind.DIRECT, DeliveryResult.REJECTED, 1, short(C)).status)
        assertEquals(MsgStatus.FAILED, Routing.applyResult(Routing.Kind.FORWARD, DeliveryResult.REJECTED, 1, short(C)).status)
        // a forward that lands on a non-destination keeps carrying, never marks forwarded
        val t = Routing.applyResult(Routing.Kind.FORWARD, DeliveryResult.RELAYED, 1, short(D))
        assertEquals(MsgStatus.CARRYING, t.status)
        assertTrue(t.backoffMs > 0)
    }

    @Test
    fun transport_failures_back_off_then_give_up() {
        val t1 = Routing.applyResult(Routing.Kind.DIRECT, DeliveryResult.TRANSPORT_FAILED, 1, short(C))
        assertEquals(MsgStatus.PENDING, t1.status)
        assertEquals(Routing.BACKOFF_BASE_MS, t1.backoffMs)
        val t3 = Routing.applyResult(Routing.Kind.DIRECT, DeliveryResult.NO_RECEIPT, 3, short(C))
        assertEquals(Routing.BACKOFF_BASE_MS * 4, t3.backoffMs)
        val tBig = Routing.applyResult(Routing.Kind.FORWARD, DeliveryResult.TRANSPORT_FAILED, 20, short(C))
        assertEquals(MsgStatus.CARRYING, tBig.status)
        assertEquals(Routing.BACKOFF_MAX_MS, tBig.backoffMs)
        val gaveUp = Routing.applyResult(Routing.Kind.DIRECT, DeliveryResult.TRANSPORT_FAILED, Routing.MAX_ATTEMPTS, short(C))
        assertEquals(MsgStatus.FAILED, gaveUp.status)
    }

    // ---- the whole A -> B -> C story -----------------------------------------------------------

    @Test
    fun end_to_end_A_hands_to_B_who_forwards_to_C_who_sees_origin_A_via_B() {
        val b = Phone(B); val c = Phone(C)
        val msg = Packet.text(A, C, "relay test")
        // A: C absent, B present -> handoff
        val planA = Routing.plan(emptyList(), listOf(item(msg, false)), listOf(Routing.PeerView(short(B), -50)), 0)!!
        assertEquals(Routing.Kind.HANDOFF, planA.kind)
        val onAirA = Routing.outgoingPacket(A, C, msg.msgId, msg.timestamp, msg.text, msg.ttl, 0, isCarry = false, myId = A)
        assertEquals(short(A), onAirA.lastHopShort)
        val receiptB = b.receive(onAirA.encode())
        assertEquals(Routing.RECEIPT_ACCEPTED_RELAY, receiptB)
        val tA = Routing.applyResult(planA.kind, Routing.resultFor(receiptB), 1, short(B))
        assertEquals(MsgStatus.HANDED_OFF, tA.status)
        // B: carries; A gone; only D around -> nothing
        val carriedAtB = Packet.decode(onAirA.encode())!!
        assertNull(Routing.plan(listOf(item(carriedAtB, true)), emptyList(), listOf(Routing.PeerView(short(D), -40)), 0))
        // B: C appears -> forward with hops 1 and last hop B
        val planB = Routing.plan(listOf(item(carriedAtB, true)), emptyList(), listOf(Routing.PeerView(short(C), -60)), 0)!!
        assertEquals(Routing.Kind.FORWARD, planB.kind)
        val onAirB = Routing.outgoingPacket(carriedAtB.originId, carriedAtB.destId, carriedAtB.msgId, carriedAtB.timestamp, carriedAtB.text,
            carriedAtB.ttl, carriedAtB.hops, isCarry = true, myId = B)
        assertEquals(1, onAirB.hops)
        assertEquals(short(B), onAirB.lastHopShort)
        assertEquals(short(A), onAirB.originShort)
        val receiptC = c.receive(onAirB.encode())
        assertEquals(Routing.RECEIPT_ACCEPTED, receiptC)
        assertEquals(MsgStatus.FORWARDED, Routing.applyResult(planB.kind, Routing.resultFor(receiptC), 1, short(C)).status)
        // C shows: from A, via B, once
        assertEquals(1, c.receivedFrom.size)
        assertEquals(short(A), c.receivedFrom[0].first)
        assertEquals(short(B), c.receivedFrom[0].second)
        // a retry of the forward is a duplicate at C, and B still ends up 'forwarded'
        assertEquals(Routing.RECEIPT_DUPLICATE, c.receive(onAirB.encode()))
        assertEquals(1, c.receivedFrom.size)
        // a fourth phone must refuse to relay the already-relayed packet
        val d = Phone(D)
        assertEquals(Routing.RECEIPT_REJECTED, d.receive(onAirB.encode()))
        assertTrue(d.rows.isEmpty())
    }

    @Test
    fun outgoing_packet_never_alters_identity_and_uses_default_ttl_for_old_rows() {
        val msg = Packet.text(A, C, "x")
        val p = Routing.outgoingPacket(A, C, msg.msgId, msg.timestamp, "x", ttl = 0, hops = 0, isCarry = false, myId = A)
        assertEquals(Packet.DEFAULT_TTL, p.ttl)
        assertEquals(msg.msgIdHex, p.msgIdHex)
        assertEquals(short(A), p.originShort)
        assertEquals(short(C), p.destShort)
        assertFalse(p.legacy)
    }
}
