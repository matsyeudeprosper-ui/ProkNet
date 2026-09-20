package net.prok.proknet.core

/**
 * v0.14: the money, decided internally so nobody has to think in megabytes.
 *
 *   Buyer:  "I have 50 CFA. Get me Internet."
 *   Seller: "Share my Internet and make me money."
 *   ProkNet: does the ugly arithmetic.
 *
 * Everything here is integer centimes (1 CFA = 100 centimes) and centimes per
 * MB. No floating point ever touches money. The engine answers one question —
 * *is there a deal that is good for both sides, and what is its exact internal
 * rate?* — and refuses rather than invent one.
 *
 * The accounting identity every quote satisfies:
 *
 *     buyer charge  =  Prok fee  +  the seller's source cost  +  the seller's profit
 *
 * A COMMERCIAL session may never exceed the buyer's budget and may never leave
 * the seller out of pocket. Losing money is possible only when someone
 * explicitly pays for it: SPONSORED or GROWTH_SUBSIDY.
 */
object Pricing {

    /** Where the Internet comes from, and therefore what it costs its owner. */
    enum class SourceKind { FREE_PUBLIC, AUTHORIZED_HOME_WIFI, AUTHORIZED_SHOP_WIFI, MOBILE_DATA, SPONSORED, PROK_FUNDED, UNKNOWN }

    /** What the seller wants out of it. The normal setting is BALANCED ("Automatique"). */
    enum class SellerPolicy { CHEAPER, BALANCED, EARN_MORE }

    /** Who actually pays the bill. */
    enum class Payer { BUYER, SPONSOR, PROK }

    const val MB = Market.MB
    const val PRICING_MODE_LEGACY = 0
    const val PRICING_MODE_BUDGET = 1

    /**
     * Every number the engine is allowed to invent, in one place, so they can be
     * tuned and tested instead of being scattered as magic constants.
     */
    data class Policy(
        /** What the seller wants to earn per MB when the source itself costs nothing. */
        val earningCentimesPerMb: Map<SellerPolicy, Int> = mapOf(
            SellerPolicy.CHEAPER to 100,        // 1 CFA / MB
            SellerPolicy.BALANCED to 200,       // 2 CFA / MB
            SellerPolicy.EARN_MORE to 400,      // 4 CFA / MB
        ),
        /** Extra profit as a share of what the source costs, so expensive data earns proportionally more. */
        val marginPermille: Map<SellerPolicy, Int> = mapOf(
            SellerPolicy.CHEAPER to 150, SellerPolicy.BALANCED to 300, SellerPolicy.EARN_MORE to 600,
        ),
        /** Overhead and safety on top of the raw source cost: retransmissions, probes, measurement error. */
        val safetyPermille: Int = 100,
        /** When a mobile seller has not said what their bundle costs, assume this rather than assume zero. */
        val unknownMobileCostCentimesPerMb: Int = 100,
        /** A session below this is not worth a contract. */
        val minSessionCentimes: Long = 100,
        /** The buyer must be able to afford at least this much data, or there is no deal. */
        val minUsefulMb: Int = 1,
        val feePct: Int = Market.DEFAULT_FEE_PCT,
        val maxRateCentimesPerMb: Int = 100_000,
    ) {
        fun earning(p: SellerPolicy): Int = earningCentimesPerMb[p] ?: 200
        fun margin(p: SellerPolicy): Int = marginPermille[p] ?: 300
    }

    val DEFAULT = Policy()

    /** Budgets a normal person recognises. */
    val BUDGET_CHOICES_CENTIMES = listOf(2_500L, 5_000L, 10_000L)
    const val DEFAULT_BUDGET_CENTIMES = 5_000L

    // ---- what the source costs its owner ---------------------------------------------------------------

    /**
     * "J'ai payé 1 000 CFA pour 2 Go" -> centimes per MB, rounded up so the
     * seller is never under-compensated by rounding.
     */
    fun mobileCostPerMb(bundleCentimes: Long, bundleMb: Long): Int {
        if (bundleCentimes <= 0 || bundleMb <= 0) return -1
        return Math.min(((bundleCentimes + bundleMb - 1) / bundleMb), Int.MAX_VALUE.toLong()).toInt()
    }

    /** What one MB costs the person sharing it. -1 means "unknown", which is never treated as free. */
    fun sourceCostPerMb(kind: SourceKind, declaredCentimesPerMb: Int, policy: Policy = DEFAULT): Int = when (kind) {
        SourceKind.FREE_PUBLIC, SourceKind.SPONSORED, SourceKind.PROK_FUNDED -> 0
        // a fixed line: the marginal cost of one more MB really is about nothing, but the owner may say otherwise
        SourceKind.AUTHORIZED_HOME_WIFI, SourceKind.AUTHORIZED_SHOP_WIFI -> maxOf(0, declaredCentimesPerMb)
        SourceKind.MOBILE_DATA -> if (declaredCentimesPerMb >= 0) declaredCentimesPerMb else policy.unknownMobileCostCentimesPerMb
        SourceKind.UNKNOWN -> if (declaredCentimesPerMb >= 0) declaredCentimesPerMb else policy.unknownMobileCostCentimesPerMb
    }

