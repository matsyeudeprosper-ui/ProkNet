package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.10.0: the Bluetooth bulk link, the pure part. Negotiation over BLE with a
 * session token, a lifecycle where nothing is trusted before the signed
 * handshake, bounded stages, and a probe that only passes on real bytes in
 * both directions.
 */
class BulkPlanTest {

    private val seller = "24e480e6"
    private val buyer = "0f7d57b3"

    @Test
    fun the_negotiation_messages_survive_the_wire() {
        val req = Wire.parseControl(Wire.bulkRequest(0x1234abcd)) as Wire.Control.BulkRequest
        assertEquals(0x1234abcd, req.session)
        val offer = Wire.parseControl(Wire.bulkOffer(0x1234abcd, BulkPlan.TECH_L2CAP, 0x85)) as Wire.Control.BulkOffer
        assertEquals(0x1234abcd, offer.session); assertEquals(BulkPlan.TECH_L2CAP, offer.tech); assertEquals(0x85, offer.psm)
        val ready = Wire.parseControl(Wire.bulkReady(7)) as Wire.Control.BulkReady
        assertEquals(7, ready.session)
        val cancel = Wire.parseControl(Wire.bulkCancel(7, "customer stopped")) as Wire.Control.BulkCancel
        assertEquals(7, cancel.session); assertEquals("customer stopped", cancel.detail)
        // a truncated message is nothing, not a crash and not a default
        assertNull(Wire.parseControl(byteArrayOf(Wire.OP_BULK_OFFER.toByte(), 1, 2)))
        assertNull(Wire.parseControl(byteArrayOf(Wire.OP_BULK_REQUEST.toByte())))
        // the probe verdict for one direction
        val done = Wire.parseBulkProbeDone(Wire.bulkProbeDone(1_048_576L, 4321L))!!
        assertEquals(1_048_576L, done.first); assertEquals(4321L, done.second)
        assertNull(Wire.parseBulkProbeDone(ByteArray(3)))
        // a negative session token survives too
        assertEquals(-5, (Wire.parseControl(Wire.bulkRequest(-5)) as Wire.Control.BulkRequest).session)
    }

    @Test
    fun a_stale_or_foreign_offer_changes_nothing() {
        val s = BulkPlan.request(BulkPlan.IDLE, session = 111, peer = seller)
        assertEquals(BulkPlan.Phase.REQUESTED, s.phase)
        // an offer from an earlier purchase
        assertEquals(s, BulkPlan.offerReceived(s, session = 110, peer = seller, tech = BulkPlan.TECH_L2CAP, psm = 0x85))
        assertFalse(BulkPlan.isOurOffer(s, 110, seller))
        // an offer from another phone, even with our session
        assertEquals(s, BulkPlan.offerReceived(s, 111, "deadbeef", BulkPlan.TECH_L2CAP, 0x85))
        // the right one
        val c = BulkPlan.offerReceived(s, 111, seller, BulkPlan.TECH_L2CAP, 0x85)
        assertEquals(BulkPlan.Phase.CONNECTING, c.phase); assertEquals(0x85, c.psm)
        // an offer with a technology we do not speak, or no PSM, ends the attempt with a reason
        assertEquals(BulkPlan.Phase.FAILED, BulkPlan.offerReceived(s, 111, seller, 9, 0x85).phase)
        assertEquals(BulkPlan.Phase.FAILED, BulkPlan.offerReceived(s, 111, seller, BulkPlan.TECH_L2CAP, 0).phase)
        // a second request while one is active is ignored
        assertEquals(s, BulkPlan.request(s, 222, seller))
    }

