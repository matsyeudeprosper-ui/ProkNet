package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.13: the signed request, the store-carry-forward, provider activation,
 * the jobs and the matching, the sync protocol, and the honesty of shared
 * coverage. Everything here runs without a phone.
 */
class NetworkBrainTest {
    private val now = 1_700_000_000_000L

    /** A real P-256 identity for tests: the same primitive the phones and the server use. */
    private class TestSigner(override val displayName: String = "test") : Signer {
        private val kp = Crypto.generateKeyPair()
        override val pubBytes: ByteArray = Crypto.publicBytes(kp.public)
        override val idBytes: ByteArray = Crypto.deriveId(pubBytes)
        val short: String get() = idBytes.copyOfRange(0, 4).toHex()
        override fun sign(data: ByteArray): ByteArray = Crypto.sign(kp.private, data)
    }

    private val buyer = TestSigner("buyer")
    private fun req(signer: TestSigner = buyer, id: String = "0123456789abcdef", zone: String = "z1:2", at: Long = now) =
        NetRequest.sign(NetRequest.oneTap(id, signer.short, signer.pubBytes.toHex(), at, zone), signer)

    // ---- the signed request ------------------------------------------------------------------------------

    @Test
    fun a_request_is_signed_compact_and_survives_the_wire_and_the_disk() {
        val r = req()
        assertTrue(NetRequest.verify(r))
        assertEquals(NetRequest.State.NETWORK_REQUESTED, r.state); assertTrue(r.open); assertNull(r.ceiling)
        assertEquals(NetRequest.FLEXIBLE, r.desiredMb); assertEquals(NetRequest.URGENCY_NOW, r.urgency)
        val bytes = NetRequest.encode(r)
        assertTrue("fits an encrypted BLE control envelope: " + bytes.size, bytes.size < 260)
        assertEquals(r, NetRequest.decode(bytes))
        assertEquals(r, NetRequest.decodeLine(NetRequest.encodeLine(r)))
        // any change breaks the signature
        assertFalse(NetRequest.verify(r.copy(ceilingCentimesPerMb = 100)))
        assertFalse(NetRequest.verify(r.copy(zone = "z9:9")))
        assertFalse(NetRequest.verify(r.copy(generation = 2)))
        // hops are not signed: a carried copy stays valid
        assertTrue(NetRequest.verify(NetRequest.forwarded(r)))
        // a request signed by someone else's key with this origin id is refused
        val other = TestSigner("other")
        assertFalse(NetRequest.verify(NetRequest.sign(r, other)))
        assertFalse(NetRequest.verify(r.copy(originPubHex = other.pubBytes.toHex())))
    }

    @Test
    fun a_tombstone_is_a_signed_newer_generation_that_carried_copies_cannot_undo() {
        val r = req()
        val t = NetRequest.tombstone(r, NetRequest.State.FULFILLED, now + 5000, buyer)
        assertTrue(NetRequest.verify(t)); assertTrue(t.tombstone); assertFalse(t.open); assertEquals(2, t.generation)
        var st = RequestGossip.State()
        st = RequestGossip.receive(st, t, now + 6000, "b").first
        // the old open copy comes back from another phone: blocked
        val (st2, why) = RequestGossip.receive(st, NetRequest.forwarded(r), now + 7000, "c")
        assertEquals(RequestGossip.Receipt.OLDER_GENERATION, why)
        assertTrue(st2.requests[r.id]!!.tombstone)
        // a forged "open again" generation 3 without the origin's key: invalid
        val forged = t.copy(state = NetRequest.State.NETWORK_REQUESTED, generation = 3)
        assertEquals(RequestGossip.Receipt.INVALID_SIGNATURE, RequestGossip.receive(st2, forged, now, "c").second)
        // even a validly signed open generation cannot replace a tombstone
        val reopened = NetRequest.sign(t.copy(state = NetRequest.State.NETWORK_REQUESTED, generation = 3), buyer)
        assertEquals(RequestGossip.Receipt.RESURRECTION_BLOCKED, RequestGossip.receive(st2, reopened, now, "c").second)
        assertEquals("DEMANDE", NetRequest.sphereWord(NetRequest.State.NETWORK_REQUESTED))
        assertEquals("CONNECTÉ", NetRequest.sphereWord(NetRequest.State.FULFILLED))
        assertTrue(NetRequest.hint(NetRequest.State.NETWORK_REQUESTED, 5_000, false).startsWith("Demande envoyée"))
        assertTrue(NetRequest.hint(NetRequest.State.NETWORK_REQUESTED, 120_000, false).startsWith("ProkNet continue"))
        assertEquals("Internet trouvé. Connexion…", NetRequest.hint(NetRequest.State.NETWORK_REQUESTED, 0, true))
    }

