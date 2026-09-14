package net.prok.proknet.core

/**
 * Coverage engine, pure part (v0.9 foundation). No Android, no networking.
 *
 * Long-term model (locked):
 *
 *   INTERNET SOURCES  (mobile / home Wi-Fi / shops / public / funded)
 *          |
 *   COVERAGE ENGINE   (this file: sources, nodes, links, demand -> ranked routes, zones)
 *          |
 *   direct provider / passive relay / funded provider / mover
 *          |
 *        BUYER
 *
 * Buyer priority: FREE -> CHEAPEST -> RELIABLE BACKUP. The planner prefers the
 * cheapest reliable delivered route, never the most complicated one. All
 * weights live in [Policy]; nothing else hard-codes a formula. Money is in
 * centimes (Long), probabilities are Double in 0..1.
 *
 * v0.9 status: IMPLEMENTED and tested on synthetic zones. Not yet fed by real
 * phones; the Android side only records Wi-Fi observations (Relay Lab).
 */
object Coverage {

    // ---- Internet sources ------------------------------------------------------------------------

    enum class SourceType { MOBILE_DATA, HOME_WIFI, SHOP_WIFI, PUBLIC_WIFI, FUNDED, SPONSORED }

    /** Permission / trust class of a source. Seeing a network never means Prok may redistribute it. */
    enum class Trust { OPEN_REUSABLE, AUTHORIZED_PRIVATE, CAPTIVE_PORTAL, UNKNOWN, NOT_ALLOWED }

    /** Only explicitly open-reusable or explicitly authorized sources may be shared through Prok. */
    fun redistributable(t: Trust): Boolean = t == Trust.OPEN_REUSABLE || t == Trust.AUTHORIZED_PRIVATE

    fun trustWord(t: Trust): String = when (t) {
        Trust.OPEN_REUSABLE -> "public / open"; Trust.AUTHORIZED_PRIVATE -> "authorized"; Trust.CAPTIVE_PORTAL -> "captive portal"
        Trust.UNKNOWN -> "unknown"; Trust.NOT_ALLOWED -> "do not use"
    }

    /** Wi-Fi security from an Android ScanResult.capabilities string. */
    fun securityOf(capabilities: String): String = when {
        capabilities.contains("SAE") -> "WPA3"
        capabilities.contains("WPA2") || capabilities.contains("RSN") -> "WPA2"
        capabilities.contains("WPA") -> "WPA"
        capabilities.contains("WEP") -> "WEP"
        capabilities.contains("OWE") -> "open (OWE)"
        else -> "open"
    }

    class InternetSource(
        val id: String, val type: SourceType, val trust: Trust,
        /** What delivering one MB through this source costs the network, in centimes (0 = free). */
        val costPerMbCentimes: Long,
        val reliability: Double,
        val validated: Boolean = true,
        /** v0.9.6: the Wi-Fi channel it was observed on, 0 when unknown. Kept so failures can be correlated. */
        val frequencyMhz: Int = 0,
    ) {
        /** v0.9.1: an unvalidated source (captive portal behind an open SSID, dead uplink) is never planned on. */
        val usable: Boolean get() = redistributable(trust) && validated && reliability > 0.0
        /** v0.9.6: reselling THIS needs the provider phone to hold a Wi-Fi connection and a hotspot at once. */
        val wifiBased: Boolean get() = type == SourceType.HOME_WIFI || type == SourceType.SHOP_WIFI || type == SourceType.PUBLIC_WIFI
        val band: String get() = ShareCheck.band(frequencyMhz)
        fun describe(): String = id + " (" + type + ", " + trustWord(trust) + (if (wifiBased && frequencyMhz > 0) ", " + ShareCheck.describe(frequencyMhz) else "") + ")"
    }

    // ---- nodes and radio --------------------------------------------------------------------------

    enum class LinkKind { BLE, WIFI }

    class RadioObservation(val from: String, val to: String, val kind: LinkKind, val rssi: Int, val ageMs: Long, val successRate: Double)

