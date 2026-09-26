package net.prok.proknet.core

/**
 * v0.18.0: what the Brain's ledger says about this phone, read into something a screen can
 * show - and the words the screen is allowed to use.
 *
 * The product rule: the provider's screen says "Retrait demandé", "Envoi en cours" or
 * "Payé" ACCURATELY. A ledger cannot send money, so "Envoi en cours" means a treasurer
 * marked the transfer sent by hand, and "Payé" means the operator's own message (or the
 * treasurer's typed reference) confirmed it. The text for each state comes from ONE table
 * that the server also holds (server/tests/fixtures/withdrawal_states.txt); a state this
 * build does not know is shown as "En vérification", never as anything stronger.
 *
 * Pure: `MainActivity` gathers and draws, this decides. Parsing uses [BrainPayload]'s small
 * readers rather than org.json, so it runs in the JVM tests exactly as on the phone.
 */
object LedgerView {

    /** State -> what the screen says. Must equal ledger.WITHDRAWAL_TEXT on the server. */
    val WITHDRAWAL_TEXT: Map<String, String> = linkedMapOf(
        "REQUESTED" to "Retrait demandé",
        "APPROVED" to "Retrait demandé",
        "SENT" to "Envoi en cours",
        "PAID" to "Payé",
        "DENIED" to "Refusé",
        "CANCELLED" to "Annulé",
        "NEEDS_ATTENTION" to "En vérification",
    )
    const val UNKNOWN_TEXT = "En vérification"
    val OPEN_STATES = setOf("REQUESTED", "APPROVED", "SENT", "NEEDS_ATTENTION")

    class Withdrawal(
        val id: String, val amountCentimes: Long, val rail: String, val state: String,
        val amber: Boolean, val msisdnTail: String, val paidEvidence: String, val memo: String,
        val requestedAt: Long, val sentAt: Long, val paidAt: Long,
    ) {
        val open: Boolean get() = state in OPEN_STATES
        /** The one line the screen may show for this withdrawal. */
        val text: String get() = statusText(state)
        val cancellable: Boolean get() = state == "REQUESTED"
    }

    class Hold(val id: String, val amountCentimes: Long, val state: String, val sellerId: String)

    class View(
        val creditCentimes: Long, val heldCentimes: Long, val earnedCentimes: Long, val earnedLifetimeCentimes: Long,
        val withdrawableCentimes: Long, val withdrawMinCentimes: Long,
        val withdrawal: Withdrawal?, val hold: Hold?,
        val treasury: Boolean, val paymentsLive: Boolean, val fetchedAt: Long,
        /** v0.18.0 final: unspent credit that may go back to a number this customer paid from. */
        val refundableCentimes: Long = 0, val refundMinCentimes: Long = 0, val boundRails: List<String> = emptyList(),
    ) {
        /** A refund is offered only to somebody who topped up from a number, and only when nothing is open. */
        val canRefund: Boolean get() = !hasOpenWithdrawal && hold == null && boundRails.isNotEmpty() && refundableCentimes >= refundMinCentimes && refundMinCentimes > 0
        val hasOpenWithdrawal: Boolean get() = withdrawal?.open == true
        /** Retirer is offered only when it can succeed. */
        val canWithdraw: Boolean get() = !hasOpenWithdrawal && withdrawableCentimes >= withdrawMinCentimes && withdrawMinCentimes > 0
        val withdrawHint: String get() = when {
            hasOpenWithdrawal -> withdrawal!!.text + (if (withdrawal.amber) " · vérification en cours" else "")
            withdrawableCentimes < withdrawMinCentimes -> "Retrait possible à partir de " + Market.cfa(withdrawMinCentimes)
            else -> "Retirable maintenant : " + Market.cfa(withdrawableCentimes)
        }
    }

    /** Exactly one text per state; unknown states get the cautious one, never "Payé". */
    fun statusText(state: String): String = WITHDRAWAL_TEXT[state] ?: UNKNOWN_TEXT

    // ---- parsing -------------------------------------------------------------------------

