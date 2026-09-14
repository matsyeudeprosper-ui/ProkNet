package net.prok.proknet.core

import net.prok.proknet.core.Coverage.CandidateLink
import net.prok.proknet.core.Coverage.CoverageNode
import net.prok.proknet.core.Coverage.DemandRequest
import net.prok.proknet.core.Coverage.InternetSource
import net.prok.proknet.core.Coverage.Kind
import net.prok.proknet.core.Coverage.SourceType
import net.prok.proknet.core.Coverage.Trust
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/** Synthetic zones: the planner's decisions, the economic ceiling and determinism. */
class CoverageTest {
    private val mobile = InternetSource("mtn", SourceType.MOBILE_DATA, Trust.AUTHORIZED_PRIVATE, 500, 0.95)   // 5 CFA / MB
    private val freeWifi = InternetSource("cafe", SourceType.PUBLIC_WIFI, Trust.OPEN_REUSABLE, 0, 0.9)
    private val unknownWifi = InternetSource("somebody", SourceType.PUBLIC_WIFI, Trust.UNKNOWN, 0, 0.9)
    private val demand = DemandRequest("A", "Z", 10, 5, Kind.COMMERCIAL)   // buyer pays at most 50 CFA

    private fun buyer() = CoverageNode("A", "Z")
    private fun provider(id: String, src: InternetSource, rel: Double = 0.95, reward: Long = 0) = CoverageNode(id, "Z", hasInternet = true, source = src, canProvide = true, reliability = rel, minimumRewardCentimes = reward)
    private fun relay(id: String, rel: Double = 0.9, reward: Long = 0, battery: Int = 100, charging: Boolean = false) = CoverageNode(id, "Z", canRelay = true, reliability = rel, minimumRewardCentimes = reward, battery = battery, charging = charging)
    private fun mover(id: String, cost: Long, heading: String? = null) = CoverageNode(id, "Z", canRelay = true, canMove = true, moveCostCentimes = cost, headingZone = heading)
    private fun link(a: String, b: String, q: Double = 0.9) = CandidateLink(a, b, q)

    @Test
    fun free_nearby_wifi_beats_a_paid_multi_relay_path() {
        val nodes = listOf(buyer(), provider("F", freeWifi, 0.9), relay("R1"), relay("R2"), provider("M", mobile))
        val links = listOf(link("A", "F"), link("A", "R1"), link("R1", "R2"), link("R2", "M"))
        val ranked = Coverage.plan(demand, nodes, links)
        assertTrue(ranked.size >= 2)
        assertEquals(listOf("A", "F"), ranked[0].route.hops)
        assertTrue(ranked[0].score.feasible)
        assertEquals(0L, ranked[0].score.deliveredCost)
        assertTrue(ranked[0].score.total < ranked[1].score.total)
    }

    @Test
    fun one_reliable_direct_mobile_provider_beats_a_cheap_fragile_three_relay_chain() {
        val nodes = listOf(buyer(), provider("M", mobile), relay("R1", 0.8), relay("R2", 0.8), relay("R3", 0.8), provider("F", freeWifi, 0.9))
        val links = listOf(link("A", "M"), link("A", "R1", 0.5), link("R1", "R2", 0.5), link("R2", "R3", 0.5), link("R3", "F", 0.5))
        val ranked = Coverage.plan(demand, nodes, links)
        assertEquals(listOf("A", "M"), ranked[0].route.hops)
        val chain = ranked.first { it.route.hops.size == 5 }
        assertTrue("direct delivered cost is higher (" + ranked[0].score.deliveredCost + " > " + chain.score.deliveredCost + ") but it still wins", ranked[0].score.deliveredCost > chain.score.deliveredCost)
        assertTrue(chain.score.pFail > 0.9)
        assertTrue(ranked[0].score.total < chain.score.total)
    }

    @Test
    fun a_passive_relay_already_in_position_beats_paying_a_mover() {
        val nodes = listOf(buyer(), relay("R"), mover("M", 1000), provider("P", mobile))
        val links = listOf(link("A", "R"), link("R", "P"))
        val ranked = Coverage.plan(demand, nodes, links)
        assertEquals(listOf("A", "R", "P"), ranked[0].route.hops)
        assertFalse(ranked[0].route.moves)
        val viaMover = ranked.first { it.route.moves }
        assertEquals(listOf("A", "M", "P"), viaMover.route.hops)
        assertEquals(1000L, viaMover.score.movementCost)
        assertTrue(ranked[0].score.total < viaMover.score.total)
    }

