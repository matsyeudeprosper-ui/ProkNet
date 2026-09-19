package net.prok.proknet.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v0.12: the one-tap decision, the coverage registry, the coverage cells, the
 * request state machine, the planner's economic rule and the persistence codec.
 */
class GetInternetTest {
    private val now = 1_700_000_000_000L

    private fun cand(id: String, price: Int, reachable: Boolean = true, validated: Boolean = true, rel: Double = 0.8, rssi: Int = -60,
                     lastSeen: Long = now, way: GetInternet.Way = GetInternet.Way.PROKNET_DIRECT, authorized: Boolean = true, setup: Int = 0) =
        GetInternet.Candidate(id, way, id, price, reachable, rssi, lastSeen, validated, rel, setup, 0, authorized, id.removePrefix("prok:"))

    @Test
    fun free_validated_reachable_beats_paid() {
        val d = GetInternet.decide(listOf(cand("prok:a", 500), cand("wifi:home", 0, way = GetInternet.Way.CONNECTED_WIFI)), now)
        assertEquals(GetInternet.Action.CONNECT_NOW, d.action)
        assertEquals("wifi:home", d.chosen!!.id)
        assertTrue(d.reason.contains("already has validated Internet"))
        // a free ProkNet provider too
        val d2 = GetInternet.decide(listOf(cand("prok:a", 500), cand("prok:free", 0)), now)
        assertEquals("prok:free", d2.chosen!!.id)
        assertEquals("free validated reachable source", d2.reason)
    }

    @Test
    fun an_open_ssid_merely_detected_is_not_free_internet() {
        // detected, not validated, not authorized: never chosen over a paid provider that works
        val open = cand("wifi:open", 0, reachable = true, validated = false, authorized = false, way = GetInternet.Way.CONNECTED_WIFI)
        val d = GetInternet.decide(listOf(open, cand("prok:a", 500)), now)
        assertEquals("prok:a", d.chosen!!.id)
        assertFalse(d.ranked.first { it.candidate.id == "wifi:open" }.usable)
    }

    @Test
    fun a_cheap_unreachable_or_stale_source_does_not_beat_a_reachable_one() {
        val d = GetInternet.decide(listOf(cand("prok:cheap", 200, reachable = false), cand("prok:here", 500)), now)
        assertEquals("prok:here", d.chosen!!.id)
        assertEquals("cheapest validated reachable source", d.reason)
        val stale = GetInternet.decide(listOf(cand("prok:old", 200, lastSeen = now - 5 * 60_000), cand("prok:here", 500)), now)
        assertEquals("prok:here", stale.chosen!!.id)
        assertTrue(stale.ranked.first { it.candidate.id == "prok:old" }.why.startsWith("stale"))
    }

    @Test
    fun a_materially_more_reliable_source_wins_over_a_slightly_cheaper_one() {
        val d = GetInternet.decide(listOf(cand("prok:flaky", 500, rel = 0.3), cand("prok:solid", 600, rel = 0.9)), now)
        assertEquals("prok:solid", d.chosen!!.id)
        assertTrue(d.reason.startsWith("more reliable than the cheaper prok:flaky"))
        // but not when both are reliable: the cheapest wins
        val d2 = GetInternet.decide(listOf(cand("prok:a", 500, rel = 0.8), cand("prok:b", 600, rel = 0.9)), now)
        assertEquals("prok:a", d2.chosen!!.id)
    }

    @Test
    fun the_last_successful_source_wins_ties_only() {
        val tie = GetInternet.decide(listOf(cand("prok:a", 500), cand("prok:b", 500)), now, lastSuccessfulId = "prok:b")
        assertEquals("prok:b", tie.chosen!!.id)
        assertTrue(tie.reason.contains("last one that worked"))
        // a free source now available is still preferred
        val free = GetInternet.decide(listOf(cand("prok:b", 500), cand("prok:free", 0)), now, lastSuccessfulId = "prok:b")
        assertEquals("prok:free", free.chosen!!.id)
    }

