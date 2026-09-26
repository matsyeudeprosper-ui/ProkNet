package net.prok.proknet.core

/**
 * v0.15.1: what the Wallet screen shows, decided here rather than in the Activity.
 *
 * The engineering underneath works. The Wallet has to stop looking like engineering. A
 * person opening it should answer four questions without reading anything twice:
 *
 *   What do I owe?   What am I owed?   What has been paid?   What should I do now?
 *
 * So this file makes three promises that the tests enforce:
 *
 * 1. **One obvious action.** Not six equal boxes. [primaryAction] returns exactly one
 *    thing to do, chosen from the state, and it is never an empty PAY button.
 * 2. **No technical clutter.** No PENDING, no PAYMENT_SEEN, no settlement id, no
 *    checkpoint hash, no raw 32-character identity, no CFA per megabyte. Those live in
 *    an advanced section of the detail sheet, and nowhere else.
 * 3. **Honest money words.** Never "Solde", never a guarantee, and "Payé" only when a
 *    payment was actually verified.
 */
object WalletUi {

    // ---- identity ------------------------------------------------------------------------------

    /**
     * `24e480e6a1b2...` is correct and unreadable. A person needs something they can
     * recognise and say out loud, so the Wallet shows "Prok 24E4" and keeps the full
     * identity for the detail sheet.
     *
     * Deliberately not a made-up name: inventing one would imply a profile we do not
     * have. When real names arrive this is the one place that changes.
     */
    fun shortName(prokId: String): String {
        val clean = prokId.removePrefix("prok-").trim()
        if (clean.length < 4) return "Prok ?"
        return "Prok " + clean.substring(0, 4).uppercase()
    }

    /** The full identity, for the advanced section only. */
    fun fullName(prokId: String): String = "prok-" + prokId.removePrefix("prok-")

    // ---- status ---------------------------------------------------------------------------------

    /** What a person reads instead of a status enum. */
    fun chip(status: Settlement.Status, iAmSeller: Boolean): String = when (status) {
        Settlement.Status.PENDING -> "En attente"
        Settlement.Status.PAYMENT_INITIATED -> "En attente"
        Settlement.Status.PAYMENT_SEEN -> "À vérifier"
        Settlement.Status.CONFIRMED -> if (iAmSeller) "Reçu ✓" else "Payé ✓"
        Settlement.Status.FAILED -> "Échoué"
        Settlement.Status.EXPIRED -> "Expiré"
        Settlement.Status.DISPUTED -> "Contesté"
    }

    /** Three tones, so a status reads at a glance without a rainbow of colours. */
    enum class Tone { NEUTRAL, GOOD, ATTENTION }

    fun tone(status: Settlement.Status): Tone = when (status) {
        Settlement.Status.CONFIRMED -> Tone.GOOD
        Settlement.Status.FAILED, Settlement.Status.EXPIRED, Settlement.Status.DISPUTED -> Tone.ATTENTION
        else -> Tone.NEUTRAL
    }

    /** The long form, for the detail sheet. */
    fun statusSentence(status: Settlement.Status, iAmSeller: Boolean): String = when (status) {
        Settlement.Status.PENDING -> if (iAmSeller) "En attente de paiement" else "En attente de paiement"
        Settlement.Status.PAYMENT_INITIATED -> "Paiement en cours"
        Settlement.Status.PAYMENT_SEEN -> "Référence envoyée, en attente de vérification"
        Settlement.Status.CONFIRMED -> if (iAmSeller) "Paiement reçu" else "Paiement effectué"
        Settlement.Status.FAILED -> "Le paiement a échoué"
        Settlement.Status.EXPIRED -> "Le délai de paiement est dépassé"
        Settlement.Status.DISPUTED -> "Montant en cours de vérification"
    }

    // ---- the overview ---------------------------------------------------------------------------

    /**
     * Which figure gets the strongest typography. Only one may lead, and the one that
     * needs action always wins: three equal statistics tell a person nothing.
     */
    enum class Lead {
        /** Money is owed. Nothing outranks that. */
        TO_PAY,

        /** Nothing owed, but money is coming. */
        TO_RECEIVE,

        /** Nothing needs action, so the informational figure leads instead of a row of zeros. */
        EARNED,

        /** A brand new wallet: nothing to lead with at all. */
        NOTHING,
    }

    class Overview(
        val toPay: String,
        val toReceive: String,
        val earnedToday: String,
        val lead: Lead,
    )

    fun overview(w: Wallet.View): Overview = Overview(
        toPay = Market.cfa(w.toPayCentimes),
        toReceive = Market.cfa(w.toReceiveCentimes),
        earnedToday = Market.cfa(w.earnedTodayCentimes),
        lead = when {
            w.toPayCentimes > 0 -> Lead.TO_PAY          // debt always leads: it needs action
            w.toReceiveCentimes > 0 -> Lead.TO_RECEIVE
            w.earnedTodayCentimes > 0 -> Lead.EARNED    // nothing to do, so show what went well
            else -> Lead.NOTHING
        })