    /** A source nobody is allowed to resell is not supply, whatever it costs. */
    fun sellable(kind: SourceKind): Boolean = kind != SourceKind.UNKNOWN

    data class Source(val kind: SourceKind, val declaredCostCentimesPerMb: Int = -1, val free: Boolean = false)

    // ---- the rate ---------------------------------------------------------------------------------------

    /** What the seller must keep, per MB, for the deal to be worth doing. */
    fun sellerFloorPerMb(source: Source, sellerPolicy: SellerPolicy, policy: Policy = DEFAULT): Int {
        val cost = sourceCostPerMb(source.kind, source.declaredCostCentimesPerMb, policy)
        val safety = cost * policy.safetyPermille / 1000
        val proportional = (cost + safety) * policy.margin(sellerPolicy) / 1000
        val earning = maxOf(policy.earning(sellerPolicy), proportional)
        return cost + safety + earning
    }

    /**
     * The buyer's rate: enough that after the Prok fee the seller still keeps
     * its floor. Rounded up, so the fee can never eat into the seller's floor.
     */
    fun rateForFloor(sellerNetPerMb: Int, policy: Policy = DEFAULT): Int {
        val denom = 100 - policy.feePct
        if (denom <= 0) return policy.maxRateCentimesPerMb
        return Math.min(((sellerNetPerMb.toLong() * 100 + denom - 1) / denom), policy.maxRateCentimesPerMb.toLong()).toInt()
    }

    /** How many bytes a budget buys at a rate. Free Internet is not limited by money. */
    fun bytesForBudget(budgetCentimes: Long, rateCentimesPerMb: Int): Long =
        if (rateCentimesPerMb <= 0) Market.MAX_MB_PER_SESSION.toLong() * MB
        else Math.max(0L, budgetCentimes * MB / rateCentimesPerMb)

    /** What [bytes] cost at this rate, rounded half up, exactly like the rest of the ledger. */
    fun chargeFor(bytes: Long, rateCentimesPerMb: Int): Long {
        if (rateCentimesPerMb <= 0 || bytes <= 0) return 0
        return (bytes * rateCentimesPerMb + MB / 2) / MB
    }

    // ---- the quote ----------------------------------------------------------------------------------------

    data class Quote(
        val admissible: Boolean,
        val reason: String,
        val rateCentimesPerMb: Int,
        val maxBillableBytes: Long,
        val budgetCentimes: Long,
        val sourceCostPerMb: Int,
        val sellerNetPerMb: Int,
        val costClass: Coverage.Kind,
        val payer: Payer,
        val free: Boolean,
        /** What the whole session would cost and pay if the budget were spent to the last centime. */
        val expectedGross: Long,
        val expectedFee: Long,
        val expectedSellerNet: Long,
        val expectedSourceCost: Long,
        val expectedSellerProfit: Long,
        val subsidyCentimes: Long,
    ) {
        val sellerProfitable: Boolean get() = expectedSellerProfit >= 0
    }

    private fun refuse(reason: String, budget: Long, costClass: Coverage.Kind) = Quote(
        false, reason, 0, 0, budget, 0, 0, costClass, Payer.BUYER, false, 0, 0, 0, 0, 0, 0)