    // ---- store-carry-forward -------------------------------------------------------------------------------

    @Test
    fun a_request_is_stored_once_forwarded_once_per_peer_and_never_loops() {
        val r = req()
        var st = RequestGossip.State()
        val (s1, w1) = RequestGossip.receive(st, r, now, "a"); st = s1
        assertEquals(RequestGossip.Receipt.NEW, w1)
        val (s2, w2) = RequestGossip.receive(st, r, now + 1, "a"); st = s2
        assertEquals("same request twice: stored once", RequestGossip.Receipt.DUPLICATE, w2)
        assertEquals(1, st.requests.size); assertEquals(1, st.stats.dedupDrops)
        // never back to the peer it came from
        assertTrue(RequestGossip.toForward(st, "a", now).isEmpty())
        // to a new peer: once
        val toB = RequestGossip.toForward(st, "b", now)
        assertEquals(1, toB.size)
        st = RequestGossip.markForwarded(st, toB[0], "b", now)
        assertTrue(RequestGossip.toForward(st, "b", now).isEmpty())
        // b hands it back with one more hop: a duplicate, no loop
        assertEquals(RequestGossip.Receipt.DUPLICATE, RequestGossip.receive(st, NetRequest.forwarded(r), now + 2, "b").second)
        // never to its own origin
        assertTrue(RequestGossip.toForward(st, buyer.short, now).isEmpty())
        assertEquals(1, RequestGossip.carried(st, now).size)
        assertEquals(1, st.stats.forwards)
    }

    @Test
    fun expired_and_exhausted_requests_are_not_forwarded_and_the_store_survives_a_restart() {
        val r = req()
        var st = RequestGossip.receive(RequestGossip.State(), r, now, "a").first
        assertTrue(RequestGossip.toForward(st, "b", now + NetRequest.NOW_TTL_MS + 1).isEmpty())
        // received already expired: dropped, counted
        val old = req(id = "fedcba9876543210", at = now - NetRequest.NOW_TTL_MS - 1)
        val (s2, why) = RequestGossip.receive(st, old, now, "a"); st = s2
        assertEquals(RequestGossip.Receipt.EXPIRED, why); assertEquals(1, st.stats.ttlDrops)
        // hop budget
        var hopped = r; repeat(NetRequest.MAX_HOPS) { hopped = NetRequest.forwarded(hopped) }
        assertEquals(RequestGossip.Receipt.HOPS_EXHAUSTED, RequestGossip.receive(RequestGossip.State(), hopped, now, "z").second)
        // the origin's own request persists through a restart
        st = RequestGossip.originate(RequestGossip.State(), r)
        st = RequestGossip.markForwarded(st, r, "b", now)
        val back = RequestGossip.decode(RequestGossip.encode(st))
        assertEquals(st.requests, back.requests); assertEquals(st.forwarded, back.forwarded); assertEquals(st.mine, back.mine)
        assertEquals(1, RequestGossip.pendingUpload(back, now).size)
        val up = RequestGossip.markUploaded(back, RequestGossip.pendingUpload(back, now))
        assertTrue(RequestGossip.pendingUpload(up, now).isEmpty())
        // sweep: expired becomes EXPIRED, kept a while, then gone
        val swept = RequestGossip.sweep(up, now + NetRequest.NOW_TTL_MS + 1)
        assertEquals(NetRequest.State.EXPIRED, swept.requests[r.id]!!.state)
        assertTrue(RequestGossip.sweep(swept, now + NetRequest.NOW_TTL_MS + RequestGossip.TOMBSTONE_KEEP_MS + 2).requests.isEmpty())
    }

