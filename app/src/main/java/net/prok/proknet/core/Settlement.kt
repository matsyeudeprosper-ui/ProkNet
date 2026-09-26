package net.prok.proknet.core

/**
 * v0.15.0: what a finished session actually owes, as an object both phones can derive
 * independently and neither can invent.
 *
 * This is an **obligation** layer, not a wallet holding money. ProkNet computes and
 * verifies what is owed; it does not take custody of anybody's cash. "À recevoir 37 CFA"
 * means somebody owes you 37 CFA, never that Prok is holding 37 CFA for you. The wording
 * throughout is chosen to keep that distinction impossible to blur.
 *
 * Everything here is integer centimes. No floating point comes near money.
 */
object Settlement {

    /** Domain separator, so a settlement id can never collide with another hash in the system. */
    private const val DOMAIN = "ProkNet-settlement-1"

    /** How far a settlement may be paid before it is stale and must be re-derived. */
    const val DEFAULT_TTL_MS = 30L * 24 * 3600 * 1000

    enum class Status {
        /** Derived from a signed session. Nobody has paid yet. */
        PENDING,

        /** The buyer started a payment on some rail. */
        PAYMENT_INITIATED,

        /** A reference exists but nothing has verified it yet. Not proof of payment. */
        PAYMENT_SEEN,

        /** Verified by the rail or the settlement service. This is the only "paid". */
        CONFIRMED,

        /** The rail rejected it, or verification proved it never happened. */
        FAILED,

        /** Past its window without being paid. */
        EXPIRED,

        /** The two phones reported different figures for the same session. */
        DISPUTED,
    }

    /** A status nothing further will change. */
    fun isFinal(s: Status): Boolean = s == Status.CONFIRMED || s == Status.FAILED || s == Status.EXPIRED

    /** Paid means verified. A buyer tapping "I paid" does not move anything here. */
    fun isPaid(s: Status): Boolean = s == Status.CONFIRMED

    /** What is still owed counts towards a buyer's debt. */
    fun isOutstanding(s: Status): Boolean =
        s == Status.PENDING || s == Status.PAYMENT_INITIATED || s == Status.PAYMENT_SEEN

    enum class Rail {
        /** Not chosen yet. */
        NONE,

        /** Congo-Brazzaville. Interface built, no production credentials in this build. */
        MTN_MOMO,

        /** Congo-Brazzaville. Interface built, no production credentials in this build. */
        AIRTEL_MONEY,

        /** The buyer pays outside the app and gives the reference; a human verifies it. */
        MANUAL_PILOT,

        /** Developer only. Never reachable in consumer mode. */
        MOCK,
    }

    /**
     * One session, one obligation.
     *
     * [finalCheckpointHash] is the anchor: the obligation exists only because both phones
     * signed that exact usage figure. Change the usage and the id changes, so a tampered
     * amount is a different obligation rather than a louder version of this one.
     */
    class Obligation(
        val settlementId: String,
        val sessionHex: String,
        val buyerId: String,
        val sellerId: String,
        val finalCheckpointHash: String,
        val grossCentimes: Long,
        val sellerNetCentimes: Long,
        val prokFeeCentimes: Long,
        val createdAt: Long,
        val expiresAt: Long,
        val status: Status = Status.PENDING,
        val rail: Rail = Rail.NONE,
        val paymentReference: String = "",
        val note: String = "",
    ) {
        /** The accounting identity. Every obligation must satisfy it or it is malformed. */
        val balanced: Boolean get() = grossCentimes == sellerNetCentimes + prokFeeCentimes

        val valid: Boolean
            get() = settlementId.isNotEmpty() && sessionHex.isNotEmpty() &&
                buyerId.isNotEmpty() && sellerId.isNotEmpty() && buyerId != sellerId &&
                finalCheckpointHash.isNotEmpty() &&
                grossCentimes > 0 && grossCentimes <= Market.MAX_BUDGET_CENTIMES &&
                sellerNetCentimes >= 0 && prokFeeCentimes >= 0 && balanced &&
                createdAt > 0 && expiresAt > createdAt

        fun expired(now: Long): Boolean = now >= expiresAt

        fun with(status: Status = this.status, rail: Rail = this.rail,
                 paymentReference: String = this.paymentReference, note: String = this.note): Obligation =
            Obligation(settlementId, sessionHex, buyerId, sellerId, finalCheckpointHash,
                grossCentimes, sellerNetCentimes, prokFeeCentimes, createdAt, expiresAt, status, rail, paymentReference, note)

        /** What the buyer owes. Identical to [sellerReceivable] plus the fee, by construction. */
        val buyerOwes: Long get() = grossCentimes

        /** What the seller is entitled to. */
        val sellerReceivable: Long get() = sellerNetCentimes
    }

