package net.prok.proknet.core

/**
 * v0.19.0: the owner's side of the end-to-end quote (contract §4 "Owner onboarding and
 * automatic pay rule", §6, §10.3).
 *
 * The product rule: **ProkNet sets the customer price and the owner's earning**; the owner
 * accepts or declines a SIGNED offer and never types a tariff. So the only thing that leaves
 * this object towards the Brain is an [Acceptance]: the quote's id, its signature, a role and
 * ACCEPT / DECLINE. There is no field for an amount, and the test holds that line.
 *
 * What the screen says, from the quote's own numbers: "le client paie X pour 100 Mo ; vous
 * gagnez Y ; relais Z ; Prok W", the examples at 200 MB and 1 GB, when the offer expires, what
 * happens if nobody uses it, and the dated bundle comparison with one honest word and no
 * savings promise. Totals use the SAME rounding as the Brain ([totalFor]); the fixture
 * server/tests/fixtures/quote_examples.json holds that line across the two languages.
 *
 * Pure: no android.* import; parsing through [BrainPayload] and [LedgerView], never org.json.
 */
object OwnerQuoteView {

    const val MB = 1_000_000L
    const val GB = 1_000L * MB

    /** Centimes for [bytes] at [rateCentimesPerMb] per decimal MB, rounded half up ONCE - exactly quotes.money on the Brain. */
    fun totalFor(rateCentimesPerMb: Long, bytes: Long): Long = (rateCentimesPerMb * bytes + MB / 2) / MB

    /** A percentage share of a total, floored - exactly the Brain's split arithmetic. */
    fun share(total: Long, pct: Int): Long = total * pct / 100

    /** Bytes in the words the owner reads: "100 Mo", "1 Go". */
    fun sizeWord(bytes: Long): String = when {
        bytes % GB == 0L -> (bytes / GB).toString() + " Go"
        bytes % MB == 0L -> (bytes / MB).toString() + " Mo"
        else -> String.format("%.1f Mo", bytes / MB.toDouble())
    }

    // ---- the comparison: one honest word, dated, no universal claim -----------------------------

    const val DISCLAIMER = "Comparaison datée ; les offres des opérateurs changent. Ce n'est pas une promesse d'économie."

    fun verdictWord(verdict: String): String = when (verdict) {
        "cheaper" -> "moins cher que"
        "not cheaper" -> "plus cher que"
        "comparable" -> "comparable à"
        else -> "à comparer avec"
    }

    class Comparison(val verdict: String, val bundleLabel: String, val bundlePriceCentimes: Long, val prokTotalCentimes: Long,
                     val asOf: String, val prorated: Boolean, val sentence: String) {
        /** The Brain's sentence when it sent one, else the same words built here. Never a promise. */
        fun text(): String = if (sentence.isNotEmpty()) sentence else
            "Pour cette quantité, Prok (" + Market.cfa(prokTotalCentimes) + ") est " + verdictWord(verdict) + " " + bundleLabel +
                " (" + Market.cfa(bundlePriceCentimes) + (if (prorated) " au prorata" else "") + "). " +
                (if (asOf.isNotEmpty()) "Offres du " + asOf + ". " else "") + DISCLAIMER +
                (if (verdict == "not cheaper") " Préférez le forfait de l'opérateur." else "")
    }

    class Example(val label: String, val bytes: Long, val totalCentimes: Long)

