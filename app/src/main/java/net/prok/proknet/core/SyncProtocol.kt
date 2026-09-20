package net.prok.proknet.core

/**
 * v0.13: one batched, signed, idempotent sync with the Network Brain.
 * "prok-sync/1", a line protocol (tab-separated, the same style as the
 * local codecs), so the Python server parses it with no library.
 *
 * Upload lines (client -> server):
 *   V 1                         version
 *   N nodeIdHex pubHex tsMs     who, and when (the server bounds the clock)
 *   Z zone                      the client's coarse zone (or z?)
 *   C key kind zone lastSeen validated price trust observations   coverage summary (opt-in only)
 *   P zone potential sharing upstream price busy capable            availability heartbeat (opt-in only)
 *   R <NetRequest line>         a request generation (open or tombstone)
 *   J jobId state ts            a job state change
 *   S sigHex                    ECDSA P-256 / SHA-256 over every byte before this line
 *
 * Download lines (server -> client):
 *   V 1 serverTsMs
 *   X zone status direct potential bestPrice lastSeen observers   shared cell (status is YELLOW at most)
 *   R <NetRequest line>         a relevant request (verified again on receipt)
 *   J jobId type zone requestId state expiresAt                    a job for this node (an activation opportunity)
 *   Q requestId state generation                                   a request status the brain knows
 *   A text                      one line of advice, for the diagnostic
 */
object SyncProtocol {
    const val VERSION = 1
    const val CLOCK_SKEW_MS = 10 * 60_000L

    class CoverageLine(val key: String, val kind: String, val zone: String, val lastSeen: Long, val validated: Boolean, val priceCentimesPerMb: Int, val trust: String, val observations: Int)

    class Upload(
        val nodeIdHex: String, val pubHex: String, val now: Long, val zone: String,
        val coverage: List<CoverageLine>, val availability: ProviderActivation.Availability?,
        val requests: List<NetRequest.Request>, val jobChanges: List<Triple<String, Jobs.State, Long>>,
    )

    private fun esc(s: String) = s.replace("\t", " ").replace("\n", " ")

    fun body(u: Upload): String {
        val sb = StringBuilder()
        sb.append("V\t").append(VERSION).append('\n')
        sb.append("N\t").append(u.nodeIdHex).append('\t').append(u.pubHex).append('\t').append(u.now).append('\n')
        sb.append("Z\t").append(esc(u.zone)).append('\n')
        for (c in u.coverage) sb.append("C\t").append(listOf(c.key, c.kind, esc(c.zone), c.lastSeen, c.validated, c.priceCentimesPerMb, c.trust, c.observations).joinToString("\t")).append('\n')
        u.availability?.let { a -> sb.append("P\t").append(listOf(esc(a.zone), a.potential, a.sharing, a.upstreamType, a.priceCentimesPerMb, a.busy, a.capable).joinToString("\t")).append('\n') }
        for (r in u.requests) sb.append("R\t").append(NetRequest.encodeLine(r)).append('\n')
        for ((id, st, ts) in u.jobChanges) sb.append("J\t").append(esc(id)).append('\t').append(st.name).append('\t').append(ts).append('\n')
        return sb.toString()
    }

    /** The whole message: the body, then the signature over its bytes. */
    fun signed(u: Upload, signer: Signer): String {
        val b = body(u)
        return b + "S\t" + signer.sign(b.toByteArray(Charsets.UTF_8)).toHex() + "\n"
    }

    /** Split a signed message into (body, signatureHex); null when the last line is not a signature. */
    fun split(message: String): Pair<String, String>? {
        val i = message.lastIndexOf("S\t")
        if (i < 0 || (i > 0 && message[i - 1] != '\n')) return null
        val sig = message.substring(i + 2).trim()
        return message.substring(0, i) to sig
    }

    /** Verify a signed message the way the server does: the signature, the identity behind it, and the clock. */
    fun verify(message: String, now: Long): Boolean {
        val (body, sig) = split(message) ?: return false
        val n = body.split('\n').firstOrNull { it.startsWith("N\t") }?.split('\t') ?: return false
        if (n.size < 4) return false
        val pub = try { n[2].hexToBytes() } catch (_: Exception) { return false }
        if (Crypto.deriveId(pub).toHex() != n[1].lowercase()) return false
        val ts = n[3].toLongOrNull() ?: return false
        if (kotlin.math.abs(now - ts) > CLOCK_SKEW_MS) return false
        return Crypto.verify(pub, body.toByteArray(Charsets.UTF_8), try { sig.hexToBytes() } catch (_: Exception) { return false })
    }

    // ---- the download -------------------------------------------------------------------------------------------

    class SharedCell(val zone: String, val status: Coverage.ZoneStatus, val direct: Int, val potential: Int, val bestPrice: Int, val lastSeen: Long, val observers: Int)
    class JobLine(val id: String, val type: Jobs.Type, val zone: String, val requestId: String, val state: Jobs.State, val expiresAt: Long)
    class StatusLine(val requestId: String, val state: NetRequest.State, val generation: Int)

    class Download(val serverTime: Long, val cells: List<SharedCell>, val requests: List<NetRequest.Request>, val jobs: List<JobLine>, val statuses: List<StatusLine>, val advice: List<String>)

    /**
     * Shared history is never GREEN on the client: the brain saying "seen 5 min
     * ago" does not make this phone able to connect. GREEN is only for a source
     * this phone reaches now.
     */
    fun parseDownload(text: String): Download? {
        var serverTime = 0L
        val cells = ArrayList<SharedCell>(); val reqs = ArrayList<NetRequest.Request>(); val jobs = ArrayList<JobLine>(); val st = ArrayList<StatusLine>(); val advice = ArrayList<String>()
        var versionSeen = false
        for (line in text.split('\n')) {
            if (line.length < 2) continue
            val f = line.split('\t')
            try {
                when (f[0]) {
                    "V" -> { if (f[1].toInt() != VERSION) return null; serverTime = f.getOrNull(2)?.toLongOrNull() ?: 0L; versionSeen = true }
                    "X" -> cells += SharedCell(f[1], if (Coverage.ZoneStatus.valueOf(f[2]) == Coverage.ZoneStatus.RED) Coverage.ZoneStatus.RED else Coverage.ZoneStatus.YELLOW,
                        0, f[4].toInt() + f[3].toInt(), f[5].toInt(), f[6].toLong(), f[7].toInt())
                    "R" -> NetRequest.decodeLine(line.substring(2))?.let { reqs += it }
                    "J" -> jobs += JobLine(f[1], Jobs.Type.valueOf(f[2]), f[3], f[4], Jobs.State.valueOf(f[5]), f[6].toLong())
                    "Q" -> st += StatusLine(f[1], NetRequest.State.valueOf(f[2]), f[3].toInt())
                    "A" -> advice += f.drop(1).joinToString(" ")
                }
            } catch (_: Exception) { /* one bad line never loses the rest */ }
        }
        return if (versionSeen) Download(serverTime, cells, reqs, jobs, st, advice) else null
    }

    /** Local coverage, summarised for the brain: keys only (hashed Wi-Fi ids, ProkNet ids), zones, freshness, price, trust. Never a position. */
    fun coverageLines(sources: Collection<CoverageModel.Source>, now: Long, maxAgeMs: Long = CoverageModel.RECENT_MS): List<CoverageLine> =
        sources.filter { now - it.lastSeen <= maxAgeMs }.map { s ->
            CoverageLine(s.id, s.kind.name, s.lastZone, s.lastSeen, s.validated, s.priceCentimesPerMb, s.trust.name, s.observations)
        }
}