    @Test
    fun a_mover_is_selected_when_it_is_the_only_way_and_economics_permit() {
        val nodes = listOf(buyer(), mover("M", 1000), provider("P", mobile))
        val ranked = Coverage.plan(demand, nodes, emptyList())
        assertEquals(1, ranked.size)
        assertTrue(ranked[0].route.moves)
        assertTrue(ranked[0].score.feasible)
        assertEquals(1100L, ranked[0].score.jobCost)      // relay 100 + move 1000 + provide 0 <= 5000 revenue

        // same gap, mover too expensive for a commercial session
        val pricey = Coverage.plan(demand, listOf(buyer(), mover("M", 6000), provider("P", mobile)), emptyList())
        assertFalse(pricey[0].score.feasible)
        assertTrue(pricey[0].score.reason, pricey[0].score.reason.startsWith("exceeds commercial ceiling"))

        // a courier already heading to the zone pays half the movement
        val courier = Coverage.plan(demand, listOf(buyer(), mover("M", 1000, heading = "Z"), provider("P", mobile)), emptyList())
        assertEquals(500L, courier[0].score.movementCost)
    }

    @Test
    fun commercial_rejects_a_route_over_its_ceiling_and_growth_subsidy_may_approve_it() {
        val nodes = listOf(buyer(), relay("R1", reward = 2000), relay("R2", reward = 2000), provider("P", mobile, reward = 2000))
        val links = listOf(link("A", "R1"), link("R1", "R2"), link("R2", "P"))
        val commercial = Coverage.plan(demand, nodes, links)
        assertEquals(1, commercial.size)
        assertEquals(6000L, commercial[0].score.jobCost)     // 60 CFA of jobs for a 50 CFA session
        assertFalse(commercial[0].score.feasible)
        assertTrue(commercial[0].score.reason.startsWith("exceeds commercial ceiling"))

        val growth = Coverage.plan(DemandRequest("A", "Z", 10, 5, Kind.GROWTH_SUBSIDY, budgetCentimes = 1500), nodes, links)
        assertTrue(growth[0].score.feasible)
        assertEquals(1000L, growth[0].score.subsidyNeeded)   // explicit loss, never silent

        val tooSmall = Coverage.plan(DemandRequest("A", "Z", 10, 5, Kind.GROWTH_SUBSIDY, budgetCentimes = 500), nodes, links)
        assertFalse(tooSmall[0].score.feasible)
        assertTrue(tooSmall[0].score.reason.startsWith("exceeds growth subsidy"))

        val sponsored = Coverage.plan(DemandRequest("A", "Z", 10, 5, Kind.SPONSORED, budgetCentimes = 6000), nodes, links)
        assertTrue(sponsored[0].score.feasible)
        assertEquals(6000L, sponsored[0].score.subsidyNeeded)
    }

    @Test
    fun unknown_wifi_is_never_treated_as_redistributable() {
        assertFalse(Coverage.redistributable(Trust.UNKNOWN))
        assertFalse(Coverage.redistributable(Trust.CAPTIVE_PORTAL))
        assertFalse(Coverage.redistributable(Trust.NOT_ALLOWED))
        assertTrue(Coverage.redistributable(Trust.OPEN_REUSABLE))
        assertTrue(Coverage.redistributable(Trust.AUTHORIZED_PRIVATE))
        assertFalse(unknownWifi.usable)
        // a provider on an unknown network is not even a candidate
        val ranked = Coverage.plan(demand, listOf(buyer(), provider("X", unknownWifi)), listOf(link("A", "X")))
        assertTrue(ranked.isEmpty())
        // and a hand-built route on it is rejected with the reason
        val r = Coverage.CandidateRoute(listOf("A", "X"), unknownWifi, listOf(Coverage.CoverageJob("X", Coverage.JobKind.PROVIDE, 0)))
        val s = Coverage.score(r, demand, listOf(buyer(), provider("X", unknownWifi)).associateBy { it.nodeId }, listOf(link("A", "X")), Coverage.Policy())
        assertFalse(s.feasible); assertTrue(s.reason, s.reason.contains("not redistributable"))
    }

