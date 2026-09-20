package net.prok.proknet.core

/**
 * v0.15.0: the obligation wallet.
 *
 * This is **not** stored value. Prok holds nobody's money, so the wallet never shows a
 * balance. It shows what you owe, what you are owed, and what has actually been paid.
 * The wording is the product decision: "À payer", "À recevoir", "Payé", "Reçu",
 * "Gagné" — never "Solde", because a balance would be a promise Prok cannot keep.
 *
 * Pure. The store supplies the obligations; everything here is arithmetic on them.
 */
object Wallet {

    /** A day boundary in the phone's own time, for the "aujourd'hui" figures. */
    fun startOfDay(now: Long, tzOffsetMs: Long = 3600_000L): Long {
        val local = now + tzOffsetMs
        return local - (local % (24L * 3600 * 1000)) - tzOffsetMs
    }

    /**
     * What one person's wallet says. Every figure is an obligation, never a holding.
     */
    class View(
        /** Unsettled obligations where I am the buyer. */
        val toPayCentimes: Long,
        /** Unsettled obligations where I am the seller. */
        val toReceiveCentimes: Long,
        /** Confirmed today, as buyer. */
        val paidTodayCentimes: Long,
        /** Confirmed today, as seller. */
        val receivedTodayCentimes: Long,
        /** Everything I have earned as a seller, confirmed or not. */
        val earnedTodayCentimes: Long,
        val pendingCount: Int,
        val disputedCount: Int,
    ) {
        val owesSomething: Boolean get() = toPayCentimes > 0
        val isOwedSomething: Boolean get() = toReceiveCentimes > 0
    }

    fun view(obligations: List<Settlement.Obligation>, myId: String, now: Long): View {
        val dayStart = startOfDay(now)
        var toPay = 0L; var toReceive = 0L; var paid = 0L; var received = 0L; var earned = 0L
        var pending = 0; var disputed = 0
        for (o in obligations) {
            val iAmBuyer = o.buyerId == myId
            val iAmSeller = o.sellerId == myId
            if (!iAmBuyer && !iAmSeller) continue
            if (o.status == Settlement.Status.DISPUTED) { disputed++; continue }
            val outstanding = Settlement.isOutstanding(o.status)
            if (outstanding) pending++
            if (iAmBuyer) {
                if (outstanding) toPay += o.buyerOwes
                if (Settlement.isPaid(o.status) && o.createdAt >= dayStart) paid += o.buyerOwes
            }
            if (iAmSeller) {
                if (outstanding) toReceive += o.sellerReceivable
                if (Settlement.isPaid(o.status) && o.createdAt >= dayStart) received += o.sellerReceivable
                if (o.createdAt >= dayStart && o.status != Settlement.Status.FAILED && o.status != Settlement.Status.EXPIRED)
                    earned += o.sellerReceivable
            }
        }
        return View(toPay, toReceive, paid, received, earned, pending, disputed)
    }

    /**
     * What this buyer owes one particular seller, netted.
     *
     * Paying 2 CFA through Mobile Money after every short session would be absurd, so
     * obligations to the same seller accumulate and are settled together once they are
     * worth settling.
     */
    fun netOwedTo(obligations: List<Settlement.Obligation>, myId: String, sellerId: String): Long =
        obligations.filter { it.buyerId == myId && it.sellerId == sellerId && Settlement.isOutstanding(it.status) }
            .sumOf { it.buyerOwes }

    /** Everything this buyer owes anybody. This is the figure the credit limit works on. */
    fun totalOwed(obligations: List<Settlement.Obligation>, myId: String): Long =
        obligations.filter { it.buyerId == myId && Settlement.isOutstanding(it.status) }.sumOf { it.buyerOwes }

    /** The obligations that would be paid off together by one payment to [sellerId]. */
    fun payableTo(obligations: List<Settlement.Obligation>, myId: String, sellerId: String): List<Settlement.Obligation> =
        obligations.filter { it.buyerId == myId && it.sellerId == sellerId && Settlement.isOutstanding(it.status) }
            .sortedBy { it.createdAt }

    // ---- the words a person reads -------------------------------------------------------------------

    fun toPayLine(c: Long): String = Market.cfa(c)
    fun toReceiveLine(c: Long): String = Market.cfa(c)

    /** The line under a finished session, for the buyer. */
    fun buyerSessionLine(c: Long): String =
        if (c <= 0) "Gratuit" else "Vous avez utilisé " + Market.cfa(c)

    /** The line under a finished session, for the seller. */
    fun sellerSessionLine(c: Long): String =
        if (c <= 0) "Rien à recevoir" else "Vous avez gagné " + Market.cfa(c)

    /**
     * What a seller may honestly be told about getting paid.
     *
     * Prok does not hold funds, so nothing here may say "guaranteed". Saying a payment is
     * guaranteed when no money is held would be a straightforward lie to the person
     * taking the risk.
     */
    fun sellerAssurance(status: Settlement.Status, creditLimitCentimes: Long): String = when (status) {
        Settlement.Status.CONFIRMED -> "Reçu ✓"
        Settlement.Status.DISPUTED -> "Montant contesté, en cours de vérification"
        Settlement.Status.FAILED -> "Paiement échoué"
        Settlement.Status.EXPIRED -> "Délai de paiement dépassé"
        else -> "Paiement en attente · acheteur autorisé jusqu'à " + Market.cfa(creditLimitCentimes) + " de crédit"
    }

    /** The history line for one obligation, from [myId]'s point of view. */
    fun historyLine(o: Settlement.Obligation, myId: String): String {
        val seller = o.sellerId == myId
        val amount = if (seller) o.sellerReceivable else o.buyerOwes
        val sign = if (seller) "+" else "-"
        val what = if (seller) "Internet partagé" else "Internet"
        val state = when {
            Settlement.isPaid(o.status) -> if (seller) "Reçu ✓" else "Payé ✓"
            o.status == Settlement.Status.DISPUTED -> "Contesté"
            o.status == Settlement.Status.FAILED -> "Échoué"
            o.status == Settlement.Status.EXPIRED -> "Expiré"
            else -> "En attente"
        }
        return what + "  " + sign + Market.cfa(amount) + "  " + state
    }
}