    @Test
    fun the_origin_ends_its_request_and_the_tombstone_travels() {
        val r = req()
        var st = RequestGossip.originate(RequestGossip.State(), r)
        st = RequestGossip.markForwarded(st, r, "b", now)
        st = RequestGossip.end(st, r.id, NetRequest.State.FULFILLED, now + 10_000, buyer)!!
        val t = st.requests[r.id]!!
        assertTrue(t.tombstone); assertEquals(2, t.generation); assertTrue(NetRequest.verify(t))
        // the tombstone goes to b even though generation 1 already went there
        assertEquals(listOf(t), RequestGossip.toForward(st, "b", now + 11_000))
        // and it is uploaded as its own generation
        assertEquals(1, RequestGossip.pendingUpload(RequestGossip.markUploaded(st, listOf(r)), now).size)
        assertTrue(RequestGossip.active(st, now).isEmpty())
        // ending twice is harmless
        assertEquals(st, RequestGossip.end(st, r.id, NetRequest.State.CANCELLED, now + 20_000, buyer))
    }

    // ---- provider activation -----------------------------------------------------------------------------------

    private fun elig(optIn: Boolean = true, validated: Boolean = true, path: BulkPlan.SellerAccessPath = BulkPlan.SellerAccessPath.BLUETOOTH_BULK,
                     bt: Boolean = true, sharing: Boolean = false, busy: Boolean = false, price: Int = 500) =
        ProviderActivation.Eligibility(optIn, validated, path, bt, sharing, busy, price)

    @Test
    fun an_opted_in_eligible_provider_gets_the_opportunity_and_nobody_else_does() {
        val r = req()
        val o = ProviderActivation.opportunity(elig(), r, now, local = true)!!
        assertEquals("Quelqu'un cherche Internet à proximité.", o.title)
        assertTrue(o.local)
        assertEquals("Une demande Internet existe dans votre zone.", ProviderActivation.opportunity(elig(), r, now, local = false)!!.title)
        assertEquals(ProviderActivation.Refusal.NOT_OPTED_IN, ProviderActivation.refusal(elig(optIn = false), r, now))
        assertEquals(ProviderActivation.Refusal.NO_INTERNET, ProviderActivation.refusal(elig(validated = false), r, now))
        assertEquals(ProviderActivation.Refusal.NO_LOCAL_PATH, ProviderActivation.refusal(elig(path = BulkPlan.SellerAccessPath.NONE), r, now))
        assertEquals(ProviderActivation.Refusal.BLUETOOTH_OFF, ProviderActivation.refusal(elig(bt = false), r, now))
        assertEquals(ProviderActivation.Refusal.BUSY, ProviderActivation.refusal(elig(busy = true), r, now))
        assertEquals(ProviderActivation.Refusal.ALREADY_SHARING, ProviderActivation.refusal(elig(sharing = true), r, now))
        assertEquals(ProviderActivation.Refusal.REQUEST_NOT_OPEN, ProviderActivation.refusal(elig(), r, now + NetRequest.NOW_TTL_MS + 1))
        assertEquals(ProviderActivation.Refusal.REQUEST_NOT_OPEN, ProviderActivation.refusal(elig(), NetRequest.tombstone(r, NetRequest.State.CANCELLED, now, buyer), now))
        // the price ceiling is respected: a 5 CFA provider is not woken for a 3 CFA request
        val capped = NetRequest.sign(NetRequest.oneTap("1111111111111111", buyer.short, buyer.pubBytes.toHex(), now, "z1:2").copy(ceilingCentimesPerMb = 300), buyer)
        assertEquals(ProviderActivation.Refusal.ABOVE_CEILING, ProviderActivation.refusal(elig(price = 500), capped, now))
        assertNull(ProviderActivation.refusal(elig(price = 300), capped, now))
        // the mobile-data hotspot provider is eligible too
        assertNull(ProviderActivation.refusal(elig(path = BulkPlan.SellerAccessPath.HOTSPOT, bt = false), r, now))
    }

    @Test
    fun the_availability_heartbeat_carries_no_position() {
        // v0.13.3: the wall-clock rate limit this test used to pin is gone; alerting once
        // per opportunity lives in ProviderInbox and is tested in ActivationReliabilityTest.
        val a = ProviderActivation.availability(elig(), "z1:2", Tunnel.UP_WIFI)
        assertTrue(a.potential); assertFalse(a.sharing); assertEquals("z1:2", a.zone)
        assertFalse(ProviderActivation.availability(elig(optIn = false), "z1:2", Tunnel.UP_WIFI).potential)
        assertFalse(ProviderActivation.availability(elig(busy = true), "z1:2", Tunnel.UP_WIFI).potential)
    }