    @Test
    fun a_socket_is_never_a_link_before_the_signed_handshake() {
        val c = BulkPlan.offerReceived(BulkPlan.request(BulkPlan.IDLE, 5, seller), 5, seller, BulkPlan.TECH_L2CAP, 0x85)
        val a = BulkPlan.socketConnected(c)
        assertEquals(BulkPlan.Phase.AUTH, a.phase)
        assertFalse("connected is not authenticated", a.authenticated)
        assertFalse("no tunnel bytes on an unauthenticated socket", BulkPlan.mayCarry(a, seller))
        // the handshake verifies somebody else: refused
        val wrong = BulkPlan.authenticated(a, "deadbeef")
        assertEquals(BulkPlan.Phase.FAILED, wrong.phase)
        assertFalse(BulkPlan.mayCarry(wrong, seller))
        // the handshake verifies the peer we negotiated with: UP, and only then bulk capable
        val up = BulkPlan.authenticated(a, seller)
        assertEquals(BulkPlan.Phase.UP, up.phase)
        assertTrue(up.authenticated)
        assertTrue(BulkPlan.mayCarry(up, seller))
        assertFalse("and never for another peer", BulkPlan.mayCarry(up, buyer))
        // the provider side, mirrored
        val l = BulkPlan.listening(BulkPlan.IDLE, 5, buyer, 0x85)
        assertEquals(BulkPlan.Side.HOST, l.side)
        assertEquals(BulkPlan.Phase.AUTH, BulkPlan.socketConnected(l).phase)
        assertEquals(BulkPlan.Phase.UP, BulkPlan.authenticated(BulkPlan.socketConnected(l), buyer).phase)
    }

    @Test
    fun every_stage_is_bounded_and_a_late_timer_cannot_kill_a_newer_session() {
        for (p in listOf(BulkPlan.Phase.REQUESTED, BulkPlan.Phase.LISTENING, BulkPlan.Phase.CONNECTING, BulkPlan.Phase.AUTH)) {
            assertTrue(p.toString(), BulkPlan.timeoutMs(p) > 0)
            assertTrue(BulkPlan.timeoutReason(p).isNotEmpty())
        }
        assertEquals(0L, BulkPlan.timeoutMs(BulkPlan.Phase.UP))
        val s = BulkPlan.request(BulkPlan.IDLE, 9, seller)
        assertTrue(BulkPlan.timerApplies(s, BulkPlan.Phase.REQUESTED, 9))
        // the timer of a previous session fires after a new purchase started
        assertFalse(BulkPlan.timerApplies(s, BulkPlan.Phase.REQUESTED, 8))
        // or after the phase moved on
        assertFalse(BulkPlan.timerApplies(BulkPlan.offerReceived(s, 9, seller, 1, 0x85), BulkPlan.Phase.REQUESTED, 9))
        // a failure ends it, a reset returns to IDLE with nothing kept
        val f = BulkPlan.failed(s, "the provider did not offer a Bluetooth bulk channel")
        assertEquals(BulkPlan.Phase.FAILED, f.phase); assertFalse(f.active)
        assertEquals(BulkPlan.IDLE, BulkPlan.reset(f))
        assertEquals("failing IDLE is still IDLE", BulkPlan.IDLE, BulkPlan.failed(BulkPlan.IDLE, "x"))
    }

    @Test
    fun the_probe_passes_only_on_the_whole_payload_both_ways() {
        val full = BulkPlan.PROBE_BYTES.toLong()
        val ok = BulkPlan.Direction(full, 9_000)
        val part = BulkPlan.Direction(243_712, 30_000, timedOut = true)
        val none = BulkPlan.Direction(0, 0, timedOut = true)
        assertEquals(BulkPlan.Verdict.NOT_RUN, BulkPlan.verdict(null, null))
        assertEquals(BulkPlan.Verdict.BIDIRECTIONAL, BulkPlan.verdict(ok, ok))
        assertTrue(BulkPlan.probePassed(BulkPlan.Verdict.BIDIRECTIONAL))
        assertFalse(BulkPlan.probePassed(BulkPlan.Verdict.PARTIAL))
        assertFalse(BulkPlan.probePassed(BulkPlan.Verdict.NO_DATA))
        assertFalse("a socket that connected is not a link that carries", BulkPlan.probePassed(BulkPlan.verdict(ok, null)))
        assertEquals(28L, ok.kbps())
        assertTrue(ProductState.lostHint(BulkPlan.PROBE_FAIL_REASON).contains("trop faible"))
        for (v in BulkPlan.Verdict.values()) assertTrue(BulkPlan.verdictText(v).isNotEmpty())
        // the 256 KB target is what the phones measured they can do in well under a timeout
        assertEquals(262_144, BulkPlan.PROBE_BYTES)
        assertTrue(BulkPlan.PROBE_REPORT_TIMEOUT_MS > BulkPlan.PROBE_RECEIVE_TIMEOUT_MS)
        assertTrue(part.describe().contains("PARTIAL"))
        assertTrue(none.describe().contains("no receipt report"))
    }

