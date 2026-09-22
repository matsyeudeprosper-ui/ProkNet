package net.prok.proknet.core

/**
 * v0.13.3: the provider's demand inbox, pure. One source of truth behind both
 * the notification and the Gagner card.
 *
 * Two phone bugs came from having neither:
 *
 *  - a second, different buyer request was swallowed by a global two-minute
 *    cooldown ("activation ... rate-limited"), so real demand disappeared;
 *  - the only place demand appeared was a push notification, so a dismissed,
 *    missed or forbidden notification lost the request entirely.
 *
 * So notification is an alert, not the truth. The truth is the set of active
 * opportunities: each is notified once, by id, and anything that ends it
 * (tombstone, expiry, acceptance) removes it from both places at once.
 */
object ProviderInbox {

    enum class Source { LOCAL, BRAIN }

    data class Opportunity(
        val requestId: String,
        val originShort: String,
        val source: Source,
        val zone: String,
        val receivedAt: Long,
        val expiresAt: Long,
        val generation: Int,
        /** 0 = never alerted. An opportunity is alerted once. */
        val notifiedAt: Long = 0,
        /** The provider pressed PARTAGER for it. */
        val accepted: Boolean = false,
        /**
         * v0.17.2: the Brain activation this came from, for [Source.BRAIN] only.
         *
         * Without it, pressing PARTAGER could start the seller and leave the Brain
         * showing OFFERED for ever - the buyer would wait on "un fournisseur se prépare"
         * that never arrives, because nothing knew which activation to acknowledge.
         *
         * Empty for a local opportunity, which has no activation behind it.
         */
        val brainActivationId: String = "",
    ) {
        val local: Boolean get() = source == Source.LOCAL
        fun expired(now: Long): Boolean = now >= expiresAt

        /** Accepted here, but the Brain has not been told yet. */
        fun needsBrainAck(): Boolean = accepted && brainActivationId.isNotEmpty()
    }

    data class State(
        val items: Map<String, Opportunity> = emptyMap(),
        /** Why the last alert was not shown, for the diagnostic. */
        val lastSuppressed: String = "",
        val lastNotifiedAt: Long = 0,
        val notifications: Int = 0,
    )

    // ---- what the provider is holding -------------------------------------------------------------------

    fun active(st: State, now: Long): List<Opportunity> =
        st.items.values.filter { !it.expired(now) }.sortedBy { it.receivedAt }

    fun localCount(st: State, now: Long): Int = active(st, now).count { it.local }
    fun brainCount(st: State, now: Long): Int = active(st, now).count { !it.local }
    fun oldest(st: State, now: Long): Opportunity? = active(st, now).minByOrNull { it.receivedAt }
    fun anyLocal(st: State, now: Long): Boolean = localCount(st, now) > 0

    // ---- changes ------------------------------------------------------------------------------------------

    /**
     * A request this phone could serve. The same generation twice changes
     * nothing; a newer generation updates in place and never re-alerts.
     */
    /**
     * v0.17.2: the same offer, remembering which Brain activation produced it.
     *
     * Re-offering an activation the provider has already accepted must not quietly reset
     * that - a phone whose acceptance has not reached the Brain yet will see the job come
     * back as OFFERED on the next poll, and forgetting would lose the tap.
     */
    fun offerFromBrain(st: State, r: NetRequest.Request, activationId: String, now: Long): State {
        val existing = st.items[r.id]
        val next = offer(st, r, Source.BRAIN, now)
        val o = next.items[r.id] ?: return next
        return next.copy(items = next.items + (r.id to o.copy(
            brainActivationId = activationId,
            accepted = o.accepted || (existing?.accepted ?: false))))
    }

    fun offer(st: State, r: NetRequest.Request, source: Source, now: Long): State {
        val cur = st.items[r.requestKey()]
        if (cur != null && r.generation <= cur.generation) return st
        val o = Opportunity(r.id, r.originShort, source, r.zone, cur?.receivedAt ?: now, r.expiresAt, r.generation,
            notifiedAt = cur?.notifiedAt ?: 0, accepted = cur?.accepted ?: false)
        return st.copy(items = st.items + (r.id to o))
    }

    /** The request ended (tombstone), was served elsewhere, or the provider is no longer able. */
    fun remove(st: State, requestId: String): State =
        if (requestId in st.items) st.copy(items = st.items - requestId) else st

    fun accept(st: State, requestId: String, now: Long): State =
        st.items[requestId]?.let { st.copy(items = st.items + (requestId to it.copy(accepted = true))) } ?: st

    /** Expired opportunities leave by themselves: no stale demand card, ever. */
    fun sweep(st: State, now: Long): State {
        val keep = st.items.filterValues { !it.expired(now) }
        return if (keep.size == st.items.size) st else st.copy(items = keep)
    }

    // ---- the alert ------------------------------------------------------------------------------------------

    /**
     * What a single alert should say right now, or null when there is nothing
     * new to say. Based on the active set, never on a wall clock: a brand new
     * request from another buyer always gets through, a duplicate never does.
     */
    data class Alert(val title: String, val text: String, val count: Int, val requestIds: List<String>, val local: Boolean)

    fun pending(st: State, now: Long): List<Opportunity> = active(st, now).filter { it.notifiedAt == 0L && !it.accepted }