    /** One phone (or fixed device) as the engine sees it. Raw fields never reach the consumer UI. */
    class CoverageNode(
        val nodeId: String,
        val zone: String,
        val hasInternet: Boolean = false,
        val source: InternetSource? = null,
        val canProvide: Boolean = false,
        val canRelay: Boolean = false,
        val canMove: Boolean = false,
        val stationary: Boolean = false,
        val charging: Boolean = false,
        val battery: Int = 100,
        val reliability: Double = 0.9,
        val expectedAvailabilityMs: Long = 3_600_000L,
        val minimumRewardCentimes: Long = 0L,
        val moveCostCentimes: Long = 0L,
        /** Zone this node is already travelling to (a COURIER), if known. */
        val headingZone: String? = null,
        /** Could be turned into a provider for [activationCostCentimes] (a FUNDED source). */
        val fundable: Boolean = false,
        val activationCostCentimes: Long = 0L,
        /**
         * v0.9.6: can this phone serve a customer while it stays on its own Wi-Fi network?
         * Measured by ShareCheck / HotspotProbe. False ONLY when it was tested and refused;
         * it never removes the source from the map, it removes this phone as its deliverer.
         */
        val canShareWhileOnWifi: Boolean = true,
    )

    /**
     * v0.9.6: can [n] actually hand its source to a customer today? A phone that
     * cannot run a hotspot while joined to its Wi-Fi network cannot resell that
     * network, although it can still sell mobile data.
     */
    fun canDeliver(n: CoverageNode): Boolean {
        val s = n.source ?: return false
        if (!n.canProvide || !s.usable) return false
        return !s.wifiBased || n.canShareWhileOnWifi
    }

    /**
     * Every source seen around, deliverable or not. The connectivity map keeps a
     * Wi-Fi network even when the phone that sees it cannot resell it: another,
     * capable phone may deliver it later.
     */
    fun observedSources(nodes: List<CoverageNode>): List<InternetSource> =
        nodes.mapNotNull { it.source }.distinctBy { it.id }.sortedBy { it.id }

    /** The subset at least one node can hand to a customer right now. */
    fun deliverableSources(nodes: List<CoverageNode>): List<InternetSource> =
        nodes.filter { canDeliver(it) && it.hasInternet }.mapNotNull { it.source }.distinctBy { it.id }.sortedBy { it.id }

    /** Sources on the map that nobody present can deliver today, with the reason. */
    fun blockedSources(nodes: List<CoverageNode>): List<Pair<InternetSource, String>> {
        val ok = deliverableSources(nodes).map { it.id }.toSet()
        return observedSources(nodes).filter { it.id !in ok }.map { s ->
            val why = when {
                !redistributable(s.trust) -> "not redistributable (" + trustWord(s.trust) + ")"
                !s.validated -> "not validated"
                s.wifiBased && nodes.any { it.source?.id == s.id && it.canProvide && !it.canShareWhileOnWifi } ->
                    "no phone here can resell this Wi-Fi network (tested: the hotspot is refused while joined to it)"
                else -> "no provider available"
            }
            s to why
        }
    }

    /** Undirected radio link with a quality in 0..1 (probability it carries a session). */
    class CandidateLink(val a: String, val b: String, val quality: Double) {
        fun touches(n: String) = a == n || b == n
        fun other(n: String) = if (a == n) b else a
    }

    /** Observations -> links. Quality = success rate x signal factor x freshness. Deterministic. */
    fun linksFrom(obs: List<RadioObservation>, policy: Policy = Policy()): List<CandidateLink> {
        val best = HashMap<String, CandidateLink>()
        for (o in obs) {
            val signal = ((o.rssi.coerceIn(-100, -40) + 100) / 60.0)           // -100 -> 0, -40 -> 1
            val fresh = if (o.ageMs <= policy.freshMs) 1.0 else if (o.ageMs >= policy.staleMs) 0.0 else 1.0 - (o.ageMs - policy.freshMs).toDouble() / (policy.staleMs - policy.freshMs)
            val q = (o.successRate.coerceIn(0.0, 1.0) * (0.5 + 0.5 * signal) * fresh).coerceIn(0.0, 1.0)
            val key = if (o.from < o.to) o.from + "|" + o.to else o.to + "|" + o.from
            val cur = best[key]
            if (cur == null || q > cur.quality) best[key] = CandidateLink(minOf(o.from, o.to), maxOf(o.from, o.to), q)
        }
        return best.values.sortedBy { it.a + "|" + it.b }
    }

    // ---- demand and economics ---------------------------------------------------------------------

    enum class Kind { COMMERCIAL, SPONSORED, GROWTH_SUBSIDY }

