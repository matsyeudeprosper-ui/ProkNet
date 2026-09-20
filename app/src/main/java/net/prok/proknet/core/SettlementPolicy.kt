package net.prok.proknet.core

/**
 * v0.15.0: may this phone start a paid session right now, given what it already owes?
 *
 * Pure and deterministic, and asked **before** any Bluetooth channel, handshake or probe
 * is paid for. A buyer who has quietly run up unpaid sessions should be told to settle on
 * the home screen, not after a connection has been built.
 *
 * The balance this strikes: nobody wants to authorise a Mobile Money payment every time
 * they read one page, so small obligations accumulate; but "use, use, use, never pay" has
 * to stop somewhere, so they accumulate only up to a limit.
 *
 * Free Internet is never gated by any of this.
 */
object SettlementPolicy {

    /** Pilot default: about the price of a short session or two. Business policy, not protocol. */
    const val DEFAULT_CREDIT_LIMIT_CENTIMES = 5_000L

    /** Below this, offering to pay is more annoying than useful. */
    const val DEFAULT_SETTLE_THRESHOLD_CENTIMES = 2_500L

    class Policy(
        /** How much a buyer may owe before it must settle. */
        val creditLimitCentimes: Long = DEFAULT_CREDIT_LIMIT_CENTIMES,
        /** When to start suggesting payment, without blocking anything. */
        val settleThresholdCentimes: Long = DEFAULT_SETTLE_THRESHOLD_CENTIMES,
        /** A buyer with a confirmed payment behind it may be trusted a little further. */
        val goodHistoryBonusCentimes: Long = 2_500L,
        /** A disputed obligation stops paid sessions until a human has looked at it. */
        val blockOnDispute: Boolean = true,
    )

    val DEFAULT = Policy()

    enum class Decision {
        /** A paid session may start. */
        ALLOW_SESSION,

        /** Owes too much: settle first. Nothing expensive is set up. */
        REQUIRE_SETTLEMENT,

        /** The source costs nobody anything, so no obligation arises. Always allowed. */
        FREE_SESSION_ALLOWED,

        /** Somebody else is paying under their own policy. Allowed, creates no buyer debt. */
        SPONSORED_ALLOWED,

        /** A paid session must not start at all, and settling will not change that yet. */
        BLOCK_PAID_SESSION,
    }

    class Verdict(val decision: Decision, val reason: String, val mustSettleCentimes: Long = 0) {
        val mayStart: Boolean get() = decision != Decision.REQUIRE_SETTLEMENT && decision != Decision.BLOCK_PAID_SESSION
    }

    /**
     * @param outstandingCentimes everything this buyer owes and has not had confirmed
     * @param sessionEstimateCentimes the most this session could cost, i.e. the signed budget
     * @param confirmedPayments how many obligations this buyer has actually had confirmed
     * @param railAvailable whether any way to pay exists on this phone
     */
    fun admit(
        free: Boolean,
        costClass: Coverage.Kind,
        outstandingCentimes: Long,
        sessionEstimateCentimes: Long,
        disputedCount: Int,
        confirmedPayments: Int,
        railAvailable: Boolean,
        policy: Policy = DEFAULT,
    ): Verdict {
        if (free) return Verdict(Decision.FREE_SESSION_ALLOWED, "free source: nothing is owed")
        // v0.15: a v2 contract does not sign a payer, so a sponsored session creates no
        // payable obligation. It still runs; it simply does not count against the buyer.
        if (costClass == Coverage.Kind.SPONSORED || costClass == Coverage.Kind.GROWTH_SUBSIDY)
            return Verdict(Decision.SPONSORED_ALLOWED, "somebody else is paying under their own policy")

        if (policy.blockOnDispute && disputedCount > 0)
            return Verdict(Decision.BLOCK_PAID_SESSION, "a previous session is being checked", outstandingCentimes)

        val limit = policy.creditLimitCentimes + (if (confirmedPayments > 0) policy.goodHistoryBonusCentimes else 0L)
        if (outstandingCentimes >= limit) {
            if (!railAvailable) return Verdict(Decision.BLOCK_PAID_SESSION, "no way to pay is set up yet", outstandingCentimes)
            return Verdict(Decision.REQUIRE_SETTLEMENT, "settle " + Market.cfa(outstandingCentimes) + " to continue", outstandingCentimes)
        }
        // the session could push us past the limit: allowed, because the budget is a
        // ceiling and most sessions cost a fraction of it, but the debt is what stops
        // the NEXT one
        return Verdict(Decision.ALLOW_SESSION, "within the credit limit")
    }

    /** Should the app offer to settle now, without blocking anything? */
    fun shouldOfferToSettle(outstandingCentimes: Long, policy: Policy = DEFAULT): Boolean =
        outstandingCentimes >= policy.settleThresholdCentimes

    /** The sentence a blocked buyer reads. */
    fun settleSentence(outstandingCentimes: Long): String =
        "Réglez " + Market.cfa(outstandingCentimes) + " pour continuer"

    /** The quiet reminder, when nothing is blocked. */
    fun reminderSentence(outstandingCentimes: Long): String =
        "Vous avez " + Market.cfa(outstandingCentimes) + " à payer"
}
