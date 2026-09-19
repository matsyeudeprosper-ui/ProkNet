package net.prok.proknet.core

/**
 * v0.12: an Internet request as the future Uber-like model sees it, independent
 * of any transport, plus the first planner: a direct plan and the economic
 * rule. One tap creates a request with flexible defaults; the state machine
 * is honest about what the network can do today (NETWORK_NEEDED never
 * pretends a relay is coming).
 */
object InternetRequest {

    enum class State { IDLE, SEARCHING, DIRECT_SOURCE_FOUND, NETWORK_NEEDED, CONNECTING, ONLINE, FAILED, CANCELLED }
    enum class Urgency { NOW, SOON, FLEXIBLE }

    const val FLEXIBLE = 0
    const val PRICE_AUTOMATIC = -1

    data class Request(
        val id: String,
        val createdAt: Long,
        /** A coarse zone, never a point. */
        val zone: String,
        val desiredMb: Int,
        val desiredMinutes: Int,
        /** Centimes per MB; [PRICE_AUTOMATIC] = cheapest available. */
        val maxPriceCentimesPerMb: Int,
        val urgency: Urgency,
        val state: State,
        val sourceId: String?,
        val updatedAt: Long,
        val note: String,
    ) {
        val terminal: Boolean get() = state == State.ONLINE || state == State.FAILED || state == State.CANCELLED
        val active: Boolean get() = !terminal && state != State.IDLE
        /** null = no ceiling: take the cheapest usable. */
        val ceiling: Int? get() = if (maxPriceCentimesPerMb == PRICE_AUTOMATIC) null else maxPriceCentimesPerMb
    }

    /** The one-tap request: nothing asked, everything flexible, now. */
    fun oneTap(id: String, now: Long, zone: String): Request =
        Request(id, now, zone, FLEXIBLE, FLEXIBLE, PRICE_AUTOMATIC, Urgency.NOW, State.SEARCHING, null, now, "")

    fun apply(r: Request, d: GetInternet.Decision, now: Long): Request = when (d.action) {
        GetInternet.Action.CONNECT_NOW -> r.copy(state = State.DIRECT_SOURCE_FOUND, sourceId = d.chosen?.id, updatedAt = now, note = d.reason)
        GetInternet.Action.REQUEST_NETWORK, GetInternet.Action.NONE -> r.copy(state = State.NETWORK_NEEDED, sourceId = null, updatedAt = now, note = d.reason)
        GetInternet.Action.WAIT -> r.copy(state = State.SEARCHING, updatedAt = now, note = d.reason)
    }

    fun connecting(r: Request, now: Long): Request = r.copy(state = State.CONNECTING, updatedAt = now)
    fun online(r: Request, now: Long): Request = r.copy(state = State.ONLINE, updatedAt = now)
    fun failed(r: Request, why: String, now: Long): Request = r.copy(state = State.FAILED, updatedAt = now, note = why)
    fun cancelled(r: Request, now: Long): Request = r.copy(state = State.CANCELLED, updatedAt = now)
    /** Searching again after NETWORK_NEEDED: the request is the same one. */
    fun searching(r: Request, now: Long): Request = r.copy(state = State.SEARCHING, updatedAt = now)

    /** The words on the big button screen. */
    fun title(s: State, longSearch: Boolean = false): String = when (s) {
        State.IDLE -> ""
        State.SEARCHING -> if (longSearch) "Recherche du meilleur Internet…" else "Recherche d'Internet…"
        State.DIRECT_SOURCE_FOUND -> "Internet trouvé ✅"
        State.NETWORK_NEEDED -> "Aucun Internet disponible tout de suite."
        State.CONNECTING -> "Connexion…"
        State.ONLINE -> "Internet connecté ✅"
        State.FAILED -> "Connexion impossible"
        State.CANCELLED -> ""
    }

    fun hint(s: State): String = when (s) {
        State.NETWORK_NEEDED -> "ProkNet continue de chercher autour de vous."
        State.SEARCHING -> "Gardez le téléphone allumé"
        else -> ""
    }

    // ---- the planner foundation ---------------------------------------------------------------------------

    enum class HopRole { PROVIDER, ANCHOR, RELAY, MOVER, COURIER }

    data class Hop(val role: HopRole, val nodeId: String, val rewardCentimes: Long)

    data class Plan(
        val requestId: String,
        val sourceId: String,
        val hops: List<Hop>,
        val expectedPriceCentimesPerMb: Int,
        val expectedReliability: Double,
        val movementRequired: Boolean,
        val subsidyRequiredCentimes: Long,
        val costClass: Coverage.Kind,
        /** What delivering the session costs the network, in centimes. */
        val deliveryCostCentimes: Long,
    )

    /** v0.12: buyer -> provider, nothing else exists yet. */
    fun directPlan(r: Request, c: GetInternet.Candidate, expectedMb: Int, costClass: Coverage.Kind = Coverage.Kind.COMMERCIAL): Plan {
        val hop = Hop(HopRole.PROVIDER, c.peerShort ?: c.id, 0L)
        val delivery = c.priceCentimesPerMb.toLong() * expectedMb + c.setupCostCentimes
        return Plan(r.id, c.id, listOf(hop), c.priceCentimesPerMb, c.reliability, false, 0L, costClass, delivery)
    }

    /**
     * The economic rule: do not spend 300 CFA delivering a 50 CFA session unless
     * it is explicitly sponsored or a growth subsidy. Returns (admissible, why).
     */
    fun admissible(p: Plan, customerCeilingCentimes: Long, budgetCentimes: Long = 0L): Pair<Boolean, String> = when (p.costClass) {
        Coverage.Kind.COMMERCIAL ->
            if (p.deliveryCostCentimes <= customerCeilingCentimes) true to "delivery " + Market.cfa(p.deliveryCostCentimes) + " within the customer ceiling " + Market.cfa(customerCeilingCentimes)
            else false to "delivery " + Market.cfa(p.deliveryCostCentimes) + " exceeds the customer ceiling " + Market.cfa(customerCeilingCentimes)
        Coverage.Kind.SPONSORED ->
            if (p.deliveryCostCentimes <= budgetCentimes) true to "sponsored: " + Market.cfa(p.deliveryCostCentimes) + " within the sponsor budget"
            else false to "sponsored: " + Market.cfa(p.deliveryCostCentimes) + " exceeds the sponsor budget " + Market.cfa(budgetCentimes)
        Coverage.Kind.GROWTH_SUBSIDY -> {
            val need = maxOf(0L, p.deliveryCostCentimes - customerCeilingCentimes)
            if (need <= budgetCentimes) true to "growth subsidy of " + Market.cfa(need) + " accepted"
            else false to "growth subsidy of " + Market.cfa(need) + " exceeds the budget " + Market.cfa(budgetCentimes)
        }
    }

    /** Movement is the last resort: plans that need none first, then the cheapest delivery, then the most reliable. */
    fun rank(plans: List<Plan>): List<Plan> =
        plans.sortedWith(compareBy<Plan> { it.movementRequired }.thenBy { it.deliveryCostCentimes }.thenByDescending { it.expectedReliability })
}