    @Test
    fun no_candidate_is_a_truthful_waiting_state_not_a_fake_relay() {
        val none = GetInternet.decide(emptyList(), now)
        assertEquals(GetInternet.Action.NONE, none.action)
        val unreachable = GetInternet.decide(listOf(cand("prok:a", 500, reachable = false)), now)
        assertEquals(GetInternet.Action.REQUEST_NETWORK, unreachable.action)
        assertNull(unreachable.chosen)
        val r = InternetRequest.apply(InternetRequest.oneTap("r1", now, "z1:2"), unreachable, now)
        assertEquals(InternetRequest.State.NETWORK_NEEDED, r.state)
        assertEquals("Aucun Internet disponible tout de suite.", InternetRequest.title(r.state))
        assertEquals("ProkNet continue de chercher autour de vous.", InternetRequest.hint(r.state))
        // a ceiling above which nothing is usable
        val ceiling = GetInternet.decide(listOf(cand("prok:a", 500)), now, ceilingCentimesPerMb = 300)
        assertEquals(GetInternet.Action.REQUEST_NETWORK, ceiling.action)
    }

    @Test
    fun one_tap_chooses_the_proven_bluetooth_provider_and_the_mobile_data_provider_when_that_is_what_is_there() {
        // the OUKITEL as the OnePlus sees it: selling, validated, on Wi-Fi, advertising Bluetooth
        val oukitel = Market.Offer("24e480e6", 5, Market.flags(sell = true, relay = false, validated = true, upstreamType = Tunnel.UP_WIFI, viaRelay = false, p2p = false, bulkBt = true), -58, now)
        val c = GetInternet.fromOffer(oukitel, reachableNow = true, linkUp = false, history = null, now = now)
        assertEquals(GetInternet.Way.PROKNET_DIRECT, c.way)
        val d = GetInternet.decide(listOf(c), now)
        assertEquals(GetInternet.Action.CONNECT_NOW, d.action)
        assertEquals("24e480e6", d.chosen!!.peerShort)
        // and the transport underneath is the proven rule, not the decision
        assertEquals(P2pAdmission.BuyPath.BLUETOOTH_BULK,
            P2pAdmission.buyPath(P2pPlan.Topology.SELLER_GROUP_OWNER, offerP2p = false, linkUp = false, viaRelay = false, offerBulkBt = oukitel.bulkBt, sellerOnWifi = oukitel.upstreamType == Tunnel.UP_WIFI, preferBluetooth = false))

        // a mobile-data provider: the hotspot path stays selected
        val mobile = Market.Offer("aa11bb22", 5, Market.flags(sell = true, relay = false, validated = true, upstreamType = Tunnel.UP_CELLULAR, viaRelay = false, p2p = false, bulkBt = false), -60, now)
        val m = GetInternet.fromOffer(mobile, reachableNow = true, linkUp = false, history = null, now = now)
        assertEquals(GetInternet.Way.MOBILE_DATA_PROVIDER, m.way)
        assertEquals(GetInternet.Action.CONNECT_NOW, GetInternet.decide(listOf(m), now).action)
        assertEquals(P2pAdmission.BuyPath.HOTSPOT,
            P2pAdmission.buyPath(P2pPlan.Topology.SELLER_GROUP_OWNER, offerP2p = false, linkUp = false, viaRelay = false, offerBulkBt = false, sellerOnWifi = false, preferBluetooth = false))

        // a provider that stopped selling is not reachable for Internet
        val stopped = Market.Offer("24e480e6", 5, Market.flags(sell = false, relay = false, validated = true, upstreamType = Tunnel.UP_WIFI, viaRelay = false, p2p = false, bulkBt = true), -58, now)
        assertFalse(GetInternet.fromOffer(stopped, true, false, null, now).reachableNow)
        // an existing authenticated link is reused, and preferred to a fresh setup at the same price
        val linked = GetInternet.fromOffer(oukitel, true, linkUp = true, history = null, now = now)
        assertEquals(GetInternet.Way.EXISTING_LINK, linked.way)
        assertEquals("an authenticated link to it already exists", GetInternet.decide(listOf(linked, m), now).reason)
    }

    // ---- the registry: one record per real source ----------------------------------------------------------