    /**
     * The only way to make an obligation: from a v2 contract and the checkpoint BOTH
     * phones signed. The UI can never name an amount.
     *
     * Returns null when there is nothing to settle, which is a normal outcome:
     * - a free session (rate 0) owes nothing;
     * - a session with no mutually signed checkpoint owes nothing, because nobody may be
     *   billed for usage they never signed for;
     * - a legacy v1 session is out of scope for real money in v0.15.
     *
     * v0.15 deliberately covers **commercial v2 sessions only**. Contract v2 does not
     * sign a cost class or a payer, so a sponsored session cannot be proven to be one,
     * and real money must not rest on an unsigned claim. Sponsored and Prok-funded
     * sessions keep working; they simply create no payable obligation here.
     */
    fun fromSession(
        contract: Market.Contract,
        finalSigned: Market.Checkpoint?,
        now: Long,
        ttlMs: Long = DEFAULT_TTL_MS,
    ): Obligation? {
        // v0.18.0: version 2 and version 3 both settle; v1 never did
        if (!contract.budgetSession) return null
        if (contract.rateCentimesPerMb <= 0) return null          // free: nothing is owed
        val cp = finalSigned ?: return null                        // nothing mutually signed
        if (!cp.sessionId.contentEquals(contract.sessionId)) return null
        val gross = Market.finalCost(contract, cp)
        if (gross <= 0) return null
        if (gross > contract.buyerBudgetCentimes && contract.buyerBudgetCentimes > 0) return null
        val split = Market.split(gross, contract.feePct)
        val cpHash = Crypto.sha256(cp.encode()).toHex()
        return Obligation(
            settlementId = idFor(contract.sessionHex, contract.hash().toHex(), cpHash),
            sessionHex = contract.sessionHex,
            buyerId = contract.buyerId.toHex(),
            sellerId = contract.sellerId.toHex(),
            finalCheckpointHash = cpHash,
            grossCentimes = split.gross,
            sellerNetCentimes = split.sellerNet,
            prokFeeCentimes = split.fee,
            createdAt = now,
            expiresAt = now + ttlMs,
        )
    }

    /**
     * Deterministic, so the same session can never produce two payment requests. Derived
     * only from signed facts: re-deriving it after a restart, on the other phone, or on
     * the server gives the same string.
     */
    fun idFor(sessionHex: String, contractHash: String, checkpointHash: String): String =
        Crypto.sha256((DOMAIN + "|" + sessionHex + "|" + contractHash + "|" + checkpointHash).toByteArray(Charsets.UTF_8))
            .toHex().substring(0, 32)

    /**
     * Do two independently derived obligations describe the same fact?
     *
     * The buyer and the seller each build one from their own copy of the signed session.
     * If they match, the obligation is trustworthy without anybody trusting anybody. If
     * they do not, it is DISPUTED and **the larger figure is never charged**.
     */
    fun agree(a: Obligation, b: Obligation): Boolean =
        a.settlementId == b.settlementId && a.sessionHex == b.sessionHex &&
            a.buyerId == b.buyerId && a.sellerId == b.sellerId &&
            a.finalCheckpointHash == b.finalCheckpointHash &&
            a.grossCentimes == b.grossCentimes && a.sellerNetCentimes == b.sellerNetCentimes &&
            a.prokFeeCentimes == b.prokFeeCentimes

    /** Reconciliation of two reports of the same session. */
    fun reconcile(a: Obligation, b: Obligation): Obligation =
        if (agree(a, b)) a
        else a.with(status = Status.DISPUTED, note = "buyer " + Market.cfa(a.grossCentimes) + " vs seller " + Market.cfa(b.grossCentimes))

    /**
     * A payment event may arrive twice: a retried webhook, a resent reference, a sync
     * after a restart. Applying it must be idempotent, and a confirmed obligation may
     * never be moved backwards or re-confirmed with a different reference.
     */
    fun applyPayment(o: Obligation, status: Status, rail: Rail, reference: String, now: Long): Obligation = when {
        o.status == Status.CONFIRMED && status == Status.CONFIRMED ->
            if (o.paymentReference == reference || reference.isEmpty()) o
            else o.with(status = Status.DISPUTED, note = "a second reference for an already confirmed payment")
        o.status == Status.CONFIRMED -> o                              // nothing moves a confirmed payment
        o.status == Status.DISPUTED -> o                               // a human decides this one
        o.expired(now) && status != Status.CONFIRMED -> o.with(status = Status.EXPIRED)
        else -> o.with(status = status, rail = rail, paymentReference = reference)
    }
}