    @Test
    fun a_partial_payload_is_reported_as_partial_not_as_nothing() {
        // the v0.10.1 phones: seller -> buyer full, buyer -> seller 696-966 KB of 1 MB then killed. That is PARTIAL.
        val full = BulkPlan.Direction(BulkPlan.PROBE_BYTES.toLong(), 9_000)
        val part = BulkPlan.Direction(243_712, 30_000, timedOut = true)
        assertEquals(BulkPlan.DirectionResult.NO_DATA, BulkPlan.judge(0))
        assertEquals(BulkPlan.DirectionResult.PARTIAL, BulkPlan.judge(1))
        assertEquals(BulkPlan.DirectionResult.PARTIAL, BulkPlan.judge(BulkPlan.PROBE_BYTES - 1L))
        assertEquals(BulkPlan.DirectionResult.PASS, BulkPlan.judge(BulkPlan.PROBE_BYTES.toLong()))
        assertEquals(BulkPlan.DirectionResult.PASS, BulkPlan.judge(BulkPlan.PROBE_BYTES + 5L))

        val v = BulkPlan.verdict(part, full)
        assertEquals(BulkPlan.Verdict.PARTIAL, v)
        assertEquals("PARTIAL, buyer -> seller timed out", BulkPlan.verdictText(v, part, full))
        assertEquals("Connection test failed. Buyer -> seller was too slow.", BulkPlan.failureSentence(v, part, full))
        assertEquals("243,712 / 262,144 B PARTIAL (timeout)", part.describe())

        // the other way round, and a direction that carried nothing
        assertEquals("PARTIAL, seller -> buyer timed out", BulkPlan.verdictText(BulkPlan.verdict(full, part), full, part))
        val none = BulkPlan.Direction(0, 0, timedOut = true)
        assertEquals(BulkPlan.Verdict.NO_DATA, BulkPlan.verdict(full, none))
        assertEquals("NO_DATA, seller -> buyer carried nothing", BulkPlan.verdictText(BulkPlan.Verdict.NO_DATA, full, none))
        assertEquals("Connection test failed. Seller -> buyer carried no data.", BulkPlan.failureSentence(BulkPlan.Verdict.NO_DATA, full, none))
        // direction 2 never ran because direction 1 failed: the verdict names direction 1
        assertEquals("PARTIAL, buyer -> seller timed out", BulkPlan.verdictText(BulkPlan.verdict(part, null), part, null))
        assertNull(BulkPlan.failingDirection(full, full))
    }

    @Test
    fun the_probe_runs_one_direction_at_a_time_buyer_first() {
        // the buyer (client) sends first; the seller (host) only after it confirmed the buyer's payload
        assertEquals(BulkPlan.ProbeStep.BUYER_TO_SELLER, BulkPlan.nextProbeStep(BulkPlan.ProbeStep.NOT_STARTED))
        assertEquals(BulkPlan.ProbeStep.SELLER_TO_BUYER, BulkPlan.nextProbeStep(BulkPlan.ProbeStep.BUYER_TO_SELLER))
        assertEquals(BulkPlan.ProbeStep.COMPLETE, BulkPlan.nextProbeStep(BulkPlan.ProbeStep.SELLER_TO_BUYER))
        assertEquals(BulkPlan.ProbeStep.COMPLETE, BulkPlan.nextProbeStep(BulkPlan.ProbeStep.COMPLETE))
        assertTrue(BulkPlan.probeSender(BulkPlan.ProbeStep.BUYER_TO_SELLER, isHost = false))
        assertFalse(BulkPlan.probeSender(BulkPlan.ProbeStep.BUYER_TO_SELLER, isHost = true))
        assertTrue(BulkPlan.probeSender(BulkPlan.ProbeStep.SELLER_TO_BUYER, isHost = true))
        assertFalse(BulkPlan.probeSender(BulkPlan.ProbeStep.SELLER_TO_BUYER, isHost = false))
        // nobody sends outside a step: no full-duplex contention is possible
        for (host in listOf(true, false)) {
            assertFalse(BulkPlan.probeSender(BulkPlan.ProbeStep.NOT_STARTED, host))
            assertFalse(BulkPlan.probeSender(BulkPlan.ProbeStep.COMPLETE, host))
        }
        // in every step exactly one side sends
        for (s in listOf(BulkPlan.ProbeStep.BUYER_TO_SELLER, BulkPlan.ProbeStep.SELLER_TO_BUYER))
            assertTrue(BulkPlan.probeSender(s, true) != BulkPlan.probeSender(s, false))
        assertEquals("PROBE BUYER_TO_SELLER", BulkPlan.probeStepText(BulkPlan.ProbeStep.BUYER_TO_SELLER))
        assertEquals("PROBE SELLER_TO_BUYER", BulkPlan.probeStepText(BulkPlan.ProbeStep.SELLER_TO_BUYER))
        assertEquals("PROBE COMPLETE", BulkPlan.probeStepText(BulkPlan.ProbeStep.COMPLETE))
    }

