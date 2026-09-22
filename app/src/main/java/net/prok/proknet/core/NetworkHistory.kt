package net.prok.proknet.core

/**
 * v0.17.1: the network's own events, as a person would read them in Activité.
 *
 * v0.17.0 had the sentences ([NetworkAccess.eventLine]) and the dedup key, and wrote
 * neither anywhere. This is the missing half: a small durable list, one line per real
 * transition.
 *
 * Why dedup is the whole design rather than an afterthought. A waiting buyer polls the
 * Brain every few seconds and the Brain keeps reporting the same status, so the naive
 * version produces
 *
 *     Recherche Internet
 *     Recherche Internet
 *     Recherche Internet
 *
 * until the screen is useless. So a row is keyed on the demand and the transition, and
 * the second arrival of a transition is silently dropped.
 *
 * Deliberately **not** in the SQLite database. Network coordination is not financial: it
 * has no signed evidence in it, losing it costs a reader some history and nothing else,
 * and `RequestGossip` and `ProviderInbox` already keep their state this way. Bumping the
 * database from 10 to 11 for this would have been a migration with nothing to migrate.
 */
object NetworkHistory {

    /** Enough rows that a day of use is readable; old ones fall off the end. */
    const val MAX_ROWS = 60

    class Row(val at: Long, val key: String, val kind: String) {
        /** The sentence Activité shows. Empty for anything not meant for a person. */
        val line: String get() = NetworkAccess.eventLine(kind)
    }

    class State(val rows: List<Row> = emptyList()) {
        /** Newest first, and only the ones that have something to say to a person. */
        val visible: List<Row> get() = rows.filter { it.line.isNotEmpty() }
            .sortedByDescending { it.at }
    }

    /**
     * Record one transition, or do nothing if it is already there.
     *
     * Returns the same [State] when nothing changed, so a caller can cheaply tell whether
     * the screen needs redrawing - a poll that learns nothing should not repaint anything.
     */
    fun note(st: State, demandId: String, kind: String, now: Long): State {
        if (demandId.isEmpty() || kind.isEmpty()) return st
        if (NetworkAccess.eventLine(kind).isEmpty()) return st   // diagnostics, not history
        val key = NetworkAccess.dedupKey(demandId, kind)
        if (st.rows.any { it.key == key }) return st
        val rows = ArrayList<Row>(st.rows)
        rows.add(Row(now, key, kind))
        // keep the newest, drop the oldest
        val kept = rows.sortedByDescending { it.at }.take(MAX_ROWS)
        return State(kept)
    }

    /** The status word the Brain reports, as the history kind it corresponds to. */
    fun kindForStatus(status: String): String = when (status) {
        "CREATED", "SEARCHING" -> "demand.created"
        "PROVIDER_FOUND", "ACTIVATION_SENT" -> "demand.provider_found"
        "PROVIDER_ACCEPTED" -> "demand.provider_accepted"
        "CONNECTED" -> "demand.connected"
        "CANCELLED" -> "demand.cancelled"
        "EXPIRED" -> "demand.expired"
        "FAILED" -> "demand.failed"
        else -> ""
    }

    /**
     * Fold a reported status in. One call per poll; nothing happens on a repeat.
     *
     * Note that SEARCHING and CREATED share a row: a demand that goes back to searching
     * after a provider declines is still the same "Recherche Internet" the reader already
     * saw, and telling them twice would suggest something new had begun.
     */
    fun onStatus(st: State, demandId: String, status: String, now: Long): State =
        note(st, demandId, kindForStatus(status), now)

    // ---- storage -----------------------------------------------------------------------

    private const val SEP = "\t"

    fun encode(st: State): String =
        st.rows.sortedBy { it.at }.joinToString("\n") {
            listOf(it.at.toString(), it.kind, it.key).joinToString(SEP)
        }

    /**
     * Read it back. A line that does not parse is skipped rather than throwing: a
     * truncated file from a phone that died mid-write must not stop the app opening.
     */
    fun decode(text: String): State {
        val rows = ArrayList<Row>()
        for (line in text.split("\n")) {
            if (line.isBlank()) continue
            val p = line.split(SEP)
            if (p.size < 3) continue
            val at = p[0].toLongOrNull() ?: continue
            rows.add(Row(at, p[2], p[1]))
        }
        return State(rows.sortedByDescending { it.at }.take(MAX_ROWS))
    }
}