    // ---- the one action -------------------------------------------------------------------------

    enum class ActionKind { PAY, ALL_CLEAR, AWAITING_PAYMENT, SET_UP_RECEIVING, NOTHING_YET }

    class Action(
        val kind: ActionKind,
        val title: String,
        val detail: String,
        /** Empty when the action is not a button. */
        val button: String = "",
        /** Who to pay, when [kind] is PAY. */
        val counterpartyId: String = "",
        val amountCentimes: Long = 0,
        /** "Voir les autres paiements", when more than one creditor exists. */
        val secondary: String = "",
    )

    /**
     * Exactly one thing to do. The order is the order of urgency: money I owe, then money
     * I cannot receive because nothing is set up, then money owed to me, then nothing.
     */
    fun primaryAction(
        obligations: List<Settlement.Obligation>,
        myId: String,
        w: Wallet.View,
        hasReceivingMethod: Boolean,
        isSeller: Boolean,
        /** v0.18.0: false in the product - nobody is ever asked to pay a person. */
        directPay: Boolean = false,
    ): Action {
        val creditors = if (!directPay) emptyList() else obligations
            .filter { it.buyerId == myId && Settlement.isOutstanding(it.status) }
            .groupBy { it.sellerId }
            .map { (seller, list) -> Triple(seller, list.sumOf { it.buyerOwes }, list.size) }
            .sortedByDescending { it.second }

        if (creditors.isNotEmpty()) {
            val (seller, amount, count) = creditors.first()
            return Action(
                kind = ActionKind.PAY,
                title = "Paiement à effectuer",
                detail = "Vous devez " + Market.cfa(amount) + " à " + shortName(seller) +
                    (if (count > 1) "\n" + count + " sessions sont regroupées dans ce paiement." else ""),
                button = "Payer " + Market.cfa(amount),
                counterpartyId = seller,
                amountCentimes = amount,
                secondary = if (creditors.size > 1) "Voir les autres paiements" else "")
        }

        if (isSeller && !hasReceivingMethod)
            return Action(ActionKind.SET_UP_RECEIVING, "Commencez à gagner",
                "Ajoutez un moyen de réception pour recevoir l'argent gagné avec ProkNet.",
                button = "Configurer")

        if (w.toReceiveCentimes > 0)
            return Action(ActionKind.AWAITING_PAYMENT, "À recevoir",
                Market.cfa(w.toReceiveCentimes) + " en attente", secondary = "Voir les paiements")

        if (obligations.none { it.buyerId == myId || it.sellerId == myId })
            return Action(ActionKind.NOTHING_YET, "Aucun paiement pour le moment",
                "Quand vous achetez ou partagez Internet, vos paiements apparaîtront ici.")

        return Action(ActionKind.ALL_CLEAR, "Tout est à jour", "Aucun paiement en attente.")
    }

    // ---- receiving ------------------------------------------------------------------------------

    class Receiving(val configured: Boolean, val title: String, val detail: String, val button: String)

    fun receiving(d: PaymentRails.Destination?): Receiving =
        if (d == null || !d.valid)
            Receiving(false, "Recevoir de l'argent",
                "Ajoutez votre numéro Mobile Money pour recevoir vos paiements.", "Configurer")
        else
            Receiving(true, railName(d.rail), d.masked(), "Modifier")

    fun railName(r: Settlement.Rail): String = when (r) {
        Settlement.Rail.MTN_MOMO -> "MTN Mobile Money"
        Settlement.Rail.AIRTEL_MONEY -> "Airtel Money"
        Settlement.Rail.MANUAL_PILOT -> "Paiement direct"
        Settlement.Rail.MOCK -> "Paiement simulé"
        Settlement.Rail.NONE -> "Mobile Money"
    }

    /** One sentence, not a paragraph. */
    const val PRIVACY_NOTE = "Votre numéro n'est jamais diffusé aux téléphones autour de vous."

    /** The note under the figures. It exists to stop anybody reading them as a balance. */
    const val CUSTODY_NOTE = "Ce sont des montants à régler entre personnes. Prok ne détient pas votre argent."

    // ---- history ---------------------------------------------------------------------------------

    class Row(
        val settlementId: String,
        val title: String,
        val counterparty: String,
        val amount: String,
        val positive: Boolean,
        val chip: String,
        val tone: Tone,
    )

    class Group(val label: String, val rows: List<Row>)

    fun dayLabel(at: Long, now: Long): String {
        val today = Wallet.startOfDay(now)
        val yesterday = today - 24L * 3600 * 1000
        return when {
            at >= today -> "Aujourd'hui"
            at >= yesterday -> "Hier"
            else -> {
                val days = ((today - at) / (24L * 3600 * 1000)) + 1
                "Il y a " + days + " jours"
            }
        }
    }