    class DemandRequest(
        val buyer: String, val zone: String, val expectedMb: Int, val maxPricePerMb: Int, val kind: Kind,
        /** SPONSORED: what the sponsor pays at most. GROWTH_SUBSIDY: how much loss Prok accepts. */
        val budgetCentimes: Long = 0L,
    ) {
        /** What the buyer pays at most for this session, in centimes. */
        val revenueCentimes: Long get() = expectedMb.toLong() * maxPricePerMb.toLong() * 100L
    }

    enum class JobKind { PROVIDE, RELAY, MOVE, ACTIVATE }
    class CoverageJob(val nodeId: String, val kind: JobKind, val rewardCentimes: Long)

    class CandidateRoute(val hops: List<String>, val source: InternetSource, val jobs: List<CoverageJob>) {
        val id: String get() = hops.joinToString(">") + "@" + source.id + (if (moves) "+move" else "") + (if (activates) "+activate" else "")
        val relayCount: Int get() = maxOf(0, hops.size - 2)
        val moves: Boolean get() = jobs.any { it.kind == JobKind.MOVE }
        val activates: Boolean get() = jobs.any { it.kind == JobKind.ACTIVATE }
        val provider: String get() = hops.last()
        val jobCost: Long get() = jobs.sumOf { it.rewardCentimes }
    }

    class RouteScore(
        val deliveredCost: Long, val failurePenalty: Long, val delayPenalty: Long, val movementCost: Long, val resourcePenalty: Long,
        val pFail: Double, val revenue: Long, val jobCost: Long, val subsidyNeeded: Long, val feasible: Boolean, val reason: String,
    ) {
        val total: Long get() = deliveredCost + failurePenalty + delayPenalty + movementCost + resourcePenalty
    }

    class Ranked(val route: CandidateRoute, val score: RouteScore)

    /** Every weight of the planner. Tune here, nowhere else. */
    class Policy(
        val maxRelays: Int = 3,
        val perHopDelayCentimes: Long = 200L,
        val moveDelayCentimes: Long = 2000L,
        val activateDelayCentimes: Long = 1500L,
        /** failurePenalty = pFail x (revenue + this). */
        val failureBaseCentimes: Long = 5000L,
        val lowBatteryPct: Int = 30,
        val lowBatteryPenalty: Long = 500L,
        val chargingBonus: Long = 200L,
        val stationaryBonus: Long = 200L,
        /** A relay already linked on both sides is worth this much more than one that must move. */
        val inPositionBonus: Long = 300L,
        /** A mover already heading to the demand zone (COURIER) pays this share of its movement cost. */
        val courierCostPct: Int = 50,
        /** Quality assumed for a link a mover will create by moving. */
        val moveLinkQuality: Double = 0.7,
        /** COMMERCIAL routes may cost up to revenue x (100 + this) / 100 in jobs. */
        val commercialMarginPct: Int = 0,
        val greenMaxFail: Double = 0.35,
        val yellowMaxFail: Double = 0.75,
        val freshMs: Long = 60_000L,
        val staleMs: Long = 10 * 60_000L,
        val defaultRelayReward: Long = 100L,
    )

    // ---- planner ----------------------------------------------------------------------------------

    /**
     * Ranked candidate solutions for [demand]: feasible routes first, cheapest
     * total first, then fewer relays, then route id. Deterministic for equal
     * inputs in any order.
     */
    fun plan(demand: DemandRequest, nodes: List<CoverageNode>, links: List<CandidateLink>, policy: Policy = Policy()): List<Ranked> {
        val byId = nodes.associateBy { it.nodeId }
        val buyer = byId[demand.buyer] ?: return emptyList()
        val adj = HashMap<String, MutableList<CandidateLink>>()
        for (l in links) { adj.getOrPut(l.a) { ArrayList() }.add(l); adj.getOrPut(l.b) { ArrayList() }.add(l) }
        val routes = ArrayList<CandidateRoute>()

        // 1. paths through nodes already in position (direct or passive relays)
        fun dfs(path: List<String>, quality: List<Double>) {
            val last = path.last()
            if (path.size - 1 > policy.maxRelays + 1) return
            for (l in adj[last].orEmpty().sortedBy { it.other(last) }) {
                val next = l.other(last)
                if (next in path) continue
                val n = byId[next] ?: continue
                val newPath = path + next
                if (canDeliver(n) && n.hasInternet) routes.add(routeFor(newPath, n, n.source!!, policy, moveNode = null, activate = false, byId = byId))
                if (canDeliver(n) && !n.hasInternet && n.fundable) routes.add(routeFor(newPath, n, n.source!!, policy, moveNode = null, activate = true, byId = byId))
                if (n.canRelay && newPath.size - 1 <= policy.maxRelays) dfs(newPath, quality + l.quality)
            }
        }
        dfs(listOf(buyer.nodeId), emptyList())

        // 2. a mover that closes a gap: buyer -> mover -> provider where at least one of the two links is missing
        val providers = nodes.filter { it.nodeId != buyer.nodeId && canDeliver(it) && (it.hasInternet || it.fundable) }.sortedBy { it.nodeId }
        for (m in nodes.filter { it.canMove && it.canRelay && it.nodeId != buyer.nodeId }.sortedBy { it.nodeId }) {
            for (p in providers) {
                if (p.nodeId == m.nodeId) continue
                val hasBm = links.any { it.touches(buyer.nodeId) && it.touches(m.nodeId) }
                val hasMp = links.any { it.touches(m.nodeId) && it.touches(p.nodeId) }
                if (hasBm && hasMp) continue // already covered by the DFS as a passive relay
                routes.add(routeFor(listOf(buyer.nodeId, m.nodeId, p.nodeId), p, p.source!!, policy, moveNode = m, activate = !p.hasInternet, byId = byId))
            }
        }

        val ranked = routes.distinctBy { it.id }.map { Ranked(it, score(it, demand, byId, links, policy)) }
        return ranked.sortedWith(compareByDescending<Ranked> { it.score.feasible }.thenBy { it.score.total }.thenBy { it.route.relayCount }.thenBy { it.route.id })
    }

