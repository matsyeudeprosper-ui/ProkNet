package net.prok.proknet.core

import java.security.MessageDigest
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * v0.12: the coverage observation layer, pure part. Every phone is a sensor:
 * it sees ProkNet providers over BLE and Wi-Fi networks around it, and this
 * object turns those sightings into ONE record per real Internet source, per
 * coarse zone, with a conservative status.
 *
 * Privacy, from the start: a Wi-Fi source is identified by a hash of its
 * BSSID, never the BSSID itself; a zone is a ~500 m cell, never a point; no
 * password is ever seen or stored. Detection is not authorization: a
 * network that was merely seen stays UNKNOWN and is never counted as usable.
 *
 * No Android here. The Android side ([net.prok.proknet.node.CoverageEngine])
 * feeds [Sighting]s and persists [State] through [encode] / [decode].
 */
object CoverageModel {

    enum class SourceKind { PROKNET, WIFI }

    const val PRICE_UNKNOWN = -1
    const val NO_ZONE = "z?"
    const val MAX_ZONES_PER_SOURCE = 20
    const val MAX_OBSERVATIONS = 500
    const val MAX_REQUESTS = 50
    /** A source seen this recently can make a zone GREEN (if it is usable now). */
    const val FRESH_MS = 10 * 60_000L
    /** A source seen this recently still counts as potential coverage (YELLOW). */
    const val RECENT_MS = 24 * 3_600_000L
    /** Cell edge in degrees: about 550 m of latitude. Coarse on purpose. */
    const val CELL_DEG = 0.005

    /** One real Internet source, deduplicated across every sighting. */
    data class Source(
        val id: String,
        val kind: SourceKind,
        val name: String,
        val firstSeen: Long,
        val lastSeen: Long,
        val observations: Int,
        val bestRssi: Int,
        val lastRssi: Int,
        /** Centimes per MB; 0 = free; [PRICE_UNKNOWN] when nobody said. */
        val priceCentimesPerMb: Int,
        /** Last known Internet validation. */
        val validated: Boolean,
        val validatedSeen: Int,
        val validatedOk: Int,
        val trust: Coverage.Trust,
        val security: String,
        val zones: List<String>,
        val lastZone: String,
        /** ProkNet: was it selling the last time we saw it. */
        val selling: Boolean,
        /** Sessions that reached the Internet through it from this phone. */
        val successes: Int,
    ) {
        val free: Boolean get() = priceCentimesPerMb == 0
        val priceKnown: Boolean get() = priceCentimesPerMb >= 0
    }

    data class Observation(val sourceId: String, val at: Long, val rssi: Int, val zone: String, val validated: Boolean?, val priceCentimesPerMb: Int, val selling: Boolean)

    /** What the Android side saw, once. `validated == null` means "nobody knows". */
    class Sighting(
        val kind: SourceKind, val rawId: String, val name: String, val rssi: Int, val at: Long, val zone: String,
        val validated: Boolean?, val priceCentimesPerMb: Int, val trust: Coverage.Trust, val security: String, val selling: Boolean,
    )

    // ---- stable identities -------------------------------------------------------------------------

    fun sourceId(kind: SourceKind, rawId: String): String = when (kind) {
        SourceKind.PROKNET -> "prok:" + rawId.lowercase()
        SourceKind.WIFI -> "wifi:" + hash16(rawId.lowercase())
    }

    /** First 16 hex characters of SHA-256: stable, and not the identifier itself. */
    fun hash16(s: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        val sb = StringBuilder()
        for (i in 0 until 8) sb.append(String.format("%02x", d[i].toInt() and 0xFF))
        return sb.toString()
    }

    /** Merge one sighting into the registry: the same BSSID or the same ProkNet identity stays ONE source. */
    fun observe(sources: Map<String, Source>, s: Sighting): Pair<Map<String, Source>, Observation> {
        val id = sourceId(s.kind, s.rawId)
        val obs = Observation(id, s.at, s.rssi, s.zone, s.validated, s.priceCentimesPerMb, s.selling)
        val old = sources[id]
        val merged = if (old == null) Source(
            id, s.kind, s.name, s.at, s.at, 1, s.rssi, s.rssi, s.priceCentimesPerMb,
            s.validated == true, if (s.validated != null) 1 else 0, if (s.validated == true) 1 else 0,
            s.trust, s.security, listOf(s.zone), s.zone, s.selling, 0,
        ) else old.copy(
            name = s.name.ifEmpty { old.name },
            firstSeen = min(old.firstSeen, s.at),
            lastSeen = max(old.lastSeen, s.at),
            observations = old.observations + 1,
            bestRssi = max(old.bestRssi, s.rssi),
            lastRssi = s.rssi,
            priceCentimesPerMb = if (s.priceCentimesPerMb >= 0) s.priceCentimesPerMb else old.priceCentimesPerMb,
            validated = s.validated ?: old.validated,
            validatedSeen = old.validatedSeen + (if (s.validated != null) 1 else 0),
            validatedOk = old.validatedOk + (if (s.validated == true) 1 else 0),
            trust = if (s.trust != Coverage.Trust.UNKNOWN) s.trust else old.trust,
            security = s.security.ifEmpty { old.security },
            zones = if (s.zone in old.zones) old.zones else (old.zones + s.zone).takeLast(MAX_ZONES_PER_SOURCE),
            lastZone = s.zone,
            selling = s.selling,
        )
        return (sources + (id to merged)) to obs
    }