    // ---- jobs and matching ----------------------------------------------------------------------------------------

    @Test
    fun jobs_execute_only_what_this_build_can_run() {
        assertTrue(Jobs.executable(Jobs.Type.PROVIDER_ACTIVATION)); assertTrue(Jobs.executable(Jobs.Type.CARRY_REQUEST))
        for (t in listOf(Jobs.Type.ANCHOR, Jobs.Type.RELAY, Jobs.Type.MOVE_TO_ZONE, Jobs.Type.COURIER)) assertFalse(t.name, Jobs.executable(t))
        val r = req()
        val j = Jobs.providerActivation("j1", r, "aabbccdd", now)
        assertEquals(Jobs.State.OFFERED, j.state); assertEquals(0L, j.rewardCentimes)
        val acc = Jobs.advance(j, Jobs.State.ACCEPTED, now + 1)!!
        val act = Jobs.advance(acc, Jobs.State.ACTIVE, now + 2)!!
        assertEquals(Jobs.State.COMPLETED, Jobs.advance(act, Jobs.State.COMPLETED, now + 3)!!.state)
        assertNull("no shortcut from OFFERED to COMPLETED", Jobs.advance(j, Jobs.State.COMPLETED, now))
        assertEquals(Jobs.State.EXPIRED, Jobs.expire(j, j.expiresAt).state)
        assertEquals(Jobs.State.ACTIVE, Jobs.carry("j2", r, "carrier", now).state)
    }

    private fun prov(id: String, zone: String = "z1:2", price: Int = 500, hb: Long = now, busy: Boolean = false, potential: Boolean = true, local: Boolean = false, sharing: Boolean = false) =
        Jobs.ProviderView(id, zone, potential, sharing, price, hb, busy, true, local)

    @Test
    fun matching_prefers_direct_then_a_reachable_or_same_zone_provider_within_the_ceiling() {
        val r = req()
        assertEquals(Jobs.Plan.DIRECT_SOURCE, Jobs.match(r, directUsable = true, providers = emptyList(), now = now).plan)
        assertEquals(Jobs.Plan.WAIT_FOR_SUPPLY, Jobs.match(r, false, emptyList(), now).plan)
        val d = Jobs.match(r, false, listOf(prov("p1"), prov("p2", price = 300)), now)
        assertEquals(Jobs.Plan.ACTIVATE_PROVIDER, d.plan); assertEquals("cheapest first", "p2", d.providerId)
        // seen locally beats a cheaper one only known by zone
        assertEquals("p1", Jobs.match(r, false, listOf(prov("p1", local = true), prov("p2", price = 300)), now).providerId)
        // a stale heartbeat, another zone, busy, sharing, not opted in: not matched
        assertEquals(Jobs.Plan.WAIT_FOR_SUPPLY, Jobs.match(r, false, listOf(prov("p1", hb = now - Jobs.HEARTBEAT_MAX_AGE_MS - 1)), now).plan)
        assertEquals(Jobs.Plan.WAIT_FOR_SUPPLY, Jobs.match(r, false, listOf(prov("p1", zone = "z9:9")), now).plan)
        assertEquals(Jobs.Plan.WAIT_FOR_SUPPLY, Jobs.match(r, false, listOf(prov("p1", busy = true)), now).plan)
        assertEquals(Jobs.Plan.WAIT_FOR_SUPPLY, Jobs.match(r, false, listOf(prov("p1", sharing = true)), now).plan)
        assertEquals(Jobs.Plan.WAIT_FOR_SUPPLY, Jobs.match(r, false, listOf(prov("p1", potential = false)), now).plan)
        // no zone on the request: only a locally seen provider can match
        val noZone = req(id = "2222222222222222", zone = CoverageModel.NO_ZONE)
        assertEquals(Jobs.Plan.WAIT_FOR_SUPPLY, Jobs.match(noZone, false, listOf(prov("p1", zone = CoverageModel.NO_ZONE)), now).plan)
        assertEquals(Jobs.Plan.ACTIVATE_PROVIDER, Jobs.match(noZone, false, listOf(prov("p1", zone = CoverageModel.NO_ZONE, local = true)), now).plan)
        // the ceiling: a 3 CFA request never activates a 5 CFA provider
        val capped = NetRequest.sign(r.copy(id = "3333333333333333", ceilingCentimesPerMb = 300), buyer)
        assertEquals(Jobs.Plan.WAIT_FOR_SUPPLY, Jobs.match(capped, false, listOf(prov("p1", price = 500)), now).plan)
        assertEquals("p1", Jobs.match(capped, false, listOf(prov("p1", price = 300)), now).providerId)
        // over: no plan
        assertEquals(Jobs.Plan.NO_PLAN, Jobs.match(r, false, listOf(prov("p1")), now + NetRequest.NOW_TTL_MS + 1).plan)
        assertEquals(Jobs.Plan.NO_PLAN, Jobs.match(NetRequest.tombstone(r, NetRequest.State.FULFILLED, now, buyer), false, listOf(prov("p1")), now).plan)
    }