    private fun routeFor(path: List<String>, provider: CoverageNode, source: InternetSource, policy: Policy, moveNode: CoverageNode?, activate: Boolean, byId: Map<String, CoverageNode>): CandidateRoute {
        val jobs = ArrayList<CoverageJob>()
        for (h in path.drop(1).dropLast(1)) {
            val n = byId[h]
            jobs.add(CoverageJob(h, JobKind.RELAY, maxOf(n?.minimumRewardCentimes ?: 0L, policy.defaultRelayReward)))
        }
        if (moveNode != null) jobs.add(CoverageJob(moveNode.nodeId, JobKind.MOVE, moveNode.moveCostCentimes))
        if (activate) jobs.add(CoverageJob(provider.nodeId, JobKind.ACTIVATE, provider.activationCostCentimes))
        jobs.add(CoverageJob(provider.nodeId, JobKind.PROVIDE, provider.minimumRewardCentimes))
        return CandidateRoute(path, source, jobs)
    }

    /** Score one route: total delivered cost + failure + delay + movement + resource penalties, and the economic verdict. */
    fun score(r: CandidateRoute, demand: DemandRequest, byId: Map<String, CoverageNode>, links: List<CandidateLink>, policy: Policy): RouteScore {
        val mb = demand.expectedMb.toLong()
        val sourceCost = r.source.costPerMbCentimes * mb
        val moveJobs = r.jobs.filter { it.kind == JobKind.MOVE }
        var movement = 0L
        for (j in moveJobs) {
            val m = byId[j.nodeId]
            val courier = m?.headingZone != null && m.headingZone == demand.zone
            movement += if (courier) j.rewardCentimes * policy.courierCostPct / 100 else j.rewardCentimes
        }
        val activation = r.jobs.filter { it.kind == JobKind.ACTIVATE }.sumOf { it.rewardCentimes }
        val rewards = r.jobs.filter { it.kind == JobKind.RELAY || it.kind == JobKind.PROVIDE }.sumOf { it.rewardCentimes }
        val jobCost = rewards + movement + activation
        val delivered = sourceCost + rewards + activation

        // failure probability: every node, every link and the source must hold
        var pOk = r.source.reliability
        for (h in r.hops.drop(1)) pOk *= (byId[h]?.reliability ?: 0.0)
        for (i in 0 until r.hops.size - 1) {
            val a = r.hops[i]; val b = r.hops[i + 1]
            val l = links.firstOrNull { it.touches(a) && it.touches(b) }
            pOk *= l?.quality ?: (if (r.moves) policy.moveLinkQuality else 0.0)
        }
        val pFail = (1.0 - pOk).coerceIn(0.0, 1.0)
        val failurePenalty = Math.round(pFail * (demand.revenueCentimes + policy.failureBaseCentimes))

        val delay = r.relayCount * policy.perHopDelayCentimes + (if (r.moves) policy.moveDelayCentimes else 0L) + (if (r.activates) policy.activateDelayCentimes else 0L)

        var resource = 0L
        for (h in r.hops.drop(1)) {
            val n = byId[h] ?: continue
            if (n.battery < policy.lowBatteryPct) resource += policy.lowBatteryPenalty
            if (n.charging) resource -= policy.chargingBonus
            if (n.stationary) resource -= policy.stationaryBonus
        }
        for (h in r.hops.drop(1).dropLast(1)) if (moveJobs.none { it.nodeId == h }) resource -= policy.inPositionBonus

        // economic ceiling
        val revenue = demand.revenueCentimes
        var subsidy = 0L; var feasible = true; var reason = "ok"
        when (demand.kind) {
            Kind.COMMERCIAL -> {
                val ceiling = revenue * (100 + policy.commercialMarginPct) / 100
                if (jobCost > ceiling) { feasible = false; reason = "exceeds commercial ceiling: jobs " + Market.cfa(jobCost) + " > " + Market.cfa(ceiling) }
            }
            Kind.SPONSORED -> { subsidy = jobCost; if (jobCost > demand.budgetCentimes) { feasible = false; reason = "exceeds sponsor budget: jobs " + Market.cfa(jobCost) + " > " + Market.cfa(demand.budgetCentimes) } }
            Kind.GROWTH_SUBSIDY -> { subsidy = maxOf(0L, jobCost - revenue); if (subsidy > demand.budgetCentimes) { feasible = false; reason = "exceeds growth subsidy: needs " + Market.cfa(subsidy) + " > " + Market.cfa(demand.budgetCentimes) } }
        }
        if (feasible) {
            val bad = r.hops.drop(1).dropLast(1).firstOrNull { byId[it]?.canRelay != true }
            if (bad != null) { feasible = false; reason = bad + " cannot relay" }
            else if (!redistributable(r.source.trust)) { feasible = false; reason = "source " + r.source.id + " is " + trustWord(r.source.trust) + ": not redistributable" }
            else if (r.source.wifiBased && byId[r.provider]?.canShareWhileOnWifi == false) {
                feasible = false; reason = r.provider + " cannot resell a Wi-Fi network (its hotspot is refused while it is joined to one)"
            }
            else if (!r.source.usable) { feasible = false; reason = "source " + r.source.id + " is not usable: " + (if (!r.source.validated) "not validated" else "unreliable") }
            else if (r.relayCount > policy.maxRelays) { feasible = false; reason = "too many relays" }
            else if (moveJobs.any { byId[it.nodeId]?.canMove != true }) { feasible = false; reason = "mover cannot move" }
        }
        return RouteScore(delivered, failurePenalty, delay, movement, resource, pFail, revenue, jobCost, subsidy, feasible, reason)
    }

