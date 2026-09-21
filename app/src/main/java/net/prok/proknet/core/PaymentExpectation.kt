package net.prok.proknet.core

/**
 * v0.16.0: matching a real Mobile Money payment to a debt, without a transaction reference.
 *
 * The constraint that shapes everything here: **the buyer has no reference to give us.**
 * They hand cash to a kiosk, say a phone number, and walk away. The kiosk types nothing
 * into ProkNet because the kiosk has never heard of ProkNet. So the match has to be made
 * from what we do know:
 *
 *     which seller   +   which rail   +   the exact amount   +   a short time window
 *     +   the operator's own message arriving on the SELLER's phone
 *
 * Two deliberate non-solutions, both rejected:
 *
 * - **Do not add a few centimes to make each payment unique.** Charging somebody 51 CFA
 *   instead of 50 to fingerprint their transfer is taking their money to solve our
 *   engineering problem.
 * - **Do not ask for a reference.** It would work, and it would break the habit the whole
 *   design exists to preserve.
 *
 * Instead, ambiguity is prevented rather than resolved: one seller may not have two
 * outstanding expectations for the same amount on the same rail at the same time. Two
 * buyers owing 50 CFA each simply take their turn, seconds apart, and neither is charged
 * anything extra.
 */
object PaymentExpectation {

    /** How long a buyer has to reach a kiosk. Policy, not protocol. */
    const val DEFAULT_WINDOW_MS = 20 * 60 * 1000L

    /** A receipt may arrive slightly before the window opens; operators are not punctual. */
    const val EARLY_TOLERANCE_MS = 60_000L

    enum class State {
        /** Created, waiting for the operator message. */
        WAITING,

        /** Matched and cleared. */
        MATCHED,

        /** The window closed with nothing. The debt is untouched and may be retried. */
        EXPIRED,

        /** Something matched but not safely. A person decides. */
        NEEDS_REVIEW,

        /** The buyer abandoned it. */
        CANCELLED,
    }

    class Expectation(
        val paymentId: String,
        val buyerId: String,
        val sellerId: String,
        val rail: Settlement.Rail,
        /** The seller's number, hashed. The plain number is never needed for matching. */
        val destinationHash: String,
        val amountCentimes: Long,
        val createdAt: Long,
        val validFrom: Long,
        val expiresAt: Long,
        val includedSettlementIds: List<String>,
        val state: State = State.WAITING,
    ) {
        fun active(now: Long): Boolean =
            state == State.WAITING && now >= validFrom - EARLY_TOLERANCE_MS && now < expiresAt

        fun expired(now: Long): Boolean = state == State.WAITING && now >= expiresAt

        fun with(state: State) = Expectation(paymentId, buyerId, sellerId, rail, destinationHash,
            amountCentimes, createdAt, validFrom, expiresAt, includedSettlementIds, state)
    }

    /**
     * Deterministic, so the same attempt does not create two expectations and a receipt
     * delivered twice lands on the same one.
     */
    fun idFor(buyerId: String, sellerId: String, amountCentimes: Long, createdAt: Long): String =
        Crypto.sha256(("ProkNet-expectation-1|" + buyerId + "|" + sellerId + "|" + amountCentimes + "|" + createdAt)
            .toByteArray(Charsets.UTF_8)).toHex().substring(0, 32)

    fun destinationHash(rail: Settlement.Rail, msisdn: String): String =
        Crypto.sha256(("ProkNet-destination-1|" + rail.name + "|" + normalizeMsisdn(msisdn))
            .toByteArray(Charsets.UTF_8)).toHex()

    /** Numbers are written a dozen ways; compare them one way. */
    fun normalizeMsisdn(raw: String): String {
        val digits = raw.filter { it.isDigit() }
        // Congo-Brazzaville numbers are nine digits; a country prefix may or may not be there
        return if (digits.length > 9) digits.takeLast(9) else digits
    }

    // ---- creating one ---------------------------------------------------------------------------------

    enum class Refusal { NONE, NO_DESTINATION, NOTHING_OWED, AMOUNT_BUSY }

    class Creation(val expectation: Expectation?, val refusal: Refusal, val message: String = "") {
        val ok: Boolean get() = expectation != null
    }

