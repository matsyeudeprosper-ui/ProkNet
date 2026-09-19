package net.prok.proknet.core

import kotlin.math.min
import kotlin.math.roundToLong

/**
 * v0.12: the one-tap decision. FREE first, CHEAPEST next, a materially more
 * reliable source over a slightly cheaper one, and never a source the phone
 * cannot genuinely use right now. Pure, tested; the screen only executes
 * what it returns.
 */
object GetInternet {

    enum class Action { CONNECT_NOW, REQUEST_NETWORK, WAIT, NONE }

    /** How the phone would use the source. Only ways the app can execute today are produced. */
    enum class Way { PROKNET_DIRECT, EXISTING_LINK, CONNECTED_WIFI, MOBILE_DATA_PROVIDER }

    data class Candidate(
        val id: String,
        val way: Way,
        val name: String,
        /** Centimes per MB, 0 = free. */
        val priceCentimesPerMb: Int,
        val reachableNow: Boolean,
        val rssi: Int,
        val lastSeen: Long,
        val validated: Boolean,
        val reliability: Double,
        val setupCostCentimes: Int = 0,
        val capacityMb: Int = 0,
        val authorized: Boolean = true,
        val peerShort: String? = null,
    ) {
        val free: Boolean get() = priceCentimesPerMb == 0
    }

    data class Scored(val candidate: Candidate, val usable: Boolean, val cost: Long, val why: String)

    data class Decision(val action: Action, val chosen: Candidate?, val reason: String, val ranked: List<Scored>)

    /** A candidate not seen for this long is not "reachable now", whatever its flag says. */
    const val STALE_MS = 90_000L
    /** A fully unreliable source counts this much more per MB than its price. */
    const val UNRELIABILITY_PENALTY_CENTIMES = 300L
    /** The last source that worked wins ties, and only ties. */
    const val LAST_SUCCESS_BONUS_CENTIMES = 40L
    /** A setup cost is spread over roughly this many MB. */
    const val SETUP_SPREAD_MB = 20

    fun blocker(c: Candidate, now: Long, ceilingCentimesPerMb: Int?): String? = when {
        !c.reachableNow -> "not reachable now"
        now - c.lastSeen > STALE_MS -> "stale (last seen " + ((now - c.lastSeen) / 1000) + " s ago)"
        !c.authorized -> "not authorized"
        !c.validated -> "Internet not validated"
        c.reliability <= 0.0 -> "unreliable"
        ceilingCentimesPerMb != null && c.priceCentimesPerMb > ceilingCentimesPerMb -> "above the price ceiling"
        else -> null
    }

    fun cost(c: Candidate, lastSuccessfulId: String?): Long =
        c.priceCentimesPerMb + ((1.0 - c.reliability.coerceIn(0.0, 1.0)) * UNRELIABILITY_PENALTY_CENTIMES).roundToLong() +
            c.setupCostCentimes / SETUP_SPREAD_MB - (if (c.id == lastSuccessfulId) LAST_SUCCESS_BONUS_CENTIMES else 0L)

    fun decide(candidates: List<Candidate>, now: Long, ceilingCentimesPerMb: Int? = null, lastSuccessfulId: String? = null): Decision {
        val scored = candidates.map { c -> val b = blocker(c, now, ceilingCentimesPerMb); Scored(c, b == null, cost(c, lastSuccessfulId), b ?: "usable") }
        val ranked = scored.sortedWith(
            compareBy<Scored> { !it.usable }
                .thenBy { !(it.usable && it.candidate.free) }
                .thenBy { it.cost }
                .thenByDescending { it.candidate.rssi }
                .thenBy { it.candidate.id })
        val first = ranked.firstOrNull { it.usable }
            ?: return if (candidates.isEmpty()) Decision(Action.NONE, null, "no source known", ranked)
            else Decision(Action.REQUEST_NETWORK, null, "no source usable right now (" + ranked.first().why + "); keep searching", ranked)
        val c = first.candidate
        val cheapest = ranked.filter { it.usable }.minByOrNull { it.candidate.priceCentimesPerMb }!!.candidate
        val reason = when {
            c.way == Way.CONNECTED_WIFI -> "this phone already has validated Internet over Wi-Fi"
            c.free -> "free validated reachable source"
            c.way == Way.EXISTING_LINK -> "an authenticated link to it already exists"
            c.priceCentimesPerMb > cheapest.priceCentimesPerMb -> "more reliable than the cheaper " + cheapest.name + " for a little more"
            c.id == lastSuccessfulId -> "cheapest validated reachable source, and the last one that worked"
            else -> "cheapest validated reachable source"
        }
        return Decision(Action.CONNECT_NOW, c, reason, ranked)
    }

    /** How much to trust a source: its validation history, how often we saw it, what worked, and the signal now. */
    fun reliability(history: CoverageModel.Source?, rssi: Int): Double {
        val signal = (rssi.coerceIn(-100, -40) + 100) / 60.0
        val base = if (history == null) 0.6 else {
            val v = if (history.validatedSeen == 0) 0.6 else history.validatedOk.toDouble() / history.validatedSeen
            0.5 + 0.3 * v + 0.1 * min(1.0, history.observations / 10.0) + 0.1 * min(1.0, history.successes / 3.0)
        }
        return (base * (0.6 + 0.4 * signal)).coerceIn(0.05, 0.99)
    }

    /** A live ProkNet offer as a candidate. The transport underneath is chosen later by the proven rules, never here. */
    fun fromOffer(o: Market.Offer, reachableNow: Boolean, linkUp: Boolean, history: CoverageModel.Source?, now: Long): Candidate = Candidate(
        id = CoverageModel.sourceId(CoverageModel.SourceKind.PROKNET, o.sellerShort),
        way = if (linkUp) Way.EXISTING_LINK else if (o.upstreamType == Tunnel.UP_CELLULAR) Way.MOBILE_DATA_PROVIDER else Way.PROKNET_DIRECT,
        name = "prok-" + o.sellerShort,
        priceCentimesPerMb = o.pricePerMb * 100,
        reachableNow = reachableNow && o.selling,
        rssi = o.rssi,
        lastSeen = if (o.lastSeen == 0L) now else o.lastSeen,
        validated = o.validated,
        reliability = if (linkUp) 0.95 else reliability(history, o.rssi),
        setupCostCentimes = if (linkUp) 0 else 0,
        authorized = true,
        peerShort = o.sellerShort,
    )

    fun describe(d: Decision): String {
        val sb = StringBuilder()
        sb.append("decision: ").append(d.action).append(d.chosen?.let { " -> " + it.name + " (" + it.way + ", " + CoverageModel.priceWord(it.priceCentimesPerMb) + ")" } ?: "")
            .append("\n  why: ").append(d.reason).append('\n')
        for (s in d.ranked) sb.append("  ").append(if (s.usable) "OK  " else "no  ").append(s.candidate.name).append(' ').append(s.candidate.way)
            .append(" price=").append(s.candidate.priceCentimesPerMb).append("c rel=").append(String.format("%.2f", s.candidate.reliability))
            .append(" rssi=").append(s.candidate.rssi).append(" cost=").append(s.cost).append(" : ").append(s.why).append('\n')
        return sb.toString()
    }
}