    // ---- the sync protocol -----------------------------------------------------------------------------------------

    @Test
    fun a_signed_sync_is_verified_and_a_tampered_or_stale_one_is_not() {
        val node = TestSigner("node")
        val r = req()
        val up = SyncProtocol.Upload(node.idBytes.toHex(), node.pubBytes.toHex(), now, "z1:2",
            listOf(SyncProtocol.CoverageLine("wifi:0123456789abcdef", "WIFI", "z1:2", now, true, 0, "AUTHORIZED_PRIVATE", 7)),
            ProviderActivation.availability(elig(), "z1:2", Tunnel.UP_WIFI), listOf(r), listOf(Triple("j1", Jobs.State.ACCEPTED, now)))
        val msg = SyncProtocol.signed(up, node)
        assertTrue(SyncProtocol.verify(msg, now))
        assertTrue(SyncProtocol.verify(msg, now + SyncProtocol.CLOCK_SKEW_MS - 1))
        assertFalse("clock too far", SyncProtocol.verify(msg, now + SyncProtocol.CLOCK_SKEW_MS + 1))
        assertFalse("tampered body", SyncProtocol.verify(msg.replace("z1:2", "z1:3"), now))
        assertFalse("no signature", SyncProtocol.verify(SyncProtocol.body(up), now))
        // the identity line must match the key: a message claiming another node id is refused
        val other = TestSigner("other")
        assertFalse(SyncProtocol.verify(SyncProtocol.signed(SyncProtocol.Upload(other.idBytes.toHex(), node.pubBytes.toHex(), now, "z1:2", emptyList(), null, emptyList(), emptyList()), node), now))
        // the body carries no coordinates, only zones and keys
        val body = SyncProtocol.body(up)
        assertFalse(body.contains("lat")); assertTrue(body.contains("\tz1:2")); assertTrue(body.contains("wifi:0123456789abcdef"))
        assertFalse("never a BSSID", body.contains(":ee:"))
        assertTrue(body.startsWith("V\t1\nN\t" + node.idBytes.toHex()))
    }

    @Test
    fun the_download_never_turns_shared_history_into_green() {
        val r = req()
        val text = "V\t1\t" + now + "\nX\tz1:2\tGREEN\t2\t1\t0\t" + (now - 300_000) + "\t3\nX\tz9:9\tRED\t0\t0\t-1\t0\t0\n" +
            "R\t" + NetRequest.encodeLine(r) + "\nJ\tj1\tPROVIDER_ACTIVATION\tz1:2\t" + r.id + "\tOFFERED\t" + (now + 600_000) + "\n" +
            "Q\t" + r.id + "\tUPLOADED\t1\nA\tone provider in your zone\nX\tbroken line\n"
        val d = SyncProtocol.parseDownload(text)!!
        assertEquals(now, d.serverTime)
        assertEquals(2, d.cells.size)
        val c = d.cells.first { it.zone == "z1:2" }
        assertEquals("the brain's GREEN is at most YELLOW here", Coverage.ZoneStatus.YELLOW, c.status)
        assertEquals(0, c.direct); assertEquals(3, c.potential); assertEquals(3, c.observers)
        assertEquals(Coverage.ZoneStatus.RED, d.cells.first { it.zone == "z9:9" }.status)
        assertEquals(r, d.requests.single()); assertTrue(NetRequest.verify(d.requests.single()))
        assertEquals(Jobs.Type.PROVIDER_ACTIVATION, d.jobs.single().type)
        assertEquals(NetRequest.State.UPLOADED, d.statuses.single().state)
        assertEquals("one provider in your zone", d.advice.single())
        assertNull("wrong version", SyncProtocol.parseDownload("V\t2\t1\n"))
        assertNull("no version", SyncProtocol.parseDownload("A\thello\n"))
    }