    @Test
    fun the_tunnel_writes_to_the_bulk_link_that_holds_the_peer_and_never_to_gatt() {
        // Wi-Fi first when it is up, Bluetooth bulk when it is the one holding the peer, GATT for control only
        assertEquals(Routing.TRANSPORT_WIFI, Routing.chooseTransport(wifiUp = true, bulkUp = true, bleReachable = true))
        assertEquals(Routing.TRANSPORT_BT_BULK, Routing.chooseTransport(wifiUp = false, bulkUp = true, bleReachable = true))
        assertEquals(Routing.TRANSPORT_BLE, Routing.chooseTransport(wifiUp = false, bulkUp = false, bleReachable = true))
        assertNull(Routing.chooseTransport(false, false, false))
        // GATT stays available for control while the bulk link is UP: the two-argument rule is unchanged
        assertEquals(Routing.TRANSPORT_BLE, Routing.chooseTransport(wifiUp = false, bleReachable = true))
        // the Internet tunnel itself
        assertEquals(Routing.TRANSPORT_BT_BULK, Routing.bulkLinkFor(wifiPeer = null, bulkPeer = seller, peer = seller))
        assertEquals(Routing.TRANSPORT_WIFI, Routing.bulkLinkFor(wifiPeer = seller, bulkPeer = null, peer = seller))
        assertNull("no bulk link to that peer means no tunnel frame, never GATT", Routing.bulkLinkFor(null, null, seller))
        assertNull(Routing.bulkLinkFor(null, buyer, seller))
    }

    @Test
    fun the_purchase_chooses_bluetooth_for_a_provider_on_home_wifi_and_keeps_the_hotspot_for_mobile_data() {
        val t = P2pPlan.Topology.SELLER_GROUP_OWNER
        // the OUKITEL on the Freebox: hotspot refused, Bluetooth advertised
        assertEquals(P2pAdmission.BuyPath.BLUETOOTH_BULK,
            P2pAdmission.buyPath(t, offerP2p = false, linkUp = false, viaRelay = false, offerBulkBt = true, sellerOnWifi = true, preferBluetooth = false))
        // a provider on mobile data keeps the proven hotspot
        assertEquals(P2pAdmission.BuyPath.HOTSPOT,
            P2pAdmission.buyPath(t, false, false, false, offerBulkBt = true, sellerOnWifi = false, preferBluetooth = false))
        // unless the lab prefers Bluetooth
        assertEquals(P2pAdmission.BuyPath.BLUETOOTH_BULK,
            P2pAdmission.buyPath(t, false, false, false, offerBulkBt = true, sellerOnWifi = false, preferBluetooth = true))
        // a provider that does not advertise Bluetooth cannot be bought over it
        assertEquals(P2pAdmission.BuyPath.HOTSPOT,
            P2pAdmission.buyPath(t, false, false, false, offerBulkBt = false, sellerOnWifi = true, preferBluetooth = true))
        // an existing authenticated link always wins
        assertEquals(P2pAdmission.BuyPath.LINK_UP,
            P2pAdmission.buyPath(t, false, linkUp = true, viaRelay = false, offerBulkBt = true, sellerOnWifi = true, preferBluetooth = true))
        // the advert bit
        val f = Market.flags(sell = true, relay = false, validated = true, upstreamType = Tunnel.UP_WIFI, bulkBt = true)
        val o = Market.Offer(seller, 5, f, -50, 0L)
        assertTrue(o.bulkBt); assertTrue(o.selling); assertEquals(Tunnel.UP_WIFI, o.upstreamType)
        assertFalse(Market.Offer(seller, 5, Market.flags(true, false, true, Tunnel.UP_WIFI), -50, 0L).bulkBt)
        assertTrue("bit 7 fits the one advert byte", Market.FLAG_BULK_BT <= 0xFF)
    }