    private fun wifi(bssid: String, at: Long, zone: String = "z1:2", validated: Boolean? = null, trust: Coverage.Trust = Coverage.Trust.UNKNOWN, rssi: Int = -70) =
        CoverageModel.Sighting(CoverageModel.SourceKind.WIFI, bssid, "Freebox-1234", rssi, at, zone, validated, CoverageModel.PRICE_UNKNOWN, trust, "WPA2", false)
    private fun prok(short: String, at: Long, zone: String = "z1:2", selling: Boolean = true, validated: Boolean = true, price: Int = 500, rssi: Int = -60) =
        CoverageModel.Sighting(CoverageModel.SourceKind.PROKNET, short, "prok-" + short, rssi, at, zone, validated, price, Coverage.Trust.AUTHORIZED_PRIVATE, "", selling)

    @Test
    fun the_same_wifi_and_the_same_provider_stay_one_source() {
        var s = emptyMap<String, CoverageModel.Source>()
        for (i in 0 until 8) s = CoverageModel.observe(s, wifi("AA:BB:CC:DD:EE:FF", now + i * 1000, rssi = -80 + i)).first
        // the same BSSID in another case is the same network
        s = CoverageModel.observe(s, wifi("aa:bb:cc:dd:ee:ff", now + 9000, zone = "z1:3")).first
        assertEquals(1, s.size)
        val w = s.values.first()
        assertEquals(9, w.observations); assertEquals(-70, w.bestRssi); assertEquals(listOf("z1:2", "z1:3"), w.zones); assertEquals(now + 9000, w.lastSeen); assertEquals(now, w.firstSeen)
        assertFalse("the BSSID itself is never the id", w.id.contains("aa:bb", ignoreCase = true))
        assertTrue(w.id.startsWith("wifi:"))
        // the same ProkNet identity seen repeatedly is one source, and it remembers the price and the validation
        for (i in 0 until 5) s = CoverageModel.observe(s, prok("24e480e6", now + i * 1000)).first
        s = CoverageModel.observe(s, prok("24E480E6", now + 6000, selling = false)).first
        assertEquals(2, s.size)
        val p = s["prok:24e480e6"]!!
        assertEquals(6, p.observations); assertEquals(500, p.priceCentimesPerMb); assertTrue(p.validated); assertFalse(p.selling); assertEquals(6, p.validatedOk)
    }

    @Test
    fun a_detected_wifi_is_never_usable_and_a_stale_source_is_never_green() {
        val seen = CoverageModel.observe(emptyMap(), wifi("AA:BB:CC:DD:EE:FF", now, validated = true)).first
        val w = seen.values.first()
        assertFalse("detection is not authorization", CoverageModel.usableNow(w, reachableNow = true))
        // the provider this phone is using: usable now, GREEN now
        val p = CoverageModel.observe(emptyMap(), prok("24e480e6", now)).first.values.first()
        assertTrue(CoverageModel.usableNow(p, true))
        assertEquals(Coverage.ZoneStatus.GREEN, CoverageModel.cellStatus(listOf(p), now, setOf(p.id)))
        // the same provider, not reachable right now: YELLOW, not GREEN
        assertEquals(Coverage.ZoneStatus.YELLOW, CoverageModel.cellStatus(listOf(p), now, emptySet()))
        // seen 20 minutes ago, even if the flag says reachable: YELLOW
        assertEquals(Coverage.ZoneStatus.YELLOW, CoverageModel.cellStatus(listOf(p), now + 20 * 60_000, setOf(p.id)))
        // seen two days ago: RED
        assertEquals(Coverage.ZoneStatus.RED, CoverageModel.cellStatus(listOf(p), now + 2 * 24 * 3_600_000, setOf(p.id)))
        // nothing known: RED
        assertEquals(Coverage.ZoneStatus.RED, CoverageModel.cellStatus(emptyList(), now, emptySet()))
        // a validated, authorized Wi-Fi this phone may use, reachable now: GREEN
        val mine = CoverageModel.observe(emptyMap(), wifi("11:22:33:44:55:66", now, validated = true, trust = Coverage.Trust.AUTHORIZED_PRIVATE)).first.values.first()
        assertEquals(Coverage.ZoneStatus.GREEN, CoverageModel.cellStatus(listOf(mine), now, setOf(mine.id)))
    }