    class Quote(
        val quoteId: String, val signature: String, val configVersion: String, val sourceKind: String, val label: String,
        val payingParty: String, val rateCentimesPerMb: Long, val allowanceBytes: Long, val grossTotal: Long, val customerTotal: Long,
        val sponsorTotal: Long, val allInTotal: Long, val sourceShare: Long, val relayShare: Long, val platformShare: Long,
        val reserveShare: Long, val movementFee: Long, val sponsorCampaignId: String, val issuedAt: Long, val expiresAt: Long,
        val per100Customer: Long, val per100Source: Long, val per100Relay: Long, val per100Platform: Long, val per100Reserve: Long,
        val examples: List<Example>, val comparison: Comparison?, val nobodyUsesIt: String,
    ) {
        val free: Boolean get() = payingParty == "NONE"
        val sponsored: Boolean get() = payingParty == "SPONSOR"
        val relayPresent: Boolean get() = relayShare > 0
        /** Prok's part as the owner reads it: platform plus the reserve for support and collection. */
        val per100Prok: Long get() = per100Platform + per100Reserve
        val prokShare: Long get() = platformShare + reserveShare

        fun expired(now: Long): Boolean = expiresAt <= now

        /** The one line the contract asks for. */
        fun headline(): String = when {
            free -> "Gratuit : le client ne paie rien, personne ne gagne rien sur ce partage."
            sponsored -> "le client ne paie rien (sponsorisé) ; vous gagnez " + Market.cfa(per100Source) + " pour 100 Mo ; relais " +
                Market.cfa(per100Relay) + " ; Prok " + Market.cfa(per100Prok)
            else -> "le client paie " + Market.cfa(per100Customer) + " pour 100 Mo ; vous gagnez " + Market.cfa(per100Source) +
                " ; relais " + Market.cfa(per100Relay) + " ; Prok " + Market.cfa(per100Prok)
        }

        fun examplesLine(): String = if (free || examples.isEmpty()) "" else
            "Exemples : " + examples.joinToString(" · ") { it.label + " = " + (if (sponsored) "0.00 CFA (sponsorisé)" else Market.cfa(it.totalCentimes)) }

        fun splitLine(): String = if (free) "" else
            "Sur " + sizeWord(allowanceBytes) + " : vous " + Market.cfa(sourceShare) + " · relais " + Market.cfa(relayShare) +
                " · Prok " + Market.cfa(platformShare) + " · réserve " + Market.cfa(reserveShare) +
                (if (sponsored) " · payé par le sponsor : " + Market.cfa(sponsorTotal) else " · total client : " + Market.cfa(customerTotal))

        fun movementLine(): String = if (movementFee <= 0) "" else
            "Déplacement d'un relais : +" + Market.cfa(movementFee) + " fixes, inclus dans le total " +
                (if (sponsored) "du sponsor" else "client de " + Market.cfa(allInTotal)) + ". Aucun frais caché."

        fun expiryLine(now: Long): String {
            if (free) return ""
            val left = expiresAt - now
            return if (left <= 0) "Offre expirée — demandez une nouvelle offre." else "Offre valable encore " + ((left + 59_999) / 60_000) + " min."
        }

        fun verdictLine(): String = comparison?.text() ?: ""

        fun nobodyLine(): String = nobodyUsesIt.ifEmpty { "Si personne ne se connecte, rien n'est facturé et rien n'est gagné." }
    }

    // ---- parsing -------------------------------------------------------------------------

    fun parse(text: String): Quote? {
        if (text.isBlank() || !text.contains("\"quote_id\"") || !text.contains("\"signature\"")) return null
        val n = { k: String -> LedgerView.num(text, k) }
        val examples = LedgerView.objects(text, "examples").map { e ->
            Example(BrainPayload.field(e, "label"), LedgerView.num(e, "example_bytes"), LedgerView.num(e, "example_total"))
        }
        val c = LedgerView.obj(text, "comparison")?.let { s ->
            Comparison(BrainPayload.field(s, "verdict"), BrainPayload.field(s, "bundle_label"), LedgerView.num(s, "bundle_price_centimes"),
                LedgerView.num(s, "prok_total_centimes"), BrainPayload.field(s, "as_of"), BrainPayload.field(s, "prorated") == "true",
                BrainPayload.field(s, "sentence"))
        }
        return Quote(
            quoteId = BrainPayload.field(text, "quote_id"), signature = BrainPayload.field(text, "signature"),
            configVersion = BrainPayload.field(text, "config_version"), sourceKind = BrainPayload.field(text, "source_kind"),
            label = BrainPayload.field(text, "label"), payingParty = BrainPayload.field(text, "paying_party"),
            rateCentimesPerMb = n("rate_centimes_per_mb"), allowanceBytes = n("allowance_bytes"), grossTotal = n("gross_total"),
            customerTotal = n("customer_total"), sponsorTotal = n("sponsor_total"), allInTotal = n("all_in_total"),
            sourceShare = n("source_share"), relayShare = n("relay_share"), platformShare = n("platform_share"), reserveShare = n("reserve_share"),
            movementFee = n("movement_fee"), sponsorCampaignId = BrainPayload.field(text, "sponsor_campaign_id"),
            issuedAt = n("issued_at"), expiresAt = n("expires_at"), per100Customer = n("per_100mb_customer"), per100Source = n("per_100mb_source"),
            per100Relay = n("per_100mb_relay"), per100Platform = n("per_100mb_platform"), per100Reserve = n("per_100mb_reserve"),
            examples = examples, comparison = c, nobodyUsesIt = BrainPayload.field(text, "nobody_uses_it"),
        )
    }