    /**
     * @param active every expectation currently outstanding anywhere on this phone
     *
     * The ambiguity lock lives here: if this seller already has a live expectation for the
     * same amount on the same rail, a second one would make the operator message impossible
     * to attribute, so the buyer is asked to wait rather than being charged a different
     * amount.
     */
    fun create(
        buyerId: String, sellerId: String, rail: Settlement.Rail, destinationHash: String,
        amountCentimes: Long, settlementIds: List<String>, active: List<Expectation>,
        now: Long, windowMs: Long = DEFAULT_WINDOW_MS,
    ): Creation {
        if (destinationHash.isEmpty()) return Creation(null, Refusal.NO_DESTINATION,
            "Le fournisseur n'a pas encore indiqué où être payé.")
        if (amountCentimes <= 0 || settlementIds.isEmpty()) return Creation(null, Refusal.NOTHING_OWED, "Rien à payer")

        val clash = active.any {
            it.active(now) && it.sellerId == sellerId && it.rail == rail &&
                it.amountCentimes == amountCentimes && it.buyerId != buyerId
        }
        if (clash) return Creation(null, Refusal.AMOUNT_BUSY,
            "Un paiement de ce montant est déjà en cours pour ce fournisseur. Réessayez dans quelques minutes.")

        val id = idFor(buyerId, sellerId, amountCentimes, now)
        return Creation(Expectation(id, buyerId, sellerId, rail, destinationHash, amountCentimes,
            now, now, now + windowMs, settlementIds), Refusal.NONE)
    }

    // ---- matching a receipt ---------------------------------------------------------------------------

    enum class Match {
        /** Exactly one live expectation fits. Clear the debt. */
        ONE,

        /** Nothing fits. Keep waiting; the debt is untouched. */
        NONE,

        /** More than one fits. Never guess between them. */
        AMBIGUOUS,

        /** Something fits but the evidence was not good enough to act on. */
        WEAK_EVIDENCE,
    }

    class Outcome(val match: Match, val expectation: Expectation?, val reason: String)

    /**
     * @param observedCentimes what the operator's message said arrived
     * @param destinationHash  the seller number the message was about, when known
     *
     * The amount must be **exact**. Not "at least", not "within a few francs": a kiosk
     * sends what it is told, and a mismatch means this is somebody else's payment.
     */
    fun match(
        observedCentimes: Long, sellerId: String, rail: Settlement.Rail,
        destinationHash: String, observedAt: Long, active: List<Expectation>,
        parsed: ReceiptParser.Parsed,
    ): Outcome {
        if (!parsed.usable) return Outcome(Match.WEAK_EVIDENCE, null,
            "the message was not a confident credit: " + parsed.reason)

        val fits = active.filter {
            it.active(observedAt) &&
                it.sellerId == sellerId &&
                it.rail == rail &&
                it.amountCentimes == observedCentimes &&
                (destinationHash.isEmpty() || it.destinationHash == destinationHash)
        }
        return when (fits.size) {
            0 -> Outcome(Match.NONE, null, "no live expectation for " + Market.cfa(observedCentimes))
            1 -> Outcome(Match.ONE, fits.first(), "exactly one expectation fits")
            else -> Outcome(Match.AMBIGUOUS, null, fits.size.toString() + " expectations fit; refusing to guess")
        }
    }

    /** Close everything whose window has passed. The debt survives; only the attempt ends. */
    fun sweep(all: List<Expectation>, now: Long): List<Expectation> =
        all.map { if (it.expired(now)) it.with(State.EXPIRED) else it }

    // ---- what the buyer reads -------------------------------------------------------------------------

    /**
     * The instruction. Deliberately does not say "press when you have paid": there is no
     * such button, because a buyer saying they paid is not evidence of anything.
     */
    fun instruction(amountCentimes: Long, railName: String, maskedNumber: String): String =
        "Envoyez exactement " + Market.cfa(amountCentimes) + " à :\n" +
            railName + "\n" + maskedNumber + "\n\n" +
            "Vous pouvez utiliser votre méthode Mobile Money habituelle ou un kiosque.\n\n" +
            "ProkNet détectera automatiquement la réception."

    fun waitingLine(amountCentimes: Long): String =
        "En attente du paiement\n" + Market.cfa(amountCentimes) +
            "\n\nNous détecterons automatiquement la réception."

    /**
     * v0.16.1: what to say while the seller has not yet confirmed it is watching.
     *
     * Sending somebody to a kiosk before the seller knows a payment is coming would waste
     * their trip, so the screen is honest about the difference.
     */
    fun preparingLine(amountCentimes: Long): String =
        "Préparation du paiement…\n" + Market.cfa(amountCentimes) +
            "\n\nNous prévenons le fournisseur. Gardez les téléphones proches un instant."

    /** Spaced for reading aloud at a kiosk, and still masked. */
    fun maskedNumber(msisdn: String): String {
        val d = normalizeMsisdn(msisdn)
        if (d.length < 4) return "•••"
        val tail = d.takeLast(4)
        return "•• •• " + tail.substring(0, 2) + " " + tail.substring(2)
    }
}