    @Test
    fun cells_count_real_sources_and_words_hide_zone_colours() {
        var s = emptyMap<String, CoverageModel.Source>()
        for (i in 0 until 8) s = CoverageModel.observe(s, wifi("AA:BB:CC:DD:EE:FF", now - i * 1000)).first
        s = CoverageModel.observe(s, prok("24e480e6", now)).first
        s = CoverageModel.observe(s, prok("deadbeef", now - 3 * 3_600_000, zone = "z9:9", price = 300)).first
        val cells = CoverageModel.cells(s.values, now, setOf("prok:24e480e6"))
        assertEquals(2, cells.size)
        val here = cells.first { it.zoneId == "z1:2" }
        assertEquals(Coverage.ZoneStatus.GREEN, here.status)
        assertEquals("one Freebox seen 8 times is not 8 sources", 1, here.directSourceCount)
        assertEquals(500, here.bestKnownPrice)
        val there = cells.first { it.zoneId == "z9:9" }
        assertEquals(Coverage.ZoneStatus.YELLOW, there.status)
        assertEquals(0, there.directSourceCount); assertEquals(1, there.potentialSourceCount); assertEquals(300, there.bestKnownPrice)
        assertTrue(there.confidence < here.confidence)
        assertEquals(Coverage.ZoneStatus.GREEN, CoverageModel.hereStatus(cells, "z1:2"))
        assertEquals(Coverage.ZoneStatus.RED, CoverageModel.hereStatus(cells, "z5:5"))
        for (st in Coverage.ZoneStatus.values()) {
            val w = CoverageModel.cellWord(st)
            assertFalse(w.contains("GREEN") || w.contains("YELLOW") || w.contains("RED"))
        }
        assertEquals("Internet disponible", CoverageModel.cellWord(Coverage.ZoneStatus.GREEN))
        assertEquals("il y a 8 min", CoverageModel.ageWord(8 * 60_000))
        assertEquals("gratuit", CoverageModel.priceWord(0)); assertEquals("5 CFA par Mo", CoverageModel.priceWord(500)); assertEquals("inconnu", CoverageModel.priceWord(-1))
    }

    @Test
    fun zones_are_coarse_cells_never_points() {
        val z = CoverageModel.zoneId(-4.3217, 15.3125)
        assertEquals(z, CoverageModel.zoneId(-4.3219, 15.3127))       // 20 m apart: same cell
        assertNotEquals(z, CoverageModel.zoneId(-4.33, 15.3125))     // ~1 km: another cell
        val c = CoverageModel.zoneCenter(z)!!
        assertTrue(Math.abs(c.first - -4.3217) < CoverageModel.CELL_DEG && Math.abs(c.second - 15.3125) < CoverageModel.CELL_DEG)
        assertNull(CoverageModel.zoneCenter(CoverageModel.NO_ZONE))
        val idx = CoverageModel.zoneIndex(z)!!
        assertEquals(z, CoverageModel.zoneAt(idx.first, idx.second))
    }

    // ---- the request and the planner ---------------------------------------------------------------------------

    @Test
    fun the_one_tap_request_asks_nothing_and_walks_to_online() {
        val r = InternetRequest.oneTap("r1", now, "z1:2")
        assertEquals(InternetRequest.FLEXIBLE, r.desiredMb); assertEquals(InternetRequest.FLEXIBLE, r.desiredMinutes)
        assertEquals(InternetRequest.PRICE_AUTOMATIC, r.maxPriceCentimesPerMb); assertNull(r.ceiling)
        assertEquals(InternetRequest.Urgency.NOW, r.urgency); assertEquals(InternetRequest.State.SEARCHING, r.state)
        val d = GetInternet.decide(listOf(cand("prok:a", 500)), now)
        val found = InternetRequest.apply(r, d, now + 1)
        assertEquals(InternetRequest.State.DIRECT_SOURCE_FOUND, found.state); assertEquals("prok:a", found.sourceId)
        val on = InternetRequest.online(InternetRequest.connecting(found, now + 2), now + 3)
        assertEquals(InternetRequest.State.ONLINE, on.state); assertTrue(on.terminal)
        assertEquals("Internet connecté ✅", InternetRequest.title(on.state))
        assertEquals("Recherche d'Internet…", InternetRequest.title(InternetRequest.State.SEARCHING))
        assertEquals("Recherche du meilleur Internet…", InternetRequest.title(InternetRequest.State.SEARCHING, longSearch = true))
        assertTrue(InternetRequest.failed(found, "x", now).terminal); assertTrue(InternetRequest.cancelled(found, now).terminal)
        assertEquals(InternetRequest.State.SEARCHING, InternetRequest.searching(InternetRequest.apply(r, GetInternet.decide(emptyList(), now), now), now).state)
    }