    // ---- the decision: accept or decline THAT offer, nothing typed -------------------------------

    enum class Decision { ACCEPT, DECLINE }

    /** The whole of what the owner may send back about a quote. No amount, no rate, no tariff. */
    class Acceptance(val quoteId: String, val signature: String, val decision: Decision, val role: String) {
        fun pairs(): List<Pair<String, Any>> =
            listOf("quote_id" to quoteId, "signature" to signature, "role" to role, "decision" to decision.name)
    }

    fun decide(q: Quote, accept: Boolean, role: String = "OWNER"): Acceptance =
        Acceptance(q.quoteId, q.signature, if (accept) Decision.ACCEPT else Decision.DECLINE, role)

    // ---- onboarding: the steps before a quote may be asked for ----------------------------------

    /** free / earn / sponsored - the owner's only pricing-related choice (§4). */
    enum class Choice { FREE, EARN, SPONSORED }

    fun sourceKindFor(choice: Choice): String = if (choice == Choice.FREE) "FREE" else "APPROVED_PAID"

    fun choiceWord(choice: Choice): String = when (choice) {
        Choice.FREE -> "Gratuit : je partage sans rien gagner"
        Choice.EARN -> "Gagner sur l'Internet livré (ProkNet fixe le prix et votre part)"
        Choice.SPONSORED -> "Sponsorisé : gratuit pour le client, un sponsor paie"
    }

    /** The attestation the owner ticks. Owning the router or knowing the password is not enough (§4, §10.5). */
    const val ATTESTATION_SENTENCE = "J'atteste que le contrat de mon fournisseur d'accès autorise ce partage avec des tiers. " +
        "Un abonnement résidentiel qui interdit le partage hors du foyer (par exemple les conditions Canalbox) disqualifie la ligne : " +
        "elle ne peut pas être listée, même gratuitement."

    class Checks(val controlsNetwork: Boolean, val upstreamDescription: String, val validated: Boolean, val attested: Boolean, val choice: Choice?) {
        val canRequestQuote: Boolean get() = controlsNetwork && validated && attested && choice != null
        /** The first thing missing, in the owner's words, or "". */
        fun blockingReason(): String = when {
            !controlsNetwork -> "Connectez d'abord ce téléphone au réseau que vous contrôlez (le partage est désactivé)."
            !validated -> "Internet non validé sur ce réseau (" + upstreamDescription + ") : impossible de proposer une offre."
            !attested -> "Cochez l'attestation sur les conditions de votre fournisseur."
            choice == null -> "Choisissez : gratuit, gagner ou sponsorisé."
            else -> ""
        }
        fun checksLines(): String =
            "Réseau : " + (if (controlsNetwork) "partage activé sur " + upstreamDescription else "partage désactivé") +
                "\nInternet : " + (if (validated) "validé par Android" else "NON validé") +
                "\nDébit et capacité : non mesurés par cette version — la première session réelle le dira (aucune promesse de vitesse)."
    }
}