    // ---- shared coverage honesty --------------------------------------------------------------------------------------

    @Test
    fun shared_coverage_dedups_by_key_and_local_reach_is_the_only_green() {
        val hashed = CoverageModel.sourceId(CoverageModel.SourceKind.WIFI, "AA:BB:CC:DD:EE:FF")
        val lines = SyncProtocol.coverageLines(listOf(
            CoverageModel.observe(emptyMap(), CoverageModel.Sighting(CoverageModel.SourceKind.WIFI, "aa:bb:cc:dd:ee:ff", "Freebox", -60, now, "z1:2", true, 0, Coverage.Trust.AUTHORIZED_PRIVATE, "WPA2", false)).first.values.first(),
            CoverageModel.observe(emptyMap(), CoverageModel.Sighting(CoverageModel.SourceKind.WIFI, "AA:BB:CC:DD:EE:FF", "Freebox", -70, now - 1000, "z1:2", null, -1, Coverage.Trust.UNKNOWN, "WPA2", false)).first.values.first(),
        ), now)
        assertEquals("two observers of one Freebox produce one key", 1, lines.map { it.key }.toSet().size)
        assertEquals(hashed, lines[0].key)
        // a source seen two days ago is not summarised as current
        val stale = CoverageModel.observe(emptyMap(), CoverageModel.Sighting(CoverageModel.SourceKind.PROKNET, "24e480e6", "prok-24e480e6", -60, now - 2 * CoverageModel.RECENT_MS, "z1:2", true, 500, Coverage.Trust.AUTHORIZED_PRIVATE, "", true)).first.values.first()
        assertTrue(SyncProtocol.coverageLines(listOf(stale), now).isEmpty())
        // the local reachable provider is GREEN; the same provider only known from the brain is YELLOW
        val p = CoverageModel.observe(emptyMap(), CoverageModel.Sighting(CoverageModel.SourceKind.PROKNET, "24e480e6", "prok-24e480e6", -60, now, "z1:2", true, 500, Coverage.Trust.AUTHORIZED_PRIVATE, "", true)).first.values.first()
        assertEquals(Coverage.ZoneStatus.GREEN, CoverageModel.cellStatus(listOf(p), now, setOf(p.id)))
        val shared = SyncProtocol.parseDownload("V\t1\t" + now + "\nX\tz1:2\tGREEN\t1\t0\t500\t" + (now - 60_000) + "\t9\n")!!.cells.single()
        assertEquals(Coverage.ZoneStatus.YELLOW, shared.status)
    }

    // ---- v0.13.2: could this phone become a seller RIGHT NOW? ------------------------------------------

    /** Eligibility as the phone builds it: the capability it has, not the gateway it has not started. */
    private fun capable(optIn: Boolean = true, type: Int = Tunnel.UP_WIFI, validated: Boolean = true, bulk: Boolean = true,
                        bt: Boolean = true, sharing: Boolean = false, busy: Boolean = false, price: Int = 500) =
        ProviderActivation.eligibility(optIn, type, validated, bulk, bt, sharing, busy, price)