    fun parse(text: String, now: Long): View? {
        if (text.isBlank() || !text.contains("\"credit\"")) return null
        val w = obj(text, "withdrawal")?.let { parseWithdrawal(it) }
        val h = obj(text, "hold")?.let { s ->
            Hold(BrainPayload.field(s, "hold_id"), num(s, "amount"), BrainPayload.field(s, "state"), BrainPayload.field(s, "seller_id"))
        }
        return View(
            creditCentimes = num(text, "credit"), heldCentimes = num(text, "held"),
            earnedCentimes = num(text, "earned"), earnedLifetimeCentimes = num(text, "earned_lifetime"),
            withdrawableCentimes = num(text, "withdrawable"), withdrawMinCentimes = num(text, "withdraw_min"),
            withdrawal = w, hold = h,
            treasury = BrainPayload.field(text, "treasury") == "true",
            paymentsLive = BrainPayload.field(text, "payments_live") == "true",
            fetchedAt = now,
            refundableCentimes = num(text, "refundable"), refundMinCentimes = num(text, "refund_min"),
            boundRails = Regex("\"bound_rails\"\\s*:\\s*\\[([^\\]]*)\\]").find(text)?.groupValues?.get(1)
                ?.split(',')?.map { it.trim().trim('"') }?.filter { it.isNotEmpty() } ?: emptyList(),
        )
    }

    fun parseWithdrawal(s: String): Withdrawal? {
        val id = BrainPayload.field(s, "id")
        if (id.isEmpty()) return null
        return Withdrawal(
            id = id, amountCentimes = num(s, "amount"), rail = BrainPayload.field(s, "rail"),
            state = BrainPayload.field(s, "state"), amber = BrainPayload.field(s, "amber") == "true",
            msisdnTail = BrainPayload.field(s, "msisdn_tail"), paidEvidence = BrainPayload.field(s, "paid_evidence"),
            memo = BrainPayload.field(s, "memo"), requestedAt = num(s, "requested_at"),
            sentAt = num(s, "sent_at"), paidAt = num(s, "paid_at"),
        )
    }

    /**
     * The `{...}` under [key] when it is a flat object, or null when it is `null` or absent.
     * Flat is all the ledger sends per object, and the reader refuses to guess past that.
     */
    fun obj(text: String, key: String): String? {
        val at = text.indexOf("\"" + key + "\"")
        if (at < 0) return null
        val colon = text.indexOf(':', at)
        if (colon < 0) return null
        var i = colon + 1
        while (i < text.length && text[i].isWhitespace()) i++
        if (i >= text.length || text[i] != '{') return null
        val end = text.indexOf('}', i)
        if (end < 0) return null
        return text.substring(i, end + 1)
    }

    /** Every flat `{...}` inside the array under [key]. */
    fun objects(text: String, key: String): List<String> {
        val at = text.indexOf("\"" + key + "\"")
        if (at < 0) return emptyList()
        val open = text.indexOf('[', at)
        if (open < 0) return emptyList()
        val close = text.indexOf(']', open)
        if (close < 0) return emptyList()
        return Regex("\\{[^{}]*\\}").findAll(text.substring(open + 1, close)).map { it.value }.toList()
    }

    fun num(text: String, key: String): Long = BrainPayload.field(text, key).toLongOrNull() ?: 0L

    // ---- the treasurer's screen ------------------------------------------------------------

    class QueueRow(
        val id: String, val payeeId: String, val rail: String, val msisdn: String, val amountCentimes: Long,
        val state: String, val requestedAt: Long, val sentAt: Long, val amber: Boolean, val memo: String,
        val kind: String = "WITHDRAWAL",
    ) {
        /** Which buttons this row may show. A SENT row is never offered "Marquer envoyé". */
        val actions: List<String> get() = when (state) {
            "REQUESTED" -> listOf("approve", "deny")
            "APPROVED" -> listOf("sent", "deny")
            "SENT" -> listOf("paid", "unsent")
            "NEEDS_ATTENTION" -> listOf("paid", "deny")
            else -> emptyList()
        }
        fun ageLine(now: Long): String {
            val h = ((now - requestedAt) / 3_600_000L).coerceAtLeast(0)
            return if (h < 1) "à l'instant" else if (h < 48) "il y a " + h + " h" else "il y a " + (h / 24) + " j"
        }
    }