    /**
     * The whole decision. [budgetCentimes] is a ceiling the buyer allows, never
     * an amount to be spent: a free source costs nothing whatever the budget says.
     */
    fun quote(
        budgetCentimes: Long,
        source: Source,
        sellerPolicy: SellerPolicy = SellerPolicy.BALANCED,
        costClass: Coverage.Kind = Coverage.Kind.COMMERCIAL,
        sponsorBudgetCentimes: Long = 0,
        policy: Policy = DEFAULT,
    ): Quote {
        if (!sellable(source.kind)) return refuse("this source may not be resold", budgetCentimes, costClass)
        val cost = sourceCostPerMb(source.kind, source.declaredCostCentimesPerMb, policy)

        // 1. Internet that costs nobody anything is free, whatever the buyer was prepared to spend.
        if (source.free || source.kind == SourceKind.FREE_PUBLIC) {
            val bytes = bytesForBudget(0, 0)
            return Quote(true, "free source: the buyer pays nothing", 0, bytes, budgetCentimes, 0, 0, costClass, Payer.BUYER, true,
                0, 0, 0, 0, 0, 0)
        }

        val floor = sellerFloorPerMb(source, sellerPolicy, policy)
        val rate = rateForFloor(floor, policy)

        // 2. Somebody else is paying: the buyer's budget is not the constraint, the sponsor's is.
        if (costClass == Coverage.Kind.SPONSORED) {
            if (sponsorBudgetCentimes < policy.minSessionCentimes) return refuse("the sponsor budget is too small for a session", budgetCentimes, costClass)
            val bytes = bytesForBudget(sponsorBudgetCentimes, rate)
            return economics(true, "sponsored: the sponsor pays, the buyer pays nothing", rate, bytes, 0, cost, floor, costClass, Payer.SPONSOR, policy, sponsorBudgetCentimes)
        }

        // 3. Prok deliberately accepting a loss, only inside an explicit budget.
        if (costClass == Coverage.Kind.GROWTH_SUBSIDY) {
            val affordable = bytesForBudget(budgetCentimes, rate)
            val needed = Math.max(0L, chargeFor(affordable, rate) - budgetCentimes)
            if (needed > sponsorBudgetCentimes) return refuse("the growth subsidy needed exceeds the budget", budgetCentimes, costClass)
            return economics(true, "growth subsidy accepted", rate, affordable, budgetCentimes, cost, floor, costClass, Payer.PROK, policy, budgetCentimes + sponsorBudgetCentimes)
        }

        // 4. A normal commercial session: it must fit the budget AND leave the seller better off.
        if (budgetCentimes < policy.minSessionCentimes)
            return refuse("budget below the minimum session (" + Market.cfa(policy.minSessionCentimes) + ")", budgetCentimes, costClass)
        val minUseful = chargeFor(policy.minUsefulMb.toLong() * MB, rate)
        if (minUseful > budgetCentimes)
            return refuse("no economically valid offer: " + policy.minUsefulMb + " MB costs " + Market.cfa(minUseful) + ", budget is " + Market.cfa(budgetCentimes), budgetCentimes, costClass)
        val bytes = bytesForBudget(budgetCentimes, rate)
        if (bytes <= 0) return refuse("the budget buys nothing at this price", budgetCentimes, costClass)
        return economics(true, "within budget and profitable for the provider", rate, bytes, budgetCentimes, cost, floor, costClass, Payer.BUYER, policy, 0)
    }

    /**
     * The buyer's view. It cannot see the seller's bundle, only the price it is
     * offered, so it checks the one thing that is its own business: does my
     * budget buy something useful at this price?
     */
    fun quoteForOffer(budgetCentimes: Long, rateCentimesPerMb: Int, policy: Policy = DEFAULT): Quote {
        if (rateCentimesPerMb <= 0)
            return Quote(true, "free source: the buyer pays nothing", 0, bytesForBudget(0, 0), budgetCentimes, 0, 0,
                Coverage.Kind.COMMERCIAL, Payer.BUYER, true, 0, 0, 0, 0, 0, 0)
        if (budgetCentimes < policy.minSessionCentimes)
            return refuse("budget below the minimum session (" + Market.cfa(policy.minSessionCentimes) + ")", budgetCentimes, Coverage.Kind.COMMERCIAL)
        val minUseful = chargeFor(policy.minUsefulMb.toLong() * MB, rateCentimesPerMb)
        if (minUseful > budgetCentimes)
            return refuse("no economically valid offer: " + policy.minUsefulMb + " MB costs " + Market.cfa(minUseful) + ", budget is " + Market.cfa(budgetCentimes), budgetCentimes, Coverage.Kind.COMMERCIAL)
        val bytes = bytesForBudget(budgetCentimes, rateCentimesPerMb)
        if (bytes <= 0) return refuse("the budget buys nothing at this price", budgetCentimes, Coverage.Kind.COMMERCIAL)
        val gross = chargeFor(bytes, rateCentimesPerMb)
        val split = Market.split(gross, policy.feePct)
        return Quote(true, "within budget at the offered price", rateCentimesPerMb, bytes, budgetCentimes, 0, rateCentimesPerMb - (rateCentimesPerMb * policy.feePct / 100),
            Coverage.Kind.COMMERCIAL, Payer.BUYER, false, gross, split.fee, split.sellerNet, 0, split.sellerNet, 0)
    }

    /** What the seller puts on the air: whole CFA, rounded UP so the price never dips under the floor. */
    fun advertisedPriceCfa(rateCentimesPerMb: Int): Int = ((rateCentimesPerMb + 99) / 100)

    /** The seller's automatic price, from its source and what it wants to earn. */
    fun autoRate(source: Source, sellerPolicy: SellerPolicy, policy: Policy = DEFAULT): Int =
        if (source.free || source.kind == SourceKind.FREE_PUBLIC) 0 else rateForFloor(sellerFloorPerMb(source, sellerPolicy, policy), policy)

