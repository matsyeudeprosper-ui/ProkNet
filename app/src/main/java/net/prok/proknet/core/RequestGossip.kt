package net.prok.proknet.core

/**
 * v0.13: store-carry-forward for Internet requests, the DTN control plane.
 * Carrying a request means storing the signed object, moving with it,
 * forwarding it once to each new peer, and uploading it when Internet is
 * available. It never means carrying live Internet.
 *
 * Pure: a [State] in, a [State] out. Dedup by id + generation, a hop budget,
 * an expiry, tombstones that outlive the request so an old carried copy
 * cannot bring it back, and a per-peer forward record so nothing loops.
 */
object RequestGossip {

    enum class Receipt { NEW, NEWER_GENERATION, DUPLICATE, OLDER_GENERATION, EXPIRED, INVALID_SIGNATURE, HOPS_EXHAUSTED, RESURRECTION_BLOCKED }

    data class Stats(val received: Int = 0, val accepted: Int = 0, val dedupDrops: Int = 0, val ttlDrops: Int = 0, val hopDrops: Int = 0, val badSignature: Int = 0, val forwards: Int = 0)

    data class State(
        /** The latest generation known per id (an open request, or its tombstone). */
        val requests: Map<String, NetRequest.Request> = emptyMap(),
        /** "id:generation:peer" -> when it was forwarded there. */
        val forwarded: Map<String, Long> = emptyMap(),
        /** "id:generation" already uploaded to the brain. */
        val uploaded: Set<String> = emptySet(),
        /** ids this phone originated. */
        val mine: Set<String> = emptySet(),
        val stats: Stats = Stats(),
    )

    const val TOMBSTONE_KEEP_MS = 2 * 3_600_000L
    const val FORWARD_MEMORY_MS = 6 * 3_600_000L

    private fun key(id: String, gen: Int, peer: String) = id + ":" + gen + ":" + peer
    private fun ukey(id: String, gen: Int) = id + ":" + gen

    /** A request this phone originated. */
    fun originate(st: State, r: NetRequest.Request): State =
        st.copy(requests = st.requests + (r.id to r), mine = st.mine + r.id)

    /** A request arrived from [fromPeer] (or "brain"). The signature is checked here, always. */
    fun receive(st: State, r: NetRequest.Request, now: Long, fromPeer: String): Pair<State, Receipt> {
        val s = st.stats.copy(received = st.stats.received + 1)
        if (!NetRequest.verify(r)) return st.copy(stats = s.copy(badSignature = s.badSignature + 1)) to Receipt.INVALID_SIGNATURE
        val cur = st.requests[r.id]
        if (cur != null) {
            if (r.generation < cur.generation) return st.copy(stats = s.copy(dedupDrops = s.dedupDrops + 1)) to Receipt.OLDER_GENERATION
            if (r.generation == cur.generation) return st.copy(stats = s.copy(dedupDrops = s.dedupDrops + 1)) to Receipt.DUPLICATE
            // a newer generation: a tombstone always wins; an open state can never replace a tombstone
            if (cur.tombstone && !r.tombstone) return st.copy(stats = s.copy(dedupDrops = s.dedupDrops + 1)) to Receipt.RESURRECTION_BLOCKED
        }
        if (!r.tombstone && r.expired(now)) return st.copy(stats = s.copy(ttlDrops = s.ttlDrops + 1)) to Receipt.EXPIRED
        if (!r.tombstone && r.hops >= NetRequest.MAX_HOPS) return st.copy(stats = s.copy(hopDrops = s.hopDrops + 1)) to Receipt.HOPS_EXHAUSTED
        // the sender already has this generation: never forward it back
        val fwd = st.forwarded + (key(r.id, r.generation, fromPeer) to now)
        val next = st.copy(requests = st.requests + (r.id to r), forwarded = fwd, stats = s.copy(accepted = s.accepted + 1))
        return next to (if (cur == null) Receipt.NEW else Receipt.NEWER_GENERATION)
    }

    /** What to hand to [peer] now: every live generation it has not seen, from someone else, with hops to spare. */
    fun toForward(st: State, peer: String, now: Long): List<NetRequest.Request> =
        st.requests.values.filter { r ->
            r.originShort != peer &&
                st.forwarded[key(r.id, r.generation, peer)] == null &&
                (r.tombstone || (!r.expired(now) && r.hops < NetRequest.MAX_HOPS - 1)) &&
                (!r.tombstone || now - r.updatedAt < TOMBSTONE_KEEP_MS)
        }.sortedBy { it.createdAt }