    /** Money events only. What happened on the network belongs in Activité. */
    fun history(obligations: List<Settlement.Obligation>, myId: String, now: Long, limit: Int = 50): List<Group> {
        val mine = obligations
            .filter { it.buyerId == myId || it.sellerId == myId }
            .sortedByDescending { it.createdAt }
            .take(limit)
        val out = ArrayList<Group>()
        var label = ""
        var rows = ArrayList<Row>()
        for (o in mine) {
            val l = dayLabel(o.createdAt, now)
            if (l != label) {
                if (rows.isNotEmpty()) out.add(Group(label, rows))
                label = l; rows = ArrayList()
            }
            val seller = o.sellerId == myId
            val amount = if (seller) o.sellerReceivable else o.buyerOwes
            rows.add(Row(
                settlementId = o.settlementId,
                title = if (seller) "Internet partagé" else "Internet",
                counterparty = shortName(if (seller) o.buyerId else o.sellerId),
                amount = (if (seller) "+" else "-") + Market.cfa(amount),
                positive = seller,
                chip = chip(o.status, seller),
                tone = tone(o.status)))
        }
        if (rows.isNotEmpty()) out.add(Group(label, rows))
        return out
    }

    // ---- one transaction ---------------------------------------------------------------------------

    class Detail(
        val title: String,
        val amount: String,
        val statusLabel: String,
        val statusValue: String,
        val withLabel: String,
        val withValue: String,
        /** Label/value pairs a normal person understands. */
        val lines: List<Pair<String, String>>,
        val action: String,
        /** settlement id, session, checkpoint, rail reference: hidden unless asked for. */
        val advanced: List<Pair<String, String>>,
    )

    /**
     * @param serverState v0.15.3: how far this settlement got with the server. It appears
     *        in the advanced section and nowhere else: a consumer card must never talk
     *        about servers, and an obligation that has not reached one is not "wrong".
     */
    fun detail(o: Settlement.Obligation, myId: String, budgetCentimes: Long, whenText: String,
               serverState: String = ""): Detail {
        val seller = o.sellerId == myId
        val lines = ArrayList<Pair<String, String>>()
        if (!seller && budgetCentimes > 0) lines.add("Budget maximum" to Market.cfa(budgetCentimes))
        lines.add("Utilisé" to Market.cfa(o.grossCentimes))
        lines.add("Frais Prok" to Market.cfa(o.prokFeeCentimes))
        lines.add(("Le vendeur reçoit") to Market.cfa(o.sellerNetCentimes))
        val action = when {
            Settlement.isPaid(o.status) -> if (seller) "Reçu ✓" else "Payé ✓"
            o.status == Settlement.Status.PAYMENT_SEEN -> "En attente de vérification"
            seller -> ""
            else -> "Payer " + Market.cfa(o.buyerOwes)
        }
        return Detail(
            title = if (seller) "Internet partagé" else "Internet",
            amount = Market.cfa(if (seller) o.sellerReceivable else o.buyerOwes),
            statusLabel = "Statut",
            statusValue = statusSentence(o.status, seller),
            withLabel = "Avec",
            withValue = shortName(if (seller) o.buyerId else o.sellerId),
            lines = lines,
            action = action,
            advanced = listOf(
                "Identifiant de règlement" to o.settlementId,
                "Session" to o.sessionHex,
                "Preuve d'usage" to o.finalCheckpointHash,
                "Référence" to o.paymentReference.ifEmpty { "—" },
                "Vérification serveur" to serverState.ifEmpty { "—" },
                "Identité complète" to fullName(if (seller) o.buyerId else o.sellerId),
                "Date" to whenText))
    }

    // ---- the payment sheet ----------------------------------------------------------------------

    class PaymentSheet(
        val title: String,
        val toLabel: String,
        val toValue: String,
        val methodLabel: String,
        val methodValue: String,
        val grouping: String,
        val sessions: List<Pair<String, String>>,
        val button: String,
    )

    fun paymentSheet(due: List<Settlement.Obligation>, sellerId: String, railName: String): PaymentSheet {
        val total = due.sumOf { it.buyerOwes }
        return PaymentSheet(
            title = "Payer " + Market.cfa(total),
            toLabel = "À", toValue = shortName(sellerId),
            methodLabel = "Moyen de paiement", methodValue = railName,
            grouping = if (due.size > 1) due.size.toString() + " sessions Internet sont regroupées dans ce paiement." else "",
            sessions = due.mapIndexed { i, o -> ("Session " + (i + 1)) to Market.cfa(o.buyerOwes) },
            button = "Continuer")
    }

    /** What the buyer reads after giving a reference. Careful wording: this is not "paid". */
    fun referenceAccepted(reference: String): String =
        "Paiement envoyé\n\nRéférence : " + reference +
            "\n\nEn attente de vérification. Nous ne marquerons pas ce paiement comme reçu avant vérification."

    fun payInstruction(amount: Long, masked: String): String =
        "Envoyez " + Market.cfa(amount) + " à :\n" + masked + "\n\nPuis entrez la référence de transaction."

    /** A reference already used for another payment must not be silently reused. */
    const val REFERENCE_IN_USE = "Cette référence est déjà utilisée."
}