    @Test
    fun accounting_and_the_provider_upstream_do_not_know_which_link_carried_the_bytes() {
        // a checkpoint is bytes and a price, never a transport name
        val c = Tunnel.Accounting(seller, "buyer", 1_000L)
        c.bytesUp += 1_000_000L; c.bytesDown += 2_000_000L
        assertEquals(3_000_000L, c.bytesUp + c.bytesDown)
        assertEquals("", c.disconnectReason)
        // the bulk lifecycle carries no gateway or upstream field at all: it cannot touch them
        val s = BulkPlan.authenticated(BulkPlan.socketConnected(BulkPlan.listening(BulkPlan.IDLE, 1, buyer, 0x85)), buyer)
        assertEquals(setOf("phase", "side", "session", "peer", "psm", "authenticated", "error"),
            BulkPlan.State::class.java.declaredFields.map { it.name }.filter { !it.startsWith("$") }.toSet())
        assertTrue(s.authenticated)
    }

    // ---- v0.10.1: a home-Wi-Fi seller stays on Wi-Fi and serves over Bluetooth, never Wi-Fi Direct ------

    @Test
    fun the_seller_access_path_never_chooses_wifi_direct_automatically() {
        // home Wi-Fi upstream + Bluetooth available: Bluetooth bulk, and no hotspot probe
        val bt = BulkPlan.sellerAccessPath(upstreamIsWifi = true, bulkSupported = true, bluetoothOn = true)
        assertEquals(BulkPlan.SellerAccessPath.BLUETOOTH_BULK, bt)
        assertFalse("a Bluetooth seller does not touch the Wi-Fi radio", BulkPlan.needsHotspotProbe(bt))

        // mobile data upstream: the proven hotspot path, which is the one that probes
        val hs = BulkPlan.sellerAccessPath(upstreamIsWifi = false, bulkSupported = true, bluetoothOn = true)
        assertEquals(BulkPlan.SellerAccessPath.HOTSPOT, hs)
        assertTrue(BulkPlan.needsHotspotProbe(hs))

        // home Wi-Fi but Bluetooth off or unsupported: NONE, never an automatic Wi-Fi Direct group
        assertEquals(BulkPlan.SellerAccessPath.NONE, BulkPlan.sellerAccessPath(true, bulkSupported = true, bluetoothOn = false))
        assertEquals(BulkPlan.SellerAccessPath.NONE, BulkPlan.sellerAccessPath(true, bulkSupported = false, bluetoothOn = true))
        assertFalse(BulkPlan.needsHotspotProbe(BulkPlan.SellerAccessPath.NONE))

        // NONE is honest, not silent
        assertFalse(BulkPlan.SellerAccessPath.values().any { it.name.contains("WIFI_DIRECT") || it.name.contains("P2P") })
        for (p in BulkPlan.SellerAccessPath.values()) assertTrue(BulkPlan.accessPathText(p).isNotEmpty())
        assertFalse("the Bluetooth path text says it leaves the Wi-Fi radio alone",
            BulkPlan.accessPathText(BulkPlan.SellerAccessPath.BLUETOOTH_BULK).contains("hotspot"))
    }

    @Test
    fun a_remembered_hotspot_refusal_does_not_matter_to_a_bluetooth_seller() {
        // the whole point: a phone that cannot host a hotspot on this network still serves over Bluetooth
        val bt = BulkPlan.sellerAccessPath(upstreamIsWifi = true, bulkSupported = true, bluetoothOn = true)
        assertEquals(BulkPlan.SellerAccessPath.BLUETOOTH_BULK, bt)
        assertFalse(BulkPlan.needsHotspotProbe(bt))
        // and when the Wi-Fi network changes under it, the answer is the same: still Bluetooth, still no probe
        val again = BulkPlan.sellerAccessPath(true, true, true)
        assertEquals(BulkPlan.SellerAccessPath.BLUETOOTH_BULK, again)
        assertFalse(BulkPlan.needsHotspotProbe(again))
    }
}