    @Test
    fun the_oukitel_on_its_freebox_with_sharing_off_is_a_potential_provider() {
        // the exact hardware case: Freebox validated, Bluetooth on, bulk supported, opt-in on,
        // not sharing, not busy. v0.13.1 read the seller gateway here, which is not started while
        // sharing is off, and answered NO_INTERNET, so PARTAGER was never offered.
        val e = capable()
        assertTrue(e.upstreamValidated)
        assertEquals(BulkPlan.SellerAccessPath.BLUETOOTH_BULK, e.accessPath)
        assertFalse(e.alreadySharing)
        val r = req()
        assertNull(ProviderActivation.refusal(e, r, now))
        val o = ProviderActivation.opportunity(e, r, now, local = true)!!
        assertEquals("Quelqu'un cherche Internet à proximité.", o.title)

        // mobile data with sharing off is eligible too, through the proven hotspot path
        val m = capable(type = Tunnel.UP_CELLULAR)
        assertEquals(BulkPlan.SellerAccessPath.HOTSPOT, m.accessPath)
        assertNull(ProviderActivation.refusal(m, r, now))
        // a phone on mobile data does not need Bluetooth for the hotspot path
        assertNull(ProviderActivation.refusal(capable(type = Tunnel.UP_CELLULAR, bt = false), r, now))
    }

    @Test
    fun potential_path_reads_the_phone_capability_and_never_the_gateway() {
        // no Internet at all, or Internet that Android has not validated: NONE, and NO_INTERNET
        assertEquals(BulkPlan.SellerAccessPath.NONE, ProviderActivation.potentialPath(Tunnel.UP_NONE, false, true, true))
        assertEquals(BulkPlan.SellerAccessPath.NONE, ProviderActivation.potentialPath(Tunnel.UP_WIFI, false, true, true))
        assertEquals(ProviderActivation.Refusal.NO_INTERNET, ProviderActivation.refusal(capable(type = Tunnel.UP_NONE, validated = false), req(), now))
        assertEquals(ProviderActivation.Refusal.NO_INTERNET, ProviderActivation.refusal(capable(validated = false), req(), now))
        // validated Wi-Fi with Bluetooth off, or without L2CAP: no local path for a Wi-Fi seller
        assertEquals(BulkPlan.SellerAccessPath.NONE, ProviderActivation.potentialPath(Tunnel.UP_WIFI, true, bulkSupported = true, bluetoothOn = false))
        assertEquals(BulkPlan.SellerAccessPath.NONE, ProviderActivation.potentialPath(Tunnel.UP_WIFI, true, bulkSupported = false, bluetoothOn = true))
        assertEquals(ProviderActivation.Refusal.NO_LOCAL_PATH, ProviderActivation.refusal(capable(bt = false), req(), now))
        // validated Wi-Fi + Bluetooth: the proven path
        assertEquals(BulkPlan.SellerAccessPath.BLUETOOTH_BULK, ProviderActivation.potentialPath(Tunnel.UP_WIFI, true, true, true))
        // anything else validated falls to the hotspot path
        assertEquals(BulkPlan.SellerAccessPath.HOTSPOT, ProviderActivation.potentialPath(Tunnel.UP_OTHER, true, true, true))
    }

    @Test
    fun the_other_refusals_still_hold_when_the_phone_is_capable() {
        val r = req()
        assertEquals(ProviderActivation.Refusal.NOT_OPTED_IN, ProviderActivation.refusal(capable(optIn = false), r, now))
        assertEquals(ProviderActivation.Refusal.ALREADY_SHARING, ProviderActivation.refusal(capable(sharing = true), r, now))
        assertEquals(ProviderActivation.Refusal.BUSY, ProviderActivation.refusal(capable(busy = true), r, now))
        assertEquals(ProviderActivation.Refusal.REQUEST_NOT_OPEN, ProviderActivation.refusal(capable(), r, now + NetRequest.NOW_TTL_MS + 1))
        // the request's ceiling is still respected against the phone's own price
        val capped = NetRequest.sign(NetRequest.oneTap("4444444444444444", buyer.short, buyer.pubBytes.toHex(), now, "z1:2").copy(ceilingCentimesPerMb = 300), buyer)
        assertEquals(ProviderActivation.Refusal.ABOVE_CEILING, ProviderActivation.refusal(capable(price = 500), capped, now))
        assertNull(ProviderActivation.refusal(capable(price = 300), capped, now))
        // and a capable phone still heartbeats as a potential provider
        assertTrue(ProviderActivation.availability(capable(), "z1:2", Tunnel.UP_WIFI).potential)
        assertFalse(ProviderActivation.availability(capable(validated = false, type = Tunnel.UP_NONE), "z1:2", Tunnel.UP_NONE).potential)
    }
}