    fun unmark(st: State, r: NetRequest.Request, peer: String): State = st.copy(forwarded = st.forwarded - key(r.id, r.generation, peer))

    fun markForwarded(st: State, r: NetRequest.Request, peer: String, now: Long): State =
        st.copy(forwarded = st.forwarded + (key(r.id, r.generation, peer) to now), stats = st.stats.copy(forwards = st.stats.forwards + 1))

    /** Live generations the brain has not received. */
    fun pendingUpload(st: State, now: Long): List<NetRequest.Request> =
        st.requests.values.filter { ukey(it.id, it.generation) !in st.uploaded && (it.tombstone || !it.expired(now)) }.sortedBy { it.createdAt }

    fun markUploaded(st: State, rs: List<NetRequest.Request>): State =
        st.copy(uploaded = st.uploaded + rs.map { ukey(it.id, it.generation) })

    /** The origin ends its own request; the tombstone replaces it and travels like any generation. */
    fun end(st: State, id: String, state: NetRequest.State, now: Long, signer: Signer): State? {
        val cur = st.requests[id] ?: return null
        if (cur.tombstone) return st
        val t = NetRequest.tombstone(cur, state, now, signer)
        return st.copy(requests = st.requests + (id to t))
    }

    /** Housekeeping: expired requests become EXPIRED tombstones kept a while; old tombstones and forward memory go. */
    fun sweep(st: State, now: Long): State {
        val keep = HashMap<String, NetRequest.Request>()
        for ((id, r) in st.requests) {
            if (r.tombstone || r.state == NetRequest.State.EXPIRED) { if (now - r.updatedAt < TOMBSTONE_KEEP_MS) keep[id] = r }
            else if (r.expired(now)) keep[id] = r.copy(state = NetRequest.State.EXPIRED, updatedAt = now)
            else keep[id] = r
        }
        val ids = keep.keys
        val fwd = st.forwarded.filter { (k, t) -> now - t < FORWARD_MEMORY_MS && k.substringBefore(':') in ids }
        val up = st.uploaded.filter { it.substringBefore(':') in ids }.toSet()
        return st.copy(requests = keep, forwarded = fwd, uploaded = up, mine = st.mine.filter { it in ids }.toSet())
    }

    fun active(st: State, now: Long): List<NetRequest.Request> = st.requests.values.filter { it.open && !it.expired(now) }
    fun carried(st: State, now: Long): List<NetRequest.Request> = active(st, now).filter { it.id !in st.mine }

    // ---- persistence --------------------------------------------------------------------------------------------

    fun encode(st: State): String {
        val sb = StringBuilder("V\t1\n")
        for (r in st.requests.values.sortedBy { it.createdAt }) sb.append("R\t").append(NetRequest.encodeLine(r)).append('\n')
        for ((k, t) in st.forwarded) sb.append("F\t").append(k).append('\t').append(t).append('\n')
        for (u in st.uploaded) sb.append("U\t").append(u).append('\n')
        for (m in st.mine) sb.append("M\t").append(m).append('\n')
        val s = st.stats
        sb.append("S\t").append(listOf(s.received, s.accepted, s.dedupDrops, s.ttlDrops, s.hopDrops, s.badSignature, s.forwards).joinToString("\t")).append('\n')
        return sb.toString()
    }

    fun decode(text: String): State {
        val reqs = HashMap<String, NetRequest.Request>(); val fwd = HashMap<String, Long>(); val up = HashSet<String>(); val mine = HashSet<String>()
        var stats = Stats()
        for (line in text.split('\n')) {
            if (line.length < 2) continue
            try {
                when (line[0]) {
                    'R' -> NetRequest.decodeLine(line.substring(2))?.let { reqs[it.id] = it }
                    'F' -> { val f = line.substring(2).split('\t'); fwd[f[0]] = f[1].toLong() }
                    'U' -> up += line.substring(2)
                    'M' -> mine += line.substring(2)
                    'S' -> { val f = line.substring(2).split('\t').map { it.toInt() }; stats = Stats(f[0], f[1], f[2], f[3], f[4], f[5], f[6]) }
                }
            } catch (_: Exception) {}
        }
        return State(reqs, fwd, up, mine, stats)
    }
}