    @Test
    fun the_commercial_planner_refuses_to_spend_300_to_deliver_50() {
        val r = InternetRequest.oneTap("r1", now, "z1:2")
        val c = cand("prok:a", 500, setup = 25_000)        // 5 CFA/MB plus a 250 CFA setup
        val p = InternetRequest.directPlan(r, c, expectedMb = 10)
        assertEquals(listOf(InternetRequest.HopRole.PROVIDER), p.hops.map { it.role })
        assertFalse(p.movementRequired)
        assertEquals(30_000L, p.deliveryCostCentimes)
        val (ok, why) = InternetRequest.admissible(p, customerCeilingCentimes = 5_000)
        assertFalse(why, ok)
        assertTrue(InternetRequest.admissible(InternetRequest.directPlan(r, cand("prok:b", 500), 10), 5_000).first)
        // sponsored or growth: the subsidy may be explicit
        assertTrue(InternetRequest.admissible(p.copy(costClass = Coverage.Kind.SPONSORED), 5_000, budgetCentimes = 30_000).first)
        assertFalse(InternetRequest.admissible(p.copy(costClass = Coverage.Kind.SPONSORED), 5_000, budgetCentimes = 10_000).first)
        assertTrue(InternetRequest.admissible(p.copy(costClass = Coverage.Kind.GROWTH_SUBSIDY), 5_000, budgetCentimes = 25_000).first)
        assertFalse(InternetRequest.admissible(p.copy(costClass = Coverage.Kind.GROWTH_SUBSIDY), 5_000, budgetCentimes = 20_000).first)
        // movement is the last resort
        val moving = p.copy(movementRequired = true, deliveryCostCentimes = 100)
        assertEquals(p, InternetRequest.rank(listOf(moving, p)).first())
    }

    // ---- persistence ---------------------------------------------------------------------------------------------

    @Test
    fun coverage_and_requests_survive_a_reload() {
        var s = emptyMap<String, CoverageModel.Source>()
        val obs = ArrayList<CoverageModel.Observation>()
        for (i in 0 until 3) { val (m, o) = CoverageModel.observe(s, wifi("AA:BB:CC:DD:EE:FF", now + i, validated = if (i == 1) true else null)); s = m; obs += o }
        val (m2, o2) = CoverageModel.observe(s, CoverageModel.Sighting(CoverageModel.SourceKind.PROKNET, "24e480e6", "Mike\tphone\n2", -55, now, "z1:2", true, 500, Coverage.Trust.AUTHORIZED_PRIVATE, "", true)); s = m2; obs += o2
        s = CoverageModel.withSuccess(s, "prok:24e480e6")
        val r = InternetRequest.online(InternetRequest.oneTap("r1", now, "z1:2").copy(note = "tab\there"), now)
        val st = CoverageModel.State(s, obs, listOf(r), "prok:24e480e6")
        val back = CoverageModel.decode(CoverageModel.encode(st))
        assertEquals(st.sources, back.sources)
        assertEquals(st.observations, back.observations)
        assertEquals(st.requests, back.requests)
        assertEquals("prok:24e480e6", back.lastSuccessfulSourceId)
        assertEquals(1, back.sources["prok:24e480e6"]!!.successes)
        assertEquals("Mike\tphone\n2", back.sources["prok:24e480e6"]!!.name)
        // a damaged line loses only itself
        val damaged = CoverageModel.encode(st) + "S\tbroken\n"
        assertEquals(2, CoverageModel.decode(damaged).sources.size)
        assertEquals(CoverageModel.EMPTY, CoverageModel.decode(""))
    }
}