    fun withSuccess(sources: Map<String, Source>, id: String): Map<String, Source> {
        val s = sources[id] ?: return sources
        return sources + (id to s.copy(successes = s.successes + 1))
    }

    // ---- zones: coarse cells, never points -------------------------------------------------------------

    fun zoneId(lat: Double, lon: Double): String = "z" + floor(lat / CELL_DEG).toLong() + ":" + floor(lon / CELL_DEG).toLong()

    fun zoneIndex(zone: String): Pair<Long, Long>? {
        if (zone == NO_ZONE || !zone.startsWith("z")) return null
        val p = zone.substring(1).split(":")
        if (p.size != 2) return null
        return try { p[0].toLong() to p[1].toLong() } catch (_: Exception) { null }
    }

    fun zoneAt(latIndex: Long, lonIndex: Long): String = "z" + latIndex + ":" + lonIndex

    /** Centre of a cell (lat, lon), or null for the no-location zone. */
    fun zoneCenter(zone: String): Pair<Double, Double>? = zoneIndex(zone)?.let { (it.first + 0.5) * CELL_DEG to (it.second + 0.5) * CELL_DEG }

    // ---- status: conservative ----------------------------------------------------------------------------

    data class Cell(
        val zoneId: String,
        val lastObservedAt: Long,
        val directSourceCount: Int,
        val potentialSourceCount: Int,
        /** Centimes per MB of the cheapest known source, [PRICE_UNKNOWN] if none said. */
        val bestKnownPrice: Int,
        val status: Coverage.ZoneStatus,
        val confidence: Double,
    )

    /**
     * Can this source give THIS phone Internet right now? It must be reachable
     * now, validated, and either a ProkNet provider that is selling or a Wi-Fi
     * network this phone is allowed to use. A network merely detected never
     * qualifies.
     */
    fun usableNow(s: Source, reachableNow: Boolean): Boolean =
        reachableNow && s.validated && (if (s.kind == SourceKind.PROKNET) s.selling else Coverage.redistributable(s.trust))

    /** Plausible but not deliverable now: seen recently, and the kind of thing that could be activated. */
    fun potential(s: Source, now: Long): Boolean =
        now - s.lastSeen <= RECENT_MS && (s.kind == SourceKind.PROKNET || s.validated || Coverage.redistributable(s.trust))

    fun cellStatus(inZone: List<Source>, now: Long, reachable: Set<String>): Coverage.ZoneStatus = when {
        inZone.any { usableNow(it, it.id in reachable) && now - it.lastSeen <= FRESH_MS } -> Coverage.ZoneStatus.GREEN
        inZone.any { potential(it, now) } -> Coverage.ZoneStatus.YELLOW
        else -> Coverage.ZoneStatus.RED
    }

    private fun freshness(ageMs: Long): Double = when {
        ageMs <= FRESH_MS -> 1.0
        ageMs >= RECENT_MS -> 0.2
        else -> 1.0 - 0.8 * (ageMs - FRESH_MS).toDouble() / (RECENT_MS - FRESH_MS)
    }

    /** One cell per zone any source was seen in, most recently observed first. */
    fun cells(sources: Collection<Source>, now: Long, reachable: Set<String>): List<Cell> {
        val byZone = HashMap<String, MutableList<Source>>()
        for (s in sources) for (z in s.zones) byZone.getOrPut(z) { ArrayList() }.add(s)
        return byZone.map { (zone, list) ->
            val last = list.maxOf { it.lastSeen }
            val direct = list.count { usableNow(it, it.id in reachable) && now - it.lastSeen <= FRESH_MS }
            val potential = list.count { potential(it, now) } - direct
            val price = list.filter { it.priceKnown && potential(it, now) }.minOfOrNull { it.priceCentimesPerMb } ?: PRICE_UNKNOWN
            val obs = list.sumOf { it.observations }
            Cell(zone, last, direct, max(0, potential), price, cellStatus(list, now, reachable), (min(1.0, obs / 10.0) * freshness(now - last)).coerceIn(0.0, 1.0))
        }.sortedWith(compareByDescending<Cell> { it.lastObservedAt }.thenBy { it.zoneId })
    }

    /** What the phone can say about "around you": the best status over the zones it is in or has no location for. */
    fun hereStatus(cells: List<Cell>, myZone: String): Coverage.ZoneStatus {
        val here = cells.filter { it.zoneId == myZone || it.zoneId == NO_ZONE }
        return when {
            here.any { it.status == Coverage.ZoneStatus.GREEN } -> Coverage.ZoneStatus.GREEN
            here.any { it.status == Coverage.ZoneStatus.YELLOW } -> Coverage.ZoneStatus.YELLOW
            else -> Coverage.ZoneStatus.RED
        }
    }

    // ---- words -------------------------------------------------------------------------------------------

