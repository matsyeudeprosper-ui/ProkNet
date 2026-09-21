package net.prok.proknet.core

/**
 * v0.15.2: what the Activité screen shows.
 *
 * The old screen carried three money boxes, a note about custody, a payment card and a
 * receiving-method card — all of which now live in the Wallet, one tap away — and then
 * buried the account settings underneath them. Two screens were doing the same job, and
 * neither did it well.
 *
 * The split is now clean:
 *
 *   **Activité** answers *what happened*.
 *   **Wallet**   answers *what money needs attention*.
 *
 * So this file is a history and nothing else: one row per thing that happened, grouped by
 * day, with the amount as a quiet detail rather than the subject.
 */
object ActivityUi {

    /** What kind of thing happened. The icon and the wording follow from this. */
    enum class Kind { BOUGHT, SHARED, CARRIED }

    class Row(
        val sessionHex: String,
        val kind: Kind,
        val title: String,
        /** Who it was with, and when. One line. */
        val subtitle: String,
        /** "+11 CFA", "-12 CFA", or "Gratuit". */
        val amount: String,
        val positive: Boolean,
        val chip: String,
        val tone: WalletUi.Tone,
    )

    class Group(val label: String, val rows: List<Row>)

    fun title(kind: Kind): String = when (kind) {
        Kind.BOUGHT -> "Internet utilisé"
        Kind.SHARED -> "Internet partagé"
        Kind.CARRIED -> "Message transporté"
    }

    /**
     * The amount, from the point of view of the person reading. A free session says so
     * rather than showing a zero, because "0 CFA" reads like something went wrong.
     */
    fun amountWord(centimes: Long, earning: Boolean): String = when {
        centimes <= 0 -> "Gratuit"
        earning -> "+" + Market.cfa(centimes)
        else -> "-" + Market.cfa(centimes)
    }

    /**
     * One line: who, and when. The counterparty is the friendly short name, never a raw
     * identity, and never an accounting term.
     */
    fun subtitle(peerName: String, timeWord: String): String =
        listOf(peerName, timeWord).filter { it.isNotEmpty() }.joinToString(" · ")

    /**
     * Where a session stands. Reuses the Wallet's words so the two screens never
     * disagree about the same fact.
     */
    fun chip(settled: Boolean, free: Boolean, iAmSeller: Boolean): String = when {
        free -> "Gratuit"
        settled -> if (iAmSeller) "Reçu ✓" else "Payé ✓"
        else -> "En attente"
    }

    fun tone(settled: Boolean, free: Boolean): WalletUi.Tone = when {
        free -> WalletUi.Tone.NEUTRAL
        settled -> WalletUi.Tone.GOOD
        else -> WalletUi.Tone.NEUTRAL
    }

    /** Group rows under "Aujourd'hui", "Hier", and so on. Same labels as the Wallet. */
    fun group(rows: List<Pair<Long, Row>>, now: Long): List<Group> {
        val out = ArrayList<Group>()
        var label = ""
        var current = ArrayList<Row>()
        for ((at, row) in rows.sortedByDescending { it.first }) {
            val l = WalletUi.dayLabel(at, now)
            if (l != label) {
                if (current.isNotEmpty()) out.add(Group(label, current))
                label = l; current = ArrayList()
            }
            current.add(row)
        }
        if (current.isNotEmpty()) out.add(Group(label, current))
        return out
    }

    const val EMPTY_TITLE = "Rien pour le moment"
    const val EMPTY_BODY = "Vos connexions et vos partages apparaîtront ici."

    /** The account section is a separate concern, so it gets its own heading and rhythm. */
    const val ACCOUNT_TITLE = "Compte"
}