    class Summary(
        val manualSendsPending: Int, val sentence: String, val sentUnconfirmed: Int, val needsAttention: Int,
        val topupsUnassigned: Int, val floatMtn: Long, val floatAirtel: Long, val liabilities: Long, val shortfall: Long,
        val paymentsLive: Boolean,
    )

    class Queue(val rows: List<QueueRow>, val summary: Summary)

    fun parseQueue(text: String): Queue? {
        val s = obj(text, "summary") ?: return null
        val summary = Summary(
            manualSendsPending = num(s, "manual_sends_pending").toInt(), sentence = BrainPayload.field(s, "sentence"),
            sentUnconfirmed = num(s, "sent_unconfirmed").toInt(), needsAttention = num(s, "needs_attention").toInt(),
            topupsUnassigned = num(s, "topups_unassigned").toInt(), floatMtn = num(s, "float_mtn"), floatAirtel = num(s, "float_airtel"),
            liabilities = num(s, "liabilities"), shortfall = num(s, "shortfall"), paymentsLive = BrainPayload.field(s, "payments_live") == "true",
        )
        val rows = objects(text, "rows").mapNotNull { r ->
            val id = BrainPayload.field(r, "id")
            if (id.isEmpty()) null else QueueRow(
                id = id, payeeId = BrainPayload.field(r, "payee_id"), rail = BrainPayload.field(r, "rail"),
                msisdn = BrainPayload.field(r, "msisdn"), amountCentimes = num(r, "amount"), state = BrainPayload.field(r, "state"),
                requestedAt = num(r, "requested_at"), sentAt = num(r, "sent_at"), amber = BrainPayload.field(r, "amber") == "true",
                memo = BrainPayload.field(r, "memo"), kind = BrainPayload.field(r, "kind").ifEmpty { "WITHDRAWAL" },
            )
        }
        return Queue(rows, summary)
    }

    /**
     * The per-rail reconciliation, as the treasurer reads it. "Doute" means the balance
     * the treasurer typed is BELOW what the ledger expects: a parsed message is then in
     * question, and the Brain approves nothing new until a check matches.
     */
    fun reconcileLines(text: String): String {
        val alert = BrainPayload.field(text, "alert") == "true"
        val parts = ArrayList<String>()
        for (rail in listOf("MTN", "AIRTEL")) {
            val r = obj(text, rail) ?: continue
            val typed = BrainPayload.field(r, "typed")
            val line = rail + " attendu " + Market.cfa(num(r, "expected")) +
                (if (typed.isEmpty() || typed == "null") " · solde jamais saisi" else " · saisi " + Market.cfa(num(r, "typed")) + " (écart " + Market.cfa(num(r, "delta")) + ")") +
                (if (BrainPayload.field(r, "doubt") == "true") " · DOUTE" else "") +
                (if (BrainPayload.field(r, "check_stale") == "true") " · contrôle à refaire" else "")
            parts.add(line)
        }
        return (if (alert) "ALERTE RAPPROCHEMENT - aucune approbation tant que les soldes ne concordent pas\n" else "Rapprochement OK\n") + parts.joinToString("\n")
    }

    /** The honest first line of the treasurer's screen, computed here so a test can hold it. */
    fun manualSendsLine(pending: Int): String =
        pending.toString() + " retrait" + (if (pending == 1) "" else "s") + " en attente = " +
            pending + " envoi" + (if (pending == 1) "" else "s") + " manuel" + (if (pending == 1) "" else "s")

    fun actionLabel(action: String): String = when (action) {
        "approve" -> "Approuver"
        "deny" -> "Refuser"
        "sent" -> "Marquer envoyé"
        "unsent" -> "Non envoyé"
        "paid" -> "Confirmer payé"
        else -> action
    }
}