    fun ageWord(ageMs: Long): String {
        val m = ageMs / 60_000
        return when {
            m < 1 -> "\u00e0 l'instant"
            m < 60 -> "il y a " + m + " min"
            m < 48 * 60 -> "il y a " + (m / 60) + " h"
            else -> "il y a " + (m / 1440) + " j"
        }
    }

    fun kindWord(k: SourceKind): String = if (k == SourceKind.WIFI) "Wi-Fi" else "ProkNet"

    fun priceWord(centimesPerMb: Int): String = when {
        centimesPerMb == PRICE_UNKNOWN -> "inconnu"
        centimesPerMb == 0 -> "gratuit"
        centimesPerMb % 100 == 0 -> (centimesPerMb / 100).toString() + " CFA par Mo"
        else -> String.format(java.util.Locale.FRANCE, "%.2f CFA par Mo", centimesPerMb / 100.0)
    }

    /** "Internet disponible récemment" and friends: the user words for a cell. */
    fun cellWord(status: Coverage.ZoneStatus): String = when (status) {
        Coverage.ZoneStatus.GREEN -> "Internet disponible"
        Coverage.ZoneStatus.YELLOW -> "Internet peut \u00eatre organis\u00e9"
        Coverage.ZoneStatus.RED -> "Pas encore couvert"
    }

    // ---- persistence: a plain text codec, versioned, tolerant --------------------------------------------

    data class State(
        val sources: Map<String, Source>,
        val observations: List<Observation>,
        val requests: List<InternetRequest.Request>,
        val lastSuccessfulSourceId: String?,
    )

    val EMPTY = State(emptyMap(), emptyList(), emptyList(), null)

    private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")
    private fun unesc(s: String): String {
        val sb = StringBuilder(); var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) { when (s[i + 1]) { 't' -> sb.append('\t'); 'n' -> sb.append('\n'); else -> sb.append(s[i + 1]) }; i += 2 }
            else { sb.append(c); i++ }
        }
        return sb.toString()
    }

    fun encode(st: State): String {
        val sb = StringBuilder("V\t1\n")
        st.lastSuccessfulSourceId?.let { sb.append("L\t").append(esc(it)).append('\n') }
        for (s in st.sources.values.sortedBy { it.id }) sb.append("S\t").append(listOf(
            s.id, s.kind.name, s.name, s.firstSeen, s.lastSeen, s.observations, s.bestRssi, s.lastRssi, s.priceCentimesPerMb,
            s.validated, s.validatedSeen, s.validatedOk, s.trust.name, s.security, s.zones.joinToString(","), s.lastZone, s.selling, s.successes,
        ).joinToString("\t") { esc(it.toString()) }).append('\n')
        for (o in st.observations.takeLast(MAX_OBSERVATIONS)) sb.append("O\t").append(listOf(
            o.sourceId, o.at, o.rssi, o.zone, o.validated?.toString() ?: "?", o.priceCentimesPerMb, o.selling,
        ).joinToString("\t") { esc(it.toString()) }).append('\n')
        for (r in st.requests.takeLast(MAX_REQUESTS)) sb.append("R\t").append(listOf(
            r.id, r.createdAt, r.zone, r.desiredMb, r.desiredMinutes, r.maxPriceCentimesPerMb, r.urgency.name, r.state.name, r.sourceId ?: "", r.updatedAt, r.note,
        ).joinToString("\t") { esc(it.toString()) }).append('\n')
        return sb.toString()
    }

    fun decode(text: String): State {
        val sources = HashMap<String, Source>(); val obs = ArrayList<Observation>(); val reqs = ArrayList<InternetRequest.Request>()
        var last: String? = null
        for (line in text.split('\n')) {
            if (line.isEmpty()) continue
            val f = line.split('\t').map { unesc(it) }
            try {
                when (f[0]) {
                    "L" -> last = f[1]
                    "S" -> if (f.size >= 19) sources[f[1]] = Source(
                        f[1], SourceKind.valueOf(f[2]), f[3], f[4].toLong(), f[5].toLong(), f[6].toInt(), f[7].toInt(), f[8].toInt(), f[9].toInt(),
                        f[10].toBoolean(), f[11].toInt(), f[12].toInt(), Coverage.Trust.valueOf(f[13]), f[14],
                        f[15].split(',').filter { it.isNotEmpty() }, f[16], f[17].toBoolean(), f[18].toInt(),
                    )
                    "O" -> if (f.size >= 8) obs.add(Observation(f[1], f[2].toLong(), f[3].toInt(), f[4], if (f[5] == "?") null else f[5].toBoolean(), f[6].toInt(), f[7].toBoolean()))
                    "R" -> if (f.size >= 12) reqs.add(InternetRequest.Request(
                        f[1], f[2].toLong(), f[3], f[4].toInt(), f[5].toInt(), f[6].toInt(), InternetRequest.Urgency.valueOf(f[7]),
                        InternetRequest.State.valueOf(f[8]), f[9].ifEmpty { null }, f[10].toLong(), f[11],
                    ))
                }
            } catch (_: Exception) { /* one bad line never loses the rest */ }
        }
        return State(sources, obs, reqs, last)
    }
}
