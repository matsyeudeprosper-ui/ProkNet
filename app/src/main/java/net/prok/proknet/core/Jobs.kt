package net.prok.proknet.core

/**
 * v0.13: the generic job model and the pure matching. Two job types execute
 * today (PROVIDER_ACTIVATION, CARRY_REQUEST); the others are modelled so a
 * third phone plugs into an architecture that already understands them, and
 * nothing pretends they run.
 */
object Jobs {

    enum class Type { PROVIDER_ACTIVATION, CARRY_REQUEST, ANCHOR, RELAY, MOVE_TO_ZONE, COURIER }
    enum class State { OPEN, OFFERED, ACCEPTED, ACTIVE, COMPLETED, FAILED, EXPIRED, CANCELLED }

    /** What this build can actually run. Everything else is a model. */
    fun executable(t: Type): Boolean = t == Type.PROVIDER_ACTIVATION || t == Type.CARRY_REQUEST

    data class Job(
        val id: String, val type: Type, val zone: String, val requestId: String, val providerId: String?,
        val createdAt: Long, val expiresAt: Long, val rewardCentimes: Long, val maxCostCentimes: Long,
        val requirements: String, val state: State, val updatedAt: Long,
    ) {
        val terminal: Boolean get() = state == State.COMPLETED || state == State.FAILED || state == State.EXPIRED || state == State.CANCELLED
    }

    private val allowed: Map<State, Set<State>> = mapOf(
        State.OPEN to setOf(State.OFFERED, State.ACCEPTED, State.EXPIRED, State.CANCELLED),
        State.OFFERED to setOf(State.ACCEPTED, State.OPEN, State.EXPIRED, State.CANCELLED),
        State.ACCEPTED to setOf(State.ACTIVE, State.FAILED, State.EXPIRED, State.CANCELLED),
        State.ACTIVE to setOf(State.COMPLETED, State.FAILED, State.CANCELLED),
    )

    fun advance(j: Job, to: State, now: Long): Job? = if (allowed[j.state]?.contains(to) == true) j.copy(state = to, updatedAt = now) else null

    fun expire(j: Job, now: Long): Job = if (!j.terminal && now >= j.expiresAt) j.copy(state = State.EXPIRED, updatedAt = now) else j

    /** Activation asks nothing extra of the provider: it earns through the session. Carrying pays nothing yet. */
    fun providerActivation(id: String, r: NetRequest.Request, providerId: String, now: Long): Job =
        Job(id, Type.PROVIDER_ACTIVATION, r.zone, r.id, providerId, now, minOf(r.expiresAt, now + 10 * 60_000L), 0L, 0L, "validated upstream, local path, price within ceiling", State.OFFERED, now)

    fun carry(id: String, r: NetRequest.Request, carrierId: String, now: Long): Job =
        Job(id, Type.CARRY_REQUEST, r.zone, r.id, carrierId, now, r.expiresAt, 0L, 0L, "store, carry, forward once per peer, upload when online", State.ACTIVE, now)

    // ---- matching --------------------------------------------------------------------------------------------

    enum class Plan { DIRECT_SOURCE, ACTIVATE_PROVIDER, WAIT_FOR_SUPPLY, NO_PLAN }

    data class ProviderView(val id: String, val zone: String, val potential: Boolean, val sharing: Boolean, val priceCentimesPerMb: Int,
                            val lastHeartbeat: Long, val busy: Boolean, val upstreamValidated: Boolean, val reachableLocally: Boolean = false)

    data class Decision(val plan: Plan, val providerId: String?, val reason: String)

    const val HEARTBEAT_MAX_AGE_MS = 15 * 60_000L

    /**
     * Direct first. Then a provider that could be activated: opted in, alive,
     * validated, not busy, in the same zone (or seen locally, which beats any
     * zone), within the ceiling, cheapest. Otherwise wait, or nothing once the
     * request is over. Zone proximity is never proof of reach: an activated
     * provider must still advertise and be reachable before anyone connects.
     */
    fun match(r: NetRequest.Request, directUsable: Boolean, providers: List<ProviderView>, now: Long): Decision {
        if (directUsable) return Decision(Plan.DIRECT_SOURCE, null, "a usable source is reachable now")
        if (!r.open || r.expired(now)) return Decision(Plan.NO_PLAN, null, "request is not open")
        val ceiling = r.ceiling
        val ok = providers.filter { p ->
            p.potential && !p.busy && !p.sharing && p.upstreamValidated && now - p.lastHeartbeat <= HEARTBEAT_MAX_AGE_MS &&
                (p.reachableLocally || (p.zone == r.zone && r.zone != CoverageModel.NO_ZONE)) &&
                (ceiling == null || p.priceCentimesPerMb <= ceiling)
        }.sortedWith(compareBy<ProviderView> { !it.reachableLocally }.thenBy { it.priceCentimesPerMb }.thenByDescending { it.lastHeartbeat })
        val best = ok.firstOrNull() ?: return Decision(Plan.WAIT_FOR_SUPPLY, null,
            if (providers.isEmpty()) "no potential provider known" else "no eligible provider (" + providers.size + " known)")
        val plan = InternetRequest.directPlan(InternetRequest.oneTap(r.id, r.createdAt, r.zone), GetInternet.Candidate(best.id, GetInternet.Way.PROKNET_DIRECT, best.id, best.priceCentimesPerMb, true, -60, now, true, 0.7), expectedMb = 10)
        val ceilingCentimes = (ceiling ?: best.priceCentimesPerMb).toLong() * 10
        val (admissible, why) = InternetRequest.admissible(plan, ceilingCentimes)
        if (!admissible) return Decision(Plan.WAIT_FOR_SUPPLY, null, "provider " + best.id + " rejected: " + why)
        return Decision(Plan.ACTIVATE_PROVIDER, best.id, (if (best.reachableLocally) "seen locally, " else "same zone, ") + CoverageModel.priceWord(best.priceCentimesPerMb))
    }
}