    fun alert(st: State, now: Long): Alert? {
        val fresh = pending(st, now)
        if (fresh.isEmpty()) return null
        // one alert for everything that is waiting, not one per request
        val all = active(st, now).filter { !it.accepted }
        val local = all.any { it.local }
        return Alert(title(all.size, local), TEXT, all.size, fresh.map { it.requestId }, local)
    }

    /** Mark exactly the ids that were alerted; the rest stay pending. */
    fun noted(st: State, ids: List<String>, now: Long): State {
        var items = st.items
        for (id in ids) items[id]?.let { items = items + (id to it.copy(notifiedAt = now)) }
        return st.copy(items = items, lastNotifiedAt = now, notifications = st.notifications + 1, lastSuppressed = "")
    }

    /** The alert could not be shown (no permission, for example). The opportunity stays. */
    fun suppressed(st: State, why: String): State = st.copy(lastSuppressed = why)

    const val TEXT = "Vous pouvez partager votre connexion et gagner des CFA."

    fun title(count: Int, local: Boolean): String = when {
        count <= 0 -> ""
        local && count == 1 -> "Quelqu'un cherche Internet à proximité."
        local -> count.toString() + " personnes cherchent Internet à proximité."
        count == 1 -> "Une demande Internet existe dans votre zone."
        else -> count.toString() + " demandes Internet existent dans votre zone."
    }

    // ---- the Gagner card ---------------------------------------------------------------------------------------

    /** The card's headline: the same honesty rule as the alert. */
    fun cardTitle(st: State, now: Long): String {
        val all = active(st, now)
        if (all.isEmpty()) return ""
        val local = all.any { it.local }
        return when {
            local && all.size == 1 -> "1 personne cherche Internet"
            local -> all.size.toString() + " personnes cherchent Internet"
            all.size == 1 -> "Une demande Internet dans votre zone"
            else -> all.size.toString() + " demandes Internet dans votre zone"
        }
    }

    /** "À proximité · il y a 20 s" / "Dans votre zone · il y a 3 min". */
    fun cardSub(st: State, now: Long): String {
        val o = oldest(st, now) ?: return ""
        val age = now - o.receivedAt
        val when_ = if (age < 45_000) "maintenant" else CoverageModel.ageWord(age)
        return (if (anyLocal(st, now)) "À proximité" else "Dans votre zone") + " · " + when_
    }

    /** While already sharing: "2 autres personnes cherchent Internet", or "". */
    fun otherDemandLine(st: State, now: Long): String {
        val waiting = active(st, now).count { !it.accepted }
        return when {
            waiting <= 0 -> ""
            waiting == 1 -> "1 autre personne cherche Internet"
            else -> waiting.toString() + " autres personnes cherchent Internet"
        }
    }

    // ---- accepting -----------------------------------------------------------------------------------------------

    /** Why sharing cannot start for an opportunity the provider just tapped. One plain sentence, or null. */
    fun refusalSentence(refusal: ProviderActivation.Refusal?): String? = when (refusal) {
        null -> null
        ProviderActivation.Refusal.NO_INTERNET -> "Votre Internet n'est plus disponible."
        ProviderActivation.Refusal.BLUETOOTH_OFF -> "Activez le Bluetooth pour partager."
        ProviderActivation.Refusal.NO_LOCAL_PATH -> "Ce téléphone ne peut pas partager cette connexion."
        ProviderActivation.Refusal.REQUEST_NOT_OPEN -> "Cette demande n'est plus active."
        ProviderActivation.Refusal.ALREADY_SHARING -> "Vous partagez déjà votre Internet."
        ProviderActivation.Refusal.BUSY -> "Vous servez déjà quelqu'un."
        ProviderActivation.Refusal.ABOVE_CEILING -> "Votre prix est plus élevé que ce que cette personne peut payer."
        ProviderActivation.Refusal.NOT_OPTED_IN -> "Activez « Me prévenir quand quelqu'un cherche Internet »."
    }

    // ---- persistence: the inbox survives the process ----------------------------------------------------------------

    fun encode(st: State): String {
        val sb = StringBuilder("V\t1\n")
        for (o in st.items.values.sortedBy { it.receivedAt })
            sb.append("O\t").append(listOf(o.requestId, o.originShort, o.source.name, o.zone.replace("\t", " "), o.receivedAt, o.expiresAt,
                o.generation, o.notifiedAt, o.accepted, o.brainActivationId).joinToString("\t")).append('\n')
        return sb.toString()
    }

    fun decode(text: String): State {
        val items = HashMap<String, Opportunity>()
        for (line in text.split('\n')) {
            if (line.length < 2 || line[0] != 'O') continue
            try {
                val f = line.substring(2).split('\t')
                // v0.17.2 appended a tenth field. A file written by build 69 has nine and
                // must still load: an old install losing its inbox on upgrade would drop
                // requests a provider had already agreed to.
                if (f.size >= 9) items[f[0]] = Opportunity(
                    f[0], f[1], Source.valueOf(f[2]), f[3], f[4].toLong(), f[5].toLong(),
                    f[6].toInt(), f[7].toLong(), f[8].toBoolean(),
                    if (f.size >= 10) f[9] else "")
            } catch (_: Exception) { /* one bad line never loses the rest */ }
        }
        return State(items)
    }
}

/** The inbox keys by request id; the extension keeps the intent readable. */
fun NetRequest.Request.requestKey(): String = id