    @Test
    fun an_unvalidated_source_is_never_planned_on() {
        val dead = InternetSource("open-but-dead", SourceType.PUBLIC_WIFI, Trust.OPEN_REUSABLE, 0, 0.9, validated = false)
        assertFalse(dead.usable)
        assertTrue(freeWifi.usable)
        assertTrue(Coverage.plan(demand, listOf(buyer(), provider("D", dead)), listOf(link("A", "D"))).isEmpty())
        // it does not hide a real one either: the validated provider is still found
        val ranked = Coverage.plan(demand, listOf(buyer(), provider("D", dead), provider("F", freeWifi, 0.9)), listOf(link("A", "D"), link("A", "F")))
        assertEquals(1, ranked.size); assertEquals(listOf("A", "F"), ranked[0].route.hops)
    }

    @Test
    fun ranking_is_deterministic_for_any_input_order() {
        val nodes = listOf(buyer(), provider("F", freeWifi, 0.9), relay("R1"), relay("R2", charging = true), relay("R3", battery = 10), provider("M", mobile), mover("V", 800), provider("Q", mobile, 0.8))
        val links = listOf(link("A", "F", 0.6), link("A", "R1"), link("R1", "R2"), link("R2", "M"), link("A", "R3", 0.8), link("R3", "Q"), link("R1", "Q", 0.7))
        val reference = Coverage.plan(demand, nodes, links).map { it.route.id + "=" + it.score.total }
        assertTrue(reference.size >= 5)
        for (seed in 1..6) {
            val rnd = Random(seed.toLong())
            val n2 = nodes.shuffled(rnd); val l2 = links.shuffled(rnd).map { if (rnd.nextBoolean()) CandidateLink(it.b, it.a, it.quality) else it }
            assertEquals("seed " + seed, reference, Coverage.plan(demand, n2, l2).map { it.route.id + "=" + it.score.total })
        }
    }

    @Test
    fun zones_are_green_yellow_or_red() {
        val green = Coverage.plan(demand, listOf(buyer(), provider("F", freeWifi, 0.9)), listOf(link("A", "F")))
        assertEquals(Coverage.ZoneStatus.GREEN, Coverage.zoneStatus(green))
        val moverOnly = Coverage.plan(demand, listOf(buyer(), mover("M", 1000), provider("P", mobile)), emptyList())
        assertEquals(Coverage.ZoneStatus.YELLOW, Coverage.zoneStatus(moverOnly))
        val fundable = CoverageNode("S", "Z", hasInternet = false, source = InternetSource("shop", SourceType.FUNDED, Trust.AUTHORIZED_PRIVATE, 300, 0.9), canProvide = true, fundable = true, activationCostCentimes = 1000)
        val activate = Coverage.plan(demand, listOf(buyer(), fundable), listOf(link("A", "S")))
        assertTrue(activate[0].route.activates)
        assertEquals(Coverage.ZoneStatus.YELLOW, Coverage.zoneStatus(activate))
        assertEquals(Coverage.ZoneStatus.RED, Coverage.zoneStatus(Coverage.plan(demand, listOf(buyer()), emptyList())))
        val z = Coverage.zone("z1", "Market", green)
        assertEquals(Coverage.ZoneStatus.GREEN, z.status); assertNotNull(z.bestRouteId)
    }

    @Test
    fun observations_become_links_and_capabilities_become_security_words() {
        val obs = listOf(
            Coverage.RadioObservation("A", "B", Coverage.LinkKind.BLE, -50, 1000, 1.0),
            Coverage.RadioObservation("B", "A", Coverage.LinkKind.BLE, -90, 1000, 1.0),          // weaker, same pair: best wins
            Coverage.RadioObservation("A", "C", Coverage.LinkKind.WIFI, -50, 20 * 60_000L, 1.0), // stale: quality 0
        )
        val links = Coverage.linksFrom(obs)
        assertEquals(2, links.size)
        val ab = links.first { it.touches("B") }; val ac = links.first { it.touches("C") }
        assertTrue(ab.quality > 0.8); assertEquals(0.0, ac.quality, 1e-9)
        assertEquals("WPA2", Coverage.securityOf("[WPA2-PSK-CCMP][ESS]")); assertEquals("WPA3", Coverage.securityOf("[RSN-SAE-CCMP][ESS]"))
        assertEquals("open", Coverage.securityOf("[ESS]")); assertEquals("WEP", Coverage.securityOf("[WEP][ESS]"))
    }
}