    // ---- zones ----------------------------------------------------------------------------------------

    enum class ZoneStatus { GREEN, YELLOW, RED }
    class CoverageZone(val id: String, val name: String, val status: ZoneStatus, val bestRouteId: String?)

    /**
     * GREEN: a feasible route exists now, no movement or activation, failure risk under the green limit.
     * YELLOW: Internet can probably be created quickly (a feasible route needs a mover or an activation,
     *         or a low-risk route is blocked only by money).
     * RED: nothing reliable.
     */
    fun zoneStatus(ranked: List<Ranked>, policy: Policy = Policy()): ZoneStatus {
        if (ranked.any { it.score.feasible && !it.route.moves && !it.route.activates && it.score.pFail <= policy.greenMaxFail }) return ZoneStatus.GREEN
        if (ranked.any { it.score.feasible && it.score.pFail <= policy.yellowMaxFail }) return ZoneStatus.YELLOW
        if (ranked.any { !it.score.feasible && it.score.reason.startsWith("exceeds") && it.score.pFail <= policy.yellowMaxFail }) return ZoneStatus.YELLOW
        return ZoneStatus.RED
    }

    fun zone(id: String, name: String, ranked: List<Ranked>, policy: Policy = Policy()): CoverageZone =
        CoverageZone(id, name, zoneStatus(ranked, policy), ranked.firstOrNull { it.score.feasible }?.route?.id)

    fun describe(r: Ranked): String = r.route.id + " total " + Market.cfa(r.score.total) + " (delivered " + Market.cfa(r.score.deliveredCost) + ", fail " + String.format("%.0f%%", r.score.pFail * 100) +
        ", jobs " + Market.cfa(r.score.jobCost) + ") " + (if (r.score.feasible) "OK" else "REJECTED: " + r.score.reason)
}