    private fun economics(ok: Boolean, reason: String, rate: Int, bytes: Long, buyerBudget: Long, costPerMb: Int, floorPerMb: Int,
                          costClass: Coverage.Kind, payer: Payer, policy: Policy, sponsor: Long): Quote {
        val gross = chargeFor(bytes, rate)
        val split = Market.split(gross, policy.feePct)
        val sourceCost = chargeFor(bytes, costPerMb)
        val profit = split.sellerNet - sourceCost
        val subsidy = if (payer == Payer.BUYER) 0L else gross
        return Quote(ok, reason, rate, bytes, buyerBudget, costPerMb, floorPerMb, costClass, payer, false,
            gross, split.fee, split.sellerNet, sourceCost, profit, subsidy)
    }

    /** What actually happened, once the session is over: the buyer pays for what was used, never the whole budget. */
    data class Settlement(val bytes: Long, val gross: Long, val fee: Long, val sellerNet: Long, val sourceCost: Long, val sellerProfit: Long, val payer: Payer)

    fun settle(bytes: Long, q: Quote, policy: Policy = DEFAULT): Settlement {
        val capped = Math.min(bytes, q.maxBillableBytes)
        val gross = if (q.free) 0L else chargeFor(capped, q.rateCentimesPerMb)
        val split = Market.split(gross, policy.feePct)
        val sourceCost = chargeFor(capped, q.sourceCostPerMb)
        return Settlement(capped, gross, split.fee, split.sellerNet, sourceCost, split.sellerNet - sourceCost, q.payer)
    }

    // ---- what a person reads -------------------------------------------------------------------------------

    /** "50 CFA", never "5000 centimes". Whole CFA, because that is how people speak. */
    fun cfa(centimes: Long): String = ((centimes + 50) / 100).toString() + " CFA"

    fun budgetLine(centimes: Long): String = "Budget maximum : " + cfa(centimes)

    fun remainingWord(spentCentimes: Long, budgetCentimes: Long): String {
        val left = Math.max(0L, budgetCentimes - spentCentimes)
        return "Il vous reste environ " + cfa(left)
    }

    fun exhaustedWord(budgetCentimes: Long): String = "Votre budget de " + cfa(budgetCentimes) + " est utilisé."

    fun spentWord(spentCentimes: Long, budgetCentimes: Long): String =
        "Vous avez dépensé " + cfa(spentCentimes) + " sur votre budget de " + cfa(budgetCentimes)

    fun earnedWord(centimes: Long): String = "Vous avez gagné " + cfa(centimes)

    /** Warn once the budget is nearly gone, not before. */
    fun nearlyExhausted(spentCentimes: Long, budgetCentimes: Long): Boolean =
        budgetCentimes > 0 && spentCentimes * 100 >= budgetCentimes * 80

    fun sellerPolicyWord(p: SellerPolicy): String = when (p) {
        SellerPolicy.CHEAPER -> "Moins cher"
        SellerPolicy.BALANCED -> "Équilibré"
        SellerPolicy.EARN_MORE -> "Gagner plus"
    }

    /**
     * An estimate is shown only when it is honestly bounded: the whole budget at
     * this rate, from nothing to that. Otherwise the screen says nothing.
     */
    fun earningEstimate(q: Quote): String? {
        if (!q.admissible || q.free || q.expectedSellerNet <= 0) return null
        val low = q.expectedSellerNet / 4
        return "Gain estimé : environ " + cfa(low) + " à " + cfa(q.expectedSellerNet)
    }

    fun describe(q: Quote): String =
        "admission: " + (if (q.admissible) "PASS" else "REFUSED") + "\n" +
            "  reason: " + q.reason + "\n" +
            "  buyer budget: " + Market.cfa(q.budgetCentimes) + " | cost class: " + q.costClass + " | payer: " + q.payer + "\n" +
            "  internal rate: " + Market.cfa(q.rateCentimesPerMb.toLong()) + " / MB" + (if (q.free) " (free)" else "") + "\n" +
            "  max billable: " + q.maxBillableBytes + " bytes (" + Market.mb(q.maxBillableBytes) + ")\n" +
            "  seller source cost: " + Market.cfa(q.sourceCostPerMb.toLong()) + " / MB | seller floor: " + Market.cfa(q.sellerNetPerMb.toLong()) + " / MB\n" +
            "  at full budget: gross " + Market.cfa(q.expectedGross) + ", Prok fee " + Market.cfa(q.expectedFee) +
            ", seller net " + Market.cfa(q.expectedSellerNet) + ", source cost " + Market.cfa(q.expectedSourceCost) +
            ", seller profit " + Market.cfa(q.expectedSellerProfit) + (if (q.subsidyCentimes > 0) ", subsidy " + Market.cfa(q.subsidyCentimes) else "")
}
